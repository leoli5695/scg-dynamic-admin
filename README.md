# API Gateway Management Platform

> **Enterprise-Grade API Gateway with Dynamic Configuration, Multi-Service Routing & Intelligent Monitoring**

**📺 [Watch Full Feature Demo on YouTube](https://youtu.be/u4CjBDdKTQ4)**

**📸 [View Feature Screenshots](docs/images/SCREENSHOTS.md)** - If you prefer screenshots over video demo, browse 66 annotated screenshots covering all features.

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-4.3.5-blue)](https://spring.io/projects/spring-cloud-gateway)
[![React](https://img.shields.io/badge/React-19-blue)](https://react.dev/)
[![Tests](https://img.shields.io/badge/Tests-see%20Test%20Status-yellow)](#testing)
[![License](https://img.shields.io/badge/License-All__Rights__Reserved-lightgrey)](LICENSE)

---

## Why This Gateway?

**The problem with traditional API gateways:**

| Challenge | Traditional Gateway | Our Solution |
|-----------|-------------------|--------------|
| **Config Changes** | Restart required | Hot-reload via listeners + periodic reconciliation |
| **Gray Release** | Complex setup | Visual configuration, instant deploy |
| **Monitoring** | Separate tools | Built-in dashboards + AI analysis |
| **SSL Certificates** | Manual management | Auto-discovery + expiry alerts |
| **Multi-Service Routing** | Not supported | Weighted routing with fine-grained rules |
| **Kubernetes Deploy** | Manual YAML editing | One-click deployment from UI |
| **Multi-Tenancy** | Shared configuration | Namespace isolation per instance |

---

## Key Features at a Glance

### Core Gateway Capabilities

| Feature | Description |
|---------|-------------|
| **Dynamic Route Management** | Create, update, delete routes without restart - changes propagate via listeners + periodic reconciliation |
| **Multi-Service Routing** | Route to multiple backend services with weight-based load balancing |
| **Gray Release (Canary Deploy)** | Header/Cookie/Query/Weight-based traffic splitting for safe deployments |
| **Service Discovery** | Nacos/Consul integration with `lb://` protocol, plus static services via `static://` |
| **Load Balancing** | Weighted round-robin, consistent hash, random strategies with health awareness |
| **SSL Termination** | HTTPS on port 8443 with dynamic certificate loading and multi-domain support |

### Security & Protection

| Feature | Description |
|---------|-------------|
| **Multi-Auth Support** | JWT, API Key, Basic Auth, HMAC Signature, OAuth2 - mix and match per route |
| **IP Filtering** | Blacklist/whitelist with CIDR support - block malicious IPs before auth |
| **Rate Limiting** | Redis + local hybrid with graceful degradation when Redis fails |
| **Circuit Breaker** | Resilience4j integration - protect downstream from cascading failures |
| **Security Hardening** | Built-in XSS, SQL injection, CSRF protection |

### Advanced Traffic Management (New)

| Feature | Description |
|---------|-------------|
| **Multi-Dimensional Rate Limiting** | Hierarchical rate limits: Global → Tenant → User → IP with Redis + local fallback |
| **Request Body Transformation** | Protocol conversion (JSON↔XML), field mapping, data masking for sensitive fields |
| **Response Body Transformation** | Transform backend responses before returning to clients |
| **Request Validation** | JSON Schema validation, required field checks, type constraints |
| **Mock Response** | Static/dynamic/template responses for frontend-backend collaboration, with delay/error simulation |

### Observability & Operations

| Feature | Description |
|---------|-------------|
| **Real-time Monitoring** | JVM, CPU, memory, HTTP metrics with historical charts |
| **Request Tracing** | Capture error/slow requests with full headers and replay capability |
| **AI-Powered Analysis** | Integration with GPT, Claude, Qwen for intelligent metrics analysis |
| **AI Copilot** | 35+ tools for autonomous route creation, error analysis, performance optimization |
| **Filter Chain Analysis** | Self-time tracking, P50/P95/P99 percentiles, slow request detection |
| **Historical Comparison** | Period-over-period analysis, trend detection, performance prediction |
| **Anomaly Detection** | AI-powered detection of performance anomalies and unusual patterns |
| **Alert System** | Configurable thresholds with email notifications |
| **SSL Certificate Management** | Upload, monitor expiry, get alerts before certificates expire |
| **Audit Logs** | Configuration change tracking with diff comparison and rollback |
| **Stress Test Tool** | Concurrent load testing with AI analysis, export reports (PDF/Excel/JSON/Markdown), and shareable links |

### Kubernetes & Multi-Tenancy (New)

| Feature | Description |
|---------|-------------|
| **Gateway Instance Management** | Deploy and manage multiple gateway instances from single admin console |
| **Kubernetes Integration** | Deploy gateways to K8s clusters with one click, view pod status in UI |
| **Namespace Isolation** | Each gateway instance has isolated Nacos namespace for configuration |
| **Heartbeat Monitoring** | Real-time health status with heartbeat-based detection |
| **Resource Specs** | Pre-defined specs (small/medium/large) or custom CPU/memory configurations |

### Performance Optimizations

| Feature | Description |
|---------|-------------|
| **JWT Validation Cache** | O(1) cached claims lookup, ~90% reduction in verification overhead |
| **Shadow Quota Failover** | Smooth traffic degradation when Redis fails (no traffic spike) |
| **Non-Blocking Locks** | CAS + tryLock strategy prevents EventLoop thread blocking |
| **Connection Pool** | Optimized Netty connection pool with lifecycle management |
| **Hybrid Health Check** | Passive checks with Caffeine cache, zero overhead for healthy instances |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           CONTROL PLANE                                   │
│  ┌───────────────────────────────────────────────────────────────────┐   │
│  │                     gateway-admin (:9090)                          │   │
│  │                                                                     │   │
│  │   React UI ──▶ REST API ──▶ MySQL ──▶ Nacos (Config Push)         │   │
│  │                                                                     │   │
│  │   Features: Route Mgmt, Service Mgmt, SSL Certs, Alerts,           │   │
│  │             Request Traces, AI Copilot, Filter Chain Analysis,    │   │
│  │             Historical Comparison, Audit Logs, K8s Deploy,        │   │
│  │             Gateway Instance Management, Strategy Types           │   │
│  └───────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ Config Push + Periodic Sync
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            DATA PLANE                                     │
│  ┌───────────────────────────────────────────────────────────────────┐   │
│  │                       my-gateway (:80, :8443)                      │   │
│  │                                                                     │   │
│  │   Request ──▶ Filter Chain ──▶ Backend Services                   │   │
│  │                                                                     │   │
│  │   Filters: Security ▶ IP Filter ▶ Access Log ▶ CORS ▶ TraceID      │   │
│  │            ▶ FilterChainTracking ▶ Req Transform ▶ Validation      │   │
│  │            ▶ Auth ▶ Mock ▶ Timeout ▶ API Ver ▶ CB ▶ HeaderOp       │   │
│  │            ▶ Resp Transform ▶ Cache ▶ Retry ▶ Multi-Service LB    │   │
│  │            ▶ Discovery LB                                          │   │
│  └───────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

Configuration changes are applied through listeners and periodic reconciliation. The reconciliation interval defaults to 60 seconds (`gateway.config-sync.interval-ms` / `GATEWAY_CONFIG_SYNC_INTERVAL_MS`). When listener delivery fails, allow up to one interval plus fetch and apply time; config-center outages delay updates until recovery.

**Key Benefits:**
- Zero-downtime configuration updates
- Independent scaling of control and data planes
- Hot-reload routes, services, and strategies
- Multi-tenancy with namespace isolation

---

## Resilience & High Availability

### Nacos Failure Fallback (Snapshot Cache)

When Nacos config center becomes unavailable, the gateway gracefully degrades using snapshot cache:

```
┌───────────────────────────────────────────────────────────────────────┐
│                        StrategyManager                                  │
│                                                                         │
│   ┌─────────────────┐         ┌─────────────────┐                      │
│   │  Primary Cache  │         │ Snapshot Backup │                      │
│   │ (ConcurrentHashMap)│◄────│ (Deep Copy)     │                      │
│   │                 │  sync   │                 │                      │
│   └─────────────────┘         └─────────────────┘                      │
│           │                           │                                 │
│           ▼                           ▼                                 │
│   Nacos Healthy ──► Use Primary    Nacos Down ──► Use Snapshot        │
│                                                                         │
│   Features:                                                             │
│   - markConfigCenterUnhealthy() ──► Switch to snapshot mode            │
│   - createSnapshot() ──► Persist known-good state after refresh        │
│   - restoreFromSnapshotIfNeeded() ──► Recover when cache corrupted     │
└───────────────────────────────────────────────────────────────────────┘
```

**Flow:**
1. Normal operation: Fresh config from Nacos, snapshot created after each refresh
2. Nacos failure: Mark unhealthy, use snapshot for routing decisions
3. Nacos recovery: Mark healthy, create new snapshot, resume normal operation

### Redis Rate Limiter Fallback (Shadow Quota)

Redis failure triggers graceful degradation to local rate limiting with shadow quota:

```
┌───────────────────────────────────────────────────────────────────────┐
│                     HybridRateLimiterFilter                            │
│                                                                         │
│   Redis Available?                                                      │
│        │                                                                │
│        ├── Yes ──► Redis Distributed Rate Limiting                      │
│        │              (DistributedRateLimiter)                          │
│        │                                                                │
│        └── No ──► Local Rate Limiting + Shadow Quota                    │
│                     (RateLimiterWindow)                                │
│                                                                         │
│   ShadowQuotaManager:                                                   │
│   - Tracks route QPS during Redis outage                               │
│   - Provides reduced quota (e.g., 80% of configured)                   │
│   - Gradual recovery when Redis comes back                             │
│   - Probabilistic routing during recovery phase                        │
└───────────────────────────────────────────────────────────────────────┘
```

### Non-Blocking Lock Optimization (CAS + tryLock)

Local rate limiter uses hybrid locking strategy to avoid blocking EventLoop threads:

```java
// Fast path: CAS for low contention (optimistic)
if (currentCount.compareAndSet(count, count + 1)) {
    return true;  // Success without blocking
}

// Slow path: tryLock for high contention (never blocks!)
if (lock.tryLock()) {
    try {
        // Double-check under lock
        if (count < maxRequests) {
            currentCount.incrementAndGet();
            return true;
        }
        return false;
    } finally {
        lock.unlock();
    }
}

// tryLock failed - immediately reject without blocking
return false;  // Prevents EventLoop thread starvation
```

**Benefits:**
- No thread blocking in reactive context
- High throughput under normal load (CAS)
- Safe degradation under extreme contention (tryLock)
- Prevents EventLoop starvation

---

## Gateway Instance Management

### Overview

The platform supports deploying and managing multiple gateway instances, each with its own isolated configuration namespace.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        gateway-admin (Control Plane)                     │
│                                                                          │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │
│   │  Instance 1  │  │  Instance 2  │  │  Instance 3  │                  │
│   │  (dev)       │  │  (staging)   │  │  (prod)      │                  │
│   └──────┬───────┘  └──────┬───────┘  └──────┬───────┘                  │
│          │                 │                 │                           │
│          ▼                 ▼                 ▼                           │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │
│   │ Nacos NS:    │  │ Nacos NS:    │  │ Nacos NS:    │                  │
│   │ gateway-dev  │  │ gateway-stg  │  │ gateway-prod │                  │
│   └──────────────┘  └──────────────┘  └──────────────┘                  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ Deploy to Kubernetes
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Kubernetes Cluster                                │
│                                                                          │
│   Namespace: gateway-dev        Namespace: gateway-stg                  │
│   ┌─────────────────────┐       ┌─────────────────────┐                 │
│   │  ┌───┐ ┌───┐ ┌───┐  │       │  ┌───┐ ┌───┐        │                 │
│   │  │Pod│ │Pod│ │Pod│  │       │  │Pod│ │Pod│        │                 │
│   │  └───┘ └───┘ └───┘  │       │  └───┘ └───┘        │                 │
│   │     my-gateway      │       │     my-gateway      │                 │
│   └─────────────────────┘       └─────────────────────┘                 │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Instance Configuration

| Spec Type | CPU | Memory | Replicas | Use Case |
|-----------|-----|--------|----------|----------|
| `small` | 0.5 core | 512MB | 1 | Development |
| `medium` | 1 core | 1GB | 2 | Staging |
| `large` | 2 cores | 2GB | 3 | Production |
| `xlarge` | 4 cores | 4GB | 5 | High-traffic Production |
| `custom` | Custom | Custom | Custom | Special requirements |

### Heartbeat Monitoring

Each gateway instance sends heartbeats to the admin service:
- **Running**: Heartbeat received within 30 seconds
- **Warning**: Missed 1-2 heartbeats (30-60 seconds)
- **Error**: Missed 3+ heartbeats (> 60 seconds)

---

## Multi-Service Routing & Gray Release

### The Problem

Traditional gateways route all traffic to a single backend. But what if you want to:
- Test a new version with 5% of users?
- Route internal users to a staging environment?
- Gradually shift traffic from v1 to v2?

### Our Solution

```json
{
  "mode": "MULTI",
  "services": [
    {"serviceId": "user-v1", "weight": 90, "serviceName": "User Service v1"},
    {"serviceId": "user-v2", "weight": 10, "serviceName": "User Service v2"}
  ],
  "grayRules": {
    "rules": [
      {
        "type": "HEADER",
        "name": "X-Version",
        "value": "v2",
        "targetVersion": "user-v2"
      },
      {
        "type": "WEIGHT",
        "value": "10",
        "targetVersion": "user-v2"
      }
    ]
  }
}
```

**Result:**
- Requests with `X-Version: v2` header → Always go to v2
- Other requests → 90% to v1, 10% to v2

### Supported Rule Types

| Type | Match By | Use Case |
|------|----------|----------|
| `HEADER` | HTTP header value | Internal users, beta testers |
| `COOKIE` | Cookie value | User segments, A/B testing |
| `QUERY` | URL parameter | Campaign tracking |
| `WEIGHT` | Percentage | Gradual rollout |

---

## SSL Termination

### Dynamic Certificate Loading

```yaml
gateway:
  ssl:
    enabled: true
    port: 8443
    cert-path: /opt/certificates
```

**Features:**
- Hot-reload certificates without restart
- Multi-domain support with SNI
- PEM, PKCS12, JKS formats
- Expiry monitoring with email alerts

---

## AI-Powered Analysis

### Integrated AI Providers

| Provider | Models | Best For |
|----------|--------|----------|
| OpenAI | GPT-4, GPT-3.5 | General analysis |
| Anthropic | Claude 3 | Detailed explanations |
| Qwen | qwen-plus, qwen-turbo | Cost-effective |
| DeepSeek | deepseek-chat | Reasoning |
| Ollama | llama2, mistral | Local deployment |

### AI Analysis Features

- **Real-time Metrics Analysis**: Upload current metrics, get insights
- **Alert Content Generation**: AI-written alert emails with recommendations
- **Anomaly Detection**: Identify unusual patterns in HTTP traffic

---

## Monitoring & Alerts

### Built-in Metrics

| Category | Metrics |
|----------|---------|
| **JVM** | Heap usage, GC count/time, thread count |
| **System** | CPU usage (process/system), memory |
| **HTTP** | Requests/sec, avg response time, error rate |
| **Status** | 2xx/4xx/5xx distribution |

### Alert Thresholds

```yaml
alerts:
  cpu:
    process-threshold: 80%
    system-threshold: 90%
  memory:
    heap-threshold: 85%
  http:
    error-rate-threshold: 5%
    response-time-threshold: 2000ms
```

---

## Quick Start

### Prerequisites

- Java 21+ (`pom.xml` sets `java.version=21` for every module; the images are `eclipse-temurin:21`)
- Node.js 18+
- MySQL 8.0+
- Nacos 2.3.0 (the version `docker-compose.yml` and `k8s/nacos.yaml` pin) or Consul
- Redis (optional, for distributed rate limiting)
- Kubernetes (optional, for instance deployment)

### 1. Start Infrastructure

```bash
# Start Nacos (standalone mode)
docker run -d --name nacos -p 8848:8848 -e MODE=standalone nacos/nacos-server:v2.3.0

# Start Redis (optional)
docker run -d --name redis -p 6379:6379 redis:7

# Start MySQL
docker run -d --name mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8
```

### 2. Start Backend Services

```bash
# Start Admin Service (Control Plane)
cd gateway-admin
mvn spring-boot:run

# Start Gateway (Data Plane)
cd my-gateway
mvn spring-boot:run

# Start Demo Service (for testing)
cd demo-service
mvn spring-boot:run
```

### 3. Start Frontend

```bash
cd gateway-ui
npm install
npm run dev
```

### 4. Access the Dashboard

Open http://localhost:3000 in your browser.

Login with the `ADMIN_USERNAME` / `ADMIN_PASSWORD` you set in `.env`. There is **no** built-in
`admin` / `admin123` account: `AuthService` creates the initial administrator only when both
environment variables are present, the password must be at least 12 characters, and there is no
self-service registration - so if they are missing the console has no user at all.

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| **Gateway Core** | Spring Cloud Gateway 4.3.5, Project Reactor |
| **Framework** | Spring Boot 3.5.16, Spring Security 6.5 |
| **Config Center** | Nacos 2.3.0 / Consul (SPI switchable) |
| **Circuit Breaker** | Resilience4j 2.1 |
| **Rate Limiting** | Redis + Caffeine (hybrid) |
| **Authentication** | JJWT 0.12 |
| **Database** | MySQL 8, Spring Data JPA |
| **Monitoring** | Prometheus, Micrometer |
| **Frontend** | React 19, TypeScript, Ant Design 6, Vite |
| **Container** | Docker, Kubernetes |

---

## Testing

Counts below were measured with `mvn -o test` on 2026-10-28 at commit `ed93868` (JDK 21, offline).
"Errors" are tests that could not run at all because an external dependency is absent; the suite
has zero assertion failures.

| Module | Tests | Failures | Errors | Status |
|--------|-------|----------|--------|--------|
| **my-gateway** | 973 | 0 | 0 | ✅ All passing (2 skipped) |
| **gateway-admin** | 547 | 0 | 80 | ⚠️ 63 of 75 classes green locally; the other 12 are `@SpringBootTest` integration tests that need a reachable MySQL - without one they all fail at context load with `Communications link failure` |
| **gateway-trace-starter** | 160 | 0 | 0 | ✅ All passing |
| **license-client** | 83 | 0 | 0 | ✅ All passing |
| **license-server** | 40 | 0 | 0 | ✅ All passing |
| **seckill-core-engine** | 217 | 0 | 4 | ⚠️ 3 need a bare Redis on `localhost:30379`; 1 (`LuaSlidingWindowRateLimitTest`) needs Docker via Testcontainers (4 skipped) |
| **gateway-ui** | 5 | - | - | ⚠️ Route-contract tests only (`npm run test:routes`); no component coverage |
| **Total (JVM)** | **2020** | **0** | **84** | ⚠️ Not reproducible on a clean machine without MySQL:3306, Redis:30379 and Docker |

CI (`.github/workflows/ci.yml`) runs on every push to `main` and starts real MySQL 8, Redis 7 and
Nacos v2.3.0 service containers, so the 84 errors above do not occur there - the Actions tab is the
authoritative verdict, and it is also the only place those 12 `@SpringBootTest` classes and the 3
Redis-backed seckill tests actually execute.

Counts are the surefire aggregate lines, not a sum of the per-class reports: `@Nested` classes
(133 in my-gateway, 27 in gateway-admin) make a per-class sum undercount.

`npm run test:routes` imports `src/features/routes/utils.ts` directly, which needs Node's type
stripping - Node 22.18 or newer. CI asks `setup-node` for `'22'`, which floats to a version that
has it; an older local Node 22 fails with `ERR_UNKNOWN_FILE_EXTENSION` rather than reporting a
test failure.

The old badge claimed an exact "1674 Tests"; that figure was padded by three `@RepeatedTest(100)`
methods in `GatewayTracePropertiesTest` whose only assertion was `assertNotNull` on a primitive
`boolean` - 300 executions, i.e. 18% of the total, that could never fail. They now assert the
observed sampling rate instead of running the same tautology a hundred times, which is also why
gateway-trace-starter reads 160 here rather than the 444 an older revision of this table claimed.

Run tests:
```bash
# Run all tests
cd gateway-admin && mvn test
cd my-gateway && mvn test
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [QUICK_START.md](docs/QUICK_START.md) | Step-by-step setup guide |
| [FEATURES.md](docs/FEATURES.md) | Complete feature documentation with examples |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture & design patterns |

---

## Gateway Trace Starter - Minimal Intrusion Distributed Tracing

### Overview

`gateway-trace-starter` provides a **zero-code-intrusion** distributed tracing solution for Spring Boot applications. By simply adding the dependency and configuring the admin URL, your service will automatically:

- Generate and propagate TraceId across the entire call chain
- Trace Redis, RocketMQ, MySQL, Elasticsearch, Feign operations
- Report middleware metadata for AI-powered observability
- Support async thread Trace propagation (@Async, CompletableFuture)

**Key Features:**

| Feature | Description |
|---------|-------------|
| **Zero Intrusion** | No code changes required, just dependency + config |
| **Auto Trace Propagation** | TraceId flows through HTTP, Feign, async threads |
| **Middleware Tracing** | Auto-trace Redis, MQ, DB, ES operations |
| **Graceful Degradation** | Fallback to local disk when admin is unavailable |
| **Batch Reporting** | Async batch reporting for high throughput |

### Quick Start

#### 1. Add Dependency

```xml
<dependency>
    <groupId>com.leoli.gateway</groupId>
    <artifactId>gateway-trace-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### 2. Configure YAML

```yaml
# Required: Gateway Admin URL
gateway:
  trace:
    admin-url: http://gateway-admin:9090

# Optional configurations (with defaults)
    enabled: true                    # Enable tracing (default: true)
    service-name: my-service         # Service name (default: spring.application.name)
    sample-rate: 1.0                 # Sampling rate 0.0-1.0 (default: 1.0)
    trace-redis: true                # Trace Redis operations (default: true)
    trace-mq: true                   # Trace RocketMQ operations (default: true)
    trace-db: true                   # Trace DB operations (default: true)
    async-trace-enabled: false       # Enable async thread propagation (default: false)
```

**Minimal Configuration (all you need):**

```yaml
gateway:
  trace:
    admin-url: http://gateway-admin:9090
```

#### 3. That's It!

No code changes required. Your service is now part of the distributed tracing system.

### What Gets Traced Automatically

| Component | Tracing Method | TraceId Propagation |
|-----------|---------------|---------------------|
| **HTTP Inbound** | WebInterceptor | Extract from header / Generate new |
| **HTTP Outbound (Feign)** | FeignInterceptor | Inject into request header |
| **Redis** | RedisTraceInterceptor | Wrap operations with span |
| **RocketMQ** | RocketMQTraceInterceptor | Inject into message property |
| **MySQL/JDBC** | DataSourceTraceInterceptor | Wrap SQL execution |
| **Elasticsearch** | ElasticsearchTraceInterceptor | Trace search/index ops |
| **@Async Methods** | TraceTaskExecutor | Propagate to async threads |

### Middleware Metadata Reporting

The starter also reports middleware metadata (connection pool, exporters) for AI-powered observability:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Your Service                                  │
│                                                                  │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│   │ Redis       │  │ RocketMQ    │  │ MySQL       │             │
│   │ Connection  │  │ Producer    │  │ DataSource  │             │
│   │ Pool Stats  │  │ Stats       │  │ Pool Stats  │             │
│   └─────────────┘  └─────────────┘  └─────────────┘             │
│          │               │               │                       │
│          └───────────────┼───────────────┘                       │
│                          ▼                                       │
│              ┌─────────────────────┐                             │
│              │ Trace Reporter      │                             │
│              │ (Async Batch)       │                             │
│              └─────────────────────┘                             │
│                          │                                       │
│                          ▼                                       │
│              ┌─────────────────────┐                             │
│              │ Gateway Admin       │                             │
│              │ (AI Analysis Ready) │                             │
│              └─────────────────────┘                             │
└─────────────────────────────────────────────────────────────────┘
```

### Async Thread Trace Propagation

Enable async thread propagation for `@Async` methods:

```yaml
gateway:
  trace:
    async-trace-enabled: true
```

This auto-configures `TraceTaskExecutor` that propagates TraceId to:

- `@Async` annotated methods
- `CompletableFuture` executions
- Custom thread pool tasks

### Recommended Production Configuration

```yaml
gateway:
  trace:
    admin-url: http://gateway-admin:9090
    sample-rate: 0.1               # 10% sampling for high traffic
    async-queue-size: 2000         # Larger queue for burst traffic
    report-batch-size: 200         # Larger batch for efficiency
    async-trace-enabled: true      # Enable async propagation
```

---

## Demo Service & Seckill Service

### demo-service

A sample backend service designed for **gateway testing**. Provides endpoints to test:

- Route matching and load balancing
- Authentication and authorization flows
- Rate limiting behavior
- Circuit breaker triggers
- Timeout handling
- Multi-service routing

```bash
# Start demo service
cd demo-service
mvn spring-boot:run

# Test endpoints
curl http://localhost:9000/api/user/info
curl http://localhost:9000/api/order/list
```

### seckill-core-engine

A **high-concurrency seckill (flash sale) service** showcasing:

| Feature | Implementation |
|---------|---------------|
| **Stock Deduction** | Redis Lua script (atomic + sharded) |
| **Order Creation** | RocketMQ transaction message |
| **Anti-Duplicate** | Redis SISMEMBER + DB unique index |
| **Rate Limiting** | Redis distributed + local fallback |
| **Batch Insert** | Async buffering + batch DB write |
| **Graceful Degradation** | Redis/MQ failover with local fallback |

**Architecture:**

```
┌─────────────────────────────────────────────────────────────────┐
│                    Seckill Request Flow                          │
│                                                                  │
│   Request ──▶ Lua Deduct Stock ──▶ MQ Transaction Msg           │
│                   │                      │                       │
│                   ▼                      ▼                       │
│              Redis Shards          Transaction Log              │
│              (Atomic Ops)          (PROCESSING)                 │
│                                          │                       │
│                                          ▼                       │
│                                   Batch Insert Orders            │
│                                          │                       │
│                                          ▼                       │
│                                   Update Transaction (SUCCESS)   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Project Structure

```
.
├── my-gateway/                 # Core gateway runtime (Data Plane)
│   └── src/main/java/
│       ├── filter/             # Global filters (organized by category)
│       │   ├── security/       # SecurityGlobalFilter, IPFilterGlobalFilter,
│       │   │                   # AuthenticationGlobalFilter, CorsGlobalFilter
│       │   ├── loadbalancer/   # DiscoveryLoadBalancerFilter, MultiServiceLoadBalancerFilter,
│       │   │                   # InstanceFilter, InstanceSelector, InstanceRetryExecutor
│       │   ├── ratelimit/      # HybridRateLimiterFilter, MultiDimRateLimiterFilter
│       │   ├── resilience/     # CircuitBreakerGlobalFilter, TimeoutGlobalFilter, RetryGlobalFilter
│       │   ├── transform/      # RequestTransformFilter, RequestValidationFilter,
│       │   │                   # ResponseTransformFilter, MockResponseFilter
│       │   └── *.java          # AccessLogGlobalFilter, CacheGlobalFilter, TraceIdGlobalFilter, etc.
│       ├── auth/               # Authentication processors + JwtValidationCache
│       ├── constants/          # FilterOrderConstants, GatewayConfigConstants
│       ├── exception/          # Custom exceptions (AuthenticationException, RateLimitException, etc.)
│       ├── ssl/                # SSL certificate management
│       ├── center/             # Config center SPI
│       ├── discovery/          # Service discovery SPI
│       ├── health/             # Health check & heartbeat
│       └── limiter/            # Rate limiting (Redis + Local)
│
├── gateway-admin/              # Management console (Control Plane)
│   └── src/main/java/
│       ├── controller/         # REST API endpoints
│       ├── service/            # Business logic
│       ├── repository/         # JPA repositories
│       ├── center/             # Nacos/Consul config publishers
│       ├── reconcile/          # Config reconciliation tasks
│       └── cache/              # Instance namespace cache
│
├── gateway-ui/                 # Web dashboard frontend (React 19 + Vite)
│   └── src/
│       ├── features/           # Feature-sliced pages and components (auth, instances,
│       │                       #   routes, services, strategies, trace, copilot, ...)
│       ├── api/                # HTTP client and per-domain API modules
│       ├── components/         # Cross-feature reusable components
│       ├── hooks/              # useRequest / usePolling (abort + monotonic seq race guard)
│       ├── types/              # Hand-written mirrors of the backend DTOs
│       ├── locales/            # EN / CN translation namespaces
│       ├── styles/             # Global styles
│       ├── utils/              # logger, formatters, access-log escaping
│       ├── i18n.ts             # Internationalization (EN/CN)
│       └── theme.ts            # Ant Design theme tokens
│
├── gateway-ui-2/               # Vue 3 exploration branch - NOT deployed, see its STATUS.md
│
├── docs/                       # Documentation
│   ├── features/               # Feature-specific documentation
│   ├── operations/             # Deployment & operations guides
│   ├── images/                 # Screenshots & diagrams
│   ├── ARCHITECTURE.md         # System architecture
│   ├── FEATURES.md             # Feature index
│   └── QUICK_START.md          # Setup guide
│
├── demo-service/               # Sample backend service (for gateway testing)
│
├── seckill-core-engine/        # Seckill service (high-concurrency demo)
│
├── gateway-trace-starter/      # Distributed tracing starter (minimal intrusion)
│
├── k8s/                        # Kubernetes deployment manifests
│   ├── nacos.yaml              # Nacos deployment
│   ├── redis.yaml              # Redis deployment
│   ├── my-gateway.yaml         # Gateway deployment
│   └── prometheus.yaml         # Prometheus monitoring
│
├── prometheus/                 # Prometheus configuration
│
└── scripts/                    # Utility scripts
```

---

## License

**Proprietary - All Rights Reserved.** See [LICENSE](LICENSE) for the full terms.

You may use, copy and modify this software for personal learning, non-commercial research and
development, and evaluation/testing. You may **not** redistribute, sublicense or sell it, and you
may **not** deploy it in production without a valid license agreement.

The `license-server` / `license-client` modules enforce the edition and quota tiers. Note that a
gateway started without a reachable license server runs on the BASIC fallback edition (1 instance,
10 routes, 5 services, 100 QPS), and `LICENSE_ENABLED=false` does **not** unlock features - the
admin console consults the gatekeeper unconditionally, so ENTERPRISE-only surfaces such as audit
export stay blocked either way.

For licensing inquiries: https://github.com/leoli5695

---

## Support

- **Issues**: [GitHub Issues](https://github.com/leoli5695/scg-dynamic-admin/issues)
- **Discussions**: [GitHub Discussions](https://github.com/leoli5695/scg-dynamic-admin/discussions)

---

## Author

**Leo Li** - Full-Stack Developer specializing in Microservices, API Gateway, and Cloud-Native Architecture.

**Core Competencies:**
- ✓ Microservices Architecture Design (Spring Cloud, Dubbo)
- ✓ API Gateway Development (Spring Cloud Gateway, Kong)
- ✓ Kubernetes Deployment & DevOps Automation
- ✓ High-Availability Patterns (Rate Limiting, Circuit Breaker, Failover)
- ✓ Full-Stack Development (Java Backend + React Frontend)
- ✓ AI Integration for Intelligent Operations

All core features of this project were designed and implemented independently, demonstrating production-grade distributed system development capability.

**Contact:**
- GitHub: [@leoli5695](https://github.com/leoli5695)
- Email: lizhao5695@gmail.com
- Upwork: [Hire Me on Upwork](https://www.upwork.com/freelancers/~017be8c63f36907379)

Available for freelance projects. Feel free to reach out!