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

package com.google.devtools.build.lib.remote.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.TimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Triggers {@link IdleState.ALL_IDLE} events, when no reads or writes were performed for a period
 * of time.
 */
public final class IdleTimeoutHandler extends IdleStateHandler {
  private final TimeoutException timeoutException;
  private boolean closed;

  public final static class WriteTimeoutReaderIdleException extends IOException {
    public static final WriteTimeoutReaderIdleException INSTANCE = new WriteTimeoutReaderIdleException();
    private WriteTimeoutReaderIdleException() {}
  }

  public final static class WriteTimeoutWriterIdleException extends IOException {
    public static final WriteTimeoutWriterIdleException INSTANCE = new WriteTimeoutWriterIdleException();
    private WriteTimeoutWriterIdleException() {}
  }

  public final static class ReadTimeoutReaderIdleException extends IOException {
    public static final ReadTimeoutReaderIdleException INSTANCE = new ReadTimeoutReaderIdleException();
    private ReadTimeoutReaderIdleException() {}
  }

  public final static class ReadTimeoutWriterIdleException extends IOException {
    public static final ReadTimeoutWriterIdleException INSTANCE = new ReadTimeoutWriterIdleException();
    private ReadTimeoutWriterIdleException() {}
  }

  @SuppressWarnings("GoodTime-ApiWithNumericTimeUnit")
  public IdleTimeoutHandler(long timeoutSeconds, TimeoutException timeoutException) {
    super(timeoutSeconds, timeoutSeconds, timeoutSeconds, TimeUnit.SECONDS);
    this.timeoutException = timeoutException;
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  protected final void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
    if (!closed) {
      switch (evt.state()) {
        case ALL_IDLE:
          ctx.fireExceptionCaught(timeoutException);
          break;
        case READER_IDLE:
          if (timeoutException == WriteTimeoutException.INSTANCE) {
            ctx.fireExceptionCaught(WriteTimeoutReaderIdleException.INSTANCE);
          }
          else if (timeoutException == ReadTimeoutException.INSTANCE) {
            ctx.fireExceptionCaught(ReadTimeoutReaderIdleException.INSTANCE);
          }
          break;
        case WRITER_IDLE:
          if (timeoutException == WriteTimeoutException.INSTANCE) {
            ctx.fireExceptionCaught(WriteTimeoutWriterIdleException.INSTANCE);
          }
          else if (timeoutException == ReadTimeoutException.INSTANCE) {
            ctx.fireExceptionCaught(ReadTimeoutWriterIdleException.INSTANCE);
          }
          break;
      }
      ctx.close();
      closed = true;
    }
  }
}
