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
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.EmptyToNullLabelConverter;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.LabelConverter;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.PerLabelOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.DynamicMode;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.StripMode;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.List;
import javax.annotation.Nullable;

/** Command-line options for C++. */
public class CppOptions extends FragmentOptions {
  /** Converts a comma-separated list of compilation mode settings to a properly typed List. */
  public static class FissionOptionConverter extends Converter.Contextless<List<CompilationMode>> {
    @Override
    public List<CompilationMode> convert(String input) throws OptionsParsingException {
      ImmutableSet.Builder<CompilationMode> modes = ImmutableSet.builder();
      if (input.equals("yes")) { // Special case: enable all modes.
        modes.add(CompilationMode.values());
      } else if (!input.equals("no")) { // "no" is another special case that disables all modes.
        CompilationMode.Converter modeConverter = new CompilationMode.Converter();
        for (String mode : Splitter.on(',').split(input)) {
          modes.add(modeConverter.convert(mode, /*conversionContext=*/ null));
        }
      }
      return modes.build().asList();
    }

    @Override
    public String getTypeDescription() {
      return "a set of compilation modes";
    }
  }

  /** Converter for {@link DynamicMode} */
  public static class DynamicModeConverter extends EnumConverter<DynamicMode> {
    public DynamicModeConverter() {
      super(DynamicMode.class, "dynamic mode");
    }
  }

  /** Converter for the --strip option. */
  public static class StripModeConverter extends EnumConverter<StripMode> {
    public StripModeConverter() {
      super(StripMode.class, "strip mode");
    }
  }

  /**
   * Converts a String, which is a package label into a label that can be used for a LibcTop object.
   */
  public static class LibcTopLabelConverter implements Converter<Label> {
    private static final LabelConverter LABEL_CONVERTER = new LabelConverter();

    @Nullable
    @Override
    public Label convert(String input, Object conversionContext) throws OptionsParsingException {
      if (input.equals(TARGET_LIBC_TOP_NOT_YET_SET)) {
        return Label.createUnvalidated(
            PackageIdentifier.EMPTY_PACKAGE_ID, TARGET_LIBC_TOP_NOT_YET_SET);
      } else if (input.equals("default")) {
        // This is needed for defining config_setting() values, the syntactic form
        // of which must be a String, to match absence of a --grte_top option.
        // "--grte_top=default" works on the command-line too,
        // but that's an inconsequential side-effect, not the intended purpose.
        return null;
      } else if (!input.startsWith("//")) {
        throw new OptionsParsingException("Not a label");
      }
      return Label.createUnvalidated(
          LABEL_CONVERTER.convert(input, conversionContext).getPackageIdentifier(), "everything");
    }

    @Override
    public String getTypeDescription() {
      return "a label";
    }
  }

  @Option(
      name = "crosstool_top",
      defaultValue = "@bazel_tools//tools/cpp:toolchain",
      converter = LabelConverter.class,
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.CHANGES_INPUTS,
        OptionEffectTag.AFFECTS_OUTPUTS
      },
      help = "The label of the crosstool package to be used for compiling C++ code.")
  public Label crosstoolTop;

  @Option(
      name = "compiler",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EXECUTION},
      help = "The C++ compiler to use for compiling the target.")
  public String cppCompiler;

  @Option(
      name = "host_compiler",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EXECUTION},
      help =
          "The C++ compiler to use for host compilation. It is ignored if --host_crosstool_top "
              + "is not set.")
  public String hostCppCompiler;

  // This is different from --platform_suffix in that that one is designed to facilitate the
  // migration to toolchains and this one is designed to eliminate the C++ toolchain identifier
  // from the output directory path.
  @Option(
      name = "cc_output_directory_tag",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      metadataTags = {OptionMetadataTag.EXPLICIT_IN_OUTPUT_PATH},
      help = "Specifies a suffix to be added to the configuration directory.")
  public String outputDirectoryTag;

  @Option(
      name = "minimum_os_version",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "The minimum OS version which your compilation targets.")
  @Nullable
  public String minimumOsVersion;

  // O intrepid reaper of unused options: Be warned that the [no]start_end_lib
  // option, however tempting to remove, has a use case. Look in our telemetry data.
  @Option(
      name = "start_end_lib",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.CHANGES_INPUTS,
        OptionEffectTag.AFFECTS_OUTPUTS
      },
      metadataTags = {OptionMetadataTag.HIDDEN},
      help = "Use the --start-lib/--end-lib ld options if supported by the toolchain.")
  public boolean useStartEndLib;

  @Option(
      name = "interface_shared_objects",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.AFFECTS_OUTPUTS,
        OptionEffectTag.AFFECTS_OUTPUTS
      },
      help =
          "Use interface shared objects if supported by the toolchain. "
              + "All ELF toolchains currently support this setting.")
  public boolean useInterfaceSharedObjects;

  @Option(
      name = "fission",
      defaultValue = "no",
      converter = FissionOptionConverter.class,
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.ACTION_COMMAND_LINES,
        OptionEffectTag.AFFECTS_OUTPUTS
      },
      help =
          "Specifies which compilation modes use fission for C++ compilations and links.  May be"
              + " any combination of {'fastbuild', 'dbg', 'opt'} or the special values 'yes'  to"
              + " enable all modes and 'no' to disable all modes.")
  public List<CompilationMode> fissionModes;

  @Option(
      name = "build_test_dwp",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If enabled, when building C++ tests statically and with fission the .dwp file "
              + " for the test binary will be automatically built as well.")
  public boolean buildTestDwp;

  @Option(
      name = "dynamic_mode",
      defaultValue = "default",
      converter = DynamicModeConverter.class,
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Determines whether C++ binaries will be linked dynamically.  'default' means "
              + "Bazel will choose whether to link dynamically.  'fully' means all libraries "
              + "will be linked dynamically. 'off' means that all libraries will be linked "
              + "in mostly static mode.")
  public DynamicMode dynamicMode;

  @Option(
      name = "incompatible_avoid_conflict_dlls",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If enabled, all C++ dynamic linked libraries (DLLs) generated by cc_library on Windows "
              + "will be renamed to name_{hash}.dll where hash is calculated based on "
              + "the RepositoryName and the DLL's package path. This option is useful "
              + "when you have one package which depends on severals cc_library with the same name "
              + "(e.g //foo/bar1:utils and //foo/bar2:utils).")
  public boolean renameDLL;

  @Option(
      name = "force_pic",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If enabled, all C++ compilations produce position-independent code (\"-fPIC\"),"
              + " links prefer PIC pre-built libraries over non-PIC libraries, and links produce"
              + " position-independent executables (\"-pie\").")
  public boolean forcePic;

  @Option(
      name = "process_headers_in_dependencies",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "When building a target //a:a, process headers in all targets that //a:a depends "
              + "on (if header processing is enabled for the toolchain).")
  public boolean processHeadersInDependencies;

  @Option(
      name = "copt",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional options to pass to gcc.")
  public List<String> coptList;

  @Option(
      name = "cxxopt",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional option to pass to gcc when compiling C++ source files.")
  public List<String> cxxoptList;

  @Option(
      name = "conlyopt",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional option to pass to gcc when compiling C source files.")
  public List<String> conlyoptList;

  @Option(
      name = "objccopt",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
      help = "Additional options to pass to gcc when compiling Objective-C/C++ source files.")
  public List<String> objcoptList;

  @Option(
      name = "linkopt",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional option to pass to gcc when linking.")
  public List<String> linkoptList;

  @Option(
      name = "ltoindexopt",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional option to pass to the LTO indexing step (under --features=thin_lto).")
  public List<String> ltoindexoptList;

  @Option(
      name = "ltobackendopt",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional option to pass to the LTO backend step (under --features=thin_lto).")
  public List<String> ltobackendoptList;

  @Option(
      name = "stripopt",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional options to pass to strip when generating a '<name>.stripped' binary.")
  public List<String> stripoptList;

  @Option(
      name = "custom_malloc",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.CHANGES_INPUTS, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Specifies a custom malloc implementation. This setting overrides malloc "
              + "attributes in build rules.",
      converter = LabelConverter.class)
  public Label customMalloc;

  @Option(
      name = "legacy_whole_archive",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      metadataTags = {OptionMetadataTag.DEPRECATED},
      help =
          "Deprecated, superseded by --incompatible_remove_legacy_whole_archive "
              + "(see https://github.com/bazelbuild/bazel/issues/7362 for details). "
              + "When on, use --whole-archive for cc_binary rules that have "
              + "linkshared=True and either linkstatic=True or '-static' in linkopts. "
              + "This is for backwards compatibility only. "
              + "A better alternative is to use alwayslink=1 where required.")
  public boolean legacyWholeArchive;

  @Option(
      name = "strip",
      defaultValue = "sometimes",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Specifies whether to strip binaries and shared libraries "
              + " (using \"-Wl,--strip-debug\").  The default value of 'sometimes'"
              + " means strip iff --compilation_mode=fastbuild.",
      converter = StripModeConverter.class)
  public StripMode stripBinaries;

  @Option(
      name = "fdo_instrument",
      defaultValue = "null",
      implicitRequirements = {"--copt=-Wno-error"},
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Generate binaries with FDO instrumentation. With Clang/LLVM compiler, it also accepts"
              + " the directory name under which the raw profile file(s) will be dumped at"
              + " runtime.")
  public String fdoInstrumentForBuild;

  @Option(
      name = "fdo_optimize",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Use FDO profile information to optimize compilation. Specify the name "
              + "of a zip file containing a .gcda file tree, an afdo file containing "
              + "an auto profile, or an LLVM profile file. This flag also accepts files "
              + "specified as labels (e.g. `//foo/bar:file.afdo` - you may need to add "
              + "an `exports_files` directive to the corresponding package) and labels "
              + "pointing to `fdo_profile` targets. This flag will be superseded by the "
              + "`fdo_profile` rule.")
  public String fdoOptimizeForBuild;

  /**
   * Returns the --fdo_optimize value if FDO is specified and active for this configuration, the
   * default value otherwise.
   */
  public String getFdoOptimize() {
    return fdoOptimizeForBuild;
  }

  @Option(
      name = "cs_fdo_instrument",
      defaultValue = "null",
      implicitRequirements = {"--copt=-Wno-error"},
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Generate binaries with context sensitive FDO instrumentation. With Clang/LLVM compiler, "
              + "it also accepts the directory name under which the raw profile file(s) will be "
              + "dumped at runtime.")
  public String csFdoInstrumentForBuild;

  @Option(
      name = "cs_fdo_absolute_path",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Use CSFDO profile information to optimize compilation. Specify the absolute path name "
              + "of the zip file containing the profile file, a raw or an indexed "
              + "LLVM profile file.")
  public String csFdoAbsolutePathForBuild;

  @Option(
      name = "xbinary_fdo",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      converter = EmptyToNullLabelConverter.class,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Use XbinaryFDO profile information to optimize compilation. Specify the name "
              + "of default cross binary profile. When the option is used together with "
              + "--fdo_instrument/--fdo_optimize/--fdo_profile, those options will always "
              + "prevail as if xbinary_fdo is never specified. ")
  public Label xfdoProfileLabel;

  @Option(
      name = "fdo_prefetch_hints",
      defaultValue = "null",
      converter = LabelConverter.class,
      category = "flags",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Use cache prefetch hints.")
  public Label fdoPrefetchHintsLabel;

  /** Returns the --fdo_prefetch_hints value. */
  public Label getFdoPrefetchHintsLabel() {
    return fdoPrefetchHintsLabel;
  }

  @Option(
      name = "fdo_profile",
      defaultValue = "null",
      category = "flags",
      converter = LabelConverter.class,
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "The fdo_profile representing the profile to be used for optimization.")
  public Label fdoProfileLabel;

  @Option(
      name = "cs_fdo_profile",
      defaultValue = "null",
      category = "flags",
      converter = LabelConverter.class,
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "The cs_fdo_profile representing the context sensitive profile to be used for"
              + " optimization.")
  public Label csFdoProfileLabel;

  @Option(
      name = "enable_fdo_profile_absolute_path",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "If set, use of fdo_absolute_profile_path will raise an error.")
  public boolean enableFdoProfileAbsolutePath;

  @Option(
      name = "propeller_optimize_absolute_cc_profile",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Absolute path name of cc_profile file for Propeller Optimized builds.")
  public String propellerOptimizeAbsoluteCCProfile;

  @Option(
      name = "propeller_optimize_absolute_ld_profile",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Absolute path name of ld_profile file for Propeller Optimized builds.")
  public String propellerOptimizeAbsoluteLdProfile;

  @Option(
      name = "propeller_optimize",
      defaultValue = "null",
      converter = LabelConverter.class,
      category = "flags",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Use Propeller profile information to optimize the build target."
              + "A propeller profile must consist of at least one of two files, a cc profile "
              + "and a ld profile.  This flag accepts a build label which must refer to "
              + "the propeller profile input files. For example, the BUILD file that "
              + "defines the label, in a/b/BUILD:"
              + "propeller_optimize("
              + "    name = \"propeller_profile\","
              + "    cc_profile = \"propeller_cc_profile.txt\","
              + "    ld_profile = \"propeller_ld_profile.txt\","
              + ")"
              + "An exports_files directive may have to be added to the corresponding package "
              + "to make these files visible to Bazel. The option must be used as: "
              + "--propeller_optimize=//a/b:propeller_profile")
  public Label propellerOptimizeLabel;

  public Label getPropellerOptimizeLabel() {
    return propellerOptimizeLabel;
  }

  @Option(
      name = "save_temps",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If set, temporary outputs from gcc will be saved.  "
              + "These include .s files (assembler code), .i files (preprocessed C) and "
              + ".ii files (preprocessed C++).")
  public boolean saveTemps;

  @Option(
      name = "per_file_copt",
      allowMultiple = true,
      converter = PerLabelOptions.PerLabelOptionsConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Additional options to selectively pass to gcc when compiling certain files. "
              + "This option can be passed multiple times. "
              + "Syntax: regex_filter@option_1,option_2,...,option_n. Where regex_filter stands "
              + "for a list of include and exclude regular expression patterns (Also see "
              + "--instrumentation_filter). option_1 to option_n stand for "
              + "arbitrary command line options. If an option contains a comma it has to be "
              + "quoted with a backslash. Options can contain @. Only the first @ is used to "
              + "split the string. Example: "
              + "--per_file_copt=//foo/.*\\.cc,-//foo/bar\\.cc@-O0 adds the -O0 "
              + "command line option to the gcc command line of all cc files in //foo/ "
              + "except bar.cc.")
  public List<PerLabelOptions> perFileCopts;

  @Option(
      name = "per_file_ltobackendopt",
      allowMultiple = true,
      converter = PerLabelOptions.PerLabelOptionsConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Additional options to selectively pass to LTO backend (under --features=thin_lto) when "
              + "compiling certain backend objects. This option can be passed multiple times. "
              + "Syntax: regex_filter@option_1,option_2,...,option_n. Where regex_filter stands "
              + "for a list of include and exclude regular expression patterns. "
              + "option_1 to option_n stand for arbitrary command line options. "
              + "If an option contains a comma it has to be quoted with a backslash. "
              + "Options can contain @. Only the first @ is used to split the string. Example: "
              + "--per_file_ltobackendopt=//foo/.*\\.o,-//foo/bar\\.o@-O0 adds the -O0 "
              + "command line option to the LTO backend command line of all o files in //foo/ "
              + "except bar.o.")
  public List<PerLabelOptions> perFileLtoBackendOpts;

  /**
   * The value of "--crosstool_top" to use for building tools.
   *
   * <p>We want to make sure this stays bound to the top-level configuration when not explicitly set
   * (as opposed to a configuration that comes out of a transition). Otherwise we risk using the
   * wrong crosstool (i.e., trying to build tools with an Android-specific crosstool).
   *
   * <p>To accomplish this, we initialize this to null and, if it isn't explicitly set, use {@link
   * #getNormalized} to rewrite it to {@link #crosstoolTop}. Blaze always evaluates top-level
   * configurations first, so they'll trigger this. But no followup transitions can.
   */
  @Option(
      name = "host_crosstool_top",
      defaultValue = "null",
      converter = LabelConverter.class,
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.CHANGES_INPUTS,
        OptionEffectTag.AFFECTS_OUTPUTS
      },
      help =
          "By default, the --crosstool_top and --compiler options are also used "
              + "for the host configuration. If this flag is provided, Bazel uses the default libc "
              + "and compiler for the given crosstool_top.")
  public Label hostCrosstoolTop;

  @Option(
      name = "host_copt",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional options to pass to gcc for host tools.")
  public List<String> hostCoptList;

  @Option(
      name = "host_cxxopt",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional options to pass to gcc for host tools.")
  public List<String> hostCxxoptList;

  @Option(
      name = "host_conlyopt",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional option to pass to gcc when compiling C source files for host tools.")
  public List<String> hostConlyoptList;

  @Option(
      name = "host_per_file_copt",
      allowMultiple = true,
      converter = PerLabelOptions.PerLabelOptionsConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Additional options to selectively pass to the C/C++ compiler when "
              + "compiling certain files in the host or exec configurations. "
              + "This option can be passed multiple times. "
              + "Syntax: regex_filter@option_1,option_2,...,option_n. Where regex_filter stands "
              + "for a list of include and exclude regular expression patterns (Also see "
              + "--instrumentation_filter). option_1 to option_n stand for "
              + "arbitrary command line options. If an option contains a comma it has to be "
              + "quoted with a backslash. Options can contain @. Only the first @ is used to "
              + "split the string. Example: "
              + "--host_per_file_copt=//foo/.*\\.cc,-//foo/bar\\.cc@-O0 adds the -O0 "
              + "command line option to the gcc command line of all cc files in //foo/ "
              + "except bar.cc.")
  public List<PerLabelOptions> hostPerFileCoptsList;

  @Option(
      name = "host_linkopt",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Additional option to pass to gcc when linking host tools.")
  public List<String> hostLinkoptList;

  @Option(
      name = "grte_top",
      defaultValue = "null", // The default value is chosen by the toolchain.
      converter = LibcTopLabelConverter.class,
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "A label to a checked-in libc library. The default value is selected by the crosstool "
              + "toolchain, and you almost never need to override it.")
  public Label libcTopLabel;

  @Option(
      name = "host_grte_top",
      defaultValue = "null", // The default value is chosen by the toolchain.
      converter = LibcTopLabelConverter.class,
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If specified, this setting overrides the libc top-level directory (--grte_top) "
              + "for the host configuration.")
  public Label hostLibcTopLabel;

  /** See {@link #targetLibcTopLabel} documentation. * */
  private static final String TARGET_LIBC_TOP_NOT_YET_SET = "TARGET LIBC TOP NOT YET SET";

  /**
   * This is a fake option used to pass data from target configuration to the host configuration.
   * It's a horrible hack that will be removed once toolchain-transitions are implemented.
   *
   * <p>We want to make sure this stays bound to the top-level configuration (as opposed to a
   * configuration that comes out of a transition). Otherwise we risk multiple exec configurations
   * writing to the same path and creating C++ action conflicts (C++ actions can not be shared
   * across configurations: see {@link ActionAnalysisMetadata#isShareable}). {@link
   * com.google.devtools.build.lib.rules.android.AndroidSplitTransition}, for example, changes
   * {@link #libcTopLabel} to an Android-specific variant.
   *
   * <p>To accomplish this, we initialize this to a special value that means "I haven't been set
   * yet" and use {@link #getNormalized} to rewrite it to {@link #libcTopLabel} <b>only</b> from
   * that default. Blaze always evaluates top-level configurations first, so they'll trigger this.
   * But no followup transitions can.
   *
   * <p>It's not sufficient to use null for the default. That wouldn't handle the case of the
   * top-level {@link #libcTopLabel} being null and {@link
   * com.google.devtools.build.lib.rules.android.AndroidConfiguration.Options#androidLibcTopLabel}
   * being non-null.
   */
  // TODO(b/129045294): Remove once toolchain-transitions are implemented.
  @Option(
      name = "target libcTop label",
      defaultValue = TARGET_LIBC_TOP_NOT_YET_SET,
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      converter = LibcTopLabelConverter.class,
      effectTags = {
        OptionEffectTag.LOSES_INCREMENTAL_STATE,
        OptionEffectTag.AFFECTS_OUTPUTS,
        OptionEffectTag.LOADING_AND_ANALYSIS
      },
      metadataTags = {OptionMetadataTag.INTERNAL})
  public Label targetLibcTopLabel;

  @Option(
      name = "experimental_inmemory_dotd_files",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.EXECUTION,
        OptionEffectTag.AFFECTS_OUTPUTS
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If enabled, C++ .d files will be passed through in memory directly from the remote "
              + "build nodes instead of being written to disk.")
  public boolean inmemoryDotdFiles;

  @Option(
      name = "experimental_parse_headers_skipped_if_corresponding_srcs_found",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If enabled, the parse_headers feature does not create a separate header compile action "
              + "if a source with the same basename is found in the same target.")
  public boolean parseHeadersSkippedIfCorrespondingSrcsFound;

  @Option(
      name = "experimental_omitfp",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.AFFECTS_OUTPUTS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If true, use libunwind for stack unwinding, and compile with "
              + "-fomit-frame-pointer and -fasynchronous-unwind-tables.")
  public boolean experimentalOmitfp;

  @Option(
      name = "share_native_deps",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If true, native libraries that contain identical functionality "
              + "will be shared among different targets")
  public boolean shareNativeDeps;

  @Option(
      name = "strict_system_includes",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EAGERNESS_TO_EXIT},
      help =
          "If true, headers found through system include paths (-isystem) are also required to be "
              + "declared.")
  public boolean strictSystemIncludes;

  @Option(
      name = "experimental_use_llvm_covmap",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {
        OptionEffectTag.CHANGES_INPUTS,
        OptionEffectTag.AFFECTS_OUTPUTS,
        OptionEffectTag.LOADING_AND_ANALYSIS
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If specified, Bazel will generate llvm-cov coverage map information rather than "
              + "gcov when collect_code_coverage is enabled.")
  public boolean useLLVMCoverageMapFormat;

  @Option(
      name = "incompatible_dont_enable_host_nonhost_crosstool_features",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If true, Bazel will not enable 'host' and 'nonhost' features in the c++ toolchain "
              + "(see https://github.com/bazelbuild/bazel/issues/7407 for more information).")
  public boolean dontEnableHostNonhost;

  @Option(
      name = "incompatible_make_thinlto_command_lines_standalone",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If true, Bazel will not reuse C++ link action command lines for lto indexing command "
              + "lines (see https://github.com/bazelbuild/bazel/issues/6791 for more information).")
  public boolean useStandaloneLtoIndexingCommandLines;

  @Option(
      name = "incompatible_require_ctx_in_configure_features",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If true, Bazel will require 'ctx' parameter in to cc_common.configure_features "
              + "(see https://github.com/bazelbuild/bazel/issues/7793 for more information).")
  public boolean requireCtxInConfigureFeatures;

  @Option(
      name = "incompatible_validate_top_level_header_inclusions",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If true, Bazel will also validate top level directory header inclusions "
              + "(see https://github.com/bazelbuild/bazel/issues/10047 for more information).")
  public boolean validateTopLevelHeaderInclusions;

  @Option(
      name = "incompatible_remove_legacy_whole_archive",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If true, Bazel will not link library dependencies as whole archive by default "
              + "(see https://github.com/bazelbuild/bazel/issues/7362 for migration instructions).")
  public boolean removeLegacyWholeArchive;

  @Option(
      name = "incompatible_remove_cpu_and_compiler_attributes_from_cc_toolchain",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If true, Bazel will complain when cc_toolchain.cpu and cc_toolchain.compiler attributes "
              + "are set "
              + "(see https://github.com/bazelbuild/bazel/issues/7075 for migration instructions).")
  public boolean removeCpuCompilerCcToolchainAttributes;

  @Option(
      name = "incompatible_disable_expand_if_all_available_in_flag_set",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If true, Bazel will not allow specifying expand_if_all_available in flag_sets"
              + "(see https://github.com/bazelbuild/bazel/issues/7008 for migration instructions).")
  public boolean disableExpandIfAllAvailableInFlagSet;

  @Option(
      name = "experimental_includes_attribute_subpackage_traversal",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.EXECUTION},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If a cc target has loose headers checking, disabled layering check and an "
              + "includes attribute set, it is allowed to include anything under its folder, even "
              + "across subpackage boundaries.")
  public boolean experimentalIncludesAttributeSubpackageTraversal;

  @Option(
      name = "incompatible_disable_legacy_cc_provider",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If true, the legacy provider accessible by 'dep.cc.' is removed. See #7036.")
  // TODO(b/122328491): Document migration steps. See #7036.
  public boolean disableLegacyCcProvider;

  @Option(
      name = "incompatible_enable_cc_toolchain_resolution",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If true, cc rules use toolchain resolution to find the cc_toolchain.")
  public boolean enableCcToolchainResolution;

  @Option(
      name = "experimental_save_feature_state",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help = "Save the state of enabled and requested feautres as an output of compilation.")
  public boolean saveFeatureState;

  @Option(
      name = "incompatible_use_specific_tool_files",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "Use cc toolchain's compiler_files, as_files, and ar_files as inputs to appropriate "
              + "actions. See https://github.com/bazelbuild/bazel/issues/8531")
  public boolean useSpecificToolFiles;

  @Option(
      name = "incompatible_disable_nocopts",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "When enabled, it removes nocopts attribute from C++ rules. See"
              + " https://github.com/bazelbuild/bazel/issues/8706 for details.")
  public boolean disableNoCopts;

  @Option(
      name = "incompatible_enable_cc_test_feature",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "When enabled, it switches Crosstool to use feature 'is_cc_test' rather than"
              + " the link-time build variable of the same name.")
  public boolean enableCcTestFeature;

  @Option(
      name = "incompatible_load_cc_rules_from_bzl",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "No-op flag. Will be removed in a future release.")
  public boolean loadCcRulesFromBzl;

  @Option(
      name = "apple_generate_dsym",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.ACTION_COMMAND_LINES},
      help = "Whether to generate debug symbol(.dSYM) file(s).")
  public boolean appleGenerateDsym;

  @Option(
      name = "apple_enable_auto_dsym_dbg",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.ACTION_COMMAND_LINES},
      help = "Whether to force enable generating debug symbol(.dSYM) file(s) for dbg builds.")
  public boolean appleEnableAutoDsymDbg;

  @Option(
      name = "objc_generate_linkmap",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Specifies whether to generate a linkmap file.")
  public boolean objcGenerateLinkmap;

  @Option(
      name = "objc_enable_binary_stripping",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
      help =
          "Whether to perform symbol and dead-code strippings on linked binaries. Binary "
              + "strippings will be performed if both this flag and --compilation_mode=opt are "
              + "specified.")
  public boolean objcEnableBinaryStripping;

  @Option(
      name = "experimental_starlark_cc_import",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help = "If enabled, the Starlark version of cc_import can be used.")
  public boolean experimentalStarlarkCcImport;

  @Option(
      name = "incompatible_force_strict_header_check_from_starlark",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.CHANGES_INPUTS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If enabled, set strict header checking in the Starlark API")
  public boolean forceStrictHeaderCheckFromStarlark;

  @Option(
      name = "experimental_generate_llvm_lcov",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS},
      help = "If true, coverage for clang will generate an LCOV report.")
  public boolean generateLlvmLcov;

  @Option(
      name = "incompatible_use_cpp_compile_header_mnemonic",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.EXECUTION},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If enabled, give distinguishing mnemonic to header processing actions")
  public boolean useCppCompileHeaderMnemonic;

  @Option(
      name = "incompatible_macos_set_install_name",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "Whether to explicitly set `-install_name` when creating dynamic libraries. "
              + "See https://github.com/bazelbuild/bazel/issues/12370")
  public boolean macosSetInstallName;

  @Option(
      name = "experimental_use_cpp_compile_action_args_params_file",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help = "If enabled, write CppCompileAction exposed action.args to parameters file.")
  public boolean useArgsParamsFile;

  @Option(
      name = "experimental_cpp_compile_argv_ignore_param_file",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If enabled, CppCompileAction action.argv returns the complete list of argv even if"
              + " compiler_param_file is enabled.")
  public boolean ignoreParamFile;

  @Option(
      name = "experimental_unsupported_and_brittle_include_scanning",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.EXECUTION,
        OptionEffectTag.CHANGES_INPUTS
      },
      help =
          "Whether to narrow inputs to C/C++ compilation by parsing #include lines from input"
              + " files. This can improve performance and incrementality by decreasing the size of"
              + " compilation input trees. However, it can also break builds because the include"
              + " scanner does not fully implement C preprocessor semantics. In particular, it does"
              + " not understand dynamic #include directives and ignores preprocessor conditional"
              + " logic. Use at your own risk. Any issues relating to this flag that are filed will"
              + " be closed.")
  public boolean experimentalIncludeScanning;

  @Option(
      name = "experimental_objc_include_scanning",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.EXECUTION,
        OptionEffectTag.CHANGES_INPUTS
      },
      help = "Whether to perform include scanning for objective C/C++.")
  public boolean objcScanIncludes;

  @Option(
      name = "objc_use_dotd_pruning",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.CHANGES_INPUTS, OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          "If set, .d files emitted by clang will be used to prune the set of inputs passed into "
              + "objc compiles.")
  public boolean objcGenerateDotdFiles;

  @Option(
      name = "experimental_cc_implementation_deps",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help = "If enabled, cc_library targets can use attribute `implementation_deps`.")
  public boolean experimentalCcImplementationDeps;

  @Option(
      name = "experimental_link_static_libraries_once",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
      },
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If enabled, cc_shared_library will link all libraries statically linked into it, that"
              + " should only be linked once.")
  public boolean experimentalLinkStaticLibrariesOnce;

  @Option(
      name = "experimental_enable_target_export_check",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If enabled, cc_shared_library will export targets which is allowed by its `permissions`"
              + " attribute.")
  public boolean experimentalEnableTargetExportCheck;

  @Option(
      name = "experimental_cc_shared_library_debug",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help = "If enabled, will create debug files associated with cc_shared_library.")
  public boolean experimentalCcSharedLibraryDebug;

  @Option(
      name = "experimental_cpp_compile_resource_estimation",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.EXECUTION,
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If enabled, will estimate precise resource usage for local execution of"
              + " CppCompileAction.")
  public boolean experimentalCppCompileResourcesEstimation;

  @Option(
      name = "experimental_cpp_link_resources_estimation_cpu",
      defaultValue = "1",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.EXECUTION,
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "Specify the number of CPU cores to allocate for local execution of CppLinkAction. "
              + "the default configuration is to use 1 cpu core.")
  public int experimentalCppLinkResourcesEstimationCpu;

  @Option(
      name = "experimental_cpp_link_resources_estimation_min_memory",
      defaultValue = "-1.0",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.EXECUTION,
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "Specify the minimum amount of memory (in MBs) to allocate for local execution of CppLinkAction. "
              + "The default configuration is to use existing platform specific minimum memory.")
  public double experimentalCppLinkResourcesEstimationMinMemory;

  @Option(
      name = "experimental_platform_cc_test",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {
        OptionEffectTag.LOADING_AND_ANALYSIS,
      },
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If enabled, a Starlark version of cc_test can be used which will use platform-based"
              + " toolchain() resolution to choose a test runner.")
  public boolean experimentalPlatformCcTest;

  /** See {@link #targetLibcTopLabel} documentation. * */
  @Override
  public FragmentOptions getNormalized() {
    CppOptions newOptions = (CppOptions) this.clone();
    boolean changed = false;
    if (targetLibcTopLabel != null
        && targetLibcTopLabel.getName().equals(TARGET_LIBC_TOP_NOT_YET_SET)) {
      newOptions.targetLibcTopLabel = libcTopLabel;
      changed = true;
    }
    if (hostCrosstoolTop == null) {
      // Default to the initial target crosstoolTop.
      newOptions.hostCrosstoolTop = crosstoolTop;
      // Reset this, also, to maintain the invariant that host_compiler is ignored if
      // host_crosstool_top is unset.
      newOptions.hostCppCompiler = cppCompiler;
      changed = true;
    }
    if (changed) {
      return newOptions;
    }
    return this;
  }

  @Override
  public FragmentOptions getHost() {
    CppOptions host = (CppOptions) getDefault();

    host.crosstoolTop = hostCrosstoolTop;
    host.cppCompiler = hostCppCompiler;

    // hostLibcTop doesn't default to the target's libcTop.
    // Only an explicit command-line option will change it.
    // The default is whatever the host's crosstool (which might have been specified
    // by --host_crosstool_top, or --crosstool_top as a fallback) says it should be.
    host.libcTopLabel = hostLibcTopLabel;
    // TODO(b/129045294): Remove once toolchain-transitions are implemented.
    host.targetLibcTopLabel = targetLibcTopLabel;

    // -g0 is the default, but allowMultiple options cannot have default values so we just pass
    // -g0 first and let the user options override it.
    ImmutableList.Builder<String> coptListBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> cxxoptListBuilder = ImmutableList.builder();
    // Don't add -g0 if the host platform is Windows.
    // Note that host platform is not necessarily the platform bazel is running on (foundry)
    if (OS.getCurrent() != OS.WINDOWS) {
      coptListBuilder.add("-g0");
      cxxoptListBuilder.add("-g0");
    }
    host.experimentalLinkStaticLibrariesOnce = experimentalLinkStaticLibrariesOnce;
    host.experimentalEnableTargetExportCheck = experimentalEnableTargetExportCheck;
    host.experimentalCcSharedLibraryDebug = experimentalCcSharedLibraryDebug;
    host.experimentalCcImplementationDeps = experimentalCcImplementationDeps;

    host.coptList = coptListBuilder.addAll(hostCoptList).build();
    host.cxxoptList = cxxoptListBuilder.addAll(hostCxxoptList).build();
    host.conlyoptList = ImmutableList.copyOf(hostConlyoptList);
    host.perFileCopts = ImmutableList.copyOf(hostPerFileCoptsList);
    host.linkoptList = ImmutableList.copyOf(hostLinkoptList);

    host.useStartEndLib = useStartEndLib;
    host.stripBinaries = StripMode.ALWAYS;
    host.inmemoryDotdFiles = inmemoryDotdFiles;

    host.disableExpandIfAllAvailableInFlagSet = disableExpandIfAllAvailableInFlagSet;
    host.disableLegacyCcProvider = disableLegacyCcProvider;
    host.removeCpuCompilerCcToolchainAttributes = removeCpuCompilerCcToolchainAttributes;
    host.enableCcToolchainResolution = enableCcToolchainResolution;
    host.removeLegacyWholeArchive = removeLegacyWholeArchive;
    host.dontEnableHostNonhost = dontEnableHostNonhost;
    host.requireCtxInConfigureFeatures = requireCtxInConfigureFeatures;
    host.useStandaloneLtoIndexingCommandLines = useStandaloneLtoIndexingCommandLines;
    host.useSpecificToolFiles = useSpecificToolFiles;
    host.disableNoCopts = disableNoCopts;
    host.loadCcRulesFromBzl = loadCcRulesFromBzl;
    host.validateTopLevelHeaderInclusions = validateTopLevelHeaderInclusions;
    host.parseHeadersSkippedIfCorrespondingSrcsFound = parseHeadersSkippedIfCorrespondingSrcsFound;
    host.strictSystemIncludes = strictSystemIncludes;
    host.useArgsParamsFile = useArgsParamsFile;
    host.ignoreParamFile = ignoreParamFile;
    host.experimentalIncludeScanning = experimentalIncludeScanning;
    host.renameDLL = renameDLL;
    host.enableCcTestFeature = enableCcTestFeature;
    host.forceStrictHeaderCheckFromStarlark = forceStrictHeaderCheckFromStarlark;
    host.useCppCompileHeaderMnemonic = useCppCompileHeaderMnemonic;

    // Save host options for further use.
    host.hostCoptList = hostCoptList;
    host.hostConlyoptList = hostConlyoptList;
    host.hostCppCompiler = hostCppCompiler;
    host.hostCrosstoolTop = hostCrosstoolTop;
    host.hostCxxoptList = hostCxxoptList;
    host.hostPerFileCoptsList = hostPerFileCoptsList;
    host.hostLibcTopLabel = hostLibcTopLabel;
    host.hostLinkoptList = hostLinkoptList;

    host.experimentalStarlarkCcImport = experimentalStarlarkCcImport;

    host.macosSetInstallName = macosSetInstallName;

    return host;
  }

  /** Returns true if targets under this configuration should apply FDO. */
  public boolean isFdo() {
    return getFdoOptimize() != null || fdoInstrumentForBuild != null || fdoProfileLabel != null;
  }

  /** Returns true if targets under this configuration should apply CSFdo. */
  public boolean isCSFdo() {
    return (getFdoOptimize() != null || fdoProfileLabel != null)
        && (csFdoInstrumentForBuild != null
            || csFdoProfileLabel != null
            || csFdoAbsolutePathForBuild != null);
  }
}
