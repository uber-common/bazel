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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AbstractAttributeMapper;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.query2.query.output.AttributeValueSource;
import com.google.devtools.build.lib.query2.query.output.BuildOutputFormatter.AttributeReader;
import com.google.devtools.build.lib.query2.query.output.JsonOutputFormatter;
import com.google.devtools.build.lib.query2.query.output.PossibleAttributeValues;
import com.google.devtools.build.lib.rules.AliasConfiguredTarget;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Cquery implementation of BUILD-style output.
 */
class JsonOutputFormatterCallback extends CqueryThreadsafeCallback {

  JsonOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      CqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<ConfiguredTarget> accessor) {
    super(eventHandler, options, out, skyframeExecutor, accessor);
  }

  @Override
  public String getName() {
    return "json";
  }

  private AbstractAttributeMapper getAttributeMap(Rule rule, ConfiguredTarget configuredTarget)
      throws InterruptedException {
    if (configuredTarget instanceof RuleConfiguredTarget) {
      return ConfiguredAttributeMapper
          .of(rule, ((RuleConfiguredTarget) configuredTarget).getConfigConditions());
    }
    if (configuredTarget instanceof AliasConfiguredTarget) {
      return ConfiguredAttributeMapper
          .of(rule, ((AliasConfiguredTarget) configuredTarget).getConfigConditions());
    }
    if (configuredTarget instanceof OutputFileConfiguredTarget) {
      return ConfiguredAttributeMapper
          .of(rule, accessor
              .getGeneratingConfiguredTarget((OutputFileConfiguredTarget) configuredTarget)
              .getConfigConditions());
    }
    return null;
  }

  public static class JsonCQueryAttributeReader implements AttributeReader {

    private final AbstractAttributeMapper abstractAttributeMap;

    public JsonCQueryAttributeReader(AbstractAttributeMapper abstractAttributeMap) {
      this.abstractAttributeMap = abstractAttributeMap;
    }

    private Object getValues(Attribute attr) {
      if (abstractAttributeMap == null){
        return null;
      }
      return abstractAttributeMap.get(attr.getName(), attr.getType());
    }

    @Override
    public PossibleAttributeValues getPossibleValues(Rule rule, Attribute attr) {
      Object values = getValues(attr);
      return new PossibleAttributeValues(
          values == null ? ImmutableList.of() : ImmutableList.of(values),
          AttributeValueSource.forRuleAndAttribute(rule, attr));
    }
  }

  @Override
  public void processOutput(Iterable<ConfiguredTarget> partialResult)
      throws IOException, InterruptedException {

    JsonObject result = new JsonObject();
    Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    for (ConfiguredTarget configuredTarget : partialResult) {
      Target target = accessor.getTargetFromConfiguredTarget(configuredTarget);
      Rule rule = target.getAssociatedRule();
      AbstractAttributeMapper attributeMap = getAttributeMap(rule, configuredTarget);
      AttributeReader attributeReader = new JsonCQueryAttributeReader(attributeMap);
      result.add(target.getLabel().getCanonicalForm(),
          JsonOutputFormatter.createTargetJsonObject(target, attributeReader));
    }
    printStream.write(gson.toJson(result));
  }
}