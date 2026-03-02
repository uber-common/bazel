#!/bin/bash
#
# Copyright 2025 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Test platform-independent action caching for Java/Kotlin compilation.
# This feature allows Java/Kotlin bytecode to be shared across different
# platform configurations (e.g., Android app vs Android test builds).
#

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

# Test that the experimental_platform_independent_mnemonics flag is recognized
function test_flag_is_recognized() {
  mkdir -p java/com/example
  cat > java/com/example/BUILD <<EOF
java_library(
    name = "lib",
    srcs = ["Lib.java"],
)
EOF

  cat > java/com/example/Lib.java <<EOF
package com.example;
public class Lib {
  public static String getMessage() {
    return "Hello from Lib";
  }
}
EOF

  # Test that the flag is recognized (doesn't cause an error)
  bazel build \
    --experimental_platform_independent_mnemonics=Javac \
    //java/com/example:lib \
    || fail "Flag experimental_platform_independent_mnemonics should be recognized"
}

# Test that Java compilation is cached across different configurations
function test_java_compilation_cache_sharing() {
  mkdir -p java/com/example
  cat > java/com/example/BUILD <<EOF
java_library(
    name = "lib",
    srcs = ["Lib.java"],
)
EOF

  cat > java/com/example/Lib.java <<EOF
package com.example;
public class Lib {
  public static String getMessage() {
    return "Hello from Lib";
  }
}
EOF

  # Clean to start fresh
  bazel clean

  # First build with one configuration
  bazel build \
    --experimental_platform_independent_mnemonics=Javac \
    //java/com/example:lib \
    || fail "First build failed"

  # Second build with different configuration (using --define to change config)
  # With the feature enabled, Java compilation should be cached
  bazel build \
    --experimental_platform_independent_mnemonics=Javac \
    --define=test_config=1 \
    //java/com/example:lib \
    || fail "Second build with different config failed"

  # TODO: Verify action cache was actually used by checking build output
  # or using --execution_log_json_file
}

# Test that multiple mnemonics can be specified
function test_multiple_mnemonics() {
  mkdir -p java/com/example
  cat > java/com/example/BUILD <<EOF
java_library(
    name = "lib",
    srcs = ["Lib.java"],
)
EOF

  cat > java/com/example/Lib.java <<EOF
package com.example;
public class Lib {}
EOF

  # Test that multiple mnemonics are accepted
  bazel build \
    --experimental_platform_independent_mnemonics=Javac,JavacTurbine,Turbine \
    //java/com/example:lib \
    || fail "Multiple mnemonics should be accepted"
}

# Test that empty list (default) works
function test_empty_mnemonics_default() {
  mkdir -p java/com/example
  cat > java/com/example/BUILD <<EOF
java_library(
    name = "lib",
    srcs = ["Lib.java"],
)
EOF

  cat > java/com/example/Lib.java <<EOF
package com.example;
public class Lib {}
EOF

  # Test that default (empty list) works - feature is disabled
  bazel build \
    //java/com/example:lib \
    || fail "Build without flag should work (feature disabled by default)"
}

# Test with .bazelrc configuration
function test_bazelrc_configuration() {
  mkdir -p java/com/example
  cat > java/com/example/BUILD <<EOF
java_library(
    name = "lib",
    srcs = ["Lib.java"],
)
EOF

  cat > java/com/example/Lib.java <<EOF
package com.example;
public class Lib {}
EOF

  # Create .bazelrc with the flag
  cat > .bazelrc <<EOF
build --experimental_platform_independent_mnemonics=Javac
EOF

  # Build should use the flag from .bazelrc
  bazel build //java/com/example:lib \
    || fail "Build with .bazelrc configuration should work"
}

run_suite "platform_independent_caching"
