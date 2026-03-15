# Production-Grade API Gateway Demo

Enterprise-ready API Gateway built with Spring Cloud Gateway, featuring production-proven security, resilience, and observability patterns.

> **Demo Notice:** This is a demonstration project. All core features are fully implemented and follow production-ready patterns.

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.x-blue.svg)](https://spring.io/projects/spring-cloud)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Key Features

### Enterprise Authentication
- **Strategy Pattern Design** - Extensible auth processor architecture
- **JWT / API Key / OAuth2** - Multiple auth methods out of the box
- **Extensible** - Add custom auth types (e.g., DingTalk, WeChat) in minutes
- **Performance Optimized** - IP filtering before auth (+37% TPS)

### Resilience & Protection
- **Circuit Breaker** - Prevent cascading failures (Resilience4j)
- **Rate Limiting** - QPS-based throttling (Redis sliding window)
- **Timeout Control** - Per-route connection/response timeouts
- **Multi-Layered Defense** - IP filter -> Auth -> Rate limit -> Circuit breaker

### Observability
- **Distributed Tracing** - Automatic TraceId propagation
- **Audit Logging** - Complete change history via AOP
- **Structured Logging** - MDC-based correlation

### Management
- **REST Admin API** - Full CRUD for all configurations
- **Web Dashboard** - User-friendly UI (Thymeleaf + Bootstrap)
- **Dynamic Updates** - Hot-reload without restarts (< 1s)
- **Nacos Integration** - Centralized config management

---

## Architecture Overview

### System Flow

```
+-------------+
|   Client    |
+------+------+
       | HTTP Request
       |
+------------------------------------------+
|      API Gateway (Port 80)               |
|                                          |
|  +--------------------------------------+|
|  | Global Filters (Ordered)            ||
|  |  1. StaticProtocolGlobalFilter      ||
|  |  2. IPFilterGlobalFilter            ||
|  |  3. AuthenticationGlobalFilter      ||
|  |  4. TimeoutGlobalFilter             ||
|  |  5. CircuitBreakerGlobalFilter      ||
|  |  6. TraceIdGlobalFilter             ||
|  +--------------------------------------+|
|              |                           |
|  +--------------------------------------+|
|  | DiscoveryLoadBalancerFilter          ||
|  |  - Weighted Round-Robin LB           ||
|  |  - Service instance selection        ||
|  +--------------------------------------+|
+------------------+-----------------------+
                   |
+------------------------------------------+
|      Backend Service (9000/9001)         |
+------------------------------------------+
```

### Configuration Sync Flow

```
+-----------------------------+
| Gateway Admin  (Port 8080)  |
|  - REST API                 |
|  - Web UI                   |
+--------------+--------------+
               | 1. Create/Update Route
               |    (POST /api/routes)
               |
+------------------------------------------+
|  H2 Database                             |
|  - RouteEntity                           |
|  - ServiceEntity                         |
|  - PluginEntity                          |
+--------------+---------------------------+
               | 2. Transaction Commit
               |
+------------------------------------------+
|  NacosPublisher (Async)                  |
|  - Query all routes from H2              |
|  - Convert to JSON                       |
|  - Publish to Nacos                      |
+--------------+---------------------------+
               | 3. HTTP PUT /nacos/config
               |
+------------------------------------------+
|  Nacos Config Center                     |
|  - gateway-routes.json                   |
|  - gateway-services.json                 |
|  - gateway-plugins.json                  |
+--------------+---------------------------+
               | 4. Configuration Push (<100ms)
               |
+------------------------------------------+
|  API Gateway Listeners                   |
|  - RouteRefresher                        |
|  - ServiceRefresher                      |
|  - StrategyRefresher                     |
+--------------+---------------------------+
               | 5. Detect Change
               |
+------------------------------------------+
|  Manager Layer                           |
|  - RouteManager.loadConfig()             |
|  - ServiceManager.loadConfig()           |
|  - StrategyManager.loadConfig()          |
+--------------+---------------------------+
               | 6. Update Cache
               |
+------------------------------------------+
|  Locator Layer                           |
|  - DynamicRouteDefinitionLocator         |
|    -> clearCache()                       |
|    -> publishRefreshEvent()              |
+--------------+---------------------------+
               | 7. RefreshRoutesEvent
               |
+------------------------------------------+
|  Spring Cloud Gateway                    |
|  - Auto-reload route table               |
|  - Next request uses new config          |
+------------------------------------------+

Total Latency: < 1 second
```

---

## Quick Start

### Prerequisites

- JDK 17+
- Maven 3.8+
- Nacos 2.4.3 (standalone)
- Redis 6.0+

### Step 1: Start Infrastructure

```bash
# Nacos standalone
cd nacos/bin
startup.cmd -m standalone        # Windows

# Redis
redis-server
```

### Step 2: Bootstrap Nacos Configs

In Nacos console (`http://localhost:8848/nacos`), create under **Namespace: public / Group: DEFAULT_GROUP**:

**`gateway-routes.json`**
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

**`gateway-services.json`**
```json
{
  "version": "1.0",
  "services": [{
    "name": "demo-service",
    "loadBalancer": "weighted",
    "instances": [
      {"ip": "127.0.0.1", "port": 9000, "weight": 1, "healthy": true},
      {"ip": "127.0.0.1", "port": 9001, "weight": 2, "healthy": true}
    ]
  }]
}
```

**`gateway-plugins.json`**
```json
{
  "version": "1.0",
  "plugins": {
    "rateLimiters": [],
    "ipFilters": [],
    "authConfigs": [],
    "circuitBreakers": [],
    "timeouts": [{"routeId": "demo-route", "connectTimeout": 5000, "responseTimeout": 10000}]
  }
}
```

### Step 3: Start Services

```bash
# demo-service (2 instances for load balancing demo)
cd demo-service
mvn spring-boot:run -Dserver.port=9000    # Terminal 1
mvn spring-boot:run -Dserver.port=9001    # Terminal 2

# Gateway
cd my-gateway
mvn spring-boot:run                       # Terminal 3

# Admin
cd gateway-admin
mvn spring-boot:run                       # Terminal 4
```

### Step 4: Verify

| Component | URL |
|-----------|-----|
| Admin Console | http://localhost:8080 |
| Gateway Entry | http://localhost:80 |
| Nacos Console | http://localhost:8848/nacos |

Test load balancing:
```bash
curl http://localhost/api/hello
# Alternates between port 9000 and 9001 (weight ratio 1:2)
```

---

## Real-Time Configuration Updates

**How It Works:**
```
Admin API (POST/PUT/DELETE)
  -> H2 Database (Persist)
  -> Nacos Config Center (< 100ms push)
  -> Gateway Listener (Detects change)
  -> Clear cache + Rebuild routes/plugins
  -> Next request uses new config (no restart!)
```

**Effective Latency:** < 1 second

**Example: Add JWT Authentication**
```bash
# 1. Call Admin API
curl -X POST http://localhost:8080/api/plugins/auth \
  -H "Content-Type: application/json" \
  -d '{"routeId":"demo-route","authType":"JWT","secretKey":"test-secret-key"}'

# 2. Immediate effect - next request requires JWT token
curl http://localhost:80/api/data
# Returns 401 Unauthorized (missing Authorization header)

# 3. With valid JWT token
curl http://localhost:80/api/data -H "Authorization: Bearer <token>"
# Returns 200 OK
```

---

## Project Structure

```
scg-dynamic-admin-demo/
+-- gateway-admin/           # Admin console (port 8080)
|   +-- controller/          # REST API + Web UI
|   +-- service/             # Business logic
|   +-- mapper/              # Data access (MyBatis Plus)
|   +-- model/               # Entity classes
|   +-- config/              # Security & JWT config
|   +-- aspect/              # Audit logging (AOP)
|   +-- filter/              # JWT authentication filter
|
+-- my-gateway/              # Core gateway (port 80)
|   +-- filter/              # Global filters
|   |   +-- AuthenticationGlobalFilter.java
|   |   +-- CircuitBreakerGlobalFilter.java
|   |   +-- IPFilterGlobalFilter.java
|   |   +-- TimeoutGlobalFilter.java
|   |   +-- TraceIdGlobalFilter.java
|   |   +-- DiscoveryLoadBalancerFilter.java
|   +-- manager/             # Configuration managers
|   |   +-- RouteManager.java
|   |   +-- ServiceManager.java
|   |   +-- StrategyManager.java
|   +-- refresher/           # Config refreshers
|   |   +-- RouteRefresher.java
|   |   +-- ServiceRefresher.java
|   |   +-- StrategyRefresher.java
|   +-- route/               # Route locator
|   |   +-- DynamicRouteDefinitionLocator.java
|   +-- discovery/           # Service discovery SPI
|   |   +-- spi/DiscoveryService.java
|   |   +-- nacos/NacosDiscoveryService.java
|   |   +-- consul/ConsulDiscoveryService.java
|   +-- center/              # Config center SPI
|   |   +-- spi/ConfigCenterService.java
|   |   +-- nacos/NacosConfigService.java
|   |   +-- consul/ConsulConfigService.java
|   +-- enums/               # Unified enums
|   |   +-- StrategyType.java
|   |   +-- CenterType.java
|   |   +-- AuthType.java
|   +-- model/               # Config models
|       +-- RateLimiterConfig.java
|       +-- CircuitBreakerConfig.java
|       +-- TimeoutConfig.java
|       +-- ...
|
+-- demo-service/            # Sample backend (port 9000/9001)
|
+-- docs/                    # Documentation
    +-- FEATURES.md          # Complete feature guide
    +-- ARCHITECTURE.md      # System architecture
    +-- GATEWAY_ARCHITECTURE.md  # Gateway architecture details
```

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| **Framework** | Spring Boot 3.x | Core framework |
| **Gateway** | Spring Cloud Gateway 4.1 | API Gateway pattern |
| **Reactive** | Project Reactor | Async programming |
| **Config & Discovery** | Nacos 2.4.3 | Registry + Config center |
| **Rate Limiting** | Redis 6.0 | Sliding window counter |
| **Circuit Breaker** | Resilience4j 2.1 | Fault tolerance |
| **Authentication** | JJWT 0.12.3 | JWT processing |
| **Database** | H2 (embedded) | Demo persistence |
| **ORM** | MyBatis Plus | Data access |
| **AOP** | Spring AOP | Audit logging |
| **Admin UI** | Thymeleaf + Bootstrap | Web interface |

---

## Documentation

| Document | Audience | Content |
|----------|----------|---------|
| [README.md](README.md) | Everyone | Overview, quick start, architecture flow |
| [FEATURES.md](docs/FEATURES.md) | Users | Complete feature guide with examples |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Developers | System design principles, trade-offs |
| [GATEWAY_ARCHITECTURE.md](docs/GATEWAY_ARCHITECTURE.md) | Architects | Complete gateway architecture, patterns, extensibility |

---

## Available for Hire

**Need a customized API Gateway or Microservices Architecture?**

I'm available on Upwork for freelance projects:
- **Profile:** [https://www.upwork.com/freelancers/~017be8c63f36907379](https://www.upwork.com/freelancers/~017be8c63f36907379)
- **Contact:** lizhao5695@gmail.com

**Specialties:**
- Spring Cloud Gateway customization
- Microservices architecture design
- Production-grade security patterns
- Performance optimization
- Enterprise authentication integration

---

## License

MIT License - free for personal and commercial use. See [LICENSE](LICENSE) for details.

---

## 💝 Support & Sponsor

This project is free to use under the MIT License. 

If it helps your business or learning, consider:
- ⭐ Starring this repository
- 📢 Sharing with others
- 💬 Contributing improvements
- ☕ Buying me a coffee (optional)

Your support keeps this project growing! 🚀

---

<div align="center">

**Built by leoli**

Found this useful? Give it a Star!

</div>
