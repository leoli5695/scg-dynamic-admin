# Quick Start Guide

> Get your API Gateway up and running in 15 minutes.

---

## Prerequisites

| Tool | Version | Required |
|------|---------|----------|
| Java | 21+ | Yes |
| Maven | 3.8+ | Yes |
| Node.js | 18+ | Yes (for UI) |
| MySQL | 8.0+ | **Yes** - gateway-admin has no H2 dependency, there is no embedded fallback |
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

## Step 2: Initialize Nacos Configuration

Open Nacos Console: http://localhost:8848/nacos (nacos/nacos)

### Create `gateway-routes.json`

**Data ID:** `gateway-routes.json`  
**Group:** `DEFAULT_GROUP`  
**Format:** JSON

```json
{
  "version": "1.0",
  "routes": [
    {
      "id": "demo-route",
      "routeName": "Demo Service Route",
      "uri": "lb://demo-service",
      "order": 0,
      "predicates": [
        {"name": "Path", "args": {"pattern": "/api/**"}}
      ],
      "filters": [
        {"name": "StripPrefix", "args": {"parts": "1"}}
      ],
      "enabled": true
    }
  ]
}
```

### Create `gateway-services.json`

**Data ID:** `gateway-services.json`  
**Group:** `DEFAULT_GROUP`  
**Format:** JSON

```json
{
  "version": "1.0",
  "services": []
}
```

### Create `gateway-strategies.json`

**Data ID:** `gateway-strategies.json`  
**Group:** `DEFAULT_GROUP`  
**Format:** JSON

```json
{
  "version": "1.0",
  "strategies": []
}
```

---

## Step 3: Configure Application

### gateway-admin/src/main/resources/application.yml

The committed file already reads MySQL settings from the environment; you normally only need to
export the variables rather than edit it. There is **no** H2 profile - an earlier revision of this
document described one, but `gateway-admin/pom.xml` has never declared an H2 dependency, so
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

nacos:
  server-addr: 127.0.0.1:8848
```

### my-gateway/src/main/resources/application.yml

```yaml
server:
  port: 80

spring:
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
      config:
        server-addr: 127.0.0.1:8848

gateway:
  center:
    type: nacos
```

---

## Step 4: Start Services

### Start Admin Service

```bash
cd gateway-admin
mvn spring-boot:run
```

Verify: http://localhost:9090/api/routes

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

Verify: http://localhost:9002/hello

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

```bash
curl -X POST http://localhost:9090/api/routes \
  -H "Content-Type: application/json" \
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

# Check route list
curl http://localhost:9090/api/routes

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
2. For `static://` - Configure instances in `gateway-services.json`
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
│                   demo-service (:9002)                       │
│                                                              │
│   /hello, /headers, /params, /delay                          │
└─────────────────────────────────────────────────────────────┘
```

---

## Development Tips

### Hot Reload

Configuration changes in Nacos automatically propagate to the gateway in < 1 second. No restart required.

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

> An earlier revision of this document described an H2 console at
> `http://localhost:9090/h2-console`. That never worked: gateway-admin has no H2 dependency and
> does not enable an H2 console.

---

## Production Checklist

- [ ] Set `ADMIN_USERNAME` / `ADMIN_PASSWORD` to real values (there is no default account)
- [ ] Enable HTTPS on gateway
- [ ] Switch `ddl-auto` to `validate` (the `prod` profile already does) and back the schema with a
      dump, since there is no migration tool
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
   - Spec Type: `large` (2 cores, 2GB, 3 replicas)
4. Click **Create**

The gateway will be deployed automatically and start sending heartbeats.

---

For complete feature documentation, see [FEATURES.md](FEATURES.md).