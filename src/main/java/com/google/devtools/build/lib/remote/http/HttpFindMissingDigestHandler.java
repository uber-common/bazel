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

import static com.google.common.base.Preconditions.checkState;

import com.google.auth.Credentials;
import com.google.common.collect.ImmutableList;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;
import io.netty.util.internal.StringUtil;
import java.io.IOException;
import java.util.Map.Entry;

/** ChannelHandler for find missing digests api. */
final class HttpFindMissingDigestHandler extends AbstractHttpHandler<HttpResponse> {

  String path;

  public HttpFindMissingDigestHandler(
      Credentials credentials, ImmutableList<Entry<String, String>> extraHttpHeaders) {
    super(credentials, extraHttpHeaders);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  @Override
  protected void channelRead0(ChannelHandlerContext ctx, HttpResponse response) {
    if (!response.decoderResult().isSuccess()) {
      failAndClose(new IOException("Failed to parse the HTTP response."), ctx);
      return;
    }
    try {
      checkState(userPromise != null, "response before request");
      if (response.status().equals(HttpResponseStatus.OK)) {
        succeedAndResetUserPromise();
      } else {
        failAndResetUserPromise(new HttpException(response, null, null));
      }
    } finally {
      if (!HttpUtil.isKeepAlive(response)) {
        ctx.close();
      }
    }
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    checkState(userPromise == null, "handler can't be shared between pipelines.");
    userPromise = promise;
    if (!(msg instanceof FindMissingDigestCommand)) {
      failAndResetUserPromise(
          new IllegalArgumentException(
              "Unsupported message type: " + StringUtil.simpleClassName(msg)));
      return;
    }
    FindMissingDigestCommand cmd = (FindMissingDigestCommand) msg;
    path = constructPath(cmd.uri(), cmd.hash(), true);
    HttpRequest request = buildRequest(path, constructHost(cmd.uri()));
    addCredentialHeaders(request, cmd.uri());
    addExtraRemoteHeaders(request);
    addUserAgentHeader(request);
    ctx.writeAndFlush(request)
        .addListener(
            (f) -> {
              if (!f.isSuccess()) {
                failAndClose(f.cause(), ctx);
              }
            });
  }

  @Override
  @SuppressWarnings("deprecation")
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
    if (t instanceof ReadTimeoutException) {
      super.exceptionCaught(ctx, new FindMissingDigestTimeoutException(path));
    } else if (t instanceof TooLongFrameException) {
      super.exceptionCaught(ctx, new IOException(t));
    } else {
      super.exceptionCaught(ctx, t);
    }
  }

  private HttpRequest buildRequest(String path, String host) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, path);
    request.headers().set(HttpHeaderNames.HOST, host);
    request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    return request;
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  provate void succeedAndResetUserPromise() {
    userPromise.setSuccess();
    userPromise = null;
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void failAndClose(Throwable t, ChannelHandlerContext ctx) {
    try {
      failAndResetUserPromise(t);
    } finally {
      ctx.close();
    }
  }
}
