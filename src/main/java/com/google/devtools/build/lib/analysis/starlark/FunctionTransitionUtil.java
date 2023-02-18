// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.starlark;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.COMMAND_LINE_OPTION_PREFIX;
import static com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition.PATCH_TRANSITION_KEY;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Joiner;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.ValidationException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Starlark;

/**
 * Utility class for common work done across {@link StarlarkAttributeTransitionProvider} and {@link
 * StarlarkRuleTransitionProvider}.
 */
public final class FunctionTransitionUtil {

  // The length of the hash of the config tacked onto the end of the output path.
  // Limited for ergonomics and MAX_PATH reasons.
  private static final int HASH_LENGTH = 12;

  /**
   * Figure out what build settings the given transition changes and apply those changes to the
   * incoming {@link BuildOptions}. For native options, this involves a preprocess step of
   * converting options to their "command line form".
   *
   * <p>Also validate that transitions output the declared results.
   *
   * @param buildOptions the pre-transition build options
   * @param starlarkTransition the transition to apply
   * @param attrObject the attributes of the rule to which this transition is attached
   * @return the post-transition build options, or null if errors were reported to handler.
   */
  @Nullable
  static Map<String, BuildOptions> applyAndValidate(
      BuildOptions buildOptions,
      StarlarkDefinedConfigTransition starlarkTransition,
      StructImpl attrObject,
      EventHandler handler)
      throws InterruptedException {
    try {
      checkForDenylistedOptions(starlarkTransition);

      // TODO(waltl): consider building this once and use it across different split
      // transitions.
      Map<String, OptionInfo> optionInfoMap = buildOptionInfo(buildOptions);
      Dict<String, Object> settings =
          buildSettings(buildOptions, optionInfoMap, starlarkTransition);

      ImmutableMap.Builder<String, BuildOptions> splitBuildOptions = ImmutableMap.builder();

      ImmutableMap<String, Map<String, Object>> transitions =
          starlarkTransition.evaluate(settings, attrObject, handler);
      if (transitions == null) {
        return null; // errors reported to handler
      } else if (transitions.isEmpty()) {
        // The transition produced a no-op.
        return ImmutableMap.of(PATCH_TRANSITION_KEY, buildOptions);
      }

      for (Map.Entry<String, Map<String, Object>> entry : transitions.entrySet()) {
        Map<String, Object> newValues = handleImplicitPlatformChange(entry.getValue());
        BuildOptions transitionedOptions =
            applyTransition(buildOptions, newValues, optionInfoMap, starlarkTransition);
        splitBuildOptions.put(entry.getKey(), transitionedOptions);
      }
      return splitBuildOptions.build();

    } catch (ValidationException ex) {
      handler.handle(Event.error(starlarkTransition.getLocation(), ex.getMessage()));
      return null;
    }
  }

  /**
   * If the transition changes --cpu but not --platforms, clear out --platforms.
   *
   * <p>Purpose:
   *
   * <ol>
   *   <li>A platform mapping sets --cpu=foo when --platforms=foo.
   *   <li>A transition sets --cpu=bar.
   *   <li>Because --platforms=foo, the platform mapping kicks in to set --cpu back to foo.
   *   <li>Result: the mapping accidentally overrides the transition
   * </ol>
   *
   * <p>Transitions can also explicitly set --platforms to be clear what platform they set.
   *
   * <p>Platform mappings:
   * https://docs.bazel.build/versions/main/platforms-intro.html#platform-mappings.
   *
   * <p>This doesn't check that the changed value is actually different than the source (i.e.
   * setting {@code --cpu=foo} when {@code --cpu} is already {@code foo}). That could unnecessarily
   * fork configurations that are really the same. That's a possible optimization TODO.
   */
  private static Map<String, Object> handleImplicitPlatformChange(
      Map<String, Object> originalOutput) {
    boolean changesCpu = originalOutput.containsKey(COMMAND_LINE_OPTION_PREFIX + "cpu");
    boolean changesPlatforms = originalOutput.containsKey(COMMAND_LINE_OPTION_PREFIX + "platforms");
    return changesCpu && !changesPlatforms
        ? ImmutableMap.<String, Object>builder()
            .putAll(originalOutput)
            .put(COMMAND_LINE_OPTION_PREFIX + "platforms", ImmutableList.<Label>of())
            .build()
        : originalOutput;
  }

  private static void checkForDenylistedOptions(StarlarkDefinedConfigTransition transition)
      throws ValidationException {
    if (transition.getOutputs().contains("//command_line_option:define")) {
      throw new ValidationException(
          "Starlark transition on --define not supported - try using build settings"
              + " (https://docs.bazel.build/skylark/config.html#user-defined-build-settings).");
    }
  }

  /** For all the options in the BuildOptions, build a map from option name to its information. */
  static ImmutableMap<String, OptionInfo> buildOptionInfo(BuildOptions buildOptions) {
    ImmutableMap.Builder<String, OptionInfo> builder = new ImmutableMap.Builder<>();

    ImmutableSet<Class<? extends FragmentOptions>> optionClasses =
        buildOptions.getNativeOptions().stream()
            .map(FragmentOptions::getClass)
            .collect(toImmutableSet());

    for (Class<? extends FragmentOptions> optionClass : optionClasses) {
      ImmutableList<OptionDefinition> optionDefinitions =
          OptionsParser.getOptionDefinitions(optionClass);
      for (OptionDefinition def : optionDefinitions) {
        String optionName = def.getOptionName();
        builder.put(optionName, new OptionInfo(optionClass, def));
      }
    }

    return builder.build();
  }

  /**
   * Enter the options in buildOptions into a Starlark dictionary, and return the dictionary.
   *
   * @throws IllegalArgumentException If the method is unable to look up the value in buildOptions
   *     corresponding to an entry in optionInfoMap
   * @throws RuntimeException If the field corresponding to an option value in buildOptions is
   *     inaccessible due to Java language access control, or if an option name is an invalid key to
   *     the Starlark dictionary
   * @throws ValidationException if any of the specified transition inputs do not correspond to a
   *     valid build setting
   */
  static Dict<String, Object> buildSettings(
      BuildOptions buildOptions,
      Map<String, OptionInfo> optionInfoMap,
      StarlarkDefinedConfigTransition starlarkTransition)
      throws ValidationException {
    Map<String, String> inputsCanonicalizedToGiven =
        starlarkTransition.getInputsCanonicalizedToGiven();
    LinkedHashSet<String> remainingInputs =
        Sets.newLinkedHashSet(inputsCanonicalizedToGiven.keySet());

    try (Mutability mutability = Mutability.create("build_settings")) {
      Dict<String, Object> dict = Dict.of(mutability);

      // Add native options
      for (Map.Entry<String, OptionInfo> entry : optionInfoMap.entrySet()) {
        String optionName = entry.getKey();
        String optionKey = COMMAND_LINE_OPTION_PREFIX + optionName;

        if (!remainingInputs.remove(optionKey)) {
          // This option was not present in inputs. Skip it.
          continue;
        }
        OptionInfo optionInfo = entry.getValue();

        Field field = optionInfo.getDefinition().getField();
        FragmentOptions options = buildOptions.get(optionInfo.getOptionClass());
        try {
          Object optionValue = field.get(options);
          dict.putEntry(optionKey, optionValue == null ? Starlark.NONE : optionValue);
        } catch (IllegalAccessException e) {
          // These exceptions should not happen, but if they do, throw a RuntimeException.
          throw new RuntimeException(e);
        } catch (EvalException ex) {
          throw new IllegalStateException(ex); // can't happen
        }
      }

      // Add Starlark options
      for (Map.Entry<Label, Object> starlarkOption : buildOptions.getStarlarkOptions().entrySet()) {
        String canonicalLabelForm = starlarkOption.getKey().getUnambiguousCanonicalForm();
        if (!remainingInputs.remove(canonicalLabelForm)) {
          continue;
        }
        // Convert the canonical form to the user requested form that they expect to see in this
        // dict.
        String userRequestedLabelForm = inputsCanonicalizedToGiven.get(canonicalLabelForm);
        try {
          dict.putEntry(userRequestedLabelForm, starlarkOption.getValue());
        } catch (EvalException ex) {
          throw new IllegalStateException(ex); // can't happen
        }
      }

      if (!remainingInputs.isEmpty()) {
        throw ValidationException.format(
            "transition inputs [%s] do not correspond to valid settings",
            Joiner.on(", ").join(remainingInputs));
      }

      return dict;
    }
  }

  /**
   * Apply the transition dictionary to the build option, using optionInfoMap to look up the option
   * info.
   *
   * @param fromOptions the pre-transition build options
   * @param newValues a map of option name: option value entries to override current option values
   *     in the buildOptions param
   * @param optionInfoMap a map of all native options (name -> OptionInfo) present in {@code
   *     toOptions}.
   * @param starlarkTransition transition object that is being applied. Used for error reporting and
   *     checking for analysis testing
   * @return the post-transition build options
   * @throws ValidationException If a requested option field is inaccessible
   */
  private static BuildOptions applyTransition(
      BuildOptions fromOptions,
      Map<String, Object> newValues,
      Map<String, OptionInfo> optionInfoMap,
      StarlarkDefinedConfigTransition starlarkTransition)
      throws ValidationException {
    // toOptions being null means the transition hasn't changed anything. We avoid preemptively
    // cloning it from fromOptions since options cloning is an expensive operation.
    BuildOptions toOptions = null;
    // The names and values of options (Starlark + native) that are different after this transition.
    Set<String> convertedNewValues = new HashSet<>();
    // Starlark options that are different after this transition. We collect all of them, then clone
    // the build options once with all cumulative changes. Native option changes, in contrast, are
    // set directly in the BuildOptions instance. The former approach is preferred since it makes
    // BuildOptions objects more immutable. Native options use the latter approach for legacy
    // reasons. While not preferred, direct mutation doesn't require expensive cloning.
    Map<Label, Object> changedStarlarkOptions = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : newValues.entrySet()) {
      String optionName = entry.getKey();
      Object optionValue = entry.getValue();

      if (!optionName.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
        // The transition changes a Starlark option.
        Label optionLabel = Label.parseAbsoluteUnchecked(optionName);
        Object oldValue = fromOptions.getStarlarkOptions().get(optionLabel);
        if ((oldValue == null && optionValue != null)
            || (oldValue != null && optionValue == null)
            || (oldValue != null && !oldValue.equals(optionValue))) {
          changedStarlarkOptions.put(optionLabel, optionValue);
          convertedNewValues.add(optionLabel.toString());
        }
      } else {
        // The transition changes a native option.
        optionName = optionName.substring(COMMAND_LINE_OPTION_PREFIX.length());

        // Convert NoneType to null.
        if (optionValue instanceof NoneType) {
          optionValue = null;
        }
        try {
          if (!optionInfoMap.containsKey(optionName)) {
            throw ValidationException.format(
                "transition output '%s' does not correspond to a valid setting", entry.getKey());
          }

          OptionInfo optionInfo = optionInfoMap.get(optionName);
          OptionDefinition def = optionInfo.getDefinition();
          Field field = def.getField();
          // TODO(b/153867317): check for crashing options types in this logic.
          Object convertedValue;
          if (def.getType() == List.class && optionValue instanceof List && !def.allowsMultiple()) {
            // This is possible with Starlark code like "{ //command_line_option:foo: ["a", "b"] }".
            // In that case def.getType() == List.class while optionValue.type == StarlarkList.
            // Unfortunately we can't check the *element* types because OptionDefinition won't tell
            // us that about def (def.getConverter() returns LabelListConverter but nowhere does it
            // mention Label.class). Worse, def.getConverter().convert takes a String input. This
            // forces us to serialize optionValue back to a scalar string to convert. There's no
            // generically safe way to do this. We convert its elements with .toString() with a ","
            // separator, which happens to work for most implementations. But that's not universally
            // guaranteed.
            // TODO(b/153867317): support allowMultiple options too. This is subtle: see the
            // description of allowMultiple in Option.java. allowMultiple converts have the choice
            // of returning either a scalar or list.
            List<?> optionValueAsList = (List<?>) optionValue;
            if (optionValueAsList.isEmpty()) {
              convertedValue = def.getDefaultValue();
            } else {
              convertedValue =
                  def.getConverter()
                      .convert(
                          optionValueAsList.stream().map(Object::toString).collect(joining(",")));
            }
          } else if (def.getType() == List.class && optionValue == null) {
            throw ValidationException.format(
                "'None' value not allowed for List-type option '%s'. Please use '[]' instead if"
                    + " trying to set option to empty value.",
                optionName);
          } else if (optionValue == null || def.getType().isInstance(optionValue)) {
            convertedValue = optionValue;
          } else if (def.getType().equals(boolean.class) && optionValue instanceof Boolean) {
            convertedValue = ((Boolean) optionValue).booleanValue();
          } else if (optionValue instanceof String) {
            convertedValue = def.getConverter().convert((String) optionValue);
          } else {
            throw ValidationException.format("Invalid value type for option '%s'", optionName);
          }

          Object oldValue = field.get(fromOptions.get(optionInfo.getOptionClass()));
          if ((oldValue == null && convertedValue != null)
              || (oldValue != null && convertedValue == null)
              || (oldValue != null && !oldValue.equals(convertedValue))) {
            if (toOptions == null) {
              toOptions = fromOptions.clone();
            }
            field.set(toOptions.get(optionInfo.getOptionClass()), convertedValue);
            convertedNewValues.add(entry.getKey());
          }

        } catch (IllegalArgumentException e) {
          throw ValidationException.format(
              "IllegalArgumentError for option '%s': %s", optionName, e.getMessage());
        } catch (IllegalAccessException e) {
          throw new RuntimeException(
              "IllegalAccess for option " + optionName + ": " + e.getMessage());
        } catch (OptionsParsingException e) {
          throw ValidationException.format(
              "OptionsParsingError for option '%s': %s", optionName, e.getMessage());
        }
      }
    }

    if (!changedStarlarkOptions.isEmpty()) {
      toOptions =
          BuildOptions.builder()
              .merge(toOptions == null ? fromOptions.clone() : toOptions)
              .addStarlarkOptions(changedStarlarkOptions)
              .build();
    } else {
      // Uber Hack: Force exposing apple split cpu into transition hash key if there's a valid transition
      // Transition hash only record the changed fregment set, for some reason a multi_arch_split transition seems to:
      // - result in changed apple_split_cpu from default "" to host cpu
      // - applied before the starlark transition difference analysis here
      // we also remove ios_cpu from the transition evaluation as it's in the process of deprecation and poorly handled.
      // Ideally we should fix the root cause, this is more or less a lazy hack
      if (newValues.keySet().contains("//command_line_option:apple_split_cpu")) {
        convertedNewValues.add("//command_line_option:apple_split_cpu");
      }
      if (newValues.keySet().contains("//command_line_option:ios_cpu")) {
        convertedNewValues.remove("//command_line_option:ios_cpu");
      }
    }
    if (toOptions == null) {
      return fromOptions;
    }
    if (starlarkTransition.isForAnalysisTesting()) {
      // We need to record every time we change a configuration option.
      // see {@link #updateOutputDirectoryNameFragment} for usage.
      convertedNewValues.add("//command_line_option:evaluating for analysis test");
      toOptions.get(CoreOptions.class).evaluatingForAnalysisTest = true;
    }

    updateAffectedByStarlarkTransition(toOptions.get(CoreOptions.class), convertedNewValues);
    return toOptions;
  }

  /**
   * Compute the output directory name fragment corresponding to the new BuildOptions based on the
   * names and values of all options (both native and Starlark) previously transitioned anywhere in
   * the build by Starlark transitions. Options only set on command line are not affecting the
   * computation.
   *
   * @param toOptions the {@link BuildOptions} to use to calculate which we need to compute {@code
   *     transitionDirectoryNameFragment}.
   */
  // TODO(bazel-team): This hashes different forms of equivalent values differently though they
  // should be the same configuration. Starlark transitions are flexible about the values they
  // take (e.g. bool-typed options can take 0/1, True/False, "0"/"1", or "True"/"False") which
  // makes it so that two configurations that are the same in value may hash differently.
  public static String computeOutputDirectoryNameFragment(BuildOptions toOptions) {
    CoreOptions buildConfigOptions = toOptions.get(CoreOptions.class);
    if (buildConfigOptions.affectedByStarlarkTransition.isEmpty()) {
      return "";
    }
    // TODO(blaze-configurability-team): A mild performance optimization would have this be global.
    Map<String, OptionInfo> optionInfoMap = buildOptionInfo(toOptions);

    TreeMap<String, Object> toHash = new TreeMap<>();
    for (String optionName : buildConfigOptions.affectedByStarlarkTransition) {
      if (optionName.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
        String nativeOptionName = optionName.substring(COMMAND_LINE_OPTION_PREFIX.length());
        Object value;
        try {
          value =
              optionInfoMap
                  .get(nativeOptionName)
                  .getDefinition()
                  .getField()
                  .get(toOptions.get(optionInfoMap.get(nativeOptionName).getOptionClass()));
        } catch (IllegalAccessException e) {
          throw new VerifyException(
              "IllegalAccess for option " + nativeOptionName + ": " + e.getMessage());
        }
        toHash.put(optionName, value);
      } else {
        Object value = toOptions.getStarlarkOptions().get(Label.parseAbsoluteUnchecked(optionName));
        toHash.put(optionName, value);
      }
    }

    ImmutableList.Builder<String> hashStrs = ImmutableList.builderWithExpectedSize(toHash.size());
    for (Map.Entry<String, Object> singleOptionAndValue : toHash.entrySet()) {
      Object value = singleOptionAndValue.getValue();
      if (value != null) {
        hashStrs.add(singleOptionAndValue.getKey() + "=" + value);
      } else {
        // Avoid using =null to different from value being the non-null String "null"
        hashStrs.add(singleOptionAndValue.getKey() + "@null");
      }
    }
    return transitionDirectoryNameFragment(hashStrs.build());
  }

  /**
   * Extend the global build config affectedByStarlarkTransition, by adding any new option names
   * from changedOptions
   */
  private static void updateAffectedByStarlarkTransition(
      CoreOptions buildConfigOptions, Set<String> changedOptions) {
    if (changedOptions.isEmpty()) {
      return;
    }
    Set<String> mutableCopyToUpdate =
        new TreeSet<>(buildConfigOptions.affectedByStarlarkTransition);
    mutableCopyToUpdate.addAll(changedOptions);
    buildConfigOptions.affectedByStarlarkTransition =
        ImmutableList.sortedCopyOf(mutableCopyToUpdate);
  }

  public static String transitionDirectoryNameFragment(Iterable<String> opts) {
    Fingerprint fp = new Fingerprint();
    for (String opt : opts) {
      fp.addString(opt);
    }
    // Shorten the hash to 48 bits. This should provide sufficient collision avoidance
    // (that is, we don't expect anyone to experience a collision ever).
    // Shortening the hash is important for Windows paths that tend to be short.
    String suffix = fp.hexDigestAndReset().substring(0, HASH_LENGTH);
    return "ST-" + suffix;
  }

  /** Stores option info useful to a FunctionSplitTransition. */
  static class OptionInfo {
    private final Class<? extends FragmentOptions> optionClass;
    private final OptionDefinition definition;

    public OptionInfo(Class<? extends FragmentOptions> optionClass, OptionDefinition definition) {
      this.optionClass = optionClass;
      this.definition = definition;
    }

    Class<? extends FragmentOptions> getOptionClass() {
      return optionClass;
    }

    OptionDefinition getDefinition() {
      return definition;
    }
  }

  private FunctionTransitionUtil() {}
}
