// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.actions.usage;

import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Reads resource signatures without loading classes or running their static initializers. */
public final class AndroidResourceMetadata {
  private static final String CLASS_PREFIX = "class-shape:";

  private AndroidResourceMetadata() {}

  static boolean coversClass(Map<String, FileArtifactValue> metadata, ClassUsageInfo usedClass) {
    return metadata.containsKey(CLASS_PREFIX + usedClass.getFullyQualifiedName().replace('$', '.'));
  }

  public static Map<String, FileArtifactValue> read(Path jarPath, Set<String> resources)
      throws IOException {
    Map<String, String> requestedClasses = new HashMap<>();
    Map<String, Set<String>> resourceAliases = new HashMap<>();
    for (String reference : resources) {
      // javac emits source-spelled R.type.name; Kotlin emits the resolved qualified name.
      String resource = reference.startsWith("R.")
          ? ActionInputUsageTracker.ANDROID_RESOURCES_NAMESPACE + reference : reference;
      resourceAliases.computeIfAbsent(resource, unused -> new HashSet<>()).add(reference);
      int typeEnd = resource.lastIndexOf('.');
      int rStart = resource.lastIndexOf(".R.");
      if (rStart < 0 || typeEnd <= rStart + 3 || resource.endsWith(".*")) {
        // Incomplete/import-only metadata is not authoritative field provenance.
        throw new IOException("Unsupported resource reference: " + resource);
      }
      String owner = resource.substring(0, typeEnd);
      String entry = owner.substring(0, rStart).replace('.', '/')
          + "/R$" + owner.substring(rStart + 3) + ".class";
      requestedClasses.put(entry, owner);
    }

    Map<String, FileArtifactValue> result = new HashMap<>();
    // Random access to only the referenced classes avoids scanning a large merged R jar.
    // Close every handle; do not memoize by path/mtime, which misses same-mtime replacements.
    try (JarFile jar = new JarFile(jarPath.getPathFile())) {
      for (Map.Entry<String, String> requested : requestedClasses.entrySet()) {
        JarEntry entry = jar.getJarEntry(requested.getKey());
        if (entry == null) {
          continue;
        }
        String owner = requested.getValue();
        ClassReader reader;
        try (InputStream input = jar.getInputStream(entry)) {
          reader = new ClassReader(input);
        }
        if (!reader.getClassName().equals(entry.getName().replace(".class", ""))) {
          throw new IOException("Resource class name does not match jar entry");
        }
        Fingerprint shape = new Fingerprint();
        shape.addString("android-resource-class-v1");
        shape.addString(reader.getClassName());
        shape.addInt(reader.getAccess());
        shape.addInt(reader.readUnsignedShort(6));
        shape.addString(reader.getSuperName());
        shape.addStrings(java.util.Arrays.asList(reader.getInterfaces()));
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
          @Override
          public FieldVisitor visitField(
              int access, String name, String descriptor, String signature, Object value) {
            if ((!descriptor.equals("I") && !descriptor.equals("[I"))
                || (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                    != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                || (value != null && !(value instanceof Integer))) {
              throw new IllegalArgumentException("Not a generated resource field: " + owner + "." + name);
            }
            String resource = owner + "." + name;
            if (!resourceAliases.containsKey(resource)) {
              return null;
            }
            Fingerprint hash = new Fingerprint();
            hash.addString("android-resource-field-v1");
            hash.addString(descriptor);
            hash.addInt(access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL));
            hash.addBoolean(value != null);
            if (value != null) {
              // ConstantValue can be inlined by javac/kotlinc, including styleable indexes.
              hash.addInt((Integer) value);
            }
            FileArtifactValue metadata = new PreComputedMetadataValue(hash.digestAndReset());
            for (String reference : resourceAliases.get(resource)) {
              result.put(reference, metadata);
            }
            return null;
          }

          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            if (!name.equals("<init>") && !name.equals("<clinit>")) {
              throw new IllegalArgumentException("Not a generated resource class: " + owner);
            }
            shape.addString(name);
            shape.addInt(access);
            shape.addString(descriptor);
            shape.addString(signature == null ? "" : signature);
            shape.addStrings(exceptions == null ? java.util.Collections.emptyList()
                : java.util.Arrays.asList(exceptions));
            return null;
          }

          @Override
          public void visitInnerClass(String name, String outerName, String innerName, int access) {
            if (name.equals(reader.getClassName())) {
              shape.addInt(access);
            }
          }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        result.put(CLASS_PREFIX + owner, new PreComputedMetadataValue(shape.digestAndReset()));
      }
    }
    return result;
  }
}
