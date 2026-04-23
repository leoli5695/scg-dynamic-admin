# Historical Comparison

> Historical comparison functionality enables period-over-period analysis of gateway performance metrics, filter chain statistics, and configuration changes, helping identify trends and anomalies.

---

## Overview

Historical comparison features:

| Feature | Description |
|---------|-------------|
| **Period Comparison** | Compare metrics between different time periods |
| **Trend Analysis** | Identify performance trends and degradation patterns |
| **Anomaly Detection** | AI-powered detection of unusual patterns |
| **Performance Prediction** | Predict future performance based on historical data |
| **Multi-Instance Comparison** | Compare metrics across different gateway instances |

---

## Comparison Types

### Time Period Comparison

| Comparison | Description | Use Case |
|------------|-------------|----------|
| **Hour-over-Hour** | Compare last hour vs previous hour | Real-time anomaly detection |
| **Day-over-Day** | Compare today vs yesterday | Daily performance monitoring |
| **Week-over-Week** | Compare this week vs last week | Weekly trend analysis |
| **Month-over-Month** | Compare this month vs last month | Monthly capacity planning |

### Instance Comparison

| Comparison | Description |
|------------|-------------|
| **Config Diff** | Compare configuration between instances |
| **Performance Diff** | Compare response time, error rate |
| **Resource Diff** | Compare JVM memory, CPU usage |

---

## Data Collection

### Metrics Stored

| Category | Metrics |
|----------|---------|
| **Gateway Metrics** | QPS, response time, error rate, active connections |
| **JVM Metrics** | Heap usage, GC count, thread count |
| **Filter Metrics** | Self-time, total-time, success rate |
| **Route Metrics** | Request count, latency per route |
| **Backend Metrics** | Service response time, instance health |

### Storage Granularity

| Interval | Retention | Purpose |
|----------|-----------|---------|
| **1 minute** | 24 hours | Real-time analysis |
| **5 minutes** | 7 days | Hourly comparison |
| **1 hour** | 30 days | Daily/weekly comparison |
| **1 day** | 365 days | Monthly/yearly comparison |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/historical/compare` | Compare two periods |
| `GET` | `/api/historical/trends` | Get trend analysis |
| `GET` | `/api/historical/anomalies` | Get detected anomalies |
| `GET` | `/api/historical/predict` | Get performance prediction |
| `GET` | `/api/historical/instances/compare` | Compare multiple instances |

### Compare Periods

```bash
curl "http://localhost:9090/api/historical/compare?currentPeriod=1h&previousPeriod=1h"
```

Response:
```json
{
  "currentPeriod": {
    "start": "2024-01-15T09:00:00Z",
    "end": "2024-01-15T10:00:00Z",
    "metrics": {
      "avgResponseTime": 150,
      "errorRate": 2.5,
      "qps": 1000
    }
  },
  "previousPeriod": {
    "start": "2024-01-15T08:00:00Z",
    "end": "2024-01-15T09:00:00Z",
    "metrics": {
      "avgResponseTime": 120,
      "errorRate": 1.5,
      "qps": 800
    }
  },
  "changes": {
    "avgResponseTime": "+25%",
    "errorRate": "+67%",
    "qps": "+25%"
  },
  "status": "WARNING",
  "anomalies": [
    "Error rate increased significantly"
  ]
}
```

---

## Anomaly Detection

### Detection Types

| Type | Description | Threshold |
|------|-------------|-----------|
| **Statistical** | Values outside normal range | >2 standard deviations |
| **Trend** | Unexpected trend changes | >30% change in trend direction |
| **Pattern** | Unusual sequence patterns | AI-detected patterns |
| **Correlation** | Unexpected correlations | Correlation coefficient >0.8 |

### Anomaly Categories

| Category | Examples |
|----------|----------|
| **Performance** | Response time spike, throughput drop |
| **Resource** | Memory leak, CPU spike |
| **Error** | Error rate increase, failure pattern |
| **Filter** | Slow filter, filter chain anomaly |

### Anomaly Response

```
┌────────────────────────────────────────────────────────────────────┐
│ ANOMALY DETECTED                                                    │
│                                                                     │
│ Type: PERFORMANCE_ANOMALY                                           │
│ Severity: HIGH                                                      │
│ Detected: 2024-01-15T10:05:00Z                                      │
│                                                                     │
│ Details:                                                            │
│ - Response time increased from 120ms to 450ms (+275%)               │
│ - Affected routes: /api/users/**, /api/orders/**                    │
│ - Possible cause: LoadBalancerFilter self-time increased            │
│                                                                     │
│ Recommendation:                                                     │
│ 1. Check LoadBalancerFilter configuration                           │
│ 2. Verify backend service health                                    │
│ 3. Consider enabling circuit breaker                                │
└────────────────────────────────────────────────────────────────────┘
```

---

## Performance Prediction

### Prediction Models

| Model | Description | Accuracy |
|-------|-------------|----------|
| **Linear Regression** | Simple trend extrapolation | Good for steady trends |
| **Moving Average** | Smoothed trend prediction | Good for noisy data |
| **ARIMA** | Time-series forecasting | Good for periodic patterns |
| **ML Model** | Machine learning prediction | Best for complex patterns |

### Prediction Output

```json
{
  "prediction": {
    "nextHour": {
      "expectedQPS": 1200,
      "expectedResponseTime": 160,
      "confidenceLevel": 0.85
    },
    "nextDay": {
      "expectedPeakQPS": 2500,
      "expectedPeakTime": "14:00-15:00",
      "capacityWarning": "Current capacity may not handle peak"
    },
    "bottleneckPrediction": {
      "filter": "LoadBalancerFilter",
      "expectedSelfTime": 60,
      "recommendation": "Optimize before peak hours"
    }
  }
}
```

---

## Trend Analysis

### Trend Metrics

| Metric | Trend Direction | Interpretation |
|--------|-----------------|----------------|
| **Response Time** | Increasing | Performance degradation |
| **QPS** | Increasing | Growing traffic |
| **Error Rate** | Increasing | Stability issues |
| **Memory Usage** | Increasing | Possible leak |

### Trend Visualization

```
┌────────────────────────────────────────────────────────────────────┐
│ TREND CHART - Response Time (7 days)                                │
│                                                                     │
│ Day 1 │ Day 2 │ Day 3 │ Day 4 │ Day 5 │ Day 6 │ Day 7              │
│ 120ms │ 125ms │ 130ms │ 140ms │ 150ms │ 170ms │ 200ms              │
│   ●───│──●───│──●───│──●───│──●───│──●───│──●                │
│                                                                     │
│ Trend: ↑ INCREASING (+67% over 7 days)                              │
│ Rate: +11ms/day                                                     │
│ Prediction: Will reach 250ms in 5 days if trend continues           │
│                                                                     │
│ Recommendation: Investigate LoadBalancerFilter                      │
└────────────────────────────────────────────────────────────────────┘
```

---

## Multi-Instance Comparison

### Instance Comparison Report

```
┌────────────────────────────────────────────────────────────────────┐
│ INSTANCE COMPARISON REPORT                                          │
│                                                                     │
│ Instance-A vs Instance-B                                            │
│                                                                     │
│ Configuration Differences:                                          │
│ ├─ Rate limiting: Instance-A=100 QPS, Instance-B=200 QPS           │
│ ├─ Timeout: Instance-A=30s, Instance-B=60s                         │
│ └─ Circuit breaker: Instance-A=enabled, Instance-B=disabled        │
│                                                                     │
│ Performance Differences:                                            │
│ ├─ Response time: Instance-A=150ms, Instance-B=120ms               │
│ ├─ Error rate: Instance-A=2%, Instance-B=1%                        │
│ └─ QPS capacity: Instance-A=800, Instance-B=1500                   │
│                                                                     │
│ Recommendations:                                                    │
│ 1. Align rate limiting configuration                                │
│ 2. Enable circuit breaker on Instance-B                            │
│ 3. Consider load balancing adjustment                               │
└────────────────────────────────────────────────────────────────────┘
```

---

## AI Copilot Integration

AI Copilot can analyze historical data:

| Tool | Capability |
|------|------------|
| `compare_periods` | Compare metrics between periods |
| `detect_anomalies` | AI-powered anomaly detection |
| `predict_performance` | Predict future performance |
| `analyze_trends` | Trend analysis with recommendations |
| `compare_instances` | Multi-instance comparison |

### Example: AI Analysis

```
User: "Compare today's performance with yesterday"

AI Response:
┌─────────────────────────────────────────────────────────────┐
│ PERFORMANCE COMPARISON                                       │
│                                                             │
│ Today vs Yesterday                                           │
│                                                             │
│ Response Time: 150ms vs 120ms (+25%) ⚠                      │
│ Error Rate: 2.5% vs 1.5% (+67%) ⚠                           │
│ QPS: 1000 vs 800 (+25%)                                      │
│                                                             │
│ Analysis:                                                    │
│ - Traffic increased 25%, but response time degraded         │
│ - Error rate spike detected at 10:00-10:15                  │
│ - Root cause: LoadBalancerFilter self-time increased        │
│                                                             │
│ Recommendations:                                             │
│ 1. Scale backend services to handle increased traffic       │
│ 2. Optimize LoadBalancerFilter configuration                │
│ 3. Add circuit breaker for protection                       │
└─────────────────────────────────────────────────────────────┘
```

---

## Best Practices

1. **Regular Comparison**: Compare metrics daily to catch issues early
2. **Baseline Establishment**: Establish normal performance baselines
3. **Anomaly Thresholds**: Configure appropriate anomaly thresholds
4. **Prediction Validation**: Validate predictions against actual data
5. **Cross-Instance Analysis**: Regularly compare instances for consistency

---

## Configuration

```yaml
historical:
  data-collection:
    interval: 1m
    retention-days: 365
  anomaly-detection:
    enabled: true
    sensitivity: medium
    statistical-threshold: 2.0
  prediction:
    enabled: true
    model: arima
    confidence-threshold: 0.8
```

---

## Related Features

- [Filter Chain Analysis](filter-chain-analysis.md) - Filter performance analysis
- [Monitoring & Alerts](monitoring-alerts.md) - Real-time monitoring
- [AI Copilot](ai-copilot.md) - AI-powered analysis
- [Request Tracing](request-tracing.md) - Request-level tracing