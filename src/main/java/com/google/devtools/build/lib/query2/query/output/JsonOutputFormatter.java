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
package com.google.devtools.build.lib.query2.query.output;

import com.google.common.hash.HashFunction;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AbstractAttributeMapper;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.BuildType.Selector;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.common.CommonQueryOptions;
import com.google.devtools.build.lib.query2.engine.OutputFormatterCallback;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.SynchronizedDelegatingOutputFormatterCallback;
import com.google.devtools.build.lib.query2.engine.ThreadSafeOutputFormatterCallback;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver;
import com.google.devtools.build.lib.query2.query.output.BuildOutputFormatter.AttributeReader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An output formatter that prints the result as Json.
 */
public class JsonOutputFormatter extends AbstractUnorderedFormatter {

  @Override
  public String getName() {
    return "json";
  }

  @Override
  public ThreadSafeOutputFormatterCallback<Target> createStreamCallback(
      OutputStream out, QueryOptions options, QueryEnvironment<?> env) {
    return new SynchronizedDelegatingOutputFormatterCallback<>(
        createPostFactoStreamCallback(out, options));
  }

  @Override
  public void setOptions(CommonQueryOptions options, AspectResolver aspectResolver, HashFunction hashFunction) {
    super.setOptions(options, aspectResolver, hashFunction);

    Preconditions.checkArgument(options instanceof QueryOptions);
  }

  @Override
  public OutputFormatterCallback<Target> createPostFactoStreamCallback(
      final OutputStream out, final QueryOptions options) {
    return new OutputFormatterCallback<Target>() {

      private JsonObject result = new JsonObject();
      private Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

      @Override
      public void processOutput(Iterable<Target> partialResult)
          throws IOException, InterruptedException {
        for (Target target : partialResult) {
          result.add(target.getLabel().getCanonicalForm(),
              createTargetJsonObject(target,
                  new JsonQueryAttributeReader(target)));
        }
      }

      @Override
      public void close(boolean failFast) throws IOException {
        if (!failFast) {
          out.write(gson.toJson(result).getBytes());
        }
      }
    };
  }

  public static class JsonQueryAttributeReader implements AttributeReader {

    private final AbstractAttributeMapper abstractAttributeMap;

    public JsonQueryAttributeReader(Target target) {
      Rule rule = target.getAssociatedRule();
      this.abstractAttributeMap = rule == null ? null : RawAttributeMapper.of(rule);
    }

    @Override
    public PossibleAttributeValues getPossibleValues(Rule rule, Attribute attr) {
      if (rule.isConfigurableAttribute(attr.getName())) {
        // Multiple selectors can be concatenated when the attribute value is a list.
        // To create a consistent output, we return a list of maps, where each map is
        // a selector dictionary.
        ImmutableList<Object> selectors = abstractAttributeMap
            .getSelectorList(attr.getName(), attr.getType())
            .getSelectors()
            .stream()
            .map(Selector::getEntries)
            .collect(ImmutableList.toImmutableList());
        return new PossibleAttributeValues(
            ImmutableList.of(selectors),
            AttributeValueSource.forRuleAndAttribute(rule, attr));
      }
      return PossibleAttributeValues.forRuleAndAttribute(rule, attr);
    }
  }

  public static JsonObject createTargetJsonObject(Target target, AttributeReader reader) {
    JsonObject result = new JsonObject();

    if (target instanceof Rule) {
      Rule rule = target.getAssociatedRule();
      result.addProperty("fully_qualified_name", target.getLabel().getCanonicalForm());
      result.addProperty("base_path", target.getLabel().getPackageName());
      result.addProperty("class", rule.getRuleClass());
      for (Attribute attr : rule.getAttributes()) {
        PossibleAttributeValues values = reader.getPossibleValues(rule, attr);
        if (values.getSource() == AttributeValueSource.DEFAULT) {
          continue;
        }
        for (Object value : values) {
          result.add(attr.getName(), getJsonFromValue(value));
        }
      }
    } else if (target instanceof InputFile) {
      InputFile file = (InputFile) target;
      result.addProperty("class", target.getTargetKind());
      result.addProperty("name", file.getName());
      result.addProperty("path", file.getPath().toString());
      // FIXME - set current value for siblingRepositoryLayout or remove it all together
      result.addProperty("execpath", file.getExecPath(false).toString());
    } else if (target instanceof OutputFile) {
      OutputFile file = (OutputFile) target;
      result.addProperty("class", target.getTargetKind());
      result.addProperty("name", file.getName());
      result.addProperty("owner", file.getGeneratingRule().getLabel().toString());
    } else if (target instanceof PackageGroup) {
      PackageGroup group = (PackageGroup) target;
      result.addProperty("class", target.getTargetKind());
      result.addProperty("name", group.getName());
      result.add("packages", getJsonFromValue(group.getContainedPackages()));
      result.add("includes", getJsonFromValue(group.getIncludes()));
    } else {
      result.addProperty("class", target.getTargetKind());
    }
    return result;
  }

  private static JsonElement getJsonFromValue(Object val) {
    Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    if (val instanceof List) {
      Iterator<Object> it = ((List) val).iterator();
      JsonArray result = new JsonArray();
      while (it.hasNext()) {
        Object currentVal = it.next();
        result.add(getJsonFromValue(currentVal));
      }
      return result;
    } else if (val instanceof Map) {
      JsonObject result = new JsonObject();
      Map<Object, Object> valMap = (Map) val;
      for (Object key : valMap.keySet()) {
        result.add(key.toString(), getJsonFromValue(valMap.get(key)));
      }
      return result;
    } else if (val instanceof Boolean) {
      return gson.toJsonTree(val);
    }
    return gson.toJsonTree(val == null ? "None" : val.toString());
  }
}