# Features Documentation

> Complete guide to all gateway features with configuration examples and API reference.

---

## Table of Contents

1. [Route Management](#1-route-management)
2. [Service Discovery](#2-service-discovery)
3. [Authentication](#3-authentication)
4. [Rate Limiting](#4-rate-limiting)
5. [Circuit Breaker](#5-circuit-breaker)
6. [IP Filtering](#6-ip-filtering)
7. [Timeout Control](#7-timeout-control)
8. [Distributed Tracing](#8-distributed-tracing)
9. [Health Checking](#9-health-checking)
10. [API Reference](#10-api-reference)

---

## 1. Route Management

### 1.1 Overview

Routes define how incoming requests are forwarded to backend services.

**Configuration Storage:** Nacos `gateway-routes.json`

### 1.2 Route Structure

```json
{
  "id": "user-service-route",
  "uri": "lb://user-service",
  "order": 0,
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/user/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ],
  "metadata": {
    "timeout": 3000,
    "retry": 3
  }
}
```

### 1.3 URI Schemes

| Scheme | Description | Example |
|--------|-------------|---------|
| `lb://` | Dynamic service discovery via Nacos/Consul | `lb://user-service` |
| `static://` | Static service discovery (custom protocol) | `static://backend-service` |

> Note: `http://` is supported natively by Spring Cloud Gateway (not a custom feature of this project).

### 1.4 API Endpoints

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

## 2. Service Discovery

### 2.1 Dual Protocol Support

The gateway supports two service discovery protocols:

| Protocol | Description | Use Case |
|----------|-------------|----------|
| `lb://` | Native SCG load balancing via Nacos/Consul | Services registered in service registry |
| `static://` | Custom static service discovery | Legacy systems, external APIs, non-registered services |

### 2.2 Dynamic Discovery (`lb://`)

Services registered in Nacos/Consul are automatically discovered using Spring Cloud Gateway's native load balancer.

```yaml
# application.yml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
```

### 2.3 Static Discovery (`static://`) - Highlight Feature

A custom protocol designed for services **not registered in Nacos/Consul**:

```
+------------------------------------------------------------------+
|                    STATIC PROTOCOL FLOW                          |
+------------------------------------------------------------------+

  Route URI: static://my-service
            |
            v
  +---------------------------+
  | StaticProtocolGlobalFilter|  (Order: 10001)
  | - Intercepts static://    |
  | - Converts to lb://       |
  +---------------------------+
            |
            v
  +---------------------------+
  | DiscoveryLoadBalancerFilter| (Order: 10150)
  | - Gets instances from     |
  |   ServiceManager          |
  | - Health-aware selection  |
  | - Weighted Round-Robin    |
  +---------------------------+
            |
            v
  +---------------------------+
  | Backend Instance          |
  | 192.168.1.10:8080        |
  +---------------------------+
```

**Configuration Example:**

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

### 2.4 Weighted Round-Robin Load Balancing

Nginx-style smooth weighted round-robin algorithm:

```
Instances: [A(weight=1), B(weight=2)]
Distribution: A -> B -> B -> A -> B -> B ...  (exact 1:2 ratio)

Algorithm:
1. Add original weight to current weight for each instance
2. Select instance with highest current weight
3. Subtract total weight from selected instance's current weight
```

### 2.5 Health-Aware Routing

**Multi-Instance Service:**
- Unhealthy instances are **automatically skipped** in load balancing
- Traffic routes only to healthy instances

**Single-Instance Service:**
- Unhealthy instance is **kept** for potential auto-recovery
- Allows fast failure with automatic recovery when instance becomes healthy

**Disabled Instances:**
- Instances with `enabled: false` are **excluded** from load balancing

### 2.3 Load Balancing Algorithms

| Algorithm | Description |
|-----------|-------------|
| `round-robin` | Sequential distribution |
| `weighted` | Weight-based distribution |
| `random` | Random selection |

**Weighted Round-Robin Example:**
- Instance A (weight=1), Instance B (weight=2)
- Distribution: A -> B -> B -> A -> B -> B (1:2 ratio)

### 2.4 API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/services` | List all services |
| `GET` | `/api/services/{name}` | Get service details |
| `POST` | `/api/services` | Create service |
| `PUT` | `/api/services/{name}` | Update service |
| `DELETE` | `/api/services/{name}` | Delete service |

---

## 3. Authentication

### 3.1 Overview

Multi-strategy authentication using the Strategy Pattern.

**Filter Order:** -250

### 3.2 Supported Types

| Type | Processor | Use Case |
|------|-----------|----------|
| `JWT` | `JwtAuthProcessor` | Stateless API authentication |
| `API_KEY` | `ApiKeyAuthProcessor` | Simple partner access |
| `BASIC` | `BasicAuthProcessor` | Simple username/password |
| `HMAC` | `HmacSignatureAuthProcessor` | API signature verification |
| `OAUTH2` | `OAuth2AuthProcessor` | Third-party SSO |

### 3.3 Configuration Examples

**JWT Authentication:**
```json
{
  "routeId": "secure-api",
  "authType": "JWT",
  "secretKey": "your-256-bit-secret-key-here",
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

### 3.4 Extending Authentication

Add custom authentication type:

```java
@Component
public class CustomAuthProcessor extends AbstractAuthProcessor {

    @Override
    public AuthType getType() {
        return AuthType.CUSTOM;
    }

    @Override
    public Mono<Boolean> validate(ServerWebExchange exchange, AuthConfig config) {
        // Custom validation logic
        return Mono.just(true);
    }
}
```

---

## 4. Rate Limiting

### 4.1 Overview

Hybrid rate limiting with Redis (distributed) + Local (fallback).

**Filter Order:** -100

### 4.2 Configuration

```json
{
  "routeId": "public-api",
  "qps": 100,
  "timeUnit": "second",
  "burstCapacity": 200,
  "keyType": "ip",
  "enabled": true
}
```

### 4.3 Key Types

| Key Type | Description |
|----------|-------------|
| `ip` | Per-client IP rate limiting |
| `route` | Shared limit per route |
| `combined` | Route + IP combination |
| `header` | Based on header value |

### 4.4 Response Headers

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Maximum requests allowed |
| `X-RateLimit-Remaining` | Remaining requests |
| `X-RateLimit-Reset` | Reset timestamp |

**Exceed Limit:** HTTP 429 Too Many Requests

---

## 5. Circuit Breaker

### 5.1 Overview

Protects downstream services from cascading failures using Resilience4j.

**Filter Order:** -150

### 5.2 State Machine

```
CLOSED (Normal)
    |
    +--> Failure rate > threshold --> OPEN (Reject all)
                                        |
                                        +--> After waitDuration --> HALF_OPEN (Test)
                                                                        |
                                    +-----------------------------------+
                                    |
                                    v
                            Success --> CLOSED
                            Failure --> OPEN
```

### 5.3 Configuration

```json
{
  "routeId": "critical-service",
  "failureRateThreshold": 50.0,
  "slowCallDurationThreshold": 60000,
  "slowCallRateThreshold": 80.0,
  "waitDurationInOpenState": 30000,
  "slidingWindowSize": 10,
  "minimumNumberOfCalls": 5,
  "enabled": true
}
```

### 5.4 Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `failureRateThreshold` | Failure rate % to open circuit | 50 |
| `waitDurationInOpenState` | Time to wait before half-open (ms) | 30000 |
| `slidingWindowSize` | Number of calls in window | 10 |
| `minimumNumberOfCalls` | Minimum calls before calculating rate | 5 |

**Circuit Open:** HTTP 503 Service Unavailable

---

## 6. IP Filtering

### 6.1 Overview

IP blacklist/whitelist with CIDR support.

**Filter Order:** -280 (before authentication for performance)

### 6.2 Modes

| Mode | Description |
|------|-------------|
| `blacklist` | Block listed IPs |
| `whitelist` | Allow only listed IPs |

### 6.3 IP Formats

| Format | Example |
|--------|---------|
| Exact | `192.168.1.100` |
| Wildcard | `192.168.1.*` |
| CIDR | `192.168.1.0/24` |

### 6.4 Configuration

```json
{
  "routeId": "internal-api",
  "mode": "whitelist",
  "ipList": [
    "10.0.0.0/8",
    "192.168.0.0/16",
    "172.16.0.0/12"
  ],
  "enabled": true
}
```

**Blocked:** HTTP 403 Forbidden

### 6.5 Performance Impact

IP filtering before authentication provides **+37% TPS improvement** by rejecting malicious IPs early without JWT validation overhead.

---

## 7. Timeout Control

### 7.1 Overview

Per-route connection and response timeout control.

**Filter Order:** -200

### 7.2 Configuration

```json
{
  "routeId": "slow-api",
  "connectTimeout": 5000,
  "responseTimeout": 30000,
  "enabled": true
}
```

### 7.3 Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `connectTimeout` | TCP connection timeout (ms) | 5000 |
| `responseTimeout` | Full response timeout (ms) | 30000 |

**Timeout:** HTTP 504 Gateway Timeout

---

## 8. Distributed Tracing

### 8.1 Overview

Automatic TraceId generation and propagation across services.

**Filter Order:** -300 (first filter for full visibility)

### 8.2 How It Works

```
Client Request
    |
    v
Gateway generates X-Trace-Id: abc-123
    |
    v
MDC.put("traceId") --> All logs include [traceId=abc-123]
    |
    v
Forward with header: X-Trace-Id: abc-123
    |
    v
Response includes: X-Trace-Id: abc-123
```

### 8.3 Benefits

- End-to-end request visibility
- Log correlation across services
- Performance bottleneck identification
- Compliance audit trail

---

## 9. Health Checking

### 9.1 Overview

Hybrid health checking for `static://` service instances with real-time status sync to admin console.

```
+------------------------------------------------------------------+
|                    HYBRID HEALTH CHECK                           |
+------------------------------------------------------------------+

  +-------------------+           +-------------------+
  |   PASSIVE CHECK  |           |   ACTIVE CHECK   |
  |                   |           |                   |
  | - Request success |           | - TCP port probe |
  | - Request failure |           | - Optional HTTP  |
  | - Consecutive     |           | - Every 30s      |
  |   failures >= 3   |           |                   |
  +---------+---------+           +---------+---------+
            |                               |
            +---------------+---------------+
                            |
                            v
                +-------------------+
                |  Health Status    |
                |  (in memory)      |
                +---------+---------+
                          |
                          | Batch Sync
                          v
                +-------------------+
                |  gateway-admin    |
                |  (visible in UI)  |
                +-------------------+
```

### 9.2 Check Types

| Type | Trigger | Description |
|------|---------|-------------|
| **Passive** | Every request | Record success/failure, mark unhealthy after 3 consecutive failures |
| **Active** | Every 30s | TCP port probe (optionally HTTP health endpoint) |

### 9.3 Passive Health Check

```
Request arrives at gateway
        |
        v
+---------------+
| Route to      |
| instance      |
+-------+-------+
        |
   +----+----+
   |         |
   v         v
Success   Failure (retry 3x)
   |         |
   v         v
Record    Mark unhealthy
success   if failures >= 3
```

### 9.4 Active Health Check

- **Check Interval:** 30 seconds
- **Check Method:** TCP port connectivity (optional HTTP `/actuator/health`)
- **Timeout:** 3 seconds per check

```yaml
gateway:
  health-check:
    timeout: 3000
    http-enabled: false        # Enable HTTP health check
    http-path: /actuator/health
```

### 9.5 Health Status Sync

Instance health status is **automatically synced** to gateway-admin:

- **Batch processing:** Health statuses are batched and pushed to admin
- **Visible in UI:** Users can see instance health status in the web dashboard
- **Network flap detection:** Mass state changes are detected and logged

### 9.6 Health-Aware Routing Behavior

| Scenario | Behavior |
|----------|----------|
| **Multi-instance service** | Unhealthy instances are skipped in load balancing |
| **Single-instance service** | Unhealthy instance is kept for potential auto-recovery |
| **Disabled instance** | Excluded from load balancing regardless of health |
| **Recovery** | Auto-recover when health check passes |

### 9.7 Configuration

```yaml
gateway:
  health:
    failure-threshold: 3        # Mark unhealthy after N failures
    recovery-time: 30000        # Auto-recovery check interval (ms)
    idle-threshold: 300000      # Idle instance check threshold (ms)
    batch-size: 50              # Batch sync size to admin
    network-flap-threshold: 10  # Network flap detection threshold
```

---

## 10. API Reference

### 10.1 Route API

```bash
# List all routes
GET /api/routes

# Create route
POST /api/routes
Content-Type: application/json
{
  "id": "my-route",
  "uri": "lb://my-service",
  "predicates": [{"name": "Path", "args": {"pattern": "/api/**"}}]
}

# Update route
PUT /api/routes/{id}

# Delete route
DELETE /api/routes/{id}
```

### 10.2 Service API

```bash
# List all services
GET /api/services

# Create static service
POST /api/services
Content-Type: application/json
{
  "name": "backend",
  "instances": [{"ip": "127.0.0.1", "port": 8080}]
}

# Delete service
DELETE /api/services/{name}
```

### 10.3 Strategy API

```bash
# Authentication
GET  /api/strategies/auth
POST /api/strategies/auth

# Rate Limiter
GET  /api/strategies/rate-limiter
POST /api/strategies/rate-limiter

# Circuit Breaker
GET  /api/strategies/circuit-breaker
POST /api/strategies/circuit-breaker

# IP Filter
GET  /api/strategies/ip-filter
POST /api/strategies/ip-filter

# Timeout
GET  /api/strategies/timeout
POST /api/strategies/timeout
```

---

## 11. Filter Chain Summary

```
Request Flow:
+----------------------------------------------------------+
| Security (-500)        --> Hardening first                |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| AccessLog (-400)       --> Log all requests               |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| TraceId (-300)         --> Full visibility                |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| CORS (-300)            --> Handle preflight               |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| IP Filter (-280)       --> Fast rejection (coarse)        |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| Authentication (-250)  --> User identity (fine)           |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| Timeout (-200)         --> Protect downstream             |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| Retry (-200)           --> Retry on failure               |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| Circuit Breaker (-100) --> Prevent cascade failure        |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| Header Op (-50)        --> Add/modify headers             |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| Cache (50)             --> Response caching               |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| Static Protocol (10001)--> Transform static://            |
+----------------------------------------------------------+
            |
            v
+----------------------------------------------------------+
| Load Balancer (10150)  --> Forward to backend             |
+----------------------------------------------------------+
```

**Design Philosophy:**
1. Observability first (TraceId sees everything)
2. Coarse before fine (IP filter before auth)
3. Protection before function (Timeout/CB before routing)
4. Fast failure (Reject early, save resources)

---

## 12. Configuration Files

| File | Description |
|------|-------------|
| `gateway-routes.json` | Route definitions |
| `gateway-services.json` | Static service instances |
| `gateway-strategies.json` | All strategy configurations |

---

For architecture details, see [ARCHITECTURE.md](ARCHITECTURE.md).