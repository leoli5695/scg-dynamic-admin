# Traffic Topology

> Traffic topology visually displays request flow, supporting real-time and historical data.

---

## Overview

Traffic topology uses ECharts force-directed graph for visualization:

```
┌─────────────────────────────────────────────┐
│         TRAFFIC TOPOLOGY                     │
│                                              │
│   Client IP → Gateway → Route → Service     │
│                                              │
│   Node Types:                                │
│   - gateway (blue)                           │
│   - route (cyan)                             │
│   - service (purple)                         │
│   - client (orange)                          │
│                                              │
│   Edge Metrics:                              │
│   - requestCount                             │
│   - avgLatency                               │
│   - errorRate                                │
└─────────────────────────────────────────────┘
```

---

## Features

| Feature | Description |
|---------|-------------|
| **Real-time Graph** | Real-time traffic topology graph |
| **Traffic Metrics** | Request count, error rate, latency |
| **Time Range** | 15min, 30min, 1h, 3h, 6h |
| **Auto Refresh** | Auto refresh every 30 seconds |
| **Node Details** | Click to view detailed metrics |

---

## Node Types

| Node Type | Color | Description |
|-----------|-------|-------------|
| `gateway` | Blue | Gateway instance |
| `route` | Cyan | Route definition |
| `service` | Purple | Backend service |
| `client` | Orange | Client IP |

---

## Edge Metrics

| Metric | Description |
|--------|-------------|
| `requestCount` | Total requests on this path |
| `avgLatency` | Average response latency |
| `errorRate` | Error rate percentage |

### Edge Styling

- **Width**: `min(8, max(1, requestCount / 50))`
- **Color**:
  - Green: errorRate < 5%
  - Orange: errorRate 5-10%
  - Red: errorRate > 10%

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/topology/{instanceId}` | Get topology data |

### Response Example

```json
{
  "nodes": [
    {"id": "gateway-1", "type": "gateway", "name": "Gateway"},
    {"id": "route-users", "type": "route", "name": "/api/users/**"},
    {"id": "service-user", "type": "service", "name": "user-service"},
    {"id": "client-192.168.1.100", "type": "client", "name": "192.168.1.100"}
  ],
  "edges": [
    {
      "source": "client-192.168.1.100",
      "target": "gateway-1",
      "metrics": {"requestCount": 500, "avgLatency": 45, "errorRate": 0.5}
    },
    {
      "source": "gateway-1",
      "target": "route-users",
      "metrics": {"requestCount": 500, "avgLatency": 40, "errorRate": 0.2}
    }
  ],
  "metrics": {
    "totalRequests": 15000,
    "requestsPerSecond": 125.5,
    "avgLatency": 45.2,
    "errorRate": 0.5,
    "uniqueClients": 50,
    "uniqueRoutes": 10
  }
}
```

---

## Best Practices

1. **Regular Review**: Understand traffic distribution
2. **Anomaly Detection**: Focus on red edge paths
3. **Latency Analysis**: Identify high latency paths
4. **Client Analysis**: Understand primary client sources
5. **Combine with Monitoring**: Compare with monitoring data for analysis

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - Monitoring data
- [Request Tracing](request-tracing.md) - Request tracing
- [Analytics](analytics.md) - Data analysis