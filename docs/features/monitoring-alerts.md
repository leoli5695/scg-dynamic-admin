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
curl "http://localhost:9090/api/monitor/metrics?timestamp=1704067200"

# Response includes historical flag
{
  "code": 200,
  "data": { ... },
  "queryTimestamp": 1704067200,
  "isHistorical": true
}
```

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

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "jvm": {
    "heapUsed": 512,
    "heapMax": 1024,
    "heapUsagePercent": 50,
    "gcCount": 10,
    "gcTimeMs": 100
  },
  "cpu": {
    "processUsage": 45,
    "systemUsage": 30
  },
  "http": {
    "requestsPerSecond": 1234,
    "avgResponseTimeMs": 45,
    "errorRatePercent": 0.5,
    "status2xx": 9950,
    "status4xx": 30,
    "status5xx": 20
  }
}
```

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

Configure SMTP:

```json
{
  "host": "smtp.example.com",
  "port": 587,
  "username": "alerts@example.com",
  "password": "password",
  "from": "Gateway Alerts <alerts@example.com>",
  "useStartTls": true
}
```

### AI-Generated Content

Alert emails are generated by AI and include:
- Problem description
- Impact scope
- Recommended actions

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

# Key Metrics
gateway_requests_total
gateway_requests_duration_seconds
gateway_active_connections
jvm_memory_used_bytes
process_cpu_usage
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/monitor/metrics` | Current metrics |
| `GET` | `/api/monitor/history` | Historical metrics |
| `POST` | `/api/monitor/analyze` | AI analysis |
| `GET` | `/api/alerts/config` | Get alert config |
| `PUT` | `/api/alerts/config` | Update alert config |
| `GET` | `/api/alerts/history` | Alert history |

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