# Kubernetes Integration

> Kubernetes 集成功能支持从 Admin UI 一键部署网关实例到 K8s 集群。

---

## Overview

从 Admin UI 管理 Kubernetes 部署：

```
┌─────────────────────────────────────────────┐
│        Admin UI (Kubernetes Page)            │
│                                              │
│   1. Add Cluster → Register K8s cluster      │
│   2. Create Instance → Configure spec        │
│   3. Deploy → Apply YAML to K8s              │
│   4. Monitor → View pod status               │
└─────────────────────────────────────────────┘
```

---

## Cluster Registration

```bash
POST /api/kubernetes/clusters
{
  "name": "Production Cluster",
  "apiServer": "https://k8s-api.example.com",
  "token": "xxx",
  "namespace": "gateway-prod"
}
```

| Parameter | Description |
|-----------|-------------|
| `name` | 集群名称 |
| `apiServer` | K8s API Server URL |
| `token` | Service Account Token |
| `namespace` | 默认部署命名空间 |

---

## Deployment Flow

```
┌─────────────────────────────────────────────┐
│         K8S DEPLOYMENT FLOW                  │
│                                              │
│   User Request (Create Instance)             │
│          │                                   │
│          ▼                                   │
│   ┌─────────────────┐                        │
│   │ Validate Params │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Create Nacos    │                        │
│   │ Namespace       │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Generate YAML   │                        │
│   │ from Template   │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Apply to K8s    │                        │
│   │ via Fabric8 SDK │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Watch Pod Status│                        │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

---

## Generated Deployment YAML

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-gateway
  namespace: gateway-prod
spec:
  replicas: 3
  selector:
    matchLabels:
      app: my-gateway
  template:
    metadata:
      labels:
        app: my-gateway
    spec:
      containers:
      - name: my-gateway
        image: my-gateway:latest
        ports:
        - containerPort: 80
        env:
        - name: NACOS_SERVER_ADDR
          value: "nacos:8848"
        - name: NACOS_NAMESPACE
          value: "gateway-prod-xxx"
        - name: GATEWAY_ADMIN_URL
          value: "http://admin:9090"
        - name: GATEWAY_ID
          value: "gateway-prod-1"
        resources:
          requests:
            cpu: "2"
            memory: "2Gi"
          limits:
            cpu: "4"
            memory: "4Gi"
```

---

## Environment Variables

| Variable | Description |
|----------|-------------|
| `NACOS_SERVER_ADDR` | Nacos 服务地址 |
| `NACOS_NAMESPACE` | 实例命名空间 |
| `GATEWAY_ADMIN_URL` | Admin 服务 URL |
| `GATEWAY_ID` | 实例标识 |
| `REDIS_HOST` | Redis 服务地址 |
| `REDIS_PORT` | Redis 端口 |

---

## Health Probes

### Liveness Probe

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 60
  periodSeconds: 15
  timeoutSeconds: 10
```

### Readiness Probe

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
```

---

## Pod Management

### List Pods

```bash
GET /api/kubernetes/instances/{instanceId}/pods
```

### Get Pod Logs

```bash
GET /api/kubernetes/pods/{podName}/logs
```

### Delete Pod

```bash
DELETE /api/kubernetes/pods/{podName}
```

---

## Metrics Integration

Pod 暴露 Prometheus 指标：

```
http://pod:8081/actuator/prometheus

gateway_requests_total
gateway_requests_duration_seconds
gateway_active_connections
jvm_memory_used_bytes
```

---

## Best Practices

1. **Service Account**：配置适当的 K8s 权限
2. **资源限制**：设置合理的 requests/limits
3. **健康检查**：确保 liveness/readiness 正常
4. **命名空间隔离**：不同环境使用不同 namespace
5. **监控集成**：连接 Prometheus 监控

---

## Related Features

- [Gateway Instance Management](instance-management.md) - 实例管理
- [Monitoring & Alerts](monitoring-alerts.md) - Prometheus 集成