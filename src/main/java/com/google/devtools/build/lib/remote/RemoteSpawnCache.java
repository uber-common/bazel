// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.devtools.build.lib.profiler.ProfilerTask.REMOTE_DOWNLOAD;
import static com.google.devtools.build.lib.remote.util.Utils.createSpawnResult;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.SpawnCache;
import com.google.devtools.build.lib.exec.SpawnCheckingCacheEvent;
import com.google.devtools.build.lib.exec.SpawnExecutingEvent;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.RemoteExecutionService.RemoteActionResult;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.remote.util.Utils.InMemoryOutput;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.NoSuchElementException;

/** A remote {@link SpawnCache} implementation. */
@ThreadSafe // If the RemoteActionCache implementation is thread-safe.
final class RemoteSpawnCache implements SpawnCache {

  private static final SpawnCheckingCacheEvent SPAWN_CHECKING_CACHE_EVENT =
      SpawnCheckingCacheEvent.create("remote-cache");

  private static final SpawnExecutingEvent SPAWN_EXECUTING_EVENT =
      SpawnExecutingEvent.create("remote-cache");

  private final Path execRoot;
  private final RemoteOptions options;
  private final boolean verboseFailures;
  private final RemoteExecutionService remoteExecutionService;

  RemoteSpawnCache(
      Path execRoot,
      RemoteOptions options,
      boolean verboseFailures,
      RemoteExecutionService remoteExecutionService) {
    this.execRoot = execRoot;
    this.options = options;
    this.verboseFailures = verboseFailures;
    this.remoteExecutionService = remoteExecutionService;
  }

  @VisibleForTesting
  RemoteExecutionService getRemoteExecutionService() {
    return remoteExecutionService;
  }

  @Override
  public CacheHandle lookup(Spawn spawn, SpawnExecutionContext context)
      throws InterruptedException, IOException, ExecException, ForbiddenActionInputException {
    boolean shouldAcceptCachedResult =
        remoteExecutionService.getReadCachePolicy(spawn).allowAnyCache();
    boolean shouldUploadLocalResults =
        remoteExecutionService.getWriteCachePolicy(spawn).allowAnyCache();
    if (!shouldAcceptCachedResult && !shouldUploadLocalResults) {
      return SpawnCache.NO_RESULT_NO_STORE;
    }

    Stopwatch totalTime = Stopwatch.createStarted();

    RemoteAction action = remoteExecutionService.buildRemoteAction(spawn, context);
    SpawnMetrics.Builder spawnMetrics =
        SpawnMetrics.Builder.forRemoteExec()
            .setInputBytes(action.getInputBytes())
            .setInputFiles(action.getInputFiles());

    Profiler prof = Profiler.instance();
    if (shouldAcceptCachedResult) {
      context.report(SPAWN_CHECKING_CACHE_EVENT);
      // Metadata will be available in context.current() until we detach.
      // This is done via a thread-local variable.
      try {
        RemoteActionResult result;
        try (SilentCloseable c = prof.profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit for " + action.getActionKey().getDigest().getHash())) {
          result = remoteExecutionService.lookupCache(action);
        }

        String verb = (result != null && result.getExitCode() == 0) ? "HIT" : "MISS";
        if (options.remotePrintExecutionMessages == RemoteOptions.ExecutionMessagePrintMode.ALL) {
          System.out.println("RemoteSpawnCache: Lookup " + verb + " with key " + action.getActionKey().getDigest().getHash() + " for action " + action.getSpawn().getResourceOwner().getOwner().getLabel() + " [" + action.getSpawn().getResourceOwner().getMnemonic() + "]");
        }

        // In case the remote cache returned a failed action (exit code != 0) we treat it as a
        // cache miss
        if (result != null && result.getExitCode() == 0) {
          Stopwatch fetchTime = Stopwatch.createStarted();
          InMemoryOutput inMemoryOutput;
          try (SilentCloseable c = prof.profile(REMOTE_DOWNLOAD, "download outputs")) {
            inMemoryOutput = remoteExecutionService.downloadOutputs(action, result);
          }
          fetchTime.stop();
          totalTime.stop();
          spawnMetrics
              .setFetchTime(fetchTime.elapsed())
              .setTotalTime(totalTime.elapsed())
              .setNetworkTime(action.getNetworkTime().getDuration());
          SpawnResult spawnResult =
              createSpawnResult(
                  action.getActionKey(),
                  result.getExitCode(),
                  /*cacheHit=*/ true,
                  result.cacheName(),
                  inMemoryOutput,
                  result.getExecutionMetadata().getExecutionStartTimestamp(),
                  result.getExecutionMetadata().getExecutionCompletedTimestamp(),
                  spawnMetrics.build(),
                  spawn.getMnemonic());
          return SpawnCache.success(spawnResult);
        }
      } catch (CacheNotFoundException e) {
        // Intentionally left blank
      } catch (IOException e) {
        if (BulkTransferException.allCausedByCacheNotFoundException(e)) {
          // Intentionally left blank
        } else {
          String errorMessage;
          if (!verboseFailures) {
            errorMessage = Utils.grpcAwareErrorMessage(e);
          } else {
            // On --verbose_failures print the whole stack trace
            errorMessage = "\n" + Throwables.getStackTraceAsString(e);
          }
          if (isNullOrEmpty(errorMessage)) {
            errorMessage = e.getClass().getSimpleName();
          }
          errorMessage = "Remote Cache: " + errorMessage;
          remoteExecutionService.report(Event.warn(errorMessage));
        }
      }
    }

    context.prefetchInputsAndWait();

    context.report(SPAWN_EXECUTING_EVENT);

    if (shouldUploadLocalResults) {
      return new CacheHandle() {
        @Override
        public boolean hasResult() {
          return false;
        }

        @Override
        public SpawnResult getResult() {
          throw new NoSuchElementException();
        }

        @Override
        public boolean willStore() {
          return true;
        }

        @Override
        public void store(SpawnResult result) throws ExecException, InterruptedException {
          boolean uploadResults = Status.SUCCESS.equals(result.status()) && result.exitCode() == 0;
          if (!uploadResults) {
            return;
          }

          if (options.experimentalGuardAgainstConcurrentChanges) {
            try (SilentCloseable c = prof.profile("RemoteCache.checkForConcurrentModifications")) {
              checkForConcurrentModifications();
            } catch (IOException | ForbiddenActionInputException e) {
              String msg = "Skipping uploading outputs because of concurrent modifications " +
                  "with --experimental_guard_against_concurrent_changes enabled: " + e.getMessage();
              remoteExecutionService.report(Event.warn(msg));
              return;
            }
          }

          remoteExecutionService.uploadOutputs(action, result);
        }

        @Override
        public void close() {}

        private void checkForConcurrentModifications()
            throws IOException, ForbiddenActionInputException {
          for (ActionInput input : action.getInputMap(true).values()) {
            if (input instanceof VirtualActionInput) {
              continue;
            }
            FileArtifactValue metadata = context.getMetadataProvider().getMetadata(input);
            Path path = execRoot.getRelative(input.getExecPath());
            if (metadata.wasModifiedSinceDigest(path)) {
              throw new IOException(path + " was modified during execution");
            }
          }
        }
      };
    } else {
      return SpawnCache.NO_RESULT_NO_STORE;
    }
  }

  @Override
  public boolean usefulInDynamicExecution() {
    return false;
  }
}
