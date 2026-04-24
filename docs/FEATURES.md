# Features Documentation

> Complete guide to all gateway features with detailed configuration examples.

---

## Core Features

| Feature | Description | Document |
|---------|-------------|----------|
| **Route Management** | Dynamic route configuration with hot reload | [route-management.md](features/route-management.md) |
| **Multi-Service Routing** | Traffic distribution across multiple services | [multi-service-routing.md](features/multi-service-routing.md) |
| **Service Discovery** | Dynamic (Nacos/Consul) and static discovery | [service-discovery.md](features/service-discovery.md) |
| **SSL Termination** | HTTPS with dynamic certificate management | [ssl-termination.md](features/ssl-termination.md) |
| **Authentication** | JWT, API Key, Basic, HMAC, OAuth2 | [authentication.md](features/authentication.md) |

---

## Protection Features

| Feature | Description | Document |
|---------|-------------|----------|
| **Rate Limiting** | Redis + local hybrid with graceful degradation | [rate-limiting.md](features/rate-limiting.md) |
| **Circuit Breaker** | Resilience4j protection against cascading failures | [circuit-breaker.md](features/circuit-breaker.md) |
| **IP Filtering** | Blacklist/whitelist with CIDR support | [ip-filtering.md](features/ip-filtering.md) |
| **Timeout Control** | Connection and response timeout configuration | [timeout-control.md](features/timeout-control.md) |

---

## Transformation Features

| Feature | Description | Document |
|---------|-------------|----------|
| **Request Transform** | Protocol conversion, field mapping, masking | [request-transform.md](features/request-transform.md) |
| **Request Validation** | JSON Schema, required fields, type constraints | [request-validation.md](features/request-validation.md) |
| **Mock Response** | Static/dynamic responses for testing | [mock-response.md](features/mock-response.md) |
| **Response Transform** | Field filtering, mapping, format conversion | [response-transform.md](features/response-transform.md) |
| **Response Caching** | Caffeine-based GET/HEAD caching | [response-caching.md](features/response-caching.md) |

---

## Observability Features

| Feature | Description | Document |
|---------|-------------|----------|
| **Monitoring & Alerts** | JVM, CPU, HTTP metrics with threshold alerts | [monitoring-alerts.md](features/monitoring-alerts.md) |
| **Request Tracing** | Error/slow request capture with replay | [request-tracing.md](features/request-tracing.md) |
| **AI-Powered Analysis** | GPT/Claude/Qwen metrics analysis | [ai-analysis.md](features/ai-analysis.md) |
| **Email Notifications** | SMTP alerts with AI-generated content | [email-notifications.md](features/email-notifications.md) |
| **System Diagnostic** | Comprehensive health checks | [system-diagnostic.md](features/system-diagnostic.md) |
| **Traffic Topology** | Real-time traffic visualization | [traffic-topology.md](features/traffic-topology.md) |
| **Filter Chain Analysis** | Filter execution statistics | [filter-chain-analysis.md](features/filter-chain-analysis.md) |

---

## Operations Features

| Feature | Description | Document |
|---------|-------------|----------|
| **Gateway Instance Management** | Multi-instance with namespace isolation | [instance-management.md](features/instance-management.md) |
| **Kubernetes Integration** | One-click deployment to K8s clusters | [kubernetes-integration.md](features/kubernetes-integration.md) |
| **Audit Logs** | Configuration change history with rollback | [audit-logs.md](features/audit-logs.md) |

---

## Developer Tools

| Feature | Description | Document |
|---------|-------------|----------|
| **Request Replay Debugger** | Modify and replay captured requests | [request-replay.md](features/request-replay.md) |
| **AI Copilot Assistant** | Intelligent configuration assistant | [ai-copilot.md](features/ai-copilot.md) |
| **Stress Test Tool** | Concurrent load testing with AI analysis, export (PDF/Excel/JSON/Markdown), and shareable reports | [stress-test.md](features/stress-test.md) |

---

## Filter Chain Summary

```
Request Flow:
┌─────────────────────────────────────────────────────────────┐
│ Security (-500)        → Security hardening first           │
├─────────────────────────────────────────────────────────────┤
│ IP Filter (-490)       → Fast rejection (before logging)    │
├─────────────────────────────────────────────────────────────┤
│ AccessLog (-400)       → Log all requests                   │
├─────────────────────────────────────────────────────────────┤
│ CORS (-300)            → Handle preflight requests          │
├─────────────────────────────────────────────────────────────┤
│ TraceId (-300)         → Full visibility                    │
├─────────────────────────────────────────────────────────────┤
│ Request Transform (-255) → Modify request body              │
├─────────────────────────────────────────────────────────────┤
│ Request Validation (-254) → Validate request schema         │
├─────────────────────────────────────────────────────────────┤
│ Authentication (-250)  → User identity                      │
├─────────────────────────────────────────────────────────────┤
│ Mock Response (-249)   → Return mock data for testing       │
├─────────────────────────────────────────────────────────────┤
│ Timeout (-200)         → Protect downstream                 │
├─────────────────────────────────────────────────────────────┤
│ API Version (-150)     → Version-based routing              │
├─────────────────────────────────────────────────────────────┤
│ Circuit Breaker (-100) → Prevent cascade failure            │
├─────────────────────────────────────────────────────────────┤
│ Header Op (-50)        → Add/modify headers                 │
├─────────────────────────────────────────────────────────────┤
│ Response Transform (-45) → Modify response body             │
├─────────────────────────────────────────────────────────────┤
│ Cache (50)             → Response caching                   │
├─────────────────────────────────────────────────────────────┤
│ Trace Capture (100)    → Capture error/slow requests        │
├─────────────────────────────────────────────────────────────┤
│ Filter Chain Tracking (N/A) → Performance monitoring         │
├─────────────────────────────────────────────────────────────┤
│ Retry (9999)           → Retry on failure                   │
├─────────────────────────────────────────────────────────────┤
│ Multi-Service LB (10001) → Multi-service routing            │
├─────────────────────────────────────────────────────────────┤
│ Nacos Discovery LB (10100) → Namespace/group override       │
├─────────────────────────────────────────────────────────────┤
│ Discovery LB (10150)   → Forward to backend                 │
├─────────────────────────────────────────────────────────────┤
│ Actuator Endpoint (N/A) → Protect actuator endpoints        │
└─────────────────────────────────────────────────────────────┘
```

**Design Philosophy:**
1. Security first → Block malicious requests early
2. Fast rejection → IP filter before logging and auth
3. Observability → TraceId sees everything for debugging
4. Protection → Timeout/Circuit Breaker before routing
5. Retry before routing → Retry at 9999, before LB filters
6. Namespace isolation → Nacos Discovery LB for cross-namespace routing

---

For architecture details, see [ARCHITECTURE.md](ARCHITECTURE.md).
For quick start guide, see [QUICK_START.md](QUICK_START.md).