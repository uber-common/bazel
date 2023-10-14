// Copyright 2017 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec.util;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.DelegateSpawn;
import com.google.devtools.build.lib.actions.EmptyRunfilesSupplier;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** Builder class to create {@link Spawn} instances for testing. */
public final class SpawnBuilder {
  private String mnemonic = "Mnemonic";
  private String progressMessage = "progress message";
  private String ownerLabel = "//dummy:label";
  @Nullable private Artifact ownerPrimaryOutput;
  @Nullable private PlatformInfo platform;
  private final List<String> args;
  private final Map<String, String> environment = new HashMap<>();
  private final Map<String, String> executionInfo = new HashMap<>();
  private ImmutableMap<String, String> execProperties = ImmutableMap.of();
  private final NestedSetBuilder<ActionInput> inputs = NestedSetBuilder.stableOrder();
  private final List<ActionInput> outputs = new ArrayList<>();
  @Nullable private Set<? extends ActionInput> mandatoryOutputs;
  private final Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings =
      new HashMap<>();
  private final NestedSetBuilder<ActionInput> tools = NestedSetBuilder.stableOrder();

  private RunfilesSupplier runfilesSupplier = EmptyRunfilesSupplier.INSTANCE;
  private ResourceSet resourceSet = ResourceSet.ZERO;
  private boolean stripOutputPaths;
  private boolean builtForToolConfiguration;

  /**
   * A {@link DelegateSpawn} that supports output path stripping as described in {@link
   * com.google.devtools.build.lib.actions.PathStripper}.
   *
   * <p>By overriding {@link #stripOutputPaths} - and only in test code - instead of adding an extra
   * boolean in {@link SimpleSpawn}, we avoid further pressuring memory on large build graphs.
   */
  private static class PathStrippableSpawn extends DelegateSpawn {
    private final boolean stripOutputPaths;

    public PathStrippableSpawn(Spawn spawn, boolean stripOutputPaths) {
      super(spawn);
      this.stripOutputPaths = stripOutputPaths;
    }

    @Override
    public boolean stripOutputPaths() {
      return stripOutputPaths;
    }
  }

  public SpawnBuilder(String... args) {
    this.args = ImmutableList.copyOf(args);
  }

  public Spawn build() {
    ActionExecutionMetadata owner =
        new FakeOwner(
            mnemonic,
            progressMessage,
            ownerLabel,
            ownerPrimaryOutput,
            platform,
            execProperties,
            builtForToolConfiguration);
    return new PathStrippableSpawn(
        new SimpleSpawn(
            owner,
            ImmutableList.copyOf(args),
            ImmutableMap.copyOf(environment),
            ImmutableMap.copyOf(executionInfo),
            runfilesSupplier,
            ImmutableMap.copyOf(filesetMappings),
            inputs.build(),
            tools.build(),
            ImmutableSet.copyOf(outputs),
            mandatoryOutputs,
            resourceSet),
        stripOutputPaths);
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withPlatform(PlatformInfo platform) {
    this.platform = platform;
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withMnemonic(String mnemonic) {
    this.mnemonic = checkNotNull(mnemonic);
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withProgressMessage(String progressMessage) {
    this.progressMessage = progressMessage;
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withOwnerLabel(String ownerLabel) {
    this.ownerLabel = checkNotNull(ownerLabel);
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withOwnerPrimaryOutput(Artifact output) {
    ownerPrimaryOutput = checkNotNull(output);
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withEnvironment(String key, String value) {
    this.environment.put(key, value);
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withExecutionInfo(String key, String value) {
    this.executionInfo.put(key, value);
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withExecProperties(ImmutableMap<String, String> execProperties) {
    this.execProperties = execProperties;
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withInput(ActionInput input) {
    this.inputs.add(input);
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withInput(String name) {
    this.inputs.add(ActionInputHelper.fromPath(name));
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withInputs(ActionInput... inputs) {
    for (var input : inputs) {
      this.inputs.add(input);
    }
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withInputs(String... names) {
    for (String name : names) {
      this.inputs.add(ActionInputHelper.fromPath(name));
    }
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withOutput(ActionInput output) {
    outputs.add(output);
    return this;
  }

  public SpawnBuilder withOutput(String name) {
    return withOutput(ActionInputHelper.fromPath(name));
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withOutputs(ActionInput... outputs) {
    for (ActionInput output : outputs) {
      withOutput(output);
    }
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withOutputs(String... names) {
    for (String name : names) {
      this.outputs.add(ActionInputHelper.fromPath(name));
    }
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withMandatoryOutputs(@Nullable Set<? extends ActionInput> mandatoryOutputs) {
    this.mandatoryOutputs = mandatoryOutputs;
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withFilesetMapping(
      Artifact fileset, ImmutableList<FilesetOutputSymlink> mappings) {
    Preconditions.checkArgument(fileset.isFileset(), "Artifact %s is not fileset", fileset);
    filesetMappings.put(fileset, mappings);
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withRunfilesSupplier(RunfilesSupplier runfilesSupplier) {
    this.runfilesSupplier = runfilesSupplier;
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withTool(ActionInput tool) {
    tools.add(tool);
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder withLocalResources(ResourceSet resourceSet) {
    this.resourceSet = resourceSet;
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder stripOutputPaths(boolean strip) {
    this.stripOutputPaths = strip;
    return this;
  }

  @CanIgnoreReturnValue
  public SpawnBuilder setBuiltForToolConfiguration(boolean builtForToolConfiguration) {
    this.builtForToolConfiguration = builtForToolConfiguration;
    return this;
  }
}
