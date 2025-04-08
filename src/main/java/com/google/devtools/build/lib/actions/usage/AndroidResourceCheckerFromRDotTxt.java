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
package com.google.devtools.build.lib.actions.usage;

import com.google.devtools.build.lib.actions.*;
import com.google.devtools.build.lib.vfs.Path;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Class responsible for checking resource presence by looking up R.txt file, which is faster than 
 * loading resource jar file.
 */
public class AndroidResourceCheckerFromRDotTxt implements AndroidResourceChecker {

    private static final boolean DEBUG = false;

    private Map<String, Set<String>> typeToResIds = new HashMap<>();

    AndroidResourceCheckerFromRDotTxt(Path rDotTxtPath) throws IOException {
        typeToResIds = parse(rDotTxtPath);

        if (DEBUG) {
            System.out.println("==============================================================");
            System.out.println("Parsed " + typeToResIds.size() + " types from " + rDotTxtPath);
            for (Map.Entry<String, Set<String>> entry : typeToResIds.entrySet()) {
                System.out.println(entry.getKey() + " -> " + entry.getValue());
            }
            System.out.println("==============================================================");
        }
    }

    public boolean resourceExists(String resId) {
        String resourceName = resId.substring(resId.lastIndexOf('.') + 1);
        String resourceType = resId.substring(0, resId.lastIndexOf('.')).replace("R.", "");

        if (typeToResIds.containsKey(resourceType)) {
            return typeToResIds.get(resourceType).contains(resourceName);
        }

        return false;
    }

    private Map parse(Path rDotTxtPath) throws IOException {
        Map result = new HashMap<String, Set<String>>();
        try (BufferedReader reader = new BufferedReader(new FileReader(rDotTxtPath.getPathFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) {
                    String type = parts[1];
                    String name = parts[2];
                    Set<String> set = (Set) result.computeIfAbsent(type, k -> new HashSet<String>());
                    set.add(name);
                }
            }
        }
        return result;
    }
}

