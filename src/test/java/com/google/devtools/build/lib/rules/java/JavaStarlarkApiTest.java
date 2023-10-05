// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;
import static com.google.devtools.build.lib.rules.java.JavaCompileActionTestHelper.getProcessorNames;
import static com.google.devtools.build.lib.rules.java.JavaCompileActionTestHelper.getProcessorPath;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink;
import com.google.devtools.build.lib.rules.java.JavaPluginInfo.JavaPluginData;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.FileType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests Starlark API for Java rules. */
@RunWith(JUnit4.class)
public class JavaStarlarkApiTest extends BuildViewTestCase {
  @Before
  public void setupMyInfo() throws Exception {
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");

    scratch.file("myinfo/BUILD");
  }

  private StructImpl getMyInfoFromTarget(ConfiguredTarget configuredTarget) throws Exception {
    Provider.Key key =
        new StarlarkProvider.Key(Label.parseCanonical("//myinfo:myinfo.bzl"), "MyInfo");
    return (StructImpl) configuredTarget.get(key);
  }

  @Test
  public void testJavaRuntimeProviderJavaAbsolute() throws Exception {
    scratch.file(
        "a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "load('"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:java_toolchain_alias.bzl', 'java_runtime_alias')",
        "java_runtime(name='jvm', srcs=[], java_home='/foo/bar')",
        "java_runtime_alias(name='alias')",
        "jrule(name='r')",
        "toolchain(",
        "    name = 'java_runtime_toolchain',",
        "    toolchain = ':jvm',",
        "    toolchain_type = '"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:runtime_toolchain_type',",
        ")");

    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  provider = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]",
        "  return MyInfo(",
        "    java_home_exec_path = provider.java_home,",
        "    java_executable_exec_path = provider.java_executable_exec_path,",
        "    java_home_runfiles_path = provider.java_home_runfiles_path,",
        "    java_executable_runfiles_path = provider.java_executable_runfiles_path,",
        "  )",
        "jrule = rule(_impl, attrs = { '_java_runtime': attr.label(default=Label('//a:alias'))})");

    useConfiguration("--extra_toolchains=//a:all");
    ConfiguredTarget ct = getConfiguredTarget("//a:r");
    StructImpl myInfo = getMyInfoFromTarget(ct);
    String javaHomeExecPath = (String) myInfo.getValue("java_home_exec_path");
    assertThat(javaHomeExecPath).isEqualTo("/foo/bar");
    String javaExecutableExecPath = (String) myInfo.getValue("java_executable_exec_path");
    assertThat(javaExecutableExecPath).startsWith("/foo/bar/bin/java");
    String javaHomeRunfilesPath = (String) myInfo.getValue("java_home_runfiles_path");
    assertThat(javaHomeRunfilesPath).isEqualTo("/foo/bar");
    String javaExecutableRunfiles = (String) myInfo.getValue("java_executable_runfiles_path");
    assertThat(javaExecutableRunfiles).startsWith("/foo/bar/bin/java");
  }

  @Test
  public void testJavaRuntimeProviderJavaHermetic() throws Exception {
    scratch.file(
        "a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "load('"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:java_toolchain_alias.bzl', 'java_runtime_alias')",
        "java_runtime(name='jvm', srcs=[], java_home='foo/bar')",
        "java_runtime_alias(name='alias')",
        "jrule(name='r')",
        "toolchain(",
        "    name = 'java_runtime_toolchain',",
        "    toolchain = ':jvm',",
        "    toolchain_type = '"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:runtime_toolchain_type',",
        ")");

    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  provider = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]",
        "  return MyInfo(",
        "    java_home_exec_path = provider.java_home,",
        "    java_executable_exec_path = provider.java_executable_exec_path,",
        "    java_home_runfiles_path = provider.java_home_runfiles_path,",
        "    java_executable_runfiles_path = provider.java_executable_runfiles_path,",
        "  )",
        "jrule = rule(_impl, attrs = { '_java_runtime': attr.label(default=Label('//a:alias'))})");

    useConfiguration("--extra_toolchains=//a:all");
    ConfiguredTarget ct = getConfiguredTarget("//a:r");
    StructImpl myInfo = getMyInfoFromTarget(ct);
    String javaHomeExecPath = (String) myInfo.getValue("java_home_exec_path");
    assertThat(javaHomeExecPath).isEqualTo("a/foo/bar");
    String javaExecutableExecPath = (String) myInfo.getValue("java_executable_exec_path");
    assertThat(javaExecutableExecPath).startsWith("a/foo/bar/bin/java");
    String javaHomeRunfilesPath = (String) myInfo.getValue("java_home_runfiles_path");
    assertThat(javaHomeRunfilesPath).isEqualTo("a/foo/bar");
    String javaExecutableRunfiles = (String) myInfo.getValue("java_executable_runfiles_path");
    assertThat(javaExecutableRunfiles).startsWith("a/foo/bar/bin/java");
  }

  @Test
  public void testJavaRuntimeProviderJavaGenerated() throws Exception {
    scratch.file(
        "a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "load('"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:java_toolchain_alias.bzl', 'java_runtime_alias')",
        "genrule(name='gen', cmd='', outs=['foo/bar/bin/java'])",
        "java_runtime(name='jvm', srcs=[], java='foo/bar/bin/java')",
        "java_runtime_alias(name='alias')",
        "jrule(name='r')",
        "toolchain(",
        "    name = 'java_runtime_toolchain',",
        "    toolchain = ':jvm',",
        "    toolchain_type = '"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:runtime_toolchain_type',",
        ")");

    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  provider = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]",
        "  return MyInfo(",
        "    java_home_exec_path = provider.java_home,",
        "    java_executable_exec_path = provider.java_executable_exec_path,",
        "    java_home_runfiles_path = provider.java_home_runfiles_path,",
        "    java_executable_runfiles_path = provider.java_executable_runfiles_path,",
        "  )",
        "jrule = rule(_impl, attrs = { '_java_runtime': attr.label(default=Label('//a:alias'))})");

    useConfiguration("--extra_toolchains=//a:all");
    ConfiguredTarget genrule = getConfiguredTarget("//a:gen");
    ConfiguredTarget ct = getConfiguredTarget("//a:r");
    StructImpl myInfo = getMyInfoFromTarget(ct);
    String javaHomeExecPath = (String) myInfo.getValue("java_home_exec_path");
    assertThat(javaHomeExecPath)
        .isEqualTo(getGenfilesArtifact("foo/bar", genrule).getExecPathString());
    String javaExecutableExecPath = (String) myInfo.getValue("java_executable_exec_path");
    assertThat(javaExecutableExecPath)
        .startsWith(getGenfilesArtifact("foo/bar/bin/java", genrule).getExecPathString());
    String javaHomeRunfilesPath = (String) myInfo.getValue("java_home_runfiles_path");
    assertThat(javaHomeRunfilesPath).isEqualTo("a/foo/bar");
    String javaExecutableRunfiles = (String) myInfo.getValue("java_executable_runfiles_path");
    assertThat(javaExecutableRunfiles).startsWith("a/foo/bar/bin/java");
  }

  @Test
  public void testExposesJavaCommonProvider() throws Exception {
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")",
        "my_rule(",
        "  name = 'my',",
        "  dep = ':dep',",
        ")");
    scratch.file(
        "java/test/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   depj = ctx.attr.dep[java_common.provider]",
        "   return [result(",
        "             transitive_runtime_jars = depj.transitive_runtime_jars,",
        "             transitive_compile_time_jars = depj.transitive_compile_time_jars,",
        "             compile_jars = depj.compile_jars,",
        "             full_compile_jars = depj.full_compile_jars,",
        "             source_jars = depj.source_jars,",
        "             outputs = depj.outputs,",
        "          )]",
        "my_rule = rule(impl, attrs = { 'dep' : attr.label() })");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:my");
    StructImpl info =
        (StructImpl)
            configuredTarget.get(
                new StarlarkProvider.Key(
                    Label.parseCanonical("//java/test:extension.bzl"), "result"));

    Depset transitiveRuntimeJars = ((Depset) info.getValue("transitive_runtime_jars"));
    Depset transitiveCompileTimeJars = ((Depset) info.getValue("transitive_compile_time_jars"));
    Depset compileJars = ((Depset) info.getValue("compile_jars"));
    Depset fullCompileJars = ((Depset) info.getValue("full_compile_jars"));
    @SuppressWarnings("unchecked")
    Sequence<Artifact> sourceJars = ((Sequence<Artifact>) info.getValue("source_jars"));
    JavaRuleOutputJarsProvider outputs = ((JavaRuleOutputJarsProvider) info.getValue("outputs"));

    assertThat(artifactFilesNames(transitiveRuntimeJars.toList(Artifact.class)))
        .containsExactly("libdep.jar");
    assertThat(artifactFilesNames(transitiveCompileTimeJars.toList(Artifact.class)))
        .containsExactly("libdep-hjar.jar");
    assertThat(transitiveCompileTimeJars.toList()).containsExactlyElementsIn(compileJars.toList());
    assertThat(artifactFilesNames(fullCompileJars.toList(Artifact.class)))
        .containsExactly("libdep.jar");
    assertThat(artifactFilesNames(sourceJars)).containsExactly("libdep-src.jar");

    assertThat(outputs.getJavaOutputs()).hasSize(1);
    JavaOutput javaOutput = outputs.getJavaOutputs().get(0);
    assertThat(javaOutput.getClassJar().getFilename()).isEqualTo("libdep.jar");
    assertThat(javaOutput.getCompileJar().getFilename()).isEqualTo("libdep-hjar.jar");
    assertThat(artifactFilesNames(javaOutput.getSourceJars())).containsExactly("libdep-src.jar");
    assertThat(javaOutput.getJdeps().getFilename()).isEqualTo("libdep.jdeps");
    assertThat(javaOutput.getCompileJdeps().getFilename()).isEqualTo("libdep-hjar.jdeps");
  }

  @Test
  public void javaPlugin_exposesJavaOutputs() throws Exception {
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(",
        "  name = 'lib',",
        "  srcs = [ 'Lib.java'],",
        ")",
        "java_plugin(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        "  deps = [':lib'],",
        ")",
        "my_rule(",
        "  name = 'my',",
        "  dep = ':dep',",
        ")");
    scratch.file(
        "java/test/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   depj = ctx.attr.dep[JavaPluginInfo]",
        "   return [result(",
        "             outputs = depj.java_outputs,",
        "          )]",
        "my_rule = rule(impl, attrs = { 'dep' : attr.label() })");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:my");
    StructImpl info =
        (StructImpl)
            configuredTarget.get(
                new StarlarkProvider.Key(
                    Label.parseCanonical("//java/test:extension.bzl"), "result"));

    @SuppressWarnings("unchecked") // deserialization
    StarlarkList<JavaOutput> javaOutputs = ((StarlarkList<JavaOutput>) info.getValue("outputs"));

    assertThat(javaOutputs.size()).isEqualTo(1);
    JavaOutput javaOutput = javaOutputs.get(0);
    assertThat(javaOutput.getClassJar().getFilename()).isEqualTo("libdep.jar");
    assertThat(javaOutput.getCompileJar().getFilename()).isEqualTo("libdep-hjar.jar");
    assertThat(artifactFilesNames(javaOutput.getSourceJars())).containsExactly("libdep-src.jar");
    assertThat(javaOutput.getJdeps().getFilename()).isEqualTo("libdep.jdeps");
    assertThat(javaOutput.getCompileJdeps().getFilename()).isEqualTo("libdep-hjar.jdeps");
  }

  @Test
  public void javaToolchainInfo_jacocoRunnerAttribute() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file("java/test/B.jar");
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(name = 'dep')");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  jacoco = ctx.attr._java_toolchain[java_common.JavaToolchainInfo].jacocorunner",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([jacoco.executable]),",
        "      ),",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:dep");

    assertThat(ActionsTestUtil.baseArtifactNames(getFilesToBuild(configuredTarget)))
        .containsExactly("jacocorunner.jar");
  }

  @Test
  public void testJavaCommonCompileExposesOutputJarProvider() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file("java/test/B.jar");
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "name = 'dep',",
        "srcs = ['Main.java'],",
        "sourcepath = [':B.jar']",
        ")",
        "my_rule(",
        "  name = 'my',",
        "  dep = ':dep',",
        ")");
    scratch.file(
        "java/test/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   depj = ctx.attr.dep[java_common.provider]",
        "   return [result(",
        "             transitive_runtime_jars = depj.transitive_runtime_jars,",
        "             transitive_compile_time_jars = depj.transitive_compile_time_jars,",
        "             compile_jars = depj.compile_jars,",
        "             full_compile_jars = depj.full_compile_jars,",
        "             source_jars = depj.source_jars,",
        "             outputs = depj.outputs,",
        "          )]",
        "my_rule = rule(impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    deps = [],",
        "    sourcepath = ctx.files.sourcepath,",
        "    strict_deps = 'ERROR',",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_jar]),",
        "      ),",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'sourcepath': attr.label_list(allow_files=['.jar']),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:my");
    StructImpl info =
        (StructImpl)
            configuredTarget.get(
                new StarlarkProvider.Key(
                    Label.parseCanonical("//java/test:extension.bzl"), "result"));

    JavaRuleOutputJarsProvider outputs = ((JavaRuleOutputJarsProvider) info.getValue("outputs"));
    assertThat(outputs.getJavaOutputs()).hasSize(1);

    JavaOutput javaOutput = outputs.getJavaOutputs().get(0);
    assertThat(javaOutput.getClassJar().getFilename()).isEqualTo("libdep.jar");
    assertThat(javaOutput.getCompileJar().getFilename()).isEqualTo("libdep-hjar.jar");
    assertThat(prettyArtifactNames(javaOutput.getSourceJars()))
        .containsExactly("java/test/libdep-src.jar");
    assertThat(javaOutput.getJdeps().getFilename()).isEqualTo("libdep.jdeps");
    assertThat(javaOutput.getNativeHeadersJar().getFilename())
        .isEqualTo("libdep-native-header.jar");
    assertThat(javaOutput.getCompileJdeps().getFilename()).isEqualTo("libdep-hjar.jdeps");
  }

  @Test
  public void javaCommonCompile_setsRuntimeDeps() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['Main.java'],",
        "  deps = [':dep'],",
        "  runtime_deps = [':runtime'],",
        ")",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")",
        "java_library(",
        "  name = 'runtime',",
        "  srcs = [ 'Runtime.java'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  deps = [dep[JavaInfo] for dep in ctx.attr.deps]",
        "  runtime_deps = [dep[JavaInfo] for dep in ctx.attr.runtime_deps]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    deps = deps,",
        "    runtime_deps = runtime_deps,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_jar])",
        "      ),",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar',",
        "    'my_src_output': 'lib%{name}-src.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'deps': attr.label_list(),",
        "    'runtime_deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    JavaCompilationArgsProvider compilationArgs =
        info.getProviders().getProvider(JavaCompilationArgsProvider.class);
    JavaCompilationInfoProvider compilationInfo = info.getCompilationInfoProvider();
    JavaSourceJarsProvider sourceJarsProvider = info.getProvider(JavaSourceJarsProvider.class);

    assertThat(prettyArtifactNames(compilationArgs.getRuntimeJars()))
        .containsExactly(
            "java/test/libcustom.jar", "java/test/libdep.jar", "java/test/libruntime.jar")
        .inOrder();
    assertThat(prettyArtifactNames(info.getDirectRuntimeJars()))
        .containsExactly("java/test/libcustom.jar");
    assertThat(
            prettyArtifactNames(compilationInfo.getCompilationClasspath().toList(Artifact.class)))
        .containsExactly("java/test/libdep-hjar.jar");
    assertThat(prettyArtifactNames(compilationInfo.getRuntimeClasspath().toList(Artifact.class)))
        .containsExactly(
            "java/test/libcustom.jar", "java/test/libruntime.jar", "java/test/libdep.jar")
        .inOrder();
    assertThat(prettyArtifactNames(sourceJarsProvider.getTransitiveSourceJars()))
        .containsExactly(
            "java/test/libruntime-src.jar",
            "java/test/libdep-src.jar",
            "java/test/libcustom-src.jar")
        .inOrder();
  }

  /**
   * Tests that JavaInfo.java_annotation_processing returned from java_common.compile looks as
   * expected, and specifically, looks as if java_library was used instead.
   */
  @Test
  public void testJavaCommonCompileExposesAnnotationProcessingInfo() throws Exception {
    // Set up a Starlark rule that uses java_common.compile and supports annotation processing in
    // the same way as java_library, then use a helper method to test that the custom rule produces
    // the same annotation processing information as java_library would.
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  return java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    deps = [d[JavaInfo] for d in ctx.attr.deps],",
        "    exports = [e[JavaInfo] for e in ctx.attr.exports],",
        "    plugins = [p[JavaPluginInfo] for p in ctx.attr.plugins],",
        "    output = output_jar,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'deps': attr.label_list(),",
        "    'exports': attr.label_list(),",
        "    'plugins': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    testAnnotationProcessingInfoIsStarlarkAccessible(
        /*toBeProcessedRuleName=*/ "java_custom_library",
        /*extraLoad=*/ "load(':custom_rule.bzl', 'java_custom_library')");
  }

  /**
   * Test that plugin parameter of java_common.compile does not accept JavaInfo when
   * incompatible_require_javaplugininfo_in_javacommon is flipped.
   */
  @Test
  public void javaCommonCompile_requiresJavaPluginInfo() throws Exception {
    useConfiguration("--incompatible_require_javaplugininfo_in_javacommon");
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  return java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    plugins = [p[JavaInfo] for p in ctx.attr.deps],",
        "    output = output_jar,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_library(name = 'dep',",
        "    srcs = [ 'ProcessorDep.java'])",
        "java_custom_library(",
        "    name = 'to_be_processed',",
        "    deps = [':dep'],",
        "    srcs = ['ToBeProcessed.java'],",
        ")");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//java/test:to_be_processed");

    assertContainsEvent("at index 0 of plugins, got element of type JavaInfo, want JavaPluginInfo");
  }

  @Test
  public void testJavaCommonCompileCompilationInfo() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['Main.java'],",
        "  deps = [':dep']",
        ")",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  deps = [dep[java_common.provider] for dep in ctx.attr.deps]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "    javac_opts = ['-XDone -XDtwo'],",
        "  )",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_jar] + compilation_provider.source_jars)",
        "      ),",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar',",
        "    'my_src_output': 'lib%{name}-src.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    JavaCompilationInfoProvider compilationInfo = info.getCompilationInfoProvider();
    assertThat(
            prettyArtifactNames(compilationInfo.getCompilationClasspath().toList(Artifact.class)))
        .containsExactly("java/test/libdep-hjar.jar");

    assertThat(prettyArtifactNames(compilationInfo.getRuntimeClasspath().toList(Artifact.class)))
        .containsExactly("java/test/libdep.jar", "java/test/libcustom.jar");

    assertThat(compilationInfo.getJavacOpts()).contains("-XDone");
  }

  @Test
  public void testJavaCommonCompileTransitiveSourceJars() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['Main.java'],",
        "  deps = [':dep']",
        ")",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  deps = [dep[java_common.provider] for dep in ctx.attr.deps]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_jar] + compilation_provider.source_jars),",
        "      ),",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar',",
        "    'my_src_output': 'lib%{name}-src.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    Sequence<Artifact> sourceJars = info.getSourceJars();
    NestedSet<Artifact> transitiveSourceJars =
        info.getTransitiveSourceJars().getSet(Artifact.class);
    assertThat(artifactFilesNames(sourceJars)).containsExactly("libcustom-src.jar");
    assertThat(artifactFilesNames(transitiveSourceJars))
        .containsExactly("libdep-src.jar", "libcustom-src.jar");

    assertThat(getGeneratingAction(configuredTarget, "java/test/libcustom-src.jar")).isNotNull();
  }

  @Test
  public void testJavaCommonCompileSourceJarName() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['Main.java'],",
        "  deps = [':dep']",
        ")",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('amazing.jar')",
        "  other_output_jar = ctx.actions.declare_file('wonderful.jar')",
        "  deps = [dep[java_common.provider] for dep in ctx.attr.deps]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  other_compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = other_output_jar,",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  result_provider = java_common.merge([compilation_provider, other_compilation_provider])",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_jar])",
        "      ),",
        "      result_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'amazing.jar',",
        "    'my_second_output': 'wonderful.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    Sequence<Artifact> sourceJars = info.getSourceJars();
    NestedSet<Artifact> transitiveSourceJars =
        info.getTransitiveSourceJars().getSet(Artifact.class);
    assertThat(artifactFilesNames(sourceJars))
        .containsExactly("amazing-src.jar", "wonderful-src.jar");
    assertThat(artifactFilesNames(transitiveSourceJars))
        .containsExactly("libdep-src.jar", "amazing-src.jar", "wonderful-src.jar");
  }

  @Test
  public void testJavaCommonCompileWithOnlyOneSourceJar() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['myjar-src.jar'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_jars = ctx.files.srcs,",
        "    output = output_jar,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_jar]),",
        "      ),",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.jar']),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    Sequence<Artifact> sourceJars = info.getSourceJars();
    assertThat(artifactFilesNames(sourceJars)).containsExactly("libcustom-src.jar");
    ImmutableList<JavaOutput> javaOutputs = info.getJavaOutputs();
    assertThat(javaOutputs).hasSize(1);
    JavaOutput javaOutput = javaOutputs.get(0);
    assertThat(javaOutput.getClassJar().getFilename()).isEqualTo("libcustom.jar");
    assertThat(javaOutput.getSrcJar().getFilename()).isEqualTo("libcustom-src.jar");
    assertThat(javaOutput.getCompileJar().getFilename()).isEqualTo("libcustom-hjar.jar");
    assertThat(javaOutput.getJdeps().getFilename()).isEqualTo("libcustom.jdeps");
    assertThat(javaOutput.getCompileJdeps().getFilename()).isEqualTo("libcustom-hjar.jdeps");
  }

  @Test
  public void testJavaCommonCompile_noSources() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_jar]),",
        "      ),",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    Sequence<Artifact> sourceJars = info.getSourceJars();
    assertThat(artifactFilesNames(sourceJars)).containsExactly("libcustom-src.jar");
    ImmutableList<JavaOutput> javaOutputs = info.getJavaOutputs();
    assertThat(javaOutputs).hasSize(1);
    JavaOutput javaOutput = javaOutputs.get(0);
    assertThat(javaOutput.getClassJar().getFilename()).isEqualTo("libcustom.jar");
    assertThat(javaOutput.getSrcJar().getFilename()).isEqualTo("libcustom-src.jar");
  }

  @Test
  public void testJavaCommonCompileCustomSourceJar() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['myjar-src.jar'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  output_source_jar = ctx.actions.declare_file('lib' + ctx.label.name + '-mysrc.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_jars = ctx.files.srcs,",
        "    output = output_jar,",
        "    output_source_jar = output_source_jar,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_source_jar]),",
        "      ),",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.jar']),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    Sequence<Artifact> sourceJars = info.getSourceJars();
    assertThat(artifactFilesNames(sourceJars)).containsExactly("libcustom-mysrc.jar");
    ImmutableList<JavaOutput> javaOutputs = info.getJavaOutputs();
    assertThat(javaOutputs).hasSize(1);
    JavaOutput javaOutput = javaOutputs.get(0);
    assertThat(javaOutput.getClassJar().getFilename()).isEqualTo("libcustom.jar");
    assertThat(javaOutput.getSrcJar().getFilename()).isEqualTo("libcustom-mysrc.jar");
    assertThat(javaOutput.getCompileJar().getFilename()).isEqualTo("libcustom-hjar.jar");
    assertThat(javaOutput.getJdeps().getFilename()).isEqualTo("libcustom.jdeps");
    assertThat(javaOutput.getCompileJdeps().getFilename()).isEqualTo("libcustom-hjar.jdeps");
  }

  @Test
  public void testJavaCommonCompileAdditionalInputsAndOutputs() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['myjar-src.jar'],",
        "  additional_inputs = ['additional_input.bin'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_jars = ctx.files.srcs,",
        "    output = output_jar,",
        "    annotation_processor_additional_inputs = ctx.files.additional_inputs,",
        "    annotation_processor_additional_outputs = [ctx.outputs.additional_output],",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [DefaultInfo(files = depset([output_jar]))]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'additional_output': '%{name}_additional_output',",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.jar']),",
        "    'additional_inputs': attr.label_list(allow_files=['.bin']),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");
    Action javaAction = getGeneratingAction(configuredTarget, "java/test/libcustom.jar");
    assertThat(artifactFilesNames(javaAction.getInputs())).contains("additional_input.bin");
    assertThat(artifactFilesNames(javaAction.getOutputs())).contains("custom_additional_output");
  }

  private static Collection<String> artifactFilesNames(NestedSet<Artifact> artifacts) {
    return artifactFilesNames(artifacts.toList());
  }

  private static Collection<String> artifactFilesNames(Iterable<Artifact> artifacts) {
    List<String> result = new ArrayList<>();
    for (Artifact artifact : artifacts) {
      result.add(artifact.getFilename());
    }
    return result;
  }

  /**
   * Tests that a java_library exposes java_processing_info as expected when annotation processing
   * is used.
   */
  @Test
  public void testJavaPlugin() throws Exception {
    testAnnotationProcessingInfoIsStarlarkAccessible(
        /*toBeProcessedRuleName=*/ "java_library", /*extraLoad=*/ "");
  }

  /**
   * Tests that JavaInfo's java_annotation_processing looks as expected with a target that's assumed
   * to use annotation processing itself and has a dep and an export that likewise use annotation
   * processing.
   */
  private void testAnnotationProcessingInfoIsStarlarkAccessible(
      String toBeProcessedRuleName, String extraLoad) throws Exception {
    scratch.file(
        "java/test/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   depj = ctx.attr.dep[JavaInfo]",
        "   return [result(",
        "             enabled = depj.annotation_processing.enabled,",
        "             class_jar = depj.outputs.jars[0].generated_class_jar,",
        "             source_jar = depj.outputs.jars[0].generated_source_jar,",
        "             old_class_jar = depj.annotation_processing.class_jar,",
        "             old_source_jar = depj.annotation_processing.source_jar,",
        "             processor_classpath = depj.annotation_processing.processor_classpath,",
        "             processor_classnames = depj.annotation_processing.processor_classnames,",
        "             transitive_class_jars = depj.annotation_processing.transitive_class_jars,",
        "             transitive_source_jars = depj.annotation_processing.transitive_source_jars,",
        "          )]",
        "my_rule = rule(impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'plugin_dep',",
        "    srcs = [ 'ProcessorDep.java'])",
        "java_plugin(name = 'plugin',",
        "    srcs = ['AnnotationProcessor.java'],",
        "    processor_class = 'com.google.process.stuff',",
        "    deps = [ ':plugin_dep' ])",
        extraLoad,
        toBeProcessedRuleName + "(",
        "    name = 'to_be_processed',",
        "    plugins = [':plugin'],",
        "    srcs = ['ToBeProcessed.java'],",
        "    deps = [':dep'],",
        "    exports = [':export'],",
        ")",
        "java_library(",
        "  name = 'dep',",
        "  srcs = ['Dep.java'],",
        "  plugins = [':plugin']",
        ")",
        "java_library(",
        "  name = 'export',",
        "  srcs = ['Export.java'],",
        "  plugins = [':plugin']",
        ")",
        "my_rule(name = 'my', dep = ':to_be_processed')");

    // Assert that java_annotation_processing for :to_be_processed looks as expected:
    // the target itself uses :plugin as the processor, and transitive information includes
    // the target's own as well as :dep's and :export's annotation processing outputs, since those
    // two targets also use annotation processing.
    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:my");
    StructImpl info =
        (StructImpl)
            configuredTarget.get(
                new StarlarkProvider.Key(
                    Label.parseCanonical("//java/test:extension.bzl"), "result"));

    assertThat(info.getValue("enabled")).isEqualTo(Boolean.TRUE);
    assertThat(info.getValue("class_jar")).isNotNull();
    assertThat(info.getValue("source_jar")).isNotNull();
    assertThat(info.getValue("class_jar")).isEqualTo(info.getValue("old_class_jar"));
    assertThat(info.getValue("source_jar")).isEqualTo(info.getValue("old_source_jar"));
    assertThat((List<?>) info.getValue("processor_classnames"))
        .containsExactly("com.google.process.stuff");
    assertThat(
            Iterables.transform(
                ((Depset) info.getValue("processor_classpath")).toList(Artifact.class),
                Artifact::getFilename))
        .containsExactly("libplugin.jar", "libplugin_dep.jar");
    assertThat(((Depset) info.getValue("transitive_class_jars")).toList())
        .hasSize(3); // from to_be_processed, dep, and export
    assertThat(((Depset) info.getValue("transitive_class_jars")).toList())
        .contains(info.getValue("class_jar"));
    assertThat(((Depset) info.getValue("transitive_source_jars")).toList())
        .hasSize(3); // from to_be_processed, dep, and export
    assertThat(((Depset) info.getValue("transitive_source_jars")).toList())
        .contains(info.getValue("source_jar"));
  }

  /** Retrieves Java plugin data from a target via Starlark. */
  private JavaPluginData retrieveStarlarkPluginData(
      String target, String provider, boolean apiGenerating) throws Exception {
    String pkg = apiGenerating ? "java/getplugininfo" : "java/getapiplugininfo";
    scratch.file(
        pkg + "/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   depj = ctx.attr.dep[" + provider + "]",
        "   plugin_data = " + (apiGenerating ? "depj.api_generating_plugins" : "depj.plugins"),
        "   return [result(",
        "             processor_classpath = plugin_data.processor_jars,",
        "             processor_classnames = plugin_data.processor_classes,",
        "             processor_data = plugin_data.processor_data,",
        "          )]",
        "get_plugininfo = rule(impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        pkg + "/BUILD",
        "load(':extension.bzl', 'get_plugininfo')",
        "get_plugininfo(name = 'plugininfo', dep = '" + target + "')");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//" + pkg + ":plugininfo");
    StructImpl info =
        (StructImpl)
            configuredTarget.get(
                new StarlarkProvider.Key(
                    Label.parseCanonical("//" + pkg + ":extension.bzl"), "result"));

    return JavaPluginData.create(
        ((Depset) info.getValue("processor_classnames")).getSet(String.class),
        ((Depset) info.getValue("processor_classpath")).getSet(Artifact.class),
        ((Depset) info.getValue("processor_data")).getSet(Artifact.class));
  }

  /** Tests that java_plugin exposes plugin information to Starlark. */
  @Test
  public void javaPlugin_exposesPluginsToStarlark() throws Exception {
    scratch.file(
        "java/test/BUILD",
        "java_library(",
        "    name = 'plugin_dep',",
        "    srcs = ['ProcessorDep.java'],",
        "    data = ['depfile.dat'],",
        ")",
        "java_plugin(",
        "    name = 'plugin',",
        "    srcs = ['AnnotationProcessor.java'],",
        "    processor_class = 'com.google.process.stuff',",
        "    deps = [':plugin_dep'],",
        "    data = ['pluginfile.dat'],",
        ")");

    JavaPluginData pluginData =
        retrieveStarlarkPluginData(
            "//java/test:plugin", /* provider = */ "JavaPluginInfo", /* apiGenerating = */ false);
    JavaPluginData apiPluginData =
        retrieveStarlarkPluginData(
            "//java/test:plugin", /* provider = */ "JavaPluginInfo", /* apiGenerating = */ true);

    assertThat(pluginData.processorClasses().toList()).containsExactly("com.google.process.stuff");
    assertThat(pluginData.processorClasspath().toList().stream().map(Artifact::getFilename))
        .containsExactly("libplugin.jar", "libplugin_dep.jar");
    assertThat(pluginData.data().toList().stream().map(Artifact::getFilename))
        .containsExactly("pluginfile.dat");
    assertThat(apiPluginData).isEqualTo(JavaPluginData.empty());
  }

  /** Tests that api generating java_plugin exposes plugin information to Starlark. */
  @Test
  public void apiGeneratingjavaPlugin_exposesPluginsToStarlark() throws Exception {
    scratch.file(
        "java/test/BUILD",
        "java_library(",
        "    name = 'plugin_dep',",
        "    srcs = ['ProcessorDep.java'],",
        "    data = ['depfile.dat'],",
        ")",
        "java_plugin(",
        "    name = 'plugin',",
        "    generates_api = True,",
        "    srcs = ['AnnotationProcessor.java'],",
        "    processor_class = 'com.google.process.stuff',",
        "    deps = [':plugin_dep'],",
        "    data = ['pluginfile.dat'],",
        ")");

    JavaPluginData pluginData =
        retrieveStarlarkPluginData(
            "//java/test:plugin", /* provider = */ "JavaPluginInfo", /* apiGenerating = */ false);
    JavaPluginData apiPluginData =
        retrieveStarlarkPluginData(
            "//java/test:plugin", /* provider = */ "JavaPluginInfo", /* apiGenerating = */ true);

    assertThat(apiPluginData.processorClasses().toList())
        .containsExactly("com.google.process.stuff");
    assertThat(apiPluginData.processorClasspath().toList().stream().map(Artifact::getFilename))
        .containsExactly("libplugin.jar", "libplugin_dep.jar");
    assertThat(apiPluginData.data().toList().stream().map(Artifact::getFilename))
        .containsExactly("pluginfile.dat");
    assertThat(apiPluginData).isEqualTo(pluginData);
  }

  /** Tests that java_library exposes exported plugin information to Starlark. */
  @Test
  public void javaLibrary_exposesPluginsToStarlark() throws Exception {
    scratch.file(
        "java/test/BUILD",
        "java_library(name = 'plugin_dep1', srcs = ['A.java'], data = ['depfile1.dat'])",
        "java_library(name = 'plugin_dep2', srcs = ['B.java'], data = ['depfile2.dat'])",
        "java_plugin(",
        "    name = 'plugin',",
        "    srcs = ['AnnotationProcessor1.java'],",
        "    processor_class = 'com.google.process.stuff',",
        "    deps = [':plugin_dep1'],",
        "    data = ['pluginfile1.dat'],",
        ")",
        "java_plugin(",
        "    name = 'apiplugin',",
        "    generates_api = True,",
        "    srcs = ['AnnotationProcessor2.java'],",
        "    processor_class = 'com.google.process.apistuff',",
        "    deps = [':plugin_dep2'],",
        "    data = ['pluginfile2.dat'],",
        ")",
        "java_library(",
        "    name = 'library',",
        "    exported_plugins = [':plugin', ':apiplugin']",
        ")");

    JavaPluginData pluginData =
        retrieveStarlarkPluginData(
            "//java/test:library", /* provider = */ "JavaInfo", /* apiGenerating = */ false);
    JavaPluginData apiPluginData =
        retrieveStarlarkPluginData(
            "//java/test:library", /* provider = */ "JavaInfo", /* apiGenerating = */ true);

    assertThat(pluginData.processorClasses().toList())
        .containsExactly("com.google.process.stuff", "com.google.process.apistuff");
    assertThat(pluginData.processorClasspath().toList().stream().map(Artifact::getFilename))
        .containsExactly(
            "libplugin.jar", "libplugin_dep1.jar", "libapiplugin.jar", "libplugin_dep2.jar");
    assertThat(pluginData.data().toList().stream().map(Artifact::getFilename))
        .containsExactly("pluginfile1.dat", "pluginfile2.dat");
    assertThat(apiPluginData.processorClasses().toList())
        .containsExactly("com.google.process.apistuff");
    assertThat(apiPluginData.processorClasspath().toList().stream().map(Artifact::getFilename))
        .containsExactly("libapiplugin.jar", "libplugin_dep2.jar");
    assertThat(apiPluginData.data().toList().stream().map(Artifact::getFilename))
        .containsExactly("pluginfile2.dat");
  }

  /** Tests the JavaPluginInfo provider's constructor. */
  @Test
  public void javaPluginInfo_create() throws Exception {
    scratch.file(
        "java/test/myplugin.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib.jar')",
        "  ctx.actions.write(output_jar, '')",
        "  dep = JavaInfo(output_jar = output_jar, compile_jar = None,",
        "    deps = [d[JavaInfo] for d in ctx.attr.deps])",
        "  return [JavaPluginInfo(",
        "    runtime_deps = [dep],",
        "    processor_class = ctx.attr.processor_class,",
        "    data = ctx.files.data,",
        "  )]",
        "myplugin = rule(implementation = _impl,",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'processor_class': attr.string(),",
        "    'data': attr.label_list(allow_files = True),",
        "  })");
    scratch.file(
        "java/test/BUILD",
        "load(':myplugin.bzl', 'myplugin')",
        "java_library(name = 'plugin_dep1', srcs = ['A.java'], data = ['depfile1.dat'])",
        "myplugin(",
        "    name = 'plugin',",
        "    processor_class = 'com.google.process.stuff',",
        "    deps = [':plugin_dep1'],",
        "    data = ['pluginfile1.dat'],",
        ")");

    JavaPluginInfo pluginInfo =
        getConfiguredTarget("//java/test:plugin").get(JavaPluginInfo.PROVIDER);
    JavaPluginData pluginData = pluginInfo.plugins();
    JavaPluginData apiPluginData = pluginInfo.apiGeneratingPlugins();

    assertThat(pluginData.processorClasses().toList()).containsExactly("com.google.process.stuff");
    assertThat(pluginData.processorClasspath().toList().stream().map(Artifact::getFilename))
        .containsExactly("lib.jar", "libplugin_dep1.jar");
    assertThat(pluginData.data().toList().stream().map(Artifact::getFilename))
        .containsExactly("pluginfile1.dat");
    assertThat(apiPluginData.processorClasses().toList()).isEmpty();
    assertThat(apiPluginData.processorClasspath().toList()).isEmpty();
    assertThat(apiPluginData.data().toList()).isEmpty();
  }

  /** Tests the JavaPluginInfo provider's constructor for api generating plugin. */
  @Test
  public void javaPluginInfo_createApiPlugin() throws Exception {
    scratch.file(
        "java/test/myplugin.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib.jar')",
        "  ctx.actions.write(output_jar, '')",
        "  dep = JavaInfo(output_jar = output_jar, compile_jar = None,",
        "    deps = [d[JavaInfo] for d in ctx.attr.deps])",
        "  return [JavaPluginInfo(",
        "    runtime_deps = [dep],",
        "    processor_class = ctx.attr.processor_class,",
        "    data = ctx.files.data,",
        "    generates_api = True,",
        "  )]",
        "myplugin = rule(implementation = _impl,",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'processor_class': attr.string(),",
        "    'data': attr.label_list(allow_files = True),",
        "  })");
    scratch.file(
        "java/test/BUILD",
        "load(':myplugin.bzl', 'myplugin')",
        "java_library(name = 'plugin_dep1', srcs = ['A.java'], data = ['depfile1.dat'])",
        "myplugin(",
        "    name = 'plugin',",
        "    processor_class = 'com.google.process.stuff',",
        "    deps = [':plugin_dep1'],",
        "    data = ['pluginfile1.dat'],",
        ")");

    JavaPluginInfo pluginInfo =
        getConfiguredTarget("//java/test:plugin").get(JavaPluginInfo.PROVIDER);
    JavaPluginData pluginData = pluginInfo.plugins();
    JavaPluginData apiPluginData = pluginInfo.apiGeneratingPlugins();

    assertThat(apiPluginData.processorClasses().toList())
        .containsExactly("com.google.process.stuff");
    assertThat(apiPluginData.processorClasspath().toList().stream().map(Artifact::getFilename))
        .containsExactly("lib.jar", "libplugin_dep1.jar");
    assertThat(apiPluginData.data().toList().stream().map(Artifact::getFilename))
        .containsExactly("pluginfile1.dat");
    assertThat(apiPluginData).isEqualTo(pluginData);
  }

  /** Tests the JavaPluginInfo provider's constructor without processor class. */
  @Test
  public void javaPluginInfo_createWithoutProcessorClass() throws Exception {
    scratch.file(
        "java/test/myplugin.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib.jar')",
        "  ctx.actions.write(output_jar, '')",
        "  dep = JavaInfo(output_jar = output_jar, compile_jar = None,",
        "    deps = [d[JavaInfo] for d in ctx.attr.deps])",
        "  return [JavaPluginInfo(",
        "    runtime_deps = [dep],",
        "    processor_class = None,",
        "    data = ctx.files.data,",
        "  )]",
        "myplugin = rule(implementation = _impl,",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'data': attr.label_list(allow_files = True),",
        "  })");
    scratch.file(
        "java/test/BUILD",
        "load(':myplugin.bzl', 'myplugin')",
        "java_library(name = 'plugin_dep1', srcs = ['A.java'], data = ['depfile1.dat'])",
        "myplugin(",
        "    name = 'plugin',",
        "    deps = [':plugin_dep1'],",
        "    data = ['pluginfile1.dat'],",
        ")");

    JavaPluginInfo pluginInfo =
        getConfiguredTarget("//java/test:plugin").get(JavaPluginInfo.PROVIDER);
    JavaPluginData pluginData = pluginInfo.plugins();
    JavaPluginData apiPluginData = pluginInfo.apiGeneratingPlugins();

    assertThat(pluginData.processorClasses().toList()).isEmpty();
    assertThat(pluginData.processorClasspath().toList().stream().map(Artifact::getFilename))
        .containsExactly("lib.jar", "libplugin_dep1.jar");
    assertThat(pluginData.data().toList().stream().map(Artifact::getFilename))
        .containsExactly("pluginfile1.dat");
    assertThat(apiPluginData.processorClasses().toList()).isEmpty();
    assertThat(apiPluginData.processorClasspath().toList()).isEmpty();
    assertThat(apiPluginData.data().toList()).isEmpty();
  }

  /** Tests the JavaPluginInfo provider's constructor with data given as depset. */
  @Test
  public void javaPluginInfo_createWithDataDepset() throws Exception {
    scratch.file(
        "java/test/myplugin.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib.jar')",
        "  ctx.actions.write(output_jar, '')",
        "  dep = JavaInfo(output_jar = output_jar, compile_jar = None,",
        "    deps = [d[JavaInfo] for d in ctx.attr.deps])",
        "  return [JavaPluginInfo(",
        "    runtime_deps = [dep],",
        "    processor_class = ctx.attr.processor_class,",
        "    data = depset(ctx.files.data),",
        "  )]",
        "myplugin = rule(implementation = _impl,",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'processor_class': attr.string(),",
        "    'data': attr.label_list(allow_files = True),",
        "  })");
    scratch.file(
        "java/test/BUILD",
        "load(':myplugin.bzl', 'myplugin')",
        "java_library(name = 'plugin_dep1', srcs = ['A.java'], data = ['depfile1.dat'])",
        "myplugin(",
        "    name = 'plugin',",
        "    processor_class = 'com.google.process.stuff',",
        "    deps = [':plugin_dep1'],",
        "    data = ['pluginfile1.dat'],",
        ")");

    JavaPluginInfo pluginInfo =
        getConfiguredTarget("//java/test:plugin").get(JavaPluginInfo.PROVIDER);
    JavaPluginData pluginData = pluginInfo.plugins();
    JavaPluginData apiPluginData = pluginInfo.apiGeneratingPlugins();

    assertThat(pluginData.processorClasses().toList()).containsExactly("com.google.process.stuff");
    assertThat(pluginData.processorClasspath().toList().stream().map(Artifact::getFilename))
        .containsExactly("lib.jar", "libplugin_dep1.jar");
    assertThat(pluginData.data().toList().stream().map(Artifact::getFilename))
        .containsExactly("pluginfile1.dat");
    assertThat(apiPluginData.processorClasses().toList()).isEmpty();
    assertThat(apiPluginData.processorClasspath().toList()).isEmpty();
    assertThat(apiPluginData.data().toList()).isEmpty();
  }

  @Test
  public void testJavaProviderFieldsAreStarlarkAccessible() throws Exception {
    // The Starlark evaluation itself will test that compile_jars and
    // transitive_runtime_jars are returning a list readable by Starlark with
    // the expected number of entries.
    scratch.file(
        "java/test/extension.bzl",
        "result = provider()",
        "def impl(ctx):",
        "   java_provider = ctx.attr.dep[JavaInfo]",
        "   return [result(",
        "             compile_jars = java_provider.compile_jars,",
        "             transitive_runtime_jars = java_provider.transitive_runtime_jars,",
        "             transitive_compile_time_jars = java_provider.transitive_compile_time_jars,",
        "          )]",
        "my_rule = rule(impl, attrs = { ",
        "  'dep' : attr.label(), ",
        "  'cnt_cjar' : attr.int(), ",
        "  'cnt_rjar' : attr.int(), ",
        "})");
    scratch.file(
        "java/test/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'parent',",
        "    srcs = [ 'Parent.java'])",
        "java_library(name = 'jl',",
        "    srcs = ['Jl.java'],",
        "    deps = [ ':parent' ])",
        "my_rule(name = 'my', dep = ':jl', cnt_cjar = 1, cnt_rjar = 2)");
    // Now, get that information and ensure it is equal to what the jl java_library
    // was presenting
    ConfiguredTarget myConfiguredTarget = getConfiguredTarget("//java/test:my");
    ConfiguredTarget javaLibraryTarget = getConfiguredTarget("//java/test:jl");

    // Extract out the information from Starlark rule
    StructImpl info =
        (StructImpl)
            myConfiguredTarget.get(
                new StarlarkProvider.Key(
                    Label.parseCanonical("//java/test:extension.bzl"), "result"));

    Depset rawMyCompileJars = (Depset) info.getValue("compile_jars");
    Depset rawMyTransitiveRuntimeJars = (Depset) info.getValue("transitive_runtime_jars");
    Depset rawMyTransitiveCompileTimeJars = (Depset) info.getValue("transitive_compile_time_jars");

    NestedSet<Artifact> myCompileJars = rawMyCompileJars.getSet(Artifact.class);
    NestedSet<Artifact> myTransitiveRuntimeJars = rawMyTransitiveRuntimeJars.getSet(Artifact.class);
    NestedSet<Artifact> myTransitiveCompileTimeJars =
        rawMyTransitiveCompileTimeJars.getSet(Artifact.class);

    // Extract out information from native rule
    JavaCompilationArgsProvider jlJavaCompilationArgsProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, javaLibraryTarget);
    NestedSet<Artifact> jlCompileJars = jlJavaCompilationArgsProvider.getDirectCompileTimeJars();
    NestedSet<Artifact> jlTransitiveRuntimeJars = jlJavaCompilationArgsProvider.getRuntimeJars();
    NestedSet<Artifact> jlTransitiveCompileTimeJars =
        jlJavaCompilationArgsProvider.getTransitiveCompileTimeJars();

    // Using reference equality since should be precisely identical
    assertThat(myCompileJars == jlCompileJars).isTrue();
    assertThat(myTransitiveRuntimeJars == jlTransitiveRuntimeJars).isTrue();
    assertThat(myTransitiveCompileTimeJars).isEqualTo(jlTransitiveCompileTimeJars);
  }

  @Test
  public void javaProviderExposedOnJavaLibrary() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "my_provider = provider()",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return [my_provider(p = dep_params)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl', srcs = ['java/A.java'])",
        "my_rule(name = 'r', dep = ':jl')");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:r");
    ConfiguredTarget javaLibraryTarget = getConfiguredTarget("//foo:jl");
    StarlarkProvider.Key myProviderKey =
        new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "my_provider");
    StructImpl declaredProvider = (StructImpl) myRuleTarget.get(myProviderKey);
    Object javaProvider = declaredProvider.getValue("p");
    assertThat(javaProvider).isInstanceOf(JavaInfo.class);
    assertThat(javaLibraryTarget.get(JavaInfo.PROVIDER)).isEqualTo(javaProvider);
  }

  @Test
  public void javaProviderPropagation() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return [dep_params]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl', srcs = ['java/A.java'])",
        "my_rule(name = 'r', dep = ':jl')",
        "java_library(name = 'jl_top', srcs = ['java/C.java'], deps = [':r'])");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:r");
    ConfiguredTarget javaLibraryTarget = getConfiguredTarget("//foo:jl");
    ConfiguredTarget topJavaLibraryTarget = getConfiguredTarget("//foo:jl_top");

    Object javaProvider = myRuleTarget.get(JavaInfo.PROVIDER.getKey());
    assertThat(javaProvider).isInstanceOf(JavaInfo.class);

    JavaInfo jlJavaInfo = javaLibraryTarget.get(JavaInfo.PROVIDER);

    assertThat(jlJavaInfo == javaProvider).isTrue();

    JavaInfo jlTopJavaInfo = topJavaLibraryTarget.get(JavaInfo.PROVIDER);

    javaCompilationArgsHaveTheSameParent(
        jlJavaInfo.getProvider(JavaCompilationArgsProvider.class),
        jlTopJavaInfo.getProvider(JavaCompilationArgsProvider.class));
  }

  @Test
  public void starlarkJavaToJavaLibraryAttributes() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return [dep_params]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl_bottom_for_deps', srcs = ['java/A.java'])",
        "java_library(name = 'jl_bottom_for_exports', srcs = ['java/A2.java'])",
        "java_library(name = 'jl_bottom_for_runtime_deps', srcs = ['java/A2.java'])",
        "my_rule(name = 'mya', dep = ':jl_bottom_for_deps')",
        "my_rule(name = 'myb', dep = ':jl_bottom_for_exports')",
        "my_rule(name = 'myc', dep = ':jl_bottom_for_runtime_deps')",
        "java_library(name = 'lib_exports', srcs = ['java/B.java'], deps = [':mya'],",
        "  exports = [':myb'], runtime_deps = [':myc'])",
        "java_library(name = 'lib_interm', srcs = ['java/C.java'], deps = [':lib_exports'])",
        "java_library(name = 'lib_top', srcs = ['java/D.java'], deps = [':lib_interm'])");
    assertNoEvents();

    // Test that all bottom jars are on the runtime classpath of lib_exports.
    ConfiguredTarget jlExports = getConfiguredTarget("//foo:lib_exports");
    JavaCompilationArgsProvider jlExportsProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, jlExports);
    assertThat(prettyArtifactNames(jlExportsProvider.getRuntimeJars()))
        .containsAtLeast(
            "foo/libjl_bottom_for_deps.jar",
            "foo/libjl_bottom_for_runtime_deps.jar",
            "foo/libjl_bottom_for_exports.jar");

    // Test that libjl_bottom_for_exports.jar is in the recursive java compilation args of lib_top.
    ConfiguredTarget jlTop = getConfiguredTarget("//foo:lib_interm");
    JavaCompilationArgsProvider jlTopProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, jlTop);
    assertThat(prettyArtifactNames(jlTopProvider.getRuntimeJars()))
        .contains("foo/libjl_bottom_for_exports.jar");
  }

  @Test
  public void starlarkJavaToJavaBinaryAttributes() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return [dep_params]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl_bottom_for_deps', srcs = ['java/A.java'])",
        "java_library(name = 'jl_bottom_for_runtime_deps', srcs = ['java/A2.java'])",
        "my_rule(name = 'mya', dep = ':jl_bottom_for_deps')",
        "my_rule(name = 'myb', dep = ':jl_bottom_for_runtime_deps')",
        "java_binary(name = 'binary', srcs = ['java/B.java'], main_class = 'foo.A',",
        "  deps = [':mya'], runtime_deps = [':myb'])");
    assertNoEvents();

    setBuildLanguageOptions("--experimental_google_legacy_api");
    // Test that all bottom jars are on the runtime classpath.
    ConfiguredTarget binary = getConfiguredTarget("//foo:binary");
    assertThat(
            prettyArtifactNames(
                binary
                    .get(JavaInfo.PROVIDER)
                    .getCompilationInfoProvider()
                    .getRuntimeClasspath()
                    .getSet(Artifact.class)))
        .containsAtLeast("foo/libjl_bottom_for_deps.jar", "foo/libjl_bottom_for_runtime_deps.jar");
  }

  @Test
  public void starlarkJavaToJavaImportAttributes() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  return [dep_params]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'jl_bottom_for_deps', srcs = ['java/A.java'])",
        "java_library(name = 'jl_bottom_for_runtime_deps', srcs = ['java/A2.java'])",
        "my_rule(name = 'mya', dep = ':jl_bottom_for_deps')",
        "my_rule(name = 'myb', dep = ':jl_bottom_for_runtime_deps')",
        "java_import(name = 'import', jars = ['B.jar'], deps = [':mya'], runtime_deps = [':myb'])");
    assertNoEvents();

    // Test that all bottom jars are on the runtime classpath.
    ConfiguredTarget importTarget = getConfiguredTarget("//foo:import");
    JavaCompilationArgsProvider compilationProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, importTarget);
    assertThat(prettyArtifactNames(compilationProvider.getRuntimeJars()))
        .containsAtLeast("foo/libjl_bottom_for_deps.jar", "foo/libjl_bottom_for_runtime_deps.jar");
  }

  @Test
  public void testJavaInfoSequenceParametersTypeChecked() throws Exception {
    scratch.file(
        "foo/bad_rules.bzl",
        "def make_file(ctx):",
        "  f = ctx.actions.declare_file('out')",
        "  ctx.actions.write(f, 'out')",
        "  return f",
        "def _deps_impl(ctx):",
        "  f = make_file(ctx)",
        "  return JavaInfo(output_jar=f, compile_jar=f, deps=[f])",
        "def _runtime_deps_impl(ctx):",
        "  f = make_file(ctx)",
        "  return JavaInfo(output_jar=f, compile_jar=f, runtime_deps=[f])",
        "def _exports_impl(ctx):",
        "  f = make_file(ctx)",
        "  return JavaInfo(output_jar=f, compile_jar=f, exports=[f])",
        "def _nativelibs_impl(ctx):",
        "  f = make_file(ctx)",
        "  return JavaInfo(output_jar=f, compile_jar=f, native_libraries=[f])",
        "bad_deps = rule(_deps_impl)",
        "bad_runtime_deps = rule(_runtime_deps_impl)",
        "bad_exports = rule(_exports_impl)",
        "bad_libs = rule(_nativelibs_impl)");
    scratch.file(
        "foo/BUILD",
        "load(':bad_rules.bzl', 'bad_deps', 'bad_runtime_deps', 'bad_exports', 'bad_libs')",
        "bad_deps(name='bad_deps')",
        "bad_runtime_deps(name='bad_runtime_deps')",
        "bad_exports(name='bad_exports')",
        "bad_libs(name='bad_libs')");

    checkError(
        "//foo:bad_deps",
        "Error in JavaInfo: at index 0 of deps, got element of type File, want JavaInfo");
    checkError(
        "//foo:bad_runtime_deps",
        "Error in JavaInfo: at index 0 of runtime_deps, got element of type File, want JavaInfo");
    checkError(
        "//foo:bad_exports",
        "Error in JavaInfo: at index 0 of exports, got element of type File, want JavaInfo");
    checkError(
        "//foo:bad_libs",
        "Error in JavaInfo: at index 0 of native_libraries, got element of type File, want CcInfo");
  }

  @Test
  public void javaInfo_compileJarSet() throws Exception {
    scratch.file(
        "foo/javainfo_rules.bzl",
        "def make_file(ctx):",
        "  f = ctx.actions.declare_file('out')",
        "  ctx.actions.write(f, 'out')",
        "  return f",
        "def _bothset_impl(ctx):",
        "  f = make_file(ctx)",
        "  return [JavaInfo(output_jar=f, compile_jar=f)]",
        "bothset = rule(_bothset_impl)");
    scratch.file("foo/BUILD", "load(':javainfo_rules.bzl', 'bothset')", "bothset(name='bothset')");

    getConfiguredTarget("//foo:bothset");
    assertNoEvents();
  }

  @Test
  public void javaInfo_compileJarNotSet() throws Exception {
    scratch.file(
        "foo/javainfo_rules.bzl",
        "def make_file(ctx):",
        "  f = ctx.actions.declare_file('out')",
        "  ctx.actions.write(f, 'out')",
        "  return f",
        "def _only_outputjar_impl(ctx):",
        "  f = make_file(ctx)",
        "  return [JavaInfo(output_jar=f)]",
        "only_outputjar = rule(_only_outputjar_impl)");
    scratch.file(
        "foo/BUILD",
        "load(':javainfo_rules.bzl', 'only_outputjar')",
        "only_outputjar(name='only_outputjar')");

    checkError(
        "//foo:only_outputjar", "JavaInfo() missing 1 required positional argument: compile_jar");
  }

  @Test
  public void javaInfo_compileJarSetToNone() throws Exception {
    scratch.file(
        "foo/javainfo_rules.bzl",
        "def make_file(ctx):",
        "  f = ctx.actions.declare_file('out')",
        "  ctx.actions.write(f, 'out')",
        "  return f",
        "def _compilejar_none_impl(ctx):",
        "  f = make_file(ctx)",
        "  return [JavaInfo(output_jar=f, compile_jar=None)]",
        "compilejar_none = rule(_compilejar_none_impl)");
    scratch.file(
        "foo/BUILD",
        "load(':javainfo_rules.bzl', 'compilejar_none')",
        "compilejar_none(name='compilejar_none')");

    getConfiguredTarget("//foo:compilejar_none");

    assertNoEvents();
  }

  @Test
  public void javaInfoSourceJarsExposed() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(source_jars = ctx.attr.dep[JavaInfo].source_jars)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'] , deps = [':my_java_lib_b'])",
        "my_rule(name = 'my_starlark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_starlark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "result"));
    @SuppressWarnings("unchecked")
    Sequence<Artifact> sourceJars = (Sequence<Artifact>) info.getValue("source_jars");
    assertThat(prettyArtifactNames(sourceJars)).containsExactly("foo/libmy_java_lib_a-src.jar");

    assertThat(prettyArtifactNames(sourceJars)).doesNotContain("foo/libmy_java_lib_b-src.jar");
  }

  @Test
  public void testJavaInfoGetTransitiveSourceJars() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].transitive_source_jars)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'], deps = [':my_java_lib_c'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], deps = [':my_java_lib_b'])",
        "my_rule(name = 'my_starlark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_starlark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "result"));

    Depset sourceJars = (Depset) info.getValue("property");

    assertThat(prettyArtifactNames(sourceJars.getSet(Artifact.class)))
        .containsExactly(
            "foo/libmy_java_lib_a-src.jar",
            "foo/libmy_java_lib_b-src.jar",
            "foo/libmy_java_lib_c-src.jar");
  }

  @Test
  public void testJavaInfoGetTransitiveDeps() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].transitive_deps)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'], deps = [':my_java_lib_c'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], deps = [':my_java_lib_b'])",
        "my_rule(name = 'my_starlark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_starlark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "result"));

    Depset sourceJars = (Depset) info.getValue("property");

    assertThat(prettyArtifactNames(sourceJars.getSet(Artifact.class)))
        .containsExactly(
            "foo/libmy_java_lib_a-hjar.jar",
            "foo/libmy_java_lib_b-hjar.jar",
            "foo/libmy_java_lib_c-hjar.jar");
  }

  @Test
  public void testJavaInfoGetTransitiveRuntimeDeps() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].transitive_runtime_deps)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'], deps = [':my_java_lib_c'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], deps = [':my_java_lib_b'])",
        "my_rule(name = 'my_starlark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_starlark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "result"));

    Depset sourceJars = (Depset) info.getValue("property");

    assertThat(prettyArtifactNames(sourceJars.getSet(Artifact.class)))
        .containsExactly(
            "foo/libmy_java_lib_a.jar", "foo/libmy_java_lib_b.jar", "foo/libmy_java_lib_c.jar");
  }

  /** Tests that JavaInfo provides information about transitive native libraries in Starlark. */
  @Test
  public void javaInfo_getTransitiveNativeLibraries() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].transitive_native_libraries)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "cc_library(name = 'my_cc_lib_c.so', srcs = ['cc/c.cc'])",
        "cc_library(name = 'my_cc_lib_b.so', srcs = ['cc/b.cc'])",
        "cc_library(name = 'my_cc_lib_a.so', srcs = ['cc/a.cc'])",
        "java_library(name = 'my_java_lib_c', srcs = ['java/C.java'], deps = ['my_cc_lib_c.so'])",
        "java_library(name = 'my_java_lib_b', srcs = ['java/B.java'], deps = ['my_cc_lib_b.so'])",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], ",
        "             deps = [':my_java_lib_b', ':my_java_lib_c', 'my_cc_lib_a.so'])",
        "my_rule(name = 'my_starlark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_starlark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "result"));

    Depset nativeLibs = (Depset) info.getValue("property");

    assertThat(
            nativeLibs.getSet(LibraryToLink.class).toList().stream()
                .map(LibraryToLink::getLibraryIdentifier))
        .containsExactly("foo/libmy_cc_lib_a.so", "foo/libmy_cc_lib_b.so", "foo/libmy_cc_lib_c.so");
  }

  /**
   * Tests that java_library propagates direct native library information from JavaInfo provider.
   */
  @Test
  public void javaLibrary_propagatesDirectNativeLibrariesInJavaInfo() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  cc_dep_params = ctx.attr.cc_dep[CcInfo]",
        "  java_info = JavaInfo(output_jar = dep_params.java_outputs[0].class_jar,",
        "    compile_jar = None,",
        "    native_libraries = [cc_dep_params])",
        "  return [java_common.merge([dep_params, java_info])]",
        "my_rule = rule(_impl, attrs = { 'dep': attr.label(), 'cc_dep': attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "cc_library(name = 'native', srcs = ['cc/x.cc'])",
        "java_library(name = 'jl', srcs = ['java/A.java'], deps = [':native'])",
        "cc_library(name = 'ccl', srcs = ['cc/x.cc'])",
        "my_rule(name = 'r', dep = ':jl', cc_dep = ':ccl')",
        "java_library(name = 'jl_top', srcs = ['java/C.java'], deps = [':r'])");

    ConfiguredTarget topJavaLibrary = getConfiguredTarget("//foo:jl_top");

    NestedSet<LibraryToLink> librariesForTopTarget =
        topJavaLibrary.get(JavaInfo.PROVIDER).getTransitiveNativeLibraries();
    assertThat(librariesForTopTarget.toList().stream().map(LibraryToLink::getLibraryIdentifier))
        .containsExactly("foo/libnative", "foo/libccl")
        .inOrder();
  }

  /** Tests that java_binary propagates direct native library information from JavaInfo provider. */
  @Test
  public void javaBinary_propagatesDirectNativeLibrariesInJavaInfo() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  cc_dep_params = ctx.attr.cc_dep[CcInfo]",
        "  java_info = JavaInfo(output_jar = dep_params.java_outputs[0].class_jar,",
        "    compile_jar = None,",
        "    native_libraries = [cc_dep_params])",
        "  return [java_common.merge([dep_params, java_info])]",
        "my_rule = rule(_impl, attrs = { 'dep': attr.label(), 'cc_dep': attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "cc_binary(name = 'native', srcs = ['cc/x.cc'], linkshared=1, linkstatic=1)",
        "java_library(name = 'jl', srcs = ['java/A.java'], data = [':native'])",
        "cc_binary(name = 'ccl', srcs = ['cc/x.cc'], linkshared=1, linkstatic=1)",
        "my_rule(name = 'r', dep = ':jl', cc_dep = ':ccl')",
        "java_binary(name = 'binary', main_class = 'C', srcs = ['java/C.java'], deps = [':r'])");

    setBuildLanguageOptions("--experimental_google_legacy_api");
    ConfiguredTarget testTarget = getConfiguredTarget("//foo:binary");

    TemplateExpansionAction action =
        (TemplateExpansionAction) getGeneratingAction(getExecutable(testTarget));
    // Check that the directory name is on the java.library.path
    assertThat(action.getFileContents())
        .containsMatch("-Djava.library.path=\\$\\{JAVA_RUNFILES\\}/.*/foo");
  }

  /** Tests that java_test propagates direct native library information from JavaInfo provider. */
  @Test
  public void javaTest_propagatesDirectNativeLibrariesInJavaInfo() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo]",
        "  cc_dep_params = ctx.attr.cc_dep[CcInfo]",
        "  java_info = JavaInfo(output_jar = dep_params.java_outputs[0].class_jar,",
        "    compile_jar = None,",
        "    native_libraries = [cc_dep_params])",
        "  return [java_common.merge([dep_params, java_info])]",
        "my_rule = rule(_impl, attrs = { 'dep': attr.label(), 'cc_dep': attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "cc_binary(name = 'native', srcs = ['cc/x.cc'], linkshared=1, linkstatic=1)",
        "java_library(name = 'jl', srcs = ['java/A.java'], data = [':native'])",
        "cc_binary(name = 'ccl', srcs = ['cc/x.cc'], linkshared=1, linkstatic=1)",
        "my_rule(name = 'r', dep = ':jl', cc_dep = ':ccl')",
        "java_test(name = 'test', test_class='test', srcs = ['Test.java'], deps = [':r'])");

    ConfiguredTarget testTarget = getConfiguredTarget("//foo:test");
    TemplateExpansionAction action =
        (TemplateExpansionAction) getGeneratingAction(getExecutable(testTarget));
    // Check that the directory name is on the java.library.path
    assertThat(action.getFileContents())
        .containsMatch("-Djava.library.path=\\$\\{JAVA_RUNFILES\\}/.*/foo");
  }

  /** Tests that java_library exposes native library info to Starlark. */
  @Test
  public void javaLibrary_exposesNativeLibraryInfoToStarlark() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "my_provider = provider()",
        "def _impl(ctx):",
        "  dep_params = ctx.attr.dep[JavaInfo].transitive_native_libraries",
        "  return [my_provider(p = dep_params)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "cc_binary(name = 'native.so', srcs = ['cc/x.cc'], linkshared = True)",
        "java_library(name = 'jl', srcs = ['java/A.java'], deps = [':native.so'])",
        "my_rule(name = 'r', dep = ':jl')");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:r");
    ConfiguredTarget javaLibraryTarget = getConfiguredTarget("//foo:jl");

    JavaInfo javaInfo = javaLibraryTarget.get(JavaInfo.PROVIDER);
    StarlarkProvider.Key myProviderKey =
        new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "my_provider");
    StructImpl declaredProvider = (StructImpl) myRuleTarget.get(myProviderKey);
    Object nativeLibrariesFromStarlark = declaredProvider.getValue("p");
    assertThat(nativeLibrariesFromStarlark)
        .isEqualTo(javaInfo.getTransitiveNativeLibrariesForStarlark());
    assertThat(
            javaInfo
                .getTransitiveNativeLibraries()
                .getSingleton()
                .getDynamicLibrary()
                .getFilename())
        .isEqualTo("native.so");
  }

  /** Tests that JavaInfo propagates native libraries from deps, runtime_deps, and exports. */
  @Test
  public void javaInfo_nativeLibrariesPropagate() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['A.java'],",
        "  deps = [':lib_deps'],",
        "  runtime_deps = [':lib_runtime_deps'],",
        "  exports = [':lib_exports'],",
        ")",
        "java_library(name = 'lib_deps', srcs = ['B.java'], deps = [':native_deps1.so'])",
        "cc_library(name = 'native_deps1.so', srcs = ['a.cc'])",
        "java_library(name = 'lib_runtime_deps', srcs = ['C.java'], deps = [':native_rdeps1.so'])",
        "cc_library(name = 'native_rdeps1.so', srcs = ['c.cc'])",
        "java_library(name = 'lib_exports', srcs = ['D.java'], deps = [':native_exports1.so'])",
        "cc_library(name = 'native_exports1.so', srcs = ['e.cc'])");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  ctx.actions.write(output_jar, '')",
        "  compilation_provider = JavaInfo(",
        "    output_jar = output_jar,",
        "    compile_jar = None,",
        "    deps = [dep[JavaInfo] for dep in ctx.attr.deps if JavaInfo in dep],",
        "    runtime_deps = [dep[JavaInfo] for dep in ctx.attr.runtime_deps if JavaInfo in dep],",
        "    exports = [dep[JavaInfo] for dep in ctx.attr.exports if JavaInfo in dep],",
        "  )",
        "  return [",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=True),",
        "    'deps': attr.label_list(),",
        "    'runtime_deps': attr.label_list(),",
        "    'exports': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");

    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    NestedSet<LibraryToLink> nativeLibraries = info.getTransitiveNativeLibraries();
    assertThat(nativeLibraries.toList().stream().map(LibraryToLink::getLibraryIdentifier))
        .containsExactly(
            "java/test/libnative_rdeps1.so",
            "java/test/libnative_exports1.so",
            "java/test/libnative_deps1.so")
        .inOrder();
  }

  @Test
  public void testJavaInfoGetGenJarsProvider() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].annotation_processing)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'], ",
        "             javacopts = ['-processor com.google.process.Processor'])",
        "my_rule(name = 'my_starlark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_starlark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "result"));

    JavaGenJarsProvider javaGenJarsProvider = (JavaGenJarsProvider) info.getValue("property");

    assertThat(javaGenJarsProvider.getGenClassJar().getFilename())
        .isEqualTo("libmy_java_lib_a-gen.jar");
    assertThat(javaGenJarsProvider.getGenSourceJar().getFilename())
        .isEqualTo("libmy_java_lib_a-gensrc.jar");
  }

  @Test
  public void javaInfoGetCompilationInfoProvider() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(property = ctx.attr.dep[JavaInfo].compilation_info)]",
        "my_rule = rule(_impl, attrs = { 'dep' : attr.label() })");

    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "java_library(name = 'my_java_lib_a', srcs = ['java/A.java'])",
        "my_rule(name = 'my_starlark_rule', dep = ':my_java_lib_a')");
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_starlark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:extension.bzl"), "result"));

    JavaCompilationInfoProvider javaCompilationInfoProvider =
        (JavaCompilationInfoProvider) info.getValue("property");

    assertThat(
            prettyArtifactNames(
                javaCompilationInfoProvider.getRuntimeClasspath().getSet(Artifact.class)))
        .containsExactly("foo/libmy_java_lib_a.jar");
  }

  /* Test inspired by {@link AbstractJavaLibraryConfiguredTargetTest#testNeverlink}.*/
  @Test
  public void javaCommonCompileNeverlink() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_binary(name = 'plugin',",
        "    deps = [ ':somedep'],",
        "    srcs = [ 'Plugin.java'],",
        "    main_class = 'plugin.start')",
        "java_custom_library(name = 'somedep',",
        "    srcs = ['Dependency.java'],",
        "    deps = [ ':eclipse' ])",
        "java_custom_library(name = 'eclipse',",
        "    neverlink = 1,",
        "    srcs = ['EclipseDependency.java'])");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  deps = [dep[java_common.provider] for dep in ctx.attr.deps]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    neverlink = ctx.attr.neverlink,",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_jar]),",
        "      ),",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'neverlink': attr.bool(),",
        "     'deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    setBuildLanguageOptions("--experimental_google_legacy_api");
    ConfiguredTarget target = getConfiguredTarget("//java/test:plugin");
    assertThat(
            actionsTestUtil()
                .predecessorClosureAsCollection(getFilesToBuild(target), JavaSemantics.JAVA_SOURCE))
        .containsExactly("Plugin.java", "Dependency.java", "EclipseDependency.java");
    assertThat(
            ActionsTestUtil.baseNamesOf(
                FileType.filter(
                    getRunfilesSupport(target).getRunfilesSymlinkTargets(), JavaSemantics.JAR)))
        .isEqualTo("plugin.jar libsomedep.jar");
  }

  /**
   * Tests that java_common.compile propagates native libraries from deps, runtime_deps, and
   * exports.
   */
  @Test
  public void javaCommonCompile_nativeLibrariesPropagate() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['A.java'],",
        "  deps = [':lib_deps'],",
        "  runtime_deps = [':lib_runtime_deps'],",
        "  exports = [':lib_exports'],",
        ")",
        "java_library(name = 'lib_deps', srcs = ['B.java'], deps = [':native_deps1.so'])",
        "cc_library(name = 'native_deps1.so', srcs = ['a.cc'])",
        "java_library(name = 'lib_runtime_deps', srcs = ['C.java'], deps = [':native_rdeps1.so'])",
        "cc_library(name = 'native_rdeps1.so', srcs = ['c.cc'])",
        "java_library(name = 'lib_exports', srcs = ['D.java'], deps = [':native_exports1.so'])",
        "cc_library(name = 'native_exports1.so', srcs = ['e.cc'])");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    deps = [dep[JavaInfo] for dep in ctx.attr.deps if JavaInfo in dep],",
        "    runtime_deps = [dep[JavaInfo] for dep in ctx.attr.runtime_deps if JavaInfo in dep],",
        "    exports = [dep[JavaInfo] for dep in ctx.attr.exports if JavaInfo in dep],",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=True),",
        "    'deps': attr.label_list(),",
        "    'runtime_deps': attr.label_list(),",
        "    'exports': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");

    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    NestedSet<LibraryToLink> nativeLibraries = info.getTransitiveNativeLibraries();
    assertThat(nativeLibraries.toList().stream().map(LibraryToLink::getLibraryIdentifier))
        .containsExactly(
            "java/test/libnative_rdeps1.so",
            "java/test/libnative_exports1.so",
            "java/test/libnative_deps1.so")
        .inOrder();
  }

  /**
   * Tests that java_common.compile propagates native libraries passed by native_libraries argument.
   */
  @Test
  public void javaCommonCompile_directNativeLibraries() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['A.java'],",
        "  ccdeps = [':native.so'],",
        ")",
        "cc_library(",
        "  name = 'native.so',",
        "  srcs = ['a.cc'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    native_libraries = [dep[CcInfo] for dep in ctx.attr.ccdeps if CcInfo in dep],",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=True),",
        "    'ccdeps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/test:custom");

    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    NestedSet<LibraryToLink> nativeLibraries = info.getTransitiveNativeLibraries();
    assertThat(nativeLibraries.toList().stream().map(LibraryToLink::getLibraryIdentifier))
        .containsExactly("java/test/libnative.so")
        .inOrder();
  }

  @Test
  public void strictDepsEnabled() throws Exception {
    scratch.file(
        "foo/custom_library.bzl",
        "def _impl(ctx):",
        "  java_provider = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])",
        "  if not ctx.attr.strict_deps:",
        "    java_provider = java_common.make_non_strict(java_provider)",
        "  return [java_provider]",
        "custom_library = rule(",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'strict_deps': attr.bool()",
        "  },",
        "  implementation = _impl",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_library.bzl', 'custom_library')",
        "custom_library(name = 'custom', deps = [':a'], strict_deps = True)",
        "java_library(name = 'a', srcs = ['java/A.java'], deps = [':b'])",
        "java_library(name = 'b', srcs = ['java/B.java'])");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:custom");
    JavaCompilationArgsProvider javaCompilationArgsProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, myRuleTarget);
    List<String> directJars =
        prettyArtifactNames(javaCompilationArgsProvider.getDirectCompileTimeJars());
    assertThat(directJars).containsExactly("foo/liba-hjar.jar");
  }

  @Test
  public void strictDepsDisabled() throws Exception {
    scratch.file(
        "foo/custom_library.bzl",
        "def _impl(ctx):",
        "  java_provider = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])",
        "  if not ctx.attr.strict_deps:",
        "    java_provider = java_common.make_non_strict(java_provider)",
        "  return [java_provider]",
        "custom_library = rule(",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'strict_deps': attr.bool()",
        "  },",
        "  implementation = _impl",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_library.bzl', 'custom_library')",
        "custom_library(name = 'custom', deps = [':a'], strict_deps = False)",
        "java_library(name = 'a', srcs = ['java/A.java'], deps = [':b'])",
        "java_library(name = 'b', srcs = ['java/B.java'])");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:custom");
    JavaCompilationArgsProvider javaCompilationArgsProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, myRuleTarget);
    List<String> directJars = prettyArtifactNames(javaCompilationArgsProvider.getRuntimeJars());
    assertThat(directJars).containsExactly("foo/liba.jar", "foo/libb.jar");
  }

  @Test
  public void strictJavaDepsFlagExposed_default() throws Exception {
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(strict_java_deps=ctx.fragments.java.strict_java_deps)]",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java']",
        ")");
    scratch.file("foo/BUILD", "load(':rule.bzl', 'myrule')", "myrule(name='myrule')");
    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:myrule");
    StructImpl info =
        (StructImpl)
            configuredTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:rule.bzl"), "result"));
    assertThat(((String) info.getValue("strict_java_deps"))).isEqualTo("default");
  }

  @Test
  public void strictJavaDepsFlagExposed_error() throws Exception {
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(strict_java_deps=ctx.fragments.java.strict_java_deps)]",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java']",
        ")");
    scratch.file("foo/BUILD", "load(':rule.bzl', 'myrule')", "myrule(name='myrule')");
    useConfiguration("--strict_java_deps=ERROR");
    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:myrule");
    StructImpl info =
        (StructImpl)
            configuredTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:rule.bzl"), "result"));
    assertThat(((String) info.getValue("strict_java_deps"))).isEqualTo("error");
  }

  @Test
  public void useIjars_fails() throws Exception {
    setBuildLanguageOptions("--experimental_builtins_injection_override=+java_import");
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  ctx.fragments.java.use_ijars()",
        "  return []",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java']",
        ")");
    scratch.file("foo/BUILD", "load(':rule.bzl', 'myrule')", "myrule(name='myrule')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:myrule");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void disallowJavaImportExports_fails() throws Exception {
    setBuildLanguageOptions("--experimental_builtins_injection_override=+java_import");
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  ctx.fragments.java.disallow_java_import_exports()",
        "  return []",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java']",
        ")");
    scratch.file("foo/BUILD", "load(':rule.bzl', 'myrule')", "myrule(name='myrule')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:myrule");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void mergeRuntimeOutputJarsTest() throws Exception {
    scratch.file(
        "foo/custom_library.bzl",
        "def _impl(ctx):",
        "  java_provider = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])",
        "  return [java_provider]",
        "custom_library = rule(",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'strict_deps': attr.bool()",
        "  },",
        "  implementation = _impl",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_library.bzl', 'custom_library')",
        "custom_library(name = 'custom', deps = [':a', ':b'])",
        "java_library(name = 'a', srcs = ['java/A.java'])",
        "java_library(name = 'b', srcs = ['java/B.java'])");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:custom");
    JavaInfo javaInfo = (JavaInfo) myRuleTarget.get(JavaInfo.PROVIDER.getKey());
    List<String> directJars = prettyArtifactNames(javaInfo.getRuntimeOutputJars());
    assertThat(directJars).containsExactly("foo/liba.jar", "foo/libb.jar");
  }

  @Test
  public void javaToolchainFlag_default() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(java_toolchain_label=ctx.attr._java_toolchain)]",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java'],",
        "  attrs = { '_java_toolchain': attr.label(default=Label('//foo:alias')) }",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':rule.bzl', 'myrule')",
        "load('"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:java_toolchain_alias.bzl', 'java_toolchain_alias')",
        "java_toolchain_alias(name='alias')",
        "myrule(name='myrule')");
    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:myrule");
    StructImpl info =
        (StructImpl)
            configuredTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:rule.bzl"), "result"));
    JavaToolchainProvider javaToolchainProvider =
        JavaToolchainProvider.from((ConfiguredTarget) info.getValue("java_toolchain_label"));
    Label javaToolchainLabel = javaToolchainProvider.getToolchainLabel();
    assertWithMessage(javaToolchainLabel.toString())
        .that(
            javaToolchainLabel.toString().endsWith("jdk:remote_toolchain")
                || javaToolchainLabel.toString().endsWith("jdk:toolchain")
                || javaToolchainLabel.toString().endsWith("jdk:toolchain_host"))
        .isTrue();
  }

  @Test
  public void javaToolchainFlag_set() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  return [result(java_toolchain_label=ctx.attr._java_toolchain)]",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java'],",
        "  attrs = { '_java_toolchain': attr.label(default=Label('//foo:alias')) }",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':rule.bzl', 'myrule')",
        "load('"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:java_toolchain_alias.bzl', 'java_toolchain_alias')",
        "java_toolchain_alias(name='alias')",
        "myrule(name='myrule')");
    useConfiguration("--extra_toolchains=//java/com/google/test:all");
    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:myrule");
    StructImpl info =
        (StructImpl)
            configuredTarget.get(
                new StarlarkProvider.Key(Label.parseCanonical("//foo:rule.bzl"), "result"));
    JavaToolchainProvider javaToolchainProvider =
        JavaToolchainProvider.from((ConfiguredTarget) info.getValue("java_toolchain_label"));
    Label javaToolchainLabel = javaToolchainProvider.getToolchainLabel();
    assertThat(javaToolchainLabel.toString()).isEqualTo("//java/com/google/test:toolchain");
  }

  private static boolean javaCompilationArgsHaveTheSameParent(
      JavaCompilationArgsProvider args, JavaCompilationArgsProvider otherArgs) {
    if (!nestedSetsOfArtifactHaveTheSameParent(
        args.getTransitiveCompileTimeJars(), otherArgs.getTransitiveCompileTimeJars())) {
      return false;
    }
    if (!nestedSetsOfArtifactHaveTheSameParent(args.getRuntimeJars(), otherArgs.getRuntimeJars())) {
      return false;
    }
    return true;
  }

  private static boolean nestedSetsOfArtifactHaveTheSameParent(
      NestedSet<Artifact> artifacts, NestedSet<Artifact> otherArtifacts) {
    Iterator<Artifact> iterator = artifacts.toList().iterator();
    Iterator<Artifact> otherIterator = otherArtifacts.toList().iterator();
    while (iterator.hasNext() && otherIterator.hasNext()) {
      Artifact artifact = iterator.next();
      Artifact otherArtifact = otherIterator.next();
      if (!artifact
          .getPath()
          .getParentDirectory()
          .equals(otherArtifact.getPath().getParentDirectory())) {
        return false;
      }
    }
    if (iterator.hasNext() || otherIterator.hasNext()) {
      return false;
    }
    return true;
  }

  @Test
  public void testCompileExports() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "java/test/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(",
        "  name = 'custom',",
        "  srcs = ['Main.java'],",
        "  exports = [':dep']",
        ")",
        "java_library(",
        "  name = 'dep',",
        "  srcs = [ 'Dep.java'],",
        ")");
    scratch.file(
        "java/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('amazing.jar')",
        "  exports = [export[java_common.provider] for export in ctx.attr.exports]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    exports = exports,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [",
        "      DefaultInfo(",
        "          files = depset([output_jar]),",
        "      ),",
        "      compilation_provider",
        "  ]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'output': 'amazing.jar',",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'exports': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");

    JavaInfo info = getConfiguredTarget("//java/test:custom").get(JavaInfo.PROVIDER);
    assertThat(prettyArtifactNames(info.getTransitiveSourceJars().getSet(Artifact.class)))
        .containsExactly("java/test/amazing-src.jar", "java/test/libdep-src.jar");
    JavaCompilationArgsProvider provider = info.getProvider(JavaCompilationArgsProvider.class);
    assertThat(prettyArtifactNames(provider.getDirectCompileTimeJars()))
        .containsExactly("java/test/amazing-hjar.jar", "java/test/libdep-hjar.jar");
    assertThat(prettyArtifactNames(provider.getCompileTimeJavaDependencyArtifacts()))
        .containsExactly("java/test/amazing-hjar.jdeps", "java/test/libdep-hjar.jdeps");
  }

  @Test
  public void testCompileOutputJarHasManifestProto() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/java_custom_library.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib%s.jar' % ctx.label.name)",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [compilation_provider]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java'],",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':java_custom_library.bzl', 'java_custom_library')",
        "java_custom_library(name = 'b', srcs = ['java/B.java'])");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:b");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    ImmutableList<JavaOutput> javaOutputs = info.getJavaOutputs();
    assertThat(javaOutputs).hasSize(1);
    JavaOutput output = javaOutputs.get(0);
    assertThat(output.getManifestProto().getFilename()).isEqualTo("libb.jar_manifest_proto");
  }

  @Test
  public void testCompileWithNeverlinkDeps() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/java_custom_library.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib%s.jar' % ctx.label.name)",
        "  deps = [deps[JavaInfo] for deps in ctx.attr.deps]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    deps = deps,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [compilation_provider]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'deps': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java'],",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':java_custom_library.bzl', 'java_custom_library')",
        "java_library(name = 'b', srcs = ['java/B.java'], neverlink = 1)",
        "java_custom_library(name = 'a', srcs = ['java/A.java'], deps = [':b'])");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:a");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    assertThat(artifactFilesNames(info.getTransitiveRuntimeJars().toList(Artifact.class)))
        .containsExactly("liba.jar");
    assertThat(artifactFilesNames(info.getTransitiveSourceJars().getSet(Artifact.class)))
        .containsExactly("liba-src.jar", "libb-src.jar");
    assertThat(artifactFilesNames(info.getTransitiveCompileTimeJars().toList(Artifact.class)))
        .containsExactly("liba-hjar.jar", "libb-hjar.jar");
  }

  @Test
  public void testCompileOutputJarNotInRuntimePathWithoutAnySourcesDefined() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/java_custom_library.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib%s.jar' % ctx.label.name)",
        "  exports = [export[JavaInfo] for export in ctx.attr.exports]",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    exports = exports,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return [compilation_provider]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    'exports': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java'],",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':java_custom_library.bzl', 'java_custom_library')",
        "java_library(name = 'b', srcs = ['java/B.java'])",
        "java_custom_library(name = 'c', srcs = [], exports = [':b'])");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:c");
    JavaInfo info = configuredTarget.get(JavaInfo.PROVIDER);
    assertThat(artifactFilesNames(info.getTransitiveRuntimeJars().toList(Artifact.class)))
        .containsExactly("libb.jar");
    assertThat(artifactFilesNames(info.getTransitiveCompileTimeJars().toList(Artifact.class)))
        .containsExactly("libb-hjar.jar");
    ImmutableList<JavaOutput> javaOutputs = info.getJavaOutputs();
    assertThat(javaOutputs).hasSize(1);
    JavaOutput output = javaOutputs.get(0);
    assertThat(output.getClassJar().getFilename()).isEqualTo("libc.jar");
    assertThat(output.getCompileJar()).isNull();
  }

  @Test
  public void testConfiguredTargetToolchain() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);

    scratch.file(
        "a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "java_runtime(name='jvm', srcs=[], java_home='/foo/bar')",
        "jrule(name='r', srcs=['S.java'])");

    scratch.file(
        "a/rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    java_toolchain = ctx.attr._java_toolchain,",
        "  )",
        "  return []",
        "jrule = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java'])");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:r");
    assertContainsEvent("got value of type 'Target', want 'JavaToolchainInfo'");
  }

  @Test
  public void defaultJavacOpts() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  return MyInfo(",
        "    javac_opts = java_common.default_javac_opts(",
        "        java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo])",
        "    )",
        "get_javac_opts = rule(",
        "  _impl,",
        "  attrs = {",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  }",
        ");");

    scratch.file("a/BUILD", "load(':rule.bzl', 'get_javac_opts')", "get_javac_opts(name='r')");

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    @SuppressWarnings("unchecked") // Use an extra variable in order to suppress the warning.
    Sequence<String> javacopts = (Sequence<String>) getMyInfoFromTarget(r).getValue("javac_opts");
    assertThat(String.join(" ", javacopts)).contains("-source 6 -target 6");
  }

  @Test
  public void defaultJavacOpts_toolchainProvider() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  return MyInfo(",
        "    javac_opts = java_common.default_javac_opts(",
        "        java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo])",
        "    )",
        "get_javac_opts = rule(",
        "  _impl,",
        "  attrs = {",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  }",
        ");");

    scratch.file("a/BUILD", "load(':rule.bzl', 'get_javac_opts')", "get_javac_opts(name='r')");

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    @SuppressWarnings("unchecked") // Use an extra variable in order to suppress the warning.
    Sequence<String> javacopts = (Sequence<String>) getMyInfoFromTarget(r).getValue("javac_opts");
    assertThat(String.join(" ", javacopts)).contains("-source 6 -target 6");
  }

  @Test
  public void testJavaRuntimeProviderFiles() throws Exception {
    scratch.file("a/a.txt", "hello");
    scratch.file(
        "a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "load('"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:java_toolchain_alias.bzl', 'java_runtime_alias')",
        "java_runtime(name='jvm', srcs=['a.txt'], java_home='foo/bar')",
        "java_runtime_alias(name='alias')",
        "jrule(name='r')",
        "toolchain(",
        "    name = 'java_runtime_toolchain',",
        "    toolchain = ':jvm',",
        "    toolchain_type = '"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:runtime_toolchain_type',",
        ")");

    scratch.file(
        "a/rule.bzl",
        "def _impl(ctx):",
        "  provider = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]",
        "  return DefaultInfo(",
        "    files = provider.files,",
        "  )",
        "jrule = rule(_impl, attrs = { '_java_runtime': attr.label(default=Label('//a:alias'))})");

    useConfiguration("--extra_toolchains=//a:all");
    ConfiguredTarget ct = getConfiguredTarget("//a:r");
    Depset files = (Depset) ct.get("files");
    assertThat(prettyArtifactNames(files.toList(Artifact.class))).containsExactly("a/a.txt");
  }

  @Test
  public void testJavaLibaryCollectsCoverageDependenciesFromResources() throws Exception {
    useConfiguration("--collect_code_coverage");

    scratch.file(
        "java/BUILD",
        "java_library(",
        "    name = 'lib',",
        "    resources = [':libjni.so'],",
        ")",
        "",
        "cc_binary(",
        "    name = 'libjni.so',",
        "    srcs = ['jni.cc'],",
        "    linkshared = 1,",
        ")");

    InstrumentedFilesInfo target = getInstrumentedFilesProvider("//java:lib");

    assertThat(prettyArtifactNames(target.getInstrumentedFiles())).containsExactly("java/jni.cc");
    assertThat(prettyArtifactNames(target.getInstrumentationMetadataFiles()))
        .containsExactly("java/_objs/libjni.so/jni.gcno");
  }

  @Test
  public void testSkipAnnotationProcessing() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "    deps = [p[JavaInfo] for p in ctx.attr.deps],",
        "    plugins = [p[JavaPluginInfo] for p in ctx.attr.plugins],",
        "    enable_annotation_processing = False,",
        "  )",
        "  return struct(",
        "    files = depset([output_jar]),",
        "    providers = [compilation_provider]",
        "  )",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=True),",
        "    'deps': attr.label_list(providers=[JavaInfo]),",
        "    'plugins': attr.label_list(providers=[JavaPluginInfo]),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_plugin(",
        "  name = 'processor',",
        "  srcs = ['processor.java'],",
        "  processor_class = 'Foo',",
        "  generates_api = 1,", // so Turbine would normally run it
        "  data = ['processor_data.txt'],",
        ")",
        "java_library(",
        "  name = 'exports_processor',",
        "  exported_plugins = [':processor'],",
        ")",
        "java_custom_library(",
        "  srcs = ['custom.java'],",
        "  name = 'custom',",
        "  deps = [':exports_processor'],",
        "  plugins = [':processor'],",
        ")",
        "java_custom_library(",
        "  srcs = ['custom.java'],",
        "  name = 'custom_noproc',",
        ")");

    ConfiguredTarget custom = getConfiguredTarget("//foo:custom");
    ConfiguredTarget customNoproc = getConfiguredTarget("//foo:custom_noproc");
    assertNoEvents();

    JavaCompileAction javacAction =
        (JavaCompileAction) getGeneratingActionForLabel("//foo:libcustom.jar");
    assertThat(javacAction.getMnemonic()).isEqualTo("Javac");
    assertThat(getProcessorNames(javacAction)).isEmpty();
    assertThat(getProcessorPath(javacAction)).isNotEmpty();
    assertThat(artifactFilesNames(javacAction.getInputs())).contains("processor_data.txt");

    JavaCompileAction turbineAction =
        (JavaCompileAction) getGeneratingAction(getBinArtifact("libcustom-hjar.jar", custom));
    assertThat(turbineAction.getMnemonic()).isEqualTo("JavacTurbine");
    List<String> args = turbineAction.getArguments();
    assertThat(args).doesNotContain("--processors");

    // enable_annotation_processing=False shouldn't disable direct classpaths if there are no
    // annotation processors that need to be disabled
    SpawnAction turbineActionNoProc =
        (SpawnAction)
            getGeneratingAction(getBinArtifact("libcustom_noproc-hjar.jar", customNoproc));
    assertThat(turbineActionNoProc.getMnemonic()).isEqualTo("Turbine");
    assertThat(turbineActionNoProc.getArguments()).doesNotContain("--processors");
  }

  @Test
  public void testCompileWithDisablingCompileJarIsPrivateApi() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  java_common.compile(",
        "    ctx,",
        "    output = ctx.actions.declare_file('output.jar'),",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "    enable_compile_jar_action = False,",
        "  )",
        "  return []",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(name = 'custom')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:custom");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void testCompileWithClasspathResourcesIsPrivateApi() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file("foo/resource.txt", "Totally real resource content");
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  java_common.compile(",
        "    ctx,",
        "    output = ctx.actions.declare_file('output.jar'),",
        "    classpath_resources = ctx.files.classpath_resources,",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "  )",
        "  return []",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(),",
        "    'classpath_resources': attr.label_list(allow_files = True),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(name = 'custom', classpath_resources = ['resource.txt'])");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:custom");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void testGetBuildInfoArtifactsIsPrivateApi() throws Exception {
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  artifacts = java_common.get_build_info(ctx)",
        "  return [DefaultInfo(files = depset(artifacts))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {},",
        ")");
    scratch.file(
        "foo/BUILD", "load(':custom_rule.bzl', 'custom_rule')", "custom_rule(name = 'custom')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:custom");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void testInjectingRuleKindIsPrivateApi() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  java_common.compile(",
        "    ctx,",
        "    output = ctx.actions.declare_file('output.jar'),",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "    injecting_rule_kind = 'example_rule_kind',",
        "  )",
        "  return []",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(name = 'custom')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:custom");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void testEnableJSpecifyIsPrivateApi() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  java_common.compile(",
        "    ctx,",
        "    output = ctx.actions.declare_file('output.jar'),",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "    enable_jspecify = False,",
        "  )",
        "  return []",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(name = 'custom')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:custom");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void testMergeJavaOutputsIsPrivateApi() throws Exception {
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib.jar')",
        "  java_info = JavaInfo(output_jar = output_jar, compile_jar = None)",
        "  java_common.merge([java_info],",
        "    merge_java_outputs = False",
        "  )",
        "  return []",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(name = 'custom')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:custom");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void testMergeSourceJarsIsPrivateApi() throws Exception {
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib.jar')",
        "  java_info = JavaInfo(output_jar = output_jar, compile_jar = None)",
        "  java_common.merge([java_info],",
        "    merge_source_jars = False",
        "  )",
        "  return []",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(name = 'custom')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:custom");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void testCompileIncludeCompilationInfoIsPrivateApi() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  java_common.compile(",
        "    ctx,",
        "    output = ctx.actions.declare_file('output.jar'),",
        "    java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],",
        "    include_compilation_info = False,",
        "  )",
        "  return []",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(name = 'custom')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:custom");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void testCompileWithResorceJarsIsPrivateApi() throws Exception {
    JavaToolchainTestUtil.writeBuildFileForJavaToolchain(scratch);
    scratch.file(
        "foo/custom_rule.bzl",
        "def _impl(ctx):",
        "  java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo]",
        "  java_common.compile(",
        "    ctx,",
        "    output = ctx.actions.declare_file('output.jar'),",
        "    java_toolchain = java_toolchain,",
        "    resource_jars = [java_toolchain.timezone_data()],",
        "  )",
        "  return []",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(),",
        "    '_java_toolchain': attr.label(default = Label('//java/com/google/test:toolchain')),",
        "  },",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_rule.bzl', 'java_custom_library')",
        "java_custom_library(name = 'custom')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:custom");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void disallowJavaImportEmptyJars_fails() throws Exception {
    scratch.file(
        "foo/rule.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  ctx.fragments.java.disallow_java_import_empty_jars()",
        "  return []",
        "myrule = rule(",
        "  implementation=_impl,",
        "  fragments = ['java']",
        ")");
    scratch.file("foo/BUILD", "load(':rule.bzl', 'myrule')", "myrule(name='myrule')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo:myrule");

    assertContainsEvent("Rule in 'foo' cannot use private API");
  }

  @Test
  public void testGetBuildInfoArtifacts() throws Exception {
    scratch.file(
        "bazel_internal/test/custom_rule.bzl",
        "def _impl(ctx):",
        "  artifacts = java_common.get_build_info(ctx)",
        "  return [DefaultInfo(files = depset(artifacts))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {},",
        ")");
    scratch.file(
        "bazel_internal/test/BUILD",
        "load(':custom_rule.bzl', 'custom_rule')",
        "custom_rule(name = 'custom')");

    NestedSet<Artifact> artifacts =
        getConfiguredTarget("//bazel_internal/test:custom")
            .getProvider(FileProvider.class)
            .getFilesToBuild();

    assertThat(prettyArtifactNames(artifacts)).containsExactly("build-info-redacted.properties");
  }

  @Test
  public void mergeAddExports() throws Exception {
    scratch.file(
        "foo/custom_library.bzl",
        "def _impl(ctx):",
        "  java_provider = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])",
        "  return [java_provider]",
        "custom_library = rule(",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "  },",
        "  implementation = _impl",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':custom_library.bzl', 'custom_library')",
        "custom_library(name = 'custom', deps = [':a'])",
        "java_library(name = 'a', srcs = ['java/A.java'], add_exports = ['java.base/java.lang'])");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:custom");
    JavaModuleFlagsProvider provider =
        JavaInfo.getProvider(JavaModuleFlagsProvider.class, myRuleTarget);
    assertThat(provider.toFlags()).containsExactly("--add-exports=java.base/java.lang=ALL-UNNAMED");
  }

  private InstrumentedFilesInfo getInstrumentedFilesProvider(String label) throws Exception {
    return getConfiguredTarget(label).get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
  }

  @Test
  public void hermeticStaticLibs() throws Exception {
    scratch.file("a/libStatic.a");
    scratch.file(
        "a/BUILD",
        "load(':rule.bzl', 'jrule')",
        "load('"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:java_toolchain_alias.bzl', 'java_runtime_alias')",
        "genrule(name='gen', cmd='', outs=['foo/bar/bin/java'])",
        "cc_import(name='libs', static_library = 'libStatic.a')",
        "cc_library(name = 'jdk_static_libs00', data = ['libStatic.a'], linkstatic = 1)",
        "java_runtime(name='jvm', srcs=[], java='foo/bar/bin/java', hermetic_static_libs ="
            + " ['libs'])",
        "java_runtime_alias(name='alias')",
        "jrule(name='r')",
        "toolchain(",
        "    name = 'java_runtime_toolchain',",
        "    toolchain = ':jvm',",
        "    toolchain_type = '"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:runtime_toolchain_type',",
        ")");
    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  provider = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]",
        "  return MyInfo(",
        "    hermetic_static_libs = provider.hermetic_static_libs,",
        "  )",
        "jrule = rule(_impl, attrs = { '_java_runtime': attr.label(default=Label('//a:alias'))})");

    useConfiguration("--extra_toolchains=//a:all");
    ConfiguredTarget ct = getConfiguredTarget("//a:r");
    StructImpl myInfo = getMyInfoFromTarget(ct);
    @SuppressWarnings("unchecked")
    Sequence<CcInfo> hermeticStaticLibs =
        (Sequence<CcInfo>) myInfo.getValue("hermetic_static_libs");
    assertThat(hermeticStaticLibs).hasSize(1);
    assertThat(
            hermeticStaticLibs.get(0).getCcLinkingContext().getLibraries().toList().stream()
                .map(LibraryToLink::getLibraryIdentifier))
        .containsExactly("a/libStatic");
  }
}
