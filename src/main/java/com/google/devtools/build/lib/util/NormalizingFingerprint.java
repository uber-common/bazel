// Copyright 2025 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.util;

import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * A Fingerprint wrapper that normalizes paths for platform-independent actions.
 *
 * <p>This wrapper intercepts string additions to the fingerprint and normalizes any paths
 * containing platform-specific configuration directories. This ensures that platform-independent
 * actions (e.g., Java/Kotlin compilation) produce the same action key regardless of the target
 * platform configuration.
 *
 * <p>For example, paths like:
 * <ul>
 *   <li>bazel-out/arm64-v8a-fastbuild-android/bin/foo.jar
 *   <li>bazel-out/xplat-fastbuild/bin/foo.jar
 * </ul>
 * are both normalized to:
 * <ul>
 *   <li>bazel-out/NORMALIZED/bin/foo.jar
 * </ul>
 */
public class NormalizingFingerprint extends Fingerprint {
  /**
   * Pattern to match platform-specific configuration directories in bazel-out paths.
   *
   * <p>Examples of paths this matches:
   * <ul>
   *   <li>bazel-out/arm64-v8a-fastbuild-android-ST-02e23770d8ba/bin/...
   *   <li>bazel-out/darwin_arm64-opt-exec-ST-fad1763555eb/bin/...
   *   <li>bazel-out/xplat-fastbuild/bin/...
   * </ul>
   *
   * <p>The configuration part (between "bazel-out/" and "/bin" or "/genfiles") contains
   * platform-specific information that should be normalized for platform-independent actions.
   */
  private static final Pattern BAZEL_OUT_PATTERN =
      Pattern.compile("bazel-out/([^/]+)/(bin|genfiles|testlogs)");

  private final Fingerprint delegate;

  /**
   * Creates a new NormalizingFingerprint that wraps the given delegate.
   *
   * @param delegate the underlying Fingerprint to delegate to after normalization
   */
  public NormalizingFingerprint(Fingerprint delegate) {
    this.delegate = delegate;
  }

  /**
   * Normalizes paths by replacing platform-specific configuration directories with "NORMALIZED".
   *
   * <p>For platform-independent actions (e.g., Javac, KotlinCompile), the same source code
   * compiled with the same flags should produce the same output regardless of the target platform.
   * However, the action's command arguments and inputs contain paths that include
   * platform-specific configuration directories (e.g., "arm64-v8a-fastbuild-android" vs
   * "xplat-fastbuild").
   *
   * <p>This method normalizes such paths by replacing the platform-specific segment with a
   * constant "NORMALIZED" token, ensuring that the action key remains identical across different
   * platform configurations.
   *
   * <p>Examples:
   * <ul>
   *   <li>bazel-out/arm64-v8a-fastbuild-android/bin/foo.jar
   *       → bazel-out/NORMALIZED/bin/foo.jar
   *   <li>bazel-out/xplat-fastbuild/bin/foo.jar
   *       → bazel-out/NORMALIZED/bin/foo.jar
   * </ul>
   *
   * @param path The path string to normalize
   * @return The normalized path, or the original path if no normalization is needed
   */
  private static String normalizePath(String path) {
    Matcher matcher = BAZEL_OUT_PATTERN.matcher(path);
    return matcher.replaceAll("bazel-out/NORMALIZED/$2");
  }

  @Override
  public Fingerprint addString(String s) {
    // Normalize any paths in the string before adding to fingerprint
    String normalized = normalizePath(s);
    delegate.addString(normalized);
    return this;
  }

  @Override
  public Fingerprint addNullableString(@Nullable String s) {
    if (s != null) {
      delegate.addString(normalizePath(s));
    } else {
      delegate.addNullableString(null);
    }
    return this;
  }

  @Override
  public Fingerprint addStrings(Collection<String> strings) {
    for (String s : strings) {
      addString(s);
    }
    return this;
  }

  @Override
  public Fingerprint addIterableStrings(Iterable<String> strings) {
    for (String s : strings) {
      addString(s);
    }
    return this;
  }

  @Override
  public Fingerprint addPath(PathFragment pathFragment) {
    // Normalize the path by converting to string, normalizing, and adding as string
    delegate.addString(normalizePath(pathFragment.getPathString()));
    return this;
  }

  @Override
  public Fingerprint addPath(com.google.devtools.build.lib.vfs.Path path) {
    // Normalize the path by converting to string, normalizing, and adding as string
    delegate.addString(normalizePath(path.getPathString()));
    return this;
  }

  @Override
  public Fingerprint addPaths(Collection<PathFragment> pathFragments) {
    for (PathFragment pathFragment : pathFragments) {
      addPath(pathFragment);
    }
    return this;
  }

  @Override
  public Fingerprint addStringMap(Map<String, String> map) {
    // For maps, normalize both keys and values
    for (Map.Entry<String, String> entry : map.entrySet()) {
      addString(entry.getKey());
      addString(entry.getValue());
    }
    return this;
  }

  // Delegate all other methods to the underlying fingerprint

  @Override
  public Fingerprint addBoolean(boolean b) {
    delegate.addBoolean(b);
    return this;
  }

  @Override
  public Fingerprint addInt(int i) {
    delegate.addInt(i);
    return this;
  }

  @Override
  public Fingerprint addLong(long l) {
    delegate.addLong(l);
    return this;
  }

  @Override
  public Fingerprint addUUID(UUID uuid) {
    delegate.addUUID(uuid);
    return this;
  }

  @Override
  public Fingerprint addBytes(byte[] bytes) {
    delegate.addBytes(bytes);
    return this;
  }

  @Override
  public Fingerprint addBytes(byte[] bytes, int offset, int length) {
    delegate.addBytes(bytes, offset, length);
    return this;
  }

  @Override
  public byte[] digestAndReset() {
    return delegate.digestAndReset();
  }

  @Override
  public String hexDigestAndReset() {
    return delegate.hexDigestAndReset();
  }
}
