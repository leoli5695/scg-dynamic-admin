# System Diagnostic

> System diagnostic provides comprehensive health checks to help identify potential issues.

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
│   │ Service         │                        │
│   └────────┬────────┘                        │
│            │                                 │
│   ┌────┴────┬────┬────┬────┬────┐           │
│   │    │    │    │    │    │    │           │
│   ▼    ▼    ▼    ▼    ▼    ▼    ▼           │
│  DB  Redis Nacos Route Auth Inst Perf        │
│                                              │
│   ┌─────────────────┐                        │
│   │ Diagnostic      │                        │
│   │ Report          │                        │
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
| **Database** | Connection status, query latency, table integrity |
| **Redis** | Connection status, memory usage, key count |
| **Nacos** | Connection status, configuration sync status |
| **Routes** | Route count, enabled status, invalid routes |
| **Authentication** | Authentication policy status, JWT verification test |
| **Gateway Instances** | Instance count, health status, heartbeat check |
| **Performance** | CPU, memory, thread count, JVM metrics |

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

### Response Example

```json
{
  "overallScore": 85,
  "status": "HEALTHY",
  "duration": "5.2s",
  "recommendations": [
    "Consider increasing Redis memory allocation",
    "Review expired SSL certificates"
  ],
  "database": {
    "status": "HEALTHY",
    "metrics": {
      "connectionPoolSize": 10,
      "activeConnections": 3,
      "avgQueryLatencyMs": 12
    }
  },
  "redis": {
    "status": "WARNING",
    "warnings": ["Memory usage above 80%"],
    "metrics": {
      "connected": true,
      "memoryUsageMB": 512,
      "keyCount": 15000
    }
  },
  "nacos": {
    "status": "HEALTHY",
    "metrics": {
      "connected": true,
      "configSyncStatus": "SYNCED"
    }
  }
}
```

---

## Scoring Algorithm

```
Component Scores:
- Database:    100 if HEALTHY, 50 if WARNING, 0 if CRITICAL
- Redis:       100 if HEALTHY, 50 if WARNING, 0 if CRITICAL
- Nacos:       100 if HEALTHY, 50 if WARNING, 0 if CRITICAL
- Routes:      Based on enabled/valid route percentage
- Auth:        100 if policies valid, 0 if issues
- Instances:   Based on healthy instance percentage
- Performance: Based on CPU/memory thresholds

Overall Score = Weighted Average:
- Database:    20%
- Redis:       15%
- Nacos:       20%
- Routes:      15%
- Auth:        10%
- Instances:   10%
- Performance: 10%
```

---

## Best Practices

1. **Regular Diagnostics**: Set up scheduled diagnostic tasks
2. **Address Warnings**: Promptly handle warning issues
3. **Full Diagnostics**: Run full diagnostics after major changes
4. **Monitor History**: Compare historical diagnostic results
5. **AI Analysis**: Combine with AI analysis for diagnostic reports

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - Monitoring data source
- [AI-Powered Analysis](ai-analysis.md) - AI analysis of diagnostic results
- [Gateway Instance Management](instance-management.md) - Instance health check