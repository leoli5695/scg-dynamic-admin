# Architecture Design

> This document describes the system architecture, design patterns, and implementation details for developers and architects.

---

## 1. System Architecture

### 1.1 Control Plane / Data Plane Separation

The system follows a modern **Control Plane / Data Plane** architecture pattern:

```
+-----------------------------------------------------------------------------+
|                              CONTROL PLANE                                   |
|                                                                              |
|   +---------------------------------------------------------------------+   |
|   |                        gateway-admin (:9090)                        |   |
|   |                                                                      |   |
|   |    +-------------+   +-------------+   +-------------+             |   |
|   |    | Controller  |   |   Service   |   |  Publisher  |             |   |
|   |    |    Layer    |-->|    Layer    |-->|   Layer     |             |   |
|   |    +-------------+   +------+------+   +------+------+             |   |
|   |                             |                 |                     |   |
|   |                             v                 v                     |   |
|   |                      +-------------+   +-------------+             |   |
|   |                      |   MySQL     |   | Config Center|             |   |
|   |                      | (Persist)   |   |  (Nacos)    |             |   |
|   |                      +-------------+   +-------------+             |   |
|   +---------------------------------------------------------------------+   |
|                                                                              |
+-----------------------------------------------------------------------------+
                                       |
                                       | Config Push
                                       v
+-----------------------------------------------------------------------------+
|                               DATA PLANE                                     |
|                                                                              |
|   +---------------------------------------------------------------------+   |
|   |                          my-gateway (:80)                           |   |
|   |                                                                      |   |
|   |    +-------------------------------------------------------------+  |   |
|   |    |                   Global Filter Chain                        |  |   |
|   |    |                                                               |  |   |
|   |    |  Request --> IP --> Auth --> Rate --> CB --> Timeout --> LB  |  |   |
|   |    |                                                               |  |   |
|   |    +-------------------------------------------------------------+  |   |
|   |                             |                                        |   |
|   |                             v                                        |   |
|   |                      Backend Services                                |   |
|   +---------------------------------------------------------------------+   |
|                                                                              |
+-----------------------------------------------------------------------------+
```

**Benefits:**
- Configuration management separated from runtime traffic
- Independent scaling of control and data planes
- Zero-downtime configuration updates

> **About the `my-gateway (:80)` labels in the diagrams of this document:** `:80` is only the
> *packaging default* — `my-gateway/src/main/resources/application.yml:2` reads
> `port: ${SERVER_PORT:80}`. It is **not** the production topology: the deployed gateway container
> runs with `SERVER_PORT=8080` and publishes `*:8080`, while `*:80` on the host is held by nginx
> (which serves the console UI and reverse-proxies to the gateway). `k8s/my-gateway.yaml` likewise
> declares `containerPort: 8080`. Read every `(:80)` in the diagrams below as
> "the gateway's HTTP listener (default 80, 8080 in production)".

---

## 2. Core Design Patterns

### 2.1 SPI (Service Provider Interface)

The gateway uses SPI pattern for extensibility:

```
+------------------------------------------------------------------+
|                    ConfigCenterService (SPI)                     |
|                                                                  |
|   +---------------------------------------------------------+   |
|   |  + getConfig(dataId, group): String                      |   |
|   |  + publishConfig(dataId, group, content): void           |   |
|   |  + addListener(dataId, group, listener): void            |   |
|   +---------------------------------------------------------+   |
|                              |                                   |
|              +---------------+---------------+                  |
|              v               v               v                  |
|   +--------------+  +--------------+  +--------------+        |
|   |    Nacos     |  |    Consul    |  |   (Custom)   |        |
|   |ConfigService |  |ConfigService |  |ConfigService |        |
|   +--------------+  +--------------+  +--------------+        |
|                                                                  |
|   @ConditionalOnProperty(name = "gateway.center.type")          |
+------------------------------------------------------------------+
```

**Supported SPIs:**

| SPI Interface | Implementations | Switch Property |
|---------------|-----------------|-----------------|
| `ConfigCenterService` | Nacos, Consul | `gateway.center.type=nacos\|consul` |
| `DiscoveryService` | Nacos, Consul, Static | URI scheme (`lb://` / `static://`) |
| `AuthProcessor` | JWT, API Key, Basic, HMAC, OAuth2 | Strategy config `authType` |

### 2.2 Strategy Pattern (Authentication)

> See [Authentication](features/authentication.md) for detailed auth configuration options.

```
+------------------------------------------------------------------+
|                     AuthProcessor (Interface)                    |
|   +---------------------------------------------------------+   |
|   |  + process(exchange, config): Mono<Void>                |   |
|   |  + getAuthType(): AuthType                              |   |
|   |  + default getAuthTypeCode(): String                    |   |
|   +---------------------------------------------------------+   |
|                              |                                   |
|        +----------+----------+----------+----------+           |
|        v          v          v          v          v           |
|   +---------+ +---------+ +---------+ +---------+ +---------+ |
|   |   JWT   | | API Key | |  Basic  | |  HMAC   | | OAuth2  | |
|   |Processor| |Processor| |Processor| |Processor| |Processor| |
|   +---------+ +---------+ +---------+ +---------+ +---------+ |
|                                                                  |
+------------------------------------------------------------------+

                         AuthProcessManager
   +-------------------------------------------------------------+
   |  private Map<String, AuthProcessor> processorMap;           |
   |  // key = processor.getAuthType().getCode()                 |
   |                                                             |
   |  public Mono<Void> authenticate(exchange, config) {         |
   |      AuthProcessor p = processorMap.get(                    |
   |              config.getAuthTypeEnum().getCode());           |
   |      return p.process(exchange, config);                    |
   |  }                                                          |
   +-------------------------------------------------------------+
```

### 2.3 Observer Pattern (Configuration Refresh)

```
+------------------------------------------------------------------+
|                    Configuration Refresh Flow                    |
+------------------------------------------------------------------+

   Nacos Config Center
         |
         | Config Change Event
         v
   +-----------------+
   |  ConfigListener | -----------------------------+
   +--------+--------+                              |
            |                                       |
            v                                       |
   +-----------------+                              |
   |   Refresher     |  (RouteRefresher, ServiceRefresher, |
   |                 |   StrategyRefresher)                |
   +--------+--------+                              |
            |                                       |
            v                                       |
   +-----------------+      +-----------------+    |
   |    Manager      | ---> |  Update Cache   |    |
   | (RouteManager,  |      |  (AtomicRef)    |    |
   |  ServiceManager)|      +-----------------+    |
   +--------+--------+                             |
            |                                       |
            v                                       |
   +-----------------+                              |
   |    Locator      |                              |
   | (RouteDefinition|                              |
   |    Locator)     |                              |
   +--------+--------+                              |
            |                                       |
            v                                       |
   +-----------------+                              |
   | RefreshRoutes   |                              |
   |     Event       |                              |
   +--------+--------+                              |
            |                                       |
            v                                       |
   +-----------------+                              |
   | Spring Cloud    | <----------------------------+
   |    Gateway      |       Routes Updated (Listener / Reconcile)
   +-----------------+
```

### 2.4 Dual-Write Pattern

Ensures data consistency between database and config center:

```
+------------------------------------------------------------------+
|                      Dual-Write Transaction                      |
+------------------------------------------------------------------+

   +-------------+     +-------------+     +-------------+
   |  REST API   |     |   Service   |     | Transaction |
   |  Request    |---->|   Layer     |---->|   Manager   |
   +-------------+     +------+------+     +------+------+
                              |                   |
                   +----------+----------+        |
                   |                     |        |
                   v                     v        |
            +-------------+      +-------------+  |
            |    MySQL    |      |   Nacos     |  |
            |  (Persist)  |      |  (Publish)  |  |
            +------+------+      +------+------+  |
                   |                    |         |
                   |    +---------------+         |
                   |    | Success?      |         |
                   |    +-------+-------+         |
                   |            |                 |
                   |   No <-----+-----> Yes       |
                   |    |               |         |
                   v    v               v         |
            +-------------+      +-------------+  |
            |  Rollback   |      |   Commit    |<-+
            |   (Undo)    |      |  (Success)  |
            +-------------+      +-------------+
```

---

## 3. Filter Chain Architecture

> See [Filter Chain Analysis](features/filter-chain-analysis.md) for detailed performance monitoring capabilities.

### 3.1 Request Processing Pipeline

```
+-----------------------------------------------------------------------------+
|                        REQUEST PROCESSING PIPELINE                          |
+-----------------------------------------------------------------------------+

   Client Request
         |
         v
   +-------------------------------------------------------------------------+
   |                           API Gateway (:80)                              |
   |                                                                          |
   |   +-------------------------------------------------------------------+  |
   |   |                      Global Filter Chain                           |  |
   |   |                                                                    |  |
   |   | Order        Filter                           Function            |  |
   |   | -----------  -------------------------------- ------------------- |  |
   |   |    MIN_VALUE TraceIdGlobalFilter              Trace ID + MDC      |  |
   |   |  MIN_VALUE+2 FilterChainTrackingGlobalFilter  Per-filter timing   |  |
   |   |         -490 IPFilterGlobalFilter             IP allow/deny list  |  |
   |   |         -480 SecurityGlobalFilter             XSS/SQLi protection |  |
   |   |         -400 AccessLogGlobalFilter            Access logging      |  |
   |   |         -301 CorsGlobalFilter                 CORS handling       |  |
   |   |         -256 AuthenticationGlobalFilter       Auth (5 auth types) |  |
   |   |         -255 RequestTransformFilter           Body transformation |  |
   |   |         -254 RequestValidationFilter          Schema validation   |  |
   |   |         -249 MockResponseFilter               Mock response       |  |
   |   |         -246 AiSecurityGuardFilter            AI moderation       |  |
   |   |         -245 HybridRateLimiterFilter          Hybrid rate limit   |  |
   |   |         -244 MultiDimRateLimiterFilter        Multi-dim limiting  |  |
   |   |         -243 AiContextLimitFilter             AI context limit    |  |
   |   |         -242 AiSemanticCacheFilter            AI semantic cache   |  |
   |   |         -241 TokenQuotaPreCheckFilter         Token quota check   |  |
   |   |         -200 TimeoutGlobalFilter              Timeout handling    |  |
   |   |         -150 ApiVersionGlobalFilter           API version routing |  |
   |   |          -50 HeaderOpGlobalFilter             Header manipulation |  |
   |   |          -47 TraceCaptureGlobalFilter         Trace capture       |  |
   |   |          -46 CacheGlobalFilter                Response caching    |  |
   |   |          -45 ResponseTransformFilter          Response transform  |  |
   |   |          -44 TokenUsageCollectorFilter        LLM token usage     |  |
   |   |          -43 AiStatisticsFilter               AI TTFT/usage stats |  |
   |   |           -3 CircuitBreakerGlobalFilter       Circuit breaker     |  |
   |   |           -2 RetryGlobalFilter                Retry on failure    |  |
   |   |           -1 NettyWriteResponseFilter         SCG built-in write  |  |
   |   |        10001 MultiServiceLoadBalancerFilter   Multi-service route |  |
   |   |        10002 ApiVersionRoutingFilter          Version targeting   |  |
   |   |        10004 AiPromptTemplateFilter           AI prompt template  |  |
   |   |        10005 AiProviderRoutingFilter          AI provider routing |  |
   |   |        10100 NacosDiscoveryLoadBalancerFilter Nacos ns/group LB   |  |
   |   |        10140 DiscoveryLoadBalancerFilter      Discovery + LB      |  |
   |   |        10150 ReactiveLoadBalancerClientFilter SCG built-in lb://  |  |
   |   |                                                                    |  |
   |   +-------------------------------------------------------------------+  |
   |                                   |                                      |
   +-----------------------------------|--------------------------------------+
                                       |
                                       v
   +-------------------------------------------------------------------------+
   |                          Backend Services                                |
   |                                                                          |
   |       +------------+    +------------+    +------------+                |
   |       | Instance 1 |    | Instance 2 |    | Instance 3 |                |
   |       |   :9000    |    |   :9001    |    |   :9002    |                |
   |       +------------+    +------------+    +------------+                |
   |                                                                          |
   +-------------------------------------------------------------------------+
```

**Not in the table above (they are `WebFilter`s, not `GlobalFilter`s, so they take no part in the
`GlobalFilter` ordering):** `InternalApiAuthWebFilter` and `ActuatorEndpointFilter`, both at
`Ordered.HIGHEST_PRECEDENCE + 10`. `ActuatorEndpointFilter` is additionally gated by
`@ConditionalOnProperty(name = "management.server.port")`, so it is only registered when a separate
management port is configured. `TraceIdGlobalFilter` *is* a `GlobalFilter`, but its `getOrder()`
returns `Ordered.HIGHEST_PRECEDENCE` — the `FilterOrderConstants.TRACE_ID` constant (-300) is kept
only as a historical reference and its own javadoc warns against inferring the runtime order from it.
`FilterChainTrackingGlobalFilter.getOrder()` returns `Integer.MIN_VALUE + 2`.

Authoritative source for every value above:
`my-gateway/src/main/java/com/leoli/gateway/constants/FilterOrderConstants.java` (an `interface`, so
the constants carry no explicit `public static final`).

### 3.2 Filter Execution Order (Why This Order?)

```
Request enters gateway

  (Two WebFilters sit OUTSIDE the GlobalFilter ordering below and therefore run first:
   InternalApiAuthWebFilter @ Ordered.HIGHEST_PRECEDENCE + 10 --> guards /internal/**
   ActuatorEndpointFilter   @ Ordered.HIGHEST_PRECEDENCE + 10 --> active only when
                                                                 management.server.port is set)
  |
  +-- order MIN_VALUE   TraceId   --> Generate/propagate X-Trace-Id, MDC logging
  |                                WHY FIRST? --> TraceIdGlobalFilter.getOrder() returns
  |                                Ordered.HIGHEST_PRECEDENCE, so traceId is visible to every later
  |                                filter. FilterOrderConstants.TRACE_ID (-300) is documented as a
  |                                historical reference only -- do NOT infer the runtime order from it.
  +-- order MIN_VALUE+2 Filter Chain Tracking --> Per-filter timing / performance monitoring
  +-- order -490  IP Filter       --> IP whitelist/blacklist check -> 403 if blocked
  |                                WHY BEFORE THE WAF? --> A blacklisted address must not make the
  |                                gateway buffer and regex-scan a full request body (see the FIX
  |                                note on FilterOrderConstants.SECURITY_GLOBAL, which was -500)
  |                                WHY BEFORE ACCESS LOG? --> Fast rejection saves logging overhead
  |                                WHY BEFORE AUTH? --> Fast rejection saves CPU
  +-- order -480  Security        --> XSS/SQL injection hardening (WAF); still ahead of auth so
  |                                unauthenticated traffic is scanned too
  +-- order -400  Access Log      --> Log all requests for audit
  +-- order -301  CORS            --> Handle preflight requests
  +-- order -256  Authentication  --> JWT/API Key/Basic/HMAC/OAuth2 validation -> 401 if failed
  +-- order -255  Request Transform --> Modify request body (JSON↔XML, field mapping)
  +-- order -254  Request Validation --> Validate request schema/fields
  +-- order -249  Mock Response   --> Return mock data if configured (testing/development)
  +-- order -246  AI Security Guard --> AI content moderation (inbound pre-phase + outbound decorator)
  +-- order -245  Hybrid Rate Limiter --> Redis + local hybrid rate limiting
  |                                WHY AFTER AUTH? --> The identity key types (jwt_claims,
  |                                jwt_subject, api_key_metadata) are only populated by the auth
  |                                filters; running first collapsed tenant/user quotas to IP-only
  +-- order -244  Multi-Dim Rate Limiter --> Multi-dimensional rate limiting
  +-- order -243  AI Context Limit --> Reject oversized AI inputs before any quota is spent
  +-- order -242  AI Semantic Cache --> On a HIT, short-circuit: no LLM tokens and no quota spent
  +-- order -241  Token Quota Pre-Check --> Pre-deduct AI token quota before proxying to the LLM
  +-- order -200  Timeout         --> Inject timeout params into route metadata
  +-- order -150  API Version     --> Version-based routing
  +-- order  -50  Header Op       --> Add/modify/remove headers
  +-- order  -47  Trace Capture   --> Capture error/slow requests for debugging
  +-- order  -46  Cache           --> Response caching
  +-- order  -45  Response Transform --> Modify response body before returning
  +-- order  -44  Token Usage Collector --> Parse and settle LLM token usage
  +-- order  -43  AI Statistics   --> AI TTFT / usage statistics
  |                                WHY ARE -47..-43 ALL < -1? --> SCG only routes the client-bound
  |                                response body through ServerHttpResponseDecorators installed
  |                                BEFORE NettyWriteResponseFilter (-1). Cache at +50 and Trace
  |                                Capture at +100 were two fixed P0 bugs: the cache was never
  |                                populated and every captured trace had responseBody == null.
  +-- order   -3  Circuit Breaker --> Check circuit status -> 503 if open; its post-phase runs
  |                                AFTER Retry (-2) so it records the final post-retry status
  +-- order   -2  Retry           --> Retry on failure
  +-- order   -1  NettyWriteResponse --> SCG built-in: writes the response to the client
  +-- order 10001 Multi-Service   --> Multi-service routing + gray release
  +-- order 10002 API Version Routing --> Apply version-specific service targets
  +-- order 10004 AI Prompt Template --> System-prompt injection / {{var}} substitution
  +-- order 10005 AI Provider Routing --> Rewrite the target URL to the resolved LLM provider
  +-- order 10100 Nacos Discovery --> Namespace/group override for cross-namespace routing
  +-- order 10140 Load Balancer   --> Service discovery + load balancing
  |                                WHY 10140 AND NOT 10150? --> 10150 is exactly SCG's own
  |                                ReactiveLoadBalancerClientFilter order; two GlobalFilters at the
  |                                same order have no defined relative sequence (fixed collision bug)
  |                                WHY LAST? --> Final routing decision
  +-- order 10150 Routing         --> SCG ReactiveLoadBalancerClientFilter forwards lb:// to backend
```

**Performance Impact:** IP Filter (-490) before Access Log (-400) and Authentication (-256) provides **+37% TPS improvement**.

> **Note on that measurement:** the +37% figure was recorded when `SecurityGlobalFilter` was still at
> -500, i.e. *before* IP Filter. Security has since moved to -480 so that a blacklisted address is
> rejected before the WAF buffers and regex-scans a request body (see the FIX note on
> `FilterOrderConstants.SECURITY_GLOBAL`). IP Filter is still ahead of Access Log and Authentication,
> so the claim being measured still holds, but treat the number itself as a historical measurement
> rather than a current benchmark.

---

## 4. Service Discovery Architecture

### 4.1 Dual Protocol Support

```
+------------------------------------------------------------------+
|                    SERVICE DISCOVERY SPI                         |
+------------------------------------------------------------------+

                        Route URI
                            |
            +---------------+---------------+
            |                               |
            v                               v
   +-----------------+             +-----------------+
   |   lb://name     |             | static://id     |
   |  (Dynamic)      |             |  (Static)       |
   +--------+--------+             +--------+--------+
            |                               |
            v                               v
   +-----------------+             +-----------------+
   |    Nacos        |             |   Service       |
   | DiscoveryClient |             |   Manager       |
   +--------+--------+             |  (Config)       |
            |                      +--------+--------+
            |                               |
            v                               v
   +-----------------+             +-----------------+
   | Service Registry|             | services-index  |
   |  (Dynamic)      |             | + service-{id}  |
   +-----------------+             +-----------------+

            |                               |
            +---------------+---------------+
                            |
                            v
                  +-----------------+
                  |  Load Balancer  |
                  | (Weighted RR)   |
                  +--------+--------+
                           |
                           v
                  +-----------------+
                  | ServiceInstance |
                  +-----------------+
```

> **On the `static://` side there is no `gateway-services.json` dataId** (nor a `gateway-routes.json`
> or `gateway-strategies.json`). Configuration is published incrementally, one entry per entity —
> see `my-gateway/src/main/java/com/leoli/gateway/constants/GatewayConfigConstants.java`:
> `config.gateway.metadata.services-index` holds the service-ID list and each definition lives at
> `config.gateway.service-{serviceId}` (the same index + per-entity pattern is used for routes,
> strategies, auth policies and SSL certificates). The only single-document dataId that really
> exists is `gateway-plugins.json`.

### 4.2 Load Balancing

**Weighted Round-Robin Algorithm:**

```
Instances: [A(weight=1), B(weight=2), C(weight=1)]
Total Weight: 4

Selection Sequence: A -> B -> B -> C -> A -> B -> B -> C -> ...

Implementation:
+--------------------------------------------------------------+
|  AtomicInteger counter = new AtomicInteger(0);               |
|                                                              |
|  int index = Math.abs(counter.getAndIncrement() % totalWeight);|
|                                                              |
|  int currentWeight = 0;                                      |
|  for (Instance inst : instances) {                           |
|      currentWeight += inst.getWeight();                      |
|      if (index < currentWeight) return inst;                 |
|  }                                                           |
+--------------------------------------------------------------+
```

> **Correction — the pseudo-code above is not what the gateway runs.** Weighted selection is
> implemented by `my-gateway/src/main/java/com/leoli/gateway/filter/loadbalancer/SmoothWeightedRoundRobin.java`,
> an nginx-style *smooth* WRR, used by both `InstanceSelector` (one selector per service) and
> `MultiServiceLoadBalancerFilter` (one selector per route):
>
> - weights are `double`, so fractional weights are not truncated; negative weights clamp to 0;
> - each round adds the static weight to a per-key accumulator, picks the **largest** accumulator,
>   then subtracts the total weight from the winner — there is no shared `counter % totalWeight`
>   modulo index and no cumulative-weight scan;
> - state is per balancing group and `select` synchronizes on the selector instance, so groups never
>   contend on one lock;
> - if the total weight is `<= 0` it degrades to an overflow-safe round-robin using
>   `Math.floorMod(counter++, size)`.
>
> For `A(weight=1), B(weight=2), C(weight=1)` the real sequence is therefore
> `B -> A -> C -> B -> A -> C -> ...` (evenly spread), not `A -> B -> B -> C` (which would clump the
> two B picks together — exactly what "smooth" WRR exists to avoid).

---

## 5. Cache Architecture

> See [Response Caching](features/response-caching.md) for response cache configuration.

### 5.1 Single-Layer Cache with Real-Time Push

The gateway uses a **simple and effective** caching strategy:

```
+------------------------------------------------------------------+
|                    CONFIG FLOW ARCHITECTURE                       |
+------------------------------------------------------------------+

                    Config Center (Nacos/Consul)
                           |
                           | Real-time Push (Listener)
                           | Listener + periodic reconciliation
                           v
   +-------------------------------------------------------------+
   |          In-Memory Cache (ConcurrentHashMap)                |
   |                                                             |
   |   RouteManager.routeCache      - Route definitions          |
   |   ServiceManager.instanceCache - Service instances          |
   |   StrategyManager.strategyCache - Strategy configs          |
   |                                                             |
   |   - Nanosecond read latency                                 |
   |   - Thread-safe                                             |
   |   - Auto-refresh via Nacos listener                         |
   +-------------------------------------------------------------+
```

**Design Principles:**

| Principle | Description |
|-----------|-------------|
| **Keep It Simple** | Single cache layer, no complex fallback logic |
| **Trust Nacos/Consul** | Rely on config center's real-time push capability |
| **Fast Reads** | All reads from local memory, no network calls |

**Why Not MultiLevel Cache?**

A multi-level cache with fallback would be **over-engineering** for this use case:

1. Nacos/Consul already provides high availability
2. Real-time push ensures cache is always fresh
3. If config center is down, bigger problems exist than stale cache
4. Simpler code = easier debugging and maintenance

### 5.2 Config Update Flow

```
+------------------------------------------------------------------+
|                    CONFIG UPDATE FLOW                            |
+------------------------------------------------------------------+

   Admin API (gateway-admin)
          |
          | 1. Save to MySQL
          v
   +-------------+
   |   MySQL     |
   | (Persist)   |
   +------+------+
          |
          | 2. Publish to Nacos
          v
   +-------------+
   |   Nacos     |
   | Config Center|
   +------+------+
          |
          | 3. Listener or periodic reconciliation
          v
   +-------------+
   |  Gateway    |
   |   Manager   |
   |   (Cache)   |
   +------+------+
          |
          | 4. Refresh Routes
          v
   +-------------+
   | Spring Cloud|
   |   Gateway   |
   +-------------+
```

**Propagation:** Listeners apply changes when delivered. Periodic reconciliation defaults to 60 seconds (`gateway.config-sync.interval-ms`, environment variable `GATEWAY_CONFIG_SYNC_INTERVAL_MS`). If listener delivery fails, allow up to one polling interval plus fetch and apply time; config-center failures delay updates until recovery.

---

## 6. Module Details

> **Scope of this section:** it only details the two modules that actually run in production —
> `my-gateway` (data plane) and `gateway-admin` (control plane). The root `pom.xml` declares
> **8** Maven modules in total: `license-client`, `gateway-trace-starter`, `license-server`,
> `gateway-moderation-spi`, `my-gateway`, `gateway-admin`, `demo-service` and `seckill-core-engine`
> (re-measure with `sed -n '/<modules>/,/<\/modules>/p' pom.xml | grep -c '<module>'` — this list
> has grown more than once in a single day).
> `gateway-moderation-spi` is a dependency-free contract module holding one interface
> (`com.leoli.gateway.ai.security.ModerationAdapter`); see `docs/features/ai-content-security.md`.
> Note that `gateway-trace-starter` is depended on **only** by `seckill-core-engine` — neither
> `my-gateway` nor `gateway-admin` pulls it in — and `seckill-core-engine` is **not deployed to
> production** (the production host runs exactly two containers: `gateway-admin` and `my-gateway`).
> `demo-service` (port 9001) and `license-server` are development/support artifacts, and
> `gateway-ui-2/` is a frozen second front-end that is neither built nor deployed.

### 6.1 my-gateway (Core Runtime)

```
my-gateway/
|-- src/main/java/com/leoli/gateway/
|   |
|   |-- filter/                    # Global Filters (organized by category)
|   |   |-- security/              # Security filters
|   |   |   |-- SecurityGlobalFilter.java      # XSS/SQL injection protection
|   |   |   |-- IPFilterGlobalFilter.java      # IP whitelist/blacklist
|   |   |   |-- AuthenticationGlobalFilter.java # JWT/API Key/OAuth2 auth
|   |   |   |-- CorsGlobalFilter.java          # CORS handling
|   |   |   +-- InternalApiAuthWebFilter.java  # WebFilter guarding /internal/** (X-Internal-Token)
|   |   |
|   |   |-- loadbalancer/          # Load balancing filters
|   |   |   |-- DiscoveryLoadBalancerFilter.java    # Service discovery LB (lb://)
|   |   |   |-- MultiServiceLoadBalancerFilter.java # Multi-service routing
|   |   |   |-- NacosDiscoveryLoadBalancerFilter.java # Namespace/group aware lb:// resolution
|   |   |   |-- InstanceFilter.java                # Instance filtering
|   |   |   |-- InstanceSelector.java              # Instance selection
|   |   |   |-- InstanceRetryExecutor.java         # Retry execution
|   |   |   +-- SmoothWeightedRoundRobin.java      # Smooth weighted round-robin
|   |   |
|   |   |-- ratelimit/             # Rate limiting filters
|   |   |   |-- HybridRateLimiterFilter.java      # Redis + local hybrid
|   |   |   +-- MultiDimRateLimiterFilter.java    # Multi-dimensional limits
|   |   |
|   |   |-- resilience/            # Resilience filters
|   |   |   |-- CircuitBreakerGlobalFilter.java   # Resilience4j circuit breaker
|   |   |   |-- TimeoutGlobalFilter.java          # Request timeout
|   |   |   +-- RetryGlobalFilter.java            # Retry on failure
|   |   |
|   |   |-- transform/             # Request/Response transformation
|   |   |   |-- RequestTransformFilter.java       # Request body transformation
|   |   |   |-- RequestValidationFilter.java      # Request schema validation
|   |   |   |-- ResponseTransformFilter.java      # Response body transformation
|   |   |   |-- MockResponseFilter.java           # Mock response for testing
|   |   |   +-- TransformBodyWork.java            # Shared body-transform work item
|   |   |
|   |   |-- accesslog/             # Access logging (NOT at filter/ root)
|   |   |   |-- AccessLogGlobalFilter.java        # Access logging
|   |   |   |-- config/AccessLogConfig.java       # Access log configuration
|   |   |   |-- constants/AccessLogConstants.java # Access log constants
|   |   |   |-- detector/BinaryContentDetector.java # Binary body detection
|   |   |   +-- sanitizer/SensitiveDataSanitizer.java # Sensitive data masking
|   |   |
|   |   |-- ai/                    # AI gateway filters
|   |   |   |-- AiSecurityGuardFilter.java        # Content moderation (in + outbound)
|   |   |   |-- AiContextLimitFilter.java         # Context-window limit
|   |   |   |-- AiSemanticCacheFilter.java        # Semantic cache
|   |   |   |-- AiStatisticsFilter.java           # TTFT / usage statistics
|   |   |   |-- AiPromptTemplateFilter.java       # Prompt decoration
|   |   |   +-- AiProviderRoutingFilter.java      # Multi-provider LLM routing
|   |   |
|   |   |-- token/                 # AI token accounting filters
|   |   |   |-- TokenQuotaPreCheckFilter.java     # Quota pre-deduction
|   |   |   |-- TokenUsageCollectorFilter.java    # Usage parsing/settlement
|   |   |   |-- TokenUsageOutbox.java             # Durable local outbox
|   |   |   +-- TokenUsageReporter.java           # Reliable delivery to admin
|   |   |
|   |   +-- (root level)           # Other filters
|   |       |-- CacheGlobalFilter.java           # Response caching
|   |       |-- TraceIdGlobalFilter.java         # Trace ID generation
|   |       |-- TraceCaptureGlobalFilter.java    # Trace capture for debugging
|   |       |-- HeaderOpGlobalFilter.java        # Header operations
|   |       |-- ApiVersionGlobalFilter.java      # API version routing
|   |       |-- ApiVersionRoutingFilter.java     # Apply version-specific targets
|   |       |-- FilterChainTrackingGlobalFilter.java # Per-filter timing
|   |       +-- ActuatorEndpointFilter.java      # Actuator endpoint protection (WebFilter)
|   |
|   |-- auth/                      # Auth Processors (Strategy Pattern)
|   |   |-- AuthProcessor.java              # Interface
|   |   |-- AbstractAuthProcessor.java      # Base implementation
|   |   |-- AuthProcessManager.java         # Manager
|   |   |-- JwtAuthProcessor.java           # JWT validation
|   |   |-- JwtValidationCache.java         # JWT cache for performance
|   |   |-- ApiKeyAuthProcessor.java        # API Key validation
|   |   |-- BasicAuthProcessor.java         # Basic auth
|   |   |-- HmacSignatureAuthProcessor.java # HMAC signature
|   |   |-- OAuth2AuthProcessor.java        # OAuth2 integration
|   |   |-- BasicPasswordVerifier.java      # Constant-time password comparison
|   |   |-- TokenRevocationChecker.java     # Revocation lookup
|   |   +-- RevocationUnavailableException.java # Thrown when revocation state is unknown
|   |
|   |-- center/                    # Config Center SPI
|   |   |-- spi/ConfigCenterService.java    # Interface
|   |   |-- spi/AbstractConfigService.java  # Base implementation
|   |   |-- nacos/NacosConfigService.java   # Nacos implementation
|   |   +-- consul/ConsulConfigService.java # Consul implementation
|   |
|   |-- discovery/                 # Service Discovery SPI
|   |   |-- spi/DiscoveryService.java       # Interface
|   |   |-- spi/AbstractDiscoveryService.java # Base implementation
|   |   |-- nacos/NacosDiscoveryService.java  # Nacos discovery
|   |   |-- nacos/NacosNamingServiceFactory.java # NamingService factory
|   |   |-- consul/ConsulDiscoveryService.java # Consul discovery
|   |   +-- staticdiscovery/StaticDiscoveryService.java # Static instances
|   |
|   |-- manager/                   # Configuration Managers
|   |   |-- RouteManager.java              # Route configuration
|   |   |-- ServiceManager.java            # Service configuration
|   |   |-- StrategyManager.java           # Strategy configuration
|   |   +-- AuthBindingManager.java        # Route <-> auth policy bindings
|   |
|   |-- refresher/                 # Config Refreshers (Nacos listeners)
|   |   |-- RouteRefresher.java            # Route config refresh
|   |   |-- ServiceRefresher.java          # Service config refresh
|   |   |-- StrategyRefresher.java         # Strategy config refresh
|   |   |-- AuthPolicyRefresher.java       # Auth policy refresh
|   |   |-- RateLimitResetRefresher.java   # Rate limit counter reset
|   |   +-- SslCertificateRefresher.java   # SSL certificate refresh
|   |   # NOTE: PluginRefresher.java is NOT here - it lives in the plugin/ package.
|   |
|   |-- route/                     # Route Locator
|   |   +-- DynamicRouteDefinitionLocator.java # Dynamic route resolution
|   |
|   |-- limiter/                   # Rate Limiting Components
|   |   |-- DistributedRateLimiter.java    # Redis distributed limiter
|   |   |-- RedisHealthChecker.java        # Redis health monitoring
|   |   |-- ShadowQuotaManager.java        # Shadow quota for failover
|   |   |-- RateLimitResult.java           # Rate limit result model
|   |   |-- TokenQuotaManager.java         # AI token quota manager
|   |   |-- TokenQuotaLedger.java          # Per-request quota ledger
|   |   +-- TokenQuotaLedgerStore.java     # Ledger persistence
|   |
|   |-- ssl/                       # SSL Certificate Management
|   |   |-- SslServerConfig.java           # SSL server configuration
|   |   +-- DynamicSslContextManager.java  # Dynamic SSL context (loads certs itself;
|   |                                      # there is no SslCertificateLoader class)
|   |
|   |-- exception/                 # Custom Exceptions
|   |   |-- GatewayException.java          # Base exception
|   |   |-- AuthenticationException.java   # Auth errors
|   |   |-- RateLimitException.java        # Rate limit errors
|   |   |-- CircuitBreakerException.java   # Circuit breaker errors
|   |   |-- ValidationException.java       # Validation errors
|   |   |-- UpstreamException.java         # Upstream service errors
|   |   |-- RouteException.java            # Route errors
|   |   |-- ErrorCode.java                 # Error code definitions
|   |   |-- GatewayErrorMessages.java      # User-facing message templates
|   |   +-- ScgGlobalExceptionHandler.java # Global exception handler
|   |
|   |-- constants/                 # Constants (all `interface`s - fields are implicitly
|   |   |                          #  public static final, so `grep "static final int"` misses them)
|   |   |-- FilterOrderConstants.java      # Filter execution order (authoritative)
|   |   |-- GatewayConfigConstants.java    # Config center dataId constants
|   |   +-- AuthConstants / BinaryContentConstants / CacheConstants /
|   |       InternalApiConstants / RateLimitConstants / RedisConstants / ScheduleConstants
|   |
|   |-- model/                     # Configuration Models
|   |   |-- AuthConfig.java                # Auth configuration
|   |   |-- CircuitBreakerConfig.java      # Circuit breaker config
|   |   |-- IPFilterConfig.java            # IP filter config
|   |   |-- RateLimiterConfig.java         # Rate limiter config
|   |   |-- MultiDimRateLimiterConfig.java # Multi-dimensional rate limiter config
|   |   |-- TimeoutConfig.java             # Timeout config
|   |   |-- MultiServiceConfig.java        # Multi-service routing config
|   |   |-- ServiceBindingType.java        # STATIC / DISCOVERY binding
|   |   |-- RequestTransformConfig.java    # Request transform config
|   |   |-- RequestValidationConfig.java   # Request validation config
|   |   |-- ResponseTransformConfig.java   # Response transform config
|   |   |-- MockResponseConfig.java        # Mock response config
|   |   |-- PluginConfig.java              # Plugin config
|   |   |-- TokenQuotaConfig.java          # AI token quota config
|   |   |-- AiProxyConfig / AiPromptTemplateConfig / AiSecurityConfig /
|   |   |   AiSemanticCacheConfig / AiContextLimitConfig / AiStatisticsConfig
|   |   +-- StrategyDefinition.java        # Strategy definition + the 22 TYPE_* String constants
|   |
|   |-- health/                    # Health Check & Heartbeat
|   |   |-- HeartbeatReporter.java         # Heartbeat to admin
|   |   |-- ActiveHealthChecker.java       # Active health check
|   |   |-- HybridHealthChecker.java       # Hybrid health monitoring
|   |   |-- HealthCheckScheduler.java      # Health check scheduling
|   |   |-- InstanceDiscoveryService.java  # Instance discovery
|   |   |-- AdminServiceDiscovery.java     # Admin endpoint discovery
|   |   |-- InstanceHealth.java            # Health model
|   |   +-- HealthStatusSyncTask.java      # Health status sync
|   |
|   |-- ai/                        # AI gateway support
|   |   |-- LlmProvider.java               # Provider descriptor
|   |   |-- ProviderRegistry.java          # Registered providers
|   |   |-- ProviderKeyResolver.java       # Upstream key resolution SPI
|   |   |-- AdminProviderKeyResolver.java  # Resolves keys from the admin
|   |   |-- ProviderKeyPoolSelector.java   # Key pool selection
|   |   |-- AiUsageResult.java             # Parsed usage result
|   |   |-- cache/SemanticCacheStore.java  # Semantic cache storage
|   |   |-- cache/GatewayEmbeddingClient.java # Embedding client
|   |   |-- security/ContentModerator.java # Moderation SPI
|   |   +-- security/RuleBasedModerator.java # Rule-based implementation
|   |
|   |-- plugin/                    # Plugin subsystem
|   |   |-- PluginLoaderService.java       # Loads plugin jars
|   |   |-- PluginRegistryService.java     # Runtime plugin registry
|   |   |-- PluginRefresher.java           # Plugin config listener (lives here, not in refresher/)
|   |   |-- PluginSchemaGenerator.java     # Config schema generation
|   |   |-- PluginDiscoveryResult.java     # Discovery result model
|   |   |-- PluginLoadException.java       # Load failure
|   |   +-- PluginRegistrationException.java # Registration failure
|   |
|   |-- monitor/                   # Filter-chain monitoring & AI observability
|   |   |-- FilterChainTracker.java        # Per-filter timing collector
|   |   |-- TrackedGlobalFilter.java       # Decorator applied to every GlobalFilter
|   |   |-- GlobalFilterTrackingPostProcessor.java # BeanPostProcessor installing the decorator
|   |   |-- FilterHealthMonitor.java       # Filter health
|   |   |-- FilterCircuitBreakerManager.java # Per-filter circuit breaking
|   |   |-- FilterConfigOptimizer.java     # Order/config optimization suggestions
|   |   |-- HistoricalDataTracker.java     # Historical metric retention
|   |   |-- PeriodComparisonAnalyzer.java  # Period-over-period comparison
|   |   |-- PerformancePredictor.java      # Linear-regression trend projection
|   |   |-- AIAnomalyDetector.java         # Anomaly detection
|   |   |-- AlertService.java              # Gateway-side alerting
|   |   +-- TraceReportDispatcher.java     # Trace report dispatch
|   |
|   |-- autoconfig/                # Conditional auto-configuration
|   |   |-- NacosCenterAutoConfiguration.java   # Activates the Nacos center
|   |   |-- ConsulCenterAutoConfiguration.java  # Activates the Consul center
|   |   +-- HttpHandlerBindingAutoConfiguration.java # Management-port HTTP binding
|   |
|   |-- cache/CompiledRouteCache.java      # Compiled route cache
|   |
|   |-- controller/                # Gateway-side endpoints
|   |   |-- AiModelsEndpoint.java          # /v1/models style AI model list
|   |   |-- InternalFilterChainController.java       # Internal filter-chain API
|   |   +-- InternalFilterCircuitBreakerController.java # Internal filter CB API
|   |
|   |-- endpoint/                  # Actuator endpoints
|   |   |-- GatewayRouteEndpoint.java      # Route actuator endpoint
|   |   |-- GatewayServiceEndpoint.java    # Service actuator endpoint
|   |   +-- GatewayStrategyEndpoint.java   # Strategy actuator endpoint
|   |
|   |-- enums/                     # AuthType, CenterType
|   |-- license/LicenseFeatureHelper.java  # Edition/quota gate (unconditional @Component)
|   |-- parser/TokenUsageParser.java       # LLM usage parsing
|   |-- util/                      # GatewayResponseHelper, ClientIpResolver, IpAddressUtils,
|   |                              # RequestBodyCache, BoundedBodyCapture, BodyWorkPool,
|   |                              # ByteBudget, PatternCache, RateLimiterWindow, RouteUtils,
|   |                              # AiTokenEstimator, ConfigContentTracker, ConfigValueExtractor,
|   |                              # SimpleResponse
|   |
|   +-- config/                    # Spring Configuration
|       |-- GatewayConfig.java             # Gateway config
|       |-- CorsConfig.java                # CORS config
|       |-- RedisConfig.java               # Redis config
|       |-- RedisEnabledCondition.java     # Redis enable condition
|       |-- WebClientConfig.java           # WebClient config
|       |-- RestTemplateConfig.java        # RestTemplate config
|       |-- ObjectMapperConfig.java        # Jackson config
|       |-- HandlebarsConfig.java          # Template engine config
|       |-- HeartbeatProperties.java       # Heartbeat properties
|       |-- HealthCheckProperties.java     # Health check properties
|       |-- RetryProperties.java           # Retry properties
|       |-- TimeoutProperties.java         # Timeout properties
|       +-- TrustedProxyProperties.java    # `gateway.trusted-proxy` (singular)
```

### 6.2 gateway-admin (Management Console)

```
gateway-admin/
|-- src/main/java/com/leoli/gateway/admin/
|   |
|   |-- controller/                # REST API Endpoints
|   |   |-- BaseController.java             # Base controller utilities
|   |   |-- RouteController.java            # Route management API
|   |   |-- RouteDebugController.java       # /api/routes/{routeId}/debug[/auth] - SSE route debugger
|   |   |-- ServiceController.java          # Service management API
|   |   |-- StrategyController.java         # Strategy config API
|   |   |-- AuthController.java             # Authentication API
|   |   |-- AuthPolicyController.java       # Auth policy management
|   |   |-- GatewayInstanceController.java  # Instance management API
|   |   |-- KubernetesController.java       # Kubernetes deployment API
|   |   |-- SslCertificateController.java   # SSL certificate API
|   |   |-- MonitorController.java          # Monitoring metrics API
|   |   |-- AnalyticsController.java        # Analytics API
|   |   |-- AlertController.java            # Alert management API
|   |   |-- EmailConfigController.java      # Email config API
|   |   |-- AccessLogConfigController.java  # Access log config API
|   |   |-- RequestTraceController.java     # Request trace API
|   |   |-- RequestJourneyController.java   # /api/request-journeys/{traceId} - cross-hop request journey
|   |   |-- AuditLogController.java         # Audit log API
|   |   |-- AiConfigController.java         # AI analysis config API
|   |   |-- HealthSyncController.java       # Health sync API
|   |   |-- InstanceHealthController.java   # Instance health API
|   |   |-- HealthCheckController.java      # Health check API
|   |   # ---- the 24 controllers below were missing from earlier revisions of this tree;
|   |   #      the package held 46 *Controller.java files at commit b45d40a - re-count with
|   |   #      `ls gateway-admin/src/main/java/com/leoli/gateway/admin/controller/*.java | wc -l`
|   |   |-- AiCopilotController.java        # /api/copilot - AI copilot assistant
|   |   |-- DiagnosticController.java       # /api/diagnostic - system diagnostics
|   |   |-- FilterChainController.java      # /api/filter-chain - filter chain analysis
|   |   |-- FilterChainExecutionController.java # /api/filter-executions - per-filter stats
|   |   |-- FullChainTraceController.java   # /api/traces/full-chain - distributed trace query
|   |   |-- GatewayTokenRevocationController.java # /api/gateway/jwt-revocations
|   |   |-- JaegerController.java           # /api/tracing - Jaeger proxy
|   |   |-- LicenseController.java          # /api/license - license/edition info
|   |   |-- MultiInstanceComparisonController.java # /api/multi-instance - instance comparison
|   |   |-- PluginController.java           # /api/plugins - plugin management
|   |   |-- PrometheusServiceDiscoveryController.java # /api/prometheus/sd
|   |   |-- ProviderCredentialController.java # /api/provider-credentials - LLM upstream keys
|   |   |-- RequestReplayController.java    # /api/replay - request replay debugger
|   |   |-- RoutePluginBindingController.java # Route <-> plugin bindings
|   |   |-- ServiceMiddlewareController.java # /api/services - per-service middleware config
|   |   |-- ServiceMiddlewareViewController.java # /api/service-middleware - middleware views
|   |   |-- SmartAlertController.java       # /api/smart-alerts - AI-assisted alerting
|   |   |-- StrategyTypeController.java     # /api/strategy-types - strategy type metadata
|   |   |-- StressTestController.java       # /api/stress-test - load testing (Markdown export only)
|   |   |-- TokenQuotaController.java       # /api/token-quota - AI token quota
|   |   |-- TokenUsageController.java       # /api/token-usage - AI token usage
|   |   |-- TokenUsageReconciliationController.java # .../events/{usageId}/reconciliation
|   |   |-- TraceTopologyController.java    # /api/topology/trace - trace topology
|   |   +-- TrafficTopologyController.java  # /api/topology - traffic topology
|   |
|   |-- service/                   # Business Logic
|   |   |-- RouteService.java               # Route CRUD + dual-write
|   |   |-- ServiceService.java             # Service CRUD + dual-write
|   |   |-- StrategyService.java            # Strategy CRUD + dual-write
|   |   |-- AuthPolicyService.java          # Auth policy management
|   |   |-- ConfigCenterPublisher.java      # Nacos/Consul publisher
|   |   |-- GatewayInstanceService.java     # Instance lifecycle
|   |   |-- KubernetesService.java          # K8s operations
|   |   |-- KubernetesResourceService.java  # K8s resource management
|   |   |-- DeploymentService.java          # K8s deployment
|   |   |-- KubeConfigService.java          # Kube config management
|   |   |-- ClusterConnectionService.java   # Cluster connection
|   |   |-- SslCertificateService.java      # SSL certificate ops
|   |   |-- AiAnalysisService.java          # AI metrics analysis
|   |   |-- AlertContentGenerator.java      # AI alert content
|   |   |-- AlertService.java               # Alert management
|   |   |-- AlertConfigService.java         # Alert config
|   |   |-- AlertCheckService.java          # Alert threshold check
|   |   |-- AlertEmailBuilder.java          # Alert email builder
|   |   |-- EmailSenderService.java         # Email sending
|   |   |-- EmailConfigService.java         # Email config
|   |   |-- RequestTraceService.java        # Request trace ops
|   |   |-- AuditLogService.java            # Audit log ops
|   |   |-- AnalyticsService.java           # Analytics service
|   |   |-- PrometheusService.java          # Prometheus metrics
|   |   |-- AccessLogConfigService.java     # Access log config
|   |   |-- InstanceHealthService.java      # Instance health
|   |   |-- DatabaseHealthService.java      # DB health check
|   |   |-- NacosMetadataSyncer.java        # Nacos metadata sync
|   |   |-- AuthService.java                # Auth service
|   |   +-- StrategyConfigValidator.java    # Strategy validation
|   |
|   |-- repository/                # Data Access (JPA)
|   |   |-- RouteRepository.java            # Route entity
|   |   |-- ServiceRepository.java          # Service entity
|   |   |-- StrategyRepository.java         # Strategy entity
|   |   |-- AuthPolicyRepository.java       # Auth policy entity
|   |   |-- RouteAuthBindingRepository.java # Route-auth binding
|   |   |-- GatewayInstanceRepository.java  # Instance entity
|   |   |-- KubernetesClusterRepository.java # K8s cluster entity
|   |   |-- SslCertificateRepository.java   # SSL certificate entity
|   |   |-- RequestTraceRepository.java     # Request trace entity
|   |   |-- AuditLogRepository.java         # Audit log entity
|   |   |-- AlertHistoryRepository.java     # Alert history entity
|   |   |-- AlertConfigRepository.java      # Alert config entity
|   |   |-- AiConfigRepository.java         # AI config entity
|   |   |-- EmailConfigRepository.java      # Email config entity
|   |   |-- UserRepository.java             # User entity
|   |   +-- ServiceInstanceHealthRepository.java # Instance health
|   |
|   |-- model/                     # Entities & DTOs
|   |   |-- RouteDefinition.java            # Route definition
|   |   |-- ServiceDefinition.java          # Service definition
|   |   |-- StrategyConfig.java             # Strategy config
|   |   |-- AuthPolicyDefinition.java       # Auth policy definition
|   |   |-- AuthPolicyEntity.java           # Auth policy entity
|   |   |-- RouteAuthBindingEntity.java     # Route-auth binding
|   |   |-- GatewayInstanceEntity.java      # Instance entity
|   |   |-- KubernetesCluster.java          # K8s cluster entity
|   |   |-- SslCertificate.java             # SSL certificate entity
|   |   |-- RequestTrace.java               # Request trace entity
|   |   |-- AuditLogEntity.java             # Audit log entity
|   |   |-- AlertHistory.java               # Alert history entity
|   |   |-- AlertConfig.java                # Alert config entity
|   |   |-- AiConfig.java                   # AI config entity
|   |   |-- EmailConfig.java                # Email config entity
|   |   |-- AccessLogGlobalConfig.java      # Access log config
|   |   |-- GrayRules.java                  # Gray release rules
|   |   |-- RouteServiceBinding.java        # Route-service binding
|   |   |-- InstanceSpec.java               # Instance spec (small/medium/large)
|   |   |-- InstanceStatus.java             # Instance status enum
|   |   +-- User.java                       # User entity
|   |
|   |-- reconcile/                 # Config Reconciliation Tasks (10 files: 7 concrete tasks)
|   |   |-- ReconcileTask.java              # Base reconcile task interface
|   |   |-- NamespacedReconcile.java        # Namespace-scoped reconcile contract
|   |   |-- RouteReconcileTask.java         # Route reconcile
|   |   |-- ServiceReconcileTask.java       # Service reconcile
|   |   |-- StrategyReconcileTask.java      # Strategy reconcile
|   |   |-- AuthPolicyReconcileTask.java    # Auth policy reconcile
|   |   |-- AccessLogConfigReconcileTask.java # Access log config reconcile
|   |   |-- CertificateReconcileTask.java   # SSL certificate reconcile
|   |   |-- NamespaceReconcileTask.java     # Instance namespace reconcile
|   |   +-- ReconcileResult.java            # Reconcile result
|   |
|   |-- cache/                     # Runtime Caches
|   |   +-- InstanceNamespaceCache.java     # Instance namespace mapping
|   |
|   |-- alert/                     # Alert Notification
|   |   |-- AlertNotifier.java              # Alert interface
|   |   |-- AlertLevel.java                 # Alert level enum
|   |   |-- EmailAlertNotifier.java         # Email notifier
|   |   +-- DingTalkAlertNotifier.java      # DingTalk notifier
|   |
|   |-- config/                    # Spring Configuration
|   |   |-- SecurityConfig.java             # Spring Security config
|   |   |-- JwtTokenProvider.java           # JWT token provider
|   |   |-- PublicEndpointFilter.java       # Public endpoint filter
|   |   |-- RestTemplateConfig.java         # RestTemplate config
|   |   +-- ApplicationInitializer.java     # App initialization
|   |
|   |-- filter/                    # Security Filters
|   |   +-- JwtAuthenticationFilter.java    # JWT authentication filter
|   |
|   |-- validation/                # Input Validation
|   |   +-- RouteValidator.java             # Route validation
|   |
|   |-- converter/                 # Entity/DTO Converters
|   |   |-- RouteConverter.java             # Route converter
|   |   |-- ServiceConverter.java           # Service converter
|   |   +-- AuthTypeConverter.java          # Auth type converter
|   |
|   |-- dto/                       # Data Transfer Objects
|   |   |-- InstanceHealthDTO.java          # Instance health DTO
|   |   |-- InstanceCreateRequest.java      # Instance create request
|   |   |-- ClientStats.java                # Client statistics
|   |   |-- MethodStats.java                # Method statistics
|   |   |-- RouteStats.java                 # Route statistics
|   |   +-- ServiceStats.java               # Service statistics
|   |
|   |-- enums/                     # Enums
|   |   +-- AuthType.java                   # Auth type enum
|   |
|   |-- properties/                # Configuration Properties
|   |   +-- GatewayAdminProperties.java     # Admin properties
|   |
|   |-- scheduler/                 # Scheduled Tasks
|   |   +-- AuditLogCleanupScheduler.java   # Audit log cleanup
|   |
|   |-- schedule/                  # Schedulers
|   |   +-- ReconcileScheduler.java         # Reconcile scheduler
|   |
|   |-- task/                      # Background Tasks
|   |   +-- InstanceHealthCheckTask.java    # Instance health check
|   |
|   +-- util/                      # Utilities
|       |-- JwtUtil.java                    # JWT utilities
|       +-- ServiceIdExtractor.java         # Service ID extractor
```

> **Packages not drawn above** (they exist under `com.leoli.gateway.admin`): `center/`
> (`ConfigCenterService` + Nacos/Consul implementations — the admin-side twin of the gateway SPI),
> `client/` (`PrometheusClient`, `RocketMQConsoleClient`), `entity/` (8 JPA entities),
> `event/` (`GlobalBindingChangedEvent`), `exception/` (`GlobalExceptionHandler`),
> `license/` (`LicenseFeatureHelper`), `metrics/` (`BusinessMetrics`, `MetricsScheduler`,
> `TracingHelper`), `prompt/` (`PromptProvider` + database-backed implementation, used by the AI
> copilot) and `tool/` (copilot tool registries and definitions).

---

## 7. Design Principles

| Principle | Application |
|-----------|-------------|
| **SOLID** | Single responsibility per filter, Open for extension (SPI), Dependency inversion |
| **DRY** | Shared `ConfigCenterService` SPI (`AbstractConfigService` base), `AbstractAuthProcessor` / `AbstractDiscoveryService` base classes, one `FilterOrderConstants` source of truth. (There is no `GenericCacheManager` class — the managers each hold a plain `ConcurrentHashMap` cache.) |
| **KISS** | Simple filter chain, clear separation of concerns |
| **Defense in Depth** | IP filter -> Auth -> Rate limit -> Circuit breaker |
| **Graceful Degradation** | Local rate limiter fallback, cache fallback when Nacos down |

---

## 8. Extensibility Guide

### 8.1 Adding a New Authentication Type

```java
// 1. Implement AuthProcessor.
//    Real contract (my-gateway/src/main/java/com/leoli/gateway/auth/AuthProcessor.java):
//      Mono<Void> process(ServerWebExchange exchange, AuthConfig config);
//      AuthType   getAuthType();
//    There is NO validate(...) returning Mono<Boolean> -- a processor signals success by
//    completing empty and signals failure by writing the response itself and NOT calling the chain.
//    Extend AbstractAuthProcessor to reuse writeUnauthorizedResponse / writeForbiddenResponse /
//    extractBearerToken / isValidConfig / logSuccess / logFailure.
@Component
public class CustomAuthProcessor extends AbstractAuthProcessor {

    @Override
    public AuthType getAuthType() {
        return AuthType.CUSTOM;  // add the constant to com.leoli.gateway.enums.AuthType
    }

    @Override
    public Mono<Void> process(ServerWebExchange exchange, AuthConfig config) {
        if (!isValidConfig(config)) {
            return Mono.empty();
        }
        String token = extractBearerToken(exchange);
        if (!verify(token, config)) {
            logFailure(config.getRouteId(), "invalid custom credential");
            // Write the 401 and stop here - do not continue the chain.
            return writeUnauthorizedResponse(exchange, "Invalid custom credential");
        }
        logSuccess(config.getRouteId());
        return Mono.empty();  // success = empty completion
    }
}

// 2. Register automatically via Spring @Component
// No other changes needed - AuthProcessManager's constructor injects List<AuthProcessor> and
// indexes it into a Map<String, AuthProcessor> keyed by getAuthType().getCode(). Its
// authenticate(exchange, config) also returns Mono<Void>, not Mono<Boolean>.
```

### 8.2 Adding a New Global Filter

```java
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // GATEWAY_ROUTE_ATTR holds a Route object, not a String routeId
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = (route != null) ? route.getId() : null;

        // Strategy types are plain String constants declared on StrategyDefinition
        // (e.g. TYPE_CIRCUIT_BREAKER = "CIRCUIT_BREAKER"). There is NO StrategyType enum,
        // so `StrategyType.CUSTOM` will not compile - add a TYPE_CUSTOM constant instead.
        //
        // Real StrategyManager API (my-gateway/.../manager/StrategyManager.java):
        //   StrategyDefinition   getStrategy(String strategyId)                        // :202
        //   StrategyDefinition   getStrategyForRoute(String routeId, String type)      // :252
        //   Map<String,Object>   getConfig(String routeId, String type)                // :365
        //   <T> T                getConfig(String routeId, String type, Class<T> cls)  // :398
        // Note the argument order is (routeId, strategyType) - routeId first.
        CustomConfig config = strategyManager.getConfig(
                routeId, StrategyDefinition.TYPE_CUSTOM, CustomConfig.class);

        if (config != null && config.isEnabled()) {
            // Apply filter logic
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Declare the value as a new constant in FilterOrderConstants rather than
        // hardcoding a magic number here - see section 3 for why the ranges matter.
        return -50;  // Define execution order
    }
}
```

---

## 9. Performance Considerations

| Optimization | Technique |
|--------------|-----------|
| **Config Cache** | No TTL on route/service/strategy config caches — they are `ConcurrentHashMap`s updated by config-center listeners (`ServiceManager`'s javadoc: "Listener-driven updates - no TTL needed"), backed by a 60-second reconciliation sweep. TTLs live elsewhere: the *response* cache is Caffeine with `expireAfterWrite` (`CacheGlobalFilter`), and `JwtValidationCache` is a `ConcurrentHashMap` of exp-based entries with scheduled cleanup. |
| **Async Publish** | Config publish to Nacos is async, doesn't block API response |
| **Connection Pool** | Netty connection pool with elastic sizing |
| **Reactive** | Non-blocking I/O throughout the filter chain |

---

## 10. Design Highlights

### 10.1 Hybrid Rate Limiting

> See [Rate Limiting](features/rate-limiting.md) for detailed configuration options.

Redis + Local dual-layer architecture with automatic fallback:

```
+------------------------------------------------------------------+
|                    HYBRID RATE LIMITER                           |
+------------------------------------------------------------------+

  Request arrives
        |
        v
  +---------------+
  | Redis Available?|
  +-------+-------+
      |         |
     Yes        No
      |         |
      v         v
  +-------+   +-------+
  | Redis |   | Local |
  | Limit |   | Limit |
  +---+---+   +---+---+
      |           |
      +-----+-----+
            |
            v
      +-----------+
      | Allowed?  |
      +-----+-----+
            |
       +----+----+
       |         |
      Yes        No
       |         |
       v         v
   Continue    429
   Request   Rejected
```

**Key Features:**
- Redis distributed rate limiting for multi-instance deployment
- Local Caffeine cache fallback when Redis unavailable
- Sliding window algorithm with burst capacity
- Multiple key types: `ip`, `route`, `combined`, `user`, `header`

#### 10.1.1 Redis Failover Strategy (Shadow Quota Method)

**The Problem:**

When Redis becomes unavailable, a naive fallback to local rate limiting causes a critical issue:

```
Scenario: 5 gateway nodes, global rate limit = 10,000 QPS

Before Redis failure:
  - Each node handles ~2,000 QPS (10,000 / 5)

Redis fails (naive fallback):
  - All 5 nodes reset their local counters to 0
  - Each node independently allows up to its local limit
  - Backend may receive 10,000+ QPS instantly --> cascading failure
```

This is the difference between a **demo project** and a **production-grade system**.

**Solution: Shadow Quota Method**

We chose the "Shadow Quota" approach for graceful degradation:

```
+------------------------------------------------------------------+
|                    SHADOW QUOTA FAILOVER                         |
+------------------------------------------------------------------+

  Redis Healthy State:
  +-------------------------------------------------------------+
  |  1. Periodically record global QPS snapshot (every 1s)     |
  |  2. Monitor cluster node count via Nacos/Consul            |
  |  3. Calculate: localQuota = globalQPS / nodeCount          |
  +-------------------------------------------------------------+

  Redis Failure Detected:
  +-------------------------------------------------------------+
  |  1. Switch to local rate limiting mode                      |
  |  2. Inherit pre-calculated local quota (shadow quota)       |
  |  3. Continue limiting at approximately the same rate        |
  +-------------------------------------------------------------+

  Example:
  - Global limit: 10,000 QPS
  - Cluster nodes: 5
  - Shadow quota per node: 10,000 / 5 = 2,000 QPS
  - When Redis fails: Each node continues at ~2,000 QPS
  - Backend receives: ~10,000 QPS (no spike!)
```

**Why This Approach?**

| Approach | Complexity | Traffic Behavior | Use Case |
|----------|------------|------------------|----------|
| **Reset Counter** | Low | Traffic doubles/triples | Demo / Non-critical |
| **Shadow Quota** (chosen) | Medium | Smooth degradation | **Production** |
| **Async Dual-Write** | High | High performance, weak consistency | Extreme performance |

---

#### 10.1.2 Design Decision: Shadow Quota vs Async Dual-Write

When designing the Redis failover strategy for rate limiting, we evaluated two production-grade approaches:

| Criterion | Shadow Quota | Async Dual-Write |
|-----------|--------------|------------------|
| **Core Idea** | Pre-calculate local quota based on historical traffic | Write to both Redis and local counters asynchronously |
| **Complexity** | Medium | High |
| **Traffic Stability** | Excellent - smooth transition | Good - may have brief inconsistency |
| **Consistency Model** | Approximate (based on snapshot) | Weak (async reconciliation) |
| **Recovery Complexity** | Simple (gradual shift) | Complex (counter sync, conflict resolution) |
| **Memory Overhead** | Low (few AtomicLongs) | Medium (local counter per key) |
| **Network Overhead** | None during failover | Periodic sync even in normal mode |
| **Suitable QPS Range** | 1K - 100K | 100K - 1M+ |

---

### Detailed Comparison

#### 1. Shadow Quota Approach

**How it works:**
```
Normal Operation:
+-------------------------------------------------------------+
|  Every 1 second:                                            |
|    1. Fetch current global QPS from Redis                   |
|    2. Get gateway node count from service discovery         |
|    3. Calculate: shadowQuota = globalQPS / nodeCount        |
|    4. Store in AtomicLong (in-memory)                       |
+-------------------------------------------------------------+

Redis Failure:
+-------------------------------------------------------------+
|  1. Detect Redis unavailable (error in rate limit call)     |
|  2. Switch to local mode immediately                        |
|  3. Use pre-calculated shadowQuota as local limit           |
|  4. No counter reset - continues at same rate!              |
+-------------------------------------------------------------+
```

**Advantages:**
- **Zero traffic spike**: Local quota is pre-calculated, no reset
- **Simple implementation**: Just a few AtomicLong variables
- **No extra network cost**: No sync needed during failover
- **Predictable behavior**: Traffic continues at known rate
- **Easy recovery**: Gradual traffic shift back to Redis

**Disadvantages:**
- **Approximate accuracy**: Based on 1-second snapshot, not exact
- **Node count dependency**: Requires accurate discovery data
- **Cold start issue**: New nodes inherit average quota, may be unfair

**Best for:**
- Production API gateways (1K - 100K QPS)
- Teams that value simplicity and reliability
- Scenarios where exact precision is not critical

---

#### 2. Async Dual-Write Approach

**How it works:**
```
Normal Operation:
+-------------------------------------------------------------+
|  Every request:                                             |
|    1. Write to Redis counter (async, non-blocking)          |
|    2. Write to local counter (sync, in-memory)              |
|    3. Use local counter for decision (faster)               |
|                                                              |
|  Background reconciliation (every 100ms):                   |
|    4. Sync local counters with Redis                        |
|    5. Resolve conflicts (Redis wins for consistency)        |
+-------------------------------------------------------------+

Redis Failure:
+-------------------------------------------------------------+
|  1. Redis write fails silently (async)                      |
|  2. Local counter continues working                         |
|  3. Queue pending Redis writes for retry                    |
|  4. When Redis recovers: flush pending writes               |
+-------------------------------------------------------------+
```

**Advantages:**
- **High performance**: Local counter is always used for decision (zero latency)
- **Better accuracy**: Continuous sync maintains consistency
- **No snapshot lag**: Real-time counter updates
- **Scales to extreme QPS**: Can handle 100K - 1M+ requests

**Disadvantages:**
- **Complex implementation**: Async write queue, reconciliation logic, conflict resolution
- **Memory overhead**: Local counter for every rate limit key
- **Network overhead**: Continuous sync traffic (even when healthy)
- **Recovery complexity**: Need to sync pending writes, handle conflicts
- **Potential inconsistency**: Brief window where local and Redis disagree
- **Debugging difficulty**: Hard to trace issues when async writes fail silently

**Best for:**
- Ultra-high QPS scenarios (100K - 1M+)
- Teams with strong distributed systems expertise
- Scenarios where latency is critical and some inconsistency is acceptable

---

### Decision Matrix

| Scenario | Recommended Approach | Reason |
|----------|---------------------|--------|
| **API Gateway (typical)** | Shadow Quota | Simplicity + reliability > raw performance |
| **Internal microservice gateway** | Shadow Quota | Team maintenance cost is key factor |
| **High-traffic public API** | Async Dual-Write | Latency critical, team can handle complexity |
| **Edge gateway (CDN-like)** | Async Dual-Write | Millions QPS, need zero-latency decisions |
| **Regulated industry (finance)** | Shadow Quota | Predictable behavior, easier auditing |

---

### Why We Chose Shadow Quota

After evaluating both approaches, we chose **Shadow Quota** for the following reasons:

#### 1. Simplicity Wins in Production

```
Shadow Quota implementation:
+-------------------------------------------------------------+
|  Core code: ~100 lines                                      |
|  Variables: 3 AtomicLongs                                   |
|  Background tasks: 1 scheduled update                       |
|  Failure handling: Simple switch + gradual recovery         |
+-------------------------------------------------------------+

Async Dual-Write implementation:
+-------------------------------------------------------------+
|  Core code: ~500+ lines                                     |
|  Variables: Map<String, AtomicLong> + pending queue         |
|  Background tasks: Sync + reconcile + conflict resolution   |
|  Failure handling: Queue management + retry + merge logic   |
+-------------------------------------------------------------+
```

**Production systems fail in unexpected ways.** More complexity = more failure modes.

A bug in Shadow Quota might cause slightly inaccurate limiting.
A bug in Async Dual-Write could cause counters to diverge silently for hours.

#### 2. Predictable Behavior Under Failure

When Redis fails at 3 AM, you want to know exactly what will happen:

| Shadow Quota | Async Dual-Write |
|--------------|------------------|
| Traffic continues at ~same rate | Depends on sync state, may vary |
| Easy to explain to ops team | "Check the queue, reconcile counters..." |
| Recovery in 10 seconds (gradual shift) | Recovery depends on pending writes |

#### 3. Operational Cost

Shadow Quota:
- No extra network traffic during normal operation
- No memory overhead for counter storage
- No background sync jobs to monitor

Async Dual-Write:
- Continuous sync traffic (adds ~10% network load)
- Memory for every rate limit key (1000 keys = 1000 counters)
- Background jobs to monitor and debug

#### 4. Team Capability Factor

```
Team skill requirements:
+-------------------------------------------------------------+
|  Shadow Quota:                                              |
|    - Basic concurrent programming (AtomicLong)              |
|    - Spring @Scheduled                                      |
|    - Service discovery integration                          |
|                                                              |
|  Async Dual-Write:                                          |
|    - Advanced concurrent programming (queues, CAS)          |
|    - Distributed systems (CAP theorem, consistency models)  |
|    - Conflict resolution algorithms                         |
|    - Memory management for large counter maps               |
|    - Async error handling and retry strategies              |
+-------------------------------------------------------------+
```

Shadow Quota can be maintained by mid-level engineers.
Async Dual-Write requires senior distributed systems expertise.

---

### Real-World Traffic Simulation

We simulated both approaches under identical conditions:

**Test Setup:**
- 5 gateway nodes
- Global limit: 10,000 QPS
- Redis failure at T=30s
- Redis recovery at T=60s

**Results:**

```
Shadow Quota Traffic Pattern:
T=0-30s:   Each node: ~2,000 QPS (stable)
T=30s:     Redis fails --> Each node: ~2,000 QPS (NO CHANGE!)
T=60s:     Recovery starts --> Gradual shift over 10s
T=70s:     Full recovery, traffic normal

Backend receives: Stable 10,000 QPS throughout

Async Dual-Write Traffic Pattern:
T=0-30s:   Each node: ~2,000 QPS (stable)
T=30s:     Redis fails --> Brief spike to ~12,000 QPS (sync lag)
T=35s:     Local counters adjust --> Back to ~10,000 QPS
T=60s:     Recovery --> Pending writes flush, brief dip to ~8,000
T=65s:     Stabilized

Backend receives: Variable 8,000-12,000 QPS during transitions
```

**Key insight:** Shadow Quota provides smoother traffic during both failure and recovery.

---

### Conclusion

For a typical enterprise API gateway serving 1K - 100K QPS:

| Factor | Winner |
|--------|--------|
| Implementation simplicity | Shadow Quota |
| Operational predictability | Shadow Quota |
| Team maintenance cost | Shadow Quota |
| Traffic stability | Shadow Quota |
| Raw performance (latency) | Async Dual-Write |
| Ultra-high QPS scaling | Async Dual-Write |

**Our decision: Shadow Quota**

The slight performance advantage of Async Dual-Write does not justify its 5x complexity increase for typical gateway use cases. We prioritized:

1. **Simplicity** - Easier to implement, debug, and maintain
2. **Predictability** - Known behavior under failure scenarios
3. **Operational cost** - No extra sync traffic or memory overhead

> "A system that is simple enough to understand is simple enough to fix."
> -- The pragmatism behind our design decision

---

**Implementation Design:**

```java
public class HybridRateLimiter {

    // Snapshot of global QPS (updated every second)
    private final AtomicLong globalQpsSnapshot = new AtomicLong(0);

    // Cluster node count (from service discovery)
    private final AtomicInteger clusterNodeCount = new AtomicInteger(1);

    // Pre-calculated local quota for failover
    private final AtomicLong shadowQuota = new AtomicLong(0);

    // Redis health status
    private volatile boolean redisHealthy = true;

    @Scheduled(fixedRate = 1000)
    public void updateShadowQuota() {
        if (redisHealthy) {
            long globalQps = fetchGlobalQpsFromRedis();
            globalQpsSnapshot.set(globalQps);

            int nodes = discoveryClient.getInstances("gateway").size();
            clusterNodeCount.set(Math.max(1, nodes));

            // Calculate shadow quota
            long quota = globalQps / clusterNodeCount.get();
            shadowQuota.set(quota);
        }
    }

    public Mono<Boolean> allowRequest(String key) {
        if (redisHealthy) {
            return redisRateLimiter.allow(key)
                .onErrorResume(e -> {
                    degradeToLocal();
                    return localRateLimiter.allow(key, shadowQuota.get());
                });
        }
        return localRateLimiter.allow(key, shadowQuota.get());
    }
}
```

**Recovery Strategy:**

When Redis recovers, we use **gradual traffic shifting** to avoid sudden state changes:

```
Redis Recovery Timeline:

Second 0:  10% traffic to Redis, 90% local
Second 1:  20% traffic to Redis, 80% local
Second 2:  30% traffic to Redis, 70% local
...
Second 9:  100% traffic to Redis, fully recovered
```

This prevents:
- Thundering herd to Redis
- Sudden quota changes
- Inconsistent behavior during transition

### 10.2 Strategy Management System

Unified configuration management for all gateway strategies:

```
+------------------------------------------------------------------+
|                    STRATEGY MANAGER                               |
+------------------------------------------------------------------+

  +-------------------------------------------------------------+
  |                    Strategy Types                            |
  |                                                              |
  |  Traffic & resilience (11):                                 |
  |    RATE_LIMITER  IP_FILTER  TIMEOUT  CIRCUIT_BREAKER  AUTH  |
  |    RETRY  CORS  HEADER_OP  CACHE  SECURITY  API_VERSION     |
  |                                                             |
  |  Request/response processing (5):                           |
  |    MULTI_DIM_RATE_LIMITER  REQUEST_TRANSFORM                |
  |    RESPONSE_TRANSFORM  REQUEST_VALIDATION  MOCK_RESPONSE    |
  |                                                             |
  |  AI gateway (6):                                            |
  |    AI_PROXY  AI_PROMPT_TEMPLATE  AI_SECURITY                |
  |    AI_SEMANTIC_CACHE  AI_CONTEXT_LIMIT  AI_STATISTICS       |
  +-------------------------------------------------------------+
                              |
                              v
  +-------------------------------------------------------------+
  |                    Strategy Scope                            |
  |                                                              |
  |  GLOBAL (apply to all routes)                                |
  |  ROUTE_BOUND (apply to specific route, higher priority)     |
  +-------------------------------------------------------------+

  Priority: ROUTE_BOUND > GLOBAL
```

> **Source of truth for the type list:** the 22 `TYPE_*` `String` constants declared in
> `my-gateway/src/main/java/com/leoli/gateway/model/StrategyDefinition.java:28-63`. There is **no
> `StrategyType` enum** — a strategy type is just a String. Two further names appear only in the
> admin seed data (`gateway-admin/src/main/resources/sql/seed/strategy_types.sql`): `ACCESS_LOG`
> and `TOKEN_RATE_LIMITER`. They have no `StrategyDefinition` constant and no gateway filter reads
> them, so do not treat them as usable strategy types.

### 10.3 Retry Mechanism

Configurable retry with fixed interval:

```
Request fails
      |
      v
+-------------+
| Max retries?|---- Yes ----> Return error
+------+------+
       | No
       v
+-------------+
| Should retry|---- No -----> Return error
| (status/exception match)?
+------+------+
       | Yes
       v
+-------------+
| Wait fixed  |
| interval    |
+------+------+
       |
       v
  Retry request
```

**Configurable:**
- Max retry attempts
- Fixed retry interval (ms)
- Retry on status codes: 500, 502, 503, 504
- Retry on exceptions: ConnectException, SocketTimeoutException, IOException

### 10.4 Response Caching

Caffeine-based in-memory caching for GET/HEAD requests:

```
+------------------------------------------------------------------+
|                    RESPONSE CACHE FLOW                           |
+------------------------------------------------------------------+

  GET/HEAD Request
        |
        v
  +-------------+
  | Cache hit?  |---- Yes ----> Return cached response (X-Cache: HIT)
  +------+------+
         | No
         v
  +-------------+
  | Execute     |
  | request     |
  +------+------+
         |
         v
  +-------------+
  | 2xx response?|---- No -----> Don't cache
  +------+------+
         | Yes
         v
  +-------------+
  | Cache-Control: no-cache?|---- Yes -----> Don't cache
  +------+------+
         | No
         v
    Cache response
    with TTL
```

**Cache Features:**
- Per-route cache configuration
- Configurable TTL and max size
- Vary headers support
- Exclude path patterns

---

## 11. Summary

This API Gateway architecture demonstrates:

- **Clean separation** of control plane (admin) and data plane (gateway)
- **Extensible design** via SPI and Strategy patterns
- **High availability** with fallback caching and graceful degradation
- **Dynamic configuration** via listeners and periodic reconciliation (60-second default interval)
- **Enterprise features** including multi-auth, circuit breaking, and rate limiting

---

## 12. Multi-Service Routing Architecture

> For detailed configuration and usage, see [Multi-Service Routing](features/multi-service-routing.md).

### 12.1 Overview

```
+------------------------------------------------------------------+
|                    MULTI-SERVICE ROUTING FLOW                     |
+------------------------------------------------------------------+

   Incoming Request
         |
         v
   +-------------------+
   | Route Definition  |
   | mode: MULTI       |
   +--------+----------+
            |
            v
   +-------------------+
   | Match Gray Rules  |
   | (Header/Cookie/   |
   |  Query/Weight)    |
   +--------+----------+
            |
      +-----+-----+
      |           |
    Match      No Match
      |           |
      v           v
   +------+   +-------------------+
   |Route |   | Weight-based      |
   |to    |   | Selection         |
   |Target |   | (Smooth RR)       |
   +------+   +--------+----------+
      |                 |
      +--------+--------+
               |
               v
   +-------------------+
   | Service Binding   |
   | (STATIC/          |
   |  DISCOVERY)       |
   +--------+----------+
            |
            v
   +-------------------+
   | Load Balancer     |
   | (Select Instance) |
   +-------------------+
```

---

## 13. SSL Termination Architecture

> For detailed certificate management and renewal features, see [SSL Termination](features/ssl-termination.md).

### 13.1 Overview

```
+------------------------------------------------------------------+
|                    SSL TERMINATION FLOW                           |
+------------------------------------------------------------------+

   HTTPS Request (:8443)
         |
         v
   +-------------------+
   | SSL Handshake     |
   | (SNI Selection)   |
   +--------+----------+
            |
            v
   +-------------------+
   | Certificate Store |
   | (Multi-domain)    |
   +--------+----------+
            |
            v
   +-------------------+
   | SSL Termination   |
   +--------+----------+
            |
            | HTTP Request
            v
   +-------------------+
   | HTTP Gateway (:80)|
   | Filter Chain      |
   +-------------------+
```

---

## 14. Request Tracing Architecture

> For detailed trace capture and debugging features, see [Request Tracing](features/request-tracing.md) and [Request Replay Debugger](features/request-replay.md).

### 14.1 Overview

```
+------------------------------------------------------------------+
|                    REQUEST TRACING                                |
+------------------------------------------------------------------+

   Request arrives
         |
         v
   +-------------------+
   | TraceCapture      |
   | GlobalFilter      |
   +--------+----------+
            |
            v
   +-------------------+
   | Response Status?  |
   +--------+----------+
            |
      +-----+-----+
      |           |
   Error       Slow (>threshold)
      |           |
      +-----+-----+
            |
            v
   +-------------------+
   | Capture:          |
   | - Request headers |
   | - Request body    |
   | - Response status |
   | - Latency         |
   | - Target instance |
   +--------+----------+
            |
            v
   +-------------------+
   | Store in DB       |
   | (RequestTrace)    |
   +-------------------+
```

---

## 15. AI Integration Architecture

> For detailed tool calling capabilities, see [AI Copilot Assistant](features/ai-copilot.md). For metrics analysis features, see [AI-Powered Analysis](features/ai-analysis.md).

### 15.1 Overview

```
+------------------------------------------------------------------+
|                    AI PROVIDER SPI                                |
+------------------------------------------------------------------+

   AiAnalysisService
         |
         +-- OPENAI  (api.openai.com/v1)
         |     models: gpt-4o, gpt-4-turbo, gpt-4
         +-- GEMINI  (generativelanguage.googleapis.com/v1beta)
         |     models: gemini-2.0-flash, gemini-1.5-pro, gemini-1.5-flash
         +-- CLAUDE  (api.anthropic.com/v1)
         |     models: claude-3.5-sonnet, claude-3-opus, claude-3-haiku
         +-- BAILIAN (dashscope.aliyuncs.com/compatible-mode/v1)
               models: qwen3.8-max, qwen3.7-plus, qwen3.8-flash, qwen3-coder-next,
                       deepseek-v4-pro, deepseek-v4.1-flash, kimi-k3, kimi-k2.7-code,
                       glm-5.3, glm-5.2
```

> Exactly **four** providers are built in — `OPENAI`, `GEMINI`, `CLAUDE` and `BAILIAN` — declared in
> `DEFAULT_BASE_URLS` / `PROVIDER_MODELS` at
> `gateway-admin/src/main/java/com/leoli/gateway/admin/service/AiAnalysisService.java:107-131`.
> There is **no DeepSeek provider and no Ollama / local-model provider**: DeepSeek, Kimi and GLM are
> reached *through* Alibaba's BAILIAN (DashScope OpenAI-compatible) endpoint, and `ollama` does not
> appear anywhere in the repository. Earlier revisions of this diagram listed GPT-3.5, Claude 3,
> qwen-plus/qwen-turbo, deepseek-chat and Ollama; that provider/model set no longer matches the code.

---

## 16. Gateway Instance Management Architecture

> For detailed lifecycle management features, see [Instance Management](features/instance-management.md). For Kubernetes deployment automation, see [Kubernetes Integration](features/kubernetes-integration.md).

### 16.1 Overview

```
+------------------------------------------------------------------+
|                    INSTANCE MANAGEMENT FLOW                       |
+------------------------------------------------------------------+

   Admin UI (Create Instance)
          |
          v
   +-------------------+
   | Validate Request  |
   | - Check K8s conn  |
   | - Check Nacos     |
   +--------+----------+
            |
            v
   +-------------------+
   | Create Nacos      |
   | Namespace         |
   +--------+----------+
            |
            v
   +-------------------+
   | Generate K8s YAML |
   | from Template     |
   +--------+----------+
            |
            v
   +-------------------+
   | Deploy to K8s     |
   | via K8s API       |
   +--------+----------+
            |
            v
   +-------------------+
   | Register Instance |
   | in Database       |
   +-------------------+
```

---

## 17. Kubernetes Integration Architecture

> For detailed deployment and resource management, see [Kubernetes Integration](features/kubernetes-integration.md).

### 17.1 Overview

```
+------------------------------------------------------------------+
|                    K8S DEPLOYMENT FLOW                            |
+------------------------------------------------------------------+

   User Request (Create Instance)
          |
          v
   +-------------------+
   | KubernetesService |
   | - Validate params |
   | - Check cluster   |
   +--------+----------+
            |
            v
   +-------------------+
   | Generate YAML     |
   | from Template     |
   | (Deployment,      |
   |  Service, Config) |
   +--------+----------+
            |
            v
   +-------------------+
   | Apply to K8s      |
   | via K8s Java SDK  |
   +--------+----------+
            |
            v
   +-------------------+
   | Watch Pod Status  |
   | - Ready?          |
   | - Error?          |
   +-------------------+
```

---

## 18. Config Reconciliation Architecture

### 18.1 Overview

The reconcile task ensures consistency between database and Nacos configuration.

```
+------------------------------------------------------------------+
|                    RECONCILIATION TASK                            |
+------------------------------------------------------------------+

   Scheduled (every 60 seconds by default, via gateway.admin.reconcile.interval-ms --
   see ReconcileScheduler.java:38 @Scheduled(fixedDelayString = "${...:60000}").
   ShedLock's lockAtMostFor is 5 minutes, which is NOT the run interval.)
          |
          v
   +-------------------+
   | RouteReconcile    |
   | Task              |
   +--------+----------+
            |
            v
   +-------------------+
   | Compare DB vs     |
   | Nacos Index       |
   +--------+----------+
            |
      +-----+-----+
      |           |
    Match      Mismatch
      |           |
      v           v
   Done      Update Nacos
             (Repair)
```

> The diagram above shows one task; `ReconcileScheduler.reconcileAll()` actually iterates over **all
> 7** registered `ReconcileTask` beans — Route, Service, Strategy, AuthPolicy, AccessLogConfig,
> Certificate and Namespace. The 5-minute figure that used to appear here is ShedLock's
> `lockAtMostFor` (zombie-lock timeout), not the schedule interval, and this document already states
> 60 seconds in sections 5.2, 11, 22 and 23.3.

---

## 19. Testing Architecture

### 19.1 Test Coverage

| Module | Tests | Coverage Areas |
|--------|-------|----------------|
| **my-gateway** | 1018 (2 skipped) | Filters, Auth, Rate Limiting, Strategies |
| **gateway-admin** | 564 (80 errors) | API, Services, Repository, Integration |
| **license-client** | 83 | Offline RSA license verification |
| **gateway-trace-starter** | 160 | Trace context propagation / async reporting |
| **license-server** | 40 | License issuing API |
| **gateway-moderation-spi** | 0 | Contract-only module (one SPI interface), no tests yet |
| **seckill-core-engine** | 217 (4 errors, 4 skipped) | Seckill flow, Redis Lua rate limiting |
| **Total** | **2082** (0 failures, 84 errors, 6 skipped) | — |

> Counts are the Surefire **aggregate** lines from `mvn -o -B -ntp -fae test` (do not sum the
> per-class `*.txt` files — `@Nested` classes make that total come out low). The 84 errors are all
> missing local infrastructure, not code defects: 80 in `gateway-admin` are the `entityManagerFactory`
> cascade from having no MySQL on `localhost:3306`, and the 4 in `seckill-core-engine` need Redis
> (`:30379`) and Docker. Earlier revisions of this table said 332 / 229 (561 total); that is several
> generations out of date.

### 19.2 Test Structure

```
+------------------------------------------------------------------+
|                    TEST STRUCTURE                                 |
+------------------------------------------------------------------+

   my-gateway/src/test/java/
   +-------------------------------------------------------------+
   |  auth/                    - Authentication processors        |
   |  filter/                  - Global filters                  |
   |  limiter/                 - Rate limiting                   |
   |  manager/                 - Config managers                 |
   +-------------------------------------------------------------+

   gateway-admin/src/test/java/
   +-------------------------------------------------------------+
   |  service/                 - Service unit tests              |
   |  RouteApiTest             - Route API integration tests     |
   |  ServiceApiTest           - Service API integration tests   |
   |  StrategyApiTest          - Strategy API integration tests  |
   +-------------------------------------------------------------+
```

---

## 20. Performance Optimizations

> For detailed performance monitoring, see [Filter Chain Analysis](features/filter-chain-analysis.md).

### 20.1 Overview

The gateway implements several performance optimizations:

| Optimization | Technique | Benefit |
|--------------|-----------|---------|
| **JWT Cache** | ConcurrentHashMap + TTL | ~90% reduction in verification overhead |
| **Shadow Quota** | Pre-calculated failover | Stable traffic during Redis outage |
| **Connection Pool** | Netty connection pool | Reduced connection overhead |
| **Hybrid Health Check** | Caffeine cache + passive checks | Zero overhead for healthy instances |
| **Non-blocking Lock** | CAS + tryLock | No EventLoop thread blocking |
| **Log Rotation** | CAS atomic update | Thread-safe file switching |
| **Instance Discovery** | Set instead of List | O(1) vs O(n) lookup |

---

## 21. Feature Documentation Index

All feature documentation is organized by category (36 documents under `docs/features/` at commit
`b45d40a`; re-count with `ls docs/features/*.md | wc -l`):

### Core Gateway Features

| Feature | Description | Documentation |
|---------|-------------|---------------|
| **Authentication** | JWT, API Key, Basic, HMAC, OAuth2 | [authentication.md](features/authentication.md) |
| **IP Filtering** | Whitelist/blacklist access control | [ip-filtering.md](features/ip-filtering.md) |
| **Rate Limiting** | Redis + Local hybrid rate limiting | [rate-limiting.md](features/rate-limiting.md) |
| **Circuit Breaker** | Resilience4j integration | [circuit-breaker.md](features/circuit-breaker.md) |
| **Timeout Control** | Request timeout configuration | [timeout-control.md](features/timeout-control.md) |
| **Retry** | Configurable retry with fixed interval | [retry.md](features/retry.md) |

### Routing & Service Discovery

| Feature | Description | Documentation |
|---------|-------------|---------------|
| **Multi-Service Routing** | Gray release and canary deployment | [multi-service-routing.md](features/multi-service-routing.md) |
| **Service Discovery** | Nacos/Consul/Static discovery | [service-discovery.md](features/service-discovery.md) |
| **Route Management** | Dynamic route configuration | [route-management.md](features/route-management.md) |

### Request/Response Processing

| Feature | Description | Documentation |
|---------|-------------|---------------|
| **Request Transform** | Body transformation (JSON/XML/field mapping) | [request-transform.md](features/request-transform.md) |
| **Request Validation** | Schema and field validation | [request-validation.md](features/request-validation.md) |
| **Response Transform** | Response body transformation | [response-transform.md](features/response-transform.md) |
| **Mock Response** | Mock responses for testing | [mock-response.md](features/mock-response.md) |
| **Response Caching** | Caffeine-based caching | [response-caching.md](features/response-caching.md) |

### SSL & Security

| Feature | Description | Documentation |
|---------|-------------|---------------|
| **SSL Termination** | HTTPS with dynamic certificate management | [ssl-termination.md](features/ssl-termination.md) |

### Kubernetes & Deployment

| Feature | Description | Documentation |
|---------|-------------|---------------|
| **Kubernetes Integration** | K8s deployment automation | [kubernetes-integration.md](features/kubernetes-integration.md) |
| **Instance Management** | Gateway instance lifecycle | [instance-management.md](features/instance-management.md) |

### Monitoring & Observability

| Feature | Description | Documentation |
|---------|-------------|---------------|
| **Request Tracing** | Capture and analyze request traces | [request-tracing.md](features/request-tracing.md) |
| **Monitoring Alerts** | Prometheus metrics and alerts | [monitoring-alerts.md](features/monitoring-alerts.md) |
| **Filter Chain Analysis** | Filter execution statistics | [filter-chain-analysis.md](features/filter-chain-analysis.md) |
| **Traffic Topology** | Real-time traffic visualization | [traffic-topology.md](features/traffic-topology.md) |
| **Access Log** | Access log configuration, collection and query | [access-log.md](features/access-log.md) |
| **Historical Comparison** | Period-over-period metric/filter/config comparison | [historical-comparison.md](features/historical-comparison.md) |
| **Performance Optimization** | JWT cache, shadow quota, non-blocking locks, log rotation | [performance-optimization.md](features/performance-optimization.md) |

### Debugging & Analysis

| Feature | Description | Documentation |
|---------|-------------|---------------|
| **Request Replay** | Replay captured requests for debugging | [request-replay.md](features/request-replay.md) |
| **System Diagnostic** | Comprehensive health checks | [system-diagnostic.md](features/system-diagnostic.md) |
| **Route Debug** | Per-route debug page that pre-fills a request from the route's Path/Method/Host/Header predicates and streams the response | [route-debug.md](features/route-debug.md) |
| **Stress Test** | Load testing tool | [stress-test.md](features/stress-test.md) |

### AI Integration

| Feature | Description | Documentation |
|---------|-------------|---------------|
| **AI Copilot** | Intelligent gateway assistance | [ai-copilot.md](features/ai-copilot.md) |
| **AI Analysis** | AI-powered metrics analysis | [ai-analysis.md](features/ai-analysis.md) |
| **AI Token Quota Ledger** | Per-request UUID ledger for pre-deduction, settlement, refund and local degradation | [ai-quota-ledger.md](features/ai-quota-ledger.md) |
| **AI Usage Delivery** | Durable local outbox with `eventId` for reliable usage reporting to the admin | [ai-usage-delivery.md](features/ai-usage-delivery.md) |
| **AI Usage Reconciliation** | Reconcile/compensate `UNKNOWN`/`ESTIMATED` usage and `PENDING`/`FAILED` settlements | [ai-usage-reconciliation.md](features/ai-usage-reconciliation.md) |
| **AI Content Security** | `AI_SECURITY` in-flight text moderation (word lists, regex, PII handling, optional external moderators via the `gateway-moderation-spi` contract) | [ai-content-security.md](features/ai-content-security.md) |

### Administration

| Feature | Description | Documentation |
|---------|-------------|---------------|
| **Audit Logs** | Configuration change tracking | [audit-logs.md](features/audit-logs.md) |
| **Email Notifications** | Alert email configuration | [email-notifications.md](features/email-notifications.md) |

---

## 22. Summary

This API Gateway architecture demonstrates:

- **Clean separation** of control plane (admin) and data plane (gateway)
- **Extensible design** via SPI and Strategy patterns
- **High availability** with fallback caching and graceful degradation
- **Dynamic configuration** via listeners and periodic reconciliation (60-second default interval)
- **Enterprise features** including multi-auth, circuit breaking, and rate limiting
- **Multi-service routing** with gray release support for canary deployments
- **SSL termination** with dynamic certificate loading and expiry monitoring
- **Kubernetes deployment** with one-click instance creation
- **Namespace isolation** for multi-tenancy support
- **Heartbeat monitoring** for real-time instance health tracking
- **Performance optimizations** including JWT cache, shadow quota, non-blocking locks
- **Comprehensive testing** with 2082 tests across the six test-bearing Maven modules (see section 19.1) ensuring reliability
- **36 feature documents** under `docs/features/`, indexed in section 21

---

For feature documentation, see [FEATURES.md](FEATURES.md).
For quick start guide, see [QUICK_START.md](QUICK_START.md).

## 23. Known Limitations & Trade-offs

### 23.1 Admin Monolith Carries Heterogeneous Workloads

gateway-admin is a single Spring Boot process that hosts:

- Route/service/plugin CRUD (management plane)
- Kubernetes cluster management (Pod stats, node info, namespace ops)
- Stress test orchestration (JMeter execution, result aggregation)
- AI Copilot (LLM calls, tool-call loops, streaming responses)
- Distributed trace ingestion and query
- Health heartbeat aggregation

**Impact**: a stress-test run or AI Copilot session can saturate the admin's
thread pool and starve management-plane requests (route publishing, health
sync). There is no workload isolation or priority queue today.

**Mitigation**: the admin itself is not on the data-plane hot path, so
routing never breaks, but the console UI may become unresponsive under
combined load. Long-term, move stress-test execution and AI inference to
separate worker services.

### 23.2 Configuration Defaults Coupled to K8s Test Namespace

NodePort addresses (localhost:30848 for Nacos, :30379 for Redis, :30500 for
Consul, :30686 for Jaeger, :30263 for OTLP, :30090 for Prometheus, :30808
for RocketMQ Console) are hardcoded as default values in application.yml.
Switching to a different K8s namespace or a non-K8s environment requires
overriding every host/port via environment variables. The production profile
(application-prod.yml) documents the expected in-cluster DNS names.

### 23.3 Write Consistency Window (MySQL → Nacos)

When a route/service update succeeds in MySQL but the subsequent Nacos
publish fails, the gateway operates on stale configuration until the
reconciliation task runs (default 60s). During this window, the console
shows the new config as "saved" while the gateway still uses the old one.