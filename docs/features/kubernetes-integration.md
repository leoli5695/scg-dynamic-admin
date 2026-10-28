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

Clusters are registered by **importing a kubeconfig**, not by supplying an API-server URL plus a
service-account token. `KubernetesController.importCluster` (`POST /api/kubernetes/clusters`) reads
exactly three keys from the request body and rejects the call with 400 `"clusterName and kubeconfig
are required"` if either of the first two is missing. It also enforces a license feature check
(`LicenseInfo.Feature.K8S_INTEGRATION`) before doing anything else.

```bash
POST /api/kubernetes/clusters        # requires an ADMIN JWT
{
  "clusterName": "Production Cluster",
  "kubeconfig": "apiVersion: v1\nkind: Config\nclusters:\n  - ...",
  "description": "Prod cluster in cn-chengdu"
}
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `clusterName` | Yes | Cluster name (unique) |
| `kubeconfig` | Yes | Full kubeconfig YAML text; stored in a `TEXT` column |
| `description` | No | Free-text description |

`serverUrl` and `contextName` are **derived from the kubeconfig**, not user-supplied
(`KubernetesService.importKubeconfig` → `kubeConfigService.extractServerUrl(...)` /
`extractCurrentContext(...)`). There is no `token` field and no default-`namespace` field on the
cluster.

The full `KubernetesCluster` entity columns are: `cluster_name`, `server_url`, `kubeconfig`,
`context_name`, `cluster_version`, `node_count`, `pod_count`, `namespace_count`, `total_cpu_cores`,
`total_memory_gb`, `connection_status` (default `UNKNOWN`), `last_checked_at`, `description`,
`enabled` (default `true`), `created_at`, `updated_at`.

### All Cluster / Workload Endpoints

`KubernetesController` (`@RequestMapping("/api/kubernetes")`); every one requires an authenticated
JWT, and all write operations require the `ADMIN` authority:

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/kubernetes/test-connection` | Validate a kubeconfig / connection before importing |
| `POST` | `/api/kubernetes/clusters` | Import a cluster from kubeconfig |
| `GET` | `/api/kubernetes/clusters` | List clusters |
| `GET` | `/api/kubernetes/clusters/{id}` | Get one cluster |
| `PUT` | `/api/kubernetes/clusters/{id}` | Update cluster |
| `DELETE` | `/api/kubernetes/clusters/{id}` | Delete cluster |
| `POST` | `/api/kubernetes/clusters/{id}/refresh` | Re-probe cluster version/node/pod counts |
| `GET` | `/api/kubernetes/clusters/{id}/nodes` | List nodes |
| `GET` | `/api/kubernetes/clusters/{id}/namespaces` | List namespaces |
| `GET` | `/api/kubernetes/clusters/{id}/deployments` | List deployments |
| `GET` | `/api/kubernetes/clusters/{id}/pods` | List pods |
| `GET` | `/api/kubernetes/clusters/{id}/pods/{namespace}/{name}` | Pod detail |
| `GET` | `/api/kubernetes/clusters/{id}/pods/{namespace}/{name}/yaml` | Pod YAML |
| `GET` | `/api/kubernetes/clusters/{id}/pods/{namespace}/{name}/logs` | Pod logs |
| `POST` | `/api/kubernetes/clusters/{id}/deploy` | Deploy a workload |
| `DELETE` | `/api/kubernetes/clusters/{id}/deployments/{namespace}/{name}` | Delete a deployment |
| `PUT` | `/api/kubernetes/clusters/{id}/deployments/{namespace}/{name}/scale` | Scale a deployment |
| `GET` | `/api/kubernetes/clusters/{id}/deployments/{namespace}/{name}/status` | Deployment status |
| `GET` | `/api/kubernetes/clusters/{id}/images` | List available images |

> Note there are only **two** `DELETE` mappings — `/clusters/{id}` and
> `/clusters/{id}/deployments/{namespace}/{name}`. **There is no delete-a-single-pod capability.**

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
│   │ via client-java │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Watch Pod Status│                        │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

> **Kubernetes client library**: the official **`io.kubernetes:client-java`** (version 25.0.0,
> `gateway-admin/pom.xml`), not Fabric8. `K8sManifestBuilder` imports
> `io.kubernetes.client.custom.Quantity` and `io.kubernetes.client.openapi.models.*`, and a
> repo-wide search for `fabric8` in any `pom.xml` returns nothing. Manifests are built as typed
> `V1Deployment` / `V1Container` / `V1Probe` objects rather than from a YAML template file.

---

## Generated Deployment YAML

Built by `K8sManifestBuilder.createDeploymentManifest(instance, pullPolicy, serverPort, managementPort)`
as typed `io.kubernetes.client` objects. The example below uses a real instance ID shape
(`gateway-kd2onojjfcu9`); `<serverPort>` / `<managementPort>` come from
`InstanceCreateRequest` and default to **80** and **9091** respectively.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  # deploymentName = "gateway-" + instanceId (GatewayInstanceService), NOT a fixed "my-gateway"
  name: gateway-kd2onojjfcu9
  namespace: gateway-prod
  labels:
    app: gateway
    gateway-instance-id: kd2onojjfcu9
spec:
  replicas: 3                       # instance.getReplicas()
  selector:
    matchLabels:
      app: gateway-kd2onojjfcu9     # = deploymentName
  template:
    metadata:
      labels:
        app: gateway-kd2onojjfcu9
        gateway-instance-id: kd2onojjfcu9
    spec:
      containers:
      - name: gateway
        image: my-gateway:1.0.0     # instance.getImage()
        imagePullPolicy: Never      # request value, defaults to "Never"
        ports:                      # TWO ports are exposed, not one
        - containerPort: 80
          name: http
        - containerPort: 9091
          name: management
        env:
        - name: GATEWAY_ID
          value: "kd2onojjfcu9"
        - name: GATEWAY_INSTANCE_ID
          value: "kd2onojjfcu9"
        - name: NACOS_SERVER_ADDR
          value: "nacos.test.svc.cluster.local:8848"         # resolved, see note below
        - name: NACOS_NAMESPACE
          value: "gateway-kd2onojjfcu9"                      # = "gateway-" + instanceId
        - name: NACOS_GROUP
          value: "DEFAULT_GROUP"                             # ${nacos.group}
        - name: GATEWAY_ADMIN_URL
          value: "http://localhost:9090"                     # ${gateway.admin.k8sUrl} else ${gateway.admin.url}
        - name: REDIS_HOST            # emitted whenever the resolved address is non-empty
          value: "redis"
        - name: REDIS_PORT
          value: "6379"
        - name: OTEL_EXPORTER_OTLP_ENDPOINT   # emitted whenever a Jaeger address resolves
          value: "http://jaeger.test.svc.cluster.local:4318"
        - name: OTEL_TRACING_ENABLED
          value: "true"
        - name: PROMETHEUS_PUSH_URL   # emitted whenever a Prometheus address resolves
          value: "http://prometheus.test.svc.cluster.local:9090"
        - name: SERVER_PORT
          value: "80"
        - name: MANAGEMENT_SERVER_PORT
          value: "9091"
        resources:                    # only emitted when BOTH cpuCores and memoryMB are set
          requests:                   # = half of the limits
            cpu: "1.0"
            memory: "1024Mi"
          limits:                     # = the instance's cpuCores / memoryMB
            cpu: "2.0"
            memory: "2048Mi"
        livenessProbe:  { ... }       # see Health Probes below
        readinessProbe: { ... }
```

> **`NACOS_SERVER_ADDR` and `GATEWAY_ADMIN_URL` are resolved, not hardcoded.** `getNacosK8sAddress`
> (and the Redis/Jaeger/Prometheus equivalents) pick, in order: **1.** the instance's own custom
> address (`instance.getNacosServerAddr()` etc.), **2.** the global override
> (`nacos.k8s-server-addr`, `redis.k8s-server-addr`, `gateway.jaeger.k8s-server-addr`,
> `gateway.prometheus.k8s-server-addr`), **3.** an address auto-built as
> `<service>.<namespace>.svc.cluster.local:<port>` from
> `{nacos,redis}.k8s-service-name` / `gateway.{jaeger,prometheus}.k8s-service-name`,
> `*.k8s-namespace` (default `test`) and `*.k8s-port` (defaults 8848 / 6379 / 4318 / 9090).
> `GATEWAY_ADMIN_URL` prefers `gateway.admin.k8sUrl` and falls back to `gateway.admin.url`.
>
> **Resources are not fixed at 2/4 cores.** `limits` = the instance's `cpuCores` / `memoryMB` (memory
> is emitted in `Mi`), `requests` = exactly half of each. With `specType: large` that is limits
> 2 cores / 2048Mi and requests 1 core / 1024Mi; `custom` specs use whatever `cpuCores`/`memoryMB`
> were supplied, and when either is null the whole `resources` block is omitted.
>
> The static manifest at `k8s/my-gateway.yaml` is a **different, hand-written** deployment named
> `my-gateway` (image `my-gateway:1.0.0`) with containerPorts **8080** (`http`) and **8443**
> (`https`), a **ClusterIP** Service (no NodePort) and **no** management port — its comments explain
> that a declared 8081 "management" port was removed because nothing listened on it, leaving pods
> permanently not-ready. Do not confuse it with the per-instance manifests the console generates.

---

## Environment Variables

Injected by `K8sManifestBuilder` (`V1EnvVar` list):

| Variable | Always set | Description |
|----------|------------|-------------|
| `GATEWAY_ID` | Yes | Instance identifier (`instance.getInstanceId()`) |
| `GATEWAY_INSTANCE_ID` | Yes | Same value as `GATEWAY_ID` |
| `NACOS_SERVER_ADDR` | Yes | Nacos service address (resolved, see note above) |
| `NACOS_NAMESPACE` | Yes | Instance namespace (`gateway-<instanceId>`) |
| `NACOS_GROUP` | Yes | Nacos group (`nacos.group`, default `DEFAULT_GROUP`) |
| `GATEWAY_ADMIN_URL` | Yes | Admin service URL (`gateway.admin.k8sUrl`, else `gateway.admin.url`) |
| `SERVER_PORT` | Yes | Application port |
| `MANAGEMENT_SERVER_PORT` | Yes | Actuator/management port |
| `REDIS_HOST` | Conditional | Redis host (split from the resolved `host:port`) |
| `REDIS_PORT` | Conditional | Redis port (defaults to `6379` when the address has no `:`) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Conditional | `http://<resolved Jaeger address>` |
| `OTEL_TRACING_ENABLED` | Conditional | Set to `true` alongside the OTLP endpoint |
| `PROMETHEUS_PUSH_URL` | Conditional | `http://<resolved Prometheus address>` |

"Conditional" means the variable is added only when the corresponding resolved address is non-empty.
Because each resolver ends in a `<service>.<namespace>.svc.cluster.local:<port>` fallback, they are in
practice always emitted unless the instance/global overrides are explicitly blanked.

---

## Health Probes

Both probes target the **management port** (`new IntOrString(managementPort)`), which defaults to
**9091** (`InstanceCreateRequest.managementPort`) — not 8081. Note that the gateway's bundled
`application.yml` deliberately does *not* set `management.server.port` (actuator normally shares the
main port); the generated manifest re-enables a separate management port by injecting the
`MANAGEMENT_SERVER_PORT` env var shown above.

### Liveness Probe

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 9091                  # managementPort
  initialDelaySeconds: 60
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
```

### Readiness Probe

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 9091                  # managementPort
  initialDelaySeconds: 30
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3
```

---

## Pod Management

Pod endpoints are scoped to a **cluster id plus namespace plus pod name** — there are no
`/api/kubernetes/instances/...` or bare `/api/kubernetes/pods/...` paths.

### List Pods

```bash
GET /api/kubernetes/clusters/{id}/pods            # KubernetesController:240
# or, for a console-managed gateway instance:
GET /api/instances/{id}/pods                      # GatewayInstanceController:160
```

### Get Pod Detail / YAML / Logs

```bash
GET /api/kubernetes/clusters/{id}/pods/{namespace}/{name}          # :256
GET /api/kubernetes/clusters/{id}/pods/{namespace}/{name}/yaml     # :273
GET /api/kubernetes/clusters/{id}/pods/{namespace}/{name}/logs     # :291  (namespace segment is required)
```

### Delete Pod

**Not supported.** `KubernetesController` has only two `DELETE` mappings — `DELETE /clusters/{id}`
(:152) and `DELETE /clusters/{id}/deployments/{namespace}/{name}` (:347). There is no
`DELETE /api/kubernetes/pods/{podName}` and no other way to delete an individual pod through the API.
To recycle a pod, scale or roll the owning deployment
(`PUT /clusters/{id}/deployments/{namespace}/{name}/scale`, or `PUT /api/instances/{id}/image`).

---

## Metrics Integration

Pods expose Prometheus metrics on the actuator port (the `prometheus` endpoint is in
`management.endpoints.web.exposure.include` in `my-gateway/src/main/resources/application.yml`):

```
http://<pod>:<managementPort>/actuator/prometheus     # 9091 for console-created instances;
                                                      # the static k8s/my-gateway.yaml deployment
                                                      # serves actuator on its main port instead
```

Metric names the admin console actually queries (`PrometheusService`, `AiMetricsCollector`), all
filtered by `application="my-gateway"`:

```
http_server_requests_seconds_count
http_server_requests_seconds_sum
jvm_memory_used_bytes
jvm_memory_max_bytes
jvm_gc_pause_seconds_count
jvm_gc_pause_seconds_sum
jvm_gc_memory_allocated_bytes_total
jvm_gc_memory_promoted_bytes_total
jvm_threads_live_threads
jvm_threads_daemon_threads
jvm_threads_peak_threads
process_cpu_usage
process_uptime_seconds
system_cpu_usage
system_cpu_count
system_load_average
disk_free_bytes
disk_total_bytes
spring_cloud_gateway_routes_count
```

> `gateway_requests_total`, `gateway_requests_duration_seconds` and `gateway_active_connections`
> **do not exist** — nothing registers them. The only custom `gateway.*` meters in my-gateway are the
> counters/gauges under `gateway.accesslog.*` (`queued`, `dropped`) and `gateway.trace.export.*`
> (`sent`, `failed`, `dropped`, `queued.reports`, `queued.bytes`).

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