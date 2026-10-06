// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec;

import static com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.RunfileSymlinksMode.SKIP;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Striped;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.XattrProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Utility used in local execution to create a runfiles tree if {@code --nobuild_runfile_links} has
 * been specified, and to restore entries missing from a runfiles tree created during the build.
 *
 * <p>It is safe to call {@link #updateRunfiles} concurrently.
 */
@ThreadSafe
public class RunfilesTreeUpdater {
  /** Serializes repairs of the same runfiles tree across all instances. */
  private static final Striped<Lock> REPAIR_LOCKS = Striped.lock(64);

  private final Path execRoot;
  private final BinTools binTools;
  private final XattrProvider xattrProvider;

  /**
   * Deduplicates multiple attempts to update the same runfiles tree.
   *
   * <p>Attempts may occur concurrently, e.g. if multiple local actions have the same input.
   *
   * <p>The presence of an entry in the map signifies that an earlier attempt to update the
   * corresponding runfiles tree was started, and will (have) set the future upon completion.
   */
  private final ConcurrentHashMap<PathFragment, CompletableFuture<Void>> updatedTrees =
      new ConcurrentHashMap<>();

  public static RunfilesTreeUpdater forCommandEnvironment(CommandEnvironment env) {
    return new RunfilesTreeUpdater(
        env.getExecRoot(), env.getBlazeWorkspace().getBinTools(), env.getXattrProvider());
  }

  public RunfilesTreeUpdater(Path execRoot, BinTools binTools, XattrProvider xattrProvider) {
    this.execRoot = execRoot;
    this.binTools = binTools;
    this.xattrProvider = xattrProvider;
  }

  /** Creates or updates input runfiles trees for a spawn. */
  public void updateRunfiles(
      Iterable<RunfilesTree> runfilesTrees, ImmutableMap<String, String> env, OutErr outErr)
      throws ExecException, IOException, InterruptedException {
    for (RunfilesTree tree : runfilesTrees) {
      PathFragment runfilesDir = tree.getExecPath();

      var freshFuture = new CompletableFuture<Void>();
      CompletableFuture<Void> priorFuture = updatedTrees.putIfAbsent(runfilesDir, freshFuture);

      if (priorFuture == null) {
        // We are the first attempt; update the runfiles tree and mark the future complete.
        try {
          updateRunfilesTree(tree, env, outErr);
          freshFuture.complete(null);
        } catch (Exception e) {
          freshFuture.completeExceptionally(e);
          throw e;
        }
      } else {
        // There was a previous attempt; wait for it to complete.
        try {
          priorFuture.join();
        } catch (CompletionException e) {
          Throwable cause = e.getCause();
          if (cause != null) {
            Throwables.throwIfInstanceOf(cause, ExecException.class);
            Throwables.throwIfInstanceOf(cause, IOException.class);
            Throwables.throwIfInstanceOf(cause, InterruptedException.class);
            Throwables.throwIfUnchecked(cause);
          }
          throw new AssertionError("Unexpected exception", e);
        }
      }
    }
  }

  private void updateRunfilesTree(
      RunfilesTree tree, ImmutableMap<String, String> env, OutErr outErr)
      throws IOException, ExecException, InterruptedException {
    Path runfilesDir = execRoot.getRelative(tree.getExecPath());
    Path inputManifest =
        execRoot.getRelative(RunfilesSupport.inputManifestExecPath(tree.getExecPath()));
    if (!inputManifest.exists()) {
      return;
    }
    Path outputManifest =
        execRoot.getRelative(RunfilesSupport.outputManifestExecPath(tree.getExecPath()));
    if (tree.isBuildRunfileLinks()) {
      repairRunfilesTreeIfIncomplete(tree, runfilesDir, inputManifest, outputManifest, env, outErr);
      return;
    }
    try {
      // Avoid rebuilding the runfiles directory if the manifest in it matches the input manifest,
      // implying the symlinks exist and are already up to date. If the output manifest is a
      // symbolic link, it is likely a symbolic link to the input manifest, so we cannot trust it as
      // an up-to-date check.
      // On Windows, where symlinks may be silently replaced by copies, a previous run in SKIP mode
      // could have resulted in an output manifest that is an identical copy of the input manifest,
      // which we must not treat as up to date, but we also don't want to unnecessarily rebuild the
      // runfiles directory all the time. Instead, check for the presence of the first runfile in
      // the manifest. If it is present, we can be certain that the previous mode wasn't SKIP.
      if (tree.getSymlinksMode() != SKIP
          && !outputManifest.isSymbolicLink()
          && Arrays.equals(
              DigestUtils.getDigestWithManualFallback(outputManifest, xattrProvider),
              DigestUtils.getDigestWithManualFallback(inputManifest, xattrProvider))
          && (OS.getCurrent() != OS.WINDOWS
              || isRunfilesDirectoryPopulated(runfilesDir, outputManifest))
          && isRunfilesTreeComplete(runfilesDir, tree.getMapping())) {
        return;
      }
    } catch (IOException e) {
      // Ignore it - we will just try to create runfiles directory.
    }

    if (!runfilesDir.exists()) {
      runfilesDir.createDirectoryAndParents();
    }

    SymlinkTreeHelper helper =
        new SymlinkTreeHelper(
            execRoot,
            inputManifest,
            outputManifest,
            runfilesDir,
            /* filesetTree= */ false,
            tree.getWorkspaceName());

    switch (tree.getSymlinksMode()) {
      case SKIP -> helper.clearRunfilesDirectory();
      case EXTERNAL -> helper.createSymlinksUsingCommand(binTools, env, outErr);
      case INTERNAL -> {
        helper.createRunfilesSymlinksDirectly(tree.getMapping());
        outputManifest.createSymbolicLink(inputManifest);
      }
    }
  }

  /**
   * Restores entries missing from a runfiles tree that was created by a {@code SymlinkTreeAction}.
   *
   * <p>The output manifest is the only output of that action, so the action cache does not notice
   * symlinks that were deleted from the tree out-of-band (e.g. by a disk cleaner), not even with
   * {@code --experimental_check_output_files} or after a server restart. A spawn reading such a
   * symlink would fail.
   */
  private void repairRunfilesTreeIfIncomplete(
      RunfilesTree tree,
      Path runfilesDir,
      Path inputManifest,
      Path outputManifest,
      ImmutableMap<String, String> env,
      OutErr outErr)
      throws IOException, ExecException, InterruptedException {
    if (tree.getSymlinksMode() == SKIP) {
      return;
    }
    Map<PathFragment, Artifact> mapping = tree.getMapping();
    if (isRunfilesTreeComplete(runfilesDir, mapping)) {
      return;
    }

    // Other RunfilesTreeUpdater instances (e.g. the worker and standalone ones) may be repairing
    // the same tree concurrently.
    Lock lock = REPAIR_LOCKS.get(runfilesDir);
    lock.lock();
    try {
      if (isRunfilesTreeComplete(runfilesDir, mapping)) {
        return;
      }
      outErr.printErrLn(
          String.format(
              "Runfiles tree %s is missing entries, recreating it.", tree.getExecPath()));
      SymlinkTreeHelper helper =
          new SymlinkTreeHelper(
              execRoot,
              inputManifest,
              outputManifest,
              runfilesDir,
              /* filesetTree= */ false,
              tree.getWorkspaceName());
      switch (tree.getSymlinksMode()) {
        case SKIP -> throw new IllegalStateException("SKIP mode trees have no entries to repair");
        case EXTERNAL -> helper.createSymlinksUsingCommand(binTools, env, outErr);
        case INTERNAL -> {
          // This deletes the output manifest, as it is not part of the mapping. Recreate it the
          // same way as SymlinkTreeStrategy does.
          helper.createRunfilesSymlinksDirectly(mapping);
          outputManifest.delete();
          outputManifest.createSymbolicLink(inputManifest);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private static boolean isRunfilesTreeComplete(
      Path runfilesDir, Map<PathFragment, Artifact> mapping) {
    for (PathFragment runfile : mapping.keySet()) {
      // Don't follow symlinks: unresolved symlinks may legitimately dangle, and a missing target
      // is caught as a missing input before the spawn runs.
      if (!runfilesDir.getRelative(runfile).exists(Symlinks.NOFOLLOW)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isRunfilesDirectoryPopulated(Path runfilesDir, Path outputManifest) {
    String relativeRunfilePath;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(outputManifest.getInputStream(), ISO_8859_1))) {
      // If it is created at all, the manifest always contains at least one line.
      relativeRunfilePath = reader.readLine().split(" ", -1)[0];
    } catch (IOException e) {
      // Instead of failing outright, just assume the runfiles directory is not populated.
      return false;
    }
    // The runfile could be a dangling symlink.
    return runfilesDir.getRelative(relativeRunfilePath).exists(Symlinks.NOFOLLOW);
  }
}
