# Spring Cloud Gateway Dynamic Management Demo

A dynamic API gateway management system built on Spring Cloud Gateway + Spring Boot 3.2.4 + Nacos 2.4.3, featuring visual configuration console and real-time hot updates.

<div align="center">

[![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-2021.x-blue)](https://spring.io/projects/spring-cloud-gateway)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.4-green)](https://spring.io/projects/spring-boot)
[![Nacos](https://img.shields.io/badge/Nacos-2.4.3-orange)](https://nacos.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 📋 Overview

This demo implements a **dynamic gateway management system** with visual admin console and Nacos-based configuration hot updates. Manage routes, services, and plugins through web UI without editing YAML files.

**Key Features:**
- ✅ Visual Management Console (Web UI)
- ✅ Real-time Configuration Hot Updates
- ✅ Dynamic Routing & Service Discovery
- ✅ Load Balancing (Round Robin, Weighted, Random)
- ✅ Plugin System (Rate Limiter, Custom Headers, IP Access Control)

---

## 🎯 Core Features

### Gateway Admin Console
- **Service Management**: Create services, configure instances, set load balancing strategies
- **Route Management**: Dynamic routes with predicates (Path/Host/Method/Header) and filters
- **Plugin Management**: Rate limiting, custom headers, CORS, IP whitelist/blacklist

### My-Gateway (Core Service)
- **NacosLoadBalancerFilter**: Direct Nacos integration with weighted load balancing
- **StaticProtocolGlobalFilter**: Static node routing via `gateway-services.json`
- **DynamicCustomHeaderGlobalFilter**: Add custom headers with variable support
- **NacosRouteDefinitionLocator**: Dynamic route loading from Nacos

### Demo Service
- Sample backend service for testing
- Endpoints: `/hello`, `/api/headers`, `/api/info`

---

## 🏗️ Architecture

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │
       ▼
┌─────────────────────────┐
│     My-Gateway          │
│  ─────────────────────  │
│  - Route Locator        │
│  - LB Filter            │
│  - Custom Filters       │
└───────────┬─────────────┘
            │
            ▼
     ┌─────────────┐
     │Backend Svc  │
     └─────────────┘
```

**Configuration Flow:**
```
Gateway Admin → Nacos Config → My-Gateway (Real-time Sync)
```

---

## 🚀 Quick Start

### Prerequisites
- JDK 17+
- Maven 3.8+
- Nacos 2.4.3
- Redis 6.0+

### Step 1: Start Dependencies

**Start Nacos Server:**
```bash
cd nacos/bin
# Linux/Mac
sh startup.sh -m standalone
# Windows
startup.cmd -m standalone
```

**Start Redis:**
```bash
redis-server
```

### Step 2: Configure Nacos

Create configurations in Nacos Console (Namespace: **public**):

**gateway-routes.json:**
```json
{
  "routes": [
    {
      "id": "user-route",
      "uri": "lb://user-service",
      "predicates": [{"name": "Path", "args": {"pattern": "/api/users/**"}}]
    }
  ]
}
```

**gateway-services.json:**
```json
{
  "services": [
    {
      "name": "user-service",
      "loadBalancer": "round-robin",
      "instances": [{"ip": "127.0.0.1", "port": 8081, "weight": 1.0}]
    }
  ]
}
```

**gateway-plugins.json:**
```json
{
  "plugins": {
    "customHeaders": [
      {
        "routeId": "user-route",
        "headers": {"X-Request-Id": "${random.uuid}"}
      }
    ]
  }
}
```

### Step 3: Build and Start Services

**Start Demo Service:**
```bash
cd demo-service
mvn clean package
java -jar target/demo-service-1.0.0.jar
```

**Start My-Gateway:**
```bash
cd my-gateway
mvn clean package
java -jar target/my-gateway-1.0.0.jar
```

**Start Gateway Admin:**
```bash
cd gateway-admin
mvn clean package
java -jar target/gateway-admin-1.0.0.jar
```

### Step 4: Access Consoles

| Component | URL | Description |
|-----------|-----|-------------|
| **Gateway Admin Console** | http://localhost:8081 | Web management UI |
| **Gateway API** | http://localhost:80 | Gateway entry point |
| **Demo Service** | http://localhost:8081 | Sample backend service |
| **Nacos Console** | http://localhost:8848/nacos | Configuration management |

---

## ⚠️ Demo Limitations

This is a **learning/demo project**. Production deployment requires additional features:

**Security:** Authentication/Authorization, SSL/TLS  
**Reliability:** Circuit breakers, retry policies, timeouts  
**Monitoring:** Prometheus metrics, distributed tracing  
**HA:** Cluster deployment, Nacos cluster, Redis cluster  

This demo focuses on core dynamic routing, load balancing and plugin system. Production-grade features (auth, rate limiting, circuit breaker, monitoring) can be quickly extended based on the current architecture.

---

## 📄 Documentation

- **[INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md)** - Complete integration guide with architecture details
- **[ADVANCED_FEATURES.md](ADVANCED_FEATURES.md)** - Advanced features: distributed rate limiting, IP access control, CORS

---

## 📦 Project Structure

```
scg-dynamic-admin-demo/
├── gateway-admin/       # Web management console
├── my-gateway/          # Gateway core service
├── demo-service/        # Sample backend service
├── nacos/               # Nacos server (submodule)
├── README.md
├── INTEGRATION_GUIDE.md
└── ADVANCED_FEATURES.md
```

---

## 📄 License

MIT License - see [LICENSE](LICENSE) file.

---

## 🤝 Contributing

Contributions welcome! Please feel free to submit Pull Requests.

---

<div align="center">

**Made with ❤️ by leoli**

If you find this helpful, please give it a ⭐ Star!

</div>
