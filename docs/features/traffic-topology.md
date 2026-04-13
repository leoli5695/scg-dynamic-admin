# Traffic Topology

> 流量拓扑功能可视化展示请求流向，支持实时和历史数据。

---

## Overview

流量拓扑使用 ECharts 力导向图展示：

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
| **Real-time Graph** | 实时流量拓扑图 |
| **Traffic Metrics** | 请求数、错误率、延迟 |
| **Time Range** | 15min, 30min, 1h, 3h, 6h |
| **Auto Refresh** | 每 30 秒自动刷新 |
| **Node Details** | 点击查看详细指标 |

---

## Node Types

| Node Type | Color | Description |
|-----------|-------|-------------|
| `gateway` | Blue | Gateway 实例 |
| `route` | Cyan | 路由定义 |
| `service` | Purple | 后端服务 |
| `client` | Orange | 客户端 IP |

---

## Edge Metrics

| Metric | Description |
|--------|-------------|
| `requestCount` | 该路径请求总数 |
| `avgLatency` | 平均响应延迟 |
| `errorRate` | 错误率百分比 |

### Edge Styling

- **宽度**: `min(8, max(1, requestCount / 50))`
- **颜色**:
  - 绿色: errorRate < 5%
  - 橙色: errorRate 5-10%
  - 红色: errorRate > 10%

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

1. **定期查看**：了解流量分布
2. **异常检测**：关注红色边路径
3. **延迟分析**：识别高延迟路径
4. **客户端分析**：了解主要客户端来源
5. **结合监控**：与监控数据对比分析

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - 监控数据
- [Request Tracing](request-tracing.md) - 请求追踪
- [Analytics](analytics.md) - 数据分析