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

import java.io.IOException;
import java.net.MalformedURLException;
import javax.annotation.Nullable;

/**
 * Factory class responsible for the implementation of checking if a given resource is
 * provided to artifact compilation. It delegate to a android resource checker using either
 * jar or R.txt file as input.
 */
public class AndroidResourceCheckerFactory {

    @Nullable
    static AndroidResourceChecker create(ArtifactPathResolver pathResolver, Artifact artifact, boolean useRDotTxt) throws IOException, MalformedURLException {
        Path localResourcesArtifactPath = pathResolver.toPath(artifact);

        // Returns faster checker in case feature is enabled and R.txt is present locally.
        if (useRDotTxt) {
            Path rDotTxtPath = localResourcesArtifactPath.getParentDirectory()
            .getChild("src_release_symbols")
            .getChild("R.txt");

            if (rDotTxtPath.exists()) {
                return new AndroidResourceCheckerFromRDotTxt(rDotTxtPath);
            }
        }

        if (localResourcesArtifactPath.exists()) {
            return new AndroidResourceCheckerFromJar(localResourcesArtifactPath);
        }

        return null;
    }
}
