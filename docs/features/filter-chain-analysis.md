# Filter Chain Analysis

> Filter Chain analysis functionality tracks execution statistics for each Filter, including P50/P95/P99 percentiles, slow request detection, and time percentage analysis, helping to precisely locate performance bottlenecks.

---

## Overview

### Runtime correctness checks

Custom filters run in ascending order before routing; response decorators are
traversed in the opposite direction when the upstream body is written. The
following interactions have regression coverage:

- API-version selection happens early enough to rewrite paths, but its selected
  service URI is applied by `ApiVersionRoutingFilter` at order 10002, after SCG URL
  construction (10000) and multi-service selection (10001). Raw path/query encoding
  survives `StripPrefix` and service resolution.
- Retry preserves headers produced by earlier filters while removing the failed
  attempt's headers, status and routing state. A committed response cannot retry.
  Requests with bodies, including DELETE and explicitly framed GET bodies, require
  bounded replay caching; unknown-length uploads pass through without global retry.
- Trace capture wraps the whole retry operation, so success before the last allowed
  attempt still produces a final trace. Header operations retain the canonical
  trace ID; cache hits never replay another request's trace/span IDs.
- Ordinary response-cache keys include the selected API version and target. MULTI
  service routes bypass this cache because service weights and gray rules execute
  later in the chain and must not be short-circuited by a cache hit.
- Circuit-breaker permission acquisition handles OPEN-to-HALF_OPEN transitions even
  when automatic transition is disabled. Synchronous downstream failures are
  captured inside the reactive chain and recorded by the breaker.
- Authentication rejects an explicitly disabled matched credential. Config-center
  reads on a credential cache miss run outside the reactive event loop. Authentication
  runs at -256 before request transformation (-255) and validation (-254), so HMAC
  verifies the original wire body and the backend still receives the transformed body.
- AI semantic-cache capture copies bounded response chunks without aggregating the
  client stream. Exceeding the capture limit disables caching for that response;
  it does not turn a valid upstream response into a gateway error.
- Token pre-deductions are settled with zero upstream usage if an earlier filter
  ends the request before the collector takes ownership. The original quota period
  is retained and normal collector settlement prevents a duplicate early settlement.
  SSE side-copy parsing preserves each buffer's original read position.
- Optional access-log configuration failures retain the current configuration
  instead of aborting gateway startup. A malformed `null` configuration cannot
  replace the last valid logging configuration.

These tests exercise filter combinations in addition to individual filter methods.

Track each Filter's execution with accurate **self-time** measurement:

```
┌─────────────────────────────────────────────┐
│         FILTER CHAIN TRACKING                │
│                                              │
│   Request Processing                         │
│          │                                   │
│          ▼                                   │
│   ┌─────────────────┐                        │
│   │ Filter Execution│                        │
│   │ (Each Filter)   │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            │ Record:                         │
│            │ - filterName                    │
│            │ - order                         │
│            │ - totalTime (cumulative)        │
│            │ - selfTime (independent)  ← NEW │
│            │ - success/error                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Statistics Store│                        │
│   │ (ConcurrentHashMap)                      │
│   │ + Sliding Window (100)                   │
│   │ for Percentile Calc                      │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

---

## Key Concept: selfTime vs totalTime

Understanding the difference between these two metrics is critical for accurate performance analysis:

### totalTime (Cumulative Time)

**What it measures**: Time from filter start to request completion (including downstream service response time).

```
startTime ───────────────────────────────────────────── endTime
    │                                                      │
    │  Pre-logic │ Downstream Chain │ Post-logic │        │
    │   (own)    │ (other filters   │   (own)    │        │
    │            │ + backend)       │            │        │
    └────────────────────────────────────────────────────┘
                         totalTime
```

**Use case**: Request profiling - understanding the total request flow.

**Problem**: If downstream service takes 200ms, ALL filters before it will show 200+ms totalTime, which is misleading for filter optimization.

### selfTime (Independent Time)

**What it measures**: Only the filter's own logic execution time (pre-logic + post-logic), excluding downstream chain time.

```
startTime ──── preEndTime          postStartTime ──── endTime
    │              │                    │              │
    │  Pre-logic   │   Downstream       │  Post-logic  │
    │   (own)      │   Chain            │   (own)      │
    │              │                    │              │
    └──────────────┘                    └──────────────┘
         selfTime = preTime + postTime
```

**Use case**: Filter optimization - identifying which filters have slow logic.

**This is the key metric** for diagnosing filter performance issues!

### Example Comparison

| Filter | totalTime | selfTime | Interpretation |
|--------|-----------|----------|----------------|
| FilterChainTrackingGlobalFilter | 430ms | **6ms** | Filter is fast, totalTime includes backend response |
| RemoveCachedBodyFilter (SCG built-in) | 424ms | **1ms** | Filter is very fast |
| MultiServiceLoadBalancerFilter | 45ms | **45ms** | Filter itself is slow (needs optimization) |
| AuthenticationGlobalFilter | 200ms | **5ms** | Filter is fast, totalTime includes downstream |

> **Key insight**: Most filters show similar totalTime because they share the same downstream service response time. The difference between adjacent filters' totalTime equals the first filter's selfTime.

---

## Statistics per Filter

Each Filter maintains the following statistical metrics:

### selfTime Metrics (Key for Optimization)

| Metric | Description | Precision |
|--------|-------------|-----------|
| `avgSelfTimeMicros` | Average independent execution time | Atomic accumulation |
| `avgSelfTimeMs` | Average independent execution time (ms) | Conversion |
| `maxSelfTimeMicros` | Maximum independent execution time | CAS update |
| `minSelfTimeMicros` | Minimum independent execution time | CAS update |
| `selfP50Ms` | Median independent response time | Sliding window |
| `selfP95Ms` | 95% independent response time | Sliding window |
| `selfP99Ms` | 99% independent response time | Sliding window |

### totalTime Metrics (For Request Profiling)

| Metric | Description | Precision |
|--------|-------------|-----------|
| `totalCount` | Total execution count | Atomic counter |
| `successCount` | Success count | Atomic counter |
| `failureCount` | Failure count | Atomic counter |
| `successRate` | Success rate percentage | Real-time calculation |
| `avgDurationMicros` | Average cumulative time (microseconds) | Atomic accumulation |
| `avgDurationMs` | Average cumulative time (milliseconds) | Conversion |
| `maxDurationMicros` | Maximum cumulative execution time | CAS update |
| `minDurationMicros` | Minimum cumulative execution time | CAS update |

### Percentile Statistics (Sliding Window)

| Metric | Description |
|--------|-------------|
| `p50Micros` / `p50Ms` | Median cumulative response time |
| `p95Micros` / `p95Ms` | 95% cumulative response time |
| `p99Micros` / `p99Ms` | 99% cumulative response time |
| `selfP50Micros` / `selfP50Ms` | Median independent response time |
| `selfP95Micros` / `selfP95Ms` | 95% independent response time |
| `selfP99Micros` / `selfP99Ms` | 99% independent response time |

**Sliding Window Mechanism**: Keeps the last 100 execution times for percentile calculation, avoiding historical data interference.

---

## Slow Request Detection

### Threshold Configuration

Two separate thresholds for different purposes:

| Threshold | Default | Purpose |
|-----------|---------|---------|
| `slowThresholdMs` | 1000ms (1 second) | Total request time threshold |
| `slowSelfThresholdMs` | 50ms | Filter self-time threshold (actual filter performance) |

**Dynamically adjustable**: Modify via API or AI Copilot tool.

### Slow Request Tracking

When request total duration exceeds `slowThresholdMs`:
1. Record to slow request list
2. Increment slow request counter
3. Retain complete Filter execution details

### Slow Filter Detection (selfTime-based)

When filter's **selfTime** exceeds `slowSelfThresholdMs` (50ms):
- This indicates the filter's own logic is slow
- Log warning: "Slow filter logic detected: {filterName} self-time {X}ms"
- **This is the correct indicator for filter optimization**, not totalTime!

---

## Trace Record Structure

Single request's Filter Chain record now includes both selfTime and totalTime:

```json
{
  "traceId": "abc-123",
  "createdAt": 1705309800000,
  "totalDurationMs": 250,
  "successCount": 8,
  "failureCount": 2,
  "filterCount": 10,
  "executions": [
    {
      "filter": "AuthenticationGlobalFilter",
      "order": -256,
      "totalDurationMs": 250,      // Cumulative time (includes downstream)
      "selfTimeMs": 5,             // Filter's own logic time ← KEY METRIC
      "downstreamMs": 245,         // Time spent in downstream chain
      "success": true,
      "error": null
    },
    {
      "filter": "MultiServiceLoadBalancerFilter",
      "order": 10001,
      "totalDurationMs": 200,      // Cumulative time
      "selfTimeMs": 45,            // This filter is slow! ← Optimize this
      "downstreamMs": 155,
      "success": true,
      "error": null
    },
    {
      "filter": "HybridRateLimiterFilter",
      "order": -245,
      "totalDurationMs": 247,
      "selfTimeMs": 2,             // Filter is fast
      "downstreamMs": 245,
      "success": false,
      "error": "Rate limit exceeded"
    }
  ],
  "timeBreakdown": [
    {
      "filter": "AuthenticationGlobalFilter",
      "totalDurationMs": 250,
      "selfTimeMs": 5,
      "selfTimePercentage": "2.0%"   // Percentage of total request time
    },
    {
      "filter": "MultiServiceLoadBalancerFilter",
      "totalDurationMs": 200,
      "selfTimeMs": 45,
      "selfTimePercentage": "18.0%"  // This filter contributes 18% of overhead
    }
  ]
}
```

**Key Fields**:
- `selfTimeMs`: Filter's independent execution time (optimize when > 10ms)
- `totalDurationMs`: Cumulative time (use for request profiling)
- `downstreamMs`: Time spent in downstream chain (other filters + backend)
- `selfTimePercentage`: Filter's contribution to total request overhead

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/internal/filter-chain/stats` | Filter statistics summary |
| `GET` | `/internal/filter-chain/slowest-filters` | Slowest Filter ranking |
| `GET` | `/internal/filter-chain/slow` | Slow request list |
| `GET` | `/internal/filter-chain/trace/{traceId}` | Single trace details |
| `POST` | `/internal/filter-chain/threshold` | Set slow request threshold |
| `DELETE` | `/internal/filter-chain/stats` | Clear statistics |

> **Authentication required**: every `/internal/**` path is guarded by
> `InternalApiAuthWebFilter` (a `WebFilter` at `HIGHEST_PRECEDENCE + 10`). Requests without a valid
> `X-Internal-Token` header are rejected with HTTP 401 and the body
> `{"success":false,"message":"Missing or invalid internal token"}`. If
> `gateway.admin.internal-token` / `GATEWAY_INTERNAL_TOKEN` is not configured the gateway **refuses to
> start**, so these endpoints can never be reached anonymously.
>
> The controller (`InternalFilterChainController`, `@RequestMapping("/internal/filter-chain")`) exposes
> 22 more mappings beyond the six above, including `/records`, `/filters`, `GET /threshold`,
> `/historical`, `/historical/all`, `/historical/trend`, `DELETE /historical`, `/ai-analysis`,
> `/ai-analysis/quick`, `/ai-analysis/recommendations`, `/prediction`, `/prediction/quick`,
> `/prediction/alerts`, `/optimization`, `/optimization/prioritized`, `/optimization/filter/{filterName}`,
> `/optimization/order`, `/optimization/cache`, `/optimization/resources`, `/comparison`,
> `/comparison/quick` and `POST /comparison`.

### Get Summary Report

```bash
curl -H "X-Internal-Token: $GATEWAY_INTERNAL_TOKEN" \
  http://localhost:8080/internal/filter-chain/stats
```

Response now includes both selfTime and totalTime metrics:
```json
{
  "totalRecords": 1000,
  "filterCount": 15,
  "slowRequestCount": 23,
  "slowThresholdMs": 1000,
  "slowSelfThresholdMs": 50,
  "filters": [
    {
      "filterName": "AuthenticationGlobalFilter",
      "order": -256,
      "totalCount": 10000,
      "successCount": 9900,
      "failureCount": 100,
      "successRate": "99.00%",
      "avgSelfTimeMs": "5.200",          // ← Key metric for optimization
      "selfP50Ms": "4.500",
      "selfP95Ms": "8.200",
      "selfP99Ms": "15.300",
      "avgDurationMs": "250.500",        // Cumulative time (includes backend)
      "p50Ms": "200.500",
      "p95Ms": "450.200",
      "p99Ms": "650.300"
    },
    {
      "filterName": "MultiServiceLoadBalancerFilter",
      "order": 10001,
      "totalCount": 10000,
      "successCount": 10000,
      "failureCount": 0,
      "successRate": "100.00%",
      "avgSelfTimeMs": "45.200",         // ← This filter is slow!
      "selfP50Ms": "40.500",
      "selfP95Ms": "85.200",
      "selfP99Ms": "120.300",
      "avgDurationMs": "200.500",
      "p50Ms": "180.500",
      "p95Ms": "350.200",
      "p99Ms": "500.300"
    }
  ],
  "slowestFiltersBySelfTime": [
    {
      "filterName": "MultiServiceLoadBalancerFilter",
      "avgSelfTimeMs": "45.200"
    }
  ],
  "slowestFiltersByTotalTime": [
    {
      "filterName": "FilterChainTrackingGlobalFilter",
      "avgDurationMs": "430.977"
    }
  ]
}
```

### Get Slowest Filters Ranking

```bash
curl -H "X-Internal-Token: $GATEWAY_INTERNAL_TOKEN" \
  "http://localhost:8080/internal/filter-chain/slowest-filters?limit=10"
```

Sort by **selfTime** average, accurately identify filters that need optimization.

---

## AI Copilot Integration

AI Copilot can call the following tools to analyze Filter Chain:

| Tool | Capability |
|------|------------|
| `get_filter_chain_stats` | Get statistics summary, slow request count |
| `get_slowest_filters` | Get slowest Filter ranking |
| `get_slow_requests` | Get slow request list and traceId |
| `get_filter_trace_detail` | Get complete execution details for single trace |
| `set_slow_threshold` | Dynamically adjust slow request threshold (requires confirmation) |
| `suggest_filter_reorder` | Analyze and provide Filter reorder suggestions |

### suggest_filter_reorder Output

```
Analysis content:
- Filter execution duration ranking
- Filters that can be executed earlier (authentication/rate limiting)
- Filters that must execute after route matching (load balancing/retry)
- Performance trend comparison (current period vs previous period)
- Expected performance improvement estimation
```

---

## Use Cases

| Use Case | How to Use |
|----------|------------|
| **Performance Tuning** | View slowest Filter ranking, optimize highest duration Filters |
| **Troubleshooting** | View high failure rate Filters, locate issues with error information |
| **Capacity Planning** | Analyze P95/P99 response times, evaluate system capacity |
| **Optimization Verification** | Compare statistics before and after optimization, verify effectiveness |
| **Filter Order Optimization** | Use `suggest_filter_reorder` tool for suggestions |

---

## Best Practices

1. **Regular Analysis**: Monitor P95/P99 percentiles rather than just averages
2. **Watch for Anomalies**: Investigate Filters with failure rate > 5%
3. **Time Percentage**: Focus on Filters with > 30% in timeBreakdown
4. **Threshold Adjustment**: Adjust slowThresholdMs based on business response time requirements
5. **Rolling Window**: Up to 1000 trace records are retained (`MAX_RECORDS`); the percentile sliding
   window is a separate limit of the last 100 executions (`PERCENTILE_WINDOW`). Neither needs increasing.

---

## Related Features

- [Request Tracing](request-tracing.md) - Request tracing
- [Request Replay](request-replay.md) - Request replay debugging
- [Monitoring & Alerts](monitoring-alerts.md) - Monitoring
- [AI Copilot](ai-copilot.md) - AI tool call analysis
- [Historical Comparison](historical-comparison.md) - Historical data comparison and anomaly detection
- [ARCHITECTURE.md](../ARCHITECTURE.md) - Filter Chain architecture

---

## Advanced Features (New)

### Filter Circuit Breaker

Automatically protect the system from cascading failures by isolating problematic filters:

| Feature | Description |
|---------|-------------|
| **Automatic Detection** | Monitor filter failure rate and response time |
| **Circuit States** | CLOSED (normal), OPEN (failing), HALF_OPEN (testing) |
| **Auto Recovery** | Periodically test if filter has recovered |
| **Configurable Thresholds** | Failure rate threshold, timeout threshold |

```java
// Circuit breaker configuration - GLOBAL, not per-filter.
// Submitted to POST /internal/filter-circuit-breaker/config (X-Internal-Token required);
// read back from GET /internal/filter-circuit-breaker/config.
// These are exactly the keys returned by FilterCircuitBreakerManager.getConfig().
{
  "healthScoreThreshold": 60,     // Health score below which the circuit OPENs
  "failureRateThreshold": 50.0,   // Open circuit when 50% of calls fail
  "waitDurationMs": 30000,        // Wait 30s in OPEN before trying HALF_OPEN
  "halfOpenRequestCount": 5,      // Requests allowed through in HALF_OPEN
  "enabled": false                // Disabled by default; core filters are always excluded
}
```

> `filterName` is **not** a configuration key: the thresholds above apply to all non-core filters at
> once. Per-filter operations use the filter name as a *path* segment instead —
> `GET /internal/filter-circuit-breaker/status/{filterName}`,
> `POST /internal/filter-circuit-breaker/force-open/{filterName}`,
> `POST /internal/filter-circuit-breaker/force-close/{filterName}`.

### Filter Health Monitor

Continuous health monitoring for each filter:

| Metric | Description |
|--------|-------------|
| **Health Score** | 0-100 score based on success rate, latency, error patterns |
| **Anomaly Detection** | Detect unusual patterns (spikes, trends) |
| **Alert Integration** | Send alerts when health score drops below threshold |

### Historical Data Tracker

Track and store filter performance metrics over time:

| Capability | Description |
|------------|-------------|
| **Time-series Storage** | Store metrics at regular intervals (1min, 5min, 1hour) |
| **Trend Analysis** | Identify performance trends and degradation patterns |
| **Period Comparison** | Compare current vs previous period metrics |

### Performance Predictor

Predict future filter performance based on historical patterns:

| Feature | Description |
|---------|-------------|
| **Load Forecasting** | Predict performance under expected load |
| **Capacity Planning** | Estimate when optimization is needed |
| **Bottleneck Prediction** | Identify filters likely to become bottlenecks |

### AI Anomaly Detector

AI-powered anomaly detection for filter chain:

| Detection Type | Description |
|----------------|-------------|
| **Statistical Anomalies** | Detect outliers in response time, error rate |
| **Pattern Anomalies** | Detect unusual sequences of filter executions |
| **Trend Anomalies** | Detect sudden changes in performance trends |

### Period Comparison Analyzer

Compare filter performance between different time periods:

| Comparison | Metrics |
|------------|---------|
| **Hour-over-Hour** | Compare last hour vs previous hour |
| **Day-over-Day** | Compare today vs yesterday |
| **Week-over-Week** | Compare this week vs last week |

Output format:
```
┌─────────────────────────────────────────────────────────────┐
│ PERIOD COMPARISON REPORT                                     │
│                                                              │
│ Filter: MultiServiceLoadBalancerFilter                       │
│ Current Period: avgSelfTime=45ms, errorRate=2%               │
│ Previous Period: avgSelfTime=30ms, errorRate=1%              │
│ Change: +50% slower, +100% error rate                        │
│ Status: ⚠ NEEDS ATTENTION                                     │
└─────────────────────────────────────────────────────────────┘
```

---

## Filter Execution Waterfall Chart (New)

Visual representation of filter execution sequence:

```
┌────────────────────────────────────────────────────────────────────┐
│ WATERFALL CHART - Trace ID: abc-123                                 │
│ Total Duration: 250ms                                               │
├────────────────────────────────────────────────────────────────────┤
│                                                                     │
│ AuthenticationGlobalFilter    │███│ 5ms (self)                              │
│                              │   ├─────────────────────────────────────────│
│ RouteToRequestUrlFilter       │█│   2ms (self)                              │
│                              │   ├─────────────────────────────────────────│
│ MultiServiceLoadBalancerFilter│████████████████████████│ 45ms (self) ← SLOW │
│                              │                       ├─────────────────────│
│ HybridRateLimiterFilter       │██│ 8ms (self)                               │
│                              │   ├─────────────────────────────────────────│
│ BackendService                │████████████████████████████████████████████│ │
│                              │                                              │
│ (Backend Response)            │████████████████████████████████████████████│ │
│                              │████████████████████████████████████████████│ │
│                              │████████████████████████████████████████████│ │
│                              │████████████████████████████████████████████│ │
│                              │████████████████████████████████████████████│ │
│                              │████████████████████████████████████████████│ │
│                              │████████████████████████████████████████████│ │
│                              │████████████████████████████████████████████│ │
│                              │████████████████████████████████████████████│ │
│                              │████████████████████████████████████████████│ │
│                              │   195ms (backend response)                   │
├────────────────────────────────────────────────────────────────────┤
│ Legend: ████ Filter self-time, Backend response time               │
└────────────────────────────────────────────────────────────────────┘
```

### Waterfall Chart API

**There is no dedicated waterfall endpoint.** `/internal/filter-chain/waterfall/{traceId}` does not
exist — `InternalFilterChainController` (`@RequestMapping("/internal/filter-chain")`) declares no
`waterfall` mapping, and neither does the admin-side proxy
(`FilterChainController`, `@RequestMapping("/api/filter-chain")`).

The waterfall is rendered **client-side** by
`gateway-ui/src/features/filter-chain/components/WaterfallChart.tsx` from the per-trace execution data:

| Method | Path (admin) | Path (gateway) | Description |
|--------|--------------|----------------|-------------|
| `GET` | `/api/filter-chain/{instanceId}/trace/{traceId}` | `/internal/filter-chain/trace/{traceId}` | Per-filter `executions` / `timeBreakdown` for one trace — this is what the waterfall draws |
| `GET` | `/api/filter-chain/{instanceId}/records` | `/internal/filter-chain/records` | Recent trace records |
| `GET` | `/api/filter-chain/{instanceId}/stats` | `/internal/filter-chain/stats` | Statistics summary |

### UI Features

Implemented (`gateway-ui/src/features/filter-chain/`):

- **Waterfall Chart**: Gantt-style per-filter self-time / cumulative-time bars, rendered in the browser
  from the trace detail payload above.
- **Hover Tooltip**: mouse-following tooltip on each bar showing the filter's order and timings.
- **Export**: the waterfall chart is exported as **PNG** via `html2canvas`
  (`filter-waterfall-chart-<timestamp>.png`); the statistics table can be exported as **JSON** or **CSV**.

Not implemented:

- ~~**Zoom In/Out**: Focus on specific time range~~ — no zoom control exists.
- ~~**Export as SVG**~~ — export is PNG only (there is no SVG serializer in `gateway-ui/package.json`).
- ~~**Comparison**: Compare multiple traces side by side~~ — the chart renders a single trace.
- ~~Click on a filter bar to open detailed metrics~~ — interaction is hover-tooltip only.
