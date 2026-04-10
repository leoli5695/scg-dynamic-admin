# Features Documentation

> Complete guide to all gateway features with configuration examples and API reference.

---

## Table of Contents

1. [Route Management](#1-route-management)
2. [Multi-Service Routing & Gray Release](#2-multi-service-routing--gray-release)
3. [Service Discovery](#3-service-discovery)
4. [SSL Termination](#4-ssl-termination)
5. [Authentication](#5-authentication)
6. [Rate Limiting](#6-rate-limiting)
7. [Circuit Breaker](#7-circuit-breaker)
8. [IP Filtering](#8-ip-filtering)
9. [Timeout Control](#9-timeout-control)
10. [Request Body Transformation](#10-request-body-transformation)
11. [Request Validation](#11-request-validation)
12. [Mock Response](#12-mock-response)
13. [Response Body Transformation](#13-response-body-transformation)
14. [Response Caching](#14-response-caching)
15. [Monitoring & Alerts](#15-monitoring--alerts)
16. [Request Tracing](#16-request-tracing)
17. [AI-Powered Analysis](#17-ai-powered-analysis)
18. [Email Notifications](#18-email-notifications)
19. [Gateway Instance Management](#19-gateway-instance-management)
20. [Kubernetes Integration](#20-kubernetes-integration)
21. [API Reference](#21-api-reference)

---

## 1. Route Management

### 1.1 Overview

Routes define how incoming requests are forwarded to backend services.

**Configuration Storage:** Nacos `gateway-routes.json`

### 1.2 Route Structure

```json
{
  "id": "user-service-route",
  "routeName": "User Service Route",
  "uri": "lb://user-service",
  "order": 0,
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/user/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ],
  "enabled": true
}
```

### 1.3 URI Schemes

| Scheme | Description | Example |
|--------|-------------|---------|
| `lb://` | Dynamic service discovery via Nacos/Consul | `lb://user-service` |
| `static://` | Static service discovery | `static://backend-service` |
| `http://` | Direct HTTP endpoint | `http://192.168.1.10:8080` |

### 1.4 Predicates

| Predicate | Description | Example |
|-----------|-------------|---------|
| `Path` | URL path pattern | `/api/user/**` |
| `Host` | Host header match | `**.example.com` |
| `Method` | HTTP method | `GET,POST` |
| `Header` | Header existence/match | `X-Request-Id, \d+` |
| `Query` | Query parameter | `userId` |
| `After` | After time | `2024-01-01T00:00:00+08:00` |
| `Before` | Before time | `2024-12-31T23:59:59+08:00` |

### 1.5 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/routes` | List all routes |
| `GET` | `/api/routes/{id}` | Get route by ID |
| `POST` | `/api/routes` | Create route |
| `PUT` | `/api/routes/{id}` | Update route |
| `DELETE` | `/api/routes/{id}` | Delete route |
| `POST` | `/api/routes/{id}/enable` | Enable route |
| `POST` | `/api/routes/{id}/disable` | Disable route |

---

## 2. Multi-Service Routing & Gray Release

### 2.1 Overview

Multi-service routing allows a single route to distribute traffic across multiple backend services with configurable weights and rules.

### 2.2 Configuration

```json
{
  "id": "user-route",
  "mode": "MULTI",
  "services": [
    {
      "serviceId": "user-v1",
      "serviceName": "User Service V1",
      "weight": 90,
      "type": "DISCOVERY",
      "enabled": true
    },
    {
      "serviceId": "user-v2",
      "serviceName": "User Service V2",
      "weight": 10,
      "type": "DISCOVERY",
      "enabled": true
    }
  ],
  "grayRules": {
    "enabled": true,
    "rules": [
      {
        "type": "HEADER",
        "name": "X-Version",
        "value": "v2",
        "targetVersion": "user-v2"
      }
    ]
  }
}
```

### 2.3 Gray Rule Types

| Type | Description | Example |
|------|-------------|---------|
| `HEADER` | Match HTTP header | `X-Version: v2` |
| `COOKIE` | Match cookie value | `version=v2` |
| `QUERY` | Match URL parameter | `?version=v2` |
| `WEIGHT` | Percentage-based | `10%` to v2 |

### 2.4 Rule Matching Logic

```
Request arrives
      │
      ▼
┌─────────────────┐
│ Check Gray Rules│
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
   Match    No Match
    │         │
    ▼         ▼
  Route to  Use Weight
  Target    Distribution
  Service
```

**First-match-wins:** Rules are evaluated in order. First matching rule wins.

### 2.5 Use Cases

| Use Case | Configuration |
|----------|---------------|
| **Canary Deployment** | 5% traffic to new version via WEIGHT rule |
| **Beta Testing** | Route users with `X-Beta: true` header to beta service |
| **A/B Testing** | Use COOKIE rule to split users into groups |
| **Internal Testing** | Route internal IPs to staging via HEADER rule |

---

## 3. Service Discovery

### 3.1 Dual Protocol Support

| Protocol | Description | Use Case |
|----------|-------------|----------|
| `lb://` | Dynamic discovery via Nacos/Consul | Services in service registry |
| `static://` | Static service discovery | Legacy systems, external APIs |

### 3.2 Static Service Configuration

```json
{
  "name": "legacy-backend",
  "loadBalancer": "weighted",
  "instances": [
    {"ip": "192.168.1.10", "port": 8080, "weight": 1, "enabled": true},
    {"ip": "192.168.1.11", "port": 8080, "weight": 2, "enabled": true}
  ]
}
```

### 3.3 Load Balancing Strategies

| Strategy | Description | Best For |
|----------|-------------|----------|
| `weighted` | Smooth weighted round-robin | Uneven instance capacity |
| `round-robin` | Sequential distribution | Equal capacity instances |
| `random` | Random selection | Simple scenarios |
| `consistent-hash` | Hash-based (client IP/header) | Session stickiness |

### 3.4 Health-Aware Routing

- Unhealthy instances are **automatically skipped**
- Disabled instances are **excluded** from load balancing
- Health status synced to admin UI in real-time

---

## 4. SSL Termination

### 4.1 Overview

The gateway provides HTTPS termination on port 8443 with dynamic certificate management.

### 4.2 Configuration

```yaml
gateway:
  ssl:
    enabled: true
    port: 8443
    cert-path: /opt/certificates
```

### 4.3 Certificate Management

| Feature | Description |
|---------|-------------|
| **Formats** | PEM, PKCS12 (.p12/.pfx), JKS |
| **Multi-domain** | Multiple certificates for different domains |
| **Hot-reload** | Certificates loaded without restart |
| **Expiry monitoring** | Alerts before certificates expire |

### 4.4 Certificate Upload

```bash
# Upload PEM certificate
POST /api/ssl/upload
Content-Type: multipart/form-data

file: certificate.pem
key: private.key
domain: api.example.com

# Upload PKCS12
POST /api/ssl/upload-pkcs12
file: certificate.p12
password: changeit
domain: api.example.com
```

### 4.5 Certificate Status

| Status | Description |
|--------|-------------|
| `VALID` | Certificate is valid |
| `EXPIRING_SOON` | Expires within 30 days |
| `EXPIRED` | Certificate has expired |

---

## 5. Authentication

### 5.1 Overview

Multi-strategy authentication using the Strategy Pattern.

### 5.2 Supported Types

| Type | Processor | Use Case |
|------|-----------|----------|
| `JWT` | `JwtAuthProcessor` | Stateless API authentication |
| `API_KEY` | `ApiKeyAuthProcessor` | Simple partner access |
| `BASIC` | `BasicAuthProcessor` | Simple username/password |
| `HMAC` | `HmacSignatureAuthProcessor` | API signature verification |
| `OAUTH2` | `OAuth2AuthProcessor` | Third-party SSO |

### 5.3 Configuration Examples

**JWT Authentication:**
```json
{
  "routeId": "secure-api",
  "authType": "JWT",
  "secretKey": "your-256-bit-secret",
  "issuer": "my-app",
  "enabled": true
}
```

**API Key Authentication:**
```json
{
  "routeId": "partner-api",
  "authType": "API_KEY",
  "headerName": "X-API-Key",
  "apiKey": "sk-your-api-key",
  "enabled": true
}
```

**HMAC Signature:**
```json
{
  "routeId": "webhook-api",
  "authType": "HMAC",
  "secretKey": "hmac-secret",
  "algorithm": "HmacSHA256",
  "enabled": true
}
```

---

## 6. Rate Limiting

### 6.1 Overview

Hybrid rate limiting with Redis (distributed) + Local (fallback).

### 6.2 Configuration

```json
{
  "routeId": "public-api",
  "qps": 100,
  "burstCapacity": 200,
  "keyType": "ip",
  "enabled": true
}
```

### 6.3 Key Types

| Key Type | Description |
|----------|-------------|
| `ip` | Per-client IP |
| `route` | Shared limit per route |
| `combined` | Route + IP combination |
| `header` | Based on header value |

### 6.4 Redis Failover

When Redis is unavailable, the gateway automatically:
1. Switches to local rate limiting
2. Uses pre-calculated shadow quota
3. Gradually shifts traffic back when Redis recovers

---

## 7. Circuit Breaker

### 7.1 Overview

Protects downstream services from cascading failures using Resilience4j.

### 7.2 State Machine

```
CLOSED (Normal)
    │
    └──▶ Failure rate > threshold ──▶ OPEN (Reject all)
                                        │
                                        └──▶ After waitDuration ──▶ HALF_OPEN (Test)
                                                                      │
                                          ┌───────────────────────────┘
                                          │
                                          ▼
                                    Success ──▶ CLOSED
                                    Failure ──▶ OPEN
```

### 7.3 Configuration

```json
{
  "routeId": "critical-service",
  "failureRateThreshold": 50.0,
  "slowCallDurationThreshold": 60000,
  "waitDurationInOpenState": 30000,
  "slidingWindowSize": 10,
  "minimumNumberOfCalls": 5,
  "enabled": true
}
```

---

## 8. IP Filtering

### 8.1 Overview

IP blacklist/whitelist with CIDR support.

### 8.2 Modes

| Mode | Description |
|------|-------------|
| `blacklist` | Block listed IPs |
| `whitelist` | Allow only listed IPs |

### 8.3 IP Formats

| Format | Example |
|--------|---------|
| Exact | `192.168.1.100` |
| Wildcard | `192.168.1.*` |
| CIDR | `192.168.1.0/24` |

### 8.4 Configuration

```json
{
  "routeId": "internal-api",
  "mode": "whitelist",
  "ipList": ["10.0.0.0/8", "192.168.0.0/16"],
  "enabled": true
}
```

---

## 9. Timeout Control

### 9.1 Overview

Per-route connection and response timeout control.

### 9.2 Configuration

```json
{
  "routeId": "slow-api",
  "connectTimeout": 5000,
  "responseTimeout": 30000,
  "enabled": true
}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `connectTimeout` | TCP connection timeout (ms) | 5000 |
| `responseTimeout` | Full response timeout (ms) | 30000 |

---

## 10. Request Body Transformation

### 10.1 Overview

Transform request body before forwarding to backend services. Supports protocol conversion, field mapping, and data masking.

### 10.2 Supported Operations

| Operation | Description |
|-----------|-------------|
| **Protocol Conversion** | JSON ↔ XML conversion |
| **Field Mapping** | Rename, add, remove fields |
| **Data Masking** | Mask sensitive fields (passwords, tokens) |
| **Field Injection** | Add static or dynamic values |

### 10.3 Configuration

```json
{
  "routeId": "legacy-api",
  "enabled": true,
  "transformRules": [
    {
      "type": "FIELD_MAP",
      "sourcePath": "$.user_name",
      "targetPath": "$.username"
    },
    {
      "type": "FIELD_MASK",
      "path": "$.password",
      "maskChar": "***"
    },
    {
      "type": "FIELD_INJECT",
      "path": "$.request_time",
      "value": "${timestamp}"
    }
  ]
}
```

### 10.4 Use Cases

| Use Case | Configuration |
|----------|---------------|
| **Legacy API Integration** | Transform modern JSON to legacy format |
| **Data Sanitization** | Remove/mask sensitive fields before logging |
| **Protocol Bridge** | Convert XML to JSON for modern clients |

---

## 11. Request Validation

### 11.1 Overview

Validate incoming requests against predefined schemas before processing. Supports JSON Schema validation, required field checks, and type constraints.

### 11.2 Validation Types

| Type | Description |
|------|-------------|
| **JSON Schema** | Validate against JSON Schema specification |
| **Required Fields** | Check mandatory fields exist |
| **Type Constraints** | Validate field types (string, number, etc.) |
| **Format Validation** | Validate email, date, regex patterns |
| **Range Validation** | Min/max values for numbers |

### 11.3 Configuration

```json
{
  "routeId": "user-api",
  "enabled": true,
  "validationRules": [
    {
      "path": "$.email",
      "type": "FORMAT",
      "format": "email",
      "errorMessage": "Invalid email format"
    },
    {
      "path": "$.age",
      "type": "RANGE",
      "min": 0,
      "max": 150,
      "errorMessage": "Age must be between 0 and 150"
    }
  ],
  "requiredFields": ["username", "email", "password"]
}
```

### 11.4 Error Response

```json
{
  "status": 400,
  "error": "Validation Failed",
  "details": [
    {"field": "email", "message": "Invalid email format"},
    {"field": "age", "message": "Age must be between 0 and 150"}
  ]
}
```

---

## 12. Mock Response

### 12.1 Overview

Return mock responses for testing and development without backend services. Supports static responses, dynamic templates, and error simulation.

### 12.2 Mock Types

| Type | Description | Use Case |
|------|-------------|----------|
| **Static** | Fixed response body | Simple mocking |
| **Dynamic** | Template-based with variables | Realistic test data |
| **Error Simulation** | Return specific error codes | Error handling tests |
| **Delayed** | Add artificial delay | Latency testing |

### 12.3 Configuration

```json
{
  "routeId": "test-api",
  "enabled": true,
  "mockType": "DYNAMIC",
  "statusCode": 200,
  "headers": {
    "Content-Type": "application/json",
    "X-Mock": "true"
  },
  "bodyTemplate": {
    "id": "${random.uuid}",
    "name": "${random.name}",
    "email": "${random.email}",
    "createdAt": "${timestamp}",
    "status": "active"
  },
  "delayMs": 100,
  "errorSimulation": {
    "enabled": false,
    "errorCode": 500,
    "errorRate": 0.1
  }
}
```

### 12.4 Template Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `${random.uuid}` | Random UUID | `a1b2c3d4-e5f6-...` |
| `${random.name}` | Random name | `John Doe` |
| `${random.email}` | Random email | `john@example.com` |
| `${timestamp}` | Current timestamp | `2024-01-15T10:30:00Z` |
| `${request.header.X-Id}` | Request header value | From request |

---

## 13. Response Body Transformation

### 13.1 Overview

Transform backend responses before returning to clients. Useful for API versioning, data filtering, and format conversion.

### 13.2 Supported Operations

| Operation | Description |
|-----------|-------------|
| **Field Filtering** | Remove sensitive or unnecessary fields |
| **Field Mapping** | Rename or restructure response fields |
| **Format Conversion** | XML → JSON, etc. |
| **Response Wrapping** | Wrap response in standard envelope |

### 13.3 Configuration

```json
{
  "routeId": "external-api",
  "enabled": true,
  "transformRules": [
    {
      "type": "FIELD_REMOVE",
      "path": "$.internal_id"
    },
    {
      "type": "FIELD_REMOVE",
      "path": "$.debug_info"
    },
    {
      "type": "FIELD_MAP",
      "sourcePath": "$.user_data",
      "targetPath": "$.user"
    }
  ],
  "responseWrapper": {
    "enabled": true,
    "wrapperField": "data",
    "includeMetadata": true
  }
}
```

### 13.4 Example Transformation

**Before (Backend Response):**
```json
{
  "internal_id": 12345,
  "debug_info": {"query_time": 50},
  "user_data": {
    "name": "John",
    "email": "john@example.com"
  }
}
```

**After (Client Response):**
```json
{
  "data": {
    "user": {
      "name": "John",
      "email": "john@example.com"
    }
  },
  "metadata": {
    "transformed": true
  }
}
```

---

## 14. Response Caching

### 14.1 Overview

Caffeine-based in-memory caching for GET/HEAD requests.

### 14.2 Configuration

```json
{
  "routeId": "static-api",
  "ttl": 300,
  "maxSize": 1000,
  "enabled": true
}
```

### 14.3 Cache Headers

| Header | Description |
|--------|-------------|
| `X-Cache` | `HIT` or `MISS` |
| `Age` | Seconds since cached |

---

## 15. Monitoring & Alerts

### 15.1 Metrics

| Category | Metrics |
|----------|---------|
| **JVM** | Heap used/max, GC count/time |
| **CPU** | Process/system usage |
| **Threads** | Live, daemon, peak |
| **HTTP** | Requests/sec, response time, error rate |

### 15.2 Alert Thresholds

```json
{
  "cpu": {
    "processThreshold": 80,
    "systemThreshold": 90
  },
  "memory": {
    "heapThreshold": 85
  },
  "http": {
    "errorRateThreshold": 5,
    "responseTimeThreshold": 2000
  }
}
```

### 15.3 Notification Channels

- Email (SMTP with HTML templates)
- AI-generated alert content with recommendations

---

## 16. Request Tracing

### 16.1 Overview

Capture error and slow requests for debugging.

### 16.2 Trace Types

| Type | Description |
|------|-------------|
| `ERROR` | Failed requests (4xx/5xx) |
| `SLOW` | Requests exceeding threshold |
| `ALL` | All requests (sampling) |

### 16.3 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/traces/errors` | Get error traces |
| `GET` | `/api/traces/slow` | Get slow traces |
| `GET` | `/api/traces/{id}` | Get trace details |
| `POST` | `/api/traces/{id}/replay` | Replay request |

### 16.4 Replay Feature

```bash
POST /api/traces/{id}/replay
{
  "gatewayUrl": "http://localhost:80"
}
```

Re-executes the captured request for debugging.

---

## 17. AI-Powered Analysis

### 17.1 Supported Providers

| Provider | Models | Configuration |
|----------|--------|---------------|
| OpenAI | GPT-4, GPT-3.5 | API key + base URL |
| Anthropic | Claude 3 | API key |
| Qwen | qwen-plus, qwen-turbo | API key |
| DeepSeek | deepseek-chat | API key |
| Ollama | llama2, mistral | Local URL |

### 17.2 Features

- **Metrics Analysis**: Upload current metrics, get AI insights
- **Alert Content Generation**: AI-written alerts with recommendations
- **Multi-language**: Chinese/English support

---

## 18. Email Notifications

### 18.1 Configuration

```json
{
  "host": "smtp.example.com",
  "port": 587,
  "username": "alerts@example.com",
  "password": "password",
  "from": "Gateway Alerts <alerts@example.com>",
  "useStartTls": true
}
```

### 18.2 Test Email

```bash
POST /api/email/test
{
  "to": "admin@example.com"
}
```

---

## 19. Gateway Instance Management

### 19.1 Overview

Deploy and manage multiple gateway instances from a single admin console. Each instance has isolated configuration via Nacos namespace.

### 19.2 Instance Creation

```bash
# Create a new gateway instance
POST /api/instances
{
  "instanceName": "Production Gateway",
  "clusterId": 1,
  "namespace": "gateway-prod",
  "specType": "large",
  "replicas": 3
}
```

### 19.3 Resource Specifications

| Spec Type | CPU | Memory | Replicas | Use Case |
|-----------|-----|--------|----------|----------|
| `small` | 0.5 core | 512MB | 1 | Development |
| `medium` | 1 core | 1GB | 2 | Staging |
| `large` | 2 cores | 2GB | 3 | Production |
| `xlarge` | 4 cores | 4GB | 5 | High-traffic Production |
| `custom` | Custom | Custom | Custom | Special requirements |

### 19.4 Instance Status

| Status | Code | Description |
|--------|------|-------------|
| STARTING | 0 | Pod is starting up |
| RUNNING | 1 | Healthy, receiving heartbeats |
| ERROR | 2 | Missed heartbeats or crashed |
| STOPPING | 3 | Pod is shutting down |
| STOPPED | 4 | Pod is stopped |

### 19.5 Heartbeat Monitoring

Each gateway instance sends heartbeats every 10 seconds:

```bash
# Heartbeat endpoint
POST /api/instances/{instanceId}/heartbeat
{
  "cpuUsagePercent": 45.5,
  "memoryUsageMb": 512,
  "requestsPerSecond": 1234.5,
  "activeConnections": 100
}
```

### 19.6 Namespace Isolation

Each instance has its own Nacos namespace:

```
Instance: gateway-dev
├── Nacos Namespace: gateway-dev-xxx
│   ├── config.gateway.route-{id}
│   ├── config.gateway.service-{id}
│   ├── config.gateway.strategy-{id}
│   └── config.gateway.metadata.*-index
```

### 19.7 API Endpoints

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

## 20. Kubernetes Integration

### 20.1 Overview

Deploy gateway instances to Kubernetes clusters directly from the admin UI.

### 20.2 Cluster Management

```bash
# Register a Kubernetes cluster
POST /api/kubernetes/clusters
{
  "name": "Production Cluster",
  "apiServer": "https://k8s-api.example.com",
  "token": "xxx",
  "namespace": "gateway-prod"
}
```

### 20.3 Pod Management

```bash
# List pods for an instance
GET /api/kubernetes/instances/{instanceId}/pods

# Get pod logs
GET /api/kubernetes/pods/{podName}/logs

# Delete a pod (will restart)
DELETE /api/kubernetes/pods/{podName}
```

### 20.4 Deployment Configuration

```yaml
# Generated K8s Deployment (from template)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-gateway
  namespace: gateway-prod
spec:
  replicas: 3
  template:
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

### 20.5 Health Probes

| Probe | Path | Purpose |
|-------|------|---------|
| Liveness | `/actuator/health/liveness` | Restart if unhealthy |
| Readiness | `/actuator/health/readiness` | Ready for traffic |

### 20.6 Metrics Integration

Gateway instances expose Prometheus metrics:

```
# Actuator metrics endpoint
GET http://gateway:8081/actuator/prometheus

# Key metrics
gateway_requests_total
gateway_requests_duration_seconds
gateway_active_connections
jvm_memory_used_bytes
```

---

## 21. API Reference

### 21.1 Route API

```bash
# List routes
GET /api/routes

# Create route
POST /api/routes
{
  "routeName": "My Route",
  "uri": "lb://my-service",
  "predicates": [{"name": "Path", "args": {"pattern": "/api/**"}}]
}

# Update route
PUT /api/routes/{id}

# Delete route
DELETE /api/routes/{id}

# Enable/Disable
POST /api/routes/{id}/enable
POST /api/routes/{id}/disable
```

### 21.2 Service API

```bash
# List services
GET /api/services

# Create service
POST /api/services
{
  "name": "my-service",
  "instances": [{"ip": "127.0.0.1", "port": 8080}]
}
```

### 21.3 SSL Certificate API

```bash
# List certificates
GET /api/ssl

# Upload PEM
POST /api/ssl/upload

# Upload PKCS12
POST /api/ssl/upload-pkcs12

# Delete certificate
DELETE /api/ssl/{id}
```

### 21.4 Monitoring API

```bash
# Get metrics
GET /api/monitor/metrics

# Get history
GET /api/monitor/history?range=1h

# AI analysis
POST /api/monitor/analyze
```

---

## Filter Chain Summary

```
Request Flow:
┌─────────────────────────────────────────────────────────────┐
│ Security (-500)        → Security hardening first           │
├─────────────────────────────────────────────────────────────┤
│ IP Filter (-490)       → Fast rejection (before logging)    │
├─────────────────────────────────────────────────────────────┤
│ AccessLog (-400)       → Log all requests                   │
├─────────────────────────────────────────────────────────────┤
│ CORS (-300)            → Handle preflight requests          │
├─────────────────────────────────────────────────────────────┤
│ TraceId (-300)         → Full visibility                    │
├─────────────────────────────────────────────────────────────┤
│ Request Transform (-255) → Modify request body              │
├─────────────────────────────────────────────────────────────┤
│ Request Validation (-254) → Validate request schema         │
├─────────────────────────────────────────────────────────────┤
│ Authentication (-250)  → User identity                      │
├─────────────────────────────────────────────────────────────┤
│ Mock Response (-249)   → Return mock data for testing       │
├─────────────────────────────────────────────────────────────┤
│ Timeout (-200)         → Protect downstream                 │
├─────────────────────────────────────────────────────────────┤
│ API Version (-150)     → Version-based routing              │
├─────────────────────────────────────────────────────────────┤
│ Circuit Breaker (-100) → Prevent cascade failure            │
├─────────────────────────────────────────────────────────────┤
│ Header Op (-50)        → Add/modify headers                 │
├─────────────────────────────────────────────────────────────┤
│ Response Transform (-45) → Modify response body             │
├─────────────────────────────────────────────────────────────┤
│ Cache (50)             → Response caching                   │
├─────────────────────────────────────────────────────────────┤
│ Trace Capture (100)    → Capture error/slow requests        │
├─────────────────────────────────────────────────────────────┤
│ Retry (9999)           → Retry on failure                   │
├─────────────────────────────────────────────────────────────┤
│ Multi-Service LB (10001) → Multi-service routing            │
├─────────────────────────────────────────────────────────────┤
│ Discovery LB (10150)   → Forward to backend                 │
└─────────────────────────────────────────────────────────────┘
```

**Design Philosophy:**
1. Security first → Block malicious requests early
2. Fast rejection → IP filter before logging and auth
3. Observability → TraceId sees everything for debugging
4. Protection → Timeout/Circuit Breaker before routing
5. Retry before routing → Retry at 9999, before LB filters

---

For architecture details, see [ARCHITECTURE.md](ARCHITECTURE.md).