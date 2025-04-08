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

import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

import static com.google.devtools.build.lib.actions.usage.ActionInputUsageTracker.ANDROID_RESOURCES_NAMESPACE;

/**
 * Class responsible for checking resource presence by looking up resource jar file.
 */
public class AndroidResourceCheckerFromJar implements AndroidResourceChecker {

    private Path localResourcesArtifactPath;
    private URLClassLoader urlClassLoader;

    AndroidResourceCheckerFromJar(Path localResourcesArtifactPath) throws MalformedURLException {
        this.localResourcesArtifactPath = localResourcesArtifactPath;
        this.urlClassLoader = new URLClassLoader(new URL[]{ new URL("file:" + localResourcesArtifactPath) });
    }

    public boolean resourceExists(String resId) {
        String resourceName = resId.substring(resId.lastIndexOf('.') + 1);
        String resourceType = resId.substring(0, resId.lastIndexOf('.'));
        try {
            String resourceClassName = ANDROID_RESOURCES_NAMESPACE + resourceType.replace(".", "$");
            Class<?> resourceClass = urlClassLoader.loadClass(resourceClassName);
            Field f = resourceClass.getField(resourceName);
            return f != null;
        } catch (Exception e) {
            System.err.println("ActionInputUsageTracker: Lookup failed for " + resourceType + ":" + resourceName + " in " + localResourcesArtifactPath + " : " + e);
        }

        return false;
    }
}