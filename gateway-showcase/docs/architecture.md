# Architecture Overview

This document provides a high-level overview of the Gateway Admin architecture.

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Client Layer                                    │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │   Web Browser   │  │   Mobile App    │  │   API Client    │             │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘             │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Gateway Layer                                   │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        Spring Cloud Gateway                           │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐     │  │
│  │  │   Routing   │ │   Auth      │ │ Rate Limit  │ │ Circuit Brk │     │  │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘     │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐     │  │
│  │  │   Tracing   │ │   Logging   │ │   Metrics   │ │   SSL/TLS   │     │  │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘     │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
          ┌───────────────────────────┼───────────────────────────┐
          ▼                           ▼                           ▼
┌─────────────────────┐   ┌─────────────────────┐   ┌─────────────────────┐
│   Admin Service     │   │   Backend Services  │   │   Infrastructure    │
│  ┌───────────────┐  │   │  ┌───────────────┐  │   │  ┌───────────────┐  │
│  │ Route Manager │  │   │  │  Service A    │  │   │  │    Nacos      │  │
│  ├───────────────┤  │   │  ├───────────────┤  │   │  ├───────────────┤  │
│  │ Service Mgr   │  │   │  │  Service B    │  │   │  │    Redis      │  │
│  ├───────────────┤  │   │  ├───────────────┤  │   │  ├───────────────┤  │
│  │ Strategy Mgr  │  │   │  │  Service C    │  │   │  │    MySQL      │  │
│  ├───────────────┤  │   │  └───────────────┘  │   │  ├───────────────┤  │
│  │ Monitoring    │  │   │                     │   │  │  Prometheus   │  │
│  ├───────────────┤  │   │                     │   │  └───────────────┘  │
│  │ Alerting      │  │   │                     │   │                     │
│  └───────────────┘  │   │                     │   │                     │
└─────────────────────┘   └─────────────────────┘   └─────────────────────┘
```

## Core Components

### 1. API Gateway (Spring Cloud Gateway)

The gateway is the entry point for all API requests. It handles:

- **Route Matching**: Directs requests to appropriate backend services
- **Authentication**: Validates credentials using configured auth strategies
- **Rate Limiting**: Protects services from excessive requests
- **Circuit Breaking**: Prevents cascade failures
- **Request/Response Transformation**: Modifies headers, paths, etc.
- **SSL Termination**: Handles HTTPS connections

### 2. Admin Service

The management console backend provides:

- Route, Service, and Strategy CRUD operations
- Real-time monitoring data aggregation
- Alert configuration and notification
- SSL certificate management
- Request tracing and replay

### 3. Frontend Application

React-based management console with:

- Modern UI using Ant Design
- Real-time updates
- Internationalization (i18n)
- Responsive design

## Data Flow

### Request Flow

```
1. Client sends request → Gateway
2. Gateway matches route configuration
3. Authentication filter validates credentials
4. Rate limiter checks request quota
5. Circuit breaker checks service health
6. Request forwarded to backend service
7. Response returned through same path
8. Metrics and traces recorded
```

### Configuration Flow

```
1. Admin Console → Admin Service API
2. Admin Service → Nacos Config Center
3. Nacos → Gateway (hot reload)
4. Gateway applies new configuration
```

## Key Design Decisions

### Shadow Quota for Rate Limiting

When Redis becomes unavailable, the gateway gracefully degrades to local rate limiting using pre-calculated quotas:

```
Normal: Request → Redis Rate Limiter → Allow/Deny
Failure: Request → Local Rate Limiter (Shadow Quota) → Allow/Deny
Recovery: Gradual traffic shift back to Redis (10% per second)
```

### Dynamic Configuration

All configurations are stored in Nacos and can be updated without restarting the gateway:

- Routes
- Services
- Strategies
- SSL Certificates

### Multi-Level Caching

The system uses multiple caching layers:

1. **Local Cache (Caffeine)**: Route definitions, strategy configs
2. **Redis Cache**: Distributed rate limiting, session data
3. **Database (MySQL)**: Persistent storage

## Technology Decisions

| Component | Technology | Rationale |
|-----------|------------|-----------|
| Gateway | Spring Cloud Gateway | Native Spring integration, reactive |
| Config Center | Nacos | Service discovery + config management |
| Cache | Redis | Industry standard, distributed |
| Database | MySQL | Reliable, widely supported |
| Frontend | React + Ant Design | Modern, component-based |
| Monitoring | Prometheus | Standard metrics format |

## Scalability

### Horizontal Scaling

- Gateway instances are stateless
- Session data stored in Redis
- Configuration stored in Nacos
- Database connection pooling

### Performance Considerations

- Netty-based reactive I/O
- Connection pooling for all external services
- Asynchronous logging
- Optimized serialization (Jackson)

## Security

### Authentication Flow

```
1. Request received with credentials
2. Auth filter extracts credentials
3. Auth processor validates (API Key/JWT/etc.)
4. User context established
5. Request continues or rejected
```

### Data Protection

- Passwords hashed with bcrypt
- Sensitive configs encrypted
- TLS for all external communication
- Audit logging for all operations

---

For more details, see:
- [API Reference](../api/openapi.yaml)
- [Configuration Guide](./configuration.md)
- [Deployment Guide](./deployment.md)