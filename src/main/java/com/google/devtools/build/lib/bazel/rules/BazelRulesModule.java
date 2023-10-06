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

package com.google.devtools.build.lib.bazel.rules;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.bazel.rules.cpp.BazelCppRuleClasses;
import com.google.devtools.build.lib.bazel.rules.sh.BazelShRuleClasses;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.rules.cpp.CcSkyframeFdoSupportFunction;
import com.google.devtools.build.lib.rules.cpp.CcSkyframeFdoSupportValue;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.rules.java.JavaCompileActionContext;
import com.google.devtools.build.lib.rules.java.JavaOptions;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution.Code;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ResourceFileLoader;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import java.io.IOException;

/** Module implementing the rule set of Bazel. */
public final class BazelRulesModule extends BlazeModule {

  /**
   * This is where deprecated options used by both Bazel and Blaze but only needed for the build
   * command go to die.
   */
  @SuppressWarnings("deprecation") // These fields have no JavaDoc by design
  public static class BuildGraveyardOptions extends OptionsBase {

    @Option(
        name = "incompatible_disable_crosstool_file",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean disableCrosstool;

    @Option(
        name = "incompatible_disable_legacy_crosstool_fields",
        oldName = "experimental_disable_legacy_crosstool_fields",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean disableLegacyCrosstoolFields;

    @Option(
        name = "incompatible_require_feature_configuration_for_pic",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean requireFeatureConfigurationForPic;

    @Option(
        name = "incompatible_disable_depset_in_cc_user_flags",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean disableDepsetInUserFlags;

    @Option(
        name = "incompatible_dont_emit_static_libgcc",
        oldName = "experimental_dont_emit_static_libgcc",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean disableEmittingStaticLibgcc;

    @Option(
        name = "incompatible_linkopts_in_user_link_flags",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean enableLinkoptsInUserLinkFlags;

    @Option(
        name = "incompatible_disable_runtimes_filegroups",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean disableRuntimesFilegroups;

    @Option(
        name = "incompatible_disable_tools_defaults_package",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean incompatibleDisableInMemoryToolsDefaultsPackage;

    @Option(
        name = "output_symbol_counts",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
        metadataTags = {OptionMetadataTag.HIDDEN, OptionMetadataTag.DEPRECATED},
        help = "Deprecated no-op.")
    public boolean symbolCounts;

    @Option(
        name = "incompatible_disable_sysroot_from_configuration",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean disableSysrootFromConfiguration;

    @Option(
        name = "incompatible_provide_cc_toolchain_info_from_cc_toolchain_suite",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean provideCcToolchainInfoFromCcToolchainSuite;

    @Option(
        name = "incompatible_disable_cc_toolchain_label_from_crosstool_proto",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EAGERNESS_TO_EXIT},
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean disableCcToolchainFromCrosstool;

    @Option(
        name = "incompatible_disable_cc_configuration_make_variables",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {
          OptionMetadataTag.INCOMPATIBLE_CHANGE,
          OptionMetadataTag.DEPRECATED,
        },
        help = "Deprecated no-op.")
    public boolean disableMakeVariables;

    @Option(
        name = "incompatible_disable_legacy_flags_cc_toolchain_api",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE, OptionMetadataTag.DEPRECATED},
        help =
            "Flag for disabling the legacy cc_toolchain Starlark API for accessing legacy "
                + "CROSSTOOL fields.")
    public boolean disableLegacyFlagsCcToolchainApi;

    @Option(
        name = "incompatible_enable_legacy_cpp_toolchain_skylark_api",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE, OptionMetadataTag.DEPRECATED},
        help = "Obsolete, no effect.")
    public boolean enableLegacyToolchainStarlarkApi;

    @Option(
        name = "incompatible_disable_legacy_cpp_toolchain_skylark_api",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE, OptionMetadataTag.DEPRECATED},
        help = "Obsolete, no effect.")
    public boolean disableLegacyToolchainStarlarkApi;

    @Option(
        name = "incompatible_enable_profile_by_default",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "No-op.")
    public boolean enableProfileByDefault;

    @Option(
        name = "legacy_spawn_scheduler",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        defaultValue = "false",
        deprecationWarning =
            "The --legacy_spawn_scheduler flag is a no-op and will be removed soon.",
        help = "Was used to enable the old spawn scheduler. Now a no-op.")
    public boolean legacySpawnScheduler;

    @Option(
        name = "experimental_multi_threaded_digest",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "No-op")
    public boolean experimentalMultiThreadedDigest;

    @Option(
        name = "incompatible_override_toolchain_transition",
        defaultValue = "true",
        deprecationWarning = "This is now always set, please remove this flag.",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = OptionEffectTag.UNKNOWN,
        help = "Deprecated, this is no longer in use and should be removed.")
    public boolean overrideToolchainTransition;

    @Option(
        name = "experimental_dynamic_execution_cpu_limited",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {
          OptionEffectTag.EXECUTION,
          OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS
        },
        defaultValue = "false",
        help =
            "Deprecated no-op. Use --experimental_dynamic_local_load_factor instead, with the "
                + "values 0 for false and 1 for true, or with a value in between.")
    public boolean cpuLimited;

    @Option(
        name = "dynamic_worker_strategy",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {OptionMetadataTag.DEPRECATED},
        defaultValue = "",
        help = "Deprecated no-op. Please use --dynamic_local_strategy=worker,....")
    @Deprecated
    public String dynamicWorkerStrategy;

    @Option(
        name = "experimental_dynamic_skip_first_build",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        defaultValue = "false",
        help = "Deprecated no-op. Use --experimental_dynamic_local_load_factor instead.")
    @Deprecated
    public boolean skipFirstBuild;

    @Option(
            name = "use_workers_with_dexbuilder",
            defaultValue = "true",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = {OptionEffectTag.EXECUTION},
            help = "This option is deprecated and has no effect.")
    @Deprecated
    public boolean useWorkersWithDexbuilder;
  }

  /** This is where deprecated Bazel-specific options only used by the build command go to die. */
  @SuppressWarnings("deprecation") // These fields have no JavaDoc by design
  public static final class BazelBuildGraveyardOptions extends BuildGraveyardOptions {
    @Option(
        name = "incompatible_load_python_rules_from_bzl",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean loadPythonRulesFromBzl;

    @Option(
        name = "incompatible_load_proto_rules_from_bzl",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean loadProtoRulesFromBzl;

    @Option(
        name = "incompatible_load_java_rules_from_bzl",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean loadJavaRulesFromBzl;

    @Option(
        name = "make_variables_source",
        defaultValue = "configuration",
        metadataTags = {OptionMetadataTag.HIDDEN, OptionMetadataTag.DEPRECATED},
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN})
    public String makeVariableSource;

    @Option(
        name = "incompatible_cc_coverage",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {
          OptionEffectTag.UNKNOWN,
        },
        oldName = "experimental_cc_coverage",
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE, OptionMetadataTag.DEPRECATED},
        help = "Obsolete, no effect.")
    public boolean useGcovCoverage;

    @Deprecated
    @Option(
        name = "direct_run",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {OptionMetadataTag.DEPRECATED},
        help = "Deprecated no-op.")
    public boolean directRun;

    @Deprecated
    @Option(
        name = "glibc",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {OptionMetadataTag.DEPRECATED},
        help = "Deprecated no-op.")
    public String glibc;

    @Deprecated
    @Option(
        name = "experimental_shortened_obj_file_path",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.EXECUTION},
        defaultValue = "true",
        help = "This option is deprecated and has no effect.")
    public boolean shortenObjFilePath;

    @Option(
        name = "force_ignore_dash_static",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
        help = "noop")
    public boolean forceIgnoreDashStatic;

    @Option(
        name = "experimental_profile_action_counts",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "No-op.")
    public boolean enableActionCountProfile;

    @Option(
        name = "incompatible_remove_binary_profile",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "No-op.")
    public boolean removeBinaryProfile;

    @Option(
        name = "experimental_post_profile_started_event",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "No-op.")
    public boolean postProfileStartedEvent;

    @Option(
        name = "incompatible_dont_use_javasourceinfoprovider",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "No-op")
    public boolean dontUseJavaSourceInfoProvider;

    @Option(
        name = "experimental_local_memory_estimate",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = {OptionMetadataTag.DEPRECATED},
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op.")
    public boolean localMemoryEstimate;
  }

  /**
   * This is where deprecated options which need to be available for all commands go to die. If you
   * want to graveyard an all-command option specific to Blaze or Bazel, create a subclass.
   */
  public static final class AllCommandGraveyardOptions extends OptionsBase {
    // Historically passed to all Bazel commands by certain tools.
    @Option(
        name = "experimental_allow_top_level_aspects_parameters",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        metadataTags = {OptionMetadataTag.DEPRECATED, OptionMetadataTag.EXPERIMENTAL},
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op")
    public boolean experimentalAllowTopLevelAspectsParameters;

    @Option(
        name = "bes_best_effort",
        defaultValue = "false",
        deprecationWarning =
            "BES best effort upload has been removed. The flag has no more "
                + "functionality attached to it and will be removed in a future release.",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
        help = "No-op")
    public boolean besBestEffort;

    @Option(
        name = "distinct_host_configuration",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "No-op.")
    public boolean useDistinctHostConfiguration;

    @Option(
        name = "experimental_keep_config_nodes_on_analysis_discard",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op")
    public boolean keepConfigNodes;

    @Option(
        name = "incompatible_disable_managed_directories",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op")
    public boolean incompatibleDisableManagedDirectories;

    @Option(
        name = "experimental_use_event_based_build_completion_status",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = OptionMetadataTag.EXPERIMENTAL,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EXECUTION},
        help = "No-op")
    public boolean useEventBasedBuildCompletionStatus;

    // Moved here 2022/08/29
    @Option(
        name = "incompatible_disable_third_party_license_checking",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op")
    public boolean incompatibleDisableThirdPartyLicenseChecking;
  }

  @Override
  public void initializeRuleClasses(ConfiguredRuleClassProvider.Builder builder) {
    BazelRuleClassProvider.setup(builder);

    try {
      // Load auto-configuration files, it is made outside of the rule class provider so that it
      // will not be loaded for our Java tests.
      builder.addWorkspaceFileSuffix(
          ResourceFileLoader.loadResource(BazelCppRuleClasses.class, "cc_configure.WORKSPACE"));
      builder.addWorkspaceFileSuffix(
          ResourceFileLoader.loadResource(BazelRulesModule.class, "xcode_configure.WORKSPACE"));
      builder.addWorkspaceFileSuffix(
          ResourceFileLoader.loadResource(BazelShRuleClasses.class, "sh_configure.WORKSPACE"));

      // Load rules_license, which is needed for license attestations for many rules, including
      // things in @bazel_tools
      builder.addWorkspaceFileSuffix(
          ResourceFileLoader.loadResource(BazelRulesModule.class, "rules_license.WORKSPACE"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void beforeCommand(CommandEnvironment env) throws AbruptExitException {
    validateRemoteOutputsMode(env);
  }

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    builder.addSkyFunction(
        CcSkyframeFdoSupportValue.SKYFUNCTION, new CcSkyframeFdoSupportFunction(directories));
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return "build".equals(command.name())
        ? ImmutableList.of(BazelBuildGraveyardOptions.class, AllCommandGraveyardOptions.class)
        : ImmutableList.of(AllCommandGraveyardOptions.class);
  }

  @Override
  public void registerActionContexts(
      ModuleActionContextRegistry.Builder registryBuilder,
      CommandEnvironment env,
      BuildRequest buildRequest) {
    registryBuilder.register(JavaCompileActionContext.class, new JavaCompileActionContext());
  }

  private static void validateRemoteOutputsMode(CommandEnvironment env) throws AbruptExitException {
    RemoteOptions remoteOptions = env.getOptions().getOptions(RemoteOptions.class);
    if (remoteOptions == null) {
      return;
    }
    if (!remoteOptions.remoteOutputsMode.downloadAllOutputs()) {
      JavaOptions javaOptions = env.getOptions().getOptions(JavaOptions.class);
      if (javaOptions != null && !javaOptions.inmemoryJdepsFiles) {
        throw createRemoteExecutionExitException(
            "--experimental_remote_download_outputs=minimal requires"
                + " --experimental_inmemory_jdeps_files to be enabled",
            Code.REMOTE_DOWNLOAD_OUTPUTS_MINIMAL_WITHOUT_INMEMORY_JDEPS);
      }
      CppOptions cppOptions = env.getOptions().getOptions(CppOptions.class);
      if (cppOptions != null && !cppOptions.inmemoryDotdFiles) {
        throw createRemoteExecutionExitException(
            "--experimental_remote_download_outputs=minimal requires"
                + " --experimental_inmemory_dotd_files to be enabled",
            Code.REMOTE_DOWNLOAD_OUTPUTS_MINIMAL_WITHOUT_INMEMORY_DOTD);
      }
    }
  }

  private static AbruptExitException createRemoteExecutionExitException(
      String message, Code remoteExecutionCode) {
    return new AbruptExitException(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setRemoteExecution(RemoteExecution.newBuilder().setCode(remoteExecutionCode))
                .build()));
  }
}
