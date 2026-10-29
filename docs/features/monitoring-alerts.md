# Monitoring & Alerts

> Gateway provides real-time monitoring and alerting capabilities for JVM, CPU, HTTP, and other metrics.

---

## Overview

Monitoring metrics categories:

| Category | Metrics |
|----------|---------|
| **JVM** | Heap usage, GC count/time, thread count |
| **CPU** | Process usage, System usage |
| **Memory** | Used/Max memory |
| **HTTP** | Requests/sec, Avg response time, Error rate |
| **Status** | 2xx/4xx/5xx distribution |

---

## Metrics API

```bash
# Get current metrics (real-time)
GET /api/monitor/metrics

# Get historical metrics (time series)
GET /api/monitor/history?hours=1

# Get historical metrics at a specific time point
GET /api/monitor/metrics?timestamp=1704067200
```

### Historical Time Point Query (New Feature)

**Added: 2026-04-25**

You can now query metrics at a specific historical time point, not just current real-time data:

```bash
# Query metrics at Unix timestamp (seconds)
curl "http://localhost:9090/api/monitor/metrics?timestamp=1704067200" \
  -H "Authorization: Bearer <JWT>"

# Response: everything is inside `data` (ApiResponse wrapper)
{
  "code": 200,
  "message": "Success",
  "data": {
    "metrics": { ... },
    "prometheusAvailable": true,
    "queryTimestamp": 1704067200,
    "isHistorical": true
  }
}
```

`queryTimestamp` and `isHistorical` are **not** top-level fields — they are added to the `data` map
next to `metrics` and `prometheusAvailable`, and only when a `timestamp` was supplied
(`MonitorController.java:41-48`).

**Benefits:**
- Investigate issues that occurred in the past
- Compare metrics across different time points
- No need to replay entire time series for a single time point

**Implementation:**
- Backend uses ThreadLocal to pass timestamp context
- All Prometheus queries automatically use historical time parameter
- Frontend will support real-time/historical mode switch

---

### Response Example

`GET /api/monitor/metrics` returns an `ApiResponse`; the metrics map lives at **`data.metrics`**
(`MonitorController.java:41-48`). Its top-level keys are the ones produced by
`PrometheusService.getGatewayMetricsAtTime`: `instances`, `jvmMemory`, `gc`, `threads`,
`httpRequests`, `httpStatus`, `cpu`, `process`, `disk`, `gateway`, `logEvents`
(or a single `error` key when Prometheus has no data for the requested time).

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "prometheusAvailable": true,
    "metrics": {
      "instances": [ { "instanceId": "tj9j963zq2x1", "status": "UP" } ],
      "jvmMemory": {
        "heapUsed": 536870912,
        "heapMax": 1073741824,
        "nonHeapUsed": 123456789,
        "heapUsagePercent": 50.0
      },
      "gc": {
        "gcCount": 10,
        "gcTimeSeconds": 0.1,
        "gcOverheadPercent": 0.5,
        "allocationRateMBPerSec": 30.0,
        "promotionRateMBPerSec": 2.0,
        "promotionRatio": 6.67,
        "memoryRegions": { },
        "healthStatus": "HEALTHY",
        "healthReason": "GC表现正常，Young GC平均耗时10ms"
      },
      "threads": { "liveThreads": 42, "daemonThreads": 30, "peakThreads": 55 },
      "httpRequests": {
        "requestsPerSecond": 1234.0,
        "avgResponseTimeMs": 45,
        "errorRate": 0.5
      },
      "httpStatus": { "status2xx": 9950, "status4xx": 30, "status5xx": 20 },
      "cpu": { "processUsage": 45.0, "systemUsage": 30.0, "availableProcessors": 4 }
    }
  }
}
```

> There is no top-level `timestamp` field and no `jvm` / `http` object: JVM counters are under
> `data.metrics.jvmMemory` and `data.metrics.gc`, HTTP counters under `data.metrics.httpRequests`
> and `data.metrics.httpStatus`.

---

## GC Health Status & Promotion Rate Analysis (New Feature)

**Added: 2026-04-25**

Gateway now provides intelligent GC health assessment with promotion rate analysis:

### GC Health Status

| Status | Condition | Description |
|--------|-----------|-------------|
| `HEALTHY` | Normal GC patterns | GC performance is optimal |
| `WARNING` | Suboptimal patterns detected | Requires attention, may need tuning |
| `CRITICAL` | Severe GC issues | Immediate action required |

### Promotion Rate Combined Diagnostics

**Beyond simple threshold alerts, the system now provides intelligent combined diagnostics:**

#### 1. High Promotion Rate + High Allocation Rate

```
Trigger: promotionRateMBPerSec > 10 && allocationRateMBPerSec > 50
Diagnosis: Short-lived objects promoting to Old Gen rapidly
Recommendation: Adjust Survivor space size to keep short-lived objects in Young Gen longer
Example: "高晋升速率（15 MB/s）+ 高分配速率（80 MB/s），对象生命周期短但有大量短期对象晋升，需调整Survivor区"
```

#### 2. High Promotion Rate + Low Allocation Rate

```
Trigger: promotionRateMBPerSec > 10 && allocationRateMBPerSec <= 50
Diagnosis: Large objects bypassing Young Gen directly to Old Gen
Recommendation: Check code for large object allocations, consider increasing young gen size
Example: "高晋升速率（15 MB/s）+ 低分配速率（30 MB/s），有大对象直接进Old Gen，需检查代码"
```

#### 3. High Promotion Ratio (Memory Leak Indicator)

```
Trigger: promotionRatio > 30% (promotion rate / allocation rate)
Diagnosis: Too many objects surviving, possible memory leak
Recommendation: Investigate long-lived object accumulation, potential memory leak
Example: "晋升比例过高（35%），可能存在内存泄漏"
```

### GC Health Assessment Logic

The system evaluates multiple conditions in priority order:

```java
// Priority 1: Critical - Full GC frequency
if (fullGcCount > 3 per 5min) {
    status = CRITICAL;
    reason = "Full GC频繁，可能存在内存压力";
}

// Priority 2: Warning - Full GC occurrence
else if (fullGcCount > 1 per 5min) {
    status = WARNING;
    reason = "有Full GC发生，需关注内存使用";
}

// Priority 3: Warning - Old Gen usage
else if (oldGenUsagePercent > 80%) {
    status = WARNING;
    reason = "Old Gen使用率过高，可能即将触发Full GC";
}

// Priority 4: Warning - GC overhead
else if (gcOverheadPercent > 10%) {
    status = WARNING;
    reason = "GC开销过高，影响应用性能";
}

// Priority 5: New! Promotion rate diagnostics
else if (promotionRateMBPerSec > 10 && allocationRateMBPerSec > 50) {
    status = WARNING;
    reason = "高晋升+高分配，需调整Survivor区";
}

// Priority 6: New! Large object detection
else if (promotionRateMBPerSec > 10 && allocationRateMBPerSec <= 50) {
    status = WARNING;
    reason = "高晋升+低分配，需检查大对象分配";
}

// Priority 7: New! Memory leak indicator
else if (promotionRatio > 30%) {
    status = WARNING;
    reason = "晋升比例过高，可能存在内存泄漏";
}

// Priority 8: Warning - Young GC frequency
else if (youngGcCount > 100 per 5min) {
    status = WARNING;
    reason = "Young GC过于频繁，建议增大年轻代";
}

// Default: Healthy
else {
    status = HEALTHY;
    reason = "GC表现正常，Young GC平均耗时XXms";
}
```

### Metrics Display

**Monitor UI now shows:**

- **GC Status Card**: Displays `HEALTHY`, `WARNING`, or `CRITICAL` tag with diagnostic reason
- **Promotion Rate**: `MB/s` (bytes promoted from Young to Old Gen per second)
- **Promotion Ratio**: `%` (promotion rate / allocation rate)
- **Allocation Rate**: `MB/s` (total memory allocation rate)
- **Memory Regions**: Eden, Survivor, Old Gen usage with percentages

---

```json
{
  "cpu": {
    "processThreshold": 80,
    "systemThreshold": 90
  },
  "memory": {
    "heapThreshold": 85
  },
  "http": {
    "errorRateThreshold": 5,
    "responseTimeThreshold": 2000
  }
}
```

| Metric | Threshold | Action |
|--------|-----------|--------|
| CPU Process | > 80% | WARNING alert |
| CPU System | > 90% | CRITICAL alert |
| Heap Usage | > 85% | WARNING alert |
| Error Rate | > 5% | WARNING alert |
| Response Time | > 2000ms | WARNING alert |

---

## Alert Notification

### Email Notification

Configure SMTP through the `EmailConfig` entity (`POST`/`PUT /api/email/configs`, see
[Email Notifications](email-notifications.md)). The real field names are `smtpHost`, `smtpPort`,
`smtpUsername`, `smtpPassword`, `useSsl`, `useStartTls`, `fromEmail`, `fromName` — `host`, `port`,
`username`, `password` and `from` do not exist.

```json
{
  "configName": "Default SMTP Config",
  "smtpHost": "smtp.example.com",
  "smtpPort": 465,
  "smtpUsername": "alerts@example.com",
  "smtpPassword": "password",
  "useSsl": true,
  "useStartTls": false,
  "fromEmail": "alerts@example.com",
  "fromName": "Gateway Alerts",
  "enabled": true
}
```

Defaults (`EmailConfig.java:26-80`): port **465**, `useSsl` **true**, `useStartTls` **false**.

### Email Content

Two layers, and it matters which one you mean:

- **Body text** - `AlertContentGenerator.generateAlertContent` (`:62-90`) asks
  `aiConfigRepository.findFirstValidConfig()` (`:67`). When a valid AI config exists the text is
  AI-generated; when there is none (`:71`) or the AI call throws (`:85-88`) it falls back to
  `generateDefaultContent` (`:199`), a Java-rendered template. So alert emails are AI-generated
  **only if** an AI provider is configured and reachable - they are never blocked on it.
- **HTML envelope** - always `AlertEmailBuilder.buildAlertEmailBody(alertType, level, metricName,
  currentValue, threshold, content, language)` (`:16`), which wraps that content via `buildZhBody` /
  `buildEnBody` (Java text blocks). There are **no `.html` template files** in the repository.
  Called from `AlertCheckService.java:288-293`.

The envelope contains: alert type, level, metric name, current value, threshold and instance.
On-demand AI analysis of a time range is a separate feature (`POST /api/ai/analyze/timerange`).

---

## Dashboard

Admin UI provides real-time monitoring dashboard:
- JVM memory charts
- CPU usage trends
- HTTP QPS curves
- Response time distribution
- Error rate statistics

---

## Prometheus Integration

Gateway exposes Prometheus metrics:

```
# Endpoint
GET /actuator/prometheus

# Metrics the gateway registers itself (Micrometer, my-gateway)
gateway_accesslog_dropped_total      # gateway.accesslog.dropped  (AccessLogGlobalFilter.java:126)
gateway_accesslog_queued             # gateway.accesslog.queued   (AccessLogGlobalFilter.java:128)
gateway_trace_export_queued_bytes    # TraceReportDispatcher.java:46
gateway_trace_export_queued_reports  # TraceReportDispatcher.java:47
gateway_trace_export_dropped         # TraceReportDispatcher.java:69
gateway_trace_export_sent            # TraceReportDispatcher.java:101

# Micrometer / Spring Boot auto-registered metrics that gateway-admin actually queries
jvm_memory_used_bytes
jvm_memory_max_bytes
jvm_gc_pause_seconds_count
jvm_gc_pause_seconds_sum
jvm_gc_memory_allocated_bytes_total
jvm_gc_memory_promoted_bytes_total
jvm_threads_live_threads
process_cpu_usage
system_cpu_usage
system_cpu_count
process_uptime_seconds
disk_free_bytes
http_server_requests_seconds_count
http_server_requests_seconds_sum
spring_cloud_gateway_routes_count
```

> `gateway_requests_total`, `gateway_requests_duration_seconds` and `gateway_active_connections`
> **do not exist** — grepping them across `my-gateway/src/main/java`, `gateway-admin/src/main/java`
> and `gateway-ui/src` returns zero hits. The only gateway-specific meters are the
> `gateway.accesslog.*` and `gateway.trace.export.*` families listed above; HTTP throughput and
> latency come from the Spring Boot auto-registered `http_server_requests_seconds_*`
> (see `PrometheusService.java`).

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/monitor/metrics` | Current metrics; optional `timestamp` for a historical point (`MonitorController.java:34`) |
| `GET` | `/api/monitor/status` | Prometheus availability / monitor status (`:58`) |
| `GET` | `/api/monitor/history` | Historical metrics; `hours` default 24, capped at 168 (`:72`) |
| `GET` | `/api/monitor/routes` | Per-route response time / error rate / throughput (`:102`) |
| `GET` | `/api/alerts/config` | Get alert configs (`AlertController.java:43`) |
| `POST` | `/api/alerts/config` | Create an alert config (`:91`) |
| `PUT` | `/api/alerts/config/{id}` | Update an alert config — the `{id}` path variable is required (`:141`) |
| `GET` | `/api/alerts/history` | Alert history (`:154`) |
| `DELETE` | `/api/alerts/history` | Clear alert history (`:183`) |
| `POST` | `/api/alerts/test` | Send a test alert notification (`:209`) |
| `GET` | `/api/alerts/thresholds/metrics` | Available threshold metrics (`:237`) |
| `POST` | `/api/alerts/trigger` | Manually trigger an alert (`:293`) |

> There is **no** `POST /api/monitor/analyze` — `MonitorController` (`@RequestMapping("/api/monitor")`,
> `:22`) exposes only the four GET endpoints above. AI analysis of a time range lives at
> **`POST /api/ai/analyze/timerange`** (`AiConfigController.java:20,30`).
> There is also no `PUT /api/alerts/config` without an `{id}`.
> All of these require a JWT, and every POST/PUT/DELETE under `/api/**` requires the `ADMIN`
> authority (`SecurityConfig.java:122-127`).

---

## Best Practices

1. **Threshold Tuning**: Adjust thresholds based on business scenarios
2. **Alert Levels**: Distinguish between WARNING and CRITICAL
3. **Notification Channels**: Multi-channel notifications (email, DingTalk)
4. **Historical Analysis**: Regularly analyze historical data
5. **AI Analysis**: Leverage AI to discover potential issues
6. **Historical Time Point Query**: Use timestamp parameter to investigate past issues
7. **GC Tuning**: Follow promotion rate diagnostic recommendations
8. **Memory Leak Detection**: Monitor promotion ratio trend, investigate if > 30%

---

---

## Related Features

- [AI-Powered Analysis](ai-analysis.md) - AI analysis features
- [Email Notifications](email-notifications.md) - Email configuration
- [Request Tracing](request-tracing.md) - Error request tracing