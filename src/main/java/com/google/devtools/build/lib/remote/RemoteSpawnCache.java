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
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.SpawnCache;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.RemoteExecutionService.LocalExecution;
import com.google.devtools.build.lib.remote.RemoteExecutionService.RemoteActionResult;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.remote.util.Utils.InMemoryOutput;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/** A remote {@link SpawnCache} implementation. */
@ThreadSafe // If the RemoteActionCache implementation is thread-safe.
final class RemoteSpawnCache implements SpawnCache {

  private final Path execRoot;
  private final RemoteOptions options;
  private final RemoteExecutionService remoteExecutionService;
  private final DigestUtil digestUtil;
  private final boolean verboseFailures;
  private final ConcurrentHashMap<RemoteCacheClient.ActionKey, LocalExecution> inFlightExecutions =
      new ConcurrentHashMap<>();

  RemoteSpawnCache(
      Path execRoot,
      RemoteOptions options,
      boolean verboseFailures,
      RemoteExecutionService remoteExecutionService,
      DigestUtil digestUtil) {
    this.execRoot = execRoot;
    this.options = options;
    this.verboseFailures = verboseFailures;
    this.remoteExecutionService = remoteExecutionService;
    this.digestUtil = digestUtil;
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

    context.setDigest(digestUtil.asSpawnLogProto(action.getActionKey()));

    Profiler prof = Profiler.instance();
    LocalExecution thisExecution = null;
    if (shouldAcceptCachedResult) {
      // With path mapping enabled, different Spawns in a single build can have the same ActionKey.
      // When their result isn't in the cache and two of them are scheduled concurrently, neither
      // will result in a cache hit before the other finishes and uploads its result, which results
      // in unnecessary work. To avoid this, we keep track of in-flight executions as long as their
      // results haven't been uploaded to the cache yet and deduplicate all of them against the
      // first one.
      LocalExecution previousExecution = null;
      try {
        thisExecution = LocalExecution.createIfDeduplicatable(action);
        if (shouldUploadLocalResults && thisExecution != null) {
          LocalExecution previousOrThisExecution =
              inFlightExecutions.merge(
                  action.getActionKey(),
                  thisExecution,
                  (existingExecution, thisExecutionArg) -> {
                    if (existingExecution.registerForOutputReuse()) {
                      return existingExecution;
                    } else {
                      // The existing execution has completed and its results may have already
                      // been modified by its action, so we can't deduplicate against it. Instead,
                      // start a new in-flight execution.
                      return thisExecutionArg;
                    }
                  });
          previousExecution =
              previousOrThisExecution == thisExecution ? null : previousOrThisExecution;
        }
        try {
          RemoteActionResult result;
          try (SilentCloseable c = prof.profileAction(
            ProfilerTask.REMOTE_CACHE_CHECK,
            action.getSpawn().getResourceOwner().getMnemonic(),
            "check cache hit",
            action.getActionKey().getDigest().getHash(),
            action.getSpawn().getResourceOwner().getOwner().getLabel() != null ? action.getSpawn().getResourceOwner().getOwner().getLabel().toString() : "")) {
              result = remoteExecutionService.lookupCache(action); // arteghem
          }
          // In case the remote cache returned a failed action (exit code != 0) we treat it as a
          // cache miss
          if (result != null && result.getExitCode() == 0) {
            Stopwatch fetchTime = Stopwatch.createStarted();
            InMemoryOutput inMemoryOutput;
            try (SilentCloseable c = prof.profileAction(
              REMOTE_DOWNLOAD,
              action.getSpawn().getResourceOwner().getMnemonic(),
              "download outputs",
              action.getActionKey().getDigest().getHash(),
              action.getSpawn().getResourceOwner().getOwner().getLabel() != null ? action.getSpawn().getResourceOwner().getOwner().getLabel().toString() : "")) {
                inMemoryOutput = remoteExecutionService.downloadOutputs(action, result);
            }
            fetchTime.stop();
            totalTime.stop();
            spawnMetrics
                .setFetchTimeInMs((int) fetchTime.elapsed().toMillis())
                .setTotalTimeInMs((int) totalTime.elapsed().toMillis())
                .setNetworkTimeInMs((int) action.getNetworkTime().getDuration().toMillis());
            SpawnResult spawnResult =
                createSpawnResult(
                    digestUtil,
                    action.getActionKey(),
                    result.getExitCode(),
                    /* cacheHit= */ true,
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
            String errorMessage = Utils.grpcAwareErrorMessage(e, verboseFailures);
            if (isNullOrEmpty(errorMessage)) {
              errorMessage = e.getClass().getSimpleName();
            }
            errorMessage = "Remote Cache: " + errorMessage;
            remoteExecutionService.report(Event.warn(errorMessage));
          }
        }
        if (previousExecution != null) {
          Stopwatch fetchTime = Stopwatch.createStarted();
          SpawnResult previousResult;
          try (SilentCloseable c = prof.profile(REMOTE_DOWNLOAD, "reuse outputs")) {
            previousResult =
                remoteExecutionService.waitForAndReuseOutputs(action, previousExecution);
          }
          if (previousResult != null) {
            spawnMetrics
                .setFetchTimeInMs((int) fetchTime.elapsed().toMillis())
                .setTotalTimeInMs((int) totalTime.elapsed().toMillis())
                .setNetworkTimeInMs((int) action.getNetworkTime().getDuration().toMillis());
            SpawnMetrics buildMetrics = spawnMetrics.build();
            return SpawnCache.success(
                new SpawnResult.DelegateSpawnResult(previousResult) {
                  @Override
                  public String getRunnerName() {
                    return "deduplicated";
                  }

                  @Override
                  public SpawnMetrics getMetrics() {
                    return buildMetrics;
                  }
                });
          }
          // If we reach here, the previous execution was not successful (it encountered an
          // exception or the spawn had an exit code != 0). Since it isn't possible to accurately
          // recreate the failure without rerunning the action, we fall back to running the action
          // locally. This means that we have introduced an unnecessary wait, but that can only
          // happen in the case of a failing build with --keep_going.
        }
      } finally {
        if (previousExecution != null) {
          previousExecution.unregister();
        }
      }
    }

    if (shouldUploadLocalResults) {
      final LocalExecution thisExecutionFinal = thisExecution;
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
          if (!remoteExecutionService.commitResultAndDecideWhetherToUpload(
              result, thisExecutionFinal)) {
            return;
          }

          if (options.experimentalGuardAgainstConcurrentChanges) {
            try (SilentCloseable c = prof.profile("RemoteCache.checkForConcurrentModifications")) {
              checkForConcurrentModifications();
            } catch (IOException | ForbiddenActionInputException e) {
              String msg =
                  "Skipping uploading outputs because of concurrent modifications "
                      + "with --experimental_guard_against_concurrent_changes enabled: "
                      + e.getMessage();
              remoteExecutionService.report(Event.warn(msg));
              return;
            }
          }

          // As soon as the result is in the cache, actions can get the result from it instead of
          // from the first in-flight execution. Not keeping in-flight executions around
          // indefinitely is important to avoid excessive memory pressure - Spawns can be very
          // large.
          remoteExecutionService.uploadOutputs(
              action, result, () -> inFlightExecutions.remove(action.getActionKey()));
          if (thisExecutionFinal != null
              && action.getSpawn().getResourceOwner().mayModifySpawnOutputsAfterExecution()) {
            // In this case outputs have been uploaded synchronously and the callback above has run,
            // so no new executions will be deduplicated against this one. We can safely await all
            // existing executions finish the reuse.
            // Note that while this call itself isn't interruptible, all operations it awaits are
            // interruptible.
            try (SilentCloseable c = prof.profile(REMOTE_DOWNLOAD, "await output reuse")) {
              thisExecutionFinal.awaitAllOutputReuse();
            }
          }
        }

        private void checkForConcurrentModifications()
            throws IOException, ForbiddenActionInputException {
          for (ActionInput input : action.getInputMap(true).values()) {
            if (input instanceof VirtualActionInput) {
              continue;
            }
            FileArtifactValue metadata = context.getInputMetadataProvider().getInputMetadata(input);
            Path path = execRoot.getRelative(input.getExecPath());
            if (metadata.wasModifiedSinceDigest(path)) {
              throw new IOException(path + " was modified during execution");
            }
          }
        }

        @Override
        public void close() {
          if (thisExecutionFinal != null) {
            thisExecutionFinal.cancel();
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
