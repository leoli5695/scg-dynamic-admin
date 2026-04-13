# Gateway Instance Management

> Gateway instance management supports multi-instance deployment, with each instance having its own independent Nacos namespace.

---

## Overview

Manage multiple Gateway instances from a single Admin:

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

Each instance sends a heartbeat every 10 seconds:

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

- **Running**: Heartbeat received within 30 seconds
- **Warning**: 1-2 consecutive missed heartbeats (30-60 seconds)
- **Error**: 3+ consecutive missed heartbeats (> 60 seconds)

---

## Namespace Isolation

Each instance has its own independent Nacos namespace:

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

1. **Environment Isolation**: Use different instances for different environments
2. **Appropriate Specifications**: Choose specifications based on traffic
3. **Heartbeat Monitoring**: Ensure heartbeats are sent properly
4. **Namespace Naming**: Use clear namespace naming conventions
5. **High Availability**: At least 3 replicas for production environments

---

## Related Features

- [Kubernetes Integration](kubernetes-integration.md) - K8s deployment
- [Monitoring & Alerts](monitoring-alerts.md) - Instance monitoring
- [Email Notifications](email-notifications.md) - Instance status alerts