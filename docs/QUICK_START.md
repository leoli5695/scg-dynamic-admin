# Quick Start Guide

> Get your API Gateway up and running in 15 minutes.

---

## Prerequisites

| Tool | Version | Required |
|------|---------|----------|
| Java | 21+ | Yes |
| Maven | 3.8+ | Yes |
| Node.js | 22.18+ | Yes (for UI) - `npm run test:routes` imports `.ts` sources, which needs Node's built-in type stripping (default-on from 22.18) |
| MySQL | 8.0+ | **Yes** - `gateway-admin/pom.xml` declares `com.h2database:h2` with `<scope>test</scope>` only, so it is not on the runtime classpath and there is no embedded fallback |
| Nacos | 2.3.0+ | Yes |
| Redis | 6.0+ | Optional (for distributed rate limiting) |

---

## Step 1: Start Infrastructure

### Option A: Docker (Recommended)

```bash
# Start Nacos
docker run -d --name nacos \
  -p 8848:8848 \
  -e MODE=standalone \
  nacos/nacos-server:v2.3.0

# Start Redis (optional)
docker run -d --name redis \
  -p 6379:6379 \
  redis:7

# Start MySQL (optional)
docker run -d --name mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  mysql:8
```

### Option B: Local Installation

```bash
# Nacos (download from https://nacos.io)
cd nacos/bin
./startup.sh -m standalone  # Linux/Mac
startup.cmd -m standalone    # Windows

# Redis
redis-server

# MySQL
mysql.server start
```

---

## Step 2: Nacos Configuration (nothing to create by hand)

**Do not create `gateway-routes.json`, `gateway-services.json` or `gateway-strategies.json`.** No
code in this repository reads or writes those data-ids. The only `*.json` data-id that appears
anywhere in configuration is `gateway-plugins.json`
(`gateway-admin/src/main/resources/application.yml:234`), and even that key is not bound by any
`@ConfigurationProperties` or `@Value` in the code. Creating the three above changes nothing, and a
gateway set up that way stays at `Loaded 0 route(s)`.

The real layout is **incremental, one data-id per object** (`GatewayConfigConstants`), all in group
`DEFAULT_GROUP`:

| Object | Index data-id (JSON array of ids) | Per-object data-id |
|--------|-----------------------------------|--------------------|
| Routes | `config.gateway.metadata.routes-index` | `config.gateway.route-{routeId}` |
| Services | `config.gateway.metadata.services-index` | `config.gateway.service-{serviceId}` |
| Strategies | `config.gateway.metadata.strategies-index` | `config.gateway.strategy-{strategyId}` |
| Auth policies | `config.gateway.metadata.auth-policies-index` | `config.gateway.auth-policy-{policyId}` (+ `config.gateway.auth-routes-{policyId}`) |
| SSL certificates | `config.gateway.metadata.ssl-certificates-index` | `ssl-certificate-{domain}` |

gateway-admin writes all of these for you. Creating a route in the UI or via `POST /api/routes`
stores it in the database and publishes `config.gateway.route-{routeId}` into that instance's Nacos
namespace; `RouteReconcileTask` then rebuilds the index (enabled routes only) on the
`gateway.config-sync.interval-ms` cycle (default 60000 ms). The namespace itself is created
automatically too (`NacosConfigCenterService.createNamespace`, and
`InstanceCreateRequest.createNamespace` defaults to `true`).

So this step only requires that Nacos is reachable from gateway-admin and my-gateway. Open the
console at http://localhost:8848/nacos (nacos/nacos) if you want to watch the data-ids appear as
you create routes.

---

## Step 3: Configure Application

### gateway-admin/src/main/resources/application.yml

The committed file already reads MySQL settings from the environment; you normally only need to
export the variables rather than edit it. There is **no** H2 profile - an earlier revision of this
document described one. `gateway-admin/pom.xml` does declare `com.h2database:h2`, but with
`<scope>test</scope>`, so it is absent from the runtime classpath and
`driver-class-name: org.h2.Driver` fails at startup with "Cannot load driver class".

```yaml
server:
  port: 9090

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/gateway_admin?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USERNAME}   # no default - startup fails if unset
    password: ${MYSQL_PASSWORD}   # no default - startup fails if unset
  jpa:
    hibernate:
      ddl-auto: update            # application-prod.yml overrides this to `validate`
```

Equivalent environment variables:

```bash
export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=3306
export MYSQL_USERNAME=gateway_user
export MYSQL_PASSWORD=your_password
```

> The database name is hardcoded as `gateway_admin` in the JDBC URL - there is no `DB_NAME`
> variable. Create it first: `CREATE DATABASE gateway_admin CHARACTER SET utf8mb4;`

gateway-admin reads the Nacos address from **two different keys**, and they are not
interchangeable. Both are in the committed `application.yml` and both default to
`${NACOS_SERVER_ADDR:localhost:30848}`:

```yaml
# 1. spring.cloud.nacos.discovery.* - bound by NacosConfigCenterService
#    (@ConfigurationProperties(prefix = "spring.cloud.nacos.discovery")). This is the address the
#    config center actually publishes routes / services / strategies to.
spring:
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_SERVER_ADDR:localhost:30848}
        namespace: ${NACOS_NAMESPACE:public}
        group: DEFAULT_GROUP

# 2. Top-level nacos.server-addr - injected with @Value("${nacos.server-addr:localhost:8848}") in
#    GatewayInstanceService, GatewayInstanceController and InstanceConfigCleanupHelper.
nacos:
  server-addr: ${NACOS_SERVER_ADDR:localhost:30848}
```

> The committed file also carries a `gateway.nacos:` block. Only `gateway.nacos.orphan-cleanup.*`
> under it is actually read (via `@Value` in `NamespaceReconcileTask`); `gateway.nacos.server-addr`
> and `gateway.nacos.data-ids` are bound by nothing.

### my-gateway/src/main/resources/application.yml

`NacosCenterAutoConfiguration` is annotated
`@ConfigurationProperties(prefix = "spring.cloud.nacos")` and binds only two fields, `serverAddr`
and `namespace` - so the config-center address must sit **directly** under `spring.cloud.nacos`.
The `spring.cloud.nacos.config.server-addr` / `spring.cloud.nacos.discovery.server-addr` keys this
document used to show are not read by that class (the committed `application.yml` even carries the
comment "must be at this level, not under discovery").

```yaml
server:
  port: ${SERVER_PORT:80}

spring:
  cloud:
    nacos:
      serverAddr: ${NACOS_SERVER_ADDR:localhost:30848}   # bound by NacosCenterAutoConfiguration
      namespace: ${NACOS_NAMESPACE:}                     # empty = public namespace

gateway:
  center:
    type: ${GATEWAY_CENTER_TYPE:nacos}
```

---

## Step 4: Start Services

### Start Admin Service

```bash
cd gateway-admin
mvn spring-boot:run
```

Verify: http://localhost:9090/actuator/health - that is one of the few endpoints in
`InternalApiEndpoints.PERMIT_ALL_NO_TOKEN`. `http://localhost:9090/api/routes` needs a JWT
(`SecurityConfig` puts every `/api/**` request behind `anyRequest().authenticated()`), so opening it
in a browser without a token returns 401/403 rather than the route list.

### Start Gateway

```bash
cd my-gateway
mvn spring-boot:run
```

Verify: http://localhost:80/actuator/health

### Start Demo Service

```bash
cd demo-service
mvn spring-boot:run
```

Verify: http://localhost:9001/hello (demo-service listens on 9001 - see
`demo-service/src/main/resources/application.yml`)

---

## Step 5: Start Frontend

```bash
cd gateway-ui
npm install
npm run dev
```

Open: http://localhost:3000

**Login:**

Use the `ADMIN_USERNAME` / `ADMIN_PASSWORD` environment variables you set before starting the
backend. There is no built-in `admin` / `admin123` account - `AuthService` creates the initial
administrator only when both variables are present, requires at least 12 characters for the
password, and there is no self-service registration. If you started the backend without them,
check the log for a warning about skipping initial-admin creation; the only recovery is to set
them and restart (or insert the user directly, with a BCrypt hash).

---

## Step 6: Create Your First Route

### Via UI

1. Navigate to **Routes** page
2. Click **Create Route**
3. Fill in:
   - Route Name: `My First Route`
   - Path Pattern: `/api/my-service/**`
   - Target URI: `lb://my-service`
4. Click **Create**

### Via API

Every `/api/**` call needs a JWT first: `POST /api/auth/login` is the only public entry point, and
under `SecurityConfig` a `POST /api/**` additionally requires the `ADMIN` authority carried in the
token's `roles` claim. The body is a `RouteDefinition`, and `instanceId` is an optional query
parameter used for per-instance config isolation.

```bash
# 1. Log in and keep the token (response is wrapped in ApiResponse: {code,message,data})
TOKEN=$(curl -s -X POST http://localhost:9090/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"$ADMIN_USERNAME\", \"password\": \"$ADMIN_PASSWORD\"}" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

# 2. Create the route with the token
curl -X POST "http://localhost:9090/api/routes?instanceId=$INSTANCE_ID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "routeName": "My First Route",
    "uri": "lb://my-service",
    "predicates": [
      {"name": "Path", "args": {"pattern": "/api/my-service/**"}}
    ],
    "enabled": true
  }'
```

---

## Step 7: Test Your Gateway

```bash
# Test the route
curl http://localhost:80/api/my-service/hello

# Check route list (needs the JWT from Step 6)
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/api/routes

# Check gateway health
curl http://localhost:80/actuator/health
```

---

## Common Issues

### Gateway returns 404

**Cause:** Route not loaded or predicate not matched

**Solution:**
1. Check if route exists: `GET /api/routes`
2. Verify predicate pattern matches your request path
3. Check gateway logs for errors

### Service returns 503

**Cause:** No available instances for the service

**Solution:**
1. For `lb://` - Ensure service is registered in Nacos
2. For `static://` - Create the service and its nodes in the UI (or `POST /api/services`); admin
   publishes them as `config.gateway.service-{serviceId}`, not as a `gateway-services.json` file
3. Check instance health status in UI

### Configuration not syncing

**Cause:** Nacos listener not working

**Solution:**
1. Check Nacos connection: `http://localhost:8848/nacos`
2. Verify config exists in Nacos
3. Check gateway logs for Nacos errors

---

## Next Steps

- [Configure Multi-Service Routing](features/multi-service-routing.md)
- [Set up Authentication](features/authentication.md)
- [Enable SSL Termination](features/ssl-termination.md)
- [Configure Monitoring & Alerts](features/monitoring-alerts.md)
- [Deploy Gateway Instance to Kubernetes](features/kubernetes-integration.md)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      YOUR BROWSER                            │
│                   http://localhost:3000                      │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                   gateway-admin (:9090)                      │
│                                                              │
│   React UI ──▶ REST API ──▶ MySQL ──▶ Nacos (Config Push)   │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          │ Config Push
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    my-gateway (:80)                          │
│                                                              │
│   Request ──▶ Filter Chain ──▶ Backend Services             │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                   demo-service (:9001)                       │
│                                                              │
│   /hello, /headers, /params, /delay                          │
└─────────────────────────────────────────────────────────────┘
```

---

## Development Tips

### Hot Reload

Configuration changes in Nacos propagate without a restart through listeners and periodic reconciliation. The default reconciliation interval is 60 seconds, configured by `gateway.config-sync.interval-ms` or `GATEWAY_CONFIG_SYNC_INTERVAL_MS`. When listeners do not deliver changes, propagation takes up to one polling interval plus fetch and apply time. Config-center outages delay updates until connectivity recovers.

### Debug Mode

Enable debug logging:

```yaml
logging:
  level:
    com.leoli.gateway: DEBUG
```

### Inspecting the database

gateway-admin uses MySQL only. Connect with any client:

```bash
mysql -h 127.0.0.1 -P 3306 -u "$MYSQL_USERNAME" -p gateway_admin
```

The schema is created by Hibernate (`ddl-auto: update` by default, `validate` under the `prod`
profile). There is no Flyway or Liquibase in this project yet, so `validate` against an empty
database will fail - either let Hibernate create the schema first or restore a dump.

Two ways this bites in a real deployment:

- **The `prod` profile does not have the last word.** `SPRING_JPA_HIBERNATE_DDL_AUTO` in the
  container environment outranks `application-prod.yml`, so a deployment can be running `update`
  while the file in the jar says `validate`. Read the running container's env before reasoning
  about schema behaviour.
- **`update` is not a substitute for the scripts in `src/main/resources/sql/`.** It adds missing
  tables and columns, but it never widens an existing column's type and never applies a `DEFAULT`
  that the entity does not declare via `columnDefinition`. Anything the SQL scripts do beyond
  "add a nullable column" still has to be run by hand.

> An earlier revision of this document described an H2 console at
> `http://localhost:9090/h2-console`. That never worked: gateway-admin's H2 dependency is
> `<scope>test</scope>` only, so no H2 driver is on the runtime classpath, and no H2 console is
> enabled.

---

## Production Checklist

- [ ] Set `ADMIN_USERNAME` / `ADMIN_PASSWORD` to real values (there is no default account)
- [ ] Enable HTTPS on gateway
- [ ] Switch `ddl-auto` to `validate` (the `prod` profile already does - confirm no
      `SPRING_JPA_HIBERNATE_DDL_AUTO` in the container env silently overrides it) and back the
      schema with a dump, since there is no migration tool
- [ ] Set up Redis for distributed rate limiting
- [ ] Configure alert thresholds
- [ ] Enable request tracing
- [ ] Set up Prometheus monitoring
- [ ] Deploy gateway instances to Kubernetes
- [ ] Configure Nacos namespace isolation per instance
- [ ] Set up heartbeat monitoring for instances

---

## Deploy to Kubernetes

### 1. Prepare Kubernetes Cluster

```bash
# Ensure kubectl is configured
kubectl cluster-info

# Create namespace
kubectl create namespace gateway-prod
```

### 2. Deploy Nacos and Redis

```bash
# Use provided K8s manifests
kubectl apply -f k8s/nacos.yaml
kubectl apply -f k8s/redis.yaml
```

### 3. Register Cluster in Admin UI

1. Navigate to **Kubernetes** page
2. Click **Add Cluster**
3. Enter cluster details (API server, token)
4. Click **Save**

### 4. Create Gateway Instance

1. Navigate to **Instances** page
2. Click **Create Instance**
3. Configure:
   - Instance Name: `Production Gateway`
   - Cluster: Select your cluster
   - Namespace: `gateway-prod`
   - Spec Type: `large` (2 cores, 2GB - `InstanceSpec` defines CPU and memory only)
   - Replicas: set separately, it is not part of the spec (`InstanceCreateRequest.replicas`,
     default `1`)
4. Click **Create**

The gateway will be deployed automatically and start sending heartbeats.

---

For complete feature documentation, see [FEATURES.md](FEATURES.md).