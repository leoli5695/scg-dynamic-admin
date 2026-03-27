# Gateway Admin - Enterprise API Gateway Management Platform

<p align="center">
  <img src="docs/images/logo.png" alt="Gateway Admin Logo" width="200">
</p>

<p align="center">
  <strong>A production-ready, enterprise-grade API gateway management platform</strong>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#demo">Demo</a> •
  <a href="#documentation">Documentation</a> •
  <a href="#license">License</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2.4-brightgreen" alt="Spring Boot">
  <img src="https://img.shields.io/badge/React-18-blue" alt="React 18">
  <img src="https://img.shields.io/badge/License-Commercial-red" alt="License">
</p>

---

## Overview

Gateway Admin is a comprehensive API gateway management platform built on Spring Cloud Gateway. It provides a complete solution for managing, monitoring, and securing your APIs with an intuitive web-based administration console.

### Why Gateway Admin?

| Feature | Native Spring Cloud Gateway | Gateway Admin |
|---------|----------------------------|---------------|
| Route Management | Config file editing | ✅ Web UI, hot reload |
| Rate Limiting | Manual implementation | ✅ Distributed + local fallback |
| Authentication | Manual implementation | ✅ 5 auth methods built-in |
| Monitoring | Manual integration | ✅ Prometheus + dashboards |
| Alerting | Not available | ✅ Email + AI analysis |
| SSL Management | Manual configuration | ✅ Dynamic certificate loading |
| Request Tracing | Not available | ✅ Full tracing + replay |

---

## Features

### Core Gateway Features

- **Dynamic Route Management** - Create, update, delete routes without restart
- **Service Discovery** - Support for Nacos, Consul, and static services
- **Load Balancing** - Weighted round-robin with health checks

### Security

- **Multiple Authentication Methods**
  - API Key
  - Basic Auth
  - JWT
  - HMAC Signature
  - OAuth2
- **IP Filtering** - Whitelist/blacklist with CIDR support
- **SSL/TLS** - Dynamic certificate loading, multi-domain support

### Traffic Control

- **Distributed Rate Limiting** - Redis-based with local fallback
- **Shadow Quota** - Graceful degradation when Redis fails
- **Circuit Breaker** - Configurable failure thresholds
- **Retry Policy** - Automatic retry with backoff

### Observability

- **Real-time Metrics** - CPU, memory, QPS, latency
- **Prometheus Integration** - Standard metrics export
- **Request Tracing** - Error and slow request tracking
- **Request Replay** - Debug and test with real requests

### AI-Powered Analysis

- **Intelligent Alerts** - AI-enhanced alert content
- **Root Cause Analysis** - Automatic problem diagnosis
- **Optimization Suggestions** - Performance recommendations

### Management Console

- **React-based UI** - Modern, responsive interface
- **Internationalization** - English and Chinese support
- **Role-based Access** - Secure multi-user support

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Management Console (React)                   │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Admin Service (Spring Boot)                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │ Routes   │ │ Services │ │ Strategies│ │ Monitoring│          │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘           │
└─────────────────────────────────────────────────────────────────┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        ▼                        ▼                        ▼
  ┌───────────┐           ┌───────────┐           ┌───────────┐
  │   Nacos   │           │   Redis   │           │   MySQL   │
  └───────────┘           └───────────┘           └───────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│                   API Gateway (Spring Cloud Gateway)             │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │   Auth   │ │Rate Limit│ │Circuit Brk│ │  Tracing │           │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘           │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
                         Backend Services
```

---

## Tech Stack

### Backend
- **Java 17** - Latest LTS
- **Spring Boot 3.2** - Application framework
- **Spring Cloud Gateway 4.x** - API Gateway
- **Spring Security 6.x** - Authentication & authorization
- **Spring Data JPA** - Data access
- **Nacos** - Service discovery & configuration
- **Redis** - Distributed caching & rate limiting
- **MySQL** - Primary database
- **Prometheus** - Metrics collection

### Frontend
- **React 18** - UI framework
- **TypeScript** - Type safety
- **Ant Design 5** - UI components
- **Recharts** - Charts & visualization

---

## Demo

### Screenshots

#### Dashboard
![Dashboard](screenshots/dashboard.png)

#### Route Management
![Routes](screenshots/routes.png)

#### Monitoring
![Monitoring](screenshots/monitoring.png)

#### AI Analysis
![AI Analysis](screenshots/ai-analysis.png)

### Video Demo

Watch the full demo on YouTube: [Gateway Admin Demo](https://youtube.com/placeholder)

---

## Quick Start

### Prerequisites

- Java 17+
- Node.js 18+
- Docker & Docker Compose
- MySQL 8.0+
- Redis 6.0+
- Nacos 2.x

### Using Docker Compose

```bash
# Clone the repository
git clone https://github.com/your-username/gateway-admin.git
cd gateway-admin

# Start infrastructure
docker-compose up -d nacos redis mysql prometheus

# Start backend services
./mvnw spring-boot:run -pl gateway-admin
./mvnw spring-boot:run -pl gateway-core

# Start frontend
cd gateway-ui
npm install
npm start
```

### Access the Console

Open http://localhost:3000 in your browser.

Default credentials:
- Username: `admin`
- Password: `admin123`

---

## Documentation

- [Architecture Overview](docs/architecture.md)
- [Getting Started Guide](docs/getting-started.md)
- [API Reference](api/openapi.yaml)
- [Configuration Guide](docs/configuration.md)
- [Deployment Guide](docs/deployment.md)

---

## API Reference

### Core APIs

| Module | Endpoint | Description |
|--------|----------|-------------|
| Routes | `GET /api/routes` | List all routes |
| Routes | `POST /api/routes` | Create a route |
| Services | `GET /api/services` | List all services |
| Strategies | `GET /api/strategies` | List all strategies |
| Monitoring | `GET /api/monitor/metrics` | Get real-time metrics |
| Alerts | `GET /api/alerts/history` | Get alert history |

Full API documentation: [OpenAPI Specification](api/openapi.yaml)

---

## Roadmap

### v1.1 (Q2 2024)
- [ ] Kubernetes Operator
- [ ] Multi-tenancy support
- [ ] OpenTelemetry integration
- [ ] Plugin system

### v1.2 (Q3 2024)
- [ ] GraphQL support
- [ ] gRPC proxy
- [ ] Advanced analytics
- [ ] Custom dashboards

---

## License

This project is licensed under a **Commercial License**.

For licensing inquiries, custom development, or enterprise support, please contact:

- Email: your-email@example.com
- LinkedIn: [Your LinkedIn](https://linkedin.com/in/your-profile)

### What's Included

| | Open Source | Commercial |
|---|-------------|------------|
| Demo Project | ✅ | ✅ |
| Full Source Code | ❌ | ✅ |
| Documentation | Basic | Full |
| Support | Community | Priority |
| Custom Development | ❌ | ✅ |

---

## Contributing

This is a closed-source project. However, we welcome:

- Bug reports
- Feature suggestions
- Documentation improvements

Please open an issue for any feedback.

---

## Contact

- **Author**: Your Name
- **Email**: your-email@example.com
- **LinkedIn**: [Your LinkedIn](https://linkedin.com/in/your-profile)
- **Upwork**: [Hire me on Upwork](https://upwork.com/freelancers/your-profile)

---

## Acknowledgments

Built with ❤️ using:
- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)
- [React](https://react.dev/)
- [Ant Design](https://ant.design/)

---

<p align="center">
  <strong>Interested in the full source code or custom development?</strong>
  <br>
  <a href="mailto:your-email@example.com">Contact me</a> for licensing inquiries.
</p>