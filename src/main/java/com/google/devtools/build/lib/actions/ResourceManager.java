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

import static com.google.devtools.build.lib.profiler.AutoProfiler.profiled;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.worker.Worker;
import com.google.devtools.build.lib.worker.WorkerKey;
import com.google.devtools.build.lib.worker.WorkerPool;

import java.io.IOException;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.annotation.Nullable;

/**
 * Used to keep track of resources consumed by the Blaze action execution threads and throttle them
 * when necessary.
 *
 * <p>Threads which are known to consume a significant amount of resources should call {@link
 * #acquireResources} method. This method will check whether requested resources are available and
 * will either mark them as used and allow the thread to proceed or will block the thread until
 * requested resources will become available. When the thread completes its task, it must release
 * allocated resources by calling {@link #releaseResources} method.
 *
 * <p>Available resources can be calculated using one of three ways:
 *
 * <ol>
 *   <li>They can be preset using {@link #setAvailableResources(ResourceSet)} method. This is used
 *       mainly by the unit tests (however it is possible to provide a future option that would
 *       artificially limit amount of CPU/RAM consumed by the Blaze).
 *   <li>They can be preset based on the /proc/cpuinfo and /proc/meminfo information. Blaze will
 *       calculate amount of available CPU cores (adjusting for hyperthreading logical cores) and
 *       amount of the total available memory and will limit itself to the number of effective cores
 *       and 2/3 of the available memory. For details, please look at the {@link
 *       LocalHostCapacity#getLocalHostCapacity} method.
 * </ol>
 *
 * <p>The resource manager also allows a slight overallocation of the resources to account for the
 * fact that requested resources are usually estimated using a pessimistic approximation. It also
 * guarantees that at least one thread will always be able to acquire any amount of requested
 * resources (even if it is greater than amount of available resources). Therefore, assuming that
 * threads correctly release acquired resources, Blaze will never be fully blocked.
 */
@ThreadSafe
public class ResourceManager {

  /**
   * A handle returned by {@link #acquireResources(ActionExecutionMetadata, ResourceSet,
   * ResourcePriority)} that must be closed in order to free the resources again.
   */
  public static class ResourceHandle implements AutoCloseable {
    private final ResourceManager rm;
    private final ActionExecutionMetadata actionMetadata;
    private final ResourceSet resourceSet;
    private Worker worker;

    private ResourceHandle(
        ResourceManager rm,
        ActionExecutionMetadata actionMetadata,
        ResourceSet resources,
        Worker worker) {
      this.rm = rm;
      this.actionMetadata = actionMetadata;
      this.resourceSet = resources;
      this.worker = worker;
    }

    @Nullable
    public Worker getWorker() {
      return worker;
    }

    /** Closing the ResourceHandle releases the resources associated with it. */
    @Override
    public void close() throws IOException, InterruptedException {
      rm.releaseResources(actionMetadata, resourceSet, worker);
    }

    public void invalidateAndClose() throws IOException, InterruptedException {
      rm.workerPool.invalidateObject(resourceSet.getWorkerKey(), worker);
      worker = null;
      this.close();
    }
  }

  private final ThreadLocal<Boolean> threadLocked =
      new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
          return false;
        }
      };

  /**
   * Defines the possible priorities of resources. The earlier elements in this enum will get first
   * chance at grabbing resources.
   */
  public enum ResourcePriority {
    LOCAL(), // Local execution not under dynamic execution
    DYNAMIC_WORKER(),
    DYNAMIC_STANDALONE();
  }

  /** Singleton reference defined in a separate class to ensure thread-safe lazy initialization. */
  private static class Singleton {
    static ResourceManager instance = new ResourceManager();
  }

  /** Returns singleton instance of the resource manager. */
  public static ResourceManager instance() {
    return Singleton.instance;
  }

  // Allocated resources are allowed to go "negative", but at least
  // MIN_AVAILABLE_CPU_RATIO portion of CPU and MIN_AVAILABLE_RAM_RATIO portion
  // of RAM should be available.
  // Please note that this value is purely empirical - we assume that generally
  // requested resources are somewhat pessimistic and thread would end up
  // using less than requested amount.
  private static final double MIN_NECESSARY_CPU_RATIO = 0.6;
  private static final double MIN_NECESSARY_RAM_RATIO = 1.0;

  // Lists of blocked threads. Associated CountDownLatch object will always
  // be initialized to 1 during creation in the acquire() method.
  // We use LinkedList because we will need to remove elements from the middle frequently in the
  // middle of iterating through the list.
  @SuppressWarnings("JdkObsolete")
  private final Deque<Pair<ResourceSet, LatchWithWorker>> localRequests = new LinkedList<>();

  @SuppressWarnings("JdkObsolete")
  private final Deque<Pair<ResourceSet, LatchWithWorker>> dynamicWorkerRequests =
      new LinkedList<>();

  @SuppressWarnings("JdkObsolete")
  private final Deque<Pair<ResourceSet, LatchWithWorker>> dynamicStandaloneRequests =
      new LinkedList<>();

  private WorkerPool workerPool;

  // The total amount of available for Bazel resources on the local host. Must be set by
  // an explicit call to setAvailableResources(), often using
  // LocalHostCapacity.getLocalHostCapacity() as an argument.
  @VisibleForTesting public ResourceSet availableResources = null;

  private ImmutableMap<String, ResourceSet> mnemonicResourceOverride = ImmutableMap.of();

  // Used amount of CPU capacity (where 1.0 corresponds to the one fully
  // occupied CPU core. Corresponds to the CPU resource definition in the
  // ResourceSet class.
  private double usedCpu;

  // Used amount of RAM capacity in MB. Corresponds to the RAM resource
  // definition in the ResourceSet class.
  private double usedRam;

  // Used amount of extra resources. Corresponds to the extra resource
  // definition in the ResourceSet class.
  private Map<String, Float> usedExtraResources;

  // Used local test count. Corresponds to the local test count definition in the ResourceSet class.
  private int usedLocalTestCount;

  /** If set, local-only actions are given priority over dynamically run actions. */
  private boolean prioritizeLocalActions;

  @VisibleForTesting
  public static ResourceManager instanceForTestingOnly() {
    return new ResourceManager();
  }

  /**
   * Resets resource manager state and releases all thread locks.
   *
   * <p>Note - it does not reset available resources. Use separate call to setAvailableResources().
   */
  public synchronized void resetResourceUsage() {
    usedCpu = 0;
    usedRam = 0;
    usedExtraResources = new HashMap<>();
    usedLocalTestCount = 0;
    for (Pair<ResourceSet, LatchWithWorker> request : localRequests) {
      request.second.latch.countDown();
    }
    for (Pair<ResourceSet, LatchWithWorker> request : dynamicWorkerRequests) {
      request.second.latch.countDown();
    }
    for (Pair<ResourceSet, LatchWithWorker> request : dynamicStandaloneRequests) {
      request.second.latch.countDown();
    }
    localRequests.clear();
    dynamicWorkerRequests.clear();
    dynamicStandaloneRequests.clear();
  }

  /**
   * Sets available resources using given resource set.
   *
   * <p>Must be called at least once before using resource manager.
   */
  public synchronized void setAvailableResources(ResourceSet resources) {
    Preconditions.checkNotNull(resources);
    resetResourceUsage();
    availableResources = resources;
  }

  public void setMnemonicResourceOverride(List<Map.Entry<String, String>> mnemonic_resource_override)
          throws NumberFormatException {
    if (mnemonic_resource_override == null){
      return;
    }
    ImmutableMap.Builder<String, ResourceSet> buildData = ImmutableMap.builder();
    for (Map.Entry<String,String> keyValue : mnemonic_resource_override){
      String[] values = keyValue.getValue().split(",");

      try {
        double memory = Double.valueOf(values[0]);
        double cpu = Double.valueOf(values[1]);
        buildData.put(keyValue.getKey(), ResourceSet.createWithRamCpu(memory, cpu));
      } catch (NumberFormatException e) {
        throw new NumberFormatException("mnemonic_resource_override does not have 2 integer values.");
      }
    }
    this.mnemonicResourceOverride = buildData.build();
  }

  /** Sets worker pool for taking the workers. Must be called before requesting the workers. */
  public void setWorkerPool(WorkerPool workerPool) {
    this.workerPool = workerPool;
  }

  /** Sets whether to prioritize local-only actions in resource allocation. */
  public void setPrioritizeLocalActions(boolean prioritizeLocalActions) {
    this.prioritizeLocalActions = prioritizeLocalActions;
  }

  /**
   * Acquires requested resource set. Will block if resource is not available. NB! This method must
   * be thread-safe!
   */
  public ResourceHandle acquireResources(
      ActionExecutionMetadata owner, String mnemonic, ResourceSet resources, ResourcePriority priority)
      throws InterruptedException, IOException {
    Preconditions.checkNotNull(
        resources, "acquireResources called with resources == NULL during %s", owner);
    Preconditions.checkState(
        !threadHasResources(), "acquireResources with existing resource lock during %s", owner);

    LatchWithWorker latchWithWorker = null;
    resources = tryAcquireUserEstimatesForResources(mnemonic, resources);

    AutoProfiler p =
        profiled("Acquiring resources for: " + owner.describe(), ProfilerTask.ACTION_LOCK);
    try {
      latchWithWorker = acquire(resources, priority);
      if (latchWithWorker.latch != null) {
        latchWithWorker.latch.await();
      }
    } catch (InterruptedException e) {
      // Synchronize on this to avoid any racing with #processWaitingThreads
      synchronized (this) {
        if (latchWithWorker.latch == null || latchWithWorker.latch.getCount() == 0) {
          // Resources already acquired by other side. Release them, but not inside this
          // synchronized block to avoid deadlock.
          release(resources, latchWithWorker.worker);
        } else {
          // Inform other side that resources shouldn't be acquired.
          latchWithWorker.latch.countDown();
        }
      }
      throw e;
    }

    threadLocked.set(true);

    CountDownLatch latch;
    Worker worker;
    synchronized (this) {
      latch = latchWithWorker.latch;
      worker = latchWithWorker.worker;
    }

    // Profile acquisition only if it waited for resource to become available.
    if (latch != null) {
      p.complete();
    }

    return new ResourceHandle(this, owner, resources, worker);
  }

  /**
   * Acquires requested resource set that is estimated by the user via option mnemonic_resource_override
   */
  private ResourceSet tryAcquireUserEstimatesForResources(String mnemonic, ResourceSet resourceSet) {
    if (mnemonicResourceOverride.containsKey(mnemonic)) {
      return mnemonicResourceOverride.get(mnemonic);
    }
    return resourceSet;
  }

  @Nullable
  private Worker incrementResources(ResourceSet resources)
      throws IOException, InterruptedException {
    usedCpu += resources.getCpuUsage();
    usedRam += resources.getMemoryMb();

    resources
        .getExtraResourceUsage()
        .entrySet()
        .forEach(
            resource -> {
              String key = (String) resource.getKey();
              float value = resource.getValue();
              if (usedExtraResources.containsKey(key)) {
                value += (float) usedExtraResources.get(key);
              }
              usedExtraResources.put(key, value);
            });

    usedLocalTestCount += resources.getLocalTestCount();

    if (resources.getWorkerKey() != null) {
      return this.workerPool.borrowObject(resources.getWorkerKey());
    }
    return null;
  }

  /** Return true if any resources have been claimed through this manager. */
  public synchronized boolean inUse() {
    return usedCpu != 0.0
        || usedRam != 0.0
        || !usedExtraResources.isEmpty()
        || usedLocalTestCount != 0
        || !localRequests.isEmpty()
        || !dynamicWorkerRequests.isEmpty()
        || !dynamicStandaloneRequests.isEmpty();
  }

  /** Return true iff this thread has a lock on non-zero resources. */
  public boolean threadHasResources() {
    return threadLocked.get();
  }

  /**
   * Releases previously requested resource.
   *
   * <p>NB! This method must be thread-safe!
   *
   * @param owner action metadata, which resources should ve released
   * @param resources resources should be released
   * @param worker the worker, which used during execution
   * @throws java.io.IOException if could not return worker to the workerPool
   */
  void releaseResources(
      ActionExecutionMetadata owner, ResourceSet resources, @Nullable Worker worker)
      throws IOException, InterruptedException {
    Preconditions.checkNotNull(
        resources, "releaseResources called with resources == NULL during %s", owner);

    Preconditions.checkState(
        threadHasResources(), "releaseResources without resource lock during %s", owner);

    boolean resourcesReused = false;
    AutoProfiler p = profiled(owner.describe(), ProfilerTask.ACTION_RELEASE);
    try {
      resourcesReused = release(resources, worker);
    } finally {
      threadLocked.set(false);

      // Profile resource release only if it resolved at least one allocation request.
      if (resourcesReused) {
        p.complete();
      }
    }
  }

  // TODO (b/241066751) find better way to change resource ownership
  public void releaseResourceOwnership() {
    threadLocked.set(false);
  }

  public void acquireResourceOwnership() {
    threadLocked.set(true);
  }

  /**
   * Returns the pair of worker and latch. Worker should be null if there is no workerKey in
   * resources. The latch isn't null if we could not acquire the resources right now and need to
   * wait.
   */
  private synchronized LatchWithWorker acquire(ResourceSet resources, ResourcePriority priority)
      throws IOException, InterruptedException, NoSuchElementException {
    if (areResourcesAvailable(resources)) {
      Worker worker = incrementResources(resources);
      return new LatchWithWorker(/* latch= */ null, worker);
    }
    Pair<ResourceSet, LatchWithWorker> request =
        new Pair<>(resources, new LatchWithWorker(new CountDownLatch(1), /* worker= */ null));
    if (this.prioritizeLocalActions) {
      switch (priority) {
        case LOCAL:
          localRequests.addLast(request);
          break;
        case DYNAMIC_WORKER:
          // Dynamic requests should be LIFO, because we are more likely to win the race on newer
          // actions.
          dynamicWorkerRequests.addFirst(request);
          break;
        case DYNAMIC_STANDALONE:
          // Dynamic requests should be LIFO, because we are more likely to win the race on newer
          // actions.
          dynamicStandaloneRequests.addFirst(request);
          break;
      }
    } else {
      localRequests.addLast(request);
    }
    return request.second;
  }

  /**
   * Release resources and process the queues of waiting threads. Return true when any new thread
   * processed.
   */
  private boolean release(ResourceSet resources, @Nullable Worker worker)
      throws IOException, InterruptedException {
    // We need to release the worker first to not block highPriorityWorkerMnemonics management. See
    // more on b/244297036.
    if (worker != null) {
      this.workerPool.returnObject(worker.getWorkerKey(), worker);
    }
    releaseResourcesOnly(resources);

    return processAllWaitingThreads();
  }

  private synchronized void releaseResourcesOnly(ResourceSet resources) {
    usedCpu -= resources.getCpuUsage();
    usedRam -= resources.getMemoryMb();

    usedLocalTestCount -= resources.getLocalTestCount();

    // TODO(bazel-team): (2010) rounding error can accumulate and value below can end up being
    // e.g. 1E-15. So if it is small enough, we set it to 0. But maybe there is a better solution.
    double epsilon = 0.0001;
    if (usedCpu < epsilon) {
      usedCpu = 0;
    }
    if (usedRam < epsilon) {
      usedRam = 0;
    }

    Set<String> toRemove = new HashSet<>();
    for (Map.Entry<String, Float> resource : resources.getExtraResourceUsage().entrySet()) {
      String key = (String) resource.getKey();
      float value = (float) usedExtraResources.get(key) - resource.getValue();
      usedExtraResources.put(key, value);
      if (value < epsilon) {
        toRemove.add(key);
      }
    }
    for (String key : toRemove) {
      usedExtraResources.remove(key);
    }
  }

  private synchronized boolean processAllWaitingThreads() throws IOException, InterruptedException {
    boolean anyProcessed = false;
    if (!localRequests.isEmpty()) {
      processWaitingThreads(localRequests);
      anyProcessed = true;
    }
    if (!dynamicWorkerRequests.isEmpty()) {
      processWaitingThreads(dynamicWorkerRequests);
      anyProcessed = true;
    }
    if (!dynamicStandaloneRequests.isEmpty()) {
      processWaitingThreads(dynamicStandaloneRequests);
      anyProcessed = true;
    }
    return anyProcessed;
  }

  private synchronized void processWaitingThreads(Deque<Pair<ResourceSet, LatchWithWorker>> requests)
      throws IOException, InterruptedException {
    Iterator<Pair<ResourceSet, LatchWithWorker>> iterator = requests.iterator();
    while (iterator.hasNext()) {
      Pair<ResourceSet, LatchWithWorker> request = iterator.next();
      if (request.second.latch.getCount() != 0) {
        if (areResourcesAvailable(request.first)) {
          Worker worker = incrementResources(request.first);
          request.second.worker = worker;
          request.second.latch.countDown();
          iterator.remove();
        }
      } else {
        // Cancelled by other side.
        iterator.remove();
      }
    }
  }

  /** Throws an exception if requested extra resource isn't being tracked */
  private void assertExtraResourcesTracked(ResourceSet resources) throws NoSuchElementException {
    for (Map.Entry<String, Float> resource : resources.getExtraResourceUsage().entrySet()) {
      String key = (String) resource.getKey();
      if (!availableResources.getExtraResourceUsage().containsKey(key)) {
        throw new NoSuchElementException(
            "Resource " + key + " is not tracked in this resource set.");
      }
    }
  }

  /** Return true iff all requested extra resources are considered to be available. */
  private boolean areExtraResourcesAvailable(ResourceSet resources) throws NoSuchElementException {
    for (Map.Entry<String, Float> resource : resources.getExtraResourceUsage().entrySet()) {
      String key = (String) resource.getKey();
      float used = (float) usedExtraResources.getOrDefault(key, 0f);
      float requested = resource.getValue();
      float available = availableResources.getExtraResourceUsage().get(key);
      float epsilon = 0.0001f; // Account for possible rounding errors.
      if (requested != 0.0 && used != 0.0 && requested + used > available + epsilon) {
        return false;
      }
    }
    return true;
  }

  // Method will return true if all requested resources are considered to be available.
  @VisibleForTesting
  boolean areResourcesAvailable(ResourceSet resources) throws NoSuchElementException {
    Preconditions.checkNotNull(availableResources);
    // Comparison below is robust, since any calculation errors will be fixed
    // by the release() method.

    WorkerKey workerKey = resources.getWorkerKey();
    int availableWorkers = 0;
    int activeWorkers = 0;
    if (workerKey != null) {
      availableWorkers = this.workerPool.getMaxTotalPerKey(workerKey);
      activeWorkers = this.workerPool.getNumActive(workerKey);
    }
    boolean workerIsAvailable =
        workerKey == null
            || (activeWorkers < availableWorkers && workerPool.couldBeBorrowed(workerKey));

    // We test for tracking of extra resources whenever acquired and throw an
    // exception before acquiring any untracked resource.
    assertExtraResourcesTracked(resources);

    if (usedCpu == 0.0
        && usedRam == 0.0
        && usedExtraResources.isEmpty()
        && usedLocalTestCount == 0
        && workerIsAvailable) {
      return true;
    }
    // Use only MIN_NECESSARY_???_RATIO of the resource value to check for
    // allocation. This is necessary to account for the fact that most of the
    // requested resource sets use pessimistic estimations. Note that this
    // ratio is used only during comparison - for tracking we will actually
    // mark whole requested amount as used.
    double cpu = resources.getCpuUsage() * MIN_NECESSARY_CPU_RATIO;
    double ram = resources.getMemoryMb() * MIN_NECESSARY_RAM_RATIO;
    int localTestCount = resources.getLocalTestCount();

    double availableCpu = availableResources.getCpuUsage();
    double availableRam = availableResources.getMemoryMb();
    int availableLocalTestCount = availableResources.getLocalTestCount();

    double remainingRam = availableRam - usedRam;

    // Resources are considered available if any one of the conditions below is true:
    // 1) If resource is not requested at all, it is available.
    // 2) If resource is not used at the moment, it is considered to be
    // available regardless of how much is requested. This is necessary to
    // ensure that at any given time, at least one thread is able to acquire
    // resources even if it requests more than available.
    // 3) If used resource amount is less than total available resource amount.
    boolean cpuIsAvailable = cpu == 0.0 || usedCpu == 0.0 || usedCpu + cpu <= availableCpu;
    boolean ramIsAvailable = ram == 0.0 || usedRam == 0.0 || ram <= remainingRam;
    boolean localTestCountIsAvailable =
        localTestCount == 0
            || usedLocalTestCount == 0
            || usedLocalTestCount + localTestCount <= availableLocalTestCount;
    boolean extraResourcesIsAvailable = areExtraResourcesAvailable(resources);
    return cpuIsAvailable
        && ramIsAvailable
        && extraResourcesIsAvailable
        && localTestCountIsAvailable
        && workerIsAvailable;
  }

  @VisibleForTesting
  synchronized int getWaitCount() {
    return localRequests.size() + dynamicStandaloneRequests.size() + dynamicWorkerRequests.size();
  }

  private static class LatchWithWorker {
    public final CountDownLatch latch;
    public Worker worker;

    public LatchWithWorker(CountDownLatch latch, Worker worker) {
      this.latch = latch;
      this.worker = worker;
    }
  }
}
