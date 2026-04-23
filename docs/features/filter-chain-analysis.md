# Filter Chain Analysis

> Filter Chain analysis functionality tracks execution statistics for each Filter, including P50/P95/P99 percentiles, slow request detection, and time percentage analysis, helping to precisely locate performance bottlenecks.

---

## Overview

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
| FilterChainTracking | 430ms | **6ms** | Filter is fast, totalTime includes backend response |
| RemoveCachedBody | 424ms | **1ms** | Filter is very fast |
| LoadBalancerFilter | 45ms | **45ms** | Filter itself is slow (needs optimization) |
| AuthenticationFilter | 200ms | **5ms** | Filter is fast, totalTime includes downstream |

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
      "order": -250,
      "totalDurationMs": 250,      // Cumulative time (includes downstream)
      "selfTimeMs": 5,             // Filter's own logic time ← KEY METRIC
      "downstreamMs": 245,         // Time spent in downstream chain
      "success": true,
      "error": null
    },
    {
      "filter": "LoadBalancerFilter",
      "order": -100,
      "totalDurationMs": 200,      // Cumulative time
      "selfTimeMs": 45,            // This filter is slow! ← Optimize this
      "downstreamMs": 155,
      "success": true,
      "error": null
    },
    {
      "filter": "RateLimiterFilter",
      "order": -200,
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
      "filter": "LoadBalancerFilter",
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

### Get Summary Report

```bash
curl http://localhost:8080/internal/filter-chain/stats
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
      "order": -250,
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
      "filterName": "LoadBalancerFilter",
      "order": -100,
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
      "filterName": "LoadBalancerFilter",
      "avgSelfTimeMs": "45.200"
    }
  ],
  "slowestFiltersByTotalTime": [
    {
      "filterName": "FilterChainTracking",
      "avgDurationMs": "430.977"
    }
  ]
}
```

### Get Slowest Filters Ranking

```bash
curl http://localhost:8080/internal/filter-chain/slowest-filters?limit=10
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
5. **Rolling Window**: 1000 records is sufficient for analysis, no need to increase

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
// Circuit breaker configuration
{
  "filterName": "ExternalAuthFilter",
  "failureRateThreshold": 50,    // Open circuit when 50% failures
  "slowCallThreshold": 2000,     // Consider slow if > 2s
  "waitDurationInOpenState": 30, // Wait 30s before half-open
  "permittedCallsInHalfOpen": 5  // Test 5 calls in half-open
}
```

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
│ Filter: LoadBalancerFilter                                   │
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
│ AuthenticationFilter  │███│ 5ms (self)                              │
│                       │   ├─────────────────────────────────────────│
│ RouteMatchingFilter   │█│   2ms (self)                              │
│                       │   ├─────────────────────────────────────────│
│ LoadBalancerFilter    │████████████████████████│ 45ms (self) ← SLOW │
│                       │                       ├─────────────────────│
│ RateLimiterFilter     │██│ 8ms (self)                               │
│                       │   ├─────────────────────────────────────────│
│ BackendService        │████████████████████████████████████████████│ │
│                       │                                              │
│ (Backend Response)    │████████████████████████████████████████████│ │
│                       │████████████████████████████████████████████│ │
│                       │████████████████████████████████████████████│ │
│                       │████████████████████████████████████████████│ │
│                       │████████████████████████████████████████████│ │
│                       │████████████████████████████████████████████│ │
│                       │████████████████████████████████████████████│ │
│                       │████████████████████████████████████████████│ │
│                       │████████████████████████████████████████████│ │
│                       │████████████████████████████████████████████│ │
│                       │   195ms (backend response)                   │
├────────────────────────────────────────────────────────────────────┤
│ Legend: ████ Filter self-time, Backend response time               │
└────────────────────────────────────────────────────────────────────┘
```

### Waterfall Chart API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/internal/filter-chain/waterfall/{traceId}` | Get waterfall chart data |

### UI Features

- **Interactive Chart**: Click on filter bar to see detailed metrics
- **Zoom In/Out**: Focus on specific time range
- **Export**: Export chart as PNG or SVG
- **Comparison**: Compare multiple traces side by side