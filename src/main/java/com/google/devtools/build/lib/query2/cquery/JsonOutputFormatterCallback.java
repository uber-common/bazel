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

package com.google.devtools.build.lib.query2.cquery;
import java.util.List;
import java.util.ArrayList;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.DefaultInfo;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AbstractAttributeMapper;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.query2.query.output.BuildOutputFormatter.AttributeReader;
import com.google.devtools.build.lib.query2.query.output.JsonOutputFormatter;
import com.google.devtools.build.lib.query2.query.output.PossibleAttributeValues;
import com.google.devtools.build.lib.rules.AliasConfiguredTarget;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Cquery implementation of BUILD-style output.
 */
class JsonOutputFormatterCallback extends CqueryThreadsafeCallback {

  private final LabelPrinter labelPrinter;

  JsonOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      CqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<CqueryNode> accessor,
      LabelPrinter labelPrinter) {
    super(eventHandler, options, out, skyframeExecutor, accessor, /*uniquifyResults=*/ false);
    this.labelPrinter = labelPrinter;
  }

  @Override
  public String getName() {
    return "json";
  }

  private AbstractAttributeMapper getAttributeMap(Rule rule, CqueryNode keyedConfiguredTarget)
      throws InterruptedException {
    CqueryNode configuredTarget = keyedConfiguredTarget;
    if (configuredTarget instanceof RuleConfiguredTarget) {
      return ConfiguredAttributeMapper
          .of(rule, ((RuleConfiguredTarget) configuredTarget).getConfigConditions(), keyedConfiguredTarget.getConfigurationChecksum(),/*alwaysSucceed=*/ false);
    }
    if (configuredTarget instanceof AliasConfiguredTarget) {
      return ConfiguredAttributeMapper
          .of(rule, ((AliasConfiguredTarget) configuredTarget).getConfigConditions(),
          keyedConfiguredTarget.getConfigurationChecksum(),/*alwaysSucceed=*/ false);
    }
    if (configuredTarget instanceof OutputFileConfiguredTarget) {
      return ConfiguredAttributeMapper
          .of(rule, accessor
              .getGeneratingConfiguredTarget(keyedConfiguredTarget)
              .getConfigConditions(),
              keyedConfiguredTarget.getConfigurationChecksum(),/*alwaysSucceed=*/ false);
    }
    return null;
  }

  public static class JsonCQueryAttributeReader implements AttributeReader {

    private final AbstractAttributeMapper abstractAttributeMap;

    public JsonCQueryAttributeReader(AbstractAttributeMapper abstractAttributeMap) {
      this.abstractAttributeMap = abstractAttributeMap;
    }

    private Object getValues(Attribute attr) {
      if (abstractAttributeMap == null) {
        return null;
      }
      return abstractAttributeMap.get(attr.getName(), attr.getType());
    }

    @Override
    public Iterable<Object> getPossibleValues(Rule rule, Attribute attr) {
      Object values = getValues(attr);
      return values == null ? ImmutableList.of() : ImmutableList.of(values);
    }
  }

  @Override
  public void processOutput(Iterable<CqueryNode> partialResult)
      throws IOException, InterruptedException {

    JsonObject result = new JsonObject();
    Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    for (CqueryNode configuredTarget : partialResult) {
      Target target = accessor.getTarget(configuredTarget);
      Rule rule = target.getAssociatedRule();
      AbstractAttributeMapper attributeMap = getAttributeMap(rule, configuredTarget);
      AttributeReader attributeReader = new JsonCQueryAttributeReader(attributeMap);
      JsonObject partial = JsonOutputFormatter.createTargetJsonObject(target, attributeReader, labelPrinter);
      partial.add("output groups", createOutputGroupsJsonObject(configuredTarget, gson));
      partial.add("default outputs", createDefaultOutputsJson(configuredTarget, gson));

      result.add(labelPrinter.toString(target.getLabel()), partial);
    }
    printStream.write(gson.toJson(result));
  }

  private static JsonElement createDefaultOutputsJson(CqueryNode target, Gson gson) {
    List<String> paths = new ArrayList<>();
    try {
      if (target instanceof RuleConfiguredTarget) {
        DefaultInfo provider = ((RuleConfiguredTarget) target).get(DefaultInfo.PROVIDER);
      paths = ((Collection<Artifact>) provider.getFiles().toList())
          .stream()
          .map(Artifact::getExecPathString)
          .collect(Collectors.toList());
      }
    }
    catch (NullPointerException e){
      // Some rules do not have default info provider (e.g. Package Groups) and
      // throw an exception in case it is accessed
      return new JsonArray();
    }
    return gson.toJsonTree(paths);
  }

  private static JsonObject createOutputGroupsJsonObject(CqueryNode configuredTarget, Gson gson) {
    JsonObject outputGroups = new JsonObject();
    if (configuredTarget instanceof RuleConfiguredTarget) {
        RuleConfiguredTarget ruleConfiguredTarget = (RuleConfiguredTarget) configuredTarget;
        OutputGroupInfo provider = ruleConfiguredTarget.get(OutputGroupInfo.STARLARK_CONSTRUCTOR);
        if (provider != null) {
            for (String group : provider) {
        List<String> outputPaths = provider
            .getOutputGroup(group)
            .toList()
            .stream()
            .map(Artifact::getExecPathString)
            .collect(Collectors.toList());
        outputGroups.add(group, gson.toJsonTree(outputPaths));
          }
        }
    }
    return outputGroups;
  }
}