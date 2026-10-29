# Stress Test Module Optimization Plan

> **STATUS (verified against code on 2026-10-28)**: this is a one-off optimization plan and is **not**
> kept in sync with the codebase. Current state:
> - **Done**: §1 streaming statistics (but see the file-name correction below), §2 input validation /
>   SSRF protection, §4 service-layer unit tests (14 `@Test` methods — verified), and short-term item
>   #1 (the memory leak is fixed, though by a **different class** than the one described here).
> - **NOT done**: §3 database migration (**never implemented** — see that section), short-term item #3
>   (Micrometer metrics), and the RBAC / rate-limiting suggestions under "Security Best Practices".
> - ⚠️ The stress-test module was refactored in commit `ed93868` (which added
>   `StressTestStatistics.java`). Several class names and code snippets below no longer match HEAD.
>   `StressTestOptimizer.java` **does not exist**.

## Overview

This document details the optimization plan for the gateway management platform's stress test module, addressing critical issues including memory leaks, security, performance, and test coverage.

## Identified Issues

### 🔴 High Priority Issues

1. **Memory Leak Risk** - Storing all test results in memory list — ✅ **resolved** (see §1 and short-term item #1)
2. **Missing Input Validation** - May lead to resource exhaustion or invalid configuration — ✅ **resolved** (see §2)
3. **SSRF Security Vulnerability** - Target URL not validated — ✅ **resolved** (see §2)
4. **Missing Database Migration** - Relies on JPA auto table creation — ❌ **STILL OPEN.**
   No migration file was ever added (see §3). Tables are still created by JPA/Hibernate; at runtime
   `SPRING_JPA_HIBERNATE_DDL_AUTO=update` is set in the container env, which overrides
   `application-prod.yml`'s `ddl-auto: validate` (OS env wins over in-jar profile yml).
5. **Poor Percentile Calculation Performance** - O(n log n) sorting operation — ✅ **resolved** (bounded-array histogram)

### 🟡 Medium Priority Issues

6. **Synchronized List Contention** - Performance bottleneck under high concurrency — ✅ **resolved** (no shared result list remains)
7. **Insufficient Error Handling** - Generic exceptions lose context
8. **Code Quality** - Large method complexity, magic numbers
9. **Insufficient Test Coverage** - Missing service layer and concurrency tests — ✅ partially addressed (see §4)

## Implemented Optimizations

### 1. ✅ Streaming Statistics Calculation (Solves Memory Leak)

**File**: `gateway-admin/src/main/java/com/leoli/gateway/admin/service/StreamingStatistics.java`

> ⚠️ **Correction**: this section previously named the file `StressTestOptimizer.java`.
> **No such class exists anywhere in the repository.** The streaming-statistics implementation is
> `StreamingStatistics.java` (path above). Note that `StreamingStatistics` is used by
> `StressTestServiceTest` and returns `model/StressTestMetrics`; the production hot path in
> `StressTestService` actually uses the newer `StressTestStatistics` — see short-term item #1.

**Before**:
```java
// Store all results in memory - causes OOM for million-level requests
List<TestResult> allResults = Collections.synchronizedList(new ArrayList<>());
future.thenAccept(allResults::add);
```

**After**:
```java
// Use streaming statistics - fixed memory footprint
StreamingStatistics stats = new StreamingStatistics();
stats.recordResponseTime(responseTimeMs, success);
```

**Key Benefits**:
- ✅ Uses Welford online algorithm for mean and variance calculation
- ✅ Atomic operations track min/max values
- ✅ Histogram-based percentile approximation (10 buckets, fixed memory)
- ✅ Thread-safe lock-free design (LongAdder)
- ✅ Memory footprint reduced from O(n) to O(1)

**Memory Comparison**:
| Test Scale | Before | After | Savings |
|------------|--------|-------|---------|
| 1,000 requests | ~100 KB | ~1 KB | 99% |
| 100,000 requests | ~10 MB | ~1 KB | 99.99% |
| 1,000,000 requests | ~100 MB (may OOM) | ~1 KB | 99.999% |

### 2. ✅ Input Validation and Security Control

**File**: `StressTestValidator.java`

**Validation Items**:
```java
// 1. Concurrent users limit (1-500)
validateConcurrentUsers(concurrentUsers);

// 2. Total requests limit (1-1,000,000)
validateTotalRequests(totalRequests);

// 3. Duration limit (1-3600 seconds)
validateDuration(durationSeconds);

// 4. SSRF protection - URL validation
validateTargetUrl(targetUrl, instance);

// 5. Concurrent test count limit (max 3 per instance)
validateConcurrentTestsLimit(instanceId, currentRunningTests);

// 6. HTTP method whitelist
validateHttpMethod(method);

// 7. Headers and Body size limits
validateHeaders(headers);  // Max 8KB
validateBody(body);        // Max 1MB
```

**SSRF Protection Measures**:
- Block access to internal IPs (10.x, 172.16-31.x, 192.168.x, 127.x)
- Only allow http/https protocols
- URL format regex validation
- Optional: Force URL to match instance registration info

### 3. ❌ Database Migration — **NOT IMPLEMENTED**

> **⚠️ This whole section is aspirational and was never built. Do not cite it as existing.**
> Verified on 2026-10-28:
> - **`V24__create_stress_test_table.sql` does not exist.** `git ls-files gateway-admin/src/main/resources/sql`
>   returns 16 files (13 DDL/data + 3 under `seed/`), and **none** of them is a `stress_test` DDL nor
>   follows the `V<n>__<name>.sql` naming convention.
> - **There is no Flyway or Liquibase in this project.** `grep -i 'flyway\|liquibase' gateway-admin/pom.xml`
>   returns **zero matches**; no `V*__*.sql` file exists anywhere in the repo.
> - The `stress_test` table is created by **JPA/Hibernate** instead. At runtime the admin container sets
>   `SPRING_JPA_HIBERNATE_DDL_AUTO=update`, which overrides `application-prod.yml`'s `ddl-auto: validate`
>   (OS environment variables take precedence over the in-jar profile yml). Consequence: a missing DDL
>   will **not** fail startup — Hibernate silently adds tables/columns, but it will **not** change an
>   existing column's type and will **not** apply column defaults that the entity does not declare.
> - The project's actual convention for schema changes is hand-written `alter-*.sql` scripts under
>   `src/main/resources/sql/`, applied manually (see `docs/operations/` and the deployment runbook).
>
> **If you need a repeatable migration for the stress-test tables, it still has to be written.**

**Planned file** (never created): `V24__create_stress_test_table.sql`

**Planned features**:
- ❌ Complete table structure definition
- ❌ Index optimization (instance_id, status, created_at)
- ❌ Field comments
- ❌ Flyway/Liquibase version management support (**neither tool is on the classpath**)

**Key Fields** (these columns *do* exist, created by Hibernate from the JPA entity — not by a migration):
```sql
-- Test configuration
concurrent_users, total_requests, duration_seconds, ramp_up_seconds
target_qps, request_timeout_seconds

-- Test results
actual_requests, successful_requests, failed_requests, error_rate
min/max/avg/p50/p90/p95/p99_response_time_ms
requests_per_second, throughput_kbps

-- Distribution data (JSON)
response_time_distribution, error_distribution
```

### 4. ✅ Service Layer Unit Tests

**File**: `StressTestServiceTest.java`

**Test Coverage**:
- ✅ 14 unit tests (verified: `test01_…` through `test14_…`; note that a plain `grep -c "@Test"` reports
  15 because it also matches the class-level `@TestMethodOrder` annotation)
- ✅ Streaming statistics correctness verification
- ✅ Concurrency safety tests
- ✅ Boundary condition tests
- ✅ Exception handling tests

**Key Test Scenarios**:
```java
@Test
void test10_CalculateStatistics() {
    // Verify statistics calculation accuracy
    StreamingStatistics stats = new StreamingStatistics();
    for (int i = 1; i <= 100; i++) {
        stats.recordResponseTime(i * 10, true);
    }

    assertEquals(100, stats.getTotalCount());
    assertTrue(p50 < p90 && p90 < p95 && p95 < p99);
}

@Test
void test12_StreamingStatistics_ConcurrencySafety() {
    // Verify multi-thread safety
    // 10 threads, each 1000 records
    // Final count must be accurate
}
```

## Further Optimization Recommendations

### Short-term Optimization (1-2 weeks)

#### 1. ✅ DONE — Integrate Optimization Components into StressTestService

> **This item is complete, but NOT with the class named below.** Verified on 2026-10-28:
> `grep -n 'synchronizedList\|allResults\|StreamingStatistics' StressTestService.java` returns
> **zero matches** — the unbounded result list is gone and `StreamingStatistics` is *not* what the
> production path uses.
>
> What actually landed (commit `ed93868`) is
> `gateway-admin/src/main/java/com/leoli/gateway/admin/service/StressTestStatistics.java`:
> - package-private `final class StressTestStatistics` (:11), javadoc: *"Bounded, millisecond-resolution
>   statistics for all completed attempts, including failures."*
> - **fixed-size** accumulators instead of a growing list: `long[] latencies = new long[300_001]`
>   (`MAX_LATENCY = 300_000`, :12/:16) and `long[] distribution = new long[10]` (:17), plus a
>   `LinkedHashMap` for error types and a bounded timeline `ArrayDeque`.
> - thread safety via `synchronized` methods (`record` :31, `getLiveRps` :50, `getSummary` :60,
>   `getTimeline` :83, `applyTo` :99) — i.e. **not** the lock-free `LongAdder` design sketched below;
>   `StreamingStatistics` (which *is* `LongAdder`-based) survives mainly as the class exercised by
>   `StressTestServiceTest`.
> - wiring: `StressTestService.java:1109` holds `volatile StressTestStatistics stats` inside
>   `StressTestSession`, `:1112` resets it on `start()`, `recordResult(...)` calls `stats.record(...)`,
>   and `:647` calls `session.stats.applyTo(test, objectMapper)`.
> - `applyTo` (:99-113) is the real equivalent of the "After completion" block below: it sets
>   actual/successful/failed requests, error rate, min/max/avg/p50/p90/p95/p99, requests-per-second,
>   throughput, and serializes both `responseTimeDistribution` and `errorDistribution` to JSON.
>
> **The snippet below is kept only as the historical proposal — do not implement it again.**

Modify `executeTest()` method to use streaming statistics:

```java
private void executeTest(Long testId, Integer targetQps, int requestTimeoutSeconds) {
    // ... existing code ...

    // Replace: List<TestResult> allResults = Collections.synchronizedList(...)
    StreamingStatistics stats = new StreamingStatistics();

    // In result callback:
    CompletableFuture<TestResult> future = executeRequestAsync(request, semaphore, session);
    future.thenAccept(result -> {
        stats.recordResponseTime(result.responseTime, result.success);
        if (!result.success) {
            stats.recordError(result.errorType);
        }
    });

    // After completion:
    test.setActualRequests((int) stats.getTotalCount());
    test.setSuccessfulRequests((int) stats.getSuccessCount());
    test.setFailedRequests((int) stats.getFailedCount());
    test.setErrorRate(stats.getErrorRate());
    test.setMinResponseTimeMs((double) stats.getMin());
    test.setMaxResponseTimeMs((double) stats.getMax());
    test.setAvgResponseTimeMs(stats.getMean());
    test.setP50ResponseTimeMs((double) stats.getP50());
    test.setP90ResponseTimeMs((double) stats.getP90());
    test.setP95ResponseTimeMs((double) stats.getP95());
    test.setP99ResponseTimeMs((double) stats.getP99());

    // Save distribution as JSON
    test.setResponseTimeDistribution(
        objectMapper.writeValueAsString(stats.getDistribution())
    );
}
```

#### 2. Add Validator Injection (⚠️ partially done, not as sketched)

> **Current state**: `StressTestValidator` is **not** constructor-injected into `StressTestService`.
> The service instantiates it inline at a single call site —
> `StressTestService.java:431`: `new StressTestValidator().validateHttpMethod(config.getMethod());`.
> So only the HTTP-method whitelist is enforced on that path; the full `validateAll(...)` described
> below is **not** wired in. The limits themselves are implemented and correct — see §2
> (`StressTestValidator.java:27-41`).

```java
@Service
public class StressTestService {
    private final StressTestValidator validator;

    public StressTestService(..., StressTestValidator validator) {
        this.validator = validator;
    }

    public ResponseEntity<?> createAndStartTest(String instanceId, Map<String, Object> config) {
        // Validate before creating test
        validator.validateAll(
            targetUrl, method, headers, body,
            concurrentUsers, totalRequests, durationSeconds,
            rampUpSeconds, requestTimeoutSeconds, targetQps,
            instance, runningTests.size()
        );
        // ... continue with test creation
    }
}
```

#### 3. Add Micrometer Monitoring Metrics (❌ NOT implemented — read the naming warning first)

> **Verified not done**: `StressTestService.java` contains **no** `MeterRegistry` reference and no
> `stress.test.*` metric names.
>
> ⚠️ **Name collision**: `com.leoli.gateway.admin.model.StressTestMetrics` **already exists** — it is a
> Lombok `@Data` **DTO** for time-series visualization (`testId`, `status`, `progress`,
> `List<MetricDataPoint> timeline`, a nested `SummaryMetrics`, ...), and it is used by both
> `StreamingStatistics` and `StressTestStatistics.applyTo`. Creating a `@Component` also named
> `StressTestMetrics` will collide with it (same simple name in imports, and a confusing bean name).
> **Pick a different name**, e.g. `StressTestMetricsRecorder` or `StressTestInstrumentation`.

```java
@Component
public class StressTestMetricsRecorder {   // NOT "StressTestMetrics" - that name is taken by the DTO
    private final MeterRegistry meterRegistry;

    public void recordTestStart(String instanceId) {
        meterRegistry.counter("stress.test.started", "instance", instanceId).increment();
    }

    public void recordTestCompletion(String instanceId, boolean success) {
        meterRegistry.counter("stress.test.completed",
            "instance", instanceId,
            "status", success ? "success" : "failed"
        ).increment();
    }

    public void recordRequestLatency(String instanceId, long latencyMs) {
        meterRegistry.timer("stress.request.latency", "instance", instanceId)
            .record(latencyMs, TimeUnit.MILLISECONDS);
    }
}
```

### Medium-term Optimization (1 month)

#### 4. Use t-digest for Higher Percentile Precision

Current histogram method is approximate calculation, can use t-digest for higher precision:

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.tdunning</groupId>
    <artifactId>t-digest</artifactId>
    <version>3.3</version>
</dependency>
```

```java
import com.tdunning.math.stats.TDigest;

private TDigest digest = TDigest.createMergingDigest(100);

public void recordResponseTime(long responseTimeMs) {
    digest.add(responseTimeMs);
}

public double getPercentile(double percentile) {
    return digest.quantile(percentile / 100.0);
}
```

#### 5. Add Test Scheduling Feature

```java
@Scheduled(fixedDelay = 60000) // Every minute
public void checkScheduledTests() {
    LocalDateTime now = LocalDateTime.now();
    List<ScheduledTest> dueTests = scheduledTestRepository
        .findByScheduledTimeBeforeAndStatus(now, "SCHEDULED");

    for (ScheduledTest test : dueTests) {
        startTest(test.getTestId());
    }
}
```

#### 6. Implement Distributed Load Generation

Current single node limits maximum load capacity, can introduce multi-agent architecture:

```
Admin Node (Coordinator)
  ├── Agent 1 (AWS us-east-1)
  ├── Agent 2 (AWS eu-west-1)
  └── Agent 3 (AWS ap-southeast-1)
```

### Long-term Optimization (Quarterly)

#### 7. Protocol Extension

- HTTP/2 support
- gRPC stress testing
- WebSocket connection testing
- GraphQL query optimization testing

#### 8. Intelligent Analysis Enhancement

- Automatic baseline comparison
- Performance regression detection
- SLA compliance reporting
- Root cause analysis suggestions

#### 9. Visualization Enhancement

- Real-time RPS curve chart
- Response time heatmap
- Error type pie chart
- Historical trend comparison

## Performance Benchmark

### Before vs After Comparison

> ⚠️ The "Lock-free" row describes `StreamingStatistics` (which uses `LongAdder`). The class actually on
> the production hot path, `StressTestStatistics`, is `synchronized` instead — see short-term item #1.

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Memory Usage (100K requests) | 10 MB | 1 KB | 99.99% ↓ |
| Percentile Calculation Time | O(n log n) | O(1) | ~100x |
| Concurrency Safety | Contended | Lock-free (`StreamingStatistics`) / `synchronized` (`StressTestStatistics`) | Safer |
| Max Test Scale | Memory limited | Unlimited | ∞ |

### Recommended Configuration

Recommended test configurations for different scenarios:

| Scenario | Concurrent Users | QPS | Duration | Notes |
|----------|------------------|-----|----------|--------|
| Smoke Test | 10 | Unlimited | 30s | Quick validation |
| Load Test | 50-100 | 500-1000 | 5 min | Regular performance |
| Stress Test | 200-500 | 2000-5000 | 15 min | Extreme testing |
| Stability Test | 100 | 1000 | 1 hour | Long-running |

## Security Best Practices

> **Status: items 1 and 2 below are NOT implemented — they remain suggestions.**
> Verified on 2026-10-28: `grep -c 'PreAuthorize\|RateLimiter' StressTestController.java` returns **0**.
> There is no `STRESS_TEST_ADMIN` role and no `@RateLimiter` on the stress-test endpoints.
> What *does* apply today is the global rule in `SecurityConfig`: mutating `/api/**` calls require
> `hasAuthority("ADMIN")`, and `GET /api/stress-test/share/**` is one of the few permit-all paths.
> Also note that neither `@PreAuthorize` nor a `@RateLimiter` annotation of this shape exists in the
> codebase as written — treat both snippets as illustrative pseudo-code.

1. **RBAC Permission Control**
   ```java
   @PreAuthorize("hasRole('STRESS_TEST_ADMIN')")
   public ResponseEntity<?> startTest(...) { ... }
   ```

2. **Rate Limiting**
   ```java
   @RateLimiter(maxRequests = 10, perMinutes = 60)
   public ResponseEntity<?> startTest(...) { ... }
   ```

3. **Audit Logging**
   ```java
   auditLogService.log("STRESS_TEST_STARTED",
       "User {} started test against {}", username, targetUrl);
   ```

4. **Alert Mechanism**
   - Alert when high error rate detected
   - Test timeout alert
   - Resource usage anomaly alert

## Summary

Through this optimization, the stress test module has significantly improved in:

✅ **Memory Efficiency**: From O(n) to O(1), eliminating OOM risk
✅ **Security**: Comprehensive input validation and SSRF protection
❌ **Reliability**: ~~Database migration ensures schema consistency~~ — **not done.** No migration file
   exists; the schema is still produced by JPA with runtime `ddl-auto=update` (see §3).
✅ **Maintainability**: Service layer tests cover critical logic (14 tests in `StressTestServiceTest`)
✅ **Performance**: Streaming/bounded-array calculation greatly enhances large-scale testing capability

Next steps:
1. ~~Integrate optimization components into main service class~~ — ✅ done (`StressTestStatistics`, wired at
   `StressTestService.java:1109/:1112/:647`)
2. Add monitoring metrics and alerts — ❌ still open; if implemented, do **not** name the component
   `StressTestMetrics` (that simple name is taken by the existing `model/StressTestMetrics` DTO)
3. Write a real database migration for the stress-test tables — ❌ still open (see §3); note there is no
   Flyway/Liquibase in the project, so this means either adopting one or adding a hand-written
   `src/main/resources/sql/*.sql` script consistent with the existing convention
4. Extend integration test coverage
5. Consider distributed load generation architecture
