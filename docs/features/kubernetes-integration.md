# Kubernetes Integration

> Kubernetes integration supports one-click deployment of gateway instances to K8s clusters from the Admin UI.

---

## Overview

Manage Kubernetes deployments from Admin UI:

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
| `name` | Cluster name |
| `apiServer` | K8s API Server URL |
| `token` | Service Account Token |
| `namespace` | Default deployment namespace |

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
| `NACOS_SERVER_ADDR` | Nacos service address |
| `NACOS_NAMESPACE` | Instance namespace |
| `GATEWAY_ADMIN_URL` | Admin service URL |
| `GATEWAY_ID` | Instance identifier |
| `REDIS_HOST` | Redis service address |
| `REDIS_PORT` | Redis port |

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

Pods expose Prometheus metrics:

```
http://pod:8081/actuator/prometheus

gateway_requests_total
gateway_requests_duration_seconds
gateway_active_connections
jvm_memory_used_bytes
```

---

## Best Practices

1. **Service Account**: Configure appropriate K8s permissions
2. **Resource Limits**: Set reasonable requests/limits
3. **Health Checks**: Ensure liveness/readiness probes work properly
4. **Namespace Isolation**: Use different namespaces for different environments
5. **Monitoring Integration**: Connect to Prometheus monitoring

---

## Related Features

- [Gateway Instance Management](instance-management.md) - Instance management
- [Monitoring & Alerts](monitoring-alerts.md) - Prometheus integration