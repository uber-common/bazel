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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.actions.cache.ActionCache.Entry.SerializableTreeArtifactValue;
import com.google.devtools.build.lib.actions.cache.MetadataDigestUtils;
import com.google.devtools.build.lib.actions.cache.OutputMetadataStore;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics.MissReason;
import com.google.devtools.build.lib.actions.usage.ActionInputUsageTracker;
import com.google.devtools.build.lib.actions.usage.TrackingInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue.ArchivedRepresentation;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Checks whether an {@link Action} needs to be executed, or whether it has not changed since it was
 * last stored in the action cache. Must be informed of the new Action data after execution as well.
 *
 * <p>The fingerprint, input files names, and metadata (either mtimes or MD5sums) of each action are
 * cached in the action cache to avoid unnecessary rebuilds. Middleman artifacts are handled
 * specially, avoiding the need to create actual files corresponding to the middleman artifacts.
 * Instead of that, results of MiddlemanAction dependency checks are cached internally and then
 * reused whenever an input middleman artifact is encountered.
 *
 * <p>While instances of this class hold references to action and metadata cache instances, they are
 * otherwise lightweight, and should be constructed anew and discarded for each build request.
 */
public class ActionCacheChecker {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final ActionKeyContext actionKeyContext;
  private final Predicate<? super Action> executionFilter;
  private final ArtifactResolver artifactResolver;
  private final ActionInputUsageTracker actionInputUsageTracker;
  private final CacheConfig cacheConfig;

  @Nullable private final ActionCache actionCache; // Null when not enabled.

  /** Cache config parameters for ActionCacheChecker. */
  @AutoValue
  public abstract static class CacheConfig {
    abstract boolean enabled();
    // True iff --verbose_explanations flag is set.
    abstract boolean verboseExplanations();

    abstract boolean storeOutputMetadata();

    public static Builder builder() {
      return new AutoValue_ActionCacheChecker_CacheConfig.Builder();
    }

    /** Builder for ActionCacheChecker.CacheConfig. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setVerboseExplanations(boolean value);

      public abstract Builder setEnabled(boolean value);

      public abstract Builder setStoreOutputMetadata(boolean value);

      public abstract CacheConfig build();
    }
  }

  public ActionCacheChecker(
      @Nullable ActionCache actionCache,
      ArtifactResolver artifactResolver,
      ActionInputUsageTracker actionInputUsageTracker,
      ActionKeyContext actionKeyContext,
      Predicate<? super Action> executionFilter,
      @Nullable CacheConfig cacheConfig) {
    this.executionFilter = executionFilter;
    this.actionKeyContext = actionKeyContext;
    this.artifactResolver = artifactResolver;
    this.actionInputUsageTracker = actionInputUsageTracker;
    this.cacheConfig =
        cacheConfig != null
            ? cacheConfig
            : CacheConfig.builder()
                .setEnabled(true)
                .setVerboseExplanations(false)
                .setStoreOutputMetadata(false)
                .build();
    if (this.cacheConfig.enabled()) {
      this.actionCache = Preconditions.checkNotNull(actionCache);
    } else {
      this.actionCache = null;
    }
  }

  public boolean isActionExecutionProhibited(Action action) {
    return !executionFilter.apply(action);
  }

  /** Whether the action cache is enabled. */
  public boolean enabled() {
    return cacheConfig.enabled();
  }

  /**
   * Checks whether one of existing output paths is already used as a key. If yes, returns it -
   * otherwise uses first output file as a key
   */
  @Nullable
  private ActionCache.Entry getCacheEntry(Action action) {
    if (!cacheConfig.enabled()) {
      return null; // ignore existing cache when disabled.
    }
    return ActionCacheUtils.getCacheEntry(actionCache, action);
  }

  public void removeCacheEntry(Action action) {
    checkState(enabled(), "Action cache disabled");
    ActionCacheUtils.removeCacheEntry(actionCache, action);
  }

  @Nullable
  private static FileArtifactValue getCachedMetadata(
      @Nullable CachedOutputMetadata cachedOutputMetadata, Artifact artifact) {
    checkArgument(!artifact.isTreeArtifact());

    if (cachedOutputMetadata == null) {
      return null;
    }

    return cachedOutputMetadata.fileMetadata.get(artifact);
  }

  @Nullable
  private static TreeArtifactValue getCachedTreeMetadata(
      @Nullable CachedOutputMetadata cachedOutputMetadata, Artifact artifact) {
    checkArgument(artifact.isTreeArtifact());

    if (cachedOutputMetadata == null) {
      return null;
    }

    return cachedOutputMetadata.treeMetadata.get((SpecialArtifact) artifact);
  }

  /**
   * Validate metadata state for action input or output artifacts.
   *
   * @param entry cached action information.
   * @param action action to be validated.
   * @param actionInputs the inputs of the action. Normally just the result of action.getInputs(),
   *     but if this action doesn't yet know its inputs, we check the inputs from the cache.
   * @param outputMetadataStore provider of metadata for the action outputs.
   * @param checkOutput true to validate output artifacts, Otherwise, just validate inputs.
   * @param cachedOutputMetadata a set of cached metadata that should be used instead of loading
   *     from {@code outputMetadataStore}.
   * @param remoteArtifactChecker used to check whether remote metadata should be trusted.
   * @return true if at least one artifact has changed, false - otherwise.
   */
  private boolean validateArtifacts(
      ActionCache.Entry entry,
      Action action,
      NestedSet<Artifact> actionInputs,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      boolean checkOutput,
      @Nullable CachedOutputMetadata cachedOutputMetadata,
      @Nullable RemoteArtifactChecker remoteArtifactChecker)
      throws InterruptedException {
    Map<String, FileArtifactValue> mdMap = new HashMap<>();
    if (checkOutput) {
      for (Artifact artifact : action.getOutputs()) {
        if (artifact.isTreeArtifact()) {
          TreeArtifactValue treeMetadata = getCachedTreeMetadata(cachedOutputMetadata, artifact);
          if (treeMetadata == null) {
            treeMetadata = getOutputTreeMetadataMaybe(outputMetadataStore, artifact);
          }
          if (shouldTrustTreeMetadata(artifact, treeMetadata, remoteArtifactChecker)) {
            mdMap.put(
                artifact.getExecPathString(),
                treeMetadata != null ? treeMetadata.getMetadata() : null);
          } else {
            mdMap.put(artifact.getExecPathString(), null);
          }

        } else {
          FileArtifactValue metadata = getCachedMetadata(cachedOutputMetadata, artifact);
          if (metadata == null) {
            metadata = getOutputMetadataMaybe(outputMetadataStore, artifact);
          }
          if (shouldTrustMetadata(artifact, metadata, remoteArtifactChecker)) {
            mdMap.put(artifact.getExecPathString(), metadata);
          } else {
            mdMap.put(artifact.getExecPathString(), null);
          }
        }
      }
    }

    for (Artifact artifact : actionInputs.toList()) {
      // Request tracking info with fresh hashes from direct dependencies, since we want to compare
      // these against the hashes that were used at previous compilation time.
      TrackingInfo trackingInfo = actionInputUsageTracker.getTrackingInfo(action, artifact, true);

      if (trackingInfo.isUnused()) {
        // Input artifact is not used, exclude it from action cache key computation
        continue;
      } else if (trackingInfo.tracksUsedResources()) {
        // Make any Android resource used in code part of cache key computation
        trackingInfo.getUsedResources().forEach(usedResource -> {
          mdMap.put(usedResource, null);
        });
      } else if (trackingInfo.tracksUsedClasses()) {
        // A subset of input artifact is used, include individual used classes for action cache key computation
        trackingInfo.getUsedClasses().forEach(usedClass ->
          mdMap.put(usedClass.getInternalPath(), usedClass.getDependencyFileArtifactValue())
        );
      } else {
        // Standard case, track input artifact itself
        mdMap.put(artifact.getExecPathString(), getInputMetadataMaybe(inputMetadataProvider, artifact));
      }
    }

    // Output debug info about action if needed
    if (actionInputUsageTracker.supportsInputTracking(action) && false) {
      System.out.println(actionInputUsageTracker.dump(action));
    }

    return !Arrays.equals(MetadataDigestUtils.fromMetadata(mdMap), entry.getFileDigest());
  }

  private static boolean shouldTrustMetadata(
      Artifact artifact,
      @Nullable FileArtifactValue metadata,
      @Nullable RemoteArtifactChecker remoteArtifactChecker) {
    checkArgument(!artifact.isTreeArtifact());
    if (remoteArtifactChecker == null || metadata == null || !metadata.isRemote()) {
      return true;
    }
    return remoteArtifactChecker.shouldTrustRemoteArtifact(
        artifact, (RemoteFileArtifactValue) metadata);
  }

  private static boolean shouldTrustTreeMetadata(
      Artifact artifact,
      @Nullable TreeArtifactValue treeMetadata,
      @Nullable RemoteArtifactChecker remoteArtifactChecker) {
    checkArgument(artifact.isTreeArtifact());
    if (remoteArtifactChecker == null || treeMetadata == null) {
      return true;
    }
    if (treeMetadata.getArchivedRepresentation().isPresent()) {
      ArchivedTreeArtifact archivedArtifact =
          treeMetadata
              .getArchivedRepresentation()
              .map(ArchivedRepresentation::archivedTreeFileArtifact)
              .orElseThrow();
      FileArtifactValue archivedMetadata =
          treeMetadata
              .getArchivedRepresentation()
              .map(ArchivedRepresentation::archivedFileValue)
              .orElseThrow();
      if (archivedMetadata.isRemote()
          && !remoteArtifactChecker.shouldTrustRemoteArtifact(
              archivedArtifact, (RemoteFileArtifactValue) archivedMetadata)) {
        return false;
      }
    }
    for (Map.Entry<TreeFileArtifact, FileArtifactValue> entry :
        treeMetadata.getChildValues().entrySet()) {
      TreeFileArtifact child = entry.getKey();
      FileArtifactValue childMetadata = entry.getValue();
      if (!childMetadata.isRemote()) {
        continue;
      }
      if (!remoteArtifactChecker.shouldTrustRemoteArtifact(
          child, (RemoteFileArtifactValue) childMetadata)) {
        return false;
      }
    }
    return true;
  }

  private void reportCommand(EventHandler handler, Action action) {
    if (handler != null) {
      if (cacheConfig.verboseExplanations()) {
        String keyDescription = action.describeKey();
        String execPlatform =
            action.getExecutionPlatform() == null
                ? "<null>"
                : action.getExecutionPlatform().toString();
        String execProps = action.getExecProperties().toString();
        reportRebuild(
            handler,
            action,
            keyDescription == null
                ? "action command has changed"
                : "action command has changed.\n"
                    + "New action: "
                    + keyDescription // keyDescription ends with newline already.
                    + "    Platform: "
                    + execPlatform
                    + "\n"
                    + "    Exec Properties: "
                    + execProps
                    + "\n");
      } else {
        reportRebuild(
            handler,
            action,
            "action command has changed (try --verbose_explanations for more info)");
      }
    }
  }

  private void reportClientEnv(EventHandler handler, Action action, Map<String, String> used) {
    if (handler != null) {
      if (cacheConfig.verboseExplanations()) {
        StringBuilder message = new StringBuilder();
        message.append("Effective client environment has changed. Now using\n");
        for (Map.Entry<String, String> entry : used.entrySet()) {
          message
              .append("  ")
              .append(entry.getKey())
              .append("=")
              .append(entry.getValue())
              .append("\n");
        }
        reportRebuild(handler, action, message.toString());
      } else {
        reportRebuild(
            handler,
            action,
            "Effective client environment has changed (try --verbose_explanations for more info)");
      }
    }
  }

  private boolean unconditionalExecution(Action action) {
    return !isActionExecutionProhibited(action) && action.executeUnconditionally();
  }

  private static Map<String, String> computeUsedExecProperties(
      Action action, Map<String, String> execProperties) {
    return action.getExecProperties().isEmpty() ? execProperties : action.getExecProperties();
  }

  private static Map<String, String> computeUsedClientEnv(
      Action action, Map<String, String> clientEnv) {
    Map<String, String> used = new HashMap<>();
    for (String var : action.getClientEnvironmentVariables()) {
      String value = clientEnv.get(var);
      if (value != null) {
        used.put(var, value);
      }
    }
    return used;
  }

  private static Map<String, String> computeUsedEnv(
      Action action,
      Map<String, String> clientEnv,
      Map<String, String> remoteDefaultPlatformProperties) {
    Map<String, String> usedClientEnv = computeUsedClientEnv(action, clientEnv);
    Map<String, String> usedExecProperties =
        computeUsedExecProperties(action, remoteDefaultPlatformProperties);
    // Combining the Client environment with the Remote Default Execution Properties and Output
    // Permissions, because the Miss Reason is not used currently by Bazel, therefore there is no
    // need to distinguish between these property types. This also saves memory used for the Action
    // Cache.
    Map<String, String> usedEnvironment = new HashMap<>();
    usedEnvironment.putAll(usedClientEnv);
    usedEnvironment.putAll(usedExecProperties);
    return usedEnvironment;
  }

  /**
   * The currently cached outputs when output metadata is stored (i.e., {@code
   * CacheConfig#shouldStoreOutputMetadata}).
   *
   * <p>Metadata retrieved from the filesystem overrides the cached metadata. This way, an action
   * will not be rerun if the cached metadata is still valid, unless the filesystem state needs to
   * be updated.
   */
  private static class CachedOutputMetadata {
    private final ImmutableMap<Artifact, FileArtifactValue> fileMetadata;
    private final ImmutableMap<SpecialArtifact, TreeArtifactValue> treeMetadata;

    private CachedOutputMetadata(
        ImmutableMap<Artifact, FileArtifactValue> fileMetadata,
        ImmutableMap<SpecialArtifact, TreeArtifactValue> treeMetadata) {
      this.fileMetadata = fileMetadata;
      this.treeMetadata = treeMetadata;
    }
  }

  private static CachedOutputMetadata loadCachedOutputMetadata(
      Action action, ActionCache.Entry entry, OutputMetadataStore outputMetadataStore)
      throws InterruptedException {
    ImmutableMap.Builder<Artifact, FileArtifactValue> mergedFileMetadata = ImmutableMap.builder();
    ImmutableMap.Builder<SpecialArtifact, TreeArtifactValue> mergedTreeMetadata =
        ImmutableMap.builder();

    for (Artifact artifact : action.getOutputs()) {
      if (artifact.isTreeArtifact()) {
        SpecialArtifact parent = (SpecialArtifact) artifact;
        SerializableTreeArtifactValue cachedTreeMetadata = entry.getOutputTree(parent);
        if (cachedTreeMetadata == null) {
          continue;
        }

        Map<TreeFileArtifact, FileArtifactValue> childValues = new HashMap<>();
        // Load remote child file metadata from cache.
        cachedTreeMetadata
            .childValues()
            .forEach(
                (key, value) ->
                    childValues.put(TreeFileArtifact.createTreeOutput(parent, key), value));

        Optional<ArchivedRepresentation> archivedRepresentation =
            cachedTreeMetadata
                .archivedFileValue()
                .map(
                    fileArtifactValue ->
                        ArchivedRepresentation.create(
                            ArchivedTreeArtifact.createForTree(parent), fileArtifactValue));

        TreeArtifactValue filesystemTreeMetadata;
        try {
          filesystemTreeMetadata = outputMetadataStore.getTreeArtifactValue(parent);
        } catch (FileNotFoundException ignored) {
          filesystemTreeMetadata = null;
        } catch (IOException e) {
          // Ignore the cached metadata if we encountered an error when loading its counterpart from
          // the filesystem.
          logger.atWarning().withCause(e).log("Failed to load metadata for %s", parent);
          continue;
        }

        if (filesystemTreeMetadata != null) {
          // Filesystem metadata overrides the cached metadata.
          childValues.putAll(filesystemTreeMetadata.getChildValues());
          if (filesystemTreeMetadata.getArchivedRepresentation().isPresent()) {
            archivedRepresentation = filesystemTreeMetadata.getArchivedRepresentation();
          }
        }

        TreeArtifactValue.Builder merged = TreeArtifactValue.newBuilder(parent);
        childValues.forEach(merged::putChild);
        archivedRepresentation.ifPresent(merged::setArchivedRepresentation);

        mergedTreeMetadata.put(parent, merged.build());
      } else {
        RemoteFileArtifactValue cachedMetadata = entry.getOutputFile(artifact);
        if (cachedMetadata == null) {
          continue;
        }

        FileArtifactValue filesystemMetadata;
        try {
          filesystemMetadata = getOutputMetadataOrConstant(outputMetadataStore, artifact);
        } catch (FileNotFoundException ignored) {
          filesystemMetadata = null;
        } catch (IOException e) {
          // Ignore the cached metadata if we encountered an error when loading its counterpart from
          // the filesystem.
          logger.atWarning().withCause(e).log("Failed to load metadata for %s", artifact);
          continue;
        }

        // Filesystem metadata overrides the cached metadata.
        mergedFileMetadata.put(
            artifact, filesystemMetadata != null ? filesystemMetadata : cachedMetadata);
      }
    }

    return new CachedOutputMetadata(
        mergedFileMetadata.buildOrThrow(), mergedTreeMetadata.buildOrThrow());
  }

  /**
   * Checks whether {@code action} needs to be executed and returns a non-null Token if so.
   *
   * <p>The method checks if any of the action's inputs or outputs have changed. Returns a non-null
   * {@link Token} if the action needs to be executed, and null otherwise.
   *
   * <p>If this method returns non-null, indicating that the action will be executed, the {@code
   * outputMetadataStore} must have any cached metadata cleared so that it does not serve stale
   * metadata for the action's outputs after the action is executed.
   */
  // Note: the handler should only be used for DEPCHECKER events; there's no
  // guarantee it will be available for other events.
  @Nullable
  public Token getTokenIfNeedToExecute(
      Action action,
      List<Artifact> resolvedCacheArtifacts,
      Map<String, String> clientEnv,
      OutputPermissions outputPermissions,
      EventHandler handler,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      ArtifactExpander artifactExpander,
      Map<String, String> remoteDefaultPlatformProperties,
      @Nullable RemoteArtifactChecker remoteArtifactChecker)
      throws InterruptedException {
    // TODO(bazel-team): (2010) For RunfilesAction/SymlinkAction and similar actions that
    // produce only symlinks we should not check whether inputs are valid at all - all that matters
    // that inputs and outputs are still exist (and new inputs have not appeared). All other checks
    // are unnecessary. In other words, the only metadata we should check for them is file existence
    // itself.

    if (action.getActionType().isMiddleman()) {
      checkMiddlemanAction(action, handler, inputMetadataProvider, outputMetadataStore);
      return null;
    }
    if (!cacheConfig.enabled()) {
      return new Token(getKeyString(action));
    }
    NestedSet<Artifact> actionInputs = action.getInputs();
    // Resolve action inputs from cache, if necessary.
    boolean inputsKnown = action.inputsKnown();
    if (!inputsKnown && resolvedCacheArtifacts != null) {
      // The action doesn't know its inputs, but the caller has a good idea of what they are.
      checkState(
          action.discoversInputs(),
          "Actions that don't know their inputs must discover them: %s",
          action);
      if (action instanceof ActionCacheAwareAction
          && ((ActionCacheAwareAction) action).storeInputsExecPathsInActionCache()) {
        actionInputs = NestedSetBuilder.wrap(Order.STABLE_ORDER, resolvedCacheArtifacts);
      } else {
        actionInputs =
            NestedSetBuilder.<Artifact>stableOrder()
                .addTransitive(action.getMandatoryInputs())
                .addAll(resolvedCacheArtifacts)
                .build();
      }
    }
    ActionCache.Entry entry = getCacheEntry(action);
    CachedOutputMetadata cachedOutputMetadata = null;
    if (entry != null && !entry.isCorrupted() && cacheConfig.storeOutputMetadata()) {
      cachedOutputMetadata = loadCachedOutputMetadata(action, entry, outputMetadataStore);
    }

    // Load tracking info from action output .jdeps file
    actionInputUsageTracker.refreshInputTrackingInfo(action);

    if (mustExecute(
        action,
        entry,
        handler,
        inputMetadataProvider,
        outputMetadataStore,
        artifactExpander,
        actionInputs,
        clientEnv,
        outputPermissions,
        remoteDefaultPlatformProperties,
        cachedOutputMetadata,
        remoteArtifactChecker)) {
      if (entry != null) {
        removeCacheEntry(action);
      }
      return new Token(getKeyString(action));
    }

    if (!inputsKnown) {
      action.updateInputs(actionInputs);
    }

    // Inject cached output metadata if we have an action cache hit.
    if (cachedOutputMetadata != null) {
      cachedOutputMetadata.fileMetadata.forEach(outputMetadataStore::injectFile);
      cachedOutputMetadata.treeMetadata.forEach(outputMetadataStore::injectTree);
    }

    return null;
  }

  private boolean mustExecute(
      Action action,
      @Nullable ActionCache.Entry entry,
      EventHandler handler,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      ArtifactExpander artifactExpander,
      NestedSet<Artifact> actionInputs,
      Map<String, String> clientEnv,
      OutputPermissions outputPermissions,
      Map<String, String> remoteDefaultPlatformProperties,
      @Nullable CachedOutputMetadata cachedOutputMetadata,
      @Nullable RemoteArtifactChecker remoteArtifactChecker)
      throws InterruptedException {
    // Unconditional execution can be applied only for actions that are allowed to be executed.
    if (unconditionalExecution(action)) {
      checkState(action.isVolatile());
      reportUnconditionalExecution(handler, action);
      actionCache.accountMiss(MissReason.UNCONDITIONAL_EXECUTION);
      return true;
    }
    if (entry == null) {
      reportNewAction(handler, action);
      actionCache.accountMiss(MissReason.NOT_CACHED);
      return true;
    }

    if (entry.isCorrupted()) {
      reportCorruptedCacheEntry(handler, action);
      actionCache.accountMiss(MissReason.CORRUPTED_CACHE_ENTRY);
      return true;
    } else if (validateArtifacts(
        entry,
        action,
        actionInputs,
        inputMetadataProvider,
        outputMetadataStore,
        true,
        cachedOutputMetadata,
        remoteArtifactChecker)) {
      reportChanged(handler, action);
      actionCache.accountMiss(MissReason.DIFFERENT_FILES);
      return true;
    } else if (!entry.getActionKey().equals(action.getKey(actionKeyContext, artifactExpander))) {
      reportCommand(handler, action);
      actionCache.accountMiss(MissReason.DIFFERENT_ACTION_KEY);
      return true;
    }
    Map<String, String> usedEnvironment =
        computeUsedEnv(action, clientEnv, remoteDefaultPlatformProperties);
    if (!entry.sameActionProperties(usedEnvironment, outputPermissions)) {
      reportClientEnv(handler, action, usedEnvironment);
      actionCache.accountMiss(MissReason.DIFFERENT_ENVIRONMENT);
      return true;
    }

    entry.getFileDigest();
    actionCache.accountHit();
    return false;
  }

  private static FileArtifactValue getInputMetadataOrConstant(
      InputMetadataProvider inputMetadataProvider, Artifact artifact) throws IOException {
    FileArtifactValue metadata = inputMetadataProvider.getInputMetadata(artifact);
    return (metadata != null && artifact.isConstantMetadata())
        ? ConstantMetadataValue.INSTANCE
        : metadata;
  }

  private static FileArtifactValue getOutputMetadataOrConstant(
      OutputMetadataStore outputMetadataStore, Artifact artifact)
      throws IOException, InterruptedException {
    FileArtifactValue metadata = outputMetadataStore.getOutputMetadata(artifact);
    return (metadata != null && artifact.isConstantMetadata())
        ? ConstantMetadataValue.INSTANCE
        : metadata;
  }

  // TODO(ulfjack): It's unclear to me why we're ignoring all IOExceptions. In some cases, we want
  // to trigger a re-execution, so we should catch the IOException explicitly there. In others, we
  // should propagate the exception, because it is unexpected (e.g., bad file system state).
  @Nullable
  private static FileArtifactValue getInputMetadataMaybe(
      InputMetadataProvider inputMetadataProvider, Artifact artifact) {
    try {
      return getInputMetadataOrConstant(inputMetadataProvider, artifact);
    } catch (IOException e) {
      return null;
    }
  }

  // TODO(ulfjack): It's unclear to me why we're ignoring all IOExceptions. In some cases, we want
  // to trigger a re-execution, so we should catch the IOException explicitly there. In others, we
  // should propagate the exception, because it is unexpected (e.g., bad file system state).
  @Nullable
  private static FileArtifactValue getOutputMetadataMaybe(
      OutputMetadataStore outputMetadataStore, Artifact artifact) throws InterruptedException {
    checkArgument(!artifact.isTreeArtifact());
    try {
      return getOutputMetadataOrConstant(outputMetadataStore, artifact);
    } catch (IOException e) {
      return null;
    }
  }

  @Nullable
  private static TreeArtifactValue getOutputTreeMetadataMaybe(
      OutputMetadataStore outputMetadataStore, Artifact artifact) throws InterruptedException {
    checkArgument(artifact.isTreeArtifact());
    try {
      return outputMetadataStore.getTreeArtifactValue((SpecialArtifact) artifact);
    } catch (IOException e) {
      return null;
    }
  }

  public void updateActionCache(
      Action action,
      Token token,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      ArtifactExpander artifactExpander,
      Map<String, String> clientEnv,
      OutputPermissions outputPermissions,
      Map<String, String> remoteDefaultPlatformProperties)
      throws IOException, InterruptedException {
    checkState(cacheConfig.enabled(), "cache unexpectedly disabled, action: %s", action);
    Preconditions.checkArgument(token != null, "token unexpectedly null, action: %s", action);
    String key = token.cacheKey;
    if (actionCache.get(key) != null) {
      // This cache entry has already been updated by a shared action. We don't need to do it again.
      return;
    }

    // Action has been executed, and output .jdeps file has been re-generated, so we need to update tracking info.
    actionInputUsageTracker.refreshInputTrackingInfo(action);

    Map<String, String> usedEnvironment =
        computeUsedEnv(action, clientEnv, remoteDefaultPlatformProperties);
    ActionCache.Entry entry =
        new ActionCache.Entry(
            action.getKey(actionKeyContext, artifactExpander),
            usedEnvironment,
            action.discoversInputs(),
            outputPermissions);
    for (Artifact output : action.getOutputs()) {
      // Remove old records from the cache if they used different key.
      String execPath = output.getExecPathString();
      if (!key.equals(execPath)) {
        actionCache.remove(execPath);
      }
      if (!outputMetadataStore.artifactOmitted(output)) {
        if (output.isTreeArtifact()) {
          SpecialArtifact parent = (SpecialArtifact) output;
          TreeArtifactValue metadata = outputMetadataStore.getTreeArtifactValue(parent);
          entry.addOutputTree(parent, metadata, cacheConfig.storeOutputMetadata());
        } else {
          // Output files *must* exist and be accessible after successful action execution. We use
          // the 'constant' metadata for the volatile workspace status output. The volatile output
          // contains information such as timestamps, and even when --stamp is enabled, we don't
          // want to rebuild everything if only that file changes.
          FileArtifactValue metadata = getOutputMetadataOrConstant(outputMetadataStore, output);
          checkState(metadata != null);
          entry.addOutputFile(output, metadata, cacheConfig.storeOutputMetadata());
        }
      }
    }

    boolean storeAllInputsInActionCache =
        action instanceof ActionCacheAwareAction
            && ((ActionCacheAwareAction) action).storeInputsExecPathsInActionCache();
    ImmutableSet<Artifact> excludePathsFromActionCache =
        !storeAllInputsInActionCache && action.discoversInputs()
            ? action.getMandatoryInputs().toSet()
            : ImmutableSet.of();

    for (Artifact input : action.getInputs().toList()) {
      TrackingInfo trackingInfo = actionInputUsageTracker.getTrackingInfo(action, input, false);

      entry.addInputFile(
          input.getExecPath(),
          getInputMetadataMaybe(inputMetadataProvider, input),
          /* saveExecPath= */ !excludePathsFromActionCache.contains(input),
          /* excludeFromDigest= */ trackingInfo.isUnused() || trackingInfo.tracksUsedClasses() || trackingInfo.tracksUsedResources());

      if (trackingInfo.tracksUsedResources()) {
        trackingInfo.getUsedResources().forEach(usedResource -> {
          entry.addHash(usedResource, null);
        });
      } else if (trackingInfo.tracksUsedClasses()) {
        // A subset of input artifact is used, include individual used classes to action cache key computation
        trackingInfo.getUsedClasses().forEach(usedClass ->
                entry.addHash(usedClass.getInternalPath(), usedClass.getCompileTimeFileArtifactValue())
        );
      }
    }
    entry.getFileDigest();
    actionCache.put(key, entry);
  }

  @Nullable
  public List<Artifact> getCachedInputs(Action action, PackageRootResolver resolver)
      throws PackageRootResolver.PackageRootException, InterruptedException {
    ActionCache.Entry entry = getCacheEntry(action);
    if (entry == null || entry.isCorrupted()) {
      return ImmutableList.of();
    }

    List<PathFragment> outputs = new ArrayList<>();
    for (Artifact output : action.getOutputs()) {
      outputs.add(output.getExecPath());
    }
    List<PathFragment> inputExecPaths = new ArrayList<>();
    for (String path : entry.getPaths()) {
      PathFragment execPath = PathFragment.create(path);
      // Code assumes that action has only 1-2 outputs and ArrayList.contains() will be
      // most efficient.
      if (!outputs.contains(execPath)) {
        inputExecPaths.add(execPath);
      }
    }

    // Note that this method may trigger a violation of the desirable invariant that getInputs()
    // is a superset of getMandatoryInputs(). See bug about an "action not in canonical form"
    // error message and the integration test test_crosstool_change_and_failure().
    Map<PathFragment, Artifact> allowedDerivedInputsMap = new HashMap<>();
    for (Artifact derivedInput : action.getAllowedDerivedInputs().toList()) {
      if (!derivedInput.isSourceArtifact()) {
        allowedDerivedInputsMap.put(derivedInput.getExecPath(), derivedInput);
      }
    }

    ImmutableList.Builder<Artifact> inputArtifactsBuilder = ImmutableList.builder();
    List<PathFragment> unresolvedPaths = new ArrayList<>();
    for (PathFragment execPath : inputExecPaths) {
      Artifact artifact = allowedDerivedInputsMap.get(execPath);
      if (artifact != null) {
        inputArtifactsBuilder.add(artifact);
      } else {
        // Remember this execPath, we will try to resolve it as a source artifact.
        unresolvedPaths.add(execPath);
      }
    }

    Map<PathFragment, SourceArtifact> resolvedArtifacts =
        artifactResolver.resolveSourceArtifacts(unresolvedPaths, resolver);
    if (resolvedArtifacts == null) {
      // We are missing some dependencies. We need to rerun this update later.
      return null;
    }

    for (PathFragment execPath : unresolvedPaths) {
      Artifact artifact = resolvedArtifacts.get(execPath);
      // If PathFragment cannot be resolved into the artifact, ignore it. This could happen if the
      // rule has changed and the action no longer depends on, e.g., an additional source file in a
      // separate package and that package is no longer referenced anywhere else. It is safe to
      // ignore such paths because dependency checker would identify changes in inputs (ignored path
      // was used before) and will force action execution.
      if (artifact != null) {
        inputArtifactsBuilder.add(artifact);
      }
    }
    return inputArtifactsBuilder.build();
  }

  /**
   * Special handling for the MiddlemanAction. Since MiddlemanAction output artifacts are purely
   * fictional and used only to stay within dependency graph model limitations (action has to depend
   * on artifacts, not on other actions), we do not need to validate metadata for the outputs - only
   * for inputs. We also do not need to validate MiddlemanAction key, since action cache entry key
   * already incorporates that information for the middlemen and we will experience a cache miss
   * when it is different. Whenever it encounters middleman artifacts as input artifacts for other
   * actions, it consults with the aggregated middleman digest computed here.
   */
  private void checkMiddlemanAction(
      Action action,
      EventHandler handler,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore)
      throws InterruptedException {
    if (!cacheConfig.enabled()) {
      // Action cache is disabled, don't generate digests.
      return;
    }
    Artifact middleman = action.getPrimaryOutput();
    String cacheKey = middleman.getExecPathString();
    ActionCache.Entry entry = actionCache.get(cacheKey);
    boolean changed = false;
    if (entry != null) {
      if (entry.isCorrupted()) {
        reportCorruptedCacheEntry(handler, action);
        actionCache.accountMiss(MissReason.CORRUPTED_CACHE_ENTRY);
        changed = true;
      } else if (validateArtifacts(
          entry,
          action,
          action.getInputs(),
          inputMetadataProvider,
          outputMetadataStore,
          false,
          /* cachedOutputMetadata= */ null,
          /* remoteArtifactChecker= */ null)) {
        reportChanged(handler, action);
        actionCache.accountMiss(MissReason.DIFFERENT_FILES);
        changed = true;
      }
    } else {
      reportChangedDeps(handler, action);
      actionCache.accountMiss(MissReason.DIFFERENT_DEPS);
      changed = true;
    }
    if (changed) {
      // Compute the aggregated middleman digest.
      // Since we never validate action key for middlemen, we should not store
      // it in the cache entry and just use empty string instead.
      entry = new ActionCache.Entry("", ImmutableMap.of(), false, OutputPermissions.READONLY);
      for (Artifact input : action.getInputs().toList()) {
        entry.addInputFile(
            input.getExecPath(), getInputMetadataMaybe(inputMetadataProvider, input), /*saveExecPath=*/ true, false);
      }
    }

    outputMetadataStore.setDigestForVirtualArtifact(middleman, entry.getFileDigest());
    if (changed) {
      actionCache.put(cacheKey, entry);
    } else {
      actionCache.accountHit();
    }
  }

  /**
   * Only call if action requires execution because there was a failure to record action cache hit
   */
  public Token getTokenUnconditionallyAfterFailureToRecordActionCacheHit(
      Action action,
      List<Artifact> resolvedCacheArtifacts,
      Map<String, String> clientEnv,
      OutputPermissions outputPermissions,
      EventHandler handler,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      ArtifactExpander artifactExpander,
      Map<String, String> remoteDefaultPlatformProperties,
      @Nullable RemoteArtifactChecker remoteArtifactChecker)
      throws InterruptedException {
    if (action != null) {
      removeCacheEntry(action);
    }
    return getTokenIfNeedToExecute(
        action,
        resolvedCacheArtifacts,
        clientEnv,
        outputPermissions,
        handler,
        inputMetadataProvider,
        outputMetadataStore,
        artifactExpander,
        remoteDefaultPlatformProperties,
        remoteArtifactChecker);
  }

  /** Returns an action key. It is always set to the first output exec path string. */
  private static String getKeyString(Action action) {
    checkState(!action.getOutputs().isEmpty());
    return action.getOutputs().iterator().next().getExecPathString();
  }

  /**
   * In most cases, this method should not be called directly - reportXXX() methods should be used
   * instead. This is done to avoid cost associated with building the message.
   */
  private static void reportRebuild(@Nullable EventHandler handler, Action action, String message) {
    // For MiddlemanAction, do not report rebuild.
    if (handler != null && !action.getActionType().isMiddleman()) {
      handler.handle(
          Event.of(
              EventKind.DEPCHECKER,
              null,
              "Executing " + action.prettyPrint() + ": " + message + "."));
    }
  }

  private static void reportUnconditionalExecution(@Nullable EventHandler handler, Action action) {
    reportRebuild(handler, action, "unconditional execution is requested");
  }

  private static void reportChanged(@Nullable EventHandler handler, Action action) {
    reportRebuild(handler, action, "One of the files has changed");
  }

  private static void reportChangedDeps(@Nullable EventHandler handler, Action action) {
    reportRebuild(handler, action, "the set of files on which this action depends has changed");
  }

  private static void reportNewAction(@Nullable EventHandler handler, Action action) {
    reportRebuild(handler, action, "no entry in the cache (action is new)");
  }

  private static void reportCorruptedCacheEntry(@Nullable EventHandler handler, Action action) {
    reportRebuild(handler, action, "cache entry is corrupted");
  }

  /** Wrapper for all context needed by the ActionCacheChecker to handle a single action. */
  public static final class Token {
    private final String cacheKey;

    private Token(String cacheKey) {
      this.cacheKey = Preconditions.checkNotNull(cacheKey);
    }
  }

  private static final class ConstantMetadataValue extends FileArtifactValue
      implements FileArtifactValue.Singleton {
    static final ConstantMetadataValue INSTANCE = new ConstantMetadataValue();
    // This needs to not be of length 0, so it is distinguishable from a missing digest when written
    // into a Fingerprint.
    private static final byte[] DIGEST = new byte[1];

    private ConstantMetadataValue() {}

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return DIGEST;
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public long getModifiedTime() {
      return -1;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      throw new UnsupportedOperationException(
          "ConstantMetadataValue doesn't support wasModifiedSinceDigest " + path.toString());
    }
  }
}
