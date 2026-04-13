# Gateway Instance Management

> 网关实例管理功能支持多实例部署，每个实例有独立的 Nacos 命名空间。

---

## Overview

从单一 Admin 管理多个 Gateway 实例：

```
┌─────────────────────────────────────────────┐
│        gateway-admin (Control Plane)         │
│                                              │
│   ┌─────────────┐ ┌─────────────┐            │
│   │  Instance 1 │ │  Instance 2 │            │
│   │  (dev)      │ │  (staging)  │            │
│   └──────┬──────┘ └──────┬──────┘            │
│          │                │                  │
│          ▼                ▼                  │
│   ┌─────────────┐ ┌─────────────┐            │
│   │ Nacos NS:   │ │ Nacos NS:   │            │
│   │ gateway-dev │ │ gateway-stg │            │
│   └─────────────┘ └─────────────┘            │
└─────────────────────────────────────────────┘
```

---

## Instance Creation

```bash
POST /api/instances
{
  "instanceName": "Production Gateway",
  "clusterId": 1,
  "namespace": "gateway-prod",
  "specType": "large",
  "replicas": 3
}
```

### Resource Specifications

| Spec Type | CPU | Memory | Replicas | Use Case |
|-----------|-----|--------|----------|----------|
| `small` | 0.5 core | 512MB | 1 | Development |
| `medium` | 1 core | 1GB | 2 | Staging |
| `large` | 2 cores | 2GB | 3 | Production |
| `xlarge` | 4 cores | 4GB | 5 | High-traffic |
| `custom` | Custom | Custom | Custom | Special |

---

## Instance Status

| Status | Code | Description |
|--------|------|-------------|
| `STARTING` | 0 | Pod is starting |
| `RUNNING` | 1 | Healthy, receiving heartbeats |
| `ERROR` | 2 | Missed heartbeats or crashed |
| `STOPPING` | 3 | Pod is shutting down |
| `STOPPED` | 4 | Pod is stopped |

---

## Heartbeat Monitoring

每个实例每 10 秒发送心跳：

```bash
POST /api/instances/{instanceId}/heartbeat
{
  "cpuUsagePercent": 45.5,
  "memoryUsageMb": 512,
  "requestsPerSecond": 1234.5,
  "activeConnections": 100
}
```

### Status Detection

- **Running**: 心跳在 30 秒内收到
- **Warning**: 连续缺失 1-2 次心跳 (30-60 秒)
- **Error**: 连续缺失 3+ 次心跳 (> 60 秒)

---

## Namespace Isolation

每个实例有独立的 Nacos 命名空间：

```
Instance: gateway-prod
├── Nacos Namespace: gateway-prod-xxx
│   ├── config.gateway.route-{id}
│   ├── config.gateway.service-{id}
│   ├── config.gateway.strategy-{id}
│   └── config.gateway.metadata.*-index
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/instances` | List all instances |
| `GET` | `/api/instances/{id}` | Get instance details |
| `POST` | `/api/instances` | Create instance |
| `PUT` | `/api/instances/{id}` | Update instance |
| `DELETE` | `/api/instances/{id}` | Delete instance |
| `POST` | `/api/instances/{id}/scale` | Scale replicas |
| `POST` | `/api/instances/{id}/restart` | Restart instance |
| `POST` | `/api/instances/{id}/heartbeat` | Receive heartbeat |

---

## Operations

### Scale Replicas

```bash
curl -X POST http://localhost:9090/api/instances/1/scale \
  -H "Content-Type: application/json" \
  -d '{"replicas": 5}'
```

### Restart Instance

```bash
curl -X POST http://localhost:9090/api/instances/1/restart
```

---

## Best Practices

1. **环境隔离**：不同环境使用不同实例
2. **合理规格**：根据流量选择规格
3. **心跳监控**：确保心跳正常发送
4. **命名空间**：使用清晰的命名空间命名
5. **高可用**：生产环境至少 3 个副本

---

## Related Features

- [Kubernetes Integration](kubernetes-integration.md) - K8s 部署
- [Monitoring & Alerts](monitoring-alerts.md) - 实例监控
- [Email Notifications](email-notifications.md) - 实例状态告警