// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.NormalizingFingerprint;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/**
 * An implementation of {@link ActionAnalysisMetadata} that caches its {@linkplain #getKey key} so
 * that it is only computed once.
 */
public abstract class ActionKeyCacher implements ActionAnalysisMetadata {

  /**
   * Integer embedded in every action key.
   *
   * <p>The purpose of this member and associated property is to allow to easily invalidate the
   * action cache in case we want to mitigate bugs resulting with false-sharing.
   */
  private static final int ACTION_KEY_UNIQUIFIER =
      Integer.parseInt(System.getProperty("ACTION_KEY_UNIQUIFIER", "0"));

  /**
   * Registry of action mnemonics that produce platform-independent outputs.
   *
   * <p>Java and Kotlin bytecode (.class files) are platform-independent - the JVM handles
   * platform differences at runtime, not at compile time. By excluding the execution platform
   * from the action key for these actions, we enable cache sharing between different
   * configurations (e.g., Android app vs Android test builds) that would otherwise compile
   * identical source code with different platform settings.
   *
   * <p><b>IMPORTANT - JNI Limitation:</b> This optimization assumes pure Java/Kotlin code. If
   * Java/Kotlin libraries include JNI (Java Native Interface) code or load native libraries
   * (.so, .dylib, .dll files), those native dependencies ARE platform-specific. However, the
   * native library compilation and linking actions (e.g., CppCompile, CppLink) still include
   * platform in their action keys, so platform-specific native code will be correctly
   * recompiled. Only the Java/Kotlin bytecode compilation is shared across platforms.
   *
   * <p><b>Additional Considerations:</b>
   * <ul>
   *   <li><b>Annotation Processors:</b> Ensure annotation processors are platform-independent.
   *       Most standard processors (Dagger, AutoValue, etc.) are safe, but custom processors
   *       that use System.getProperty("os.name") or similar may cause issues.</li>
   *   <li><b>JDK Consistency:</b> Different platforms should use the same JDK version. JDK
   *       version is usually captured in the java_toolchain configuration.</li>
   *   <li><b>Compiler Flags:</b> Platform-specific compiler flags will still result in different
   *       action keys (correct behavior), preventing inappropriate cache sharing.</li>
   * </ul>
   *
   * <p>This is controlled by the experimental flag:
   * --experimental_platform_independent_mnemonics=Javac,KotlinCompile,...
   *
   * <p>Set via {@link #setPlatformIndependentMnemonics} during build initialization.
   */
  private static volatile ImmutableSet<String> platformIndependentMnemonics = ImmutableSet.of();

  /**
   * Sets the list of action mnemonics that should be treated as platform-independent.
   *
   * <p>This is called during build initialization from the execution framework, based on the
   * --experimental_platform_independent_mnemonics flag.
   *
   * @param mnemonics List of mnemonics to treat as platform-independent (e.g., "Javac",
   *     "KotlinCompile")
   */
  public static void setPlatformIndependentMnemonics(java.util.List<String> mnemonics) {
    platformIndependentMnemonics = ImmutableSet.copyOf(mnemonics);
  }

  @Nullable private volatile String cachedKey = null;

  /**
   * Returns whether the execution platform should be included in the action key.
   *
   * <p>By default, returns true. Actions that produce platform-independent outputs (e.g.,
   * Java/Kotlin bytecode) can be excluded from platform-based cache key differentiation,
   * allowing their artifacts to be shared across different target platforms.
   *
   * <p>IMPORTANT: Only return false if you're certain the action's outputs are truly
   * platform-independent. Incorrect overrides can lead to cache correctness issues where
   * actions with different platforms incorrectly share artifacts.
   *
   * @return true if platform should affect the cache key, false otherwise
   */
  protected boolean shouldIncludePlatformInKey() {
    // Check if this action's mnemonic is in the platform-independent registry
    return !platformIndependentMnemonics.contains(getMnemonic());
  }

  @Override
  public final String getKey(
      ActionKeyContext actionKeyContext, @Nullable ArtifactExpander artifactExpander)
      throws InterruptedException {
    // Only cache the key when it is given all necessary information to compute a correct key.
    // Practically, most of the benefit of the cache comes from execution, which does provide the
    // artifactExpander.
    if (artifactExpander == null) {
      return computeActionKey(actionKeyContext, null);
    }

    if (cachedKey == null) {
      synchronized (this) {
        if (cachedKey == null) {
          cachedKey = computeActionKey(actionKeyContext, artifactExpander);
        }
      }
    }
    return cachedKey;
  }

  private String computeActionKey(
      ActionKeyContext actionKeyContext, @Nullable ArtifactExpander artifactExpander)
      throws InterruptedException {
    try {
      Fingerprint fp = new Fingerprint();

      // For platform-independent actions, wrap the fingerprint to normalize paths
      // This ensures that paths like "bazel-out/arm64-v8a-fastbuild-android/bin/foo.jar"
      // and "bazel-out/xplat-fastbuild/bin/foo.jar" are treated as identical
      boolean shouldNormalize = !shouldIncludePlatformInKey();
      Fingerprint normalizedFp = shouldNormalize
          ? new NormalizingFingerprint(fp)
          : fp;

      computeKey(actionKeyContext, artifactExpander, normalizedFp);

      // Only add platform information if the action requires it.
      // Platform-independent actions (Java/Kotlin) skip this to enable cross-configuration caching.
      if (shouldIncludePlatformInKey()) {
        // Add a bool indicating whether the execution platform was set.
        fp.addBoolean(getExecutionPlatform() != null);
        if (getExecutionPlatform() != null) {
          // Add the execution platform information.
          getExecutionPlatform().addTo(fp);
        }
      }

      fp.addStringMap(getExecProperties());
      fp.addInt(ACTION_KEY_UNIQUIFIER);
      // Compute the actual key and store it.
      return fp.hexDigestAndReset();
    } catch (CommandLineExpansionException | EvalException e) {
      return KEY_ERROR;
    }
  }

  /**
   * See the javadoc for {@link Action} and {@link ActionAnalysisMetadata#getKey} for the contract
   * of this method.
   *
   * <p>TODO(b/150305897): subtypes of this are not consistent about adding the UUID as stated in
   * the ActionAnalysisMetadata. Perhaps ActionKeyCacher should just mandate subclasses provide a
   * UUID and then add that UUID itself in getKey.
   */
  protected abstract void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, EvalException, InterruptedException;
}
