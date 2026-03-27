# API Gateway Management Platform

> **Enterprise-Grade API Gateway with Dynamic Configuration, Multi-Service Routing & Intelligent Monitoring**

[![Java](https://img.shields.io/badge/Java-17+-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-4.1-blue)](https://spring.io/projects/spring-cloud-gateway)
[![React](https://img.shields.io/badge/React-19-blue)](https://react.dev/)
[![License](https://img.shields.io/badge/License-Proprietary-red)](LICENSE)

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

### Observability & Operations

| Feature | Description |
|---------|-------------|
| **Real-time Monitoring** | JVM, CPU, memory, HTTP metrics with historical charts |
| **Request Tracing** | Capture error/slow requests with full headers and replay capability |
| **AI-Powered Analysis** | Integration with GPT, Claude, Qwen for intelligent metrics analysis |
| **Alert System** | Configurable thresholds with email notifications |
| **SSL Certificate Management** | Upload, monitor expiry, get alerts before certificates expire |

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
│  │             Request Traces, AI Analysis                            │   │
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
│  │   Filters: Security ▶ IP Filter ▶ Auth ▶ Rate Limit ▶ CB ▶ LB    │   │
│  └───────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key Benefits:**
- Zero-downtime configuration updates
- Independent scaling of control and data planes
- Hot-reload routes, services, and strategies

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

---

## Documentation

| Document | Description |
|----------|-------------|
| [FEATURES.md](docs/FEATURES.md) | Complete feature documentation with examples |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture & design patterns |
| [QUICK_START.md](docs/QUICK_START.md) | Step-by-step setup guide |

---

## Project Structure

```
.
├── my-gateway/                 # Core gateway runtime (Data Plane)
│   └── src/main/java/
│       ├── filter/             # Global filters (18+ filters)
│       ├── auth/               # Authentication processors
│       ├── ssl/                # SSL certificate management
│       ├── center/             # Config center SPI
│       └── discovery/          # Service discovery SPI
│
├── gateway-admin/              # Management console (Control Plane)
│   └── src/main/java/
│       ├── controller/         # REST API endpoints
│       ├── service/            # Business logic
│       └── repository/         # JPA repositories
│
├── gateway-ui/                 # Web dashboard frontend
│   └── src/
│       ├── pages/              # React page components
│       └── i18n.ts             # Internationalization (EN/CN)
│
└── demo-service/               # Sample backend service
```

---

## License

MIT License - see [LICENSE](LICENSE) for details.

---

## Support

- **Issues**: [GitHub Issues](https://github.com/leoli5695/scg-dynamic-admin/issues)
- **Discussions**: [GitHub Discussions](https://github.com/leoli5695/scg-dynamic-admin/discussions)