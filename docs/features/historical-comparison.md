# Historical Comparison

> Historical comparison functionality enables period-over-period analysis of gateway performance metrics, filter chain statistics, and configuration changes, helping identify trends and anomalies.

---

## ⚠️ Implementation Status — read this first

**Most of what this document describes is an unimplemented design, not a shipping feature.** It is kept
as a roadmap. Sections below are individually marked **Implemented** or **Not implemented (design only)**.

Concretely, as of the current code:

- **There is no `/api/historical/*` endpoint and no `HistoricalController`.** `gateway-admin` has no
  controller, service or configuration key matching `historical`. Any curl against
  `/api/historical/compare|trends|anomalies|predict|instances/compare` returns 404.
- **There is no ARIMA, no moving-average model, no ML model, and no statistical (standard-deviation /
  correlation) anomaly detection anywhere in `gateway-admin`.** No statistics or ML library is on any
  classpath.
- **There is no metrics snapshot / downsampling table.** None of the `@Table` entities in
  `gateway-admin` store time-bucketed metrics. Historical metrics are queried **live from Prometheus**,
  and `MonitorController` caps the window at `Math.min(hours, 168)` — **7 days**, not 365.
- **There is no `historical.*` configuration block** in either `application.yml`.

What *does* exist today:

| Real capability | Where |
|-----------------|-------|
| Multi-instance performance comparison, ranking, outlier detection | `MultiInstanceComparisonController` — `GET /api/multi-instance/compare/all`, `POST /api/multi-instance/compare`, `GET /api/multi-instance/ranking`, `GET /api/multi-instance/outliers` |
| Live / historical metrics from Prometheus | `MonitorController` — `GET /api/monitor/metrics?timestamp=`, `GET /api/monitor/history?hours=&centerTime=` (≤168 h), `GET /api/monitor/routes` (≤24 h) |
| Filter-chain period comparison (hour/day/week/custom) over a **60-minute in-memory** rolling window | gateway side: `GET/POST /internal/filter-chain/comparison`, `GET /internal/filter-chain/comparison/quick` (`PeriodComparisonAnalyzer`) |
| Filter-chain history + trend | gateway side: `GET /internal/filter-chain/historical?minutes=`, `/historical/all`, `/historical/trend`, `DELETE /historical` (`HistoricalDataTracker`) |
| Filter bottleneck prediction via **linear regression**, next 30 minutes | gateway side: `GET /internal/filter-chain/prediction`, `/prediction/quick`, `/prediction/alerts` (`PerformancePredictor`) |
| Filter-health anomaly detection (fixed thresholds, not statistical) | gateway side: `AIAnomalyDetector` + `FilterHealthMonitor` |

The `/internal/**` endpoints live on the **gateway** process (port 8080), not on admin (9090), and
require the `X-Internal-Token` header (`InternalApiAuthWebFilter` refuses to start if the token is
unset). See [Filter Chain Analysis](filter-chain-analysis.md) for details.

---

## Overview

Historical comparison features:

| Feature | Description | Status |
|---------|-------------|--------|
| **Period Comparison** | Compare metrics between different time periods | Partially — filter-chain only, via `/internal/filter-chain/comparison` (60-min window). No gateway-wide metric period comparison. |
| **Trend Analysis** | Identify performance trends and degradation patterns | Partially — `/internal/filter-chain/historical/trend` and `PerformancePredictor`'s `trendDirection` |
| **Anomaly Detection** | AI-powered detection of unusual patterns | Partially — filter-health thresholds only (`AIAnomalyDetector`); no statistical/AI detection |
| **Performance Prediction** | Predict future performance based on historical data | Partially — linear-regression filter bottleneck prediction, 30-min horizon. No ARIMA/ML. |
| **Multi-Instance Comparison** | Compare metrics across different gateway instances | **Implemented** — `/api/multi-instance/*` |

---

## Comparison Types

### Time Period Comparison

`PeriodComparisonAnalyzer.ComparisonType` defines four values, but only the hour-over-hour mode is
actually backed by data:

| Comparison | Enum value | Status |
|------------|-----------|--------|
| **Hour-over-Hour** | `HOUR_VS_HOUR` | Works — served from `FilterChainTracker`'s live in-memory stats (period ends within the last 60 minutes) |
| **Day-over-Day** | `DAY_VS_DAY` | **Not functional** — the range is computed, but `getMetricsForPeriod` returns an **empty map** for any period ending more than 60 minutes ago (`PeriodComparisonAnalyzer`: "Historical period … requires database query"). There is no such database. |
| **Week-over-Week** | `WEEK_VS_WEEK` | **Not functional** — same reason |
| **Month-over-Month** | — | **Does not exist** — no such enum value |
| **Custom range** | `CUSTOM` | Accepted via `customRanges`, but only sub-60-minute windows return data |

Regression detection on the working path uses a percentage threshold, not statistics:
`DEFAULT_REGRESSION_THRESHOLD = 20.0`, with levels `critical` (> 2.5 × threshold ≈ 50%),
`warning` (> threshold ≈ 20%) and below.

### Instance Comparison

Implemented for **performance** only, via `MultiInstanceComparisonController` (`/api/multi-instance`).
Configuration diffing between instances is **not implemented**.

| Comparison | Description | Status |
|------------|-------------|--------|
| **Config Diff** | Compare configuration between instances | **Not implemented** |
| **Performance Diff** | Compare response time, error rate | Implemented — `GET /compare/all`, `POST /compare` |
| **Resource Diff** | Compare JVM memory, CPU usage | Implemented as part of the per-instance performance payload |

---

## Data Collection

### Metrics Stored

**Not implemented as described.** Nothing is *stored* for historical comparison in `gateway-admin`:

| Category | Reality |
|----------|---------|
| **Gateway Metrics** (QPS, response time, error rate, active connections) | Queried **live from Prometheus** on demand (`MonitorController` → `PrometheusClient`). Not persisted. |
| **JVM Metrics** (heap, GC, threads) | Live from Prometheus / Micrometer. Not persisted. |
| **Filter Metrics** (self-time, total-time, success rate) | Held **in memory only** on the gateway: `FilterChainTracker` (live) + `HistoricalDataTracker` (1-minute snapshots, rolling 60-minute window, lost on restart) |
| **Route Metrics** (request count, latency per route) | Live from Prometheus, `GET /api/monitor/routes` (≤24 h) |
| **Backend Metrics** (service response time, instance health) | Live from Prometheus / Nacos. Not persisted. |

Persisted time-series-like data does exist for *other* features (e.g. `access_log_entries`,
`distributed_trace`, `token_usage_history`), but there is **no metrics snapshot or downsampling table**.

### Storage Granularity

**Not implemented.** There is no multi-tier downsampling. The four-tier scheme below is design intent
only:

| Interval | Retention | Purpose | Status |
|----------|-----------|---------|--------|
| **1 minute** | 24 hours | Real-time analysis | Partially — `HistoricalDataTracker` collects 1-minute snapshots but retains only **60 minutes** (`MAX_SNAPSHOTS = 60`), in memory |
| **5 minutes** | 7 days | Hourly comparison | **Not implemented** |
| **1 hour** | 30 days | Daily/weekly comparison | **Not implemented** |
| **1 day** | 365 days | Monthly/yearly comparison | **Not implemented** — and contradicted by the hard 7-day cap below |

The longest historical window actually reachable is **7 days**: `MonitorController` clamps the `hours`
parameter with `Math.min(hours, 168)` for `/api/monitor/history` and `Math.min(hours, 24)` for
`/api/monitor/routes`. Beyond that, Prometheus retention is the only limit and the API will not ask
for it.

---

## API Endpoints

### Not implemented — `/api/historical/*`

**None of these endpoints exist.** There is no `HistoricalController` in `gateway-admin`, and a
repository-wide search for `api/historical` returns zero hits. Requests to them return 404.

| Method | Path | Status |
|--------|------|--------|
| `GET` | `/api/historical/compare` | **Not implemented** |
| `GET` | `/api/historical/trends` | **Not implemented** |
| `GET` | `/api/historical/anomalies` | **Not implemented** |
| `GET` | `/api/historical/predict` | **Not implemented** |
| `GET` | `/api/historical/instances/compare` | **Not implemented** — use `GET /api/multi-instance/compare/all` |

### Real endpoints

**Admin (`gateway-admin`, port 9090, `ApiResponse`-wrapped, JWT required; `POST` needs `ADMIN`):**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/multi-instance/compare/all` | Compare performance across all RUNNING instances |
| `POST` | `/api/multi-instance/compare` | Compare a chosen set of instances |
| `GET` | `/api/multi-instance/ranking` | Rank instances by performance |
| `GET` | `/api/multi-instance/outliers` | Identify performance outliers (requires ≥3 running instances) |
| `GET` | `/api/monitor/metrics?timestamp=` | Point-in-time metrics |
| `GET` | `/api/monitor/history?hours=&centerTime=` | Historical series from Prometheus, `hours` clamped to ≤168 |
| `GET` | `/api/monitor/routes?hours=` | Per-route history, `hours` clamped to ≤24 |

**Gateway (`my-gateway`, port 8080, `X-Internal-Token` required):**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/internal/filter-chain/comparison?comparisonType=&regressionThreshold=&currentStart=&currentEnd=&historicalStart=&historicalEnd=` | Filter-chain period comparison (`comparisonType` defaults to `HOUR_VS_HOUR`, `regressionThreshold` to `20`) |
| `GET` | `/internal/filter-chain/comparison/quick` | Last hour vs previous hour summary |
| `POST` | `/internal/filter-chain/comparison` | Same as the GET, with a JSON body |
| `GET` | `/internal/filter-chain/historical?minutes=` | Snapshot history (max 60 minutes) |
| `GET` | `/internal/filter-chain/historical/all` | All retained snapshots |
| `GET` | `/internal/filter-chain/historical/trend` | Trend analysis over the retained window |
| `DELETE` | `/internal/filter-chain/historical` | Clear the in-memory snapshot window |
| `GET` | `/internal/filter-chain/prediction` | 30-minute linear-regression prediction |
| `GET` | `/internal/filter-chain/prediction/quick` | Quick prediction summary |
| `GET` | `/internal/filter-chain/prediction/alerts` | Predicted alerts only |

### Compare Periods

The documented `GET /api/historical/compare?currentPeriod=1h&previousPeriod=1h` call and its
`{currentPeriod, previousPeriod, changes, status, anomalies}` response body have **no implementation**
and are retained below only as the intended shape. The working equivalent is:

```bash
# gateway side, requires X-Internal-Token
curl -H "X-Internal-Token: $INTERNAL_TOKEN" \
  "http://localhost:8080/internal/filter-chain/comparison?comparisonType=HOUR_VS_HOUR&regressionThreshold=20"
```

<details>
<summary>Design intent — unimplemented <code>/api/historical/compare</code> response shape</summary>

```json
{
  "currentPeriod": {
    "start": "2024-01-15T09:00:00Z",
    "end": "2024-01-15T10:00:00Z",
    "metrics": { "avgResponseTime": 150, "errorRate": 2.5, "qps": 1000 }
  },
  "previousPeriod": {
    "start": "2024-01-15T08:00:00Z",
    "end": "2024-01-15T09:00:00Z",
    "metrics": { "avgResponseTime": 120, "errorRate": 1.5, "qps": 800 }
  },
  "changes": { "avgResponseTime": "+25%", "errorRate": "+67%", "qps": "+25%" },
  "status": "WARNING",
  "anomalies": ["Error rate increased significantly"]
}
```

</details>

---

## Anomaly Detection

### Detection Types

**Not implemented as described.** There is no statistical, trend, pattern or correlation-based anomaly
detection in `gateway-admin` — a search for `standard deviation` / `stddev` / `correlation` finds no
detection code, and no statistics library is on the classpath. The table below is design intent.

| Type | Description | Threshold | Status |
|------|-------------|-----------|--------|
| **Statistical** | Values outside normal range | >2 standard deviations | **Not implemented** |
| **Trend** | Unexpected trend changes | >30% change in trend direction | **Not implemented** (the only trend logic is `PerformancePredictor`'s slope sign, see below) |
| **Pattern** | Unusual sequence patterns | AI-detected patterns | **Not implemented** |
| **Correlation** | Unexpected correlations | Correlation coefficient >0.8 | **Not implemented** |

### What actually exists

The only anomaly detector is on the **gateway** side and its scope is **filter health**, not
gateway-wide performance: `my-gateway/.../monitor/AIAnomalyDetector` (used together with
`FilterHealthMonitor`). It compares filter-chain stats against **fixed constants**, with no statistics
involved:

| Constant | Value | Check |
|----------|-------|-------|
| `SELF_TIME_THRESHOLD` | 10.0 ms | filter self-time exceeds threshold |
| `P95_THRESHOLD` | 20.0 ms | filter P95 exceeds threshold |
| `P99_THRESHOLD` | 50.0 ms | filter P99 exceeds threshold |
| `SUCCESS_RATE_THRESHOLD` | 95.0 % | filter success rate below threshold |
| `DEGRADATION_FACTOR` | 2.0 | degradation vs the historical snapshot window |

Each emitted anomaly carries `{type, severity, actualValue, threshold, deviation}` where
`deviation = actualValue - threshold`. It is reachable through
`GET /internal/filter-chain/anomalies`-style analysis endpoints and via the Copilot tool
`analyze_filter_anomaly`.

### Anomaly Categories

Design intent — the code only produces **Filter** anomalies:

| Category | Examples | Status |
|----------|----------|--------|
| **Performance** | Response time spike, throughput drop | **Not implemented** as a distinct category |
| **Resource** | Memory leak, CPU spike | **Not implemented** |
| **Error** | Error rate increase, failure pattern | **Not implemented** |
| **Filter** | Slow filter, filter chain anomaly | Implemented (`AIAnomalyDetector`) |

### Anomaly Response

**Not implemented.** No code produces a `PERFORMANCE_ANOMALY` alert type or the card format below; it
is retained as the intended presentation only.

<details>
<summary>Design intent — unimplemented anomaly card</summary>

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

</details>

---

## Performance Prediction

### Prediction Models

**Only linear regression exists**, and it lives on the **gateway** side in
`my-gateway/.../monitor/PerformancePredictor` — it predicts **filter-chain bottlenecks**, not
gateway-wide QPS or capacity. There is no ARIMA and no ML model anywhere in the repository, and no
statistics/ML dependency in any `pom.xml`.

| Model | Description | Accuracy | Status |
|-------|-------------|----------|--------|
| **Linear Regression** | Simple trend extrapolation | Good for steady trends | **Implemented** — `PerformancePredictor.linearRegression(...)` returns `[slope, intercept]` and extrapolates to `now + PREDICTION_MINUTES` (**30 minutes**) |
| **Moving Average** | Smoothed trend prediction | Good for noisy data | **Not implemented** |
| **ARIMA** | Time-series forecasting | Good for periodic patterns | **Not implemented** |
| **ML Model** | Machine learning prediction | Best for complex patterns | **Not implemented** |

### Prediction Output

The documented `{expectedQPS, expectedPeakQPS, expectedPeakTime, capacityWarning}` shape is
**not implemented** — nothing produces those fields. What `GET /internal/filter-chain/prediction`
actually returns is, per predicted metric:

```json
{
  "status": "success",
  "predictionTime": 1705312800000,
  "predictionWindowMinutes": 30,
  "confidenceLevel": 0.85,
  "confidenceStatus": "…",
  "recommendedActions": ["…"],
  "predictionAlerts": [ { "…": "…" } ],
  "alertsCount": 0
}
```

with each metric trend entry carrying:

| Field | Meaning |
|-------|---------|
| `metricName` | Display name of the metric (self-time, P95, P99, success rate, …) |
| `currentValue` | Last observed value in the snapshot window |
| `predictedValue` | `slope * futureTime + intercept`, 30 minutes ahead |
| `changeRate` | Percent change from `currentValue` to `predictedValue` |
| `trendDirection` | `上升` if `slope > 0.1`, `下降` if `slope < -0.1`, otherwise `稳定` |
| `slope` / `intercept` | Raw regression coefficients |
| `predictionMinutes` | Always 30 (`PREDICTION_MINUTES`) |
| `severity` | Derived from the predicted value |

<details>
<summary>Design intent — unimplemented prediction payload</summary>

```json
{
  "prediction": {
    "nextHour": { "expectedQPS": 1200, "expectedResponseTime": 160, "confidenceLevel": 0.85 },
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

</details>

---

## Trend Analysis

### Trend Metrics

**Design intent.** No component classifies gateway-wide metric trends this way. The only trend
direction produced by code is `PerformancePredictor`'s per-filter `trendDirection`
(`上升` / `下降` / `稳定`, from the regression slope) exposed at
`GET /internal/filter-chain/historical/trend`.

| Metric | Trend Direction | Interpretation | Status |
|--------|-----------------|----------------|--------|
| **Response Time** | Increasing | Performance degradation | Per-filter only, via regression slope |
| **QPS** | Increasing | Growing traffic | **Not implemented** as a trend classifier |
| **Error Rate** | Increasing | Stability issues | Per-filter success-rate slope only |
| **Memory Usage** | Increasing | Possible leak | **Not implemented** |

### Trend Visualization

**Design intent** — the ASCII chart below (including "Will reach 250ms in 5 days") is not produced by
any code. Note also that a 7-day chart is the longest window the API can serve at all
(`Math.min(hours, 168)`), and the gateway-side snapshot window is only 60 minutes.

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

**Implemented** — this is the one part of the document that maps to real code:
`MultiInstanceComparisonController` (`@RequestMapping("/api/multi-instance")`).

| Method | Path | Behaviour |
|--------|------|-----------|
| `GET` | `/api/multi-instance/compare/all` | Collects performance for every instance in `RUNNING` state; returns `notFound` if there are none |
| `POST` | `/api/multi-instance/compare` | Compares an explicitly supplied set of instances |
| `GET` | `/api/multi-instance/ranking` | Ranks running instances by performance |
| `GET` | `/api/multi-instance/outliers` | Flags outliers; requires **at least 3** running instances |

The comparison covers **performance/resource** data. The "Configuration Differences" block in the
sample report below is **not implemented** — there is no cross-instance config diff.

### Instance Comparison Report

Illustrative rendering of the `/api/multi-instance` result. The configuration-diff lines are design
intent only.

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

Only **one** of the five tools originally listed here is registered. `compare_periods`,
`detect_anomalies`, `predict_performance` and `analyze_trends` do **not** exist in `ToolRegistry`.

| Tool | Capability | Status |
|------|------------|--------|
| `compare_periods` | Compare metrics between periods | **Not registered** |
| `detect_anomalies` | AI-powered anomaly detection | **Not registered** — the nearest real tool is `analyze_filter_anomaly` (filter-chain anomalies only) |
| `predict_performance` | Predict future performance | **Not registered** — the nearest real tool is `predict_filter_performance` (filter bottleneck extrapolation) |
| `analyze_trends` | Trend analysis with recommendations | **Not registered** |
| `compare_instances` | Multi-instance comparison | **Registered** — config differences and performance comparison |

Related tools that do exist and are useful for historical work: `get_history_metrics` (time-series
metrics), `get_gateway_metrics`, `get_route_metrics`, `get_pod_metrics`, `get_filter_chain_stats`,
`get_slow_requests`, `get_slow_traces`, `get_trace_statistics`. See
[AI Copilot Assistant](ai-copilot.md) for the full 65-tool list.

### Example: AI Analysis

**Design intent** — the exchange below assumes the unregistered `compare_periods` tool and is not
reproducible today.

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

Applicable today, given the 7-day Prometheus window and the 60-minute gateway snapshot window:

1. **Regular Comparison**: Use `GET /api/multi-instance/compare/all` and `GET /api/monitor/history` — but note nothing is retained beyond Prometheus' own retention, and the API caps requests at 168 hours.
2. **Baseline Establishment**: Establish normal performance baselines yourself; the system does not store or learn one.
3. **Anomaly Thresholds**: **Not configurable.** `AIAnomalyDetector`'s thresholds are compile-time constants (10 ms self-time, 20 ms P95, 50 ms P99, 95 % success rate, 2.0 degradation factor). The only tunable knob is `regressionThreshold` (default 20 %) on `/internal/filter-chain/comparison`.
4. **Prediction Validation**: Validate the 30-minute linear-regression prediction against actual data — it is a straight-line extrapolation over at most 60 minutes of snapshots, so treat it as indicative only.
5. **Cross-Instance Analysis**: Regularly compare instances for consistency via `/api/multi-instance/*`.

---

## Configuration

**Not implemented.** The `historical.*` block below does not exist in
`gateway-admin/src/main/resources/application.yml`, `application-prod.yml` or
`my-gateway/src/main/resources/application.yml` — adding it would have no effect, because no
`@ConfigurationProperties` class binds the `historical` prefix. It is kept as design intent.

<details>
<summary>Design intent — unimplemented configuration block</summary>

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

</details>

The values that are actually in force are hard-coded:

| Setting | Value | Where |
|---------|-------|-------|
| Snapshot interval | 1 minute | `HistoricalDataTracker` |
| Snapshot retention | 60 snapshots (60 minutes), in memory, lost on restart | `HistoricalDataTracker.MAX_SNAPSHOTS` |
| Prediction horizon | 30 minutes | `PerformancePredictor.PREDICTION_MINUTES` |
| Regression threshold | 20 % (`critical` at ~50 %) | `PeriodComparisonAnalyzer.DEFAULT_REGRESSION_THRESHOLD` |
| Max history query | 168 hours (`/api/monitor/history`), 24 hours (`/api/monitor/routes`) | `MonitorController` |
| Anomaly thresholds | 10 / 20 / 50 ms, 95 %, factor 2.0 | `AIAnomalyDetector` |

---

## Related Features

- [Filter Chain Analysis](filter-chain-analysis.md) - Filter performance analysis
- [Monitoring & Alerts](monitoring-alerts.md) - Real-time monitoring
- [AI Copilot](ai-copilot.md) - AI-powered analysis
- [Request Tracing](request-tracing.md) - Request-level tracing