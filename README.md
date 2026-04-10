# API Gateway Management Platform

> **Enterprise-Grade API Gateway with Dynamic Configuration, Multi-Service Routing & Intelligent Monitoring**

[![Java](https://img.shields.io/badge/Java-17+-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-4.1-blue)](https://spring.io/projects/spring-cloud-gateway)
[![React](https://img.shields.io/badge/React-19-blue)](https://react.dev/)
[![Tests](https://img.shields.io/badge/Tests-500%20Passing-success)]()
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

---

## Why This Gateway?

**The problem with traditional API gateways:**

| Challenge | Traditional Gateway | Our Solution |
|-----------|-------------------|--------------|
| **Config Changes** | Restart required | Hot-reload in < 1 second |
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
| **Dynamic Route Management** | Create, update, delete routes without restart - changes propagate in < 1 second |
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
| **Alert System** | Configurable thresholds with email notifications |
| **SSL Certificate Management** | Upload, monitor expiry, get alerts before certificates expire |

### Kubernetes & Multi-Tenancy (New)

| Feature | Description |
|---------|-------------|
| **Gateway Instance Management** | Deploy and manage multiple gateway instances from single admin console |
| **Kubernetes Integration** | Deploy gateways to K8s clusters with one click, view pod status in UI |
| **Namespace Isolation** | Each gateway instance has isolated Nacos namespace for configuration |
| **Heartbeat Monitoring** | Real-time health status with heartbeat-based detection |
| **Resource Specs** | Pre-defined specs (small/medium/large) or custom CPU/memory configurations |

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
│  │             Request Traces, AI Analysis, K8s Deploy,               │   │
│  │             Gateway Instance Management                            │   │
│  └───────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ Config Push (< 1 second)
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            DATA PLANE                                     │
│  ┌───────────────────────────────────────────────────────────────────┐   │
│  │                       my-gateway (:80, :8443)                      │   │
│  │                                                                     │   │
│  │   Request ──▶ Filter Chain ──▶ Backend Services                   │   │
│  │                                                                     │   │
│  │   Filters: Security ▶ IP Filter ▶ Access Log ▶ CORS ▶ TraceID      │   │
│  │            ▶ Auth ▶ Rate Limit ▶ Req Transform ▶ Validation        │   │
│  │            ▶ Mock ▶ CB ▶ Resp Transform ▶ LB                       │   │
│  └───────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key Benefits:**
- Zero-downtime configuration updates
- Independent scaling of control and data planes
- Hot-reload routes, services, and strategies
- Multi-tenancy with namespace isolation

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

- Java 17+
- Node.js 18+
- MySQL 8.0+ (or use embedded H2)
- Nacos 2.4.3+ (or Consul)
- Redis (optional, for distributed rate limiting)
- Kubernetes (optional, for instance deployment)

### 1. Start Infrastructure

```bash
# Start Nacos (standalone mode)
docker run -d --name nacos -p 8848:8848 -e MODE=standalone nacos/nacos-server:v2.4.3

# Start Redis (optional)
docker run -d --name redis -p 6379:6379 redis:7

# Start MySQL (or use embedded H2 for dev)
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

Default credentials: `admin` / `admin123`

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| **Gateway Core** | Spring Cloud Gateway 4.1, Project Reactor |
| **Framework** | Spring Boot 3.2, Spring Security |
| **Config Center** | Nacos 2.4.3 / Consul (SPI switchable) |
| **Circuit Breaker** | Resilience4j 2.1 |
| **Rate Limiting** | Redis + Caffeine (hybrid) |
| **Authentication** | JJWT 0.12 |
| **Database** | MySQL / H2, Spring Data JPA |
| **Monitoring** | Prometheus, Micrometer |
| **Frontend** | React 19, TypeScript, Ant Design 6, Vite |
| **Container** | Docker, Kubernetes |

---

## Testing

The project has comprehensive test coverage:

| Module | Tests | Status |
|--------|-------|--------|
| **my-gateway** | 332 | ✅ All Passing |
| **gateway-admin** | 160 | ✅ All Passing |
| **Total** | **492** | ✅ **All Passing** |

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
├── gateway-ui/                 # Web dashboard frontend
│   └── src/
│       ├── pages/              # React page components
│       ├── components/         # Reusable UI components
│       └── i18n.ts             # Internationalization (EN/CN)
│
├── demo-service/               # Sample backend service
│
└── k8s/                        # Kubernetes deployment manifests
    ├── nacos.yaml              # Nacos deployment
    ├── redis.yaml              # Redis deployment
    ├── my-gateway.yaml         # Gateway deployment
    └── prometheus.yaml         # Prometheus monitoring
```

---

## License

MIT License - see [LICENSE](LICENSE) for details.

---

## Support

- **Issues**: [GitHub Issues](https://github.com/leoli5695/scg-dynamic-admin/issues)
- **Discussions**: [GitHub Discussions](https://github.com/leoli5695/scg-dynamic-admin/discussions)