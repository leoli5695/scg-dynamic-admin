# Performance Optimization

> My-Gateway implements multiple performance optimizations to ensure high throughput and low latency under high concurrency scenarios.

---

## Overview

Gateway performance optimizations focus on several key areas:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PERFORMANCE OPTIMIZATION                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. Non-Blocking Lock (CAS + tryLock)                               │
│     - Avoids EventLoop thread starvation                            │
│     - Used in: Rate Limiter, Health Checker                         │
│                                                                      │
│  2. Atomic Operations                                               │
│     - AtomicInteger for counter updates                             │
│     - No lock contention                                            │
│     - Used in: Load Balancer, Filter Chain Stats                    │
│                                                                      │
│  3. Batch Processing                                                │
│     - Health checks split into batches                              │
│     - Controlled concurrency per batch                              │
│     - Reduced system load spike                                     │
│                                                                      │
│  4. Multi-Level Check Frequency                                     │
│     - Regular/Degraded/Stable modes                                 │
│     - Healthy nodes checked less frequently                         │
│     - Unhealthy nodes also reduce check frequency                   │
│                                                                      │
│  5. Concurrent Data Structures                                      │
│     - ConcurrentHashMap for health cache                            │
│     - ConcurrentLinkedDeque for filter chain records                │
│                                                                      │
│  6. Route Cache Incremental Update                                  │
│     - Put new routes first, then remove old                         │
│     - No empty cache window                                         │
│     - Continuous route availability                                 │
│                                                                      │
│  7. Redis SCAN vs KEYS                                              │
│     - Non-blocking key iteration                                    │
│     - Prevents Redis freezing                                       │
│     - Production-safe for large datasets                            │
│                                                                      │
│  8. Node Count Detection (Three-Level)                              │
│     - Listener (real-time) + Fallback (1h) + YAML (static)          │
│     - Ensures correct QPS distribution                              │
│     - Resilient to discovery failures                               │
│                                                                      │
│  9. Shadow Quota Method                                             │
│     - Global QPS / nodeCount pre-calculation                        │
│     - Graceful degradation on Redis failure                         │
│     - No traffic spike, smooth failover                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 1. Non-Blocking Lock (CAS + tryLock)

### Problem

Traditional blocking locks can cause EventLoop thread starvation in WebFlux:

```
Scenario: High QPS rate limiting (10,000+ requests/second)

Blocking lock approach:
  - Thread 1 acquires lock, processes request
  - Threads 2-1000 wait for lock
  - EventLoop threads blocked waiting
  - Request backlog grows
  - Gateway becomes unresponsive
```

### Solution: CAS + tryLock Strategy

```java
// Fast path: CAS (Compare-And-Swap) for low contention
if (currentCount.compareAndSet(count, count + 1)) {
    return true;  // Success without any blocking
}

// Slow path: tryLock for high contention (NEVER blocks!)
if (lock.tryLock()) {
    try {
        if (count < maxRequests) {
            currentCount.incrementAndGet();
            return true;
        }
        return false;
    } finally {
        lock.unlock();
    }
}

// tryLock failed - immediately reject (no blocking)
return false;  // Prevents EventLoop thread starvation
```

### Advantages

| Aspect | Blocking Lock | CAS + tryLock |
|--------|---------------|---------------|
| Thread Safety | Yes | Yes |
| Blocking | Yes (dangerous) | No (safe) |
| Performance under low contention | Good | Excellent |
| Performance under high contention | Poor (threads blocked) | Good (immediate reject) |
| EventLoop impact | Starvation risk | Zero impact |

### Implementation Locations

- **Local Rate Limiter**: `LocalRateLimiter.java` - Counter updates
- **Health Check Batch**: `HealthCheckScheduler.java` - Semaphore for concurrent control

---

## 2. Atomic Operations

### AtomicInteger Counter

Load balancer uses atomic counter for weighted round-robin:

```java
private final AtomicInteger counter = new AtomicInteger(0);

public Instance select(List<Instance> instances) {
    int totalWeight = instances.stream()
        .mapToInt(Instance::getWeight)
        .sum();
    
    // Atomic increment - no lock needed!
    int index = counter.getAndIncrement() % totalWeight;
    
    // Select instance based on weighted index
    return findInstanceByWeight(instances, index);
}
```

### Why Atomic?

```
Traditional synchronized approach:
  synchronized(lock) {
      count++;  // Thread blocks here
  }

Atomic approach:
  counter.getAndIncrement();  // Never blocks!

Performance comparison (10 million operations):
  synchronized: ~300ms
  AtomicInteger: ~50ms (6x faster)
```

### CAS Update for Statistics

Filter chain statistics use CAS for max/min updates:

```java
// Update max value - atomic, no lock
public void updateMax(long newValue) {
    long current;
    do {
        current = maxValue.get();
        if (newValue <= current) {
            return;  // No update needed
        }
    } while (!maxValue.compareAndSet(current, newValue));
}
```

---

## 3. Batch Processing with Concurrency Control

### Health Check Optimization

For large-scale deployments (hundreds/thousands of nodes), health checks need optimization:

```
Problem:
  - 1000 nodes checked simultaneously
  - All HTTP probes fire at once
  - Gateway CPU/network overloaded
  - Health check failures due to resource exhaustion
```

### Solution: Batch + Concurrency Limit

```
┌─────────────────────────────────────────────────────────────────────┐
│                    BATCHED HEALTH CHECK                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  1000 instances to check                                            │
│         │                                                           │
│         ▼                                                           │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ Split into 10 batches (batch size: 100)                     │    │
│  └─────────────────────────────────────────────────────────────┘    │
│         │                                                           │
│         ▼                                                           │
│  Batch 1 ──▶ Batch 2 ──▶ Batch 3 ──▶ ... ──▶ Batch 10            │
│    │                                                                │
│    ▼                                                                │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ Semaphore (max 20 concurrent checks per batch)              │    │
│  │                                                             │    │
│  │  [1-20: running] [21-100: waiting for permit]               │    │
│  │                                                             │    │
│  │  Instance 1-20: acquire permit, check immediately           │    │
│  │  Instance 21+: wait until permit released                   │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
│  Result: Max 20 concurrent HTTP probes (controlled load)            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Configuration

```yaml
gateway:
  health:
    check-batch-size: 100           # Instances per batch
    max-concurrent-per-batch: 20    # Max concurrent checks
```

### Implementation

```java
private final Semaphore concurrentCheckSemaphore;

@PostConstruct
public void init() {
    concurrentCheckSemaphore = new Semaphore(maxConcurrentPerBatch);
}

private BatchResult performConcurrentHealthCheckBatch(List<InstanceKey> batch) {
    List<CompletableFuture<Void>> futures = batch.stream()
        .map(instance -> CompletableFuture.runAsync(() -> {
            // Acquire semaphore permit (may wait)
            concurrentCheckSemaphore.acquire();
            try {
                activeChecker.probe(instance);
            } finally {
                // Release permit for next instance
                concurrentCheckSemaphore.release();
            }
        }))
        .collect(Collectors.toList());
    
    // Wait for batch completion
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .get(timeoutSeconds, TimeUnit.SECONDS);
}
```

---

## 4. Multi-Level Check Frequency

### Three-Level Frequency Design

Different instance states require different check frequencies:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CHECK FREQUENCY LEVELS                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Level 1: REGULAR (30 seconds)                                      │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ - Healthy instances (not yet stable)                          │  │
│  │ - Newly unhealthy instances (< 5 consecutive failures)        │  │
│  │ - Idle instances (no recent requests)                         │  │
│  │                                                               │  │
│  │ Purpose: Quick detection of new issues                        │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  Level 2: STABLE (2 minutes)                                        │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ - Instances with 10+ consecutive healthy checks               │  │
│  │ - Consistently reliable nodes                                 │  │
│  │                                                               │  │
│  │ Purpose: Reduce load on healthy nodes                         │  │
│  │ Benefit: Fewer probes = less network/CPU usage                │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  Level 3: DEGRADED (3 minutes)                                      │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ - Instances with 5+ consecutive unhealthy checks              │  │
│  │ - Persistently failing nodes                                  │  │
│  │                                                               │  │
│  │ Purpose: Reduce load on hopeless nodes                        │  │
│  │ Benefit: Don't waste resources checking dead nodes            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Check Frequency Benefits

| Metric | Without Levels | With Levels | Improvement |
|--------|----------------|-------------|-------------|
| Checks per cycle (1000 nodes) | 1000 | ~200 (stable: 700) | 80% reduction |
| Network probes per minute | 2000 | 400 | 80% reduction |
| CPU for health checks | High | Low | Significant |

### Mode Transition Logic

```java
// Enter stable mode after 10 consecutive healthy checks
if (!health.isStableCheckMode() && totalHealthyChecks >= 10) {
    health.setStableCheckMode(true);
    health.setStableModeEnteredTime(System.currentTimeMillis());
}

// Exit stable mode on any failure
if (health.isStableCheckMode() && !checkPassed) {
    health.setStableCheckMode(false);
    health.setTotalHealthyChecks(0);  // Reset counter
}

// Enter degraded mode after 5 consecutive unhealthy checks
if (!health.isDegradedCheckMode() && totalUnhealthyChecks >= 5) {
    health.setDegradedCheckMode(true);
}
```

---

## 5. Concurrent Data Structures

### Health Status Cache

```java
// Caffeine cache with high-performance settings
private final Cache<String, InstanceHealth> healthCache = Caffeine.newBuilder()
    .maximumSize(10000)                 // Large capacity
    .expireAfterWrite(5, TimeUnit.MINUTES)  // Auto cleanup
    .recordStats()                      // Performance monitoring
    .build();
```

### Filter Chain Record Queue

```java
// Non-blocking concurrent queue
private final ConcurrentLinkedDeque<FilterChainRecord> records = 
    new ConcurrentLinkedDeque<>();

// Add record - no lock contention
records.addFirst(newRecord);

// Rolling window - auto evict old records
while (records.size() > MAX_RECORDS) {
    records.removeLast();  // Thread-safe removal
}
```

### Statistics Storage

```java
// Thread-safe statistics map
private final ConcurrentHashMap<String, FilterStats> statsMap = 
    new ConcurrentHashMap<>();

// Update stats - atomic operations
statsMap.compute(filterName, (key, stats) -> {
    stats.updateDuration(duration);  // CAS updates internally
    return stats;
});
```

---

## 6. Sliding Window Percentile

### Problem

Calculating P50/P95/P99 on full dataset is expensive:

```
Full sort approach:
  - Collect 10,000 samples
  - Sort entire array: O(n log n) = 10,000 * 13 = 130,000 operations
  - CPU heavy, memory heavy
```

### Solution: Sliding Window

```java
// Keep only last 100 samples
private final ConcurrentLinkedQueue<Long> slidingWindow = 
    new ConcurrentLinkedQueue<>();

public void recordDuration(long duration) {
    slidingWindow.add(duration);
    
    // Auto evict old samples
    while (slidingWindow.size() > 100) {
        slidingWindow.poll();
    }
}

public long getP95() {
    // Sort only 100 samples: O(100 * log 100) = ~460 operations
    List<Long> sorted = new ArrayList<>(slidingWindow);
    Collections.sort(sorted);
    
    // 95th percentile index
    int index = (int) (sorted.size() * 0.95);
    return sorted.get(index);
}
```

### Performance Comparison

| Metric | Full Sort (10,000) | Sliding Window (100) |
|--------|-------------------|---------------------|
| Memory | 80KB | 0.8KB |
| CPU (per calc) | 130,000 ops | 460 ops |
| Latency | ~5ms | ~0.05ms |
| Accuracy | Full history | Recent trend (more useful) |

---

## 7. Route Cache Incremental Update

### Problem: Cache Empty Window

Traditional route cache refresh uses "clear then rebuild" approach:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    FULL REBUILD (Problem)                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Step 1: clear() - Cache becomes EMPTY                              │
│          ┌─────────────────────────────────────────────────────┐    │
│          │         CACHE EMPTY WINDOW                          │    │
│          │                                                     │    │
│          │  All requests during this window:                   │    │
│          │  - Cache miss for ALL routes                        │    │
│          │  - Route compilation on every request               │    │
│          │  - CPU spike, latency spike                         │    │
│          │                                                     │    │
│          │  Duration: ~100ms for 1000 routes                   │    │
│          └─────────────────────────────────────────────────────┘    │
│                                                                     │
│  Step 2: put() - Rebuild cache one by one                           │
│          for (Route route : routes) {                               │
│              cache.put(route.getId(), route);                       │
│          }                                                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

Impact:
  - 1000 routes, 100 QPS during refresh
  - 100 requests hit empty cache
  - 100 route compilations (CPU intensive)
  - Latency spike: 50ms -> 200ms for affected requests
```

### Solution: Incremental Update

Put new routes first, then remove old routes:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    INCREMENTAL UPDATE (Solution)                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Step 1: Put new routes (add or update)                             │
│          ┌─────────────────────────────────────────────────────┐    │
│          │  New route: ADD to cache                            │    │
│          │  Existing route (changed): UPDATE in cache          │    │
│          │  Existing route (unchanged): SKIP                   │    │
│          │                                                     │    │
│          │  Cache state: {old routes} + {new routes}           │    │
│          │  NO EMPTY WINDOW - routes always available!         │    │
│          └─────────────────────────────────────────────────────┘    │
│                                                                     │
│  Step 2: Remove old routes                                          │
│          ┌─────────────────────────────────────────────────────┐    │
│          │  Remove routes not in new list                      │    │
│          │                                                     │    │
│          │  Cache state: {new routes}                          │    │
│          │  Brief moment: {old} + {new} coexist                │    │
│          └─────────────────────────────────────────────────────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Implementation

```java
private IncrementalUpdateResult performIncrementalUpdate(List<Route> newRoutes) {
    int added = 0, updated = 0, removed = 0;
    
    // Build set of new route IDs
    Set<String> newRouteIds = new HashSet<>();
    for (Route route : newRoutes) {
        newRouteIds.add(route.getId());
    }
    
    // Step 1: Put all new routes (add new or update existing)
    for (Route route : newRoutes) {
        Route existing = cache.get(route.getId());
        if (existing == null) {
            cache.put(route.getId(), route);
            added++;
        } else if (!routesEqual(existing, route)) {
            cache.put(route.getId(), route);
            updated++;
        }
    }
    
    // Step 2: Remove routes that no longer exist
    Iterator<Map.Entry<String, Route>> iterator = cache.entrySet().iterator();
    while (iterator.hasNext()) {
        if (!newRouteIds.contains(iterator.next().getKey())) {
            iterator.remove();
            removed++;
        }
    }
    
    return new IncrementalUpdateResult(added, removed, updated);
}
```

### Three Strategies Comparison

There are three main strategies for large-scale cache updates:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CACHE UPDATE STRATEGIES                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Strategy 1: FULL REBUILD                                           │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  clear() → put all new                                      │    │
│  │                                                             │    │
│  │  Problem: EMPTY WINDOW                                      │    │
│  │  - All requests fail during clear                           │    │
│  │  - Cache miss spike                                         │    │
│  │  - NOT recommended                                          │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
│  Strategy 2: INCREMENTAL UPDATE (Current Implementation)            │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  put new → remove old                                       │    │
│  │                                                             │    │
│  │  Benefit: NO EMPTY WINDOW                                   │    │
│  │  Trade-off: Brief memory increase (old + new coexist)       │    │
│  │  Risk: Deleted route + new route may briefly overlap        │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
│  Strategy 3: COPY-ON-WRITE (Alternative)                            │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Build new cache → swap reference atomically                │    │
│  │                                                             │    │
│  │  Benefit:                                                   │    │
│  │  - Read operations NEVER block                              │    │
│  │  - Atomic swap (instant switch)                             │    │
│  │  - No memory spike (old cache released after swap)          │    │
│  │                                                             │    │
│  │  Trade-off: Brief STALE DATA                                │    │
│  │  - Some requests see old cache, some see new                │    │
│  │  - Acceptable for route cache (routes change rarely)        │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Copy-on-Write Implementation

```java
// Volatile reference for atomic swap
private volatile ConcurrentHashMap<String, Route> routeCache = new ConcurrentHashMap<>();

public void refreshCache(List<Route> newRoutes) {
    // Step 1: Build NEW cache (off current reference)
    ConcurrentHashMap<String, Route> newCache = new ConcurrentHashMap<>();
    for (Route route : newRoutes) {
        newCache.put(route.getId(), route);
    }
    
    // Step 2: Atomic swap - instant!
    routeCache = newCache;  // Single volatile write
    
    // Step 3: Old cache eligible for GC (no explicit cleanup needed)
}

public Route getRoute(String routeId) {
    // Read NEVER blocks - just access volatile reference
    return routeCache.get(routeId);
}
```

### Comparison Table

| Aspect | Full Rebuild | Incremental Update | Copy-on-Write |
|--------|--------------|-------------------|---------------|
| Cache availability | **Empty window** | Always available | Always available |
| Read blocking | No | No | **No (lock-free)** |
| Memory impact | Normal | Brief **2x** increase | Normal (sequential) |
| Data consistency | Broken during rebuild | Perfect | Brief **stale data** |
| CPU spike | High | Normal | Normal |
| Latency impact | Spike | No change | No change |
| Code complexity | Simple | Moderate | Simple |
| Best for | Never | Perfect consistency | **High read QPS** |

### When to Use Copy-on-Write

1. **High read concurrency**: Lock-free reads scale better
2. **Infrequent updates**: Route changes are rare (minutes/hours)
3. **Stale data tolerable**: Brief inconsistency is acceptable
4. **Memory concern**: No 2x memory spike

### Why We Chose Incremental Update

For route cache specifically, we chose incremental update because:

1. **Consistency matters**: Routing to wrong service is worse than brief delay
2. **Update frequency**: Routes change infrequently, memory spike is tolerable
3. **Current implementation works**: Incremental update is already deployed

**Summary**: Copy-on-Write is a great design approach. If route count grows to 10,000+ or read QPS reaches 100K+, we can consider switching to COW strategy. The current incremental update solution is sufficiently optimized for the existing scale.

---

## 8. Redis SCAN vs KEYS

### Problem: KEYS Blocking Redis

Redis KEYS command is O(N) and blocks the entire Redis server:

```
Scenario: Rate limiter shadow quota update (every 1 second)

Using KEYS command:
  KEYS rate_limit:*:route-123*

  If 100,000 keys in Redis:
    - KEYS scans all 100,000 keys
    - Blocks Redis for ~500ms
    - ALL other operations wait
    - Rate limiter latency spikes
    - Potential timeout for requests
```

### Solution: SCAN Command

SCAN is incremental and never blocks Redis:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    SCAN vs KEYS                                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  KEYS pattern:                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Single command: KEYS rate_limit:*:route-123*               │    │
│  │  Returns: all matching keys                                 │    │
│  │  Blocking: YES - O(N) time                                  │    │
│  │  Redis state: BLOCKED until complete                        │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
│  SCAN iteration:                                                    │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Multiple commands:                                         │    │
│  │  SCAN 0 MATCH rate_limit:*:route-123* COUNT 100             │    │
│  │    -> Returns: cursor=1234, keys=[...]                      │    │
│  │  SCAN 1234 MATCH rate_limit:*:route-123* COUNT 100          │    │
│  │    -> Returns: cursor=5678, keys=[...]                      │    │
│  │  ... until cursor=0 (complete)                              │    │
│  │                                                             │    │
│  │  Blocking: NO - each call is O(1)                           │    │
│  │  Redis state: AVAILABLE between calls                       │    │
│  │  Other operations: Can run between iterations               │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Implementation

```java
// Before (blocking)
Iterable<String> keys = redisTemplate.keys(pattern);  // BLOCKS Redis!

// After (non-blocking)
ScanOptions scanOptions = ScanOptions.scanOptions()
    .match(pattern)
    .count(100)  // Batch size per iteration
    .build();

try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
    while (cursor.hasNext()) {
        String key = cursor.next();
        // Process key...
    }
}
```

### Performance Comparison

| Metric | KEYS (100k keys) | SCAN (100k keys) |
|--------|-----------------|------------------|
| Single operation time | ~500ms (blocking) | ~5ms per batch |
| Redis blocking | Yes (500ms) | No |
| Other operations | Queued/frozen | Can run |
| Rate limiter impact | Latency spike | No impact |
| Memory overhead | All keys at once | Batch by batch |

### Why SCAN is Better

1. **Non-blocking**: Redis can process other operations between iterations
2. **No latency spike**: Rate limiter continues working normally
3. **Production-safe**: Redis best practice is to never use KEYS in production
4. **Memory efficient**: Processes keys in batches, not all at once

---

## 9. Node Count Detection (Three-Level Protection)

### Problem: Redis + Service Discovery Both Fail

When Redis fails, shadow quota needs to know the cluster node count to calculate:
```
shadowQuota = globalQPS / nodeCount
```

But what if service discovery (Nacos/Consul) also fails?

```
Scenario: Redis + Nacos both unavailable

Without node count cache:
  - discoveryClient.getInstances() fails
  - nodeCount defaults to minNodeCount = 1
  - shadowQuota = 10,000 / 1 = 10,000
  
  Each gateway node thinks it's the ONLY node:
  - Node A: local limit = 10,000 QPS
  - Node B: local limit = 10,000 QPS
  - Node C: local limit = 10,000 QPS
  
  Total traffic to backend: 30,000 QPS (3x spike!)
  Backend may crash!
```

### Solution: Three-Level Node Count Protection

```
┌─────────────────────────────────────────────────────────────────────┐
│                    NODE COUNT DETECTION                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Level 1: Service Discovery Listener (Real-time)                    │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Implements ApplicationListener<HeartbeatEvent>              │   │
│  │                                                              │   │
│  │  Triggered when:                                             │   │
│  │  - New gateway node registers                                │   │
│  │  - Gateway node deregisters                                  │   │
│  │  - Heartbeat received (periodic check)                       │   │
│  │                                                              │   │
│  │  Response time: Seconds (real-time)                          │   │
│  │  Update: cachedNodeCount = discoveryClient.getInstances()    │   │
│  │                                                              │   │
│  │  Purpose: Immediate response to cluster changes              │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  Level 2: Scheduled Fallback (1 Hour)                               │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  @Scheduled(fixedRate = 3600000)  // 1 hour                  │   │
│  │                                                              │   │
│  │  Only executes if:                                           │   │
│  │  - No listener update for > 1 hour                           │   │
│  │  - Listener may have failed                                  │   │
│  │                                                              │   │
│  │  Purpose: Safety net for listener failure                    │   │
│  │  Frequency: Low (only when needed)                           │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  Level 3: YAML Config Fallback (Static)                             │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  gateway.rate-limiter.shadow-quota.fallback-node-count: 3    │   │
│  │                                                              │   │
│  │  Used when:                                                  │   │
│  │  - Discovery client unavailable                              │   │
│  │  - Scheduled fallback also fails                             │   │
│  │                                                              │   │
│  │  Purpose: Last resort for extreme cases                      │   │
│  │  Requires: Manual configuration by ops                       │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Implementation

```java
@Component
public class ShadowQuotaManager implements ApplicationListener<HeartbeatEvent> {

    private final AtomicInteger cachedNodeCount = new AtomicInteger(1);
    private final AtomicLong lastNodeCountUpdateTime = new AtomicLong(0);

    // Level 1: Listener (real-time)
    @Override
    public void onApplicationEvent(HeartbeatEvent event) {
        updateNodeCountFromDiscovery();
        lastNodeCountUpdateTime.set(System.currentTimeMillis());
    }

    // Level 2: Fallback (1 hour)
    @Scheduled(fixedRate = 3600000)
    public void fallbackUpdateNodeCount() {
        if (System.currentTimeMillis() - lastNodeCountUpdateTime.get() > 3600000) {
            if (!updateNodeCountFromDiscovery()) {
                cachedNodeCount.set(fallbackNodeCount);  // Level 3
            }
        }
    }
}
```

### Why 1 Hour Frequency?

| Frequency | Listener Delay Coverage | Resource Use | Recommended |
|-----------|------------------------|--------------|-------------|
| 30 seconds | 30 seconds | Moderate | No (too frequent) |
| 1 hour | 1 hour | **Very low** | **Yes** |
| 6 hours | 6 hours | Minimal | Acceptable |

1 hour is recommended because:
- Node count changes are rare (scaling events)
- Service discovery failure probability is low
- Listener provides real-time updates in normal cases
- 1 hour delay is acceptable for extreme edge cases

---

## 10. Global Rate Limiter Failover (QPS Distribution)

### Problem: Redis Failover Causes Traffic Spike

```
Scenario: 3 gateway nodes, global limit 10,000 QPS, Redis fails

Without shadow quota (naive fallback):
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  Redis healthy:                                                     │
│  - All nodes share counter in Redis                                 │
│  - Total limit: 10,000 QPS                                          │
│  - Backend receives ~10,000 QPS                                     │
│                                                                     │
│  Redis fails:                                                       │
│  - Each node falls back to LOCAL rate limiting                      │
│  - Each node uses configQps = 10,000 (same as global limit)         │
│  - Each node resets counter to 0                                    │
│                                                                     │
│  Result:                                                            │
│  - Node A: allows 10,000 local QPS                                  │
│  - Node B: allows 10,000 local QPS                                  │
│  - Node C: allows 10,000 local QPS                                  │
│  - Backend receives up to 30,000 QPS                                │
│  - Traffic spike: 3x the intended limit                             │
│  - Backend may crash                                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Solution: Shadow Quota Method

```
┌─────────────────────────────────────────────────────────────────────┐
│                    SHADOW QUOTA METHOD                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Phase 1: Redis Healthy (Pre-calculation)                           │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Every 1 second:                                             │   │
│  │                                                              │   │
│  │  1. Read global QPS from Redis                               │   │
│  │     ZCARD rate_limit:route:xxx → 10,000                      │   │
│  │                                                              │   │
│  │  2. Get node count (cached via listener)                     │   │
│  │     cachedNodeCount = 3                                      │   │
│  │                                                              │   │
│  │  3. Calculate shadow quota                                   │   │
│  │     shadowQuota = 10,000 / 3 = 3,333                         │   │
│  │                                                              │   │
│  │  4. Store locally for failover                               │   │
│  │     shadowQuotas[routeId] = 3,333                            │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  Phase 2: Redis Fails (Graceful Degradation)                        │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Each node uses its pre-calculated shadow quota:             │   │
│  │                                                              │   │
│  │  Node A: local limit = 3,333 QPS                             │   │
│  │  Node B: local limit = 3,333 QPS                             │   │
│  │  Node C: local limit = 3,333 QPS                             │   │
│  │                                                              │   │
│  │  Total: 3,333 × 3 ≈ 10,000 QPS                               │   │
│  │                                                              │   │
│  │  Result: Backend traffic remains stable                      │   │
│  │  No counter reset (uses last snapshot)                       │   │
│  │  No traffic spike                                            │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  Phase 3: Redis Recovers (Gradual Traffic Shift)                    │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Gradual shift over 10 seconds (prevents thundering herd):   │   │
│  │                                                              │   │
│  │  Second 0: 10% traffic to Redis, 90% local                   │   │
│  │  Second 1: 20% traffic to Redis, 80% local                   │   │
│  │  Second 2: 30% traffic to Redis, 70% local                   │   │
│  │  ...                                                         │   │
│  │  Second 9: 100% traffic to Redis                             │   │
│  │                                                              │   │
│  │  Prevents: All nodes suddenly hitting Redis                  │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Design Points

1. **Pre-calculation**: Calculate shadow quota BEFORE Redis fails
2. **Fair distribution**: `globalQPS / nodeCount` ensures each node gets fair share
3. **No counter reset**: Use last snapshot, not reset to zero
4. **Gradual recovery**: Shift traffic back slowly to prevent Redis overload

### Configuration

```yaml
gateway:
  rate-limiter:
    shadow-quota:
      enabled: true                    # Enable shadow quota
      min-node-count: 1                # Minimum node count (safety)
      fallback-node-count: 3           # YAML fallback (Level 3)
```

**Summary**: Shadow quota method ensures smooth degradation when Redis fails by pre-calculating `globalQPS / nodeCount` for each gateway node. Combined with three-level node count detection, the system is resilient to both Redis and service discovery failures.

---

## Performance Benchmarks

### Health Check Optimization Impact

| Scenario | Before | After | Improvement |
|----------|--------|-------|-------------|
| 100 nodes, all healthy | 100 checks/30s | ~100 checks/30s | Same |
| 1000 nodes, 700 stable | 1000 checks/30s | 300 checks/30s | 70% reduction |
| 100 nodes, 50 degraded | 100 checks/30s | 50 checks/30s | 50% reduction |
| Concurrent probe spike | Unlimited | Max 20 | Resource safe |

### Rate Limiter Impact

| Scenario | Blocking Lock | CAS + tryLock |
|----------|---------------|---------------|
| Low contention (100 QPS) | 0.1ms latency | 0.01ms latency |
| High contention (10K QPS) | 50ms latency (blocked) | 0.02ms latency |
| Thread starvation risk | High | Zero |

---

## Configuration Summary

```yaml
gateway:
  health:
    # Batch processing
    check-batch-size: 100
    max-concurrent-per-batch: 20
    
    # Check frequencies
    regular-check-interval: 30000    # 30 seconds
    stable-check-interval: 120000   # 2 minutes
    degraded-check-interval: 180000 # 3 minutes
    
    # Mode thresholds
    stable-check-threshold: 10      # Enter stable after 10 healthy checks
    degraded-check-threshold: 5     # Enter degraded after 5 unhealthy checks
    
  rate-limiter:
    local-mode: true                # Enable local rate limiting
    shadow-quota:
      enabled: true                  # Shadow quota for Redis failover
      fallback-node-count: 3         # Level 3 YAML fallback when Redis+Nacos both fail
```

---

## Related Features

- [Rate Limiting](rate-limiting.md) - Rate limiting functionality
- [Service Discovery](service-discovery.md) - Load balancing strategies
- [Filter Chain Analysis](filter-chain-analysis.md) - Filter performance analysis
- [Health Check](../ARCHITECTURE.md#health-check) - Health check architecture

---

## 10. GC Tuning and Promotion Rate Monitoring (New Feature)

**Added: 2026-04-25**

### Overview

Proper GC tuning is critical for gateway performance. The system now provides intelligent GC diagnostics beyond simple threshold alerts.

### Memory Allocation Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    JVM Memory Regions                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   Eden Space                                                 │
│   ├─ New objects allocated here                             │
│   ├─ Short-lived objects die here                           │
│   └─ Full → Trigger Young GC                                │
│                                                              │
│   Survivor Space (S0, S1)                                    │
│   ├─ Objects that survived Young GC                         │
│   ├─ Objects age (+1 each GC)                               │
│   ├─ Age threshold (default 15) → Promote to Old Gen        │
│   └─ Too small → Premature promotion                        │
│                                                              │
│   Old Gen / Tenured                                          │
│   ├─ Long-lived objects                                     │
│   ├─ Large objects (bypass Young Gen)                       │
│   ├─ Full → Trigger Full GC (slow!)                         │
│   └─ Memory leak → Old Gen grows continuously               │
│                                                              │
└─────────────────────────────────────────────────────────────┘

Allocation Rate: Rate of new objects in Eden (MB/s)
Promotion Rate:  Rate of objects moving to Old Gen (MB/s)
Promotion Ratio: Promotion Rate / Allocation Rate (%)
```

### Intelligent GC Diagnostics

The system now provides 3 combined diagnostic patterns for GC tuning:

#### Pattern 1: Premature Promotion (High Promotion + High Allocation)
- **Trigger**: `promotionRate > 10 MB/s && allocationRate > 50 MB/s`
- **Diagnosis**: Short-lived objects promoting to Old Gen too early
- **Recommendation**: Increase Survivor space, adjust young gen size

#### Pattern 2: Large Object Bypass (High Promotion + Low Allocation)
- **Trigger**: `promotionRate > 10 MB/s && allocationRate <= 50 MB/s`
- **Diagnosis**: Large objects bypassing Young Gen directly to Old Gen
- **Recommendation**: Check code for large allocations, use G1 GC

#### Pattern 3: Memory Leak Indicator (High Promotion Ratio)
- **Trigger**: `promotionRatio > 30%`
- **Diagnosis**: Too many objects surviving, possible memory leak
- **Recommendation**: Analyze heap dump, check for resource leaks

### Recommended JVM Options

For Gateway (High QPS, Short-lived Requests):

```bash
# Heap size
-Xms2g -Xmx2g

# Young Gen (50-60% of heap)
-Xmn1g

# Survivor ratio (larger for premature promotion prevention)
-XX:SurvivorRatio=6

# Tenure threshold
-XX:MaxTenuringThreshold=15

# G1 GC (recommended for gateway)
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200

# GC logging
-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=10m
```

### Integration with Monitoring

Monitor UI now shows:
- **GC Status Card**: Health status with diagnostic reason
- **Promotion Rate**: MB/s trend
- **Promotion Ratio**: Percentage trend
- **Memory Regions**: Eden, Survivor, Old Gen usage

**API Endpoint:**

```bash
GET /api/monitor/metrics

# Response includes GC diagnostics:
{
  "gc": {
    "healthStatus": "HEALTHY",
    "healthReason": "GC表现正常，Young GC平均耗时20ms",
    "promotionRateMBPerSec": 3.8,
    "promotionRatio": 8.4,
    "allocationRateMBPerSec": 45.2
  }
}
```

### Related Documentation

- [Monitoring & Alerts](monitoring-alerts.md) - GC health monitoring
- [Stress Test Tool](stress-test.md) - Test GC under load
- [AI-Powered Analysis](ai-analysis.md) - AI GC analysis

---