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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.Expander;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.StrictDepsMode;
import com.google.devtools.build.lib.analysis.configuredtargets.AbstractConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.MergedConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.analysis.starlark.StarlarkActionFactory;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfoNoSchema;
import com.google.devtools.build.lib.packages.StarlarkInfoWithSchema;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.rules.cpp.CppFileTypes;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaCommonApi;
import com.google.devtools.build.lib.util.OS;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** A module that contains Starlark utilities for Java support. */
public class JavaStarlarkCommon
    implements JavaCommonApi<
        Artifact,
        JavaInfo,
        ConstraintValueInfo,
        StarlarkRuleContext,
        StarlarkActionFactory> {

  private static final ImmutableSet<BuiltinRestriction.AllowlistEntry>
      PRIVATE_STARLARKIFACTION_ALLOWLIST =
          ImmutableSet.of(BuiltinRestriction.allowlistEntry("", "bazel_internal/test_rules"));
  private final JavaSemantics javaSemantics;

  private static StrictDepsMode getStrictDepsMode(String strictDepsMode) {
    switch (strictDepsMode) {
      case "OFF":
        return StrictDepsMode.OFF;
      case "ERROR":
      case "DEFAULT":
        return StrictDepsMode.ERROR;
      case "WARN":
        return StrictDepsMode.WARN;
      default:
        throw new IllegalArgumentException(
            "StrictDepsMode "
                + strictDepsMode
                + " not allowed."
                + " Only OFF and ERROR values are accepted.");
    }
  }

  private void checkJavaToolchainIsDeclaredOnRule(RuleContext ruleContext)
      throws EvalException, LabelSyntaxException {
    ToolchainInfo toolchainInfo =
        ruleContext.getToolchainInfo(Label.parseCanonical(javaSemantics.getJavaToolchainType()));
    if (toolchainInfo == null) {
      String ruleLocation = ruleContext.getRule().getLocation().toString();
      String ruleClass = ruleContext.getRule().getRuleClassObject().getName();
      throw Starlark.errorf(
          "Rule '%s' in '%s' must declare '%s' toolchain in order to use java_common. See"
              + " https://github.com/bazelbuild/bazel/issues/18970.",
          ruleClass, ruleLocation, javaSemantics.getJavaToolchainType());
    }
  }

  @Override
  public void checkJavaToolchainIsDeclaredOnRuleForStarlark(
      StarlarkActionFactory actions, StarlarkThread thread)
      throws EvalException, LabelSyntaxException {
    checkPrivateAccess(thread);
    checkJavaToolchainIsDeclaredOnRule(actions.getRuleContext());
  }

  public JavaStarlarkCommon(JavaSemantics javaSemantics) {
    this.javaSemantics = javaSemantics;
  }

  @Override
  public void createHeaderCompilationAction(
      StarlarkRuleContext ctx,
      Info toolchain,
      Artifact headerJar,
      Artifact headerDepsProto,
      Info pluginInfo,
      Depset sourceFiles,
      Sequence<?> sourceJars,
      Depset compileTimeClasspath,
      Depset directJars,
      Object bootClassPathUnchecked,
      Depset compileTimeJavaDeps,
      Depset javacOpts,
      String strictDepsMode,
      Label targetLabel,
      Object injectingRuleKind,
      boolean enableDirectClasspath,
      Sequence<?> additionalInputs)
      throws EvalException,
          TypeException,
          RuleErrorException,
          LabelSyntaxException,
          InterruptedException {
    checkJavaToolchainIsDeclaredOnRule(ctx.getRuleContext());
    JavaTargetAttributes.Builder attributesBuilder =
        new JavaTargetAttributes.Builder(javaSemantics)
            .addSourceJars(Sequence.cast(sourceJars, Artifact.class, "source_jars"))
            .addSourceFiles(sourceFiles.toList(Artifact.class))
            .addDirectJars(directJars.getSet(Artifact.class))
            .setCompileTimeClassPathEntriesWithPrependedDirectJars(
                compileTimeClasspath.getSet(Artifact.class))
            .setStrictJavaDeps(getStrictDepsMode(Ascii.toUpperCase(strictDepsMode)))
            .setTargetLabel(targetLabel)
            .setInjectingRuleKind(
                injectingRuleKind == Starlark.NONE ? null : (String) injectingRuleKind)
            .addPlugin(JavaPluginInfo.PROVIDER.wrap(pluginInfo))
            .addCompileTimeDependencyArtifacts(compileTimeJavaDeps.getSet(Artifact.class));
    if (bootClassPathUnchecked instanceof Info) {
      BootClassPathInfo bootClassPathInfo =
          BootClassPathInfo.PROVIDER.wrap((Info) bootClassPathUnchecked);
      if (!bootClassPathInfo.isEmpty()) {
        attributesBuilder.setBootClassPath(bootClassPathInfo);
      }
    }
    JavaCompilationHelper compilationHelper =
        new JavaCompilationHelper(
            ctx.getRuleContext(),
            javaSemantics,
            JavaHelper.tokenizeJavaOptions(Depset.cast(javacOpts, String.class, "javac_opts")),
            attributesBuilder,
            JavaToolchainProvider.PROVIDER.wrap(toolchain),
            Sequence.cast(additionalInputs, Artifact.class, "additional_inputs")
                .getImmutableList());
    compilationHelper.enableDirectClasspath(enableDirectClasspath);
    compilationHelper.createHeaderCompilationAction(headerJar, headerDepsProto);
  }

  @Override
  public void createCompilationAction(
      StarlarkRuleContext ctx,
      Info javaToolchain,
      Artifact output,
      Artifact manifestProto,
      Info pluginInfo,
      Depset compileTimeClasspath,
      Depset directJars,
      Object bootClassPathUnchecked,
      Depset compileTimeJavaDeps,
      Depset javacOpts,
      String strictDepsMode,
      Label targetLabel,
      Object depsProto,
      Object genClass,
      Object genSource,
      Object nativeHeader,
      Object sourceFiles,
      Sequence<?> sourceJars,
      Sequence<?> resources,
      Object resourceJars,
      Sequence<?> classpathResources,
      Sequence<?> sourcepath,
      Object injectingRuleKind,
      boolean enableJSpecify,
      boolean enableDirectClasspath,
      Sequence<?> additionalInputs,
      Sequence<?> additionalOutputs,
      boolean enableInstrumentation)
      throws EvalException,
          TypeException,
          RuleErrorException,
          LabelSyntaxException,
          InterruptedException {
    checkJavaToolchainIsDeclaredOnRule(ctx.getRuleContext());
    JavaCompileOutputs<Artifact> outputs =
        JavaCompileOutputs.builder()
            .output(output)
            .depsProto(depsProto == Starlark.NONE ? null : (Artifact) depsProto)
            .genClass(genClass == Starlark.NONE ? null : (Artifact) genClass)
            .genSource(genSource == Starlark.NONE ? null : (Artifact) genSource)
            .nativeHeader(nativeHeader == Starlark.NONE ? null : (Artifact) nativeHeader)
            .manifestProto(manifestProto)
            .build();
    JavaTargetAttributes.Builder attributesBuilder =
        new JavaTargetAttributes.Builder(javaSemantics)
            .addSourceJars(Sequence.cast(sourceJars, Artifact.class, "source_jars"))
            .addSourceFiles(Depset.noneableCast(sourceFiles, Artifact.class, "sources").toList())
            .addDirectJars(directJars.getSet(Artifact.class))
            .setCompileTimeClassPathEntriesWithPrependedDirectJars(
                compileTimeClasspath.getSet(Artifact.class))
            .addClassPathResources(
                Sequence.cast(classpathResources, Artifact.class, "classpath_resources"))
            .setStrictJavaDeps(getStrictDepsMode(Ascii.toUpperCase(strictDepsMode)))
            .setTargetLabel(targetLabel)
            .setInjectingRuleKind(
                injectingRuleKind == Starlark.NONE ? null : (String) injectingRuleKind)
            .setSourcePath(
                Sequence.cast(sourcepath, Artifact.class, "source_path").getImmutableList())
            .addPlugin(JavaPluginInfo.PROVIDER.wrap(pluginInfo))
            .addAdditionalOutputs(
                Sequence.cast(additionalOutputs, Artifact.class, "additional_outputs"));
    if (bootClassPathUnchecked instanceof Info) {
      BootClassPathInfo bootClassPathInfo =
          BootClassPathInfo.PROVIDER.wrap((Info) bootClassPathUnchecked);
      if (!bootClassPathInfo.isEmpty()) {
        attributesBuilder.setBootClassPath(bootClassPathInfo);
      }
    }
    for (Artifact resource : Sequence.cast(resources, Artifact.class, "resources")) {
      attributesBuilder.addResource(
          JavaHelper.getJavaResourcePath(javaSemantics, ctx.getRuleContext(), resource), resource);
    }
    attributesBuilder.addResourceJars(
        Depset.noneableCast(resourceJars, Artifact.class, "resource_jars"));
    attributesBuilder.addCompileTimeDependencyArtifacts(compileTimeJavaDeps.getSet(Artifact.class));
    JavaCompilationHelper compilationHelper =
        new JavaCompilationHelper(
            ctx.getRuleContext(),
            javaSemantics,
            JavaHelper.tokenizeJavaOptions(Depset.cast(javacOpts, String.class, "javac_opts")),
            attributesBuilder,
            JavaToolchainProvider.PROVIDER.wrap(javaToolchain),
            Sequence.cast(additionalInputs, Artifact.class, "additional_inputs")
                .getImmutableList());
    compilationHelper.enableJspecify(enableJSpecify);
    compilationHelper.enableDirectClasspath(enableDirectClasspath);
    compilationHelper.enableInstrumentation(enableInstrumentation);
    compilationHelper.createCompileAction(outputs);
  }

  @Override
  // TODO(b/78512644): migrate callers to passing explicit javacopts or using custom toolchains, and
  // delete
  public StarlarkValue getDefaultJavacOpts(Info javaToolchainUnchecked, boolean asDepset)
      throws EvalException, RuleErrorException {
    JavaToolchainProvider javaToolchain =
        JavaToolchainProvider.PROVIDER.wrap(javaToolchainUnchecked);
    // We don't have a rule context if the default_javac_opts.java_toolchain parameter is set
    if (asDepset) {
      return Depset.of(String.class, javaToolchain.getJavacOptions(/* ruleContext= */ null));
    } else {
      return StarlarkList.immutableCopyOf(
          javaToolchain.getJavacOptionsAsList(/* ruleContext= */ null));
    }
  }

  @Override
  public ProviderApi getJavaToolchainProvider() {
    // method exists solely for documentation
    throw new UnsupportedOperationException();
  }

  @Override
  public Provider getJavaRuntimeProvider() {
    // method exists purely for documentation
    throw new UnsupportedOperationException();
  }

  @Override
  public ProviderApi getBootClassPathInfo() {
    // method exists solely for documentation
    throw new UnsupportedOperationException();
  }

  @Override
  public String getTargetKind(Object target, StarlarkThread thread) throws EvalException {
    checkPrivateAccess(thread);
    if (target instanceof MergedConfiguredTarget) {
      target = ((MergedConfiguredTarget) target).getBaseConfiguredTarget();
    }
    if (target instanceof ConfiguredTarget) {
      target = ((ConfiguredTarget) target).getActual();
    }
    if (target instanceof AbstractConfiguredTarget) {
      return ((AbstractConfiguredTarget) target).getRuleClassString();
    }
    return "";
  }

  protected static void checkPrivateAccess(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideAllowlist(thread, PRIVATE_STARLARKIFACTION_ALLOWLIST);
  }

  @Override
  public Sequence<Artifact> getBuildInfo(
      StarlarkRuleContext starlarkRuleContext, boolean isStampingEnabled, StarlarkThread thread)
      throws EvalException, InterruptedException {
    checkPrivateAccess(thread);
    RuleContext ruleContext = starlarkRuleContext.getRuleContext();
    return StarlarkList.immutableCopyOf(
        ruleContext
            .getAnalysisEnvironment()
            .getBuildInfo(
                isStampingEnabled, JavaBuildInfoFactory.KEY, ruleContext.getConfiguration()));
  }

  @Override
  public Sequence<String> collectNativeLibsDirs(Depset libraries, StarlarkThread thread)
      throws EvalException, TypeException {
    checkPrivateAccess(thread);
    ImmutableList<Artifact> nativeLibraries =
        LibraryToLink.getDynamicLibrariesForLinking(libraries.getSet(LibraryToLink.class));
    ImmutableList<String> uniqueDirs =
        nativeLibraries.stream()
            .filter(
                nativeLibrary -> {
                  String name = nativeLibrary.getFilename();
                  if (CppFileTypes.INTERFACE_SHARED_LIBRARY.matches(name)) {
                    return false;
                  }
                  if (!(CppFileTypes.SHARED_LIBRARY.matches(name)
                      || CppFileTypes.VERSIONED_SHARED_LIBRARY.matches(name))) {
                    throw new IllegalArgumentException(
                        "not a shared library :" + nativeLibrary.prettyPrint());
                  }
                  return true;
                })
            .map(artifact -> artifact.getRootRelativePath().getParentDirectory().getPathString())
            .distinct()
            .collect(toImmutableList());
    return StarlarkList.immutableCopyOf(uniqueDirs);
  }

  @Override
  public Depset getRuntimeClasspathForArchive(
      Depset runtimeClasspath, Depset excludedArtifacts, StarlarkThread thread)
      throws EvalException, TypeException {
    checkPrivateAccess(thread);
    if (excludedArtifacts.isEmpty()) {
      return runtimeClasspath;
    } else {
      return Depset.of(
          Artifact.class,
          NestedSetBuilder.wrap(
              Order.STABLE_ORDER,
              Iterables.filter(
                  runtimeClasspath.toList(Artifact.class),
                  Predicates.not(Predicates.in(excludedArtifacts.getSet().toSet())))));
    }
  }

  @Override
  public void checkProviderInstances(
      Sequence<?> providers, String what, ProviderApi providerType, StarlarkThread thread)
      throws EvalException {
    checkPrivateAccess(thread);
    if (providerType instanceof Provider) {
      for (int i = 0; i < providers.size(); i++) {
        Object elem = providers.get(i);
        if (!isInstanceOfProvider(elem, (Provider) providerType)) {
          throw Starlark.errorf(
              "at index %d of %s, got element of type %s, want %s",
              i, what, printableType(elem), ((Provider) providerType).getPrintableName());
        }
      }
    } else {
      throw Starlark.errorf("wanted Provider, got %s", Starlark.type(providerType));
    }
  }

  @VisibleForTesting
  static String printableType(Object elem) {
    if (elem instanceof StarlarkInfoWithSchema) {
      return ((StarlarkInfoWithSchema) elem).getProvider().getPrintableName();
    } else if (elem instanceof NativeInfo) {
      return ((NativeInfo) elem).getProvider().getPrintableName();
    }
    return Starlark.type(elem);
  }

  @Override
  public boolean isLegacyGoogleApiEnabled(StarlarkThread thread) throws EvalException {
    checkPrivateAccess(thread);
    return thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_GOOGLE_LEGACY_API);
  }

  @Override
  public boolean isDepsetForJavaOutputSourceJarsEnabled(StarlarkThread thread)
      throws EvalException {
    checkPrivateAccess(thread);
    return thread
        .getSemantics()
        .getBool(BuildLanguageOptions.INCOMPATIBLE_DEPSET_FOR_JAVA_OUTPUT_SOURCE_JARS);
  }

  @Override
  public boolean isJavaInfoMergeRuntimeModuleFlagsEnabled(StarlarkThread thread)
      throws EvalException {
    checkPrivateAccess(thread);
    return thread
        .getSemantics()
        .getBool(BuildLanguageOptions.INCOMPATIBLE_JAVA_INFO_MERGE_RUNTIME_MODULE_FLAGS);
  }

  @Override
  public JavaInfo wrapJavaInfo(Info javaInfo, StarlarkThread thread)
      throws EvalException, RuleErrorException {
    checkPrivateAccess(thread);
    return JavaInfo.PROVIDER.wrap(javaInfo);
  }

  @Override
  public Sequence<String> internJavacOpts(Object javacOpts) throws EvalException {
    ImmutableList<String> interned =
        JavaCompilationHelper.internJavacOpts(
            Sequence.cast(javacOpts, String.class, "javac_opts").getImmutableList());
    return StarlarkList.lazyImmutable(() -> interned);
  }

  @Override
  public boolean incompatibleDisableNonExecutableJavaBinary(StarlarkThread thread) {
    return thread
        .getSemantics()
        .getBool(BuildLanguageOptions.INCOMPATIBLE_DISABLE_NON_EXECUTABLE_JAVA_BINARY);
  }

  @Override
  public String getCurrentOsName() {
    return OS.getCurrent().getCanonicalName();
  }

  @Override
  public Sequence<?> expandJavaOpts(
      StarlarkRuleContext ctx, String attr, boolean tokenize, boolean execPaths)
      throws InterruptedException {
    Expander expander;
    if (execPaths) {
      expander = ctx.getRuleContext().getExpander().withExecLocations(ImmutableMap.of());
    } else {
      expander = ctx.getRuleContext().getExpander().withDataLocations();
    }
    if (tokenize) {
      return StarlarkList.immutableCopyOf(expander.tokenized(attr));
    } else {
      return StarlarkList.immutableCopyOf(expander.list(attr));
    }
  }

  static boolean isInstanceOfProvider(Object obj, Provider provider) {
    if (obj instanceof NativeInfo) {
      return ((NativeInfo) obj).getProvider().getKey().equals(provider.getKey());
    } else if (obj instanceof StarlarkInfoWithSchema) {
      return ((StarlarkInfoWithSchema) obj).getProvider().getKey().equals(provider.getKey());
    } else if (obj instanceof StarlarkInfoNoSchema) {
      return ((StarlarkInfoNoSchema) obj).getProvider().getKey().equals(provider.getKey());
    }
    return false;
  }
}
