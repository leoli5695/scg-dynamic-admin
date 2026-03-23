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
   |   |  -300     TraceIdGlobalFilter       Generate Trace ID + MDC       |  |
   |   |  -280     IPFilterGlobalFilter      IP Blacklist/Whitelist        |  |
   |   |  -250     AuthenticationGlobalFilter JWT/API Key/OAuth2 Auth      |  |
   |   |  -200     TimeoutGlobalFilter       Connection/Response Timeout   |  |
   |   |  -150     CircuitBreakerGlobalFilter Resilience4j Circuit Breaker |  |
   |   |  -100     HybridRateLimiterFilter   Redis + Local Rate Limiting   |  |
   |   |   ...     ...                       ...                          |  |
   |   |  10000    StaticProtocolGlobalFilter static:// -> lb:// transform |  |
   |   |  10150    DiscoveryLoadBalancerFilter Service Discovery + LB      |  |
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
  +-- order -300  TraceId          --> Generate/propagate X-Trace-Id, MDC logging
  |                                WHY FIRST? --> See everything for debugging
  +-- order -280  IP Filter        --> Whitelist/blacklist check -> 403 if blocked
  |                                WHY BEFORE AUTH? --> Fast rejection saves CPU (+37% TPS)
  +-- order -250  Authentication   --> JWT/API Key/OAuth2 validation -> 401 if failed
  |                                WHY AFTER IP FILTER? --> Don't waste JWT validation on bad IPs
  +-- order -200  Timeout          --> Inject timeout params into route metadata
  |                                WHY HERE? --> Protect downstream before routing
  +-- order -100  Circuit Breaker  --> Check circuit status -> 503 if open
  |                                WHY BEFORE RATE LIMIT? --> Downstream protection > self protection
  +-- order  -50  Rate Limiter     --> Redis sliding-window -> 429 if exceeded
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

### 5.1 Three-Level Cache

```
+------------------------------------------------------------------+
|                       CACHE ARCHITECTURE                         |
+------------------------------------------------------------------+

   +-------------------------------------------------------------+
   |          L1: In-Memory (AtomicReference)                    |
   |                                                             |
   |   RouteManager.routeConfigCache                             |
   |   ServiceManager.serviceConfigCache                         |
   |                                                             |
   |   - Nanosecond read latency                                 |
   |   - Thread-safe (AtomicReference)                           |
   |   - Auto-refresh on config change                           |
   +-------------------------------------------------------------+
                              |
                              | Cache Miss
                              v
   +-------------------------------------------------------------+
   |          L2: Fallback Cache (ConcurrentHashMap)             |
   |                                                             |
   |   GenericCacheManager.fallbackCaches                        |
   |                                                             |
   |   - Last known good configuration                           |
   |   - Used when Nacos is unavailable                          |
   |   - TTL-based expiration                                    |
   +-------------------------------------------------------------+
                              |
                              | Cache Miss
                              v
   +-------------------------------------------------------------+
   |          L3: Config Center (Nacos/Consul)                   |
   |                                                             |
   |   gateway-routes.json                                       |
   |   gateway-services.json                                     |
   |   gateway-strategies.json                                   |
   |                                                             |
   |   - Persistent storage                                      |
   |   - Distributed consistency                                 |
   |   - Real-time push via listener                             |
   +-------------------------------------------------------------+
```

### 5.2 Fallback Strategy

```
+------------------------------------------------------------------+
|                    CACHE FALLBACK FLOW                           |
+------------------------------------------------------------------+

   getConfig(key)
        |
        v
   +-------------+
   | L1 Valid?   |---- Yes ---> Return L1 Cache
   +------+------+
          | No
          v
   +-------------+
   | Fetch from  |
   |   Nacos     |
   +------+------+
          |
          +---- Success ---> Update L1 + L2 --> Return Config
          |
          +---- Failure ---> Return L2 Fallback (if exists)
                                   |
                                   v
                            +-------------+
                            | Send Alert  |
                            | (Nacos Down)|
                            +-------------+
```

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

## 10. Summary

This API Gateway architecture demonstrates:

- **Clean separation** of control plane (admin) and data plane (gateway)
- **Extensible design** via SPI and Strategy patterns
- **High availability** with fallback caching and graceful degradation
- **Real-time configuration** with < 1 second propagation latency
- **Enterprise features** including multi-auth, circuit breaking, and rate limiting

For feature documentation, see [FEATURES.md](FEATURES.md).