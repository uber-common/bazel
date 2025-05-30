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

package com.google.devtools.build.lib.actions;

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import javax.annotation.Nullable;

/**
 * An exception indication that the execution of an action has failed OR could not be attempted OR
 * could not be finished OR had something else wrong.
 *
 * <p>The six main kinds of failure are broadly defined as follows:
 *
 * <p>USER_INPUT which means it had something to do with what the user told us to do. This failure
 * should satisfy the invariant that it would happen identically again if all other things are
 * equal.
 *
 * <p>ENVIRONMENT which is loosely defined as anything which is generally out of scope for a blaze
 * evaluation. As a rule of thumb, these are any errors would not necessarily happen again given
 * constant input.
 *
 * <p>INTERRUPTION conditions arise from being unable to complete an evaluation for whatever reason.
 *
 * <p>INTERNAL_ERROR would happen because of anything which arises from within blaze itself but is
 * generally unexpected to ever occur for any user input.
 *
 * <p>LOST_INPUT which means the failure occurred because the action expected to consume some input
 * that went missing. Although this seems similar to ENVIRONMENT, Blaze may know how to fix this
 * problem.
 *
 * <p>MISSING_DEP which means that a skyframe restart is necessary because a dependency was missing.
 *
 * <p>The class is a catch-all for both failures of actions and failures to evaluate actions
 * properly.
 *
 * <p>Invariably, all low level ExecExceptions are caught by various specific ConfigurationAction
 * classes and re-raised as ActionExecutionExceptions.
 */
public abstract class ExecException extends Exception {

  private final boolean catastrophe;

  public ExecException(String message, boolean catastrophe) {
    super(message);
    this.catastrophe = catastrophe;
  }

  public ExecException(String message) {
    this(message, false);
  }

  public ExecException(Throwable cause) {
    super(cause);
    this.catastrophe = false;
  }

  public ExecException(String message, @Nullable Throwable cause) {
    super(cause == null ? message : message + ": " + cause.getMessage(), cause);
    this.catastrophe = false;
  }

  /** Catastrophic exceptions should stop the build, even if --keep_going. */
  public boolean isCatastrophic() {
    return catastrophe;
  }

  protected String getMessageForActionExecutionException() {
    return getMessage();
  }

  protected abstract FailureDetail getFailureDetail(String message);
}
