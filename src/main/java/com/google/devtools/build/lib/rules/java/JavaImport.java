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

package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.packages.Type.STRING_LIST;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.Allowlist;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.java.JavaConfiguration.ImportDepsCheckingLevel;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.Nullable;

/** An implementation for the "java_import" rule. */
public class JavaImport implements RuleConfiguredTargetFactory {
  private final JavaSemantics semantics;

  protected JavaImport(JavaSemantics semantics) {
    this.semantics = semantics;
  }

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    ImmutableList<Artifact> srcJars = ImmutableList.of();
    ImmutableList<Artifact> jars = collectJars(ruleContext);
    Artifact srcJar = ruleContext.getPrerequisiteArtifact("srcjar");

    if (ruleContext.hasErrors()) {
      return null;
    }

    checkJarsAttributeEmpty(ruleContext);

    if (exportError(ruleContext)) {
      ruleContext.ruleError(
          "java_import.exports is no longer supported; use java_import.deps instead");
    }

    ImmutableList<TransitiveInfoCollection> targets =
        ImmutableList.<TransitiveInfoCollection>builder()
            .addAll(ruleContext.getPrerequisites("deps"))
            .addAll(ruleContext.getPrerequisites("exports"))
            .build();
    final JavaCommon common =
        new JavaCommon(
            ruleContext,
            semantics,
            /* sources= */ ImmutableList.<Artifact>of(),
            targets,
            targets,
            targets);
    semantics.checkRule(ruleContext, common);

    // No need for javac options - no compilation happening here.
    ImmutableBiMap.Builder<Artifact, Artifact> compilationToRuntimeJarMapBuilder =
        ImmutableBiMap.builder();
    ImmutableList<Artifact> interfaceJars =
        processWithIjarIfNeeded(jars, ruleContext, compilationToRuntimeJarMapBuilder);

    JavaCompilationArtifacts javaArtifacts = collectJavaArtifacts(jars, interfaceJars);
    common.setJavaCompilationArtifacts(javaArtifacts);

    boolean neverLink = JavaCommon.isNeverLink(ruleContext);
    JavaCompilationArgsProvider javaCompilationArgs =
        common.collectJavaCompilationArgs(neverLink, false);
    NestedSet<Artifact> transitiveJavaSourceJars =
        collectTransitiveJavaSourceJars(ruleContext, srcJar);
    if (srcJar != null) {
      srcJars = ImmutableList.of(srcJar);
    }

    Artifact jdepsArtifact = null;
    JavaToolchainProvider toolchain = JavaToolchainProvider.from(ruleContext);
    Artifact depsChecker = toolchain.depsChecker();
    if (Allowlist.hasAllowlist(ruleContext, "java_import_deps_checking")
        && !Allowlist.isAvailable(ruleContext, "java_import_deps_checking")
        && !ruleContext.attributes().get("tags", STRING_LIST).contains("incomplete-deps")
        && !jars.isEmpty()
        && depsChecker != null) {
      jdepsArtifact =
          ruleContext.getUniqueDirectoryArtifact(
              "_java_import", "jdeps.proto", ruleContext.getBinOrGenfilesDirectory());
      JavaCompilationArgsProvider provider = JavaCompilationArgsProvider.legacyFromTargets(targets);
      boolean pruneTransitiveDeps = ruleContext.getFragment(JavaConfiguration.class)
          .experimentalPruneTransitiveDeps();
      JavaTargetAttributes attributes =
          new JavaTargetAttributes.Builder(semantics)
              .merge(provider, pruneTransitiveDeps)
              .addDirectJars(provider.getDirectCompileTimeJars())
              .build();
      ImportDepsCheckActionBuilder.newBuilder()
          .importDepsChecker(depsChecker)
          .bootclasspath(toolchain.getBootclasspath().bootclasspath())
          .declareDeps(attributes.getDirectJars())
          .transitiveDeps(attributes.getCompileTimeClassPath())
          .checkJars(NestedSetBuilder.wrap(Order.STABLE_ORDER, jars))
          .importDepsCheckingLevel(ImportDepsCheckingLevel.ERROR)
          .jdepsOutputArtifact(jdepsArtifact)
          .ruleLabel(ruleContext.getLabel())
          .buildAndRegister(ruleContext);
    }

    // The "neverlink" attribute is transitive, so if it is enabled, we don't add any
    // runfiles from this target or its dependencies.
    Runfiles runfiles =
        neverLink
            ? Runfiles.EMPTY
            : new Runfiles.Builder(
                    ruleContext.getWorkspaceName(),
                    ruleContext.getConfiguration().legacyExternalRunfiles())
                // add the jars to the runfiles
                .addArtifacts(javaArtifacts.getRuntimeJars())
                .addTargets(
                    targets,
                    RunfilesProvider.DEFAULT_RUNFILES,
                    ruleContext.getConfiguration().alwaysIncludeFilesToBuildInData())
                .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES)
                .build();

    RuleConfiguredTargetBuilder ruleBuilder = new RuleConfiguredTargetBuilder(ruleContext);
    NestedSetBuilder<Artifact> filesBuilder = NestedSetBuilder.stableOrder();
    filesBuilder.addAll(jars);

    ImmutableBiMap<Artifact, Artifact> compilationToRuntimeJarMap =
        compilationToRuntimeJarMapBuilder.buildOrThrow();

    NestedSet<Artifact> filesToBuild = filesBuilder.build();

    JavaRuleOutputJarsProvider.Builder ruleOutputJarsProviderBuilder =
        JavaRuleOutputJarsProvider.builder();
    for (Artifact jar : jars) {
      ruleOutputJarsProviderBuilder.addJavaOutput(
          JavaOutput.builder()
              .setClassJar(jar)
              .setCompileJar(compilationToRuntimeJarMap.inverse().get(jar))
              .addSourceJar(srcJar)
              .build());
    }

    NestedSet<Artifact> proguardSpecs = new ProguardLibrary(ruleContext).collectProguardSpecs();

    JavaRuleOutputJarsProvider ruleOutputJarsProvider = ruleOutputJarsProviderBuilder.build();
    JavaSourceJarsProvider sourceJarsProvider =
        JavaSourceJarsProvider.create(transitiveJavaSourceJars, srcJars);
    JavaCompilationArgsProvider compilationArgsProvider = javaCompilationArgs;

    JavaInfo.Builder javaInfoBuilder = JavaInfo.Builder.create();
    common.addTransitiveInfoProviders(ruleBuilder, javaInfoBuilder, filesToBuild, null);

    JavaInfo javaInfo =
        javaInfoBuilder
            .addProvider(JavaCompilationArgsProvider.class, compilationArgsProvider)
            .addProvider(JavaRuleOutputJarsProvider.class, ruleOutputJarsProvider)
            .addProvider(JavaSourceJarsProvider.class, sourceJarsProvider)
            .setRuntimeJars(javaArtifacts.getRuntimeJars())
            .setJavaConstraints(JavaCommon.getConstraints(ruleContext))
            .setNeverlink(neverLink)
            .build();

    if (jdepsArtifact != null) {
      ruleBuilder.addOutputGroup(OutputGroupInfo.VALIDATION, jdepsArtifact);
    }

    return ruleBuilder
        .setFilesToBuild(filesToBuild)
        .addNativeDeclaredProvider(javaInfo)
        .add(RunfilesProvider.class, RunfilesProvider.simple(runfiles))
        .addNativeDeclaredProvider(new ProguardSpecProvider(proguardSpecs))
        .addOutputGroup(JavaSemantics.SOURCE_JARS_OUTPUT_GROUP, transitiveJavaSourceJars)
        .addOutputGroup(
            JavaSemantics.DIRECT_SOURCE_JARS_OUTPUT_GROUP,
            NestedSetBuilder.wrap(Order.STABLE_ORDER, sourceJarsProvider.getSourceJars()))
        .addOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL, proguardSpecs)
        .build();
  }

  private static boolean exportError(RuleContext ruleContext) {
    if (!ruleContext.attributes().isAttributeValueExplicitlySpecified("exports")) {
      return false;
    }
    if (ruleContext.getFragment(JavaConfiguration.class).disallowJavaImportExports()) {
      return true;
    }
    return Allowlist.hasAllowlist(ruleContext, "java_import_exports")
        && !Allowlist.isAvailable(ruleContext, "java_import_exports");
  }

  private static void checkJarsAttributeEmpty(RuleContext ruleContext) {
    if (ruleContext.getPrerequisites("jars").isEmpty()
        && ruleContext.getFragment(JavaConfiguration.class).disallowJavaImportEmptyJars()
        && Allowlist.hasAllowlist(ruleContext, "java_import_empty_jars")
        && !Allowlist.isAvailable(ruleContext, "java_import_empty_jars")) {
      ruleContext.ruleError(
          "empty java_import.jars is no longer supported " + ruleContext.getLabel());
    }
  }

  private NestedSet<Artifact> collectTransitiveJavaSourceJars(
      RuleContext ruleContext, Artifact srcJar) {
    NestedSetBuilder<Artifact> transitiveJavaSourceJarBuilder = NestedSetBuilder.stableOrder();
    if (srcJar != null) {
      transitiveJavaSourceJarBuilder.add(srcJar);
    }
    for (JavaSourceJarsProvider other :
        JavaInfo.getProvidersFromListOfTargets(
            JavaSourceJarsProvider.class, ruleContext.getPrerequisites("exports"))) {
      transitiveJavaSourceJarBuilder.addTransitive(other.getTransitiveSourceJars());
    }
    return transitiveJavaSourceJarBuilder.build();
  }

  private JavaCompilationArtifacts collectJavaArtifacts(
      ImmutableList<Artifact> jars, ImmutableList<Artifact> interfaceJars) {
    return new JavaCompilationArtifacts.Builder()
        .addRuntimeJars(jars)
        // interfaceJars Artifacts have proper owner labels
        .addInterfaceJarsWithFullJars(interfaceJars, jars)
        .build();
  }

  private ImmutableList<Artifact> collectJars(RuleContext ruleContext) {
    Set<Artifact> jars = new LinkedHashSet<>();
    for (TransitiveInfoCollection info : ruleContext.getPrerequisites("jars")) {
      if (JavaInfo.getProvider(JavaCompilationArgsProvider.class, info) != null) {
        ruleContext.attributeError("jars", "should not refer to Java rules");
      }
      for (Artifact jar : info.getProvider(FileProvider.class).getFilesToBuild().toList()) {
        if (!JavaSemantics.JAR.matches(jar.getFilename())) {
          ruleContext.attributeError("jars", jar.getFilename() + " is not a .jar file");
        } else {
          if (!jars.add(jar)) {
            ruleContext.attributeError("jars", jar.getFilename() + " is a duplicate");
          }
        }
      }
    }
    return ImmutableList.copyOf(jars);
  }

  private ImmutableList<Artifact> processWithIjarIfNeeded(
      ImmutableList<Artifact> jars,
      RuleContext ruleContext,
      ImmutableMap.Builder<Artifact, Artifact> compilationToRuntimeJarMap) {
    ImmutableList.Builder<Artifact> interfaceJarsBuilder = ImmutableList.builder();
    boolean useIjar = ruleContext.getFragment(JavaConfiguration.class).getUseIjars();
    for (Artifact jar : jars) {
      Artifact interfaceJar =
          useIjar
              ? JavaCompilationHelper.createIjarAction(
                  ruleContext,
                  JavaToolchainProvider.from(ruleContext),
                  jar,
                  ruleContext.getLabel(),
                  /* injectingRuleKind */ null,
                  true)
              : jar;
      interfaceJarsBuilder.add(interfaceJar);
      compilationToRuntimeJarMap.put(interfaceJar, jar);
    }
    return interfaceJarsBuilder.build();
  }
}
