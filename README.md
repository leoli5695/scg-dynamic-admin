# Enterprise API Gateway

> Production-grade API Gateway built on Spring Cloud Gateway with dynamic configuration, multi-strategy authentication, and real-time synchronization.

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-4.1-blue.svg)](https://spring.io/projects/spring-cloud-gateway)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Architecture Overview

```
                                    ┌─────────────────────────────────────────────────────────────┐
                                    │                    MANAGEMENT PLANE                          │
                                    │  ┌─────────────────────────────────────────────────────────┐ │
                                    │  │                   gateway-admin (:9090)                 │ │
                                    │  │                                                          │ │
                                    │  │   ┌──────────────┐   ┌──────────────┐   ┌─────────────┐ │ │
                                    │  │   │   REST API   │   │   Web Dashboard│   │  Audit Log  │ │ │
                                    │  │   └──────┬───────┘   └──────────────┘   └─────────────┘ │ │
                                    │  │          │                                              │ │
                                    │  │          ▼                                              │ │
                                    │  │   ┌──────────────┐   ┌──────────────┐                   │ │
                                    │  │   │   Service    │──►│    MySQL     │                   │ │
                                    │  │   │    Layer     │   │  (Persist)   │                   │ │
                                    │  │   └──────────────┘   └──────────────┘                   │ │
                                    │  └─────────────────────────┬───────────────────────────────┘ │
                                    └────────────────────────────│─────────────────────────────────┘
                                                                 │
                                                                 │ Config Push (< 100ms)
                                                                 ▼
                                    ┌─────────────────────────────────────────────────────────────┐
                                    │                    CONFIG CENTER                             │
                                    │                                                              │
                                    │            ┌───────────────┐   ┌───────────────┐            │
                                    │            │     Nacos     │   │    Consul     │            │
                                    │            │   (Default)   │   │   (Optional)  │            │
                                    │            └───────┬───────┘   └───────┬───────┘            │
                                    │                    │                   │                    │
                                    │                    └─────────┬─────────┘                    │
                                    │                              │                              │
                                    │                              ▼                              │
                                    │                    ┌─────────────────┐                      │
                                    │                    │ gateway-routes  │                      │
                                    │                    │ gateway-services│                      │
                                    │                    │ gateway-plugins │                      │
                                    │                    └─────────────────┘                      │
                                    └──────────────────────────────┬──────────────────────────────┘
                                                                   │
                                                                   │ Real-time Sync
                                                                   ▼
                                    ┌─────────────────────────────────────────────────────────────┐
                                    │                       DATA PLANE                             │
                                    │  ┌─────────────────────────────────────────────────────────┐ │
                                    │  │                    my-gateway (:80)                     │ │
                                    │  │                                                          │ │
                                    │  │   ┌──────────────────────────────────────────────────┐  │ │
                                    │  │   │              Global Filter Chain                  │  │ │
                                    │  │   │                                                    │  │ │
                                    │  │   │  Request ──► [IP Filter] ──► [Auth] ──► [Rate Limit]│  │ │
                                    │  │   │                    │                               │  │ │
                                    │  │   │                    ▼                               │  │ │
                                    │  │   │            [Circuit Breaker] ──► [Timeout]         │  │ │
                                    │  │   │                    │                               │  │ │
                                    │  │   │                    ▼                               │  │ │
                                    │  │   │              [Load Balancer]                       │  │ │
                                    │  │   └──────────────────────────────────────────────────┘  │ │
                                    │  │                          │                              │ │
                                    │  └──────────────────────────│──────────────────────────────┘ │
                                    └─────────────────────────────│────────────────────────────────┘
                                                                  │
                                                                  ▼
                                    ┌─────────────────────────────────────────────────────────────┐
                                    │                    BACKEND SERVICES                          │
                                    │                                                              │
                                    │       ┌────────────┐    ┌────────────┐    ┌────────────┐    │
                                    │       │  Service A │    │  Service B │    │  Service C │    │
                                    │       │   :9000    │    │   :9001    │    │   :9002    │    │
                                    │       └────────────┘    └────────────┘    └────────────┘    │
                                    └─────────────────────────────────────────────────────────────┘
```

---

## Modules

| Module | Port | Description |
|--------|------|-------------|
| **my-gateway** | 80 | Core gateway runtime - handles all API traffic with filter chain |
| **gateway-admin** | 9090 | Management console - REST API + React UI for configuration |
| **gateway-ui** | 5173 | Modern web dashboard built with React + TypeScript + Ant Design |
| **demo-service** | 9000/9001 | Sample backend services for testing load balancing |

---

## Key Features

### Service Discovery (Highlight)
- **Dual Protocol Support** - `lb://` for Nacos/Consul discovery, `static://` for non-registered services
- **Static Service Discovery** - Custom protocol for legacy systems, external APIs, and services not in service registry
- **Weighted Round-Robin** - Nginx-style smooth load balancing with configurable instance weights
- **Health-Aware Routing** - Unhealthy instances automatically skipped in multi-instance services

### Security
- **Multi-Strategy Authentication** - JWT, API Key, Basic Auth, HMAC Signature, OAuth2
- **IP Filtering** - Blacklist/Whitelist with CIDR support
- **Rate Limiting** - Hybrid (Redis + Local fallback) with sliding window

### Resilience
- **Circuit Breaker** - Resilience4j integration with configurable thresholds
- **Timeout Control** - Per-route connection and response timeout
- **Retry Mechanism** - Automatic retry with exponential backoff

### Observability
- **Distributed Tracing** - Auto-generated TraceId with MDC logging
- **Audit Logging** - Complete change history via AOP
- **Hybrid Health Checks** - Active (TCP probe) + Passive (request monitoring) with real-time UI sync

### Dynamic Configuration
- **Hot Reload** - Configuration changes take effect in < 1 second
- **Dual Config Center** - Nacos (default) / Consul switchable via SPI
- **Dual-Write Pattern** - Database persistence + Config center sync

---

## Quick Start

### Prerequisites
- JDK 17+
- Maven 3.8+
- Nacos 2.4.3 (standalone mode)
- Redis 6.0+ (optional, for distributed rate limiting)

### 1. Start Infrastructure

```bash
# Nacos (standalone)
cd nacos/bin
startup.cmd -m standalone    # Windows
./startup.sh -m standalone   # Linux/Mac

# Redis (optional)
redis-server
```

### 2. Initialize Nacos Configuration

Create the following config in Nacos Console (`http://localhost:8848/nacos`):

**Data ID:** `gateway-routes.json` | **Group:** `DEFAULT_GROUP`

```json
{
  "version": "1.0",
  "routes": [{
    "id": "demo-route",
    "uri": "static://demo-service",
    "predicates": [{"name": "Path", "args": {"pattern": "/api/**"}}]
  }]
}
```

**Data ID:** `gateway-services.json` | **Group:** `DEFAULT_GROUP`

```json
{
  "version": "1.0",
  "services": [{
    "name": "demo-service",
    "instances": [
      {"ip": "127.0.0.1", "port": 9000, "weight": 1},
      {"ip": "127.0.0.1", "port": 9001, "weight": 2}
    ]
  }]
}
```

### 3. Start Services

```bash
# Terminal 1: Demo Service (instance 1)
cd demo-service && mvn spring-boot:run -Dserver.port=9000

# Terminal 2: Demo Service (instance 2)
cd demo-service && mvn spring-boot:run -Dserver.port=9001

# Terminal 3: Gateway
cd my-gateway && mvn spring-boot:run

# Terminal 4: Admin Console
cd gateway-admin && mvn spring-boot:run

# Terminal 5: Web UI (optional)
cd gateway-ui && npm install && npm run dev
```

### 4. Verify

| Component | URL |
|-----------|-----|
| Gateway API | http://localhost:80/api/hello |
| Admin REST API | http://localhost:9090/api/routes |
| Web Dashboard | http://localhost:5173 |
| Nacos Console | http://localhost:8848/nacos |

```bash
# Test load balancing (should alternate between ports)
curl http://localhost/api/hello
```

---

## Configuration Sync Flow

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         CONFIGURATION SYNC PIPELINE                          │
└──────────────────────────────────────────────────────────────────────────────┘

     Admin API                    Config Center                    Gateway
    ┌─────────┐                  ┌─────────────┐               ┌─────────────┐
    │  POST   │                  │             │               │             │
    │ /routes │                  │    Nacos    │               │  my-gateway │
    └────┬────┘                  │    OR       │               │             │
         │                       │   Consul    │               │             │
         ▼                       └──────┬──────┘               └──────┬──────┘
    ┌─────────┐                       │                             │
    │  MySQL  │                       │                             │
    │ (Save)  │                       │                             │
    └────┬────┘                       │                             │
         │                            │                             │
         │ Publish                    │ Push (< 100ms)              │
         └───────────────────────────►│────────────────────────────►│
                                      │                             │
                                      │                    ┌────────┴────────┐
                                      │                    │   Refresher     │
                                      │                    │   Detect Change │
                                      │                    └────────┬────────┘
                                      │                             │
                                      │                    ┌────────┴────────┐
                                      │                    │    Manager      │
                                      │                    │  Update Cache   │
                                      │                    └────────┬────────┘
                                      │                             │
                                      │                    ┌────────┴────────┐
                                      │                    │RouteDefinition  │
                                      │                    │    Locator      │
                                      │                    └────────┬────────┘
                                      │                             │
                                      │                    ┌────────┴────────┐
                                      │                    │  Routes Updated │
                                      │                    │   (< 1 second)  │
                                      │                    └─────────────────┘
```

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
| **Frontend** | React 19, TypeScript, Ant Design 6, Vite |

---

## Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture, design patterns, and module details |
| [FEATURES.md](docs/FEATURES.md) | Complete feature guide with API reference and configuration examples |

---

## Project Structure

```
.
├── my-gateway/                 # Core gateway runtime
│   └── src/main/java/
│       ├── filter/             # Global filters (18 filters)
│       ├── auth/               # Authentication processors (Strategy pattern)
│       ├── center/             # Config center SPI (Nacos/Consul)
│       ├── discovery/          # Service discovery SPI
│       ├── manager/            # Configuration managers
│       └── route/              # Dynamic route locator
│
├── gateway-admin/              # Management console backend
│   └── src/main/java/
│       ├── controller/         # REST API endpoints
│       ├── service/            # Business logic
│       ├── repository/         # JPA repositories
│       └── center/             # Config center publisher
│
├── gateway-ui/                 # Web dashboard frontend
│   └── src/
│       ├── pages/              # React page components
│       ├── components/         # Reusable UI components
│       └── i18n.ts             # Internationalization
│
└── demo-service/               # Sample backend service
```

---

## Roadmap

### Static Service Discovery Enhancements
- [ ] **HTTP Health Endpoint** - Support custom health check paths per service
- [ ] **Instance Metadata** - Custom labels for canary deployments and traffic splitting
- [ ] **Connection Pooling** - HTTP connection pool per static service for better performance
- [ ] **Graceful Drain** - Support instance drain mode for zero-downtime updates

### Rate Limiting Enhancements
- [ ] **Distributed Token Bucket** - Consistent rate limiting across all gateway instances
- [ ] **Dynamic QPS Adjustment** - Real-time QPS adjustment without restart
- [ ] **Rate Limit Analytics** - Historical data and throttling pattern analysis

### Observability Improvements
- [ ] **OpenTelemetry Integration** - Native support for distributed tracing export
- [ ] **Prometheus Metrics** - Built-in metrics endpoint for monitoring
- [ ] **Access Log Aggregation** - Structured logging with ELK/Loki support

### Security Enhancements
- [ ] **mTLS Support** - Mutual TLS for service-to-service communication
- [ ] **JWT Key Rotation** - Automatic JWT signing key rotation
- [ ] **API Contract Validation** - OpenAPI schema validation for requests

### Performance Optimizations
- [ ] **gRPC Protocol Support** - Native gRPC routing and load balancing
- [ ] **WebSocket Routing** - Full WebSocket proxy support
- [ ] **Plugin Hot-Reload** - Dynamic filter loading without restart

---

## License

MIT License - See [LICENSE](LICENSE) for details.