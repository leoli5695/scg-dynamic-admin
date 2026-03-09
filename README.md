# Production-Grade API Gateway Demo

Enterprise-ready API Gateway built with Spring Cloud Gateway, featuring production-proven security, resilience, and observability patterns.

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.x-blue.svg)](https://spring.io/projects/spring-cloud)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 馃幆 Key Features

### 馃攼 Enterprise Authentication
- **Strategy Pattern Design** 鈥?Extensible auth processor architecture
- **JWT / API Key / OAuth2** 鈥?Multiple auth methods out of the box
- **Extensible** 鈥?Add custom auth types (e.g., DingTalk, WeChat) in minutes
- **Performance Optimized** 鈥?IP filtering before auth (+37% TPS)

### 鈿?Resilience & Protection
- **Circuit Breaker** 鈥?Prevent cascading failures (Resilience4j)
- **Rate Limiting** 鈥?QPS-based throttling (Redis sliding window)
- **Timeout Control** 鈥?Per-route connection/response timeouts
- **Multi-Layered Defense** 鈥?IP filter 鈫?Auth 鈫?Rate limit 鈫?Circuit breaker

### 馃攳 Observability
- **Distributed Tracing** 鈥?Automatic TraceId propagation
- **Audit Logging** 鈥?Complete change history via AOP
- **Structured Logging** 鈥?MDC-based correlation

### 馃洜锔?Management
- **REST Admin API** 鈥?Full CRUD for all configurations
- **Web Dashboard** 鈥?User-friendly UI (Thymeleaf + Bootstrap)
- **Dynamic Updates** 鈥?Hot-reload without restarts (< 1s)
- **Nacos Integration** 鈥?Centralized config management

---

## 馃殌 Quick Start

| Module | Port | Description |
|--------|------|-------------|
| `my-gateway` | 80 | Core gateway 鈥?Spring Cloud Gateway extended |
| `gateway-admin` | 8080 | Management console (REST API + Web UI) |
| `demo-service` | **9000 / 9001** | Demo backend 鈥?**start 2 instances to demonstrate load balancing** |
| Nacos | 8848 | Config center + Service registry |
| Redis | 6379 | Rate limiting counter storage |

---

## 馃殌 Quick Start

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
cd my-gateway && mvn spring-boot:run     # Terminal 3

# Admin
cd gateway-admin && mvn spring-boot:run  # Terminal 4
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

## 鈿?Real-Time Configuration Updates

**How It Works:**
```
Admin API (POST/PUT/DELETE)
  鈫?
Nacos Config Center (< 100ms push)
  鈫?
Gateway Listener (detects change)
  鈫?
Clear cache + Rebuild routes/plugins
  鈫?
Next request uses new config (no restart!)
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

## 馃搧 Project Structure

```
scg-dynamic-admin-demo/
鈹溾攢鈹€ gateway-admin/           # Admin console (port 8080)
鈹?  鈹溾攢鈹€ controller/          # REST API + Web UI
鈹?  鈹溾攢鈹€ model/               # Data models
鈹?  鈹斺攢鈹€ service/             # Business logic
鈹溾攢鈹€ my-gateway/              # Core gateway (port 80)
鈹?  鈹溾攢鈹€ filter/              # Global filters
鈹?  鈹?  鈹溾攢鈹€ StrategyGlobalFilter.java   # Main strategy-based filter
鈹?  鈹?  鈹溾攢鈹€ TraceIdGlobalFilter.java  # Distributed tracing
鈹?  鈹?  鈹溾攢鈹€ IPFilterGlobalFilter.java  # IP access control
鈹?  鈹?  鈹斺攢鈹€ ... 
鈹?  鈹溾攢鈹€ strategy/     # Strategy implementations
鈹?  鈹?  鈹溾攢鈹€ Plugin.java             # Strategy interface
鈹?  鈹?  鈹溾攢鈹€ PluginType.java         # Strategy type enum
鈹?  鈹?  鈹溾攢鈹€ AbstractPlugin.java     # Base class
鈹?  鈹?  鈹溾攢鈹€ StrategyManager.java    # Central registry
鈹?  鈹?  鈹溾攢鈹€ timeout/                 # Timeout strategy
鈹?  鈹?  鈹溾攢鈹€ ratelimiter/             # Rate limiter strategy
鈹?  鈹?  鈹溾攢鈹€ circuitbreaker/          # Circuit breaker strategy
鈹?  鈹?  鈹溾攢鈹€ auth/                    # Auth strategy
鈹?  鈹?  鈹溾攢鈹€ ipfilter/                # IP filter strategy
鈹?  鈹?  鈹斺攢鈹€ tracing/                 # Tracing strategy
鈹?  鈹溾攢鈹€ manager/             # Configuration managers
鈹?  鈹?  鈹溾攢鈹€ GatewayConfigManager.java   # Unified config store
鈹?  鈹?  鈹溾攢鈹€ TimeoutConfigManager.java  # Timeout config
鈹?  鈹?  鈹溾攢鈹€ CircuitBreakerConfigManager.java # Circuit breaker config
鈹?  鈹?  鈹斺攢鈹€ RateLimiterConfigManager.java  # Rate limiter config
鈹?  鈹溾攢鈹€ refresher/           # Config refreshers
鈹?  鈹?  鈹溾攢鈹€ AbstractRefresher.java    # Base refresher
鈹?  鈹?  鈹溾攢鈹€ StrategyRefresher.java      # Plugin config refresher
鈹?  鈹?  鈹斺攢鈹€ NacosConfigListener.java  # Nacos listener
鈹?  鈹斺攢鈹€ route/
鈹?      鈹斺攢鈹€ NacosRouteDefinitionLocator.java # Dynamic route loader
鈹溾攢鈹€ demo-service/            # Sample backend (port 9000/9001)
鈹斺攢鈹€ docs/                    # Documentation
    鈹溾攢鈹€ PLUGIN_ARCHITECTURE.md      # Architecture design
    鈹溾攢鈹€ PLUGIN_QUICKSTART.md        # Usage guide
    鈹溾攢鈹€ REFACTORING_SUMMARY.md      # Refactoring summary
    鈹溾攢鈹€ FEATURES.md                 # Feature overview
    鈹斺攢鈹€ ARCHITECTURE.md             # System architecture
```

---

## 馃洜锔?Tech Stack

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

## 馃摉 Documentation

| Document | Audience | Content |
|----------|----------|---------|
| [README.md](README.md) | Everyone | Overview, quick start |
| [FEATURES.md](docs/FEATURES.md) | Users | Complete feature guide |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Developers | Design principles, trade-offs |
| [API.md](docs/API.md) | Integrators | REST API reference |

---

## 馃捈 Available for Hire

**Need a customized API Gateway or Microservices Architecture?**

I'm available on Upwork for freelance projects:
- 馃敆 **Profile:** [https://www.upwork.com/freelancers/~017be8c63f36907379](https://www.upwork.com/freelancers/~017be8c63f36907379)
- 馃摟 **Contact:** lizhao5695@gmail.com

**Specialties:**
- 鉁?Spring Cloud Gateway customization
- 鉁?Microservices architecture design
- 鉁?Production-grade security patterns
- 鉁?Performance optimization
- 鉁?Enterprise authentication integration

---

## 馃搫 License

MIT License 鈥?free for personal and commercial use. See [LICENSE](LICENSE) for details.

---

<div align="center">

**Built with 鉂わ笍 by leoli**

Found this useful? Give it a 猸?Star!

</div>
