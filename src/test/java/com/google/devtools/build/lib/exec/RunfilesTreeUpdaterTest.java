// Copyright 2026 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.TestConstants.WORKSPACE_NAME;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.RunfileSymlinksMode;
import com.google.devtools.build.lib.analysis.util.FakeRunfilesTree;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.ByteArrayOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RunfilesTreeUpdater}. */
@RunWith(JUnit4.class)
public final class RunfilesTreeUpdaterTest {

  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path execRoot = fs.getPath("/execroot");
  private final ArtifactRoot outputRoot =
      ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out");
  private final PathFragment runfilesExecPath = PathFragment.create("out/tool.runfiles");
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
  private final OutErr outErr = OutErr.create(new ByteArrayOutputStream(), stderr);

  private Artifact file;
  private Path runfilesDir;
  private Path inputManifest;
  private Path outputManifest;

  @Before
  public void setUp() throws Exception {
    outputRoot.getRoot().asPath().createDirectoryAndParents();
    file = ActionsTestUtil.createArtifact(outputRoot, "data.txt");
    FileSystemUtils.writeContent(file.getPath(), ISO_8859_1, "data");
    runfilesDir = execRoot.getRelative(runfilesExecPath);
    inputManifest = execRoot.getRelative(RunfilesSupport.inputManifestExecPath(runfilesExecPath));
    outputManifest = execRoot.getRelative(RunfilesSupport.outputManifestExecPath(runfilesExecPath));
    FileSystemUtils.writeContent(inputManifest, ISO_8859_1, "manifest contents\n");
  }

  private RunfilesTree createTree(boolean buildRunfileLinks) {
    return new FakeRunfilesTree(
        runfilesExecPath,
        new Runfiles.Builder(WORKSPACE_NAME).addArtifact(file).build(),
        /* repoMappingManifest= */ null,
        RunfileSymlinksMode.INTERNAL,
        buildRunfileLinks);
  }

  /** Creates the tree the way SymlinkTreeStrategy does in INTERNAL mode. */
  private void createTreeLikeSymlinkTreeAction(RunfilesTree tree) throws Exception {
    runfilesDir.createDirectoryAndParents();
    new SymlinkTreeHelper(
            execRoot, inputManifest, outputManifest, runfilesDir, false, WORKSPACE_NAME)
        .createRunfilesSymlinksDirectly(tree.getMapping());
    outputManifest.createSymbolicLink(inputManifest);
  }

  private Path onlyRunfile(RunfilesTree tree) {
    return runfilesDir.getRelative(Iterables.getOnlyElement(tree.getMapping().keySet()));
  }

  private void update(RunfilesTree tree) throws Exception {
    new RunfilesTreeUpdater(
            execRoot, BinTools.forUnitTesting(execRoot, ImmutableList.of()), SyscallCache.NO_CACHE)
        .updateRunfiles(ImmutableList.of(tree), ImmutableMap.of(), outErr);
  }

  @Test
  public void buildRunfileLinks_restoresDeletedSymlink() throws Exception {
    RunfilesTree tree = createTree(/* buildRunfileLinks= */ true);
    createTreeLikeSymlinkTreeAction(tree);
    Path runfile = onlyRunfile(tree);
    runfile.delete();

    update(tree);

    assertThat(runfile.isSymbolicLink()).isTrue();
    assertThat(runfile.readSymbolicLink()).isEqualTo(file.getPath().asFragment());
    assertThat(outputManifest.isSymbolicLink()).isTrue();
    assertThat(outputManifest.readSymbolicLink()).isEqualTo(inputManifest.asFragment());
    assertThat(stderr.toString(ISO_8859_1)).contains("is missing entries, recreating it");
  }

  @Test
  public void buildRunfileLinks_leavesCompleteTreeAlone() throws Exception {
    RunfilesTree tree = createTree(/* buildRunfileLinks= */ true);
    createTreeLikeSymlinkTreeAction(tree);
    Path runfile = onlyRunfile(tree);
    long mtime = runfile.stat(Symlinks.NOFOLLOW).getLastModifiedTime();

    update(tree);

    assertThat(runfile.stat(Symlinks.NOFOLLOW).getLastModifiedTime()).isEqualTo(mtime);
    assertThat(stderr.toString(ISO_8859_1)).isEmpty();
  }

  @Test
  public void noBuildRunfileLinks_matchingManifestDoesNotHideDeletedSymlink() throws Exception {
    RunfilesTree tree = createTree(/* buildRunfileLinks= */ false);
    createTreeLikeSymlinkTreeAction(tree);
    // A copied output manifest with the same digest as the input manifest is what the up-to-date
    // shortcut trusts.
    outputManifest.delete();
    FileSystemUtils.copyFile(inputManifest, outputManifest);
    Path runfile = onlyRunfile(tree);
    runfile.delete();

    update(tree);

    assertThat(runfile.isSymbolicLink()).isTrue();
    assertThat(runfile.readSymbolicLink()).isEqualTo(file.getPath().asFragment());
  }
}
