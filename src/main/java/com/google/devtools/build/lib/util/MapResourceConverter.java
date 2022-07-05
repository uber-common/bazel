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

package com.google.devtools.build.lib.util;

import com.google.common.collect.Maps;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParsingException;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * Converter for --mnemonic_resource_override, which takes an string for the mnemonic and then
 * followed by the value for RAM then CPU. These RAM and CPU values can either be integers greater
 * than or equal to 1. RAM may be represented by "HOST_RAM", optionally followed by [-|*]<float>.
 * CPU may be represented by "HOST_CPUS", optionally followed by [-|*]<float>.
 */
public class MapResourceConverter implements Converter<Map.Entry<String, ResourceEstimation>> {
  private RamResourceConverter ramResourceConverter = new RamResourceConverter();
  private CpuResourceConverter cpuResourceConverter = new CpuResourceConverter();

  @Override
  public Map.Entry<String, ResourceEstimation> convert(String input, @Nullable Object conversionContext) throws OptionsParsingException {
    int pos = input.indexOf("=");
    if (pos <= 0) {
      throw new OptionsParsingException(
              "Variable definitions must be in the form of a 'name=value' assignment");
    }
    String name = input.substring(0, pos);
    String value = input.substring(pos + 1);

    String[] resource_cpu = value.split(",");

    // Delegate these through ram and CPU converter to support HOST_CPUS and HOST_RAM declarations
    Integer ram = ramResourceConverter.convert(resource_cpu[0]);
    Integer cpu = cpuResourceConverter.convert(resource_cpu[1]);

    return Maps.immutableEntry(name, new ResourceEstimation(ram, cpu));
  }

  @Override
  public String getTypeDescription() {
    return "a 'name=value' assignment";
  }
}
