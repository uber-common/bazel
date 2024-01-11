// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static java.util.concurrent.TimeUnit.SECONDS;

import build.bazel.remote.execution.v2.ActionCacheUpdateCapabilities;
import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.DigestFunction;
import build.bazel.remote.execution.v2.ServerCapabilities;
import build.bazel.remote.execution.v2.SymlinkAbsolutePathStrategy;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.auth.Credentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialModule;
import com.google.devtools.build.lib.bazel.repository.downloader.Downloader;
import com.google.devtools.build.lib.bazel.repository.downloader.HttpDownloader;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.LocalFilesArtifactUploader;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.ExecutorBuilder;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry;
import com.google.devtools.build.lib.remote.RemoteServerCapabilities.ServerCapabilitiesRequirement;
import com.google.devtools.build.lib.remote.ToplevelArtifactsDownloader.PathToMetadataConverter;
import com.google.devtools.build.lib.remote.circuitbreaker.CircuitBreakerFactory;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.downloader.GrpcRemoteDownloader;
import com.google.devtools.build.lib.remote.http.HttpException;
import com.google.devtools.build.lib.remote.logging.LoggingInterceptor;
import com.google.devtools.build.lib.remote.options.RemoteBuildEventUploadMode;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TempPathGenerator;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.BlockWaitingModule;
import com.google.devtools.build.lib.runtime.BuildEventArtifactUploaderFactory;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.CommandLinePathFactory;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutorFactory;
import com.google.devtools.build.lib.runtime.ServerBuilder;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution.Code;
import com.google.devtools.build.lib.skyframe.MutableSupplier;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.io.AsynchronousFileOutputStream;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingResult;
import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** RemoteModule provides distributed cache and remote execution for Bazel. */
public final class RemoteModule extends BlazeModule {
  private static final CacheCapabilities HTTP_AND_DISK_CACHE_CAPABILITIES =
      CacheCapabilities.newBuilder()
          .setActionCacheUpdateCapabilities(
              ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build())
          .setSymlinkAbsolutePathStrategy(SymlinkAbsolutePathStrategy.Value.ALLOWED)
          .build();

  private final ListeningScheduledExecutorService retryScheduler =
      MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));

  @Nullable private AsynchronousFileOutputStream rpcLogFile;
  @Nullable private ExecutorService executorService;
  @Nullable private RemoteActionContextProvider actionContextProvider;
  @Nullable private RemoteActionInputFetcher actionInputFetcher;
  @Nullable private ToplevelArtifactsDownloader toplevelArtifactsDownloader;
  @Nullable private RemoteOptions remoteOptions;
  @Nullable private RemoteOutputService remoteOutputService;
  @Nullable private TempPathGenerator tempPathGenerator;
  @Nullable private BlockWaitingModule blockWaitingModule;
  @Nullable private ImmutableList<Pattern> patternsToDownload;

  private ChannelFactory channelFactory =
      new ChannelFactory() {
        @Override
        public ManagedChannel newChannel(
            String target,
            String proxy,
            AuthAndTLSOptions options,
            List<ClientInterceptor> interceptors)
            throws IOException {
          return GoogleAuthUtils.newChannel(
              executorService,
              target,
              proxy,
              options,
              interceptors.isEmpty() ? null : interceptors);
        }
      };

  private final BuildEventArtifactUploaderFactoryDelegate
      buildEventArtifactUploaderFactoryDelegate = new BuildEventArtifactUploaderFactoryDelegate();

  private final RepositoryRemoteExecutorFactoryDelegate repositoryRemoteExecutorFactoryDelegate =
      new RepositoryRemoteExecutorFactoryDelegate();

  private final MutableSupplier<Downloader> remoteDownloaderSupplier = new MutableSupplier<>();

  private CredentialModule credentialModule;

  @Override
  public void serverInit(OptionsParsingResult startupOptions, ServerBuilder builder) {
    builder.addBuildEventArtifactUploaderFactory(
        buildEventArtifactUploaderFactoryDelegate, "remote");
    builder.setRepositoryRemoteExecutorFactory(repositoryRemoteExecutorFactoryDelegate);
    builder.setDownloaderSupplier(remoteDownloaderSupplier);
  }

  /** Returns whether remote execution should be available. */
  public static boolean shouldEnableRemoteExecution(RemoteOptions options) {
    return !Strings.isNullOrEmpty(options.remoteExecutor);
  }

  /** Returns whether remote downloading should be available. */
  private static boolean shouldEnableRemoteDownloader(RemoteOptions options) {
    return !Strings.isNullOrEmpty(options.remoteDownloader);
  }

  @Nullable
  private static ServerCapabilities getAndVerifyServerCapabilities(
      RemoteOptions remoteOptions,
      ReferenceCountedChannel channel,
      CallCredentials credentials,
      RemoteRetrier retrier,
      CommandEnvironment env,
      DigestUtil digestUtil,
      ServerCapabilitiesRequirement requirement)
      throws AbruptExitException, IOException {
    RemoteServerCapabilities rsc =
        new RemoteServerCapabilities(
            remoteOptions.remoteInstanceName,
            channel,
            credentials,
            remoteOptions.remoteTimeout.getSeconds(),
            retrier);
    ServerCapabilities capabilities = null;
    try {
      capabilities = rsc.get(env.getBuildRequestId(), env.getCommandId().toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
    checkClientServerCompatibility(
        capabilities,
        remoteOptions,
        digestUtil.getDigestFunction(),
        env.getReporter(),
        requirement);
    return capabilities;
  }

  public static final Predicate<? super Exception> RETRIABLE_HTTP_ERRORS =
      e -> {
        boolean retry = false;
        if (e instanceof ClosedChannelException) {
          retry = true;
        } else if (e instanceof HttpException) {
          int status = ((HttpException) e).response().status().code();
          retry =
              status == HttpResponseStatus.INTERNAL_SERVER_ERROR.code()
                  || status == HttpResponseStatus.BAD_GATEWAY.code()
                  || status == HttpResponseStatus.SERVICE_UNAVAILABLE.code()
                  || status == HttpResponseStatus.GATEWAY_TIMEOUT.code();
        } else if (e instanceof IOException) {
          String msg = e.getMessage().toLowerCase();
          if (msg.contains("connection reset by peer")) {
            retry = true;
          } else if (msg.contains("operation timed out")) {
            retry = true;
          }
        } else {
          // Workaround for a netty bug: https://github.com/netty/netty/issues/11815. Remove this
          // once it is fixed in the upstream.
          if (e instanceof DecoderException
              && e.getMessage().endsWith("functions:OPENSSL_internal:BAD_DECRYPT")) {
            retry = true;
          }
        }
        return retry;
      };

  private void initHttpAndDiskCache(
      CommandEnvironment env,
      Credentials credentials,
      AuthAndTLSOptions authAndTlsOptions,
      RemoteOptions remoteOptions,
      DigestUtil digestUtil) {
    RemoteCacheClient cacheClient;
    try {
      Retrier.CircuitBreaker circuitBreaker =
        CircuitBreakerFactory.createCircuitBreaker(remoteOptions);
      cacheClient =
          RemoteCacheClientFactory.create(
              remoteOptions,
              credentials,
              authAndTlsOptions,
              Preconditions.checkNotNull(env.getWorkingDirectory(), "workingDirectory"),
              digestUtil,
              new RemoteRetrier(
                  remoteOptions, RETRIABLE_HTTP_ERRORS, retryScheduler, circuitBreaker));
    } catch (IOException e) {
      handleInitFailure(env, e, Code.CACHE_INIT_FAILURE);
      return;
    }
    RemoteCache remoteCache =
        new RemoteCache(HTTP_AND_DISK_CACHE_CAPABILITIES, cacheClient, remoteOptions, digestUtil);
    actionContextProvider =
        RemoteActionContextProvider.createForRemoteCaching(
            executorService, env, remoteCache, /* retryScheduler= */ null, digestUtil);
  }

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    Preconditions.checkState(blockWaitingModule == null, "blockWaitingModule must be null");
    Preconditions.checkState(credentialModule == null, "credentialModule must be null");
    blockWaitingModule =
        Preconditions.checkNotNull(runtime.getBlazeModule(BlockWaitingModule.class));
    credentialModule = Preconditions.checkNotNull(runtime.getBlazeModule(CredentialModule.class));
  }

  @Override
  public void beforeCommand(CommandEnvironment env) throws AbruptExitException {
    Preconditions.checkState(actionContextProvider == null, "actionContextProvider must be null");
    Preconditions.checkState(actionInputFetcher == null, "actionInputFetcher must be null");
    Preconditions.checkState(remoteOptions == null, "remoteOptions must be null");
    Preconditions.checkState(tempPathGenerator == null, "tempPathGenerator must be null");
    Preconditions.checkState(patternsToDownload == null, "patternsToDownload must be null");

    RemoteOptions remoteOptions = env.getOptions().getOptions(RemoteOptions.class);
    if (remoteOptions == null) {
      // Quit if no supported command is being used. See getCommandOptions for details.
      return;
    }

    this.remoteOptions = remoteOptions;

    AuthAndTLSOptions authAndTlsOptions = env.getOptions().getOptions(AuthAndTLSOptions.class);
    DigestHashFunction hashFn = env.getRuntime().getFileSystem().getDigestFunction();
    DigestUtil digestUtil = new DigestUtil(env.getXattrProvider(), hashFn);

    boolean verboseFailures = false;
    ExecutionOptions executionOptions = env.getOptions().getOptions(ExecutionOptions.class);
    if (executionOptions != null) {
      verboseFailures = executionOptions.verboseFailures;
    }

    boolean enableDiskCache = RemoteCacheClientFactory.isDiskCache(remoteOptions);
    boolean enableHttpCache = RemoteCacheClientFactory.isHttpCache(remoteOptions);
    boolean enableRemoteExecution = shouldEnableRemoteExecution(remoteOptions);
    // If --remote_cache is empty but --remote_executor is not, endpoint for cache should be the one
    // for execution.
    if (enableRemoteExecution && Strings.isNullOrEmpty(remoteOptions.remoteCache)) {
      remoteOptions.remoteCache = remoteOptions.remoteExecutor;
    }
    boolean enableGrpcCache = GrpcCacheClient.isRemoteCacheOptions(remoteOptions);
    boolean enableRemoteDownloader = shouldEnableRemoteDownloader(remoteOptions);

    if (enableRemoteDownloader && !enableGrpcCache) {
      throw createOptionsExitException(
          "The remote downloader can only be used in combination with gRPC caching",
          FailureDetails.RemoteOptions.Code.DOWNLOADER_WITHOUT_GRPC_CACHE);
    }

    if (!enableDiskCache && !enableHttpCache && !enableGrpcCache && !enableRemoteExecution) {
      // Quit if no remote caching or execution was enabled.
      actionContextProvider =
          RemoteActionContextProvider.createForPlaceholder(env, retryScheduler, digestUtil);
      return;
    }

    if (enableHttpCache && enableRemoteExecution) {
      throw createOptionsExitException(
          "Cannot combine gRPC based remote execution with HTTP-based caching",
          FailureDetails.RemoteOptions.Code.EXECUTION_WITH_INVALID_CACHE);
    }

    // TODO(bazel-team): Consider adding a warning or more validation if the remoteDownloadRegex is
    // used without Build without the Bytes.
    if (!remoteOptions.remoteOutputsMode.downloadAllOutputs()) {
      ImmutableList.Builder<Pattern> patternsToDownloadBuilder = ImmutableList.builder();
      for (String regex : remoteOptions.remoteDownloadRegex) {
        patternsToDownloadBuilder.add(Pattern.compile(regex));
      }
      patternsToDownload = patternsToDownloadBuilder.build();
    }

    env.getEventBus().register(this);
    String invocationId = env.getCommandId().toString();
    String buildRequestId = env.getBuildRequestId();
    env.getReporter().handle(Event.info(String.format("Invocation ID: %s", invocationId)));

    RxJavaPlugins.setErrorHandler(
        error -> env.getReporter().handle(Event.error(Throwables.getStackTraceAsString(error))));

    Path logDir =
        env.getOutputBase().getRelative(env.getRuntime().getProductName() + "-remote-logs");
    cleanAndCreateRemoteLogsDir(logDir);

    BuildRequestOptions buildRequestOptions =
        env.getOptions().getOptions(BuildRequestOptions.class);

    int jobs = 0;
    if (buildRequestOptions != null) {
      jobs = buildRequestOptions.jobs;
    }

    ThreadFactory threadFactory =
        new ThreadFactoryBuilder().setNameFormat("remote-executor-%d").build();
    if (jobs != 0) {
      ThreadPoolExecutor tpe =
          new ThreadPoolExecutor(
              jobs, jobs, 60L, SECONDS, new LinkedBlockingQueue<>(), threadFactory);
      tpe.allowCoreThreadTimeOut(true);
      executorService = tpe;
    } else {
      executorService = Executors.newCachedThreadPool(threadFactory);
    }

    Credentials credentials;
    try {
      credentials =
          createCredentials(
              CredentialHelperEnvironment.newBuilder()
                  .setEventReporter(env.getReporter())
                  .setWorkspacePath(env.getWorkspace())
                  .setClientEnvironment(env.getClientEnv())
                  .setHelperExecutionTimeout(authAndTlsOptions.credentialHelperTimeout)
                  .build(),
              credentialModule.getCredentialCache(),
              env.getCommandLinePathFactory(),
              env.getRuntime().getFileSystem(),
              authAndTlsOptions,
              remoteOptions);
    } catch (IOException e) {
      handleInitFailure(env, e, Code.CREDENTIALS_INIT_FAILURE);
      return;
    }

    if ((enableHttpCache || enableDiskCache) && !enableGrpcCache) {
      initHttpAndDiskCache(env, credentials, authAndTlsOptions, remoteOptions, digestUtil);
      buildEventArtifactUploaderFactoryDelegate.init(
          new ByteStreamBuildEventArtifactUploaderFactory(
                  executorService,
                  env.getReporter(),
                  verboseFailures,
                  actionContextProvider.getRemoteCache(),
                  remoteOptions.remoteInstanceName,
                  remoteOptions.remoteBytestreamUriPrefix,
                  buildRequestId,
                  invocationId,
                  remoteOptions.remoteBuildEventUploadMode));
      return;
    }

    ClientInterceptor loggingInterceptor = null;
    if (remoteOptions.remoteGrpcLog != null) {
      try {
        rpcLogFile =
            new AsynchronousFileOutputStream(
                env.getWorkingDirectory().getRelative(remoteOptions.remoteGrpcLog));
      } catch (IOException e) {
        handleInitFailure(env, e, Code.RPC_LOG_FAILURE);
        return;
      }
      loggingInterceptor = new LoggingInterceptor(rpcLogFile, env.getRuntime().getClock());
    }

    ReferenceCountedChannel execChannel = null;
    ReferenceCountedChannel cacheChannel = null;
    ReferenceCountedChannel downloaderChannel = null;

    // The number of concurrent requests for one connection to a gRPC server is limited by
    // MAX_CONCURRENT_STREAMS which is normally being 100+. We assume 50 concurrent requests for
    // each connection should be fairly well. The number of connections opened by one channel is
    // based on the resolved IPs of that server. We assume servers normally have 2 IPs. So the
    // max concurrency per connection is 100.
    int maxConcurrencyPerConnection = 100;
    int maxConnections = 0;
    if (remoteOptions.remoteMaxConnections > 0) {
      maxConnections = remoteOptions.remoteMaxConnections;
    }

    if (enableRemoteExecution) {
      ImmutableList.Builder<ClientInterceptor> interceptors = ImmutableList.builder();
      interceptors.add(TracingMetadataUtils.newExecHeadersInterceptor(remoteOptions));
      if (loggingInterceptor != null) {
        interceptors.add(loggingInterceptor);
      }
      execChannel =
          new ReferenceCountedChannel(
              new GoogleChannelConnectionFactory(
                  channelFactory,
                  remoteOptions.remoteExecutor,
                  remoteOptions.remoteProxy,
                  authAndTlsOptions,
                  interceptors.build(),
                  maxConcurrencyPerConnection),
              maxConnections);

      // Create a separate channel if --remote_executor and --remote_cache point to different
      // endpoints.
      if (remoteOptions.remoteCache.equals(remoteOptions.remoteExecutor)) {
        cacheChannel = execChannel.retain();
      }
    }

    if (cacheChannel == null) {
      ImmutableList.Builder<ClientInterceptor> interceptors = ImmutableList.builder();
      interceptors.add(TracingMetadataUtils.newCacheHeadersInterceptor(remoteOptions));
      if (loggingInterceptor != null) {
        interceptors.add(loggingInterceptor);
      }
      cacheChannel =
          new ReferenceCountedChannel(
              new GoogleChannelConnectionFactory(
                  channelFactory,
                  remoteOptions.remoteCache,
                  remoteOptions.remoteProxy,
                  authAndTlsOptions,
                  interceptors.build(),
                  maxConcurrencyPerConnection),
              maxConnections);
    }

    if (enableRemoteDownloader) {
      // Create a separate channel if --remote_downloader and --remote_cache point to different
      // endpoints.
      if (remoteOptions.remoteDownloader.equals(remoteOptions.remoteCache)) {
        downloaderChannel = cacheChannel.retain();
      } else {
        ImmutableList.Builder<ClientInterceptor> interceptors = ImmutableList.builder();
        if (loggingInterceptor != null) {
          interceptors.add(loggingInterceptor);
        }
        downloaderChannel =
            new ReferenceCountedChannel(
                new GoogleChannelConnectionFactory(
                    channelFactory,
                    remoteOptions.remoteDownloader,
                    remoteOptions.remoteProxy,
                    authAndTlsOptions,
                    interceptors.build(),
                    maxConcurrencyPerConnection),
                maxConnections);
      }
    }

    CallCredentialsProvider callCredentialsProvider =
        GoogleAuthUtils.newCallCredentialsProvider(credentials);
    CallCredentials callCredentials = callCredentialsProvider.getCallCredentials();

    Retrier.CircuitBreaker circuitBreaker =
        CircuitBreakerFactory.createCircuitBreaker(remoteOptions);
    RemoteRetrier retrier =
        new RemoteRetrier(
            remoteOptions, RemoteRetrier.RETRIABLE_GRPC_ERRORS, retryScheduler, circuitBreaker);

    // We only check required capabilities for a given endpoint.
    //
    // If --remote_executor and --remote_cache point to the same endpoint, we require that
    // endpoint has both execution and cache capabilities.
    //
    // If they point to different endpoints, we check the endpoint with execution or cache
    // capabilities respectively.
    ServerCapabilities executionCapabilities = null;
    ServerCapabilities cacheCapabilities = null;
    try {
      if (execChannel != null) {
        if (cacheChannel != execChannel) {
          executionCapabilities =
              getAndVerifyServerCapabilities(
                  remoteOptions,
                  execChannel,
                  callCredentials,
                  retrier,
                  env,
                  digestUtil,
                  ServerCapabilitiesRequirement.EXECUTION);
          cacheCapabilities =
              getAndVerifyServerCapabilities(
                  remoteOptions,
                  cacheChannel,
                  callCredentials,
                  retrier,
                  env,
                  digestUtil,
                  ServerCapabilitiesRequirement.CACHE);
        } else {
          cacheCapabilities =
              getAndVerifyServerCapabilities(
                  remoteOptions,
                  execChannel,
                  callCredentials,
                  retrier,
                  env,
                  digestUtil,
                  ServerCapabilitiesRequirement.EXECUTION_AND_CACHE);
          executionCapabilities = cacheCapabilities;
        }
      } else {
        cacheCapabilities =
            getAndVerifyServerCapabilities(
                remoteOptions,
                cacheChannel,
                callCredentials,
                retrier,
                env,
                digestUtil,
                ServerCapabilitiesRequirement.CACHE);
      }
    } catch (AbruptExitException e) {
      throw e; // prevent abrupt interception
    } catch (Exception e) {
      String errorMessage =
          "Failed to query remote execution capabilities: "
              + Utils.grpcAwareErrorMessage(e, verboseFailures);
      if (remoteOptions.remoteLocalFallback) {
        if (verboseFailures) {
          errorMessage += System.lineSeparator() + Throwables.getStackTraceAsString(e);
        }
        env.getReporter().handle(Event.warn(errorMessage));
        actionContextProvider =
            RemoteActionContextProvider.createForPlaceholder(env, retryScheduler, digestUtil);
        return;
      } else {
        if (verboseFailures) {
          env.getReporter().handle(Event.error(Throwables.getStackTraceAsString(e)));
        }
        throw createExitException(
            errorMessage, ExitCode.REMOTE_ERROR, Code.CAPABILITIES_QUERY_FAILURE);
      }
    }

    RemoteCacheClient cacheClient =
        new GrpcCacheClient(
            cacheChannel.retain(), callCredentialsProvider, remoteOptions, retrier, digestUtil);
    cacheChannel.release();

    if (enableRemoteExecution) {
      if (enableDiskCache) {
        try {
          cacheClient =
              RemoteCacheClientFactory.createDiskAndRemoteClient(
                  env.getWorkingDirectory(),
                  remoteOptions.diskCache,
                  remoteOptions.remoteVerifyDownloads,
                  !remoteOptions.remoteOutputsMode.downloadAllOutputs(),
                  digestUtil,
                  cacheClient);
        } catch (Exception e) {
          handleInitFailure(env, e, Code.CACHE_INIT_FAILURE);
          return;
        }
      }

      RemoteExecutionClient remoteExecutor;
      if (remoteOptions.remoteExecutionKeepalive) {
        RemoteRetrier execRetrier =
            new RemoteRetrier(
                remoteOptions,
                RemoteRetrier.RETRIABLE_GRPC_ERRORS, // Handle NOT_FOUND internally
                retryScheduler,
                circuitBreaker);
        remoteExecutor =
            new ExperimentalGrpcRemoteExecutor(
                executionCapabilities,
                remoteOptions,
                execChannel.retain(),
                callCredentialsProvider,
                execRetrier);
      } else {
        RemoteRetrier execRetrier =
            new RemoteRetrier(
                remoteOptions,
                RemoteRetrier.RETRIABLE_GRPC_EXEC_ERRORS,
                retryScheduler,
                circuitBreaker);
        remoteExecutor =
            new GrpcRemoteExecutor(
                executionCapabilities, execChannel.retain(), callCredentialsProvider, execRetrier);
      }
      execChannel.release();
      RemoteExecutionCache remoteCache =
          new RemoteExecutionCache(
              cacheCapabilities.getCacheCapabilities(), cacheClient, remoteOptions, digestUtil);
      actionContextProvider =
          RemoteActionContextProvider.createForRemoteExecution(
              executorService,
              env,
              remoteCache,
              remoteExecutor,
              retryScheduler,
              digestUtil,
              logDir);
      repositoryRemoteExecutorFactoryDelegate.init(
          new RemoteRepositoryRemoteExecutorFactory(
              remoteCache,
              remoteExecutor,
              digestUtil,
              buildRequestId,
              invocationId,
              remoteOptions.remoteInstanceName,
              remoteOptions.remoteAcceptCached));
    } else {
      if (enableDiskCache) {
        try {
          cacheClient =
              RemoteCacheClientFactory.createDiskAndRemoteClient(
                  env.getWorkingDirectory(),
                  remoteOptions.diskCache,
                  remoteOptions.remoteVerifyDownloads,
                  !remoteOptions.remoteOutputsMode.downloadAllOutputs(),
                  digestUtil,
                  cacheClient);
        } catch (Exception e) {
          handleInitFailure(env, e, Code.CACHE_INIT_FAILURE);
          return;
        }
      }

      RemoteCache remoteCache =
          new RemoteCache(
              cacheCapabilities.getCacheCapabilities(), cacheClient, remoteOptions, digestUtil);
      actionContextProvider =
          RemoteActionContextProvider.createForRemoteCaching(
              executorService, env, remoteCache, retryScheduler, digestUtil);
    }

    buildEventArtifactUploaderFactoryDelegate.init(
        new ByteStreamBuildEventArtifactUploaderFactory(
            executorService,
            env.getReporter(),
            verboseFailures,
            actionContextProvider.getRemoteCache(),
            remoteOptions.remoteInstanceName,
            remoteOptions.remoteBytestreamUriPrefix,
            buildRequestId,
            invocationId,
            remoteOptions.remoteBuildEventUploadMode));

    if (enableRemoteDownloader) {
      Downloader fallbackDownloader = null;
      if (remoteOptions.remoteDownloaderLocalFallback) {
        fallbackDownloader = new HttpDownloader();
      }
      remoteDownloaderSupplier.set(
          new GrpcRemoteDownloader(
              buildRequestId,
              invocationId,
              downloaderChannel.retain(),
              Optional.ofNullable(callCredentials),
              retrier,
              cacheClient,
              remoteOptions,
              verboseFailures,
              fallbackDownloader));
      downloaderChannel.release();
    }
  }

  private static void handleInitFailure(
      CommandEnvironment env, Exception e, Code remoteExecutionCode) {
    env.getReporter().handle(Event.error(e.getMessage()));
    env.getBlazeModuleEnvironment()
        .exit(
            createExitException(
                "Error initializing RemoteModule",
                ExitCode.COMMAND_LINE_ERROR,
                remoteExecutionCode));
  }

  @Override
  public void afterAnalysis(
      CommandEnvironment env,
      BuildRequest request,
      BuildOptions buildOptions,
      AnalysisResult analysisResult) {
    if (remoteOptions != null
        && remoteOptions.remoteBuildEventUploadMode == RemoteBuildEventUploadMode.ALL
        && remoteOptions.incompatibleRemoteBuildEventUploadRespectNoCache) {
      parseNoCacheOutputs(analysisResult);
    }
  }

  private void parseNoCacheOutputs(AnalysisResult analysisResult) {
    if (actionContextProvider == null || actionContextProvider.getRemoteCache() == null) {
      return;
    }

    ByteStreamBuildEventArtifactUploader uploader = buildEventArtifactUploaderFactoryDelegate.get();
    if (uploader == null) {
      return;
    }

    for (ConfiguredTarget configuredTarget : analysisResult.getTargetsToBuild()) {
      // This will either dereference an alias chain, or return the final ConfiguredTarget.
      configuredTarget = configuredTarget.getActual();

      if (configuredTarget instanceof RuleConfiguredTarget) {
        RuleConfiguredTarget ruleConfiguredTarget = (RuleConfiguredTarget) configuredTarget;
        for (ActionAnalysisMetadata action : ruleConfiguredTarget.getActions()) {
          boolean uploadLocalResults =
              Utils.shouldUploadLocalResultsToRemoteCache(remoteOptions, action.getExecutionInfo());
          if (!uploadLocalResults) {
            for (Artifact output : action.getOutputs()) {
              if (output.isTreeArtifact()) {
                uploader.omitTree(output.getPath());
              } else {
                uploader.omitFile(output.getPath());
              }
            }
          }
        }
      }
    }
  }

  private static void cleanAndCreateRemoteLogsDir(Path logDir) throws AbruptExitException {
    try {
      // Clean out old logs files.
      if (logDir.exists()) {
        logDir.deleteTree();
      }
      logDir.createDirectory();
    } catch (IOException e) {
      String message = String.format("Could not create base directory for remote logs: %s", logDir);
      throw createExitException(
          message, ExitCode.LOCAL_ENVIRONMENTAL_ERROR, Code.LOG_DIR_CLEANUP_FAILURE);
    }
  }

  private static void checkClientServerCompatibility(
      ServerCapabilities capabilities,
      RemoteOptions remoteOptions,
      DigestFunction.Value digestFunction,
      Reporter reporter,
      ServerCapabilitiesRequirement requirement)
      throws AbruptExitException {
    RemoteServerCapabilities.ClientServerCompatibilityStatus st =
        RemoteServerCapabilities.checkClientServerCompatibility(
            capabilities, remoteOptions, digestFunction, requirement);
    for (String warning : st.getWarnings()) {
      reporter.handle(Event.warn(warning));
    }
    List<String> errors = st.getErrors();
    for (int i = 0; i < errors.size() - 1; ++i) {
      reporter.handle(Event.error(errors.get(i)));
    }
    if (!errors.isEmpty()) {
      String lastErrorMessage = errors.get(errors.size() - 1);
      throw createExitException(
          lastErrorMessage, ExitCode.REMOTE_ERROR, Code.CLIENT_SERVER_INCOMPATIBLE);
    }
  }

  @Override
  public void afterCommand() {
    Preconditions.checkNotNull(blockWaitingModule, "blockWaitingModule must not be null");

    // Some cleanup tasks must wait until every other BlazeModule's afterCommand() has run, as
    // otherwise we might interfere with asynchronous remote downloads that are in progress.
    RemoteActionContextProvider actionContextProviderRef = actionContextProvider;
    TempPathGenerator tempPathGeneratorRef = tempPathGenerator;
    AsynchronousFileOutputStream rpcLogFileRef = rpcLogFile;
    if (actionContextProviderRef != null || tempPathGeneratorRef != null || rpcLogFileRef != null) {
      blockWaitingModule.submit(
          () -> afterCommandTask(actionContextProviderRef, tempPathGeneratorRef, rpcLogFileRef));
    }

    buildEventArtifactUploaderFactoryDelegate.reset();
    repositoryRemoteExecutorFactoryDelegate.reset();
    remoteDownloaderSupplier.set(null);
    actionContextProvider = null;
    actionInputFetcher = null;
    toplevelArtifactsDownloader = null;
    remoteOptions = null;
    remoteOutputService = null;
    tempPathGenerator = null;
    rpcLogFile = null;
    patternsToDownload = null;
  }

  private static void afterCommandTask(
      RemoteActionContextProvider actionContextProvider,
      TempPathGenerator tempPathGenerator,
      AsynchronousFileOutputStream rpcLogFile)
      throws AbruptExitException {
    if (actionContextProvider != null) {
      actionContextProvider.afterCommand();
    }

    if (tempPathGenerator != null) {
      Path tempDir = tempPathGenerator.getTempDir();
      try {
        tempDir.deleteTree();
      } catch (IOException ignored) {
        // Intentionally ignored.
      }
    }

    if (rpcLogFile != null) {
      try {
        rpcLogFile.close();
      } catch (IOException e) {
        throw createExitException(
            "Partially wrote RPC log file",
            ExitCode.LOCAL_ENVIRONMENTAL_ERROR,
            Code.RPC_LOG_FAILURE);
      }
    }
  }

  @Override
  public void registerSpawnStrategies(
      SpawnStrategyRegistry.Builder registryBuilder, CommandEnvironment env) {
    if (actionContextProvider == null) {
      return;
    }
    RemoteOptions remoteOptions =
        Preconditions.checkNotNull(
            env.getOptions().getOptions(RemoteOptions.class), "RemoteOptions");
    registryBuilder.setRemoteLocalFallbackStrategyIdentifier(
        remoteOptions.remoteLocalFallbackStrategy);
    actionContextProvider.registerRemoteSpawnStrategy(registryBuilder);
  }

  @Override
  public void registerActionContexts(
      ModuleActionContextRegistry.Builder registryBuilder,
      CommandEnvironment env,
      BuildRequest buildRequest) {
    if (actionContextProvider == null) {
      return;
    }
    actionContextProvider.registerSpawnCache(registryBuilder);
  }

  private TempPathGenerator getTempPathGenerator(CommandEnvironment env)
      throws AbruptExitException {
    Path tempDir = env.getActionTempsDirectory().getChild("remote");
    if (tempDir.exists()) {
      env.getReporter()
          .handle(Event.warn("Found stale downloads from previous build, deleting..."));
      try {
        tempDir.deleteTree();
      } catch (IOException e) {
        throw new AbruptExitException(
            DetailedExitCode.of(
                ExitCode.LOCAL_ENVIRONMENTAL_ERROR,
                FailureDetail.newBuilder()
                    .setMessage(
                        String.format("Failed to delete stale downloads: %s", e.getMessage()))
                    .setRemoteExecution(
                        RemoteExecution.newBuilder()
                            .setCode(Code.DOWNLOADED_INPUTS_DELETION_FAILURE))
                    .build()));
      }
    }

    return new TempPathGenerator(tempDir);
  }

  @Override
  public void executorInit(CommandEnvironment env, BuildRequest request, ExecutorBuilder builder)
      throws AbruptExitException {
    Preconditions.checkState(actionInputFetcher == null, "actionInputFetcher must be null");
    Preconditions.checkState(tempPathGenerator == null, "tempPathGenerator must be null");
    Preconditions.checkNotNull(remoteOptions, "remoteOptions must not be null");

    if (actionContextProvider == null) {
      return;
    }

    tempPathGenerator = getTempPathGenerator(env);

    actionContextProvider.setTempPathGenerator(tempPathGenerator);

    ExecutionOptions executionOptions = env.getOptions().getOptions(ExecutionOptions.class);
    RemoteOptions remoteOptions =
        Preconditions.checkNotNull(
            env.getOptions().getOptions(RemoteOptions.class), "RemoteOptions");
    CoreOptions coreOptions = env.getOptions().getOptions(CoreOptions.class);
    OutputPermissions outputPermissions =
        coreOptions.experimentalWritableOutputs
            ? OutputPermissions.WRITABLE
            : OutputPermissions.READONLY;
    RemoteOutputsMode remoteOutputsMode = remoteOptions.remoteOutputsMode;

    if (!remoteOutputsMode.downloadAllOutputs() && actionContextProvider.getRemoteCache() != null) {
      Preconditions.checkNotNull(patternsToDownload, "patternsToDownload must not be null");
      actionInputFetcher =
          new RemoteActionInputFetcher(
              env.getReporter(),
              env.getBuildRequestId(),
              env.getCommandId().toString(),
              actionContextProvider.getRemoteCache(),
              env.getExecRoot(),
              tempPathGenerator,
              patternsToDownload,
              outputPermissions,
              remoteOptions.useNewExitCodeForLostInputs
                  || (executionOptions != null && executionOptions.remoteRetryOnCacheEviction > 0));
      env.getEventBus().register(actionInputFetcher);
      builder.setActionInputPrefetcher(actionInputFetcher);
      actionContextProvider.setActionInputFetcher(actionInputFetcher);

      toplevelArtifactsDownloader =
          new ToplevelArtifactsDownloader(
              env.getCommandName(),
              remoteOutputsMode.downloadToplevelOutputsOnly(),
              env.getSkyframeExecutor().getEvaluator(),
              actionInputFetcher,
              new PathToMetadataConverter() {
                @Nullable
                @Override
                public FileArtifactValue getMetadata(Path path) {
                  FileSystem fileSystem = path.getFileSystem();
                  if (fileSystem instanceof RemoteActionFileSystem) {
                    return ((RemoteActionFileSystem) path.getFileSystem())
                        .getRemoteMetadata(path.asFragment());
                  }
                  return null;
                }

                @Nullable
                @Override
                public ActionInput getActionInput(Path path) {
                  FileSystem fileSystem = path.getFileSystem();
                  if (fileSystem instanceof RemoteActionFileSystem) {
                    return ((RemoteActionFileSystem) path.getFileSystem())
                        .getActionInput(path.asFragment());
                  }
                  return null;
                }
              });
      env.getEventBus().register(toplevelArtifactsDownloader);

      var leaseService =
          new LeaseService(
              env.getSkyframeExecutor().getEvaluator(),
              env.getBlazeWorkspace().getPersistentActionCache());

      remoteOutputService.setActionInputFetcher(actionInputFetcher);
      remoteOutputService.setLeaseService(leaseService);
      env.getEventBus().register(remoteOutputService);
    }
  }

  @Override
  public OutputService getOutputService() {
    Preconditions.checkState(remoteOutputService == null, "remoteOutputService must be null");
    if (remoteOptions != null
        && !remoteOptions.remoteOutputsMode.downloadAllOutputs()
        && actionContextProvider.getRemoteCache() != null) {
      remoteOutputService = new RemoteOutputService();
    }
    return remoteOutputService;
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return ImmutableList.of("build", "fetch", "query", "sync", "test").contains(command.name())
        ? ImmutableList.of(RemoteOptions.class, AuthAndTLSOptions.class)
        : ImmutableList.of();
  }

  private static class BuildEventArtifactUploaderFactoryDelegate
      implements BuildEventArtifactUploaderFactory {

    @Nullable private ByteStreamBuildEventArtifactUploaderFactory uploaderFactory;

    public void init(ByteStreamBuildEventArtifactUploaderFactory uploaderFactory) {
      Preconditions.checkState(this.uploaderFactory == null);
      this.uploaderFactory = uploaderFactory;
    }

    @Nullable
    public ByteStreamBuildEventArtifactUploader get() {
      if (uploaderFactory == null) {
        return null;
      }
      return uploaderFactory.get();
    }

    public void reset() {
      this.uploaderFactory = null;
    }

    @Override
    public BuildEventArtifactUploader create(CommandEnvironment env)
        throws InvalidPackagePathSymlinkException {
      BuildEventArtifactUploaderFactory uploaderFactory0 = this.uploaderFactory;
      if (uploaderFactory0 == null) {
        return new LocalFilesArtifactUploader();
      }
      return uploaderFactory0.create(env);
    }
  }

  private static AbruptExitException createOptionsExitException(
      String message, FailureDetails.RemoteOptions.Code remoteExecutionCode) {
    return new AbruptExitException(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setRemoteOptions(
                    FailureDetails.RemoteOptions.newBuilder().setCode(remoteExecutionCode))
                .build()));
  }

  private static AbruptExitException createExitException(
      String message, ExitCode exitCode, Code remoteExecutionCode) {
    return new AbruptExitException(
        DetailedExitCode.of(
            exitCode,
            FailureDetail.newBuilder()
                .setMessage(message)
                .setRemoteExecution(RemoteExecution.newBuilder().setCode(remoteExecutionCode))
                .build()));
  }

  private static class RepositoryRemoteExecutorFactoryDelegate
      implements RepositoryRemoteExecutorFactory {

    private volatile RepositoryRemoteExecutorFactory delegate;

    public void init(RepositoryRemoteExecutorFactory delegate) {
      Preconditions.checkState(this.delegate == null);
      this.delegate = delegate;
    }

    public void reset() {
      this.delegate = null;
    }

    @Nullable
    @Override
    public RepositoryRemoteExecutor create() {
      RepositoryRemoteExecutorFactory delegate = this.delegate;
      if (delegate == null) {
        return null;
      }
      return delegate.create();
    }
  }

  @VisibleForTesting
  void setChannelFactory(ChannelFactory channelFactory) {
    this.channelFactory = channelFactory;
  }

  @VisibleForTesting
  RemoteActionContextProvider getActionContextProvider() {
    return actionContextProvider;
  }

  @VisibleForTesting
  static Credentials createCredentials(
      CredentialHelperEnvironment credentialHelperEnvironment,
      Cache<URI, ImmutableMap<String, ImmutableList<String>>> credentialCache,
      CommandLinePathFactory commandLinePathFactory,
      FileSystem fileSystem,
      AuthAndTLSOptions authAndTlsOptions,
      RemoteOptions remoteOptions)
      throws IOException {
    Credentials credentials =
        GoogleAuthUtils.newCredentials(
            credentialHelperEnvironment,
            credentialCache,
            commandLinePathFactory,
            fileSystem,
            authAndTlsOptions);

    try {
      if (credentials != null
          && remoteOptions.remoteCache != null
          && Ascii.toLowerCase(remoteOptions.remoteCache).startsWith("http://")
          && !credentials.getRequestMetadata(new URI(remoteOptions.remoteCache)).isEmpty()) {
        // TODO(yannic): Make this a error aborting the build.
        credentialHelperEnvironment
            .getEventReporter()
            .handle(
                Event.warn(
                    "Credentials are transmitted in plaintext to "
                        + remoteOptions.remoteCache
                        + ". Please consider using an HTTPS endpoint."));
      }
    } catch (URISyntaxException e) {
      throw new IOException(e.getMessage(), e);
    }

    return credentials;
  }
}
