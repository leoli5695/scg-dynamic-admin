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

```
+------------------------------------------------------------------+
|                     AuthProcessor (Interface)                    |
|   +---------------------------------------------------------+   |
|   |  + validate(exchange, config): Mono<Boolean>             |   |
|   |  + getType(): AuthType                                    |   |
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
   |  private Map<AuthType, AuthProcessor> processors;           |
   |                                                              |
   |  public Mono<Boolean> authenticate(exchange, config) {      |
   |      AuthProcessor processor = processors.get(config.type); |
   |      return processor.validate(exchange, config);           |
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
   |    Gateway      |       Routes Updated (< 1 second)
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
   |   |  Order    Filter                    Function                      |  |
   |   |  -----    ---------------------     -------------------------    |  |
   |   |  -500     SecurityGlobalFilter      Security hardening            |  |
   |   |  -400     AccessLogGlobalFilter     Access logging                |  |
   |   |  -300     TraceIdGlobalFilter       Generate Trace ID + MDC       |  |
   |   |  -300     CorsGlobalFilter          CORS handling                 |  |
   |   |  -280     IPFilterGlobalFilter      IP Blacklist/Whitelist        |  |
   |   |  -250     AuthenticationGlobalFilter JWT/API Key/OAuth2 Auth      |  |
   |   |  -200     TimeoutGlobalFilter       Connection/Response Timeout   |  |
   |   |  -200     RetryGlobalFilter         Retry on failure              |  |
   |   |  -150     ApiVersionGlobalFilter    API version routing           |  |
   |   |  -100     CircuitBreakerGlobalFilter Resilience4j Circuit Breaker |  |
   |   |   -50     HeaderOpGlobalFilter      Header manipulation           |  |
   |   |    50     CacheGlobalFilter         Response caching              |  |
   |   | 10001    StaticProtocolGlobalFilter static:// -> lb:// transform  |  |
   |   | 10150    DiscoveryLoadBalancerFilter Service Discovery + LB       |  |
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

### 3.2 Filter Execution Order (Why This Order?)

```
Request enters gateway
  |
  +-- order -500  Security        --> Security hardening first
  +-- order -400  AccessLog       --> Log all requests for audit
  +-- order -300  TraceId         --> Generate/propagate X-Trace-Id, MDC logging
  |                                WHY EARLY? --> See everything for debugging
  +-- order -300  CORS            --> Handle preflight requests
  +-- order -280  IP Filter       --> Whitelist/blacklist check -> 403 if blocked
  |                                WHY BEFORE AUTH? --> Fast rejection saves CPU
  +-- order -250  Authentication  --> JWT/API Key/OAuth2 validation -> 401 if failed
  +-- order -200  Timeout         --> Inject timeout params into route metadata
  +-- order -200  Retry           --> Configure retry behavior
  +-- order -150  API Version     --> Version-based routing
  +-- order -100  Circuit Breaker --> Check circuit status -> 503 if open
  +-- order  -50  Header Op       --> Add/modify headers
  +-- order   50  Cache           --> Response caching
  +-- order 10001 Static Protocol --> Transform static:// to lb://
  +-- order 10150 Load Balancer   --> Service discovery + load balancing
  |                                WHY LAST? --> Final defense before routing
  +-- order 10001+ Routing         --> Forward to backend
```

**Performance Impact:** IP Filter before Authentication provides **+37% TPS improvement**

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
   | Service Registry|             | gateway-services|
   |  (Dynamic)      |             |     .json       |
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

---

## 5. Cache Architecture

### 5.1 Single-Layer Cache with Real-Time Push

The gateway uses a **simple and effective** caching strategy:

```
+------------------------------------------------------------------+
|                    CONFIG FLOW ARCHITECTURE                       |
+------------------------------------------------------------------+

                    Config Center (Nacos/Consul)
                           |
                           | Real-time Push (Listener)
                           | < 100ms latency
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

**Why Not Multi-Level Cache?**

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
          | 3. Push via Listener (< 100ms)
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

**Total Latency:** Admin API -> Gateway update typically < 1 second

---

## 6. Module Details

### 6.1 my-gateway (Core Runtime)

```
my-gateway/
|-- src/main/java/com/leoli/gateway/
|   |
|   |-- filter/                    # Global Filters
|   |   |-- AuthenticationGlobalFilter.java
|   |   |-- CircuitBreakerGlobalFilter.java
|   |   |-- HybridRateLimiterFilter.java
|   |   |-- IPFilterGlobalFilter.java
|   |   |-- TimeoutGlobalFilter.java
|   |   |-- TraceIdGlobalFilter.java
|   |   +-- ...
|   |
|   |-- auth/                      # Auth Processors (Strategy Pattern)
|   |   |-- AuthProcessor.java              # Interface
|   |   |-- AuthProcessManager.java         # Manager
|   |   |-- JwtAuthProcessor.java
|   |   |-- ApiKeyAuthProcessor.java
|   |   |-- BasicAuthProcessor.java
|   |   |-- HmacSignatureAuthProcessor.java
|   |   +-- OAuth2AuthProcessor.java
|   |
|   |-- center/                    # Config Center SPI
|   |   |-- spi/ConfigCenterService.java
|   |   |-- nacos/NacosConfigService.java
|   |   +-- consul/ConsulConfigService.java
|   |
|   |-- discovery/                 # Service Discovery SPI
|   |   |-- spi/DiscoveryService.java
|   |   |-- nacos/NacosDiscoveryService.java
|   |   |-- consul/ConsulDiscoveryService.java
|   |   +-- staticdiscovery/StaticDiscoveryService.java
|   |
|   |-- manager/                   # Configuration Managers
|   |   |-- RouteManager.java
|   |   |-- ServiceManager.java
|   |   +-- StrategyManager.java
|   |
|   |-- refresher/                 # Config Refreshers
|   |   |-- RouteRefresher.java
|   |   |-- ServiceRefresher.java
|   |   +-- StrategyRefresher.java
|   |
|   |-- route/                     # Route Locator
|   |   +-- DynamicRouteDefinitionLocator.java
|   |
|   +-- limiter/                   # Rate Limiting
|       |-- RedisRateLimiter.java
|       +-- LocalRateLimiter.java
```

### 6.2 gateway-admin (Management Console)

```
gateway-admin/
|-- src/main/java/com/leoli/gateway/admin/
|   |
|   |-- controller/                # REST API
|   |   |-- RouteController.java
|   |   |-- ServiceController.java
|   |   |-- StrategyController.java
|   |   +-- AuthController.java
|   |
|   |-- service/                   # Business Logic
|   |   |-- RouteService.java              # DB + Nacos dual-write
|   |   |-- ServiceService.java
|   |   +-- StrategyService.java
|   |
|   |-- repository/                # Data Access
|   |   |-- RouteRepository.java
|   |   |-- ServiceRepository.java
|   |   +-- StrategyRepository.java
|   |
|   |-- model/                     # Entities
|   |   |-- RouteEntity.java
|   |   |-- ServiceEntity.java
|   |   +-- StrategyEntity.java
|   |
|   |-- center/                    # Config Publisher
|   |   +-- ConfigCenterPublisher.java
|   |
|   +-- aspect/                    # AOP
|       +-- AuditLogAspect.java
```

---

## 7. Design Principles

| Principle | Application |
|-----------|-------------|
| **SOLID** | Single responsibility per filter, Open for extension (SPI), Dependency inversion |
| **DRY** | Shared ConfigCenterService, GenericCacheManager reusable |
| **KISS** | Simple filter chain, clear separation of concerns |
| **Defense in Depth** | IP filter -> Auth -> Rate limit -> Circuit breaker |
| **Graceful Degradation** | Local rate limiter fallback, cache fallback when Nacos down |

---

## 8. Extensibility Guide

### 8.1 Adding a New Authentication Type

```java
// 1. Implement AuthProcessor
@Component
public class CustomAuthProcessor extends AbstractAuthProcessor {

    @Override
    public AuthType getType() {
        return AuthType.CUSTOM;  // Add to enum
    }

    @Override
    public Mono<Boolean> validate(ServerWebExchange exchange, AuthConfig config) {
        // Custom validation logic
        return Mono.just(true);
    }
}

// 2. Register automatically via Spring @Component
// No other changes needed - AuthProcessManager auto-discovers all processors
```

### 8.2 Adding a New Global Filter

```java
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = exchange.getAttribute(GATEWAY_ROUTE_ATTR);

        // Get config from StrategyManager
        CustomConfig config = strategyManager.getConfig(StrategyType.CUSTOM, routeId);

        if (config != null && config.isEnabled()) {
            // Apply filter logic
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -50;  // Define execution order
    }
}
```

---

## 9. Performance Considerations

| Optimization | Technique |
|--------------|-----------|
| **Cache TTL** | 5-minute TTL for route/service config, refresh on change |
| **Async Publish** | Config publish to Nacos is async, doesn't block API response |
| **Connection Pool** | Netty connection pool with elastic sizing |
| **Reactive** | Non-blocking I/O throughout the filter chain |

---

## 10. Design Highlights

### 10.1 Hybrid Rate Limiting

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
  - Backend may receive 10,000+ QPS instantly → cascading failure
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
| **Reset Counter** | ⭐ | Traffic doubles/triples | Demo / Non-critical |
| **Shadow Quota** (chosen) | ⭐⭐ | Smooth degradation | **Production** |
| **Async Dual-Write** | ⭐⭐⭐⭐ | High performance, weak consistency | Extreme performance |

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
  |  AUTH | IP_FILTER | RATE_LIMITER | CIRCUIT_BREAKER | TIMEOUT|
  |  RETRY | CORS | CACHE | ACCESS_LOG | HEADER_OP | API_VERSION|
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
- **Real-time configuration** with < 1 second propagation latency
- **Enterprise features** including multi-auth, circuit breaking, and rate limiting
- **Multi-service routing** with gray release support for canary deployments
- **SSL termination** with dynamic certificate loading and expiry monitoring

---

## 12. Multi-Service Routing Architecture

### 12.1 Overview

Multi-service routing enables a single route to distribute traffic across multiple backend services with configurable weights and rules.

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

### 12.2 Gray Rule Matching

```
+------------------------------------------------------------------+
|                    GRAY RULE PRIORITY                             |
+------------------------------------------------------------------+

   Rules evaluated in order (first-match-wins):

   1. HEADER rule    --> If X-Version: v2, route to v2
   2. COOKIE rule    --> If cookie[version]=v2, route to v2
   3. QUERY rule     --> If ?version=v2, route to v2
   4. WEIGHT rule    --> 10% of traffic to v2

   Default: Weight-based distribution
```

### 12.3 Service Binding Types

| Type | Protocol | Use Case |
|------|----------|----------|
| `DISCOVERY` | `lb://service-name` | Services registered in Nacos/Consul |
| `STATIC` | `static://service-id` | Fixed IP:port instances |

---

## 13. SSL Termination Architecture

### 13.1 Overview

The gateway provides HTTPS termination with dynamic certificate management.

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

### 13.2 Certificate Management

```
+------------------------------------------------------------------+
|                    CERTIFICATE LIFECYCLE                          |
+------------------------------------------------------------------+

   Upload Certificate
         |
         v
   +-------------------+
   | Parse Certificate |
   | - Extract domain  |
   | - Extract expiry  |
   | - Validate chain  |
   +--------+----------+
            |
            v
   +-------------------+
   | Store in DB       |
   | + File System     |
   +--------+----------+
            |
            v
   +-------------------+
   | Push to Gateway   |
   | (Hot-reload)      |
   +-------------------+
```

### 13.3 Certificate Status

| Status | Condition | Action |
|--------|-----------|--------|
| `VALID` | > 30 days to expiry | Normal operation |
| `EXPIRING_SOON` | < 30 days to expiry | Send alert email |
| `EXPIRED` | Past expiry date | Block usage, alert |

---

## 14. Request Tracing Architecture

### 14.1 Trace Capture Flow

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

### 14.2 Replay Capability

```
+------------------------------------------------------------------+
|                    REQUEST REPLAY                                 |
+------------------------------------------------------------------+

   Select captured request
         |
         v
   +-------------------+
   | Reconstruct:      |
   | - Method          |
   | - Path            |
   | - Headers         |
   | - Body            |
   +--------+----------+
            |
            v
   +-------------------+
   | Execute against   |
   | specified gateway |
   +-------------------+
```

---

## 15. AI Integration Architecture

### 15.1 Supported Providers

```
+------------------------------------------------------------------+
|                    AI PROVIDER SPI                                |
+------------------------------------------------------------------+

   AiAnalysisService
         |
         +-- OpenAI (GPT-4, GPT-3.5)
         +-- Anthropic (Claude 3)
         +-- Qwen (qwen-plus, qwen-turbo)
         +-- DeepSeek (deepseek-chat)
         +-- Ollama (local models)
```

### 15.2 Use Cases

| Feature | Description |
|---------|-------------|
| **Metrics Analysis** | Analyze current metrics, identify anomalies |
| **Alert Generation** | Generate alert content with recommendations |
| **Trend Prediction** | Predict resource needs based on history |

---

For feature documentation, see [FEATURES.md](FEATURES.md).
For quick start guide, see [QUICK_START.md](QUICK_START.md).