# Filter Chain Analysis

> Filter Chain analysis functionality tracks execution statistics for each Filter, including P50/P95/P99 percentiles, slow request detection, and time percentage analysis, helping to precisely locate performance bottlenecks.

---

## Overview

Track each Filter's execution:

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
│            │ - duration (nanos)              │
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

## Statistics per Filter

Each Filter maintains the following statistical metrics:

| Metric | Description | Precision |
|--------|-------------|-----------|
| `totalCount` | Total execution count | Atomic counter |
| `successCount` | Success count | Atomic counter |
| `failureCount` | Failure count | Atomic counter |
| `successRate` | Success rate percentage | Real-time calculation |
| `avgDurationMicros` | Average execution time (microseconds) | Atomic accumulation |
| `avgDurationMs` | Average execution time (milliseconds) | Conversion |
| `maxDurationMicros` | Maximum execution time | CAS update |
| `minDurationMicros` | Minimum execution time | CAS update |

### Percentile Statistics (Sliding Window)

| Metric | Description |
|--------|-------------|
| `p50Micros` / `p50Ms` | Median response time |
| `p95Micros` / `p95Ms` | 95% request response time |
| `p99Micros` / `p99Ms` | 99% request response time |

**Sliding Window Mechanism**: Keeps the last 100 execution times for percentile calculation, avoiding historical data interference.

---

## Slow Request Detection

### Threshold Configuration

- **Default threshold**: 1000ms (1 second)
- **Dynamically adjustable**: Modify via API or AI Copilot tool
- **Alert trigger**: Recorded when single Filter or entire chain exceeds threshold

### Slow Request Tracking

When request total duration exceeds threshold:
1. Record to slow request list
2. Increment slow request counter
3. Retain complete Filter execution details

---

## Trace Record Structure

Single request's Filter Chain record:

```json
{
  "traceId": "abc-123",
  "createdAt": 1705309800000,
  "totalDurationMs": 150,
  "successCount": 8,
  "failureCount": 2,
  "filterCount": 10,
  "executions": [
    {
      "filter": "AuthenticationGlobalFilter",
      "order": -250,
      "durationMs": 5,
      "durationMicros": 5200,
      "success": true,
      "error": null
    },
    {
      "filter": "RateLimiterFilter",
      "order": -200,
      "durationMs": 2,
      "durationMicros": 2100,
      "success": false,
      "error": "Rate limit exceeded"
    }
  ],
  "timeBreakdown": [
    {
      "filter": "AuthenticationGlobalFilter",
      "durationMs": 5,
      "percentage": "3.3%"
    },
    {
      "filter": "LoadBalancerFilter",
      "durationMs": 45,
      "percentage": "30.0%"
    }
  ]
}
```

**timeBreakdown** field shows each Filter's percentage of total time, quickly identifying bottlenecks.

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

Response:
```json
{
  "totalRecords": 1000,
  "filterCount": 15,
  "slowRequestCount": 23,
  "slowThresholdMs": 1000,
  "filters": [
    {
      "filterName": "AuthenticationGlobalFilter",
      "order": -250,
      "totalCount": 10000,
      "successCount": 9900,
      "failureCount": 100,
      "successRate": "99.00%",
      "avgDurationMs": "5.200",
      "p50Ms": "4.500",
      "p95Ms": "8.200",
      "p99Ms": "15.300"
    }
  ],
  "slowestFilters": [
    {
      "filterName": "LoadBalancerFilter",
      "avgDurationMs": "45.200"
    }
  ]
}
```

### Get Slowest Filters Ranking

```bash
curl http://localhost:8080/internal/filter-chain/slowest-filters?limit=10
```

Sort by average duration, quickly identify bottleneck Filters.

---

## Performance Optimizations

Implementation uses multiple optimizations:

| Optimization | Description |
|--------------|-------------|
| **ConcurrentLinkedDeque** | High-concurrency queue for storing records |
| **Atomic Counters** | Atomic counters reduce lock contention |
| **Sliding Window** | Sliding window percentile calculation, avoiding full sorting |
| **Rolling Window** | Keep up to 1000 records, automatically evict old records |

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
- [ARCHITECTURE.md](../ARCHITECTURE.md) - Filter Chain architecture