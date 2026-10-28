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
POST /api/instances          # requires an ADMIN JWT (Authorization: Bearer ...)
{
  "instanceName": "Production Gateway",
  "clusterId": 1,
  "namespace": "gateway-prod",
  "specType": "large",
  "replicas": 3
}
```

### Resource Specifications

| Spec Type | CPU | Memory | Use Case |
|-----------|-----|--------|----------|
| `small` | 0.5 core | 512MB | Development |
| `medium` | 1 core | 1GB | Staging |
| `large` | 2 cores | 2GB | Production |
| `xlarge` | 4 cores | 4GB | High-traffic |
| `custom` | Custom | Custom | Special |

`InstanceSpec` only carries `type`, `cpuCores`, `memoryMB` and `description`, and
`GET /api/instances/specs` returns exactly those four keys per spec. **There is no per-spec replica
count** — the replica number is supplied independently by `replicas` in the create request
(`InstanceCreateRequest.replicas`, default **1**), and changed later via
`PUT /api/instances/{id}/scale` or `PUT /api/instances/{id}/replicas`. `specType` defaults to `medium`.

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

Each instance sends a heartbeat every 10 seconds (`gateway.heartbeat.interval-ms`, default `10000`):

```bash
# instanceId is a QUERY PARAMETER, not a path segment.
# Requires X-Internal-Token (this endpoint is in InternalApiEndpoints.TOKEN_PROTECTED,
# i.e. permitAll for JWT but guarded by the shared internal token).
curl -X POST "http://localhost:9090/api/instances/heartbeat?instanceId=tj9j963zq2x1" \
  -H "X-Internal-Token: $GATEWAY_INTERNAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "instanceId": "tj9j963zq2x1",
    "timestamp": 1705309800000,
    "metrics": {
      "maxMemory": 4294967296,
      "totalMemory": 1073741824,
      "freeMemory": 536870912,
      "usedMemory": 536870912,
      "availableProcessors": 4
    },
    "accessUrl": "http://10.0.0.5:8080",
    "serverPort": 8080,
    "managementPort": 9091,
    "traceConfig": {}
  }'
```

The body is optional (`@RequestBody(required = false)`) and maps to
`InstanceHealthController.HeartbeatRequest` — its top-level fields are exactly
`instanceId`, `timestamp`, `metrics` (a free-form `Map`), `accessUrl`, `serverPort`,
`managementPort` and `traceConfig`. The gateway's own `HeartbeatReporter` posts to
`adminUrl + "/api/instances/heartbeat?instanceId=" + instanceId`.

There are two companion endpoints, also token-protected:
`GET /api/instances/heartbeat?instanceId=...` (convenience form) and
`POST /api/instances/started?instanceId=...`.

> **The reported `metrics` are currently not persisted.**
> `GatewayInstanceService.handleHeartbeat(...)` accepts a `metrics` map but never reads it. All a
> heartbeat does is: set `lastHeartbeatTime = now()`, reset `missedHeartbeats = 0`, validate and store
> `accessUrl` into `reportedAccessUrl` (rejected values are logged and the previous value kept — the
> URL is checked by `UrlSecurityValidator.validateReportedInstanceUrl` because the admin later dials it
> with the shared internal token), store `serverPort` / `managementPort`, record `traceConfig`, and
> transition the status from `STARTING` or `ERROR` to `RUNNING`. The `cpu_usage_percent` /
> `memory_usage_mb` / `requests_per_second` / `active_connections` columns on `GatewayInstanceEntity`
> exist but are **never written** by the heartbeat path (no `setCpuUsagePercent` / `setMemoryUsageMb` /
> `setRequestsPerSecond` / `setActiveConnections` call on that entity anywhere in gateway-admin).
> Live resource numbers come from `GET /api/instances/{id}/resource-metrics`, which delegates to
> `ClusterHealthService.getResourceMetrics(clusterId, namespace, instanceId)` (Kubernetes node/pod
> resources), and from `GET /api/instances/{id}/health-score`.

### Status Detection

- **Running**: Heartbeat received within 30 seconds (`gateway.health.heartbeat-timeout`, default 30)
- **Warning**: 1-2 consecutive missed heartbeats (30-60 seconds) — *an internal `missedHeartbeats`
  counter only; `InstanceStatus` has no `WARNING` value, so the persisted status stays `RUNNING`*
- **Error**: 3+ consecutive missed heartbeats (`gateway.health.missed-heartbeat-threshold`, default 3)

The checker itself runs every 10 seconds (`gateway.health.check-interval`, default 10000); instances
still in `STARTING` are allowed `gateway.health.startup-timeout` (default 180s) before being failed.

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

`{id}` is the numeric database primary key (`Long`); `{instanceId}` is the string instance ID such as
`tj9j963zq2x1`.

Instance CRUD and lifecycle (`GatewayInstanceController`, `@RequestMapping("/api/instances")`):

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/instances` | List all instances |
| `GET` | `/api/instances/{id}` | Get instance details |
| `GET` | `/api/instances/by-instance-id/{instanceId}` | Get instance details by string instance ID |
| `GET` | `/api/instances/config` | Get instance-related console configuration |
| `GET` | `/api/instances/specs` | List available spec types |
| `POST` | `/api/instances` | Create instance |
| `DELETE` | `/api/instances/{id}` | Delete instance |
| `POST` | `/api/instances/{id}/start` | Start instance |
| `POST` | `/api/instances/{id}/stop` | Stop instance |
| `POST` | `/api/instances/{id}/refresh` | Refresh instance state from the cluster |
| `PUT` | `/api/instances/{id}/scale` | Scale replicas and/or spec (unified) |
| `PUT` | `/api/instances/{id}/replicas` | Update replicas only (no restart) |
| `PUT` | `/api/instances/{id}/spec` | Update CPU/memory spec (requires restart) |
| `PUT` | `/api/instances/{id}/image` | Update container image (rolling update) |
| `GET` | `/api/instances/{id}/pods` | List pods of the instance |
| `GET` | `/api/instances/{id}/events` | Kubernetes events for the instance |
| `GET` | `/api/instances/{id}/health-score` | Instance health score |
| `GET` | `/api/instances/{id}/resource-metrics` | Node/pod resource metrics |

Heartbeat and lifecycle reporting (`InstanceHealthController`, same base path):

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/instances/heartbeat?instanceId={instanceId}` | Receive heartbeat (body optional) |
| `GET` | `/api/instances/heartbeat?instanceId={instanceId}` | Convenience heartbeat without a body |
| `POST` | `/api/instances/started?instanceId={instanceId}` | Report gateway startup |

> **There is no `PUT /api/instances/{id}` and no `POST /api/instances/{id}/restart`.**
> The only `PUT` mappings on the controller are `/{id}/scale`, `/{id}/replicas`, `/{id}/spec` and
> `/{id}/image`; `restart` appears in the source only inside two comments ("direct scale, no restart",
> "requires restart"). To recycle an instance use `POST /{id}/stop` followed by `POST /{id}/start`, or
> `PUT /{id}/image` / `PUT /{id}/spec`, which trigger a rolling restart.
>
> **Authentication**: all `POST`/`PUT`/`PATCH`/`DELETE` under `/api/**` require the `ADMIN` authority,
> and every other `/api/**` request requires an authenticated JWT (`SecurityConfig`). The two
> `/api/instances/heartbeat` and `/api/instances/started` endpoints are the exception: they are
> `permitAll` for JWT but guarded by `X-Internal-Token` (`InternalApiEndpoints.TOKEN_PROTECTED`).

---

## Operations

### Scale Replicas

```bash
# PUT, not POST - a POST to this path returns 405 Method Not Allowed.
curl -X PUT http://localhost:9090/api/instances/1/scale \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"replicas": 5, "specType": "large", "cpuCores": 2.0, "memoryMB": 2048, "reason": "traffic spike"}'
```

`ScaleInstanceRequest` accepts `replicas`, `specType`, `cpuCores`, `memoryMB` and `reason`; horizontal
(replicas) and vertical (spec) scaling can be combined in one call. To change only the replica count
without a restart:

```bash
curl -X PUT http://localhost:9090/api/instances/1/replicas \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"replicas": 5}'
```

### Restart Instance

There is no restart endpoint. Stop and start explicitly instead:

```bash
curl -X POST http://localhost:9090/api/instances/1/stop \
  -H "Authorization: Bearer $ADMIN_JWT"

curl -X POST http://localhost:9090/api/instances/1/start \
  -H "Authorization: Bearer $ADMIN_JWT"
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