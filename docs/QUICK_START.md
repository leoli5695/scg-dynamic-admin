# Quick Start Guide

> Get your API Gateway up and running in 15 minutes.

---

## Prerequisites

| Tool | Version | Required |
|------|---------|----------|
| Java | 17+ | Yes |
| Maven | 3.8+ | Yes |
| Node.js | 18+ | Yes (for UI) |
| MySQL | 8.0+ | Optional (H2 embedded available) |
| Nacos | 2.4.3+ | Yes |
| Redis | 6.0+ | Optional (for distributed rate limiting) |

---

## Step 1: Start Infrastructure

### Option A: Docker (Recommended)

```bash
# Start Nacos
docker run -d --name nacos \
  -p 8848:8848 \
  -e MODE=standalone \
  nacos/nacos-server:v2.4.3

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

```yaml
server:
  port: 9090

spring:
  datasource:
    url: jdbc:h2:file:./data/gateway_db
    driver-class-name: org.h2.Driver
    username: sa
    password: 
  jpa:
    hibernate:
      ddl-auto: update
  h2:
    console:
      enabled: true

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

**Default Login:**
- Username: `admin`
- Password: `admin123`

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

- [Configure Multi-Service Routing](FEATURES.md#2-multi-service-routing--gray-release)
- [Set up Authentication](FEATURES.md#5-authentication)
- [Enable SSL Termination](FEATURES.md#4-ssl-termination)
- [Configure Monitoring & Alerts](FEATURES.md#11-monitoring--alerts)
- [Deploy Gateway Instance to Kubernetes](FEATURES.md#15-gateway-instance-management)

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

### Access H2 Console

When using embedded H2:

- URL: http://localhost:9090/h2-console
- JDBC URL: `jdbc:h2:file:./data/gateway_db`
- User: `sa`
- Password: (empty)

---

## Production Checklist

- [ ] Change default admin password
- [ ] Enable HTTPS on gateway
- [ ] Configure MySQL instead of H2
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