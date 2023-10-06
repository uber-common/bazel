#!/bin/bash
#
# Copyright 2023 The Bazel Authors. All rights reserved.
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

# For these tests to run do the following:
#
#   1. Install an Android SDK from https://developer.android.com
#   2. Set the $ANDROID_HOME environment variable
#   3. Uncomment the line in WORKSPACE containing android_sdk_repository
#
# Note that if the environment is not set up as above android_integration_test
# will silently be ignored and will be shown as passing.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "${CURRENT_DIR}/android_helper.sh" \
  || { echo "android_helper.sh not found!" >&2; exit 1; }
fail_if_no_android_sdk

source "${CURRENT_DIR}/../../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

resolve_android_toolchains "$1"

function test_DexFileSplitter_synthetic_classes_crossing_dexfiles() {
  create_new_workspace
  setup_android_sdk_support

  mkdir -p java/com/testapp

  cat > java/com/testapp/AndroidManifest.xml <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.testapp"
    android:versionCode="1"
    android:versionName="1.0" >

    <uses-sdk
        android:minSdkVersion="30"
        android:targetSdkVersion="30" />

    <application android:label="Test App" >
        <activity
            android:name="com.testapp.MainActivity"
            android:label="App"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

  cat > java/com/testapp/MainActivity.java <<EOF
package com.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Log.i("tag", "info");
  }
}
EOF

  generate_java_file_with_many_synthetic_classes > java/com/testapp/BigLib.java

  cat > java/com/testapp/BUILD <<EOF
android_binary(
    name = "testapp",
    srcs = [
        "MainActivity.java",
        ":BigLib.java",
    ],
    manifest = "AndroidManifest.xml",
)
EOF

  bazel build java/com/testapp || fail "Test app should have built succesfully"

  dex_file_count=$(unzip -l bazel-bin/java/com/testapp/testapp.apk | grep "classes[0-9]*.dex" | wc -l)
  if [[ ! "$dex_file_count" -ge "2" ]]; then
    echo "Expected at least 2 dexes in app, found: $dex_file_count"
    exit 1
  fi
}


function generate_java_file_with_many_synthetic_classes() {
  echo "package com.testapp;"
  echo "public class BigLib {"

  # First generate enough inner classes to fill up most of the dex
  for (( i = 0; i < 21400; i++ )) do

    echo "  public static class Bar$i {"
    echo "    public int bar() {"
    echo "      return $i;"
    echo "    }"
    echo "  }"

  done

  # Then create enough synethetic classes via lambdas to fill up the rest of the
  # dex and into another dex file.
  echo "  public interface IntSupplier {"
  echo "    int supply();"
  echo "  }"

  echo "  public static class Foo {"
  echo "    public IntSupplier[] foo() {"
  echo "      return new IntSupplier[] {"

  for ((i = 0; i < 6000; i++ )) do
    echo "        () -> $i,"
  done

  echo "      };"
  echo "    }"
  echo "  }"
  echo "}"
}

run_suite "Tests for DexFileSplitter with synthetic classes crossing dexfiles"