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
22. [Audit Logs](#22-audit-logs)
23. [System Diagnostic](#23-system-diagnostic)
24. [Traffic Topology](#24-traffic-topology)
25. [Filter Chain Analysis](#25-filter-chain-analysis)
26. [Request Replay Debugger](#26-request-replay-debugger)
27. [AI Copilot Assistant](#27-ai-copilot-assistant)
28. [Stress Test Tool](#28-stress-test-tool)

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

## 22. Audit Logs

### 22.1 Overview

Audit logs track all configuration changes in the gateway, providing a complete history of operations for compliance and troubleshooting.

### 22.2 Features

| Feature | Description |
|---------|-------------|
| **Operation Tracking** | Record all CREATE, UPDATE, DELETE, ENABLE, DISABLE operations |
| **Diff Comparison** | Show before/after values for each change |
| **Rollback Support** | Revert configuration to a previous state |
| **Timeline View** | Visualize changes in chronological order |
| **Auto Cleanup** | Automatic cleanup of logs older than retention period |

### 22.3 Tracked Operations

| Operation Type | Description |
|----------------|-------------|
| `CREATE` | New configuration created |
| `UPDATE` | Configuration modified |
| `DELETE` | Configuration deleted |
| `ROLLBACK` | Configuration rolled back |

### 22.4 Target Types

| Target Type | Description |
|-------------|-------------|
| `ROUTE` | Route configuration |
| `SERVICE` | Service configuration |
| `STRATEGY` | Strategy configuration |
| `AUTH_POLICY` | Authentication policy |

### 22.5 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/audit-logs` | List audit logs with filters |
| `GET` | `/api/audit-logs/{id}/diff` | Get change diff for a log entry |
| `POST` | `/api/audit-logs/{id}/rollback` | Rollback to this version |
| `GET` | `/api/audit-logs/timeline/{instanceId}` | Get timeline view |
| `GET` | `/api/audit-logs/cleanup/stats` | Get cleanup statistics |
| `POST` | `/api/audit-logs/cleanup` | Trigger cleanup of expired logs |

### 22.6 Configuration

```yaml
audit:
  retention-days: 30        # Keep logs for 30 days
  cleanup-schedule: "0 0 2 * * ?"  # Daily cleanup at 2 AM
```

---

## 23. System Diagnostic

### 23.1 Overview

System diagnostic provides comprehensive health checks for all gateway components, helping identify issues before they cause problems.

### 23.2 Diagnostic Types

| Type | Description | Duration |
|------|-------------|----------|
| `quick` | Fast health check of key components | ~2-5 seconds |
| `full` | Complete diagnostic with detailed analysis | ~30-60 seconds |

### 23.3 Components Checked

| Component | Checks |
|-----------|--------|
| **Database** | Connection status, query latency, table integrity |
| **Redis** | Connection status, memory usage, key count |
| **Config Center (Nacos)** | Connection status, config sync status |
| **Routes** | Route count, enabled/disabled status, invalid routes |
| **Authentication** | Auth policy status, JWT validation test |
| **Gateway Instances** | Instance count, health status, heartbeat check |
| **Performance** | CPU usage, memory usage, thread count, JVM metrics |

### 23.4 Health Status

| Status | Description |
|--------|-------------|
| `HEALTHY` | All checks passed |
| `WARNING` | Minor issues detected |
| `CRITICAL` | Major issues requiring attention |
| `NOT_CONFIGURED` | Component not configured |

### 23.5 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/diagnostic/quick` | Quick diagnostic |
| `GET` | `/api/diagnostic/full` | Full diagnostic |

### 23.6 Response Example

```json
{
  "overallScore": 85,
  "status": "HEALTHY",
  "duration": "5.2s",
  "recommendations": [
    "Consider increasing Redis memory allocation",
    "Review expired SSL certificates"
  ],
  "database": {
    "status": "HEALTHY",
    "metrics": {
      "connectionPoolSize": 10,
      "activeConnections": 3,
      "avgQueryLatencyMs": 12
    }
  },
  "redis": {
    "status": "WARNING",
    "warnings": ["Memory usage above 80%"],
    "metrics": {
      "connected": true,
      "memoryUsageMB": 512,
      "keyCount": 15000
    }
  }
}
```

---

## 24. Traffic Topology

### 24.1 Overview

Traffic topology provides a real-time visualization of request flow through the gateway, showing relationships between clients, routes, and backend services.

### 24.2 Features

| Feature | Description |
|---------|-------------|
| **Real-time Graph** | Interactive force-directed graph visualization |
| **Traffic Metrics** | Request count, error rate, latency per connection |
| **Time Range Selection** | View topology for last 15min, 30min, 1h, 3h, 6h |
| **Auto Refresh** | Automatic refresh every 30 seconds |
| **Node Details** | Click nodes to see detailed metrics |

### 24.3 Node Types

| Node Type | Color | Description |
|-----------|-------|-------------|
| `gateway` | Blue | Gateway instance |
| `route` | Cyan | Route definition |
| `service` | Purple | Backend service |
| `client` | Orange | Client IP address |

### 24.4 Edge Metrics

| Metric | Description |
|---------|-------------|
| `requestCount` | Total requests on this path |
| `avgLatency` | Average response latency |
| `errorRate` | Error percentage |

### 24.5 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/topology/{instanceId}` | Get topology data |

### 24.6 Response Example

```json
{
  "nodes": [
    {"id": "gateway-1", "type": "gateway", "name": "Gateway"},
    {"id": "route-users", "type": "route", "name": "/api/users/**"},
    {"id": "service-user", "type": "service", "name": "user-service"}
  ],
  "edges": [
    {
      "source": "client-192.168.1.100",
      "target": "gateway-1",
      "metrics": {"requestCount": 500, "avgLatency": 45}
    }
  ],
  "metrics": {
    "totalRequests": 15000,
    "requestsPerSecond": 125.5,
    "avgLatency": 45.2,
    "errorRate": 0.5,
    "uniqueClients": 50,
    "uniqueRoutes": 10
  }
}
```

---

## 25. Filter Chain Analysis

### 25.1 Overview

Filter chain analysis provides detailed statistics on each filter's execution, helping identify performance bottlenecks and failures.

### 25.2 Features

| Feature | Description |
|---------|-------------|
| **Filter Statistics** | Execution count, success rate, latency per filter |
| **Trace Records** | Individual request trace through filter chain |
| **Trace Search** | Search by trace ID to see filter execution details |
| **Error Analysis** | Identify filters causing failures |

### 25.3 Statistics per Filter

| Metric | Description |
|--------|-------------|
| `totalCount` | Total executions |
| `successCount` | Successful executions |
| `failureCount` | Failed executions |
| `successRate` | Success percentage |
| `avgDurationMicros` | Average execution time (microseconds) |
| `maxDurationMicros` | Maximum execution time |
| `minDurationMicros` | Minimum execution time |

### 25.4 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/filter-chain/{instanceId}/stats` | Get filter statistics |
| `GET` | `/api/filter-chain/{instanceId}/records` | Get recent trace records |
| `GET` | `/api/filter-chain/{instanceId}/trace/{traceId}` | Get specific trace details |
| `DELETE` | `/api/filter-chain/{instanceId}/stats` | Clear statistics |

### 25.5 Use Cases

| Use Case | Benefit |
|----------|---------|
| **Performance Tuning** | Identify slow filters |
| **Troubleshooting** | Find which filter caused a failure |
| **Capacity Planning** | Understand filter execution patterns |

---

## 26. Request Replay Debugger

### 26.1 Overview

Request replay allows developers to debug issues by replaying captured requests with modifications, comparing original vs. replayed responses.

### 26.2 Features

| Feature | Description |
|---------|-------------|
| **Request Capture** | Capture error and slow requests automatically |
| **Request Editing** | Modify path, headers, body before replay |
| **Quick Replay** | Replay original request without modifications |
| **Response Comparison** | Compare original vs. replayed response |
| **Body Diff** | Show field-level differences in response body |

### 26.3 Workflow

```
1. Select trace from recent traces list
2. Edit request (optional):
   - Modify path
   - Add/remove headers
   - Modify request body
3. Execute replay
4. View results:
   - Status code comparison
   - Latency comparison
   - Response body diff
```

### 26.4 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/replay/prepare/{traceId}` | Prepare replayable request |
| `POST` | `/api/replay/execute/{traceId}` | Execute replay with modifications |

### 26.5 Request Modifications

```json
{
  "modifiedPath": "/api/v2/users",
  "modifiedQueryString": "debug=true",
  "modifiedHeaders": {
    "Authorization": "Bearer new-token",
    "X-Debug": "true"
  },
  "modifiedBody": "{\"name\": \"test\"}",
  "compareWithOriginal": true
}
```

### 26.6 Replay Result

```json
{
  "success": true,
  "statusCode": 200,
  "latencyMs": 45,
  "responseBody": "{\"id\": 1, \"name\": \"test\"}",
  "comparison": {
    "originalStatus": 500,
    "replayedStatus": 200,
    "statusMatch": false,
    "originalLatencyMs": 1500,
    "replayedLatencyMs": 45,
    "latencyDiffMs": -1455,
    "bodyMatch": false,
    "bodyDiff": [
      {"field": "error", "originalValue": "timeout", "replayedValue": null, "type": "REMOVED"}
    ]
  }
}
```

---

## 27. AI Copilot Assistant

### 27.1 Overview

AI Copilot is an intelligent assistant powered by large language models, helping users configure, troubleshoot, and optimize the gateway.

### 27.2 Supported AI Providers

| Region | Providers | Models |
|--------|-----------|--------|
| **Domestic (China)** | Qwen, DeepSeek | qwen-plus, qwen-turbo, deepseek-chat |
| **Overseas** | OpenAI, Anthropic | GPT-4, GPT-3.5-turbo, Claude-3 |

### 27.3 Features

| Feature | Description |
|---------|-------------|
| **Chat Interface** | Natural language conversation about gateway |
| **Route Generator** | Generate route config from description |
| **Error Analyzer** | Analyze error messages and suggest fixes |
| **Performance Optimizer** | Get optimization suggestions based on metrics |
| **Concept Explainer** | Learn gateway concepts with explanations |
| **Config Validation** | Validate generated route configurations |

### 27.4 Tabs

| Tab | Features |
|-----|----------|
| **Chat** | Free conversation with AI assistant |
| **Tools** | Route generator, error analyzer, performance optimizer |
| **Learn** | Concept explanations, quick reference |

### 27.5 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/copilot/providers` | List available AI providers |
| `GET` | `/api/copilot/providers/{provider}/models` | Get available models |
| `POST` | `/api/copilot/validate` | Validate API key |
| `POST` | `/api/copilot/config` | Save AI configuration |
| `POST` | `/api/copilot/chat` | Send chat message |
| `DELETE` | `/api/copilot/chat/{sessionId}` | Clear conversation |
| `POST` | `/api/copilot/generate-route` | Generate route from description |
| `POST` | `/api/copilot/validate-route` | Validate route JSON |
| `POST` | `/api/copilot/apply-route` | Apply generated route |
| `POST` | `/api/copilot/analyze-error` | Analyze error message |
| `GET` | `/api/copilot/optimizations/{instanceId}` | Get optimization suggestions |
| `GET` | `/api/copilot/explain` | Explain a concept |

### 27.6 Route Generation Example

**Input:**
```
"Create a route for user API, path pattern /api/users/**, 
forward to user-service with rate limiting 100 QPS"
```

**Output:**
```json
{
  "routeName": "User API Route",
  "uri": "lb://user-service",
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/users/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ],
  "strategies": {
    "rateLimiter": {
      "enabled": true,
      "qps": 100
    }
  },
  "enabled": true
}
```

---

## 28. Stress Test Tool

### 28.1 Overview

Stress test tool allows users to test gateway performance under load, measuring throughput, latency, and error rates.

### 28.2 Features

| Feature | Description |
|---------|-------------|
| **Custom Test Configuration** | Configure target URL, method, headers, body |
| **Concurrent Users Simulation** | Simulate multiple concurrent users |
| **Real-time Progress** | Live view of test progress and metrics |
| **Detailed Statistics** | P50, P90, P95, P99 latency distribution |
| **AI Analysis** | AI-powered analysis of test results |
| **Quick Test** | One-click quick test with preset parameters |

### 28.3 Test Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `testName` | Test name for identification | - |
| `targetUrl` | Target endpoint URL (optional, uses instance URL if not set) | - |
| `path` | Path to append to instance URL | - |
| `method` | HTTP method | `GET` |
| `headers` | Request headers (JSON) | - |
| `body` | Request body for POST/PUT | - |
| `concurrentUsers` | Number of concurrent users | `10` |
| `totalRequests` | Total requests to send (use with durationSeconds) | `1000` |
| `durationSeconds` | Test duration in seconds (alternative to totalRequests) | - |
| `targetQps` | Target QPS limit | - |
| `rampUpSeconds` | Ramp-up time for gradual load increase | `0` |
| `requestTimeoutSeconds` | Per-request timeout | `30` |

### 28.4 Metrics Collected

| Metric | Description |
|--------|-------------|
| `actualRequests` | Actual requests sent |
| `successfulRequests` | Successful requests (2xx) |
| `failedRequests` | Failed requests (4xx/5xx) |
| `minResponseTimeMs` | Minimum response time |
| `maxResponseTimeMs` | Maximum response time |
| `avgResponseTimeMs` | Average response time |
| `p50ResponseTimeMs` | 50th percentile latency |
| `p90ResponseTimeMs` | 90th percentile latency |
| `p95ResponseTimeMs` | 95th percentile latency |
| `p99ResponseTimeMs` | 99th percentile latency |
| `requestsPerSecond` | Achieved QPS |
| `errorRate` | Error percentage |
| `throughputKbps` | Throughput in KB/s |

### 28.5 Test Status

| Status | Description |
|--------|-------------|
| `RUNNING` | Test is currently executing |
| `COMPLETED` | Test finished successfully |
| `STOPPED` | Test stopped by user |
| `FAILED` | Test failed to complete |

### 28.6 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/stress-test/instance/{instanceId}` | List tests for instance |
| `POST` | `/api/stress-test/start` | Start new test |
| `POST` | `/api/stress-test/quick` | Quick test with defaults |
| `GET` | `/api/stress-test/{testId}/status` | Get running test status |
| `POST` | `/api/stress-test/{testId}/stop` | Stop running test |
| `DELETE` | `/api/stress-test/{testId}` | Delete test record |
| `GET` | `/api/stress-test/{testId}/analyze` | AI analysis of test results |

### 28.7 Example Test Result

```json
{
  "id": 1,
  "testName": "API Gateway Load Test",
  "status": "COMPLETED",
  "actualRequests": 10000,
  "successfulRequests": 9950,
  "failedRequests": 50,
  "minResponseTimeMs": 5,
  "maxResponseTimeMs": 2500,
  "avgResponseTimeMs": 45,
  "p50ResponseTimeMs": 35,
  "p90ResponseTimeMs": 80,
  "p95ResponseTimeMs": 120,
  "p99ResponseTimeMs": 500,
  "requestsPerSecond": 833.3,
  "errorRate": 0.5,
  "throughputKbps": 1250
}
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