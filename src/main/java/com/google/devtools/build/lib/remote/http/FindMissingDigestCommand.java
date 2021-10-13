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
package com.google.devtools.build.lib.remote.http;

import com.google.common.base.Preconditions;
import java.net.URI;

/** Object sent through the channel pipeline to query for existence of digest. */
final class FindMissingDigestCommand {

  private final URI uri;
  private final String hash;

  protected FindMissingDigestCommand(URI uri, String hash) {
    this.uri = Preconditions.checkNotNull(uri);
    this.hash = Preconditions.checkNotNull(hash);
  }

  public URI uri() {
    return uri;
  }

  public String hash() {
    return hash;
  }
}