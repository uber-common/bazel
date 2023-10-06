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

package com.google.devtools.build.lib.rules.java;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.flogger.LazyArgs.lazy;
import static com.google.devtools.build.lib.actions.ActionAnalysisMetadata.mergeMaps;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.BaseSpawn;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.actions.EmptyRunfilesSupplier;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.actions.PathStripper;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.starlark.Args;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.rules.java.JavaConfiguration.JavaClasspathMode;
import com.google.devtools.build.lib.rules.java.JavaPluginInfo.JavaPluginData;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.JavaCompile;
import com.google.devtools.build.lib.server.FailureDetails.JavaCompile.Code;
import com.google.devtools.build.lib.starlarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OnDemandString;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.proto.Deps;
import com.google.protobuf.ExtensionRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkList;

/** Action that represents a Java compilation. */
@ThreadCompatible
@Immutable
public final class JavaCompileAction extends AbstractAction implements CommandAction {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final ResourceSet LOCAL_RESOURCES =
      ResourceSet.createWithRamCpu(/* memoryMb= */ 750, /* cpuUsage= */ 1);
  private static final UUID GUID = UUID.fromString("e423747c-2827-49e6-b961-f6c08c10bb51");

  private static final ParamFileInfo PARAM_FILE_INFO =
      ParamFileInfo.builder(ParameterFile.ParameterFileType.UNQUOTED)
          .setCharset(ISO_8859_1)
          .setUseAlways(true)
          .build();

  enum CompilationType {
    JAVAC("Javac"),
    // 'javac turbine' has been replaced by just 'turbine', but the mnemonic is unchanged for
    // continuity in the blaze performance logs, and to distinguish direct classpath actions
    // which use the 'Turbine' mnemonic.
    // TODO(b/230333695): consider renaming to a more descriptive name
    TURBINE("JavacTurbine");

    final String mnemonic;

    CompilationType(String mnemonic) {
      this.mnemonic = mnemonic;
    }
  }

  private final NestedSet<Artifact> tools;
  private final CompilationType compilationType;
  private final ImmutableMap<String, String> executionInfo;
  private final CommandLine executableLine;
  private final CommandLine flagLine;
  private final BuildConfigurationValue configuration;
  private final OnDemandString progressMessage;

  private final NestedSet<Artifact> directJars;
  private final NestedSet<Artifact> mandatoryInputs;
  private final NestedSet<Artifact> transitiveInputs;
  private final NestedSet<Artifact> dependencyArtifacts;
  @Nullable private final Artifact outputDepsProto;
  private final JavaClasspathMode classpathMode;

  private final boolean compileWithUnusedDeps;

  @Nullable private final ExtraActionInfoSupplier extraActionInfoSupplier;

  /* The list of unused input discovered by the action. This list may change anytime action is executed,
   * it should ideally be stored in a separate (state) class.
   */
  @Nullable
  private Set<String> unusedArtifactsPath;

  @Nullable
  private Map<String, Map<String, String>> usedClassesMap;

  public JavaCompileAction(
      CompilationType compilationType,
      ActionOwner owner,
      NestedSet<Artifact> tools,
      OnDemandString progressMessage,
      NestedSet<Artifact> mandatoryInputs,
      NestedSet<Artifact> transitiveInputs,
      NestedSet<Artifact> directJars,
      ImmutableSet<Artifact> outputs,
      ImmutableMap<String, String> executionInfo,
      ExtraActionInfoSupplier extraActionInfoSupplier,
      CommandLine executableLine,
      CommandLine flagLine,
      BuildConfigurationValue configuration,
      NestedSet<Artifact> dependencyArtifacts,
      Artifact outputDepsProto,
      JavaClasspathMode classpathMode,
      boolean compileWithUnusedDeps) {
    super(owner, allInputs(mandatoryInputs, transitiveInputs, dependencyArtifacts), outputs);
    if (outputs.stream().anyMatch(Artifact::isTreeArtifact)) {
      throw new IllegalArgumentException(
          String.format(
              "Unexpected tree artifact output(s): [%s] in JavaCompileAction for %s",
              outputs.stream()
                  .filter(Artifact::isTreeArtifact)
                  .map(Artifact::getExecPathString)
                  .collect(joining(",")),
              owner.getLabel()));
    }
    this.tools = tools;
    this.compilationType = compilationType;
    // TODO(djasper): The only thing that is conveyed through the executionInfo is whether worker
    // mode is enabled or not. Investigate whether we can store just that.
    this.executionInfo =
        configuration.modifiedExecutionInfo(executionInfo, compilationType.mnemonic);
    this.executableLine = executableLine;
    this.flagLine = flagLine;
    this.configuration = configuration;
    this.progressMessage = progressMessage;
    this.extraActionInfoSupplier = extraActionInfoSupplier;
    this.directJars = directJars;
    this.mandatoryInputs = mandatoryInputs;
    this.transitiveInputs = transitiveInputs;
    this.dependencyArtifacts = dependencyArtifacts;
    this.outputDepsProto = outputDepsProto;
    this.classpathMode = classpathMode;
    checkState(
        outputDepsProto != null || classpathMode != JavaClasspathMode.BAZEL,
        "Cannot have null outputDepsProto with reduced class path mode BAZEL %s",
        describe());
    this.compileWithUnusedDeps = compileWithUnusedDeps;
  }

  /** Computes all of a {@link JavaCompileAction}'s inputs. */
  static NestedSet<Artifact> allInputs(
      NestedSet<Artifact> mandatoryInputs,
      NestedSet<Artifact> transitiveInputs,
      NestedSet<Artifact> dependencyArtifacts) {
    return NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(mandatoryInputs)
        .addTransitive(transitiveInputs)
        .addTransitive(dependencyArtifacts)
        .build();
  }

  @Override
  public NestedSet<Artifact> getTools() {
    return tools;
  }

  @Override
  public ActionEnvironment getEnvironment() {
    return configuration
        .getActionEnvironment()
        .withAdditionalFixedVariables(JavaCompileActionBuilder.UTF8_ENVIRONMENT);
  }

  @Override
  public String getMnemonic() {
    return compilationType.mnemonic;
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable Artifact.ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {
    fp.addUUID(GUID);
    fp.addInt(classpathMode.ordinal());
    executableLine.addToFingerprint(
        actionKeyContext, artifactExpander, fp, PathStripper.PathMapper.NOOP);
    flagLine.addToFingerprint(actionKeyContext, artifactExpander, fp, PathStripper.PathMapper.NOOP);
    // As the classpath is no longer part of commandLines implicitly, we need to explicitly add
    // the transitive inputs to the key here.
    actionKeyContext.addNestedSetToFingerprint(fp, transitiveInputs);
    // We don't need the toolManifests here, because they are a subset of the inputManifests by
    // definition and the output of an action shouldn't change whether something is considered a
    // tool or not.
    fp.addPaths(getRunfilesSupplier().getRunfilesDirs());
    ImmutableList<Artifact> runfilesManifests = getRunfilesSupplier().getManifests();
    fp.addInt(runfilesManifests.size());
    for (Artifact runfilesManifest : runfilesManifests) {
      fp.addPath(runfilesManifest.getExecPath());
    }
    getEnvironment().addTo(fp);
    fp.addStringMap(executionInfo);
    fp.addBoolean(stripOutputPaths());
  }

  /**
   * Compute a reduced classpath that is comprised of the header jars of all the direct dependencies
   * and the jars needed to build those (read from the produced .jdeps file). This duplicates the
   * logic from {@code
   * com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule#computeStrictClasspath}.
   */
  @VisibleForTesting
  ReducedClasspath getReducedClasspath(
      ActionExecutionContext actionExecutionContext, JavaCompileActionContext context)
      throws IOException {
    HashSet<String> direct = new HashSet<>();
    for (Artifact directJar : directJars.toList()) {
      direct.add(directJar.getExecPathString());
    }
    for (Artifact depArtifact : dependencyArtifacts.toList()) {
      for (Deps.Dependency dep :
          context.getDependencies(depArtifact, actionExecutionContext).getDependencyList()) {
        direct.add(dep.getPath());
      }
    }
    ImmutableList<Artifact> transitiveCollection = transitiveInputs.toList();
    ImmutableList<Artifact> reducedJars =
        ImmutableList.copyOf(
            Iterables.filter(
                transitiveCollection, input -> direct.contains(input.getExecPathString())));
    return new ReducedClasspath(reducedJars, transitiveCollection.size());
  }

  /**
   * Simpliar to {@link
   * com.google.devtools.build.lib.analysis.actions.SpawnAction.ExtraActionInfoSupplier} but
   * additionally includes the spawn arguments, which change between direct and fallback
   * invocations.
   */
  interface ExtraActionInfoSupplier {
    void extend(ExtraActionInfo.Builder builder, ImmutableList<String> arguments);
  }

  static class ReducedClasspath {
    final NestedSet<Artifact> reducedJars;
    final int reducedLength;
    final int fullLength;

    ReducedClasspath(ImmutableList<Artifact> reducedJars, int fullLength) {
      this.reducedJars = NestedSetBuilder.wrap(Order.STABLE_ORDER, reducedJars);
      this.reducedLength = reducedJars.size();
      this.fullLength = fullLength;
    }
  }

  /** Should we strip config prefixes from executor output paths for this compilation? */
  private boolean stripOutputPaths() {
    return JavaCompilationHelper.stripOutputPaths(
        allInputs(mandatoryInputs, transitiveInputs, dependencyArtifacts), configuration);
  }

  private JavaSpawn getReducedSpawn(
      ActionExecutionContext actionExecutionContext,
      ReducedClasspath reducedClasspath,
      boolean fallback)
      throws CommandLineExpansionException, InterruptedException {
    CustomCommandLine.Builder classpathLine = CustomCommandLine.builder();
    boolean stripOutputPaths = stripOutputPaths();
    if (stripOutputPaths) {
      classpathLine.stripOutputPaths(JavaCompilationHelper.outputBase(getPrimaryOutput()));
    }

    if (fallback) {
      classpathLine.addExecPaths("--classpath", transitiveInputs);
    } else {
      classpathLine.addExecPaths("--classpath", reducedClasspath.reducedJars);
    }
    // These flags instruct JavaBuilder that this is a compilation with a reduced classpath and
    // that it should report a special value back if a compilation error occurs that suggests
    // retrying with the full classpath.
    classpathLine.add("--reduce_classpath_mode", fallback ? "BAZEL_FALLBACK" : "BAZEL_REDUCED");
    classpathLine.add("--full_classpath_length", Integer.toString(reducedClasspath.fullLength));
    classpathLine.add(
        "--reduced_classpath_length", Integer.toString(reducedClasspath.reducedLength));

    CommandLines reducedCommandLine =
        CommandLines.builder()
            .addCommandLine(executableLine)
            .addCommandLine(flagLine, PARAM_FILE_INFO)
            .addCommandLine(classpathLine.build(), PARAM_FILE_INFO)
            .build();
    CommandLines.ExpandedCommandLines expandedCommandLines =
        reducedCommandLine.expand(
            actionExecutionContext.getArtifactExpander(),
            getPrimaryOutput().getExecPath(),
            PathStripper.createForAction(
                stripOutputPaths, null, getPrimaryOutput().getExecPath().subFragment(0, 1)),
            configuration.getCommandLineLimits());
    NestedSet<Artifact> inputs =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(mandatoryInputs)
            .addTransitive(fallback ? transitiveInputs : reducedClasspath.reducedJars)
            .build();
    return new JavaSpawn(
        expandedCommandLines,
        getEffectiveEnvironment(actionExecutionContext.getClientEnv()),
        getExecutionInfo(),
        inputs,
        /*onlyMandatoryOutput=*/ fallback ? null : outputDepsProto,
        stripOutputPaths);
  }

  private JavaSpawn getFullSpawn(ActionExecutionContext actionExecutionContext)
      throws CommandLineExpansionException, InterruptedException {
    boolean stripOutputPaths = stripOutputPaths();
    CommandLines.ExpandedCommandLines expandedCommandLines =
        getCommandLines()
            .expand(
                actionExecutionContext.getArtifactExpander(),
                getPrimaryOutput().getExecPath(),
                PathStripper.createForAction(
                    stripOutputPaths, null, getPrimaryOutput().getExecPath().subFragment(0, 1)),
                configuration.getCommandLineLimits());
    return new JavaSpawn(
        expandedCommandLines,
        getEffectiveEnvironment(actionExecutionContext.getClientEnv()),
        getExecutionInfo(),
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(mandatoryInputs)
            .addTransitive(transitiveInputs)
            // Full spawn mode means classPathMode != JavaClasspathMode.BAZEL, which means
            // JavaBuilder may read .jdeps files to perform classpath reduction on the executor. So
            // make sure these files are staged as inputs to the executor action.
            //
            // Contrast this with getReducedSpawn, which reduces the classpath in the Blaze process
            // *before* sending actions to the executor. In those cases we want to avoid staging
            // .jdeps files, which have config prefixes in output paths, which compromise caching
            // possible by stripping prefixes on the executor.
            .addTransitive(dependencyArtifacts)
            .build(),
        /*onlyMandatoryOutput=*/ null,
        stripOutputPaths);
  }

  @Override
  public ImmutableMap<String, String> getEffectiveEnvironment(Map<String, String> clientEnv) {
    ActionEnvironment env = getEnvironment();
    LinkedHashMap<String, String> effectiveEnvironment =
        Maps.newLinkedHashMapWithExpectedSize(env.estimatedSize());
    env.resolve(effectiveEnvironment, clientEnv);
    return ImmutableMap.copyOf(effectiveEnvironment);
  }

  private ActionExecutionException wrapIOException(IOException e, String message) {
    return ActionExecutionException.fromExecException(
        new EnvironmentalExecException(
            e, createFailureDetail(message, Code.REDUCED_CLASSPATH_FALLBACK_CLEANUP_FAILURE)),
        JavaCompileAction.this);
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    ReducedClasspath reducedClasspath;
    Spawn spawn;

    try {
      if (classpathMode == JavaClasspathMode.BAZEL) {
        JavaCompileActionContext context =
            actionExecutionContext.getContext(JavaCompileActionContext.class);
        try {
          reducedClasspath = getReducedClasspath(actionExecutionContext, context);
        } catch (IOException e) {
          throw createActionExecutionException(e, Code.REDUCED_CLASSPATH_FAILURE);
        }
        spawn = getReducedSpawn(actionExecutionContext, reducedClasspath, /* fallback= */ false);
      } else {
        reducedClasspath = null;
        spawn = getFullSpawn(actionExecutionContext);
      }
    } catch (CommandLineExpansionException e) {
      throw createActionExecutionException(e, Code.COMMAND_LINE_EXPANSION_FAILURE);
    }

    ImmutableList<SpawnResult> primaryResults;
    try {
      primaryResults =
          actionExecutionContext
              .getContext(SpawnStrategyResolver.class)
              .exec(spawn, actionExecutionContext);
    } catch (ExecException e) {
      throw ActionExecutionException.fromExecException(e, this);
    }

    if (reducedClasspath == null) {
      return ActionResult.create(primaryResults);
    }

    Deps.Dependencies dependencies =
        readFullOutputDeps(primaryResults, actionExecutionContext, stripOutputPaths());

    if (compilationType == CompilationType.TURBINE) {
      actionExecutionContext
          .getContext(JavaCompileActionContext.class)
          .insertDependencies(outputDepsProto, dependencies);
    }
    if (!dependencies.getRequiresReducedClasspathFallback()) {
      return ActionResult.create(primaryResults);
    }

    logger.atInfo().atMostEvery(1, SECONDS).log(
        "Failed reduced classpath compilation for %s", lazy(JavaCompileAction.this::prettyPrint));
    // Fall back to running with the full classpath. This requires first deleting potential
    // artifacts generated by the reduced action and clearing the metadata caches.
    try {
      deleteOutputs(
          actionExecutionContext.getExecRoot(),
          actionExecutionContext.getPathResolver(),
          /* bulkDeleter= */ null,
          // We don't create any tree artifacts anyway.
          /* cleanupArchivedArtifacts= */ false);
    } catch (IOException e) {
      throw wrapIOException(e, "Failed to delete reduced action outputs");
    }

    actionExecutionContext.getOutputMetadataStore().resetOutputs(getOutputs());

    try {
      actionExecutionContext.getFileOutErr().clearOut();
      actionExecutionContext.getFileOutErr().clearErr();
    } catch (IOException e) {
      throw wrapIOException(e, "Failed to clean reduced action stdout/stderr");
    }

    try {
      spawn = getReducedSpawn(actionExecutionContext, reducedClasspath, /* fallback= */ true);
    } catch (CommandLineExpansionException e) {
      Code detailedCode = Code.COMMAND_LINE_EXPANSION_FAILURE;
      throw createActionExecutionException(e, detailedCode);
    }

    ImmutableList<SpawnResult> fallbackResults;
    try {
      fallbackResults =
          actionExecutionContext
              .getContext(SpawnStrategyResolver.class)
              .exec(spawn, actionExecutionContext);
    } catch (ExecException e) {
      throw ActionExecutionException.fromExecException(e, this);
    }

    if (compilationType == CompilationType.TURBINE) {
      actionExecutionContext
          .getContext(JavaCompileActionContext.class)
          .insertDependencies(
              outputDepsProto,
              readFullOutputDeps(fallbackResults, actionExecutionContext, stripOutputPaths()));
    }
    return ActionResult.create(
        ImmutableList.copyOf(Iterables.concat(primaryResults, fallbackResults)));
  }

  @Override
  protected String getRawProgressMessage() {
    return progressMessage.toString();
  }

  static class ProgressMessage extends OnDemandString {

    private final String prefix;
    private final Artifact output;
    private final ImmutableSet<Artifact> sourceFiles;
    private final ImmutableList<Artifact> sourceJars;
    private final JavaPluginData plugins;

    ProgressMessage(
        String prefix,
        Artifact output,
        ImmutableSet<Artifact> sourceFiles,
        ImmutableList<Artifact> sourceJars,
        JavaPluginData plugins) {
      this.prefix = prefix;
      this.output = output;
      this.sourceFiles = sourceFiles;
      this.sourceJars = sourceJars;
      this.plugins = plugins;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(prefix);
      sb.append(' ');
      sb.append(output.prettyPrint());
      sb.append(" (");
      boolean first = true;
      first = appendCount(sb, first, sourceFiles.size(), "source file");
      appendCount(sb, first, sourceJars.size(), "source jar");
      sb.append(")");
      appendProcessorNames(sb, plugins.processorClasses());
      return sb.toString();
    }

    private static void appendProcessorNames(StringBuilder sb, NestedSet<String> processorClasses) {
      if (processorClasses.isEmpty()) {
        return;
      }
      List<String> shortNames = new ArrayList<>();
      for (String name : processorClasses.toList()) {
        // Annotation processor names are qualified class names. Omit the package part for the
        // progress message, e.g. `com.google.Foo` -> `Foo`.
        int idx = name.lastIndexOf('.');
        String shortName = idx != -1 ? name.substring(idx + 1) : name;
        shortNames.add(shortName);
      }
      sb.append(" and running annotation processors (");
      Joiner.on(", ").appendTo(sb, shortNames);
      sb.append(")");
    }

    /**
     * Append an input count to the progress message, e.g. "2 source jars". If an input count has
     * already been appended, prefix with ", ".
     */
    private static boolean appendCount(StringBuilder sb, boolean first, int count, String name) {
      if (count > 0) {
        if (!first) {
          sb.append(", ");
        } else {
          first = false;
        }
        sb.append(count).append(' ').append(name);
        if (count > 1) {
          sb.append('s');
        }
      }
      return first;
    }
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo(ActionKeyContext actionKeyContext)
      throws CommandLineExpansionException, InterruptedException {
    ExtraActionInfo.Builder builder = super.getExtraActionInfo(actionKeyContext);
    CommandLines commandLinesWithoutExecutable =
        CommandLines.builder()
            .addCommandLine(flagLine)
            .addCommandLine(getFullClasspathLine())
            .build();
    if (extraActionInfoSupplier != null) {
      extraActionInfoSupplier.extend(builder, commandLinesWithoutExecutable.allArguments());
    }
    return builder;
  }

  private final class JavaSpawn extends BaseSpawn {
    private final NestedSet<ActionInput> inputs;
    private final Artifact onlyMandatoryOutput;
    private final boolean stripOutputPaths;

    JavaSpawn(
        CommandLines.ExpandedCommandLines expandedCommandLines,
        Map<String, String> environment,
        Map<String, String> executionInfo,
        NestedSet<Artifact> inputs,
        @Nullable Artifact onlyMandatoryOutput,
        boolean stripOutputPaths) {
      super(
          ImmutableList.copyOf(expandedCommandLines.arguments()),
          environment,
          executionInfo,
          EmptyRunfilesSupplier.INSTANCE,
          JavaCompileAction.this,
          LOCAL_RESOURCES);
      this.onlyMandatoryOutput = onlyMandatoryOutput;
      this.inputs =
          NestedSetBuilder.<ActionInput>fromNestedSet(inputs)
              .addAll(expandedCommandLines.getParamFiles())
              .build();
      this.stripOutputPaths = stripOutputPaths;
    }

    @Override
    public NestedSet<? extends ActionInput> getInputFiles() {
      return inputs;
    }

    @Override
    public boolean isMandatoryOutput(ActionInput output) {
      return onlyMandatoryOutput == null || onlyMandatoryOutput.equals(output);
    }

    @Override
    public boolean stripOutputPaths() {
      return stripOutputPaths;
    }
  }

  @VisibleForTesting
  public CommandLines getCommandLines() {
    return CommandLines.builder()
        .addCommandLine(executableLine)
        .addCommandLine(flagLine, PARAM_FILE_INFO)
        .addCommandLine(getFullClasspathLine(), PARAM_FILE_INFO)
        .build();
  }

  private CommandLine getFullClasspathLine() {
    CustomCommandLine.Builder classpathLine =
        CustomCommandLine.builder().addExecPaths("--classpath", transitiveInputs);
    if (stripOutputPaths()) {
      classpathLine.stripOutputPaths(JavaCompilationHelper.outputBase(getPrimaryOutput()));
    }
    if (classpathMode == JavaClasspathMode.JAVABUILDER) {
      classpathLine.add("--reduce_classpath_mode", "JAVABUILDER_REDUCED");
      if (!dependencyArtifacts.isEmpty()) {
        classpathLine.addExecPaths("--deps_artifacts", dependencyArtifacts);
      }
    }
    return classpathLine.build();
  }

  @Override
  public Sequence<String> getStarlarkArgv() throws EvalException, InterruptedException {
    try {
      return StarlarkList.immutableCopyOf(getArguments());
    } catch (CommandLineExpansionException ex) {
      throw new EvalException(ex);
    }
  }

  /** Returns the out-of-band execution data for this action. */
  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    return mergeMaps(super.getExecutionInfo(), executionInfo);
  }

  @Override
  public ImmutableList<String> getArguments()
      throws CommandLineExpansionException, InterruptedException {
    return ImmutableList.copyOf(getCommandLines().allArguments());
  }

  @Override
  public Sequence<CommandLineArgsApi> getStarlarkArgs() {
    ImmutableList.Builder<CommandLineArgsApi> result = ImmutableList.builder();
    ImmutableSet<Artifact> directoryInputs =
        getInputs().toList().stream().filter(Artifact::isDirectory).collect(toImmutableSet());
    for (CommandLineAndParamFileInfo commandLine : getCommandLines().unpack()) {
      result.add(Args.forRegisteredAction(commandLine, directoryInputs));
    }
    return StarlarkList.immutableCopyOf(result.build());
  }

  @Override
  @VisibleForTesting
  public ImmutableMap<String, String> getIncompleteEnvironmentForTesting() {
    return getEnvironment().getFixedEnv();
  }

  @Nullable
  @Override
  public NestedSet<Artifact> getPossibleInputsForTesting() {
    return null;
  }

  public boolean discoversUnusedInputs() {
    return !this.compileWithUnusedDeps;
  }

  /* Discovers unused input to provide compilation avoidance. This method may be controversial
   * for 2 reasons. First, it re-uses the output of action previous execution, by loading action
   * generated output file (.jdeps). Second, it store this transient info data into the action
   * itself, for simplicity (it should ideally be stored in a state).
   */
  public void discoverUnusedInputs(ArtifactPathResolver pathResolver) {
    try {
      Path output = pathResolver.toPath(outputDepsProto);
      InputStream input = output.getInputStream();
      Deps.Dependencies deps = Deps.Dependencies.parseFrom(input);
      checkState(deps.getRuleLabel().equals(getOwner().getLabel().toString()));

      Set<String> usedPaths = deps.getDependencyList().stream()
              .filter(d -> d.getKind() == Deps.Dependency.Kind.EXPLICIT || d.getKind() == Deps.Dependency.Kind.IMPLICIT)
              .map(d -> d.getPath())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      this.unusedArtifactsPath = getInputs().toList().stream()
              .filter(JavaCompileAction::canArtifactBeUnused)
              .map(d -> d.getExecPathString())
              .filter(Predicate.not(usedPaths::contains))
              .collect(Collectors.toCollection(LinkedHashSet::new));
      this.usedClassesMap = deps.getDependencyList().stream()
              .filter(d -> d.getKind() == Deps.Dependency.Kind.EXPLICIT || d.getKind() == Deps.Dependency.Kind.IMPLICIT)
              .collect(Collectors.toMap(
                      d -> d.getPath(),
                      d -> d.getUsedClassesList().stream().collect(Collectors.toMap(
                              e -> e.getInnerPath(),
                              e -> e.getHash()))));
    } catch (Exception e) {
      System.err.println("Could not load .jdeps file, ex=" + e);
      this.unusedArtifactsPath = null;
      this.usedClassesMap = null;
    }
  }
  public boolean isUnusedInput(Artifact artifact) {
    if (this.unusedArtifactsPath != null) {
      String artifactExecPath = artifact.getExecPathString();
      if (this.unusedArtifactsPath.contains(artifactExecPath)) {
        return true;
      }
    }
    return false;
  }

  public boolean hasTrackedClasses(Artifact artifact) {
//    if (this.usedClassesMap != null) {
//      String artifactExecPath = artifact.getExecPathString();
//      if (this.usedClassesMap.containsKey(artifactExecPath)) {
//        return true;
//      }
//    }
    return false;
  }

  public Map<String, String> getTrackedClasses(Artifact artifact) {
    String artifactExecPath = artifact.getExecPathString();
    return this.usedClassesMap.get(artifactExecPath);
  }

  /* Returns whether artifact is eligible to be treated as unused. For java, our optimization is built
   * upon compiling against ABI jars, therefore we only support ijar/hjar/kotlin ABI.
   */
  private static boolean canArtifactBeUnused(Artifact artifact) {
    String artifactExecPath = artifact.getExecPathString();
    return artifactExecPath.endsWith("-ijar.jar") ||
            artifactExecPath.endsWith("-hjar.jar") ||
            artifactExecPath.endsWith("_kt.abi.jar");
  }

  /**
   * Locally rewrites a .jdeps file to replace missing config prefixes.
   *
   * <p>For example: {@code bazel-out/bin/foo/foo.jar -> bazel-out/x86-fastbuild/bin/foo/foo.jar}.
   *
   * <p>The executor may strip config prefixes from actions (i.e. remove {@code /x86-fastbuild/} or
   * equivalent from all input and output paths, command lines, and input file contents). This
   * provides better caching for actions that don't vary by --cpu or --compilation_mode. The full
   * paths must be re-created in Bazel's output tree to keep correct builds. For example, if an
   * otherwise cacheable action's input file *contents* differ across CPUs (like a CPU-dependent
   * generated source file), Bazel needs to maintain distinct paths for each instance. These paths
   * are chosen in Bazel's analysis phase, before it's possible to input contents. So all actions in
   * the output tree must conservatively keep full paths.
   *
   * <p>So this method's ultimate purpose is to translate the executor-optimized version of a .jdeps
   * to the original Bazel-safe version.
   *
   * <p>If the executor doesn't strip config prefixes (i.e. config stripping isn't turned on as a
   * feature), this is a trivial copy.
   *
   * <p>If config stripping is on, this method won't work with {@link
   * JavaConfiguration.JavaClasspathMode#JAVABUILDER}. That mode causes downstream Java compilations
   * to read this .jdeps on the executor. Since this method replaces config prefixes, the .jdeps
   * entries won't match the executor's stripped paths. This works best with {@link
   * JavaConfiguration.JavaClasspathMode#BAZEL}, where Bazel directly processes the .jdeps on the
   * local filesystem. Those paths match.
   *
   * @param spawnResult the executor action that created the possibly stripped .jdeps output
   * @param outputDepsProto path to the .jdeps output
   * @param actionInputs all inputs to the current action
   * @param actionExecutionContext the action execution context
   * @return the full deps proto (also written to disk to satisfy the action's declared output)
   */
  static Deps.Dependencies createFullOutputDeps(
      SpawnResult spawnResult,
      Artifact outputDepsProto,
      NestedSet<Artifact> actionInputs,
      ActionExecutionContext actionExecutionContext,
      boolean stripOutputPaths)
      throws IOException {

    Deps.Dependencies executorJdeps =
        readExecutorJdeps(spawnResult, outputDepsProto, actionExecutionContext);

    if (!stripOutputPaths) {
      return executorJdeps;
    }

    // For each of the action's generated inputs, map its stripped path to its full path.
    PathFragment outputRoot = outputDepsProto.getExecPath().subFragment(0, 1);
    Map<PathFragment, PathFragment> strippedToFullPaths = new HashMap<>();
    for (Artifact actionInput : actionInputs.toList()) {
      if (actionInput.isSourceArtifact()) {
        continue;
      }
      // Turns "bazel-out/x86-fastbuild/bin/foo" to "bazel-out/bin/foo".
      PathFragment strippedPath = outputRoot.getRelative(actionInput.getExecPath().subFragment(2));
      if (strippedToFullPaths.put(strippedPath, actionInput.getExecPath()) != null) {
        // If an entry already exists, that means different inputs reduce to the same stripped path.
        // That also means PathStripper would exempt this action from path stripping, so the
        // executor-produced .jdeps already includes full paths. No need to update it.
        return executorJdeps;
      }
    }

    // No paths to rewrite.
    if (executorJdeps.getDependencyCount() == 0) {
      return executorJdeps;
    }

    // Rewrite the .jdeps proto with full paths.
    Deps.Dependencies.Builder fullDepsBuilder = Deps.Dependencies.newBuilder(executorJdeps);
    for (Deps.Dependency.Builder dep : fullDepsBuilder.getDependencyBuilderList()) {
      PathFragment pathOnExecutor = PathFragment.create(dep.getPath());
      PathFragment fullPath = strippedToFullPaths.get(pathOnExecutor);
      if (fullPath == null && pathOnExecutor.subFragment(0, 1).equals(outputRoot)) {
        // The stripped path -> full path map failed, which means the paths weren't stripped. Fast-
        // return the original jdeps to save unnecessary CPU time.
        return executorJdeps;
      }
      dep.setPath(fullPath == null ? pathOnExecutor.getPathString() : fullPath.getPathString());
    }
    Deps.Dependencies fullOutputDeps = fullDepsBuilder.build();

    // Write the updated proto back to the filesystem. If the executor produced in-memory-only
    // outputs (see getInMemoryOutput above), the filesystem version doesn't exist and we can skip
    // this. Note that in-memory and filesystem outputs aren't necessarily mutually exclusive.
    Path fsPath = actionExecutionContext.getInputPath(outputDepsProto);
    if (fsPath.exists()) {
      // Make sure to clear the output store cache if it has an entry from before the rewrite.
      actionExecutionContext
          .getOutputMetadataStore()
          .resetOutputs(ImmutableList.of(outputDepsProto));
      fsPath.setWritable(true);
      try (var outputStream = fsPath.getOutputStream()) {
        fullOutputDeps.writeTo(outputStream);
      }
    }

    return fullOutputDeps;
  }

  private static Deps.Dependencies readExecutorJdeps(
      SpawnResult spawnResult,
      Artifact outputDepsProto,
      ActionExecutionContext actionExecutionContext)
      throws IOException {
    InputStream inMemoryOutput = spawnResult.getInMemoryOutput(outputDepsProto);
    try (InputStream inputStream =
        inMemoryOutput == null
            ? actionExecutionContext.getInputPath(outputDepsProto).getInputStream()
            : inMemoryOutput) {
      return Deps.Dependencies.parseFrom(inputStream, ExtensionRegistry.getEmptyRegistry());
    }
  }

  /** Reads the full {@code .jdeps} output from the given spawn results. */
  private Deps.Dependencies readFullOutputDeps(
      List<SpawnResult> results,
      ActionExecutionContext actionExecutionContext,
      boolean stripOutputPaths)
      throws ActionExecutionException {
    SpawnResult result = Iterables.getOnlyElement(results);
    try {
      return createFullOutputDeps(
          result, outputDepsProto, getInputs(), actionExecutionContext, stripOutputPaths);
    } catch (IOException e) {
      throw ActionExecutionException.fromExecException(
          new EnvironmentalExecException(
              e, createFailureDetail(".jdeps read IOException", Code.JDEPS_READ_IO_EXCEPTION)),
          this);
    }
  }

  private ActionExecutionException createActionExecutionException(Exception e, Code detailedCode) {
    DetailedExitCode detailedExitCode =
        DetailedExitCode.of(createFailureDetail(Strings.nullToEmpty(e.getMessage()), detailedCode));
    return new ActionExecutionException(e, this, /*catastrophe=*/ false, detailedExitCode);
  }

  private static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setJavaCompile(JavaCompile.newBuilder().setCode(detailedCode))
        .build();
  }
}
