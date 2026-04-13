# Request Tracing

> Request tracing functionality captures errors and slow requests, supports multi-instance queries, filtering by route/IP, time range statistics, providing data source for request replay debugging.

---

## Overview

Request tracing automatically captures:

| Capture Type | Condition | Storage |
|--------------|-----------|---------|
| **ERROR** | HTTP 4xx/5xx | Complete request/response |
| **SLOW** | Exceeds threshold (configurable) | Complete request/response |
| **ALL** | Sampling mode (optional) | Sampled requests |

---

## Trace Data Fields

Each trace record contains:

| Field | Description | Max Size |
|-------|-------------|----------|
| `traceId` | UUID format trace ID | 36 chars |
| `instanceId` | Gateway instance ID | 12 chars |
| `routeId` | Matched route ID | UUID |
| `method` | HTTP method | GET/POST/PUT/DELETE |
| `uri` | Complete URI | - |
| `path` | Request path | - |
| `queryString` | Query parameters | - |
| `requestHeaders` | Request Headers JSON | - |
| `requestBody` | Request body | 64KB (truncated if exceeded) |
| `statusCode` | Response status code | 100-599 |
| `responseBody` | Response body | 64KB (truncated if exceeded) |
| `latencyMs` | Response time (milliseconds) | - |
| `clientIp` | Client IP | - |
| `userAgent` | User-Agent | - |
| `targetInstance` | Target backend instance | IP:Port |
| `traceTime` | Timestamp | LocalDateTime |
| `traceType` | Type | ERROR/SLOW/ALL |
| `replayType` | Replay type | HTTP/WEBSOCKET/SSE |
| `replayable` | Whether replayable | true/false |
| `replayCount` | Replay count | - |
| `replayResult` | Last replay result | - |

---

## Multi-Instance Support

All queries support filtering by instance:

| API | Instance Filter |
|-----|-----------------|
| `/api/traces` | `?instanceId=gateway-1` |
| `/api/traces/errors` | `?instanceId=gateway-1` |
| `/api/traces/slow` | `?instanceId=gateway-1` |
| `/api/traces/stats` | `?instanceId=gateway-1` |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/traces` | Paginated query all traces |
| `GET` | `/api/traces/errors` | Error requests (4xx/5xx) |
| `GET` | `/api/traces/slow` | Slow requests |
| `GET` | `/api/traces/{id}` | Trace details |
| `GET` | `/api/traces/stats` | Statistics summary |
| `GET` | `/api/traces/route/{routeId}` | Query by route |
| `GET` | `/api/traces/client/{ip}` | Query by client IP |
| `GET` | `/api/traces/time-range` | Query by time range |
| `DELETE` | `/api/traces/{id}` | Delete single trace |
| `DELETE` | `/api/traces/old` | Clean up old traces |

### List Error Traces

```bash
curl "http://localhost:9090/api/traces/errors?instanceId=gateway-1&page=0&size=20"
```

Response:
```json
{
  "content": [
    {
      "id": 123,
      "traceId": "abc-123",
      "instanceId": "gateway-1",
      "routeId": "user-api-route",
      "method": "POST",
      "path": "/api/users",
      "statusCode": 500,
      "latencyMs": 1500,
      "clientIp": "192.168.1.100",
      "traceTime": "2024-01-15T10:30:00",
      "traceType": "ERROR",
      "replayable": true
    }
  ],
  "totalElements": 45,
  "totalPages": 3,
  "page": 0,
  "size": 20
}
```

### Get Trace Statistics

```bash
curl "http://localhost:9090/api/traces/stats?instanceId=gateway-1"
```

Response:
```json
{
  "total": 1234,
  "errorsToday": 45,
  "errorsLastHour": 12,
  "recentErrors": [
    {"traceId": "abc-123", "path": "/api/users", "statusCode": 500}
  ]
}
```

---

## Query by Dimension

### By Route ID

```bash
GET /api/traces/route/user-api-route?instanceId=gateway-1
```

Purpose: Analyze error patterns and response time distribution for specific routes.

### By Client IP

```bash
GET /api/traces/client/192.168.1.100?instanceId=gateway-1
```

Purpose: Track request issues from specific clients, identify abnormal clients.

### By Time Range

```bash
GET /api/traces/time-range?start=2024-01-15T00:00:00&end=2024-01-15T23:59:59&instanceId=gateway-1
```

Purpose: Analyze issues in specific time periods, such as fault period investigation.

---

## Slow Request Threshold

```bash
GET /api/traces/slow?thresholdMs=1000&instanceId=gateway-1
```

Dynamic threshold query, requests with response time exceeding threshold.

---

## Trace Type Detection

System automatically detects request type and marks:

| replayType | Detection Method | replayable |
|------------|------------------|------------|
| `HTTP` | Default | true |
| `WEBSOCKET` | Upgrade header, ws://, wss:// | false |
| `SSE` | Accept: text/event-stream | false |

---

## Data Retention

Clean up old data:

```bash
DELETE /api/traces/old?daysToKeep=7&instanceId=gateway-1
```

Retain by days, default cleans up traces older than specified days.

---

## Route Performance Statistics

Route-level statistics available for AI Copilot:

```bash
# AI Tool: get_route_metrics
GET /internal/traces/route-metrics?hours=1&sortBy=count&limit=10
```

Response:
```json
{
  "timeRange": "1 hour",
  "sortBy": "count",
  "totalRoutes": 25,
  "routeMetrics": [
    {
      "routeId": "user-api",
      "count": 15000,
      "avgLatencyMs": 45,
      "minLatencyMs": 10,
      "maxLatencyMs": 500,
      "errorCount": 12,
      "serverErrorCount": 5,
      "errorRate": 0.08
    }
  ]
}
```

Sort options:
- `count`: Sort by request count (high traffic routes)
- `avgLatency`: Sort by average latency (slow routes)
- `errorRate`: Sort by error rate (problem routes)

---

## Request Replay

Replay captured requests for debugging:

```bash
POST /api/traces/{id}/replay?gatewayUrl=http://localhost:8080
```

Gateway will:
1. Load original request data
2. Check replayable flag
3. Re-execute request (add X-Replay-Trace-Id Header)
4. Return comparison result
5. Update replayCount and replayResult

### Replay Result

```json
{
  "success": true,
  "statusCode": 200,
  "latency": 45,
  "responseBody": "{\"id\": 1}",
  "requestUrl": "http://localhost:8080/api/users",
  "method": "POST"
}
```

---

## AI Copilot Integration

AI Copilot can call the following tools to analyze traces:

| Tool | Capability |
|------|------------|
| `audit_query` | Query audit logs (configuration change history) |
| `get_route_metrics` | Get route-level performance statistics |

---

## Configuration

```yaml
gateway:
  trace:
    enabled: true
    error-trace-enabled: true
    slow-trace-threshold-ms: 1000
    max-trace-count: 1000
    retention-days: 7
    max-body-size: 65536  # 64KB
```

---

## Body Truncation

Request/response body automatically truncated when exceeding 64KB:

```
{"data": "...very long content..."} -> 
{"data": "...first 64KB...[TRUNCATED]"}
```

---

## Use Cases

| Use Case | Query | Benefit |
|----------|-------|---------|
| **Troubleshooting** | `/errors` + traceId details | Locate failed request cause |
| **Performance Analysis** | `/slow?thresholdMs=500` | Identify slow request patterns |
| **Route Optimization** | `/route/{routeId}` | Analyze specific route issues |
| **Client Tracking** | `/client/{ip}` | Identify abnormal clients |
| **Time Period Analysis** | `/time-range` | Analyze fault periods |
| **Capacity Planning** | `/stats` + `/route-metrics` | Evaluate traffic distribution |

---

## Best Practices

1. **Threshold Setting**: Set slow request threshold based on business P95 response time
2. **Regular Cleanup**: Set reasonable retention days (7-30 days)
3. **Query by Instance**: Filter by instanceId in multi-instance deployment
4. **Route Analysis**: Prioritize investigating high error rate routes
5. **Sampling Configuration**: Production can sample instead of full capture
6. **Sensitive Data**: Be aware of sensitive information in request body
7. **Replay Verification**: Replay after fix to verify effectiveness

---

## Related Features

- [Request Replay Debugger](request-replay.md) - Detailed replay functionality
- [Filter Chain Analysis](filter-chain-analysis.md) - Filter execution analysis
- [Monitoring & Alerts](monitoring-alerts.md) - Monitoring and alerts
- [AI Copilot](ai-copilot.md) - AI tool call analysis