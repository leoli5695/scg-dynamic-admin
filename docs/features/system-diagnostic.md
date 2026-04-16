# System Diagnostic

> System diagnostic provides comprehensive health checks to help identify potential issues, with accurate filter self-time measurement for performance analysis.

---

## Overview

System diagnostic checks all critical components:

```
┌─────────────────────────────────────────────┐
│         DIAGNOSTIC ARCHITECTURE              │
│                                              │
│   User Request (quick/full)                  │
│          │                                   │
│          ▼                                   │
│   ┌─────────────────┐                        │
│   │ Diagnostic      │                        │
│   │ Service         │◄──── Prometheus Metrics│
│   └────────┬────────┘                        │
│            │                                 │
│   ┌────┴────┬────┬────┬────┬────┬────┬────┐│
│   │    │    │    │    │    │    │    │    ││
│   ▼    ▼    ▼    ▼    ▼    ▼    ▼    ▼    ││
│  DB  Redis Nacos Route Auth Inst Perf Filter│
│                                              │
│   ┌─────────────────┐                        │
│   │ Diagnostic      │◄──── History Store    │
│   │ Report          │      (Trend Analysis) │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

---

## Diagnostic Types

| Type | Duration | Description |
|------|----------|-------------|
| `quick` | ~2-5 seconds | Quick check of critical components |
| `full` | ~30-60 seconds | Full diagnostic with detailed analysis |

---

## Components Checked

| Component | Checks |
|-----------|--------|
| **Database** | Connection status, query latency, pool metrics |
| **Redis** | Connection status, ping latency, memory usage |
| **Nacos** | Connection status, configuration availability |
| **Routes** | Route count, auth bindings validation |
| **Authentication** | Auth policies, bindings status |
| **Gateway Instances** | Instance count, health status, heartbeat check |
| **Performance** | CPU, memory, thread count, JVM metrics (Prometheus) |
| **Filter Chain** | Filter self-time analysis, slow filter detection |

---

## New Features

### 1. Filter Chain Self-Time Analysis

**Key improvement**: Filter performance now uses **selfTime** (filter's independent logic time) instead of totalTime (cumulative time including backend response).

| Metric | Purpose | Threshold |
|--------|---------|-----------|
| `avgSelfTimeMs` | Filter's own logic time | WARNING > 10ms, CRITICAL > 50ms |
| `selfP95Ms` | 95% percentile self-time | WARNING > 50ms, CRITICAL > 200ms |

**Example**: If a filter shows totalTime=430ms but selfTime=6ms, the filter is actually fast - the totalTime includes backend service response time.

### 2. Prometheus Integration

Diagnostic now fetches real-time gateway metrics from Prometheus:

| Metric | Source | Description |
|--------|--------|-------------|
| `gatewayQPS` | Prometheus | Requests per second |
| `gatewayAvgLatencyMs` | Prometheus | Average response time |
| `gatewayErrorRate` | Prometheus | Error rate percentage |
| `gatewayHeapUsedMB` | Prometheus | JVM heap usage |
| `gatewayCpuProcessUsage` | Prometheus | CPU usage |

### 3. Diagnostic History

Diagnostic results are now persisted for trend analysis:

```sql
-- Diagnostic history table
CREATE TABLE diagnostic_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    diagnostic_type VARCHAR(20),
    overall_score INT,
    status VARCHAR(20),
    gateway_qps DOUBLE,
    gateway_error_rate DOUBLE,
    gateway_avg_latency_ms DOUBLE,
    created_at TIMESTAMP
);
```

**Use case**: Compare current diagnostic with previous results, track health score trends.

### 4. Single Component Diagnosis

New API endpoints for diagnosing individual components:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/diagnostic/database` | Database health only |
| `GET` | `/api/diagnostic/redis` | Redis health only |
| `GET` | `/api/diagnostic/config-center` | Config center health only |
| `GET` | `/api/diagnostic/routes` | Routes configuration only |
| `GET` | `/api/diagnostic/auth` | Authentication only |
| `GET` | `/api/diagnostic/instances` | Gateway instances only |
| `GET` | `/api/diagnostic/performance` | Performance metrics only |
| `GET` | `/api/diagnostic/filter-chain` | Filter chain analysis only |

### 5. Compare with Previous

New API to compare current diagnostic with previous result:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/diagnostic/compare` | Compare with last diagnostic |

Response includes:
```json
{
  "previousScore": 80,
  "currentScore": 75,
  "scoreChange": -5,
  "metricChanges": {
    "errorRateChange": 2.5,
    "latencyChange": 50,
    "heapUsageChange": 5
  },
  "summary": "系统健康度较上次下降 5 分，需关注"
}
```

---

## Health Status

| Status | Description |
|--------|-------------|
| `HEALTHY` | All checks passed |
| `WARNING` | Minor issues detected |
| `CRITICAL` | Critical issues detected |
| `NOT_CONFIGURED` | Component not configured |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/diagnostic/quick` | Quick diagnostic |
| `GET` | `/api/diagnostic/full` | Full diagnostic |
| `GET` | `/api/diagnostic/history` | Diagnostic history (trend) |
| `GET` | `/api/diagnostic/score-trend` | Health score trend for charts |
| `GET` | `/api/diagnostic/{component}` | Single component diagnosis |
| `GET` | `/api/diagnostic/compare` | Compare with previous |

### Full Diagnostic Response Example

```json
{
  "startTime": 1705309800000,
  "endTime": 1705309860000,
  "duration": "6000ms",
  "overallScore": 85,
  "status": "HEALTHY",
  "database": {
    "status": "HEALTHY",
    "metrics": {
      "connectionLatency": "8ms",
      "poolActive": 3,
      "poolIdle": 7,
      "poolUtilization": "30%"
    }
  },
  "redis": {
    "status": "HEALTHY",
    "metrics": {
      "pingLatency": "9ms",
      "usedMemory": "749.74K"
    }
  },
  "performance": {
    "status": "HEALTHY",
    "metrics": {
      "gatewayQPS": "0.07",
      "gatewayAvgLatencyMs": "264ms",
      "gatewayErrorRate": "0.00%",
      "gatewayHeapUsedMB": 58,
      "gatewayHeapUsagePercent": "0.71%"
    }
  },
  "filterChain": {
    "status": "HEALTHY",
    "metrics": {
      "instancesChecked": 1,
      "problemFiltersCount": 0,
      "slowestFilter": "LoadBalancerFilter",
      "slowestFilterSelfP95Ms": 15,
      "selfAvgThresholdMs": 10,
      "selfP95ThresholdMs": 50,
      "topSlowestFilters": [
        {
          "filterName": "LoadBalancerFilter",
          "avgSelfMs": 3.5,
          "selfP95Ms": 15,
          "avgTotalMs": 150,
          "totalCount": 1000
        }
      ]
    }
  },
  "recommendations": [
    "2 routes have no auth bindings - consider adding authentication"
  ]
}
```

---

## Scoring Algorithm

```
Component Scores ( deducted from 100 ):
- Database:       -30 if CRITICAL, -10 if WARNING
- Redis:          -15 if CRITICAL, -5 if WARNING
- Config Center:  -25 if CRITICAL, -8 if WARNING
- Routes:         -10 if CRITICAL, -5 if WARNING
- Auth:           -10 if CRITICAL, -5 if WARNING
- Instances:      -15 if CRITICAL, -5 if WARNING
- Performance:    CRITICAL if errorRate>5% or memory>80%
- Filter Chain:   -10 if CRITICAL, -3 if WARNING

Overall Score = 100 - sum(deductions)
Status: HEALTHY if score>=80, WARNING if score>=50, CRITICAL if score<50
```

---

## Best Practices

1. **Regular Diagnostics**: Set up scheduled diagnostic tasks
2. **Use selfTime for Filter Analysis**: Focus on avgSelfTimeMs, not totalTime
3. **Monitor Trends**: Use history API to track score changes over time
4. **Compare Results**: Use compare API after making changes
5. **Address Warnings**: Promptly handle warning issues before they become critical
6. **Single Component Focus**: Use single component API for targeted troubleshooting

---

## Related Features

- [Filter Chain Analysis](filter-chain-analysis.md) - Detailed filter performance metrics
- [Monitoring & Alerts](monitoring-alerts.md) - Prometheus monitoring setup
- [AI-Powered Analysis](ai-analysis.md) - AI analysis of diagnostic results
- [Gateway Instance Management](instance-management.md) - Instance health check