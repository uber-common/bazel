// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.hash.Hasher;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.vfs.DigestHashFunction;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

/**
 * Utility class for action input tracking.
 */
class Utils {

    /**
     * Compute sha256 of the jar entry corresponding to provided path.
     */
    static byte[] getHashFromJarEntry(Artifact artifact, String path) {
        try (JarFile jarFile = new JarFile(artifact.getPath().getPathFile())) {
            ZipEntry entry = jarFile.getEntry(path);
            if (entry == null) {
                return null;
            }

            try (InputStream stream = jarFile.getInputStream(entry)) {
                Hasher hasher = DigestHashFunction.SHA256.getHashFunction().newHasher();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    hasher.putBytes(buffer, 0, read);
                }
                return hasher.hash().asBytes();
            }
        } catch (IOException e) {
        }
        return new byte[0];
    }

    /**
     * Get action .jdeps artifact, used to extract compilation tracking information from.
     */
    @Nullable
    static Artifact getJDepsOutput(Action action) {
        List<Artifact> jdepsOutput = action.getOutputs().stream()
                .filter(output -> output.getExecPathString().endsWith(".jdeps"))
                .collect(Collectors.toList());
        return jdepsOutput.size() == 1 ? jdepsOutput.get(0) : null;
    }

    /**
     * Checks whether the resourceId is valid.
     */
    static boolean isResourceValid(String resId) {
        if (resId.indexOf("R.") != 0) {
            if (resId.indexOf("android.R.") != 0) {
                System.err.println("WARN: Malformed res: " + resId);
            }
            return false;
        }

        if (resId.lastIndexOf('.') == 1) {
            // Ignore bogus entry like R.attr, caused by 'import R.attr;'
            return false;
        }

        return true;
    }
}
