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
| **Retry** | Configurable retry with fixed interval on 500/502/503/504 | [retry.md](features/retry.md) |

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
| **Access Log** | JSON logging with K8S real-time viewing; entries are shipped to `POST /api/access-log/collect` and stored in the `access_log_entries` table (no Elasticsearch integration — not implemented) | [access-log.md](features/access-log.md) |
| **Request Tracing** | Error/slow request capture with replay | [request-tracing.md](features/request-tracing.md) |
| **AI-Powered Analysis** | GPT/Claude/Qwen metrics analysis | [ai-analysis.md](features/ai-analysis.md) |
| **Email Notifications** | SMTP alerts rendered by `AlertEmailBuilder` (not AI-generated) | [email-notifications.md](features/email-notifications.md) |
| **System Diagnostic** | Comprehensive health checks | [system-diagnostic.md](features/system-diagnostic.md) |
| **Traffic Topology** | Real-time traffic visualization | [traffic-topology.md](features/traffic-topology.md) |
| **Filter Chain Analysis** | Filter execution statistics | [filter-chain-analysis.md](features/filter-chain-analysis.md) |
| **Historical Comparison** | Period-over-period comparison of metrics, filter stats and config changes | [historical-comparison.md](features/historical-comparison.md) |
| **Performance Optimization** | JWT cache, shadow quota, non-blocking locks and other throughput optimizations | [performance-optimization.md](features/performance-optimization.md) |
| **AI Token Quota Ledger** | Per-request UUID ledger for AI token pre-deduction, settlement, refund and local degradation | [ai-quota-ledger.md](features/ai-quota-ledger.md) |
| **AI Usage Delivery** | Durable local outbox with `eventId` for reliable AI token-usage reporting to the admin | [ai-usage-delivery.md](features/ai-usage-delivery.md) |
| **AI Usage Reconciliation** | Reconcile and compensate `UNKNOWN`/`ESTIMATED` usage and `PENDING`/`FAILED` settlements | [ai-usage-reconciliation.md](features/ai-usage-reconciliation.md) |
| **AI Content Security** | `AI_SECURITY` in-flight text moderation: word lists, regex, PII handling and optional external moderators behind the `gateway-moderation-spi` contract | [ai-content-security.md](features/ai-content-security.md) |

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
| **Route Debug Page** | Per-route debug page that pre-fills a request from the route's Path/Method/Host/Header predicates and streams the response | [route-debug.md](features/route-debug.md) |
| **AI Copilot Assistant** | Intelligent configuration assistant | [ai-copilot.md](features/ai-copilot.md) |
| **Stress Test Tool** | Concurrent load testing with AI analysis, Markdown export (the `format` query parameter is accepted but currently ignored — PDF/Excel/JSON export is not implemented), and shareable reports | [stress-test.md](features/stress-test.md) |

---

## Filter Chain Summary

```
Request Flow (GlobalFilter order, ascending = execution order):
┌─────────────────────────────────────────────────────────────┐
│ TraceId (HIGHEST_PRECEDENCE)  → Trace ID first, sees all    │
├─────────────────────────────────────────────────────────────┤
│ FilterChainTracking (MIN+2)   → Per-filter timing stats     │
├─────────────────────────────────────────────────────────────┤
│ IP Filter (-490)              → Fast rejection before WAF   │
├─────────────────────────────────────────────────────────────┤
│ Security (-480)               → XSS/SQLi hardening          │
├─────────────────────────────────────────────────────────────┤
│ AccessLog (-400)              → Log all requests            │
├─────────────────────────────────────────────────────────────┤
│ CORS (-301)                   → Handle preflight requests   │
├─────────────────────────────────────────────────────────────┤
│ Authentication (-256)         → JWT/Key/Basic/HMAC/OAuth2   │
├─────────────────────────────────────────────────────────────┤
│ Request Transform (-255)      → Modify request body         │
├─────────────────────────────────────────────────────────────┤
│ Request Validation (-254)     → Validate request schema     │
├─────────────────────────────────────────────────────────────┤
│ Mock Response (-249)          → Return mock data (testing)  │
├─────────────────────────────────────────────────────────────┤
│ AI Security Guard (-246)      → Content moderation in/out   │
├─────────────────────────────────────────────────────────────┤
│ Hybrid Rate Limiter (-245)    → Redis + local rate limiting │
├─────────────────────────────────────────────────────────────┤
│ Multi-Dim RateLimit (-244)    → Per-identity rate limiting  │
├─────────────────────────────────────────────────────────────┤
│ AI Context Limit (-243)       → Reject oversized AI inputs  │
├─────────────────────────────────────────────────────────────┤
│ AI Semantic Cache (-242)      → Cache hit skips quota spend │
├─────────────────────────────────────────────────────────────┤
│ Token Quota PreCheck (-241)   → Pre-deduct token quota      │
├─────────────────────────────────────────────────────────────┤
│ Timeout (-200)                → Protect downstream          │
├─────────────────────────────────────────────────────────────┤
│ API Version (-150)            → Version-based routing       │
├─────────────────────────────────────────────────────────────┤
│ Header Op (-50)               → Add/modify headers          │
├─────────────────────────────────────────────────────────────┤
│ Trace Capture (-47)           → Capture error/slow requests │
├─────────────────────────────────────────────────────────────┤
│ Cache (-46)                   → Response caching            │
├─────────────────────────────────────────────────────────────┤
│ Response Transform (-45)      → Modify response body        │
├─────────────────────────────────────────────────────────────┤
│ Token Usage Collector (-44)   → Parse LLM token usage       │
├─────────────────────────────────────────────────────────────┤
│ AI Statistics (-43)           → TTFT / usage statistics     │
├─────────────────────────────────────────────────────────────┤
│ Circuit Breaker (-3)          → Prevent cascade failure     │
├─────────────────────────────────────────────────────────────┤
│ Retry (-2)                    → Retry on failure            │
├─────────────────────────────────────────────────────────────┤
│ NettyWriteResponse (-1)       → SCG built-in write phase    │
├─────────────────────────────────────────────────────────────┤
│ Multi-Service LB (10001)      → Multi-service routing       │
├─────────────────────────────────────────────────────────────┤
│ API Version Routing (10002)   → Apply version targets       │
├─────────────────────────────────────────────────────────────┤
│ AI Prompt Template (10004)    → Prompt decoration           │
├─────────────────────────────────────────────────────────────┤
│ AI Provider Routing (10005)   → Rewrite target LLM URL      │
├─────────────────────────────────────────────────────────────┤
│ Nacos Discovery LB (10100)    → Namespace/group override    │
├─────────────────────────────────────────────────────────────┤
│ Discovery LB (10140)          → Service discovery + LB      │
├─────────────────────────────────────────────────────────────┤
│ Reactive LB Client (10150)    → SCG built-in lb:// resolve  │
└─────────────────────────────────────────────────────────────┘
```

Source of truth: `my-gateway/src/main/java/com/leoli/gateway/constants/FilterOrderConstants.java`
(an `interface`, so the constants carry no explicit `public static final`).

Two `WebFilter`s are **not** part of the `GlobalFilter` ordering above and therefore run ahead of the
whole chain: `InternalApiAuthWebFilter` (`Ordered.HIGHEST_PRECEDENCE + 10`, guards `/internal/**`)
and `ActuatorEndpointFilter` (`Ordered.HIGHEST_PRECEDENCE + 10`, only active when
`management.server.port` is explicitly set). Likewise `FilterOrderConstants.TRACE_ID` (-300) is only
a historical reference — `TraceIdGlobalFilter.getOrder()` actually returns
`Ordered.HIGHEST_PRECEDENCE`, so TraceId executes first.

**Why Cache is -46 and Trace Capture is -47, not positive values:** SCG only routes the client-bound
response body through `ServerHttpResponseDecorator`s installed by filters ordered *before*
`NettyWriteResponseFilter` (-1). Both filters used to sit at +50 and +100, where their `writeWith`
was never invoked on the real Netty proxy path — the response cache was never populated (every
request went back to origin) and every captured trace had `responseBody == null`. Those were two P0
bugs, fixed by moving them inside -1; the reasoning is recorded in the javadoc of
`FilterOrderConstants.CACHE` and `FilterOrderConstants.TRACE_CAPTURE`.

**Design Philosophy:**
1. Cheap rejection first → IP filter (-490) runs *before* the WAF (-480), so an already-blacklisted address never makes the gateway buffer and regex-scan a request body
2. Fast rejection → IP filter before logging and auth
3. Observability → TraceId runs at `HIGHEST_PRECEDENCE` and sees everything for debugging
4. Protection → Timeout/Circuit Breaker before routing
5. Retry before routing → Retry at **-2** (post-phase runs after `NettyWriteResponseFilter` at -1), still ahead of the LB filters at 10001+
6. Namespace isolation → Nacos Discovery LB for cross-namespace routing

---

## Implemented Surface Without a Dedicated Feature Document

The tables above index the 36 documents under `docs/features/` (count at commit `b45d40a`;
re-count with `ls docs/features/*.md | wc -l`). The following implemented pieces have
no standalone feature document yet and are only described in the code / console UI:

**`gateway-admin` REST controllers** (46 at commit `b45d40a` — re-count with
`ls gateway-admin/src/main/java/com/leoli/gateway/admin/controller/*.java | wc -l`;
the ones not covered above):
`PluginController`, `RoutePluginBindingController`, `LicenseController`,
`GatewayTokenRevocationController`, `TokenQuotaController`, `TokenUsageController`,
`TokenUsageReconciliationController`, `ProviderCredentialController`, `ServiceMiddlewareController`,
`MultiInstanceComparisonController`, `FullChainTraceController`, `RequestJourneyController`
(`/api/request-journeys/{traceId}`, the cross-hop request journey added with route debugging)

**Maven modules**: `seckill-core-engine` (standalone flash-sale workload engine — Redis Lua rate
limiting, MQ order delivery/stock release; **not deployed to production**, and it is the only module
that depends on `gateway-trace-starter`) and `license-server` (license issuing service backing the
offline RSA verification performed by `license-client`).

---

For architecture details, see [ARCHITECTURE.md](ARCHITECTURE.md).
For quick start guide, see [QUICK_START.md](QUICK_START.md).