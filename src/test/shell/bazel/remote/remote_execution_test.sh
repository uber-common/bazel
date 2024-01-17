#!/bin/bash
#
# Copyright 2016 The Bazel Authors. All rights reserved.
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
# Tests remote execution and caching.

set -euo pipefail

# --- begin runfiles.bash initialization ---
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }
source "$(rlocation "io_bazel/src/test/shell/bazel/remote_helpers.sh")" \
  || { echo "remote_helpers.sh not found!" >&2; exit 1; }
source "$(rlocation "io_bazel/src/test/shell/bazel/remote/remote_utils.sh")" \
  || { echo "remote_utils.sh not found!" >&2; exit 1; }

function set_up() {
  start_worker
}

function tear_down() {
  bazel clean >& $TEST_log
  stop_worker
}

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

if "$is_windows"; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
  declare -r EXE_EXT=".exe"
else
  declare -r EXE_EXT=""
fi

function has_utf8_locale() {
  charmap="$(LC_ALL=en_US.UTF-8 locale charmap 2>/dev/null)"
  [[ "${charmap}" == "UTF-8" ]]
}

function setup_credential_helper_test() {
  setup_credential_helper

  mkdir -p a

  cat > a/BUILD <<'EOF'
[genrule(
  name = x,
  outs = [x + ".txt"],
  cmd = "touch $(OUTS)",
) for x in ["a", "b"]]
EOF

  stop_worker
  start_worker --expected_authorization_token=TOKEN
}

function test_credential_helper_remote_cache() {
  setup_credential_helper_test

  bazel build \
      --remote_cache=grpc://localhost:${worker_port} \
      //a:a >& $TEST_log && fail "Build without credentials should have failed"
  expect_log "Failed to query remote execution capabilities"

  # Helper shouldn't have been called yet.
  expect_credential_helper_calls 0

  bazel build \
      --remote_cache=grpc://localhost:${worker_port} \
      --credential_helper="${TEST_TMPDIR}/credhelper" \
      //a:a >& $TEST_log || fail "Build with credentials should have succeeded"

  # First build should have called helper for 4 distinct URIs.
  expect_credential_helper_calls 4

  bazel build \
      --remote_cache=grpc://localhost:${worker_port} \
      --credential_helper="${TEST_TMPDIR}/credhelper" \
      //a:b >& $TEST_log || fail "Build with credentials should have succeeded"

  # Second build should have hit the credentials cache.
  expect_credential_helper_calls 4
}

function test_credential_helper_remote_execution() {
  setup_credential_helper_test

  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      //a:a >& $TEST_log && fail "Build without credentials should have failed"
  expect_log "Failed to query remote execution capabilities"

  # Helper shouldn't have been called yet.
  expect_credential_helper_calls 0

  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --credential_helper="${TEST_TMPDIR}/credhelper" \
      //a:a >& $TEST_log || fail "Build with credentials should have succeeded"

  # First build should have called helper for 5 distinct URIs.
  expect_credential_helper_calls 5

  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --credential_helper="${TEST_TMPDIR}/credhelper" \
      //a:b >& $TEST_log || fail "Build with credentials should have succeeded"

  # Second build should have hit the credentials cache.
  expect_credential_helper_calls 5
}

function test_credential_helper_clear_cache() {
  setup_credential_helper_test

  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --credential_helper="${TEST_TMPDIR}/credhelper" \
      //a:a >& $TEST_log || fail "Build with credentials should have succeeded"

  expect_credential_helper_calls 5

  bazel clean

  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --credential_helper="${TEST_TMPDIR}/credhelper" \
      //a:b >& $TEST_log || fail "Build with credentials should have succeeded"

  # Build after clean should have called helper again.
  expect_credential_helper_calls 10
}

function test_remote_grpc_cache_with_protocol() {
  # Test that if 'grpc' is provided as a scheme for --remote_cache flag, remote cache works.
  mkdir -p a
  cat > a/BUILD <<EOF
genrule(
  name = 'foo',
  outs = ["foo.txt"],
  cmd = "echo \"foo bar\" > \$@",
)
EOF

  bazel build \
      --remote_cache=grpc://localhost:${worker_port} \
      //a:foo \
      || fail "Failed to build //a:foo with remote cache"
}

# TODO(b/211478955): Deflake and re-enable.
function DISABLED_test_remote_grpc_via_unix_socket_proxy() {
  case "$PLATFORM" in
  darwin|freebsd|linux|openbsd)
    ;;
  *)
    return 0
    ;;
  esac

  # Test that remote execution can be routed via a UNIX domain socket if
  # supported by the platform.
  mkdir -p a
  cat > a/BUILD <<EOF
genrule(
  name = 'foo',
  outs = ["foo.txt"],
  cmd = "echo \"foo bar\" > \$@",
)
EOF

  # Note: not using $TEST_TMPDIR because many OSes, notably macOS, have
  # small maximum length limits for UNIX domain sockets.
  socket_dir=$(mktemp -d -t "remote_executor.XXXXXXXX")
  PROXY="$(rlocation io_bazel/src/test/shell/bazel/remote/uds_proxy.py)"
  python "${PROXY}" "${socket_dir}/executor-socket" "localhost:${worker_port}" &
  proxy_pid=$!

  bazel build \
      --remote_executor=grpc://noexist.invalid \
      --remote_proxy="unix:${socket_dir}/executor-socket" \
      //a:foo \
      || fail "Failed to build //a:foo with remote cache"

  kill ${proxy_pid}
  rm "${socket_dir}/executor-socket"
  rmdir "${socket_dir}"
}

# TODO(b/211478955): Deflake and re-enable.
function DISABLED_test_remote_grpc_via_unix_socket_direct() {
  case "$PLATFORM" in
  darwin|freebsd|linux|openbsd)
    ;;
  *)
    return 0
    ;;
  esac

  # Test that remote execution can be routed via a UNIX domain socket if
  # supported by the platform.
  mkdir -p a
  cat > a/BUILD <<EOF
genrule(
  name = 'foo',
  outs = ["foo.txt"],
  cmd = "echo \"foo bar\" > \$@",
)
EOF

  # Note: not using $TEST_TMPDIR because many OSes, notably macOS, have
  # small maximum length limits for UNIX domain sockets.
  socket_dir=$(mktemp -d -t "remote_executor.XXXXXXXX")
  PROXY="$(rlocation io_bazel/src/test/shell/bazel/remote/uds_proxy.py)"
  python "${PROXY}" "${socket_dir}/executor-socket" "localhost:${worker_port}" &
  proxy_pid=$!

  bazel build \
      --remote_executor="unix:${socket_dir}/executor-socket" \
      //a:foo \
      || fail "Failed to build //a:foo with remote cache"

  kill ${proxy_pid}
  rm "${socket_dir}/executor-socket"
  rmdir "${socket_dir}"
}

function test_cc_binary() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_binary(
name = 'test',
srcs = [ 'test.cc' ],
)
EOF
  cat > a/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello world!" << std::endl; return 0; }
EOF
  bazel build //a:test >& $TEST_log \
    || fail "Failed to build //a:test without remote execution"
  cp -f bazel-bin/a/test ${TEST_TMPDIR}/test_expected

  bazel clean >& $TEST_log
  bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      //a:test >& $TEST_log \
      || fail "Failed to build //a:test with remote execution"
  expect_log "7 processes: 5 internal, 2 remote"
  diff bazel-bin/a/test ${TEST_TMPDIR}/test_expected \
      || fail "Remote execution generated different result"
}

function test_cc_test() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_test(
name = 'test',
srcs = [ 'test.cc' ],
)
EOF
  cat > a/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello test!" << std::endl; return 0; }
EOF
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --test_output=errors \
      --noexperimental_split_xml_generation \
      //a:test >& $TEST_log \
      || fail "Failed to run //a:test with remote execution"
}

function test_cc_test_split_xml() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_test(
name = 'test',
srcs = [ 'test.cc' ],
)
EOF
  cat > a/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello test!" << std::endl; return 0; }
EOF
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --test_output=errors \
      --experimental_split_xml_generation \
      //a:test >& $TEST_log \
      || fail "Failed to run //a:test with remote execution"
}

function test_cc_binary_grpc_cache() {
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_binary(
name = 'test',
srcs = [ 'test.cc' ],
)
EOF
  cat > a/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello world!" << std::endl; return 0; }
EOF
  bazel build //a:test >& $TEST_log \
    || fail "Failed to build //a:test without remote cache"
  cp -f bazel-bin/a/test ${TEST_TMPDIR}/test_expected

  bazel clean >& $TEST_log
  bazel build \
      --remote_cache=grpc://localhost:${worker_port} \
      //a:test >& $TEST_log \
      || fail "Failed to build //a:test with remote gRPC cache service"
  diff bazel-bin/a/test ${TEST_TMPDIR}/test_expected \
      || fail "Remote cache generated different result"
}

function test_cc_binary_grpc_cache_statsline() {
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_binary(
name = 'test',
srcs = [ 'test.cc' ],
)
EOF
  cat > a/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello world!" << std::endl; return 0; }
EOF
  bazel build \
      --remote_cache=grpc://localhost:${worker_port} \
      //a:test >& $TEST_log \
      || fail "Failed to build //a:test with remote gRPC cache service"
  bazel clean >& $TEST_log
  bazel build \
      --remote_cache=grpc://localhost:${worker_port} \
      //a:test 2>&1 | tee $TEST_log | grep "remote cache hit" \
      || fail "Output does not contain remote cache hits"
}

function test_failing_cc_test() {
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_test(
name = 'test',
srcs = [ 'test.cc' ],
)
EOF
  cat > a/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Fail me!" << std::endl; return 1; }
EOF
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --test_output=errors \
      //a:test >& $TEST_log \
      && fail "Expected test failure" || true
  # TODO(ulfjack): Check that the test failure gets reported correctly.
}

function test_local_fallback_works_with_local_strategy() {
  mkdir -p gen1
  cat > gen1/BUILD <<'EOF'
genrule(
name = "gen1",
srcs = [],
outs = ["out1"],
cmd = "touch \"$@\"",
tags = ["no-remote"],
)
EOF

  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_local_fallback_strategy=local \
      --build_event_text_file=gen1.log \
      //gen1 >& $TEST_log \
      && fail "Expected failure" || true
}

function test_local_fallback_with_local_strategy_lists() {
  mkdir -p gen1
  cat > gen1/BUILD <<'EOF'
genrule(
name = "gen1",
srcs = [],
outs = ["out1"],
cmd = "touch \"$@\"",
tags = ["no-remote"],
)
EOF

  bazel build \
      --spawn_strategy=remote,local \
      --remote_executor=grpc://localhost:${worker_port} \
      --build_event_text_file=gen1.log \
      //gen1 >& $TEST_log \
      || fail "Expected success"

  mv gen1.log $TEST_log
  expect_log "2 processes: 1 internal, 1 local"
}

function test_local_fallback_with_sandbox_strategy_lists() {
  mkdir -p gen1
  cat > gen1/BUILD <<'EOF'
genrule(
name = "gen1",
srcs = [],
outs = ["out1"],
cmd = "touch \"$@\"",
tags = ["no-remote"],
)
EOF

  bazel build \
      --spawn_strategy=remote,sandboxed,local \
      --remote_executor=grpc://localhost:${worker_port} \
      --build_event_text_file=gen1.log \
      //gen1 >& $TEST_log \
      || fail "Expected success"

  mv gen1.log $TEST_log
  expect_log "2 processes: 1 internal, 1 .*-sandbox"
}

function test_local_fallback_to_sandbox_by_default() {
  mkdir -p gen1
  cat > gen1/BUILD <<'EOF'
genrule(
name = "gen1",
srcs = [],
outs = ["out1"],
cmd = "touch \"$@\"",
tags = ["no-remote"],
)
EOF

  bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      --build_event_text_file=gen1.log \
      //gen1 >& $TEST_log \
      || fail "Expected success"

  mv gen1.log $TEST_log
  expect_log "2 processes: 1 internal, 1 .*-sandbox"
}

function test_local_fallback_works_with_sandboxed_strategy() {
  mkdir -p gen2
  cat > gen2/BUILD <<'EOF'
genrule(
name = "gen2",
srcs = [],
outs = ["out2"],
cmd = "touch \"$@\"",
tags = ["no-remote"],
)
EOF

  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_local_fallback_strategy=sandboxed \
      --build_event_text_file=gen2.log \
      //gen2 >& $TEST_log \
      && fail "Expected failure" || true
}

function test_local_fallback_if_no_remote_executor() {
  # Test that when manually set --spawn_strategy that includes remote, but remote_executor isn't set, we ignore
  # the remote strategy rather than reporting an error. See https://github.com/bazelbuild/bazel/issues/13340.
  mkdir -p gen1
  cat > gen1/BUILD <<'EOF'
genrule(
name = "gen1",
srcs = [],
outs = ["out1"],
cmd = "touch \"$@\"",
)
EOF

  bazel build \
      --spawn_strategy=remote,local \
      --build_event_text_file=gen1.log \
      //gen1 >& $TEST_log \
      || fail "Expected success"

  mv gen1.log $TEST_log
  expect_log "2 processes: 1 internal, 1 local"
}

function test_local_fallback_if_remote_executor_unavailable() {
  # Test that when --remote_local_fallback is set and remote_executor is unavailable when build starts, we fallback to
  # local strategy. See https://github.com/bazelbuild/bazel/issues/13487.
  mkdir -p gen1
  cat > gen1/BUILD <<'EOF'
genrule(
name = "gen1",
srcs = [],
outs = ["out1"],
cmd = "touch \"$@\"",
)
EOF

  bazel build \
      --spawn_strategy=remote,local \
      --remote_executor=grpc://noexist.invalid \
      --remote_local_fallback \
      --build_event_text_file=gen1.log \
      //gen1 >& $TEST_log \
      || fail "Expected success"

  mv gen1.log $TEST_log
  expect_log "2 processes: 1 internal, 1 local"
}

function is_file_uploaded() {
  h=$(shasum -a256 < $1)
  if [ -e "$cas_path/${h:0:64}" ]; then return 0; else return 1; fi
}

function test_failed_test_outputs_not_uploaded() {
  # Test that outputs of a failed test/action are not uploaded to the remote
  # cache. This is a regression test for https://github.com/bazelbuild/bazel/issues/7232
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_test(
  name = 'test',
  srcs = [ 'test.cc' ],
)
EOF
  cat > a/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Fail me!" << std::endl; return 1; }
EOF
  bazel test \
      --remote_cache=grpc://localhost:${worker_port} \
      --test_output=errors \
      //a:test >& $TEST_log \
      && fail "Expected test failure" || true
   ($(is_file_uploaded bazel-testlogs/a/test/test.log) \
     && fail "Expected test log to not be uploaded to remote execution") || true
   ($(is_file_uploaded bazel-testlogs/a/test/test.xml) \
     && fail "Expected test xml to not be uploaded to remote execution") || true
}

# Tests that the remote worker can return a 200MB blob that requires chunking.
# Blob has to be that large in order to exceed the grpc default max message size.
function test_genrule_large_output_chunking() {
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
genrule(
name = "large_output",
srcs = ["small_blob.txt"],
outs = ["large_blob.txt"],
cmd = "cp \$(location small_blob.txt) tmp.txt; " +
"(for i in {1..22} ; do cat tmp.txt >> \$@; cp \$@ tmp.txt; done)",
)
EOF
  cat > a/small_blob.txt <<EOF
0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890
EOF
  bazel build //a:large_output >& $TEST_log \
    || fail "Failed to build //a:large_output without remote execution"
  cp -f bazel-genfiles/a/large_blob.txt ${TEST_TMPDIR}/large_blob_expected.txt

  bazel clean >& $TEST_log
  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      //a:large_output >& $TEST_log \
      || fail "Failed to build //a:large_output with remote execution"
  diff bazel-genfiles/a/large_blob.txt ${TEST_TMPDIR}/large_blob_expected.txt \
      || fail "Remote execution generated different result"
}

function test_py_test() {
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
py_test(
name = 'test',
srcs = [ 'test.py' ],
)
EOF
  cat > a/test.py <<'EOF'
import sys
if __name__ == "__main__":
    sys.exit(0)
EOF
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --test_output=errors \
      //a:test >& $TEST_log \
      || fail "Failed to run //a:test with remote execution"
}

function test_py_test_with_xml_output() {
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
py_test(
name = 'test',
srcs = [ 'test.py' ],
)
EOF
  cat > a/test.py <<'EOF'
import sys
import os
if __name__ == "__main__":
    f = open(os.environ['XML_OUTPUT_FILE'], "w")
    f.write('''
<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="test" tests="1" failures="1" errors="1">
    <testcase name="first" status="run">
      <failure>That did not work!</failure>
    </testcase>
  </testsuite>
</testsuites>
''')
    sys.exit(0)
EOF
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --test_output=errors \
      //a:test >& $TEST_log \
      || fail "Failed to run //a:test with remote execution"
  xml=bazel-testlogs/a/test/test.xml
  [ -e $xml ] || fail "Expected to find XML output"
  cat $xml > $TEST_log
  expect_log 'That did not work!'
}

function test_failing_py_test_with_xml_output() {
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
py_test(
name = 'test',
srcs = [ 'test.py' ],
)
EOF
  cat > a/test.py <<'EOF'
import sys
import os
if __name__ == "__main__":
    f = open(os.environ['XML_OUTPUT_FILE'], "w")
    f.write('''
<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="test" tests="1" failures="1" errors="1">
    <testcase name="first" status="run">
      <failure>That did not work!</failure>
    </testcase>
  </testsuite>
</testsuites>
''')
    sys.exit(1)
EOF
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --test_output=errors \
      //a:test >& $TEST_log \
      && fail "Expected test failure" || true
  xml=bazel-testlogs/a/test/test.xml
  [ -e $xml ] || fail "Expected to find XML output"
  cat $xml > $TEST_log
  expect_log 'That did not work!'
}

function test_noinput_action() {
  mkdir -p a
  cat > a/rule.bzl <<'EOF'
def _impl(ctx):
  output = ctx.outputs.out
  ctx.actions.run_shell(
      outputs=[output],
      command="echo 'Hello World' > %s" % (output.path))

empty = rule(
    implementation=_impl,
    outputs={"out": "%{name}.txt"},
)
EOF
  cat > a/BUILD <<'EOF'
load("//a:rule.bzl", "empty")
package(default_visibility = ["//visibility:public"])
empty(name = 'test')
EOF
  bazel build \
      --remote_cache=grpc://localhost:${worker_port} \
      --test_output=errors \
      //a:test >& $TEST_log \
      || fail "Failed to run //a:test with remote execution"
}

function test_timeout() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
sh_test(
  name = "sleep",
  timeout = "short",
  srcs = ["sleep.sh"],
)
EOF

  cat > a/sleep.sh <<'EOF'
#!/bin/bash
for i in {1..3}
do
    echo "Sleeping $i..."
    sleep 1
done
EOF
  chmod +x a/sleep.sh
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --test_output=errors \
      --test_timeout=1,1,1,1 \
      //a:sleep >& $TEST_log \
      && fail "Test failure (timeout) expected" || true
  expect_log "TIMEOUT"
  expect_log "Sleeping 1..."
  # The current implementation of the remote worker does not terminate actions
  # when they time out, therefore we cannot verify that:
  # expect_not_log "Sleeping 3..."
}

function test_passed_env_user() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
sh_test(
  name = "user_test",
  timeout = "short",
  srcs = ["user_test.sh"],
)
EOF

  cat > a/user_test.sh <<'EOF'
#!/bin/sh
echo "user=$USER"
EOF
  chmod +x a/user_test.sh
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --test_output=all \
      --test_env=USER=boo \
      //a:user_test >& $TEST_log \
      || fail "Failed to run //a:user_test with remote execution"
  expect_log "user=boo"

  # Rely on the test-setup script to set USER value to whoami.
  export USER=
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --test_output=all \
      //a:user_test >& $TEST_log \
      || fail "Failed to run //a:user_test with remote execution"
  expect_log "user=$(whoami)"
}

function test_exitcode() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"hello world\" > \"$@\"",
)
EOF

  (set +e
    bazel build \
      --genrule_strategy=remote \
      --remote_executor=bazel-test-does-not-exist \
      //a:foo >& $TEST_log
    [ $? -eq 34 ]) || fail "Test failed due to wrong exit code"
}

# Bazel should display non-test errors to the user, instead of hiding them behind test failures.
# For example, if the network connection to the remote executor fails it shouldn't be displayed as
# a test error.
function test_display_non_testerrors() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
sh_test(
  name = "test",
  timeout = "short",
  srcs = ["test.sh"],
)
EOF
  cat > a/test.sh <<'EOF'
#!/bin/sh
#This will never run, because the remote side is not reachable.
EOF
  chmod +x a/test.sh
  bazel test \
      --spawn_strategy=remote \
      --remote_executor=grpc://bazel.does.not.exist:1234 \
      --remote_retries=0 \
      --test_output=all \
      --test_env=USER=boo \
      //a:test >& $TEST_log \
      && fail "Test failure expected" || true
  expect_not_log "test.log"
  expect_log "Failed to query remote execution capabilities"
}

function set_symlinks_in_directory_testfixtures() {
    cat > BUILD <<'EOF'
genrule(
    name = 'make-links',
    outs = ['dir', 'r', 'a', 'rd', 'ad'],
    cmd = ('mkdir $(location dir) && ' +
        'cd $(location dir) && ' +
        'echo hello > foo && ' + # Regular file.
        'ln -s foo r && ' +  # Relative symlink, will be passed as symlink.
        'ln -s $$PWD/foo a && ' +  # Absolute symlink, will be copied.
        'mkdir bar && ' + # Regular directory.
        'echo bla > bar/baz && ' +
        'ln -s bar rd && ' +  # Relative symlink, will be passed as symlink.
        'ln -s $$PWD/bar ad && ' + # Absolute symlink, will be copied.
        'cd .. && ' +
        'ln -s dir/foo r && ' +  # Relative symlink, will be passed as symlink.
        'ln -s $$PWD/dir/foo a && ' +  # Absolute symlink, will be copied.
        'ln -s dir rd && ' +  # Relative symlink, will be passed as symlink.
        'ln -s $$PWD/dir ad' # Absolute symlink, will be copied.
    ),
)
EOF
    cat > "${TEST_TMPDIR}/expected_links" <<'EOF'
./ad/r
./ad/rd
./dir/r
./dir/rd
./r
./rd
EOF
}

function test_symlinks_in_directory() {
    set_symlinks_in_directory_testfixtures
    # Need --remote_download_all because the genrule generates directory output
    # for one of the declared outputs which is not supported when BwoB.
    bazel build \
          --incompatible_remote_symlinks \
          --noincompatible_remote_disallow_symlink_in_tree_artifact \
          --remote_executor=grpc://localhost:${worker_port} \
          --remote_download_all \
          --spawn_strategy=remote \
          //:make-links &> $TEST_log \
          || fail "Failed to build //:make-links with remote execution"
    expect_log "1 remote"
    find -L bazel-genfiles -type f -exec cat {} \; | sort | uniq -c &> $TEST_log
    expect_log "9 bla"
    expect_log "11 hello"
    CUR=$PWD && cd bazel-genfiles && \
      find . -type l | sort > "${TEST_TMPDIR}/links" && cd $CUR
    diff "${TEST_TMPDIR}/links" "${TEST_TMPDIR}/expected_links" \
      || fail "Remote execution created different symbolic links"
}

function test_symlinks_in_directory_cache_only() {
    # This test is the same as test_symlinks_in_directory, except it works
    # locally and uses the remote cache to query results.
    #
    # Need --remote_download_all because the genrule generates directory output
    # for one of the declared outputs which is not supported when BwoB.
    set_symlinks_in_directory_testfixtures
    bazel build \
          --incompatible_remote_symlinks \
          --noincompatible_remote_disallow_symlink_in_tree_artifact \
          --remote_cache=grpc://localhost:${worker_port} \
          --remote_download_all \
          --spawn_strategy=local \
          //:make-links &> $TEST_log \
          || fail "Failed to build //:make-links with remote cache service"
    expect_log "1 local"
    bazel clean # Get rid of local results, rely on remote cache.
    bazel build \
          --incompatible_remote_symlinks \
          --noincompatible_remote_disallow_symlink_in_tree_artifact \
          --remote_cache=grpc://localhost:${worker_port} \
          --remote_download_all \
          --spawn_strategy=local \
          //:make-links &> $TEST_log \
          || fail "Failed to build //:make-links with remote cache service"
    expect_log "1 remote cache hit"
    # Check that the results downloaded from remote cache are the same as local.
    find -L bazel-genfiles -type f -exec cat {} \; | sort | uniq -c &> $TEST_log
    expect_log "9 bla"
    expect_log "11 hello"
    CUR=$PWD && cd bazel-genfiles && \
      find . -type l | sort > "${TEST_TMPDIR}/links" && cd $CUR
    diff "${TEST_TMPDIR}/links" "${TEST_TMPDIR}/expected_links" \
      || fail "Cached result created different symbolic links"
}

function test_treeartifact_in_runfiles() {
     mkdir -p a
    cat > a/BUILD <<'EOF'
load(":output_directory.bzl", "gen_output_dir", "gen_output_dir_test")

gen_output_dir(
    name = "starlark_output_dir",
    outdir = "dir",
)

gen_output_dir_test(
    name = "starlark_output_dir_test",
    dir = ":starlark_output_dir",
)
EOF
     cat > a/output_directory.bzl <<'EOF'
def _gen_output_dir_impl(ctx):
  output_dir = ctx.actions.declare_directory(ctx.attr.outdir)
  ctx.actions.run_shell(
      outputs = [output_dir],
      inputs = [],
      command = """
        mkdir -p $1/sub; \
        echo "foo" > $1/foo; \
        echo "bar" > $1/sub/bar
      """,
      arguments = [output_dir.path],
  )
  return [
      DefaultInfo(files=depset(direct=[output_dir]),
                  runfiles = ctx.runfiles(files = [output_dir]))
  ]
gen_output_dir = rule(
    implementation = _gen_output_dir_impl,
    attrs = {
        "outdir": attr.string(mandatory = True),
    },
)
def _gen_output_dir_test_impl(ctx):
    test = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(test, "echo hello world")
    myrunfiles = ctx.runfiles(files=ctx.attr.dir.default_runfiles.files.to_list())
    return [
        DefaultInfo(
            executable = test,
            runfiles = myrunfiles,
        ),
    ]
gen_output_dir_test = rule(
    implementation = _gen_output_dir_test_impl,
    test = True,
    attrs = {
        "dir":  attr.label(mandatory = True),
    },
)
EOF
     # Also test this directory inputs with sandboxing. Ideally we would add such
     # a test into the sandboxing module.
     bazel test \
           --spawn_strategy=sandboxed \
           //a:starlark_output_dir_test \
           || fail "Failed to run //a:starlark_output_dir_test with sandboxing"

     bazel test \
           --spawn_strategy=remote \
           --remote_executor=grpc://localhost:${worker_port} \
           //a:starlark_output_dir_test \
           || fail "Failed to run //a:starlark_output_dir_test with remote execution"
}

function test_remote_exec_properties() {
  # Test that setting remote exec properties works.
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
)
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_default_exec_properties=OSFamily=linux \
    //a:foo || fail "Failed to build //a:foo"

  bazel clean

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_default_exec_properties=OSFamily=windows \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_not_log "remote cache hit"
}

function test_tag_no_cache() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
  tags = ["no-cache"]
)
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 remote"

  bazel clean

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 remote"
  expect_not_log "remote cache hit"
}

function test_tag_no_cache_for_disk_cache() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
  tags = ["no-cache"]
)
EOF

  CACHEDIR=$(mktemp -d)

  bazel build \
    --disk_cache=$CACHEDIR \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 .*-sandbox"

  bazel clean

  bazel build \
    --disk_cache=$CACHEDIR \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 .*-sandbox"
  expect_not_log "remote cache hit"
}

function test_tag_no_remote_cache() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
  tags = ["no-remote-cache"]
)
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 remote"

  bazel clean

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 remote"
  expect_not_log "remote cache hit"
}

function test_tag_no_remote_cache_upload() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
)
EOF

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    --modify_execution_info=.*=+no-remote-cache-upload \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  remote_ac_files="$(count_remote_ac_files)"
  [[ "$remote_ac_files" == 0 ]] || fail "Expected 0 remote action cache entries, not $remote_ac_files"

  # populate the cache
  bazel clean

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  bazel clean

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    --modify_execution_info=.*=+no-remote-cache-upload \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "remote cache hit"
}

function test_tag_no_remote_cache_for_disk_cache() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
  tags = ["no-remote-cache"]
)
EOF

  CACHEDIR=$(mktemp -d)

  bazel build \
    --disk_cache=$CACHEDIR \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 .*-sandbox"

  bazel clean

  bazel build \
    --disk_cache=$CACHEDIR \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 disk cache hit"
}

function test_tag_no_remote_exec() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
  tags = ["no-remote-exec"]
)
EOF

  bazel build \
    --spawn_strategy=remote,local \
    --remote_executor=grpc://localhost:${worker_port} \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 local"
  expect_not_log "1 remote"

  bazel clean

  bazel build \
    --spawn_strategy=remote,local \
    --remote_executor=grpc://localhost:${worker_port} \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 remote cache hit"
  expect_not_log "1 local"
}

function test_require_cached() {
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = ["foo.in"],
  outs = ["foo.out"],
  cmd = "cp \"$<\" \"$@\"",
)
EOF

  echo "input 1" >a/foo.in
  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  expect_log "1 remote"

  echo "input 2" >a/foo.in
  if bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_remote_require_cached \
    //a:foo >& $TEST_log; then
    fail "Build of //a:foo succeeded but it should have failed"
  fi

  expect_log "Action must be cached due to --experimental_remote_require_cached but it is not"
  expect_not_log "remote cache hit"
}

function test_nobuild_runfile_links() {
  mkdir data && echo "hello" > data/hello && echo "world" > data/world
    cat > WORKSPACE <<EOF
workspace(name = "foo")
EOF

  cat > test.sh <<'EOF'
#!/bin/bash
set -e
[[ -f ${RUNFILES_DIR}/foo/data/hello ]]
[[ -f ${RUNFILES_DIR}/foo/data/world ]]
exit 0
EOF
  chmod 755 test.sh
  cat > BUILD <<'EOF'
filegroup(
  name = "runfiles",
  srcs = ["data/hello", "data/world"],
)

sh_test(
  name = "test",
  srcs = ["test.sh"],
  data = [":runfiles"],
)
EOF

  bazel test \
    --nobuild_runfile_links \
    --remote_executor=grpc://localhost:${worker_port} \
    //:test || fail "Testing //:test failed"

  [[ ! -f bazel-bin/test.runfiles/foo/data/hello ]] || fail "expected no runfile data/hello"
  [[ ! -f bazel-bin/test.runfiles/foo/data/world ]] || fail "expected no runfile data/world"
  [[ ! -f bazel-bin/test.runfiles/MANIFEST ]] || fail "expected output manifest to exist"
}

function test_platform_default_properties_invalidation() {
  # Test that when changing values of --remote_default_platform_properties all actions are
  # invalidated.
mkdir -p test
  cat > test/BUILD << 'EOF'
genrule(
    name = "test",
    srcs = [],
    outs = ["output.txt"],
    cmd = "echo \"foo\" > \"$@\""
)
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_default_exec_properties="build=1234" \
    //test:test >& $TEST_log || fail "Failed to build //a:remote"

  expect_log "2 processes: 1 internal, 1 remote"

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_default_exec_properties="build=88888" \
    //test:test >& $TEST_log || fail "Failed to build //a:remote"

  # Changing --remote_default_platform_properties value should invalidate SkyFrames in-memory
  # caching and make it re-run the action.
  expect_log "2 processes: 1 internal, 1 remote"

  bazel  build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_default_exec_properties="build=88888" \
    //test:test >& $TEST_log || fail "Failed to build //a:remote"

  # The same value of --remote_default_platform_properties should NOT invalidate SkyFrames in-memory cache
  #  and make the action should not be re-run.
  expect_log "1 process: 1 internal"

  bazel shutdown

  bazel  build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_default_exec_properties="build=88888" \
    //test:test >& $TEST_log || fail "Failed to build //a:remote"

  # The same value of --remote_default_platform_properties should NOT invalidate SkyFrames od-disk cache
  #  and the action should not be re-run.
  expect_log "1 process: 1 internal"

  bazel build\
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_default_exec_properties="build=88888" \
    --remote_default_platform_properties='properties:{name:"build" value:"1234"}' \
    //test:test >& $TEST_log && fail "Should fail" || true

  # Build should fail with a proper error message if both
  # --remote_default_platform_properties and --remote_default_exec_properties
  # are provided via command line
  expect_log "Setting both --remote_default_platform_properties and --remote_default_exec_properties is not allowed"
}


function test_combined_disk_remote_exec_with_flag_combinations() {
  rm -f ${TEST_TMPDIR}/test_expected
  declare -a testcases=(
     # ensure CAS entries get uploaded even when action entries don't.
     "--noremote_upload_local_results"
     "--remote_upload_local_results"
     # Should be some disk cache hits, just not remote.
     "--noremote_accept_cached"
  )

  for flags in "${testcases[@]}"; do
    genrule_combined_disk_remote_exec "$flags"
    # clean up and start a new worker for the next run
    tear_down
    set_up
  done
}

function genrule_combined_disk_remote_exec() {
  # Test for the combined disk and grpc cache with remote_exec
  # These flags get reset before the bazel runs when we clear caches.
  local cache="${TEST_TMPDIR}/disk_cache"
  local disk_flags="--disk_cache=$cache"
  local grpc_flags="--remote_cache=grpc://localhost:${worker_port}"
  local remote_exec_flags="--remote_executor=grpc://localhost:${worker_port}"

  # These flags are the same for all bazel runs.
  local testcase_flags="$@"
  local spawn_flags=("--spawn_strategy=remote" "--genrule_strategy=remote")

  # if exist in disk cache or  remote cache, don't run remote exec, don't update caches.
  # [CASE]disk_cache, remote_cache: remote_exec, disk_cache, remote_cache
  #   1)     notexist     notexist   run OK    update,    update
  #   2)     notexist     exist      no run    update,    no update
  #   3)     exist        notexist   no run    no update, no update
  #   4)     exist        exist      no run    no update, no update
  #   5)  another rule that depends on 4, but run before 5
  # Our setup ensures the first 2 columns, our validation checks the last 3.
  # NOTE that remote_exec will UPDATE the disk cache.
  #
  # We measure if it was run remotely via the "1 remote." in the output and caches
  # from the cache hit on the same line.

  # https://cs.opensource.google/bazel/bazel/+/master:third_party/remoteapis/build/bazel/remote/execution/v2/remote_execution.proto;l=447;drc=29ac010f3754c308de2ff13d3480b870dc7cb7f6
  #
  #  tags: [nocache, noremoteexec]
  mkdir -p a
  cat > a/BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])
genrule(
  name = 'test',
  cmd = 'echo "Hello world" > $@',
  outs = ['test.txt'],
)

genrule(
  name = 'test2',
  srcs = [':test'],
  cmd = 'cat $(SRCS) > $@',
  outs = ['test2.txt'],
)
EOF
  rm -rf $cache
  mkdir $cache

  echo "INFO: RUNNING testcase($testcase_flags)"
  # Case 1)
  #     disk_cache, remote_cache: remote_exec, disk_cache, remote_cache
  #       notexist     notexist   run OK       update,     update
  #
  # Do a build to populate the disk and remote cache.
  # Then clean and do another build to validate nothing updates.
  bazel build $spawn_flags $testcase_flags $remote_exec_flags $grpc_flags $disk_flags //a:test &> $TEST_log \
      || fail "CASE 1 Failed to build"

  echo "Hello world" > ${TEST_TMPDIR}/test_expected
  expect_log "2 processes: 1 internal, 1 remote." "CASE 1: unexpected action line [[$(grep processes $TEST_log)]]"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
      || fail "Disk cache generated different result [$(cat bazel-genfiles/a/test.txt)] [$(cat $TEST_TMPDIR/test_expected)]"

  disk_action_cache_files="$(count_disk_ac_files "$cache")"
  remote_action_cache_files="$(count_remote_ac_files)"

  [[ "$disk_action_cache_files" == 1 ]] || fail "CASE 1: Expected 1 disk action cache entries, not $disk_action_cache_files"
  # Even though bazel isn't writing the remote action cache, we expect the worker to write one or the
  # the rest of our tests will fail.
  [[ "$remote_action_cache_files" == 1 ]] || fail "CASE 1: Expected 1 remote action cache entries, not $remote_action_cache_files"

  rm -rf $cache
  mkdir $cache

  # Case 2)
  #     disk_cache, remote_cache: remote_exec, disk_cache, remote_cache
  #       notexist     exist      no run      update,    no update
  bazel clean
  bazel build $spawn_flags $testcase_flags $remote_exec_flags $grpc_flags $disk_flags //a:test &> $TEST_log \
      || fail "CASE 2 Failed to build"
  if [[ "$testcase_flags" == --noremote_accept_cached* ]]; then
    expect_log "2 processes: 1 internal, 1 remote." "CASE 2a: unexpected action line [[$(grep processes $TEST_log)]]"
  else
    expect_log "2 processes: 1 remote cache hit, 1 internal." "CASE 2: unexpected action line [[$(grep processes $TEST_log)]]"
  fi

  # ensure disk and remote cache populated
  disk_action_cache_files="$(count_disk_ac_files "$cache")"
  remote_action_cache_files="$(count_remote_ac_files)"
  [[ "$disk_action_cache_files" == 1 ]] || fail "CASE 2: Expected 1 disk action cache entries, not $disk_action_cache_files"
  [[ "$remote_action_cache_files" == 1 ]] || fail "CASE 2: Expected 1 remote action cache entries, not $remote_action_cache_files"

  # Case 3)
  #     disk_cache, remote_cache: remote_exec, disk_cache, remote_cache
  #          exist      notexist   no run      no update, no update
  # stop the worker to clear the remote cache and then restart it.
  # This ensures that if we hit the disk cache and it returns valid values
  # for FindMissingBLobs, the remote exec can still find it from the remote cache.

  stop_worker
  start_worker
  # need to reset flags after restarting worker [on new port]
  local grpc_flags="--remote_cache=grpc://localhost:${worker_port}"
  local remote_exec_flags="--remote_executor=grpc://localhost:${worker_port}"
  bazel clean
  bazel build $spawn_flags $testcase_flags $remote_exec_flags $grpc_flags $disk_flags //a:test &> $TEST_log \
      || fail "CASE 3 failed to build"
  expect_log "2 processes: 1 disk cache hit, 1 internal." "CASE 3: unexpected action line [[$(grep processes $TEST_log)]]"

  # Case 4)
  #     disk_cache, remote_cache: remote_exec, disk_cache, remote_cache
  #          exist      exist     no run        no update, no update

  # This one is not interesting after case 3.
  bazel clean
  bazel build $spawn_flags $testcase_flags $remote_exec_flags $grpc_flags $disk_flags //a:test &> $TEST_log \
      || fail "CASE 4 failed to build"
  expect_log "2 processes: 1 disk cache hit, 1 internal." "CASE 4: unexpected action line [[$(grep processes $TEST_log)]]"

  # One last slightly more complicated case.
  # Build a target that depended on the last target but we clean and clear the remote cache.
  # We should get one cache hit from disk and and one remote exec.

  stop_worker
  start_worker
  # reset port
  local grpc_flags="--remote_cache=grpc://localhost:${worker_port}"
  local remote_exec_flags="--remote_executor=grpc://localhost:${worker_port}"

  bazel clean
  bazel build $spawn_flags $testcase_flags --genrule_strategy=remote $remote_exec_flags $grpc_flags $disk_flags //a:test2 &> $TEST_log \
        || fail "CASE 5 failed to build //a:test2"
  expect_log "3 processes: 1 disk cache hit, 1 internal, 1 remote." "CASE 5: unexpected action line [[$(grep processes $TEST_log)]]"
}

function test_combined_disk_remote_exec_nocache_tag() {
  rm -rf ${TEST_TMPDIR}/test_expected
  local cache="${TEST_TMPDIR}/disk_cache"
  local flags=("--disk_cache=$cache"
               "--remote_cache=grpc://localhost:${worker_port}"
               "--remote_executor=grpc://localhost:${worker_port}"
               "--spawn_strategy=remote"
               "--genrule_strategy=remote")

  mkdir -p a
  cat > a/BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])
genrule(
  name = 'nocache_test',
  cmd = 'echo "Hello world" > $@',
  outs = ['test.txt'],
  tags = ['no-cache'],
)
EOF

  rm -rf $cache
  mkdir $cache

  bazel build "${flags[@]}" //a:nocache_test &> $TEST_log \
      || fail "CASE 1 Failed to build"

  echo "Hello world" > ${TEST_TMPDIR}/test_expected
  expect_log "2 processes: 1 internal, 1 remote." "CASE 1: unexpected action line [[$(grep processes $TEST_log)] $flags]"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
      || fail "different result 1 [$(cat bazel-bin/a/test.txt)] [$(cat $TEST_TMPDIR/test_expected)]"

  # build it again, there should be no caching
  bazel clean
  bazel build "${flags[@]}" //a:nocache_test &> $TEST_log \
      || fail "CASE 2 Failed to build"
  ls -l bazel-bin/a
  expect_log "2 processes: 1 internal, 1 remote." "CASE 2: unexpected action line [[$(grep processes $TEST_log)]]"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
      || fail "different result 2 [$(cat bazel-bin/a/test.txt)] [$(cat $TEST_TMPDIR/test_expected)]"
}

function test_genrule_combined_disk_grpc_cache() {
  # Test for the combined disk and grpc cache.
  # Built items should be pushed to both the disk and grpc cache.
  # If --noremote_upload_local_results flag is set,
  # built items should only be pushed to the disk cache.
  # If --noremote_accept_cached flag is set,
  # built items should only be checked from the disk cache.
  # If an item is missing on disk cache, but present on grpc cache,
  # then bazel should copy it from grpc cache to disk cache on fetch.

  local cache="${TEST_TMPDIR}/cache"
  local disk_flags="--disk_cache=$cache"
  local grpc_flags="--remote_cache=grpc://localhost:${worker_port}"

  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
genrule(
name = 'test',
cmd = 'echo "Hello world" > \$@',
outs = [ 'test.txt' ],
)
EOF
  rm -rf $cache
  mkdir $cache

  # Build and push to disk cache but not grpc cache
  bazel build $disk_flags $grpc_flags --noremote_upload_local_results //a:test \
    || fail "Failed to build //a:test with combined disk grpc cache"
  cp -f bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected

  # Fetch from disk cache
  bazel clean
  bazel build $disk_flags //a:test --noremote_upload_local_results &> $TEST_log \
    || fail "Failed to fetch //a:test from disk cache"
  expect_log "1 disk cache hit" "Fetch from disk cache failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Disk cache generated different result"

  # No cache result from grpc cache, rebuild target
  bazel clean
  bazel build $grpc_flags //a:test --noremote_upload_local_results &> $TEST_log \
    || fail "Failed to build //a:test"
  expect_not_log "1 remote cache hit" "Should not get cache hit from grpc cache"
  expect_log "1 .*-sandbox" "Rebuild target failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Rebuilt target generated different result"

  rm -rf $cache
  mkdir $cache

  # No cache result from grpc cache, rebuild target, and upload result to grpc cache
  bazel clean
  bazel build $grpc_flags //a:test --noremote_accept_cached &> $TEST_log \
    || fail "Failed to build //a:test"
  expect_not_log "1 remote cache hit" "Should not get cache hit from grpc cache"
  expect_log "1 .*-sandbox" "Rebuild target failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Rebuilt target generated different result"

  # No cache result from grpc cache, rebuild target, and upload result to disk cache
  bazel clean
  bazel build $disk_flags $grpc_flags //a:test --noremote_accept_cached &> $TEST_log \
    || fail "Failed to build //a:test"
  expect_not_log "1 remote cache hit" "Should not get cache hit from grpc cache"
  expect_log "1 .*-sandbox" "Rebuild target failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Rebuilt target generated different result"

  # Fetch from disk cache
  bazel clean
  bazel build $disk_flags $grpc_flags //a:test --noremote_accept_cached &> $TEST_log \
    || fail "Failed to build //a:test"
  expect_log "1 disk cache hit" "Fetch from disk cache failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Disk cache generated different result"

  rm -rf $cache
  mkdir $cache

  # Build and push to disk cache and grpc cache
  bazel clean
  bazel build $disk_flags $grpc_flags //a:test \
    || fail "Failed to build //a:test with combined disk grpc cache"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Built target generated different result"

  # Fetch from disk cache
  bazel clean
  bazel build $disk_flags //a:test &> $TEST_log \
    || fail "Failed to fetch //a:test from disk cache"
  expect_log "1 disk cache hit" "Fetch from disk cache failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Disk cache generated different result"

  # Fetch from grpc cache
  bazel clean
  bazel build $grpc_flags //a:test &> $TEST_log \
    || fail "Failed to fetch //a:test from grpc cache"
  expect_log "1 remote cache hit" "Fetch from grpc cache failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "HTTP cache generated different result"

  rm -rf $cache
  mkdir $cache

  # Copy from grpc cache to disk cache
  bazel clean
  bazel build $disk_flags $grpc_flags //a:test &> $TEST_log \
    || fail "Failed to copy //a:test from grpc cache to disk cache"
  expect_log "1 remote cache hit" "Copy from grpc cache to disk cache failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "HTTP cache generated different result"

  # Fetch from disk cache
  bazel clean
  bazel build $disk_flags //a:test &> $TEST_log \
    || fail "Failed to fetch //a:test from disk cache"
  expect_log "1 disk cache hit" "Fetch from disk cache after copy from grpc cache failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Disk cache generated different result"

  rm -rf $cache
}

function test_combined_cache_upload() {
  mkdir -p a
  echo 'bar' > a/bar
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [":bar"],
  outs = ["foo.txt"],
  cmd = """
    echo $(location :bar) > "$@"
  """,
)
EOF

  CACHEDIR=$(mktemp -d)

  bazel build \
    --disk_cache=$CACHEDIR \
    --remote_cache=grpc://localhost:${worker_port} \
    //a:foo >& $TEST_log || fail "Failed to build //a:foo"

  remote_ac_files="$(count_remote_ac_files)"
  [[ "$remote_ac_files" == 1 ]] || fail "Expected 1 remote action cache entries, not $remote_ac_files"
  remote_cas_files="$(count_remote_cas_files)"
  [[ "$remote_cas_files" == 3 ]] || fail "Expected 3 remote cas entries, not $remote_cas_files"
}

function test_combined_cache_with_no_remote_cache_tag_remote_cache() {
  # Test that actions with no-remote-cache tag can hit disk cache of a combined cache but
  # remote cache is disabled.

  combined_cache_with_no_remote_cache_tag "--remote_cache=grpc://localhost:${worker_port}"
}

function test_combined_cache_with_no_remote_cache_tag_remote_execution() {
  # Test that actions with no-remote-cache tag can hit disk cache of a combined cache but
  # remote cache is disabled.

  combined_cache_with_no_remote_cache_tag "--remote_executor=grpc://localhost:${worker_port}"
}

function combined_cache_with_no_remote_cache_tag() {
  local cache="${TEST_TMPDIR}/cache"
  local disk_flags="--disk_cache=$cache"
  local grpc_flags="$@"

  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
genrule(
name = 'test',
cmd = 'echo "Hello world" > \$@',
outs = [ 'test.txt' ],
tags = ['no-remote-cache'],
)
EOF

  rm -rf $cache
  mkdir $cache

  # Build and push to disk cache but not remote cache
  bazel build $disk_flags $grpc_flags //a:test \
    || fail "Failed to build //a:test with combined cache"
  cp -f bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected

  # Fetch from disk cache
  bazel clean
  bazel build $disk_flags //a:test &> $TEST_log \
    || fail "Failed to fetch //a:test from disk cache"
  expect_log "1 disk cache hit" "Fetch from disk cache failed"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Disk cache generated different result"

  # No cache result from grpc cache, rebuild target
  bazel clean
  bazel build $grpc_flags //a:test &> $TEST_log \
    || fail "Failed to build //a:test"
  expect_not_log "1 remote cache hit" "Should not get cache hit from grpc cache"
  diff bazel-genfiles/a/test.txt ${TEST_TMPDIR}/test_expected \
    || fail "Rebuilt target generated different result"
}

function test_combined_cache_with_no_remote_tag() {
  # Test that outputs of actions tagged with no-remote should not be uploaded
  # to remote cache when remote execution is enabled. See
  # https://github.com/bazelbuild/bazel/issues/14900.
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
genrule(
name = 'test',
cmd = 'echo "Hello world" > \$@',
outs = [ 'test.txt' ],
tags = ['no-remote'],
)
EOF

  cache_dir=$(mktemp -d)
  bazel build \
    --disk_cache=${cache_dir} \
    --remote_executor=grpc://localhost:${worker_port} \
    //a:test &> $TEST_log \
    || fail "Failed to build //a:test"

  remote_ac_files="$(count_remote_ac_files)"
  [[ "$remote_ac_files" == 0 ]] || fail "Expected 0 remote action cache entries, not $remote_ac_files"
  remote_cas_files="$(count_remote_cas_files)"
  [[ "$remote_cas_files" == 0 ]] || fail "Expected 0 remote cas entries, not $remote_cas_files"
}

function test_repo_remote_exec() {
  # Test that repository_ctx.execute can execute a command remotely.

  touch BUILD

  cat > test.bzl <<'EOF'
def _impl(ctx):
  res = ctx.execute(["/bin/bash", "-c", "echo -n $BAZEL_REMOTE_PLATFORM"])
  if res.return_code != 0:
    fail("Return code 0 expected, but was " + res.return_code)

  entries = res.stdout.split(",")
  if len(entries) != 2:
    fail("Two platform kv pairs expected. Got:" + str(entries))
  if entries[0] != "ISA=x86-64":
    fail("'ISA' expected in remote platform'")
  if entries[1] != "OSFamily=Linux":
    fail("'OSFamily' expected in remote platform'")

  ctx.file("BUILD")

foo_configure = repository_rule(
  implementation = _impl,
  remotable = True,
)
EOF

  cat > WORKSPACE <<'EOF'
load("//:test.bzl", "foo_configure")

foo_configure(
  name = "default_foo",
  exec_properties = {
    "OSFamily" : "Linux",
    "ISA" : "x86-64",
  }
)
EOF

  bazel fetch \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_repo_remote_exec \
    @default_foo//:all
}

function test_repo_remote_exec_path_argument() {
  # Test that repository_ctx.execute fails with a descriptive error message
  # if a path argument is provided. The upload of files as part of command
  # execution is not yet supported.

  touch BUILD

  echo "hello world" > input.txt

  cat > test.bzl <<'EOF'
def _impl(ctx):
  ctx.execute(["cat", ctx.path("input.txt")])
  ctx.file("BUILD")

foo_configure = repository_rule(
  implementation = _impl,
  remotable = True,
)
EOF

  cat > WORKSPACE <<'EOF'
load("//:test.bzl", "foo_configure")

foo_configure(
  name = "default_foo",
)
EOF

  bazel fetch \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_repo_remote_exec \
    @default_foo//:all  >& $TEST_log && fail "Should fail" || true

  expect_log "Argument 1 of execute is neither a label nor a string"
}

function test_repo_remote_exec_timeout() {
  # Test that a remote job is killed if it exceeds the timeout.

  touch BUILD

  cat > test.bzl <<'EOF'
def _impl(ctx):
  ctx.execute(["/bin/bash","-c",
    "for i in {1..3}; do echo \"Sleeping $i...\" && sleep 1; done"], timeout=1)
  ctx.file("BUILD")

foo_configure = repository_rule(
  implementation = _impl,
  remotable = True,
)
EOF

  cat > WORKSPACE <<'EOF'
load("//:test.bzl", "foo_configure")

foo_configure(
  name = "default_foo",
)
EOF

  bazel fetch \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_repo_remote_exec \
    @default_foo//:all >& $TEST_log && fail "Should fail" || true

  expect_log "exceeded deadline"
}

function test_repo_remote_exec_file_upload() {
  # Test that repository_ctx.execute accepts arguments of type label and can upload files and
  # execute them remotely.

cat > BUILD <<'EOF'
  exports_files(["cmd.sh", "hello.txt"])
EOF

  cat > cmd.sh <<'EOF'
#!/bin/sh
cat $1
EOF

  chmod +x cmd.sh

  echo "hello world" > hello.txt

  cat > test.bzl <<'EOF'
def _impl(ctx):
  script = Label("//:cmd.sh")
  file = Label("//:hello.txt")

  res = ctx.execute([script, file])

  if res.return_code != 0:
    fail("Return code 0 expected, but was " + res.return_code)

  if res.stdout.strip() != "hello world":
    fail("Stdout 'hello world' expected, but was '" + res.stdout + "'");

  ctx.file("BUILD")

remote_foo_configure = repository_rule(
  implementation = _impl,
  remotable = True,
)

local_foo_configure = repository_rule(
  implementation = _impl,
)
EOF

  cat > WORKSPACE <<'EOF'
load("//:test.bzl", "remote_foo_configure", "local_foo_configure")

remote_foo_configure(
  name = "remote_foo",
)

local_foo_configure(
  name = "local_foo",
)
EOF

  bazel fetch \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_repo_remote_exec \
    @remote_foo//:all

  # '--expunge' is necessary in order to ensure that the repository is re-executed.
  bazel clean --expunge

  # Run on the host machine to test that the rule works for both local and remote execution.
  # In particular, that arguments of type label are accepted when doing local execution.
  bazel fetch \
    --experimental_repo_remote_exec \
    @remote_foo//:all

  bazel clean --expunge

  # Execute @local_foo which has the same implementation as @remote_foo but not the 'remotable'
  # attribute. This tests that a non-remotable repo rule can also run a remotable implementation
  # function.
  bazel fetch \
    --experimental_repo_remote_exec \
    @local_foo//:all
}

function test_remote_cache_intermediate_outputs() {
  # test that remote cache is hit when intermediate output is not executable
  touch WORKSPACE
  cat > BUILD <<'EOF'
genrule(
  name = "dep",
  srcs = [],
  outs = ["dep"],
  cmd = "echo 'dep' > $@",
)

genrule(
  name = "test",
  srcs = [":dep"],
  outs = ["out"],
  cmd = "cat $(SRCS) > $@",
)
EOF

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    //:test >& $TEST_log || fail "Failed to build //:test"

  bazel clean

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    //:test >& $TEST_log || fail "Failed to build //:test"

  expect_log "2 remote cache hit"
}

function setup_exclusive_test_case() {
  mkdir -p a
  cat > a/success.sh <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod 755 a/success.sh
  cat > a/BUILD <<'EOF'
sh_test(
  name = "success_test",
  srcs = ["success.sh"],
  tags = ["exclusive"],
)
EOF
}

function test_exclusive_test_hit_remote_cache() {
  # Test that the exclusive test works with the remote cache.
  setup_exclusive_test_case

  # Warm up the cache
  bazel test \
    --remote_cache=grpc://localhost:${worker_port} \
    //a:success_test || fail "Failed to test //a:success_test"

  bazel clean

  bazel test \
    --remote_cache=grpc://localhost:${worker_port} \
    //a:success_test >& $TEST_log || fail "Failed to test //a:success_test"

  # test action + test xml generation
  expect_log "2 remote cache hit"
}

function test_exclusive_test_and_no_cache_test_results() {
  # Test that the exclusive test won't hit the remote cache if
  # --nocache_test_results is set.
  setup_exclusive_test_case

  # Warm up the cache
  bazel test \
    --remote_cache=grpc://localhost:${worker_port} \
    //a:success_test || fail "Failed to test //a:success_test"

  bazel clean

  bazel test \
    --remote_cache=grpc://localhost:${worker_port} \
    --nocache_test_results \
    //a:success_test >& $TEST_log || fail "Failed to test //a:success_test"

  # test action
  expect_log "1.*-sandbox"
  # test xml generation
  expect_log "1 remote cache hit"
}

function test_exclusive_test_wont_remote_exec() {
  # Test that the exclusive test won't execute remotely.
  setup_exclusive_test_case

  bazel test \
    --remote_executor=grpc://localhost:${worker_port} \
    //a:success_test >& $TEST_log || fail "Failed to test //a:success_test"

  # test action + test xml generation
  expect_log "2.*-sandbox"
}

# TODO(alpha): Add a test that fails remote execution when remote worker
# supports sandbox.

# This test uses the flag experimental_split_coverage_postprocessing. Without
# the flag coverage won't work remotely. Without the flag, tests and coverage
# post-processing happen in the same spawn, but only the runfiles tree of the
# tests is made available to the spawn. The solution was not to merge the
# runfiles tree which could cause its own problems but to split both into
# different spawns. The reason why this only failed remotely and not locally was
# because the coverage post-processing tool escaped the sandbox to find its own
# runfiles. The error we would see here without the flag would be "Cannot find
# runfiles". See #4685.
function test_java_rbe_coverage_produces_report() {
  mkdir -p java/factorial

  JAVA_TOOLS_ZIP="released"
  COVERAGE_GENERATOR_DIR="released"

  cd java/factorial

  cat > BUILD <<'EOF'
java_library(
    name = "fact",
    srcs = ["Factorial.java"],
)

java_test(
    name = "fact-test",
    size = "small",
    srcs = ["FactorialTest.java"],
    test_class = "factorial.FactorialTest",
    deps = [
        ":fact",
    ],
)

EOF

  cat > Factorial.java <<'EOF'
package factorial;

public class Factorial {
  public static int factorial(int x) {
    return x <= 0 ? 1 : x * factorial(x-1);
  }
}
EOF

  cat > FactorialTest.java <<'EOF'
package factorial;

import static org.junit.Assert.*;

import org.junit.Test;

public class FactorialTest {
  @Test
  public void testFactorialOfZeroIsOne() throws Exception {
    assertEquals(Factorial.factorial(3),6);
  }
}
EOF
  cd ../..

  bazel coverage \
    --test_output=all \
    --experimental_fetch_all_coverage_outputs \
    --experimental_split_coverage_postprocessing \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --instrumentation_filter=//java/factorial \
    //java/factorial:fact-test >& $TEST_log || fail "Shouldn't fail"

  local expected_result="SF:java/factorial/Factorial.java
FN:3,factorial/Factorial::<init> ()V
FN:5,factorial/Factorial::factorial (I)I
FNDA:0,factorial/Factorial::<init> ()V
FNDA:1,factorial/Factorial::factorial (I)I
FNF:2
FNH:1
BRDA:5,0,0,1
BRDA:5,0,1,1
BRF:2
BRH:2
DA:3,0
DA:5,1
LH:1
LF:2
end_of_record"

  assert_equals "$expected_result" "$(cat bazel-testlogs/java/factorial/fact-test/coverage.dat)"
}

function generate_empty_tree_artifact_as_inputs() {
  touch WORKSPACE
  mkdir -p pkg

  cat > pkg/def.bzl <<'EOF'
def _r(ctx):
    empty_d = ctx.actions.declare_directory("%s/empty_dir" % ctx.label.name)
    ctx.actions.run_shell(
        outputs = [empty_d],
        command = "mkdir -p %s" % empty_d.path,
    )
    f = ctx.actions.declare_file("%s/file" % ctx.label.name)
    ctx.actions.run_shell(
        inputs = [empty_d],
        outputs = [f],
        command = "touch %s && cd %s && pwd" % (f.path, empty_d.path),
    )
    return [DefaultInfo(files = depset([f]))]

r = rule(implementation = _r)
EOF

cat > pkg/BUILD <<'EOF'
load(":def.bzl", "r")

r(name = "a")
EOF
}

function test_empty_tree_artifact_as_inputs() {
  # Test that when an empty tree artifact is the input, an empty directory is
  # created in the remote executor for action to read.
  generate_empty_tree_artifact_as_inputs

  bazel build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    //pkg:a &>$TEST_log || fail "expected build to succeed"

  bazel clean --expunge
  bazel build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_remote_merkle_tree_cache \
    //pkg:a &>$TEST_log || fail "expected build to succeed with Merkle tree cache"

  bazel clean --expunge
  bazel build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_remote_discard_merkle_trees \
    //pkg:a &>$TEST_log || fail "expected build to succeed with Merkle tree discarding"

  bazel clean --expunge
  bazel build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_sibling_repository_layout \
    //pkg:a &>$TEST_log || fail "expected build to succeed with sibling repository layout"
}

function test_empty_tree_artifact_as_inputs_remote_cache() {
  # Test that when empty tree artifact works for remote cache.
  generate_empty_tree_artifact_as_inputs

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    //pkg:a &>$TEST_log || fail "expected build to succeed"

  bazel clean

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    //pkg:a &>$TEST_log || fail "expected build to succeed"

  expect_log "remote cache hit"
}

function generate_tree_artifact_output() {
  touch WORKSPACE
  mkdir -p pkg

  cat > pkg/def.bzl <<'EOF'
def _r(ctx):
    empty_dir = ctx.actions.declare_directory("%s/empty_dir" % ctx.label.name)
    ctx.actions.run_shell(
        outputs = [empty_dir],
        command = "cd %s && pwd" % empty_dir.path,
    )
    non_empty_dir = ctx.actions.declare_directory("%s/non_empty_dir" % ctx.label.name)
    ctx.actions.run_shell(
        outputs = [non_empty_dir],
        command = "cd %s && pwd && touch out" % non_empty_dir.path,
    )
    return [DefaultInfo(files = depset([empty_dir, non_empty_dir]))]

r = rule(implementation = _r)
EOF

cat > pkg/BUILD <<'EOF'
load(":def.bzl", "r")

r(name = "a")
EOF
}

function test_create_tree_artifact_outputs() {
  # Test that if a tree artifact is declared as an input, then the corresponding
  # empty directory is created before the action executes remotely.
  generate_tree_artifact_output

  bazel build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    //pkg:a &>$TEST_log || fail "expected build to succeed"
  [[ -f bazel-bin/pkg/a/non_empty_dir/out ]] || fail "expected tree artifact to contain a file"
  [[ -d bazel-bin/pkg/a/empty_dir ]] || fail "expected directory to exist"

  bazel clean --expunge
  bazel build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_remote_merkle_tree_cache \
    //pkg:a &>$TEST_log || fail "expected build to succeed with Merkle tree cache"
  [[ -f bazel-bin/pkg/a/non_empty_dir/out ]] || fail "expected tree artifact to contain a file"
  [[ -d bazel-bin/pkg/a/empty_dir ]] || fail "expected directory to exist"

  bazel clean --expunge
  bazel build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_remote_discard_merkle_trees \
    //pkg:a &>$TEST_log || fail "expected build to succeed with Merkle tree discarding"
  [[ -f bazel-bin/pkg/a/non_empty_dir/out ]] || fail "expected tree artifact to contain a file"
  [[ -d bazel-bin/pkg/a/empty_dir ]] || fail "expected directory to exist"

  bazel clean --expunge
  bazel build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_sibling_repository_layout \
    //pkg:a &>$TEST_log || fail "expected build to succeed with sibling repository layout"
  [[ -f bazel-bin/pkg/a/non_empty_dir/out ]] || fail "expected tree artifact to contain a file"
  [[ -d bazel-bin/pkg/a/empty_dir ]] || fail "expected directory to exist"
}

function test_create_tree_artifact_outputs_remote_cache() {
  # Test that implicitly created empty directories corresponding to empty tree
  # artifacts outputs are correctly cached in the remote cache.
  generate_tree_artifact_output

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    //pkg:a &>$TEST_log || fail "expected build to succeed"

  bazel clean

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    //pkg:a &>$TEST_log || fail "expected build to succeed"

  expect_log "2 remote cache hit"
  [[ -f bazel-bin/pkg/a/non_empty_dir/out ]] || fail "expected tree artifact to contain a file"
  [[ -d bazel-bin/pkg/a/empty_dir ]] || fail "expected directory to exist"
}

function test_symlink_in_tree_artifact() {
  mkdir -p pkg

  cat > pkg/defs.bzl <<EOF
def _impl(ctx):
  d = ctx.actions.declare_directory(ctx.label.name)
  ctx.actions.run_shell(
    outputs = [d],
    command = "cd %s && touch foo && ln -s foo sym" % d.path,
  )
  return DefaultInfo(files = depset([d]))

tree = rule(implementation = _impl)
EOF

  cat > pkg/BUILD <<EOF
load(":defs.bzl", "tree")

tree(name = "tree")
EOF

  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      --noincompatible_remote_disallow_symlink_in_tree_artifact \
      //pkg:tree &>$TEST_log || fail "Expected build to succeed"

  bazel clean

  bazel build \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      //pkg:tree &>$TEST_log && fail "Expected build to fail"

  expect_log "Unsupported symlink 'sym' inside tree artifact"
}

# Runs coverage with `cc_test` and RE then checks the coverage file is returned.
# Older versions of gcov are not supported with bazel coverage and so will be skipped.
# See the above `test_java_rbe_coverage_produces_report` for more information.
function test_cc_rbe_coverage_produces_report() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  # Check to see if intermediate files are supported, otherwise skip.
  gcov --help | grep "\-i," || return 0

  local test_dir="a/cc/coverage_test"
  mkdir -p $test_dir

  cat > "$test_dir"/BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])

cc_library(
    name = "hello-lib",
    srcs = ["hello-lib.cc"],
    hdrs = ["hello-lib.h"],
)

cc_binary(
    name = "hello-world",
    srcs = ["hello-world.cc"],
    deps = [":hello-lib"],
)

cc_test(
    name = "hello-test",
    srcs = ["hello-world.cc"],
    deps = [":hello-lib"],
)

EOF

  cat > "$test_dir"/hello-lib.cc <<'EOF'
#include "hello-lib.h"

#include <iostream>

using std::cout;
using std::endl;
using std::string;

namespace hello {

HelloLib::HelloLib(const string& greeting) : greeting_(new string(greeting)) {
}

void HelloLib::greet(const string& thing) {
  cout << *greeting_ << " " << thing << endl;
}

}  // namespace hello

EOF

  cat > "$test_dir"/hello-lib.h <<'EOF'
#ifndef HELLO_LIB_H_
#define HELLO_LIB_H_

#include <string>
#include <memory>

namespace hello {

class HelloLib {
 public:
  explicit HelloLib(const std::string &greeting);

  void greet(const std::string &thing);

 private:
  std::unique_ptr<const std::string> greeting_;
};

}  // namespace hello

#endif  // HELLO_LIB_H_

EOF

  cat > "$test_dir"/hello-world.cc <<'EOF'
#include "hello-lib.h"

#include <string>

using hello::HelloLib;
using std::string;

int main(int argc, char** argv) {
  HelloLib lib("Hello");
  string thing = "world";
  if (argc > 1) {
    thing = argv[1];
  }
  lib.greet(thing);
  return 0;
}

EOF

  bazel coverage \
      --test_output=all \
      --experimental_fetch_all_coverage_outputs \
      --experimental_split_coverage_postprocessing \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      //"$test_dir":hello-test >& $TEST_log \
      || fail "Failed to run coverage for cc_test"

  # Different gcov versions generate different outputs.
  # Simply check if this is empty or not.
  if [[ ! -s bazel-testlogs/a/cc/coverage_test/hello-test/coverage.dat ]]; then
    echo "Coverage is empty. Failing now."
    return 1
  fi
}

# Runs coverage with `cc_test` using llvm-cov and RE, then checks that the coverage file is
# returned non-empty.
# See the above `test_java_rbe_coverage_produces_report` for more information.
function test_cc_rbe_coverage_produces_report_with_llvm() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  local -r clang=$(which clang)
  if [[ ! -x "${clang}" ]]; then
    echo "clang not installed. Skipping"
    return 0
  fi
  local -r clang_version=$(clang --version | grep -o "clang version [0-9]*" | cut -d " " -f 3)
  if [ "$clang_version" -lt 9 ];  then
    # No lcov produced with <9.0.
    echo "clang versions <9.0 are not supported, got $clang_version. Skipping."
    return 0
  fi

  if ! type -P llvm-cov; then
    echo "llvm-cov not found. Skipping."
    return 0
  fi
  if ! type -P llvm-profdata; then
    echo "llvm-profdata not found. Skipping."
    return 0
  fi

  local test_dir="a/cc/coverage_test"
  mkdir -p $test_dir

  cat > "$test_dir"/BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])

cc_library(
    name = "hello-lib",
    srcs = ["hello-lib.cc"],
    hdrs = ["hello-lib.h"],
)

cc_binary(
    name = "hello-world",
    srcs = ["hello-world.cc"],
    deps = [":hello-lib"],
)

cc_test(
    name = "hello-test",
    srcs = ["hello-world.cc"],
    deps = [":hello-lib"],
)

EOF

  cat > "$test_dir"/hello-lib.cc <<'EOF'
#include "hello-lib.h"

#include <iostream>

using std::cout;
using std::endl;
using std::string;

namespace hello {

HelloLib::HelloLib(const string& greeting) : greeting_(new string(greeting)) {
}

void HelloLib::greet(const string& thing) {
  cout << *greeting_ << " " << thing << endl;
}

}  // namespace hello

EOF

  cat > "$test_dir"/hello-lib.h <<'EOF'
#ifndef HELLO_LIB_H_
#define HELLO_LIB_H_

#include <string>
#include <memory>

namespace hello {

class HelloLib {
 public:
  explicit HelloLib(const std::string &greeting);

  void greet(const std::string &thing);

 private:
  std::unique_ptr<const std::string> greeting_;
};

}  // namespace hello

#endif  // HELLO_LIB_H_

EOF

  cat > "$test_dir"/hello-world.cc <<'EOF'
#include "hello-lib.h"

#include <string>

using hello::HelloLib;
using std::string;

int main(int argc, char** argv) {
  HelloLib lib("Hello");
  string thing = "world";
  if (argc > 1) {
    thing = argv[1];
  }
  lib.greet(thing);
  return 0;
}

EOF

  BAZEL_USE_LLVM_NATIVE_COVERAGE=1 GCOV=llvm-profdata BAZEL_LLVM_COV=llvm-cov CC=clang \
    bazel coverage \
      --test_output=all \
      --experimental_fetch_all_coverage_outputs \
      --experimental_generate_llvm_lcov \
      --experimental_split_coverage_postprocessing \
      --spawn_strategy=remote \
      --remote_executor=grpc://localhost:${worker_port} \
      //"$test_dir":hello-test >& $TEST_log \
      || fail "Failed to run coverage for cc_test"

  # Different LLVM versions generate different outputs.
  # Simply check if this is empty or not.
  if [[ ! -s bazel-testlogs/a/cc/coverage_test/hello-test/coverage.dat ]]; then
    echo "Coverage is empty. Failing now."
    return 1
  fi
}

function test_grpc_connection_errors_are_propagated() {
  # Test that errors when creating grpc connection are propagated instead of crashing Bazel.
  # https://github.com/bazelbuild/bazel/issues/13724

  mkdir -p a
  cat > a/BUILD <<EOF
genrule(
  name = 'foo',
  outs = ["foo.txt"],
  cmd = "echo \"foo bar\" > \$@",
)
EOF

  bazel build \
      --remote_executor=grpcs://localhost:${worker_port} \
      --tls_certificate=/nope \
      //a:foo >& $TEST_log && fail "Expected to fail" || true

  expect_log "ERROR: Failed to query remote execution capabilities: Failed to init TLS infrastructure using '/nope' as root certificate: File does not contain valid certificates: /nope"
}

function test_async_upload_works_for_flaky_tests() {
  mkdir -p a
  cat > a/BUILD <<EOF
sh_test(
    name = "test",
    srcs = ["test.sh"],
)

genrule(
  name = "foo",
  outs = ["foo.txt"],
  cmd = "echo \"foo bar\" > \$@",
)
EOF
  cat > a/test.sh <<EOF
#!/bin/sh
echo "it always fails"
exit 1
EOF
  chmod +x a/test.sh

  # Check the error message when failed to upload
  bazel build --remote_cache=http://nonexistent.example.org //a:foo >& $TEST_log || fail "Failed to build"
  expect_log "WARNING: Remote Cache:"

  bazel test \
    --remote_cache=grpc://localhost:${worker_port} \
    --experimental_remote_cache_async \
    --flaky_test_attempts=2 \
    //a:test >& $TEST_log  && fail "expected failure" || true
  expect_not_log "WARNING: Remote Cache:"
}

function test_missing_outputs_dont_upload_action_result() {
  # Test that if declared outputs are not created, even the exit code of action
  # is 0, we treat this as failed and don't upload action result.
  # See https://github.com/bazelbuild/bazel/issues/14543.
  mkdir -p a
  cat > a/BUILD <<EOF
genrule(
  name = 'foo',
  outs = ["foo.txt"],
  cmd = "echo foo-generation-error",
)
EOF

  bazel build \
      --remote_cache=grpc://localhost:${worker_port} \
      //a:foo >& $TEST_log && fail "Should failed to build"

  expect_log "foo-generation-error"
  remote_cas_files="$(count_remote_cas_files)"
  [[ "$remote_cas_files" == 0 ]] || fail "Expected 0 remote cas entries, not $remote_cas_files"
  remote_ac_files="$(count_remote_ac_files)"
  [[ "$remote_ac_files" == 0 ]] || fail "Expected 0 remote action cache entries, not $remote_ac_files"
}

function test_failed_action_dont_check_declared_outputs() {
  # Test that if action failed, outputs are not checked

  mkdir -p a
  cat > a/BUILD <<EOF
genrule(
  name = 'foo',
  outs = ["foo.txt"],
  cmd = "exit 1",
)
EOF

  bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      //a:foo >& $TEST_log && fail "Should failed to build"

  expect_log "Executing genrule .* failed: (Exit 1):"
}


function test_local_test_execution_with_disk_cache() {
  # Tests that the wall time for a locally executed test is correctly cached.
  # If not, the generate-xml.sh action, which embeds the wall time, will be
  # considered stale on a cache hit.
  # Regression test for https://github.com/bazelbuild/bazel/issues/14426.

  mkdir -p a
  cat > a/BUILD <<EOF
sh_test(
  name = 'test',
  srcs = ['test.sh'],
)
EOF
  cat > a/test.sh <<EOF
sleep 1
EOF
  chmod +x a/test.sh

  CACHEDIR=$(mktemp -d)

  bazel test \
    --disk_cache=$CACHEDIR \
    //a:test >& $TEST_log || fail "Failed to build //a:test"

  expect_log "6 processes: 4 internal, 2 .*-sandbox"

  bazel clean

  bazel test \
    --disk_cache=$CACHEDIR \
    //a:test >& $TEST_log || fail "Failed to build //a:test"

  expect_log "6 processes: 2 disk cache hit, 4 internal"
}

# Bazel assumes that non-ASCII characters in file contents (and, in
# non-Windows systems, file paths) are UTF-8, but stores them internally by
# parsing the raw UTF-8 bytes as if they were ISO-8859-1 characters.
#
# This test verifies that the inverse transformation is applied when sending
# these inputs through the remote execution protocol. The Protobuf libraries
# for Java assume that `String` values are encoded in UTF-16, and passing
# raw bytes will cause double-encoding.
function test_unicode() {
  # The in-tree remote execution worker only supports non-ASCII paths when
  # running in a UTF-8 locale.
  if ! "$is_windows"; then
    if ! has_utf8_locale; then
      echo "Skipping test due to lack of UTF-8 locale."
      echo "Available locales:"
      locale -a
      return
    fi
  fi

  # Restart the remote execution worker with LC_ALL set.
  tear_down
  LC_ALL=en_US.UTF-8 set_up

  # The test inputs contain both globbed and unglobbed input paths, to verify
  # consistent behavior on Windows where file paths are Unicode but Starlark
  # strings are UTF-8.
  cat > BUILD <<EOF
load("//:rules.bzl", "test_unicode")
test_unicode(
  name = "test_unicode",
  inputs = glob(["inputs/**/A_*"]) + [
    "inputs/入力/B_🌱.txt",
  ],
)
EOF

  cat > rules.bzl <<'EOF'
def _test_unicode(ctx):
  out_dir = ctx.actions.declare_directory(ctx.attr.name + "_outs/出力/🌱.d")
  out_file = ctx.actions.declare_file(ctx.attr.name + "_outs/出力/🌱.txt")
  out_report = ctx.actions.declare_file(ctx.attr.name + "_report.txt")
  ctx.actions.run_shell(
    inputs = ctx.files.inputs,
    outputs = [out_dir, out_file, out_report],
    command = """
set -eu

report="$3"
touch "${report}"

echo '[input tree]' >> "${report}"
find inputs | sort >> "${report}"
echo '' >> "${report}"

echo '[input file A]' >> "${report}"
cat $'inputs/\\xe5\\x85\\xa5\\xe5\\x8a\\x9b/A_\\xf0\\x9f\\x8c\\xb1.txt' >> "${report}"
echo '' >> "${report}"

echo '[input file B]' >> "${report}"
cat $'inputs/\\xe5\\x85\\xa5\\xe5\\x8a\\x9b/B_\\xf0\\x9f\\x8c\\xb1.txt' >> "${report}"
echo '' >> "${report}"

echo '[environment]' >> "${report}"
env | grep -v BASH_EXECUTION_STRING | grep TEST_UNICODE_ >> "${report}"
echo '' >> "${report}"

mkdir -p "$1"
echo 'output dir content' > "$1"/dir_content.txt
echo 'output file content' > "$2"
""",
    arguments = [out_dir.path, out_file.path, out_report.path],
    env = {"TEST_UNICODE_🌱": "🌱"},
  )
  return DefaultInfo(files=depset([out_dir, out_file, out_report]))

test_unicode = rule(
  implementation = _test_unicode,
  attrs = {
    "inputs": attr.label_list(allow_files = True),
  },
)
EOF

  # inputs/入力/A_🌱.txt and inputs/入力/B_🌱.txt
  mkdir -p $'inputs/\xe5\x85\xa5\xe5\x8a\x9b'
  echo 'input content A' > $'inputs/\xe5\x85\xa5\xe5\x8a\x9b/A_\xf0\x9f\x8c\xb1.txt'
  echo 'input content B' > $'inputs/\xe5\x85\xa5\xe5\x8a\x9b/B_\xf0\x9f\x8c\xb1.txt'

  # On UNIX platforms, Bazel assumes that file paths are encoded in UTF-8. The
  # system must have either an ISO-8859-1 or UTF-8 locale available so that
  # Bazel can read the original bytes of the file path.
  #
  # If no ISO-8859-1 locale is available, the JVM might fall back to US-ASCII
  # rather than trying UTF-8. Setting `LC_ALL=en_US.UTF-8` prevents this.
  bazel shutdown
  LC_ALL=en_US.UTF-8 bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      //:test_unicode >& $TEST_log \
      || fail "Failed to build //:test_unicode with remote execution"
  expect_log "2 processes: 1 internal, 1 remote."

  # Don't leak LC_ALL into other tests.
  bazel shutdown

  # Expect action outputs with correct structure and content.
  mkdir -p ${TEST_TMPDIR}/$'test_unicode_outs/\xe5\x87\xba\xe5\x8a\x9b/\xf0\x9f\x8c\xb1.d'
  cat > ${TEST_TMPDIR}/$'test_unicode_outs/\xe5\x87\xba\xe5\x8a\x9b/\xf0\x9f\x8c\xb1.d/dir_content.txt' <<EOF
output dir content
EOF
  cat > ${TEST_TMPDIR}/$'test_unicode_outs/\xe5\x87\xba\xe5\x8a\x9b/\xf0\x9f\x8c\xb1.txt' <<EOF
output file content
EOF

  diff -r bazel-bin/test_unicode_outs ${TEST_TMPDIR}/test_unicode_outs \
      || fail "Remote execution generated different result"

  cat > ${TEST_TMPDIR}/test_report_expected <<EOF
[input tree]
inputs
inputs/入力
inputs/入力/A_🌱.txt
inputs/入力/B_🌱.txt

[input file A]
input content A

[input file B]
input content B

[environment]
TEST_UNICODE_🌱=🌱

EOF
  diff bazel-bin/test_unicode_report.txt ${TEST_TMPDIR}/test_report_expected \
      || fail "Remote execution generated different result"
}

function setup_external_cc_test() {
  cat >> WORKSPACE <<'EOF'
local_repository(
  name = "other_repo",
  path = "other_repo",
)
EOF

  mkdir -p other_repo
  touch other_repo/WORKSPACE

  mkdir -p other_repo/lib
  cat > other_repo/lib/BUILD <<'EOF'
cc_library(
  name = "lib",
  srcs = ["lib.cpp"],
  hdrs = ["lib.h"],
  visibility = ["//visibility:public"],
)
EOF
  cat > other_repo/lib/lib.h <<'EOF'
void print_greeting();
EOF
  cat > other_repo/lib/lib.cpp <<'EOF'
#include <cstdio>
void print_greeting() {
  printf("Hello, world!\n");
}
EOF

  mkdir -p other_repo/test
  cat > other_repo/test/BUILD <<'EOF'
cc_test(
  name = "test",
  srcs = ["test.cpp"],
  deps = ["//lib"],
)
EOF
  cat > other_repo/test/test.cpp <<'EOF'
#include "lib/lib.h"
int main() {
  print_greeting();
}
EOF
}

function test_external_cc_test() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  setup_external_cc_test

  bazel test \
      --test_output=errors \
      --remote_executor=grpc://localhost:${worker_port} \
      @other_repo//test >& $TEST_log || fail "Test should pass"
}

function test_external_cc_test_sibling_repository_layout() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  setup_external_cc_test

  bazel test \
      --test_output=errors \
      --remote_executor=grpc://localhost:${worker_port} \
      --experimental_sibling_repository_layout \
      @other_repo//test >& $TEST_log || fail "Test should pass"
}

function do_test_unresolved_symlink() {
  local -r strategy=$1
  local -r link_target=$2

  mkdir -p symlink
  touch symlink/BUILD
  cat > symlink/symlink.bzl <<'EOF'
def _unresolved_symlink_impl(ctx):
  symlink = ctx.actions.declare_symlink(ctx.label.name)

  if ctx.attr.strategy == "internal":
    ctx.actions.symlink(
      output = symlink,
      target_path = ctx.attr.link_target,
    )
  elif ctx.attr.strategy == "spawn":
    ctx.actions.run_shell(
      outputs = [symlink],
      command = "ln -s $1 $2",
      arguments = [ctx.attr.link_target, symlink.path],
    )

  return DefaultInfo(files = depset([symlink]))

unresolved_symlink = rule(
  implementation = _unresolved_symlink_impl,
  attrs = {
    "link_target": attr.string(mandatory = True),
    "strategy": attr.string(values = ["internal", "spawn"], mandatory = True),
  },
)
EOF

  mkdir -p pkg
  cat > pkg/BUILD <<EOF
load("//symlink:symlink.bzl", "unresolved_symlink")
unresolved_symlink(name="a", link_target="$link_target", strategy="$strategy")
genrule(
    name = "b",
    srcs = [":a"],
    outs = ["b.txt"],
    cmd = "readlink \$(location :a) > \$@",
)
EOF

  bazel \
    --windows_enable_symlinks \
    build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    //pkg:b &>$TEST_log || fail "expected build to succeed"

  if [[ "$(cat bazel-bin/pkg/b.txt)" != "$link_target" ]]; then
    fail "expected symlink target to be $link_target"
  fi

  bazel clean --expunge
  bazel \
    --windows_enable_symlinks \
    build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_remote_merkle_tree_cache \
    //pkg:b &>$TEST_log || fail "expected build to succeed with Merkle tree cache"

  bazel clean --expunge
  bazel \
    --windows_enable_symlinks \
    build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --experimental_remote_discard_merkle_trees \
    //pkg:b &>$TEST_log || fail "expected build to succeed with Merkle tree discarding"

  if [[ "$(cat bazel-bin/pkg/b.txt)" != "$link_target" ]]; then
    fail "expected symlink target to be $link_target"
  fi
}

function test_unresolved_symlink_internal_relative() {
  do_test_unresolved_symlink internal non/existent
}

function test_unresolved_symlink_internal_absolute() {
  do_test_unresolved_symlink internal /non/existent
}

function test_unresolved_symlink_spawn_relative() {
  do_test_unresolved_symlink spawn non/existent
}

function test_unresolved_symlink_spawn_absolute() {
  do_test_unresolved_symlink spawn /non/existent
}

function setup_cc_binary_tool_with_dynamic_deps() {
  local repo=$1

  cat >> WORKSPACE <<'EOF'
local_repository(
  name = "other_repo",
  path = "other_repo",
)
EOF

  mkdir -p $repo
  touch $repo/WORKSPACE

  mkdir -p $repo/lib
  # Use a comma in the target name as that is known to be problematic whith -Wl,
  # which is commonly used to pass rpaths to the linker.
  cat > $repo/lib/BUILD <<'EOF'
cc_binary(
  name = "l,ib",
  srcs = ["lib.cpp"],
  linkshared = True,
  linkstatic = True,
)

cc_import(
  name = "dynamic_l,ib",
  shared_library = ":l,ib",
  hdrs = ["lib.h"],
  visibility = ["//visibility:public"],
)
EOF
  cat > $repo/lib/lib.h <<'EOF'
void print_greeting();
EOF
  cat > $repo/lib/lib.cpp <<'EOF'
#include <cstdio>
void print_greeting() {
  printf("Hello, world!\n");
}
EOF

  mkdir -p $repo/pkg
  cat > $repo/pkg/BUILD <<'EOF'
cc_binary(
  name = "tool",
  srcs = ["tool.cpp"],
  deps = ["//lib:dynamic_l,ib"],
)

genrule(
  name = "rule",
  outs = ["out"],
  cmd = "$(location :tool) > $@",
  tools = [":tool"],
)
EOF
  cat > $repo/pkg/tool.cpp <<'EOF'
#include "lib/lib.h"
int main() {
  print_greeting();
}
EOF
}

function test_cc_binary_tool_with_dynamic_deps() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  setup_cc_binary_tool_with_dynamic_deps .

  bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      //pkg:rule >& $TEST_log || fail "Build should succeed"
}

function test_cc_binary_tool_with_dynamic_deps_sibling_repository_layout() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  setup_cc_binary_tool_with_dynamic_deps .

  bazel build \
      --experimental_sibling_repository_layout \
      --remote_executor=grpc://localhost:${worker_port} \
      //pkg:rule >& $TEST_log || fail "Build should succeed"
}

function test_external_cc_binary_tool_with_dynamic_deps() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  setup_cc_binary_tool_with_dynamic_deps other_repo

  bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      @other_repo//pkg:rule >& $TEST_log || fail "Build should succeed"
}

function test_external_cc_binary_tool_with_dynamic_deps_sibling_repository_layout() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  setup_cc_binary_tool_with_dynamic_deps other_repo

  bazel build \
      --experimental_sibling_repository_layout \
      --remote_executor=grpc://localhost:${worker_port} \
      @other_repo//pkg:rule >& $TEST_log || fail "Build should succeed"
}

function test_shard_status_file_checked_remote_download_minimal() {
  cat <<'EOF' > BUILD
sh_test(
    name = 'x',
    srcs = ['x.sh'],
    shard_count = 2,
)
EOF
  touch x.sh
  chmod +x x.sh

  bazel test \
      --remote_executor=grpc://localhost:${worker_port} \
      --incompatible_check_sharding_support \
      --remote_download_minimal \
      //:x  &> $TEST_log && fail "expected failure"
  expect_log "Sharding requested, but the test runner did not advertise support for it by touching TEST_SHARD_STATUS_FILE."

  echo 'touch "$TEST_SHARD_STATUS_FILE"' > x.sh
  bazel test \
      --remote_executor=grpc://localhost:${worker_port} \
      --incompatible_check_sharding_support \
      --remote_download_minimal \
      //:x  &> $TEST_log || fail "expected success"
}

function test_cache_key_scrubbing() {
  echo foo > foo
  echo bar > bar
  cat<<'EOF' > BUILD
exports_files(["foo", "bar"])

label_flag(
  name = "src",
  build_setting_default = "//missing:target",
)

genrule(
  name = "gen",
  srcs = [":src"],
  outs = ["out"],
  cmd = "echo built > $@",
)
EOF
  cat<<'EOF' > scrubbing.cfg
rules {
  transform {
    omitted_inputs: "^(foo|bar)$"
  }
}
EOF

  # First build without a cache. Even though the remote cache keys can be
  # scrubbed, Bazel still considers the actions distinct.

  bazel build --experimental_remote_scrubbing_config=scrubbing.cfg \
        --//:src=//:foo :gen &> $TEST_log \
        || fail "failed to build with input foo and no cache"
    expect_log "2 processes: 1 internal, 1 .*-sandbox"

  bazel build --experimental_remote_scrubbing_config=scrubbing.cfg \
      --//:src=//:bar :gen &> $TEST_log \
      || fail "failed to build with input bar and no cache"
  expect_log "2 processes: 1 internal, 1 .*-sandbox"

  # Now build with a cache. Even though Bazel considers the actions distinct,
  # they will be looked up in the remote cache using the scrubbed key, so one
  # can serve as a cache hit for the other.

  CACHEDIR=$(mktemp -d)

  bazel build --experimental_remote_scrubbing_config=scrubbing.cfg \
        --disk_cache=$CACHEDIR --//:src=//:foo :gen &> $TEST_log \
        || fail "failed to build with input foo and a cache"
  expect_log "2 processes: 1 internal, 1 .*-sandbox"

  bazel build --experimental_remote_scrubbing_config=scrubbing.cfg \
      --disk_cache=$CACHEDIR --//:src=//:bar :gen &> $TEST_log \
      || fail "failed to build with input bar and a cache"
  expect_log "2 processes: 1 disk cache hit, 1 internal"
}

run_suite "Remote execution and remote cache tests"
