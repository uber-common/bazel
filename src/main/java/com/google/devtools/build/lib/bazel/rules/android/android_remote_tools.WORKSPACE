load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_jar")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "maybe")

# This must be kept in sync with the top-level WORKSPACE file.
maybe(
    http_archive,
    name = "android_tools",
    sha256 = "2b661a761a735b41c41b3a78089f4fc1982626c76ddb944604ae3ff8c545d3c2",  # DO_NOT_REMOVE_THIS_ANDROID_TOOLS_UPDATE_MARKER
    url = "https://mirror.bazel.build/bazel_android_tools/android_tools_pkg-0.30.0.tar",
)

# This must be kept in sync with the top-level WORKSPACE file.
maybe(
    http_jar,
    name = "android_gmaven_r8",
    sha256 = "4733945987ee0a840fafc34080b135259e01678412e07212b23f706334290294",
    url = "https://maven.google.com/com/android/tools/r8/8.5.35/r8-8.5.35.jar",
)
