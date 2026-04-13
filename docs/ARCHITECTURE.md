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

> See [Authentication](features/authentication.md) for detailed auth configuration options.

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
   |   |  Order    Filter                    Function                      |  |
   |   |  -----    ---------------------     -------------------------    |  |
   |   |  -500     SecurityGlobalFilter      Security hardening            |  |
   |   |  -490     IPFilterGlobalFilter      IP Blacklist/Whitelist        |  |
   |   |  -400     AccessLogGlobalFilter     Access logging                |  |
   |   |  -300     CorsGlobalFilter          CORS handling                 |  |
   |   |  -300     TraceIdGlobalFilter       Generate Trace ID + MDC       |  |
   |   |  -255     RequestTransformFilter    Request body transformation   |  |
   |   |  -254     RequestValidationFilter   Request schema validation     |  |
   |   |  -250     AuthenticationGlobalFilter JWT/API Key/OAuth2 Auth      |  |
   |   |  -249     MockResponseFilter        Mock response for testing     |  |
   |   |  -200     TimeoutGlobalFilter       Connection/Response Timeout   |  |
   |   |  -150     ApiVersionGlobalFilter    API version routing           |  |
   |   |  -100     CircuitBreakerGlobalFilter Resilience4j Circuit Breaker |  |
   |   |   -50     HeaderOpGlobalFilter      Header manipulation           |  |
   |   |   -45     ResponseTransformFilter   Response body transformation  |  |
   |   |    50     CacheGlobalFilter         Response caching              |  |
   |   |   100     TraceCaptureGlobalFilter  Trace capture for debugging   |  |
   |   |   N/A     FilterChainTrackingGlobalFilter Performance monitoring   |  |
   |   |  9999     RetryGlobalFilter         Retry on failure              |  |
   |   | 10001     MultiServiceLoadBalancerFilter Multi-service routing     |  |
   |   | 10100     NacosDiscoveryLoadBalancerFilter Namespace/group override |  |
   |   | 10150     DiscoveryLoadBalancerFilter Service Discovery + LB       |  |
   |   |   N/A     ActuatorEndpointFilter    Actuator endpoint protection  |  |
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
  +-- order -490  IP Filter       --> IP whitelist/blacklist check -> 403 if blocked
  |                                WHY BEFORE ACCESS LOG? --> Fast rejection saves logging overhead
  |                                WHY BEFORE AUTH? --> Fast rejection saves CPU
  +-- order -400  Access Log      --> Log all requests for audit
  +-- order -300  CORS            --> Handle preflight requests
  +-- order -300  TraceId         --> Generate/propagate X-Trace-Id, MDC logging
  |                                WHY EARLY? --> See everything for debugging
  +-- order -255  Request Transform --> Modify request body (JSON↔XML, field mapping)
  +-- order -254  Request Validation --> Validate request schema/fields
  +-- order -250  Authentication  --> JWT/API Key/OAuth2 validation -> 401 if failed
  +-- order -249  Mock Response   --> Return mock data if configured (testing/development)
  +-- order -200  Timeout         --> Inject timeout params into route metadata
  +-- order -150  API Version     --> Version-based routing
  +-- order -100  Circuit Breaker --> Check circuit status -> 503 if open
  +-- order  -50  Header Op       --> Add/modify/remove headers
  +-- order  -45  Response Transform --> Modify response body before returning
  +-- order   50  Cache           --> Response caching
  +-- order  100  Trace Capture   --> Capture error/slow requests for debugging
  +-- order   N/A  Filter Chain Tracking --> Performance monitoring
  +-- order 9999  Retry           --> Retry on failure (before routing)
  +-- order 10001 Multi-Service  --> Multi-service routing + gray release
  +-- order 10100 Nacos Discovery --> Namespace/group override for cross-namespace routing
  +-- order 10150 Load Balancer  --> Service discovery + load balancing
  |                                WHY LAST? --> Final routing decision
  +-- order 10150+ Routing        --> Forward to backend
  +-- order   N/A  Actuator Endpoint --> Protect actuator endpoints
```

**Performance Impact:** IP Filter (-490) before Access Log (-400) and Authentication (-250) provides **+37% TPS improvement**

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
|   |-- filter/                    # Global Filters (organized by category)
|   |   |-- security/              # Security filters
|   |   |   |-- SecurityGlobalFilter.java      # XSS/SQL injection protection
|   |   |   |-- IPFilterGlobalFilter.java      # IP whitelist/blacklist
|   |   |   |-- AuthenticationGlobalFilter.java # JWT/API Key/OAuth2 auth
|   |   |   +-- CorsGlobalFilter.java          # CORS handling
|   |   |
|   |   |-- loadbalancer/          # Load balancing filters
|   |   |   |-- DiscoveryLoadBalancerFilter.java    # Service discovery LB (lb://)
|   |   |   |-- MultiServiceLoadBalancerFilter.java # Multi-service routing
|   |   |   |-- InstanceFilter.java                # Instance filtering
|   |   |   |-- InstanceSelector.java              # Instance selection
|   |   |   +-- InstanceRetryExecutor.java         # Retry execution
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
|   |   |   +-- MockResponseFilter.java           # Mock response for testing
|   |   |
|   |   +-- (root level)           # Other filters
|   |       |-- AccessLogGlobalFilter.java       # Access logging
|   |       |-- CacheGlobalFilter.java           # Response caching
|   |       |-- TraceIdGlobalFilter.java         # Trace ID generation
|   |       |-- TraceCaptureGlobalFilter.java    # Trace capture for debugging
|   |       |-- HeaderOpGlobalFilter.java        # Header operations
|   |       |-- ApiVersionGlobalFilter.java      # API version routing
|   |       +-- ActuatorEndpointFilter.java      # Actuator endpoint protection
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
|   |   +-- OAuth2AuthProcessor.java        # OAuth2 integration
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
|   |   |-- consul/ConsulDiscoveryService.java # Consul discovery
|   |   +-- staticdiscovery/StaticDiscoveryService.java # Static instances
|   |
|   |-- manager/                   # Configuration Managers
|   |   |-- RouteManager.java              # Route configuration
|   |   |-- ServiceManager.java            # Service configuration
|   |   +-- StrategyManager.java           # Strategy configuration
|   |
|   |-- refresher/                 # Config Refreshers (Nacos listeners)
|   |   |-- RouteRefresher.java            # Route config refresh
|   |   |-- ServiceRefresher.java          # Service config refresh
|   |   |-- StrategyRefresher.java         # Strategy config refresh
|   |   +-- AuthPolicyRefresher.java       # Auth policy refresh
|   |
|   |-- route/                     # Route Locator
|   |   +-- DynamicRouteDefinitionLocator.java # Dynamic route resolution
|   |
|   |-- limiter/                   # Rate Limiting Components
|   |   |-- DistributedRateLimiter.java    # Redis distributed limiter
|   |   |-- RedisHealthChecker.java        # Redis health monitoring
|   |   |-- ShadowQuotaManager.java        # Shadow quota for failover
|   |   +-- RateLimitResult.java           # Rate limit result model
|   |
|   |-- ssl/                       # SSL Certificate Management
|   |   |-- SslCertificateLoader.java      # Certificate loading
|   |   |-- SslServerConfig.java           # SSL server configuration
|   |   +-- DynamicSslContextManager.java  # Dynamic SSL context
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
|   |   +-- ScgGlobalExceptionHandler.java # Global exception handler
|   |
|   |-- constants/                 # Constants
|   |   |-- FilterOrderConstants.java      # Filter execution order
|   |   +-- GatewayConfigConstants.java    # Config constants
|   |
|   |-- model/                     # Configuration Models
|   |   |-- AuthConfig.java                # Auth configuration
|   |   |-- CircuitBreakerConfig.java      # Circuit breaker config
|   |   |-- IPFilterConfig.java            # IP filter config
|   |   |-- RateLimiterConfig.java         # Rate limiter config
|   |   |-- TimeoutConfig.java             # Timeout config
|   |   |-- MultiServiceConfig.java        # Multi-service routing config
|   |   |-- RequestTransformConfig.java    # Request transform config
|   |   |-- RequestValidationConfig.java   # Request validation config
|   |   |-- ResponseTransformConfig.java   # Response transform config
|   |   |-- MockResponseConfig.java        # Mock response config
|   |   +-- StrategyDefinition.java        # Strategy definition
|   |
|   |-- health/                    # Health Check & Heartbeat
|   |   |-- HeartbeatReporter.java         # Heartbeat to admin
|   |   |-- ActiveHealthChecker.java       # Active health check
|   |   |-- HybridHealthChecker.java       # Hybrid health monitoring
|   |   |-- InstanceDiscoveryService.java  # Instance discovery
|   |   +-- HealthStatusSyncTask.java      # Health status sync
|   |
|   +-- config/                    # Spring Configuration
|       |-- GatewayConfig.java             # Gateway config
|       |-- CorsConfig.java                # CORS config
|       |-- RedisConfig.java               # Redis config
|       |-- WebClientConfig.java           # WebClient config
|       +-- HeartbeatProperties.java       # Heartbeat properties
```

### 6.2 gateway-admin (Management Console)

```
gateway-admin/
|-- src/main/java/com/leoli/gateway/admin/
|   |
|   |-- controller/                # REST API Endpoints
|   |   |-- BaseController.java             # Base controller utilities
|   |   |-- RouteController.java            # Route management API
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
|   |   |-- AuditLogController.java         # Audit log API
|   |   |-- AiConfigController.java         # AI analysis config API
|   |   |-- HealthSyncController.java       # Health sync API
|   |   |-- InstanceHealthController.java   # Instance health API
|   |   +-- HealthCheckController.java      # Health check API
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
|   |-- reconcile/                 # Config Reconciliation Tasks
|   |   |-- ReconcileTask.java              # Base reconcile task
|   |   |-- RouteReconcileTask.java         # Route reconcile
|   |   |-- ServiceReconcileTask.java       # Service reconcile
|   |   |-- AuthPolicyReconcileTask.java    # Auth policy reconcile
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

---

#### 10.1.2 Design Decision: Shadow Quota vs Async Dual-Write

When designing the Redis failover strategy for rate limiting, we evaluated two production-grade approaches:

| Criterion | Shadow Quota | Async Dual-Write |
|-----------|--------------|------------------|
| **Core Idea** | Pre-calculate local quota based on historical traffic | Write to both Redis and local counters asynchronously |
| **Complexity** | Medium (⭐⭐) | High (⭐⭐⭐⭐) |
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
┌─────────────────────────────────────────────────────────────┐
│  Every 1 second:                                            │
│    1. Fetch current global QPS from Redis                   │
│    2. Get gateway node count from service discovery         │
│    3. Calculate: shadowQuota = globalQPS / nodeCount        │
│    4. Store in AtomicLong (in-memory)                       │
└─────────────────────────────────────────────────────────────┘

Redis Failure:
┌─────────────────────────────────────────────────────────────┐
│  1. Detect Redis unavailable (error in rate limit call)     │
│  2. Switch to local mode immediately                        │
│  3. Use pre-calculated shadowQuota as local limit           │
│  4. No counter reset - continues at same rate!              │
└─────────────────────────────────────────────────────────────┘
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
┌─────────────────────────────────────────────────────────────┐
│  Every request:                                             │
│    1. Write to Redis counter (async, non-blocking)          │
│    2. Write to local counter (sync, in-memory)              │
│    3. Use local counter for decision (faster)               │
│                                                              │
│  Background reconciliation (every 100ms):                   │
│    4. Sync local counters with Redis                        │
│    5. Resolve conflicts (Redis wins for consistency)        │
└─────────────────────────────────────────────────────────────┘

Redis Failure:
┌─────────────────────────────────────────────────────────────┐
│  1. Redis write fails silently (async)                      │
│  2. Local counter continues working                         │
│  3. Queue pending Redis writes for retry                    │
│  4. When Redis recovers: flush pending writes               │
└─────────────────────────────────────────────────────────────┘
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
┌─────────────────────────────────────────────────────────────┐
│  Core code: ~100 lines                                      │
│  Variables: 3 AtomicLongs                                   │
│  Background tasks: 1 scheduled update                       │
│  Failure handling: Simple switch + gradual recovery         │
└─────────────────────────────────────────────────────────────┘

Async Dual-Write implementation:
┌─────────────────────────────────────────────────────────────┐
│  Core code: ~500+ lines                                     │
│  Variables: Map<String, AtomicLong> + pending queue         │
│  Background tasks: Sync + reconcile + conflict resolution   │
│  Failure handling: Queue management + retry + merge logic   │
└─────────────────────────────────────────────────────────────┘
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
┌─────────────────────────────────────────────────────────────┐
│  Shadow Quota:                                              │
│    - Basic concurrent programming (AtomicLong)              │
│    - Spring @Scheduled                                      │
│    - Service discovery integration                          │
│                                                              │
│  Async Dual-Write:                                          │
│    - Advanced concurrent programming (queues, CAS)          │
│    - Distributed systems (CAP theorem, consistency models)  │
│    - Conflict resolution algorithms                         │
│    - Memory management for large counter maps               │
│    - Async error handling and retry strategies              │
└─────────────────────────────────────────────────────────────┘
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
T=30s:     Redis fails → Each node: ~2,000 QPS (NO CHANGE!)
T=60s:     Recovery starts → Gradual shift over 10s
T=70s:     Full recovery, traffic normal

Backend receives: Stable 10,000 QPS throughout

Async Dual-Write Traffic Pattern:
T=0-30s:   Each node: ~2,000 QPS (stable)
T=30s:     Redis fails → Brief spike to ~12,000 QPS (sync lag)
T=35s:     Local counters adjust → Back to ~10,000 QPS
T=60s:     Recovery → Pending writes flush, brief dip to ~8,000
T=65s:     Stabilized

Backend receives: Variable 8,000-12,000 QPS during transitions
```

**Key insight:** Shadow Quota provides smoother traffic during both failure and recovery.

---

### Conclusion

For a typical enterprise API gateway serving 1K - 100K QPS:

| Factor | Winner |
|--------|--------|
| Implementation simplicity | Shadow Quota ✅ |
| Operational predictability | Shadow Quota ✅ |
| Team maintenance cost | Shadow Quota ✅ |
| Traffic stability | Shadow Quota ✅ |
| Raw performance (latency) | Async Dual-Write |
| Ultra-high QPS scaling | Async Dual-Write |

**Our decision: Shadow Quota**

The slight performance advantage of Async Dual-Write does not justify its 5x complexity increase for typical gateway use cases. We prioritized:

1. **Simplicity** - Easier to implement, debug, and maintain
2. **Predictability** - Known behavior under failure scenarios
3. **Operational cost** - No extra sync traffic or memory overhead

> "A system that is simple enough to understand is simple enough to fix."
> — The pragmatism behind our design decision

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

> See [Multi-Service Routing](features/multi-service-routing.md) for gray release and canary deployment configuration.

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

> See [SSL Termination](features/ssl-termination.md) for certificate management and renewal features.

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

> See [Request Tracing](features/request-tracing.md) and [Request Replay Debugger](features/request-replay.md) for detailed features.

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

> See [AI Copilot Assistant](features/ai-copilot.md) for detailed tool calling capabilities, and [AI-Powered Analysis](features/ai-analysis.md) for metrics analysis.

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

## 16. Gateway Instance Management Architecture

> See [Instance Management](features/instance-management.md) for lifecycle management features, and [Kubernetes Integration](features/kubernetes-integration.md) for deployment automation.

### 16.1 Overview

The platform supports managing multiple gateway instances, each deployed to Kubernetes with isolated configuration.

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

### 16.2 Instance Entity

```
+------------------------------------------------------------------+
|                    GatewayInstanceEntity                          |
+------------------------------------------------------------------+

   +-------------------------------------------------------------+
   |  instanceId: UUID          - Unique identifier              |
   |  instanceName: String      - Display name                   |
   |  clusterId: Long           - K8s cluster reference          |
   |  namespace: String         - K8s namespace                  |
   |  nacosNamespace: String    - Nacos namespace for isolation  |
   |  specType: String          - small/medium/large/xlarge      |
   |  cpuCores: Double          - CPU allocation                 |
   |  memoryMB: Integer         - Memory allocation              |
   |  replicas: Integer         - Pod replica count              |
   |  statusCode: Integer       - 0=starting, 1=running, ...     |
   |  lastHeartbeatTime: Date   - Last heartbeat timestamp       |
   +-------------------------------------------------------------+
```

### 16.3 Namespace Isolation

Each gateway instance has its own Nacos namespace for configuration isolation:

```
+------------------------------------------------------------------+
|                    NAMESPACE ISOLATION                            |
+------------------------------------------------------------------+

   Instance: gateway-dev
   Nacos Namespace: gateway-dev-xxx
   +-------------------------------------------------------------+
   |  config.gateway.route-{id}        - Route configs            |
   |  config.gateway.service-{id}      - Service configs          |
   |  config.gateway.strategy-{id}     - Strategy configs         |
   |  config.gateway.metadata.*-index  - Index metadata           |
   +-------------------------------------------------------------+

   Instance: gateway-prod
   Nacos Namespace: gateway-prod-xxx
   +-------------------------------------------------------------+
   |  config.gateway.route-{id}        - Route configs            |
   |  config.gateway.service-{id}      - Service configs          |
   |  config.gateway.strategy-{id}     - Strategy configs         |
   |  config.gateway.metadata.*-index  - Index metadata           |
   +-------------------------------------------------------------+
```

### 16.4 Heartbeat Mechanism

```
+------------------------------------------------------------------+
|                    HEARTBEAT FLOW                                 |
+------------------------------------------------------------------+

   Gateway Instance (K8s Pod)
          |
          | POST /api/instances/{id}/heartbeat
          | every 10 seconds
          v
   +-------------------+
   | gateway-admin     |
   | InstanceHealth    |
   | Controller        |
   +--------+----------+
            |
            v
   +-------------------+
   | Update Database   |
   | - lastHeartbeat   |
   | - cpuUsage        |
   | - memoryUsage     |
   | - requestsPerSec  |
   +--------+----------+
            |
            v
   +-------------------+
   | Check Status      |
   | - If heartbeat ok |
   |   → RUNNING       |
   | - If missed > 3   |
   |   → ERROR         |
   +-------------------+
```

### 16.5 Instance Status Codes

| Code | Status | Description |
|------|--------|-------------|
| 0 | STARTING | Pod is starting up |
| 1 | RUNNING | Healthy, receiving heartbeats |
| 2 | ERROR | Missed heartbeats or crashed |
| 3 | STOPPING | Pod is shutting down |
| 4 | STOPPED | Pod is stopped |

---

## 17. Kubernetes Integration Architecture

### 17.1 Deployment Flow

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
   | via Fabric8 SDK   |
   +--------+----------+
            |
            v
   +-------------------+
   | Watch Pod Status  |
   | - Ready?          |
   | - Error?          |
   +-------------------+
```

### 17.2 Resource Specs

```
+------------------------------------------------------------------+
|                    INSTANCE SPEC TYPES                            |
+------------------------------------------------------------------+

   small (Development)
   +-------------------------------------------------------------+
   |  CPU: 0.5 cores    |  Memory: 512MB   |  Replicas: 1       |
   +-------------------------------------------------------------+

   medium (Staging)
   +-------------------------------------------------------------+
   |  CPU: 1 core       |  Memory: 1GB     |  Replicas: 2       |
   +-------------------------------------------------------------+

   large (Production)
   +-------------------------------------------------------------+
   |  CPU: 2 cores      |  Memory: 2GB     |  Replicas: 3       |
   +-------------------------------------------------------------+

   xlarge (High-traffic)
   +-------------------------------------------------------------+
   |  CPU: 4 cores      |  Memory: 4GB     |  Replicas: 5       |
   +-------------------------------------------------------------+
```

### 17.3 Environment Variables

Each gateway pod receives these environment variables:

| Variable | Description |
|----------|-------------|
| `NACOS_SERVER_ADDR` | Nacos server address |
| `NACOS_NAMESPACE` | Isolated namespace ID |
| `GATEWAY_ADMIN_URL` | Admin service URL for heartbeats |
| `GATEWAY_ID` | Unique instance identifier |
| `REDIS_HOST` | Redis server for rate limiting |
| `REDIS_PORT` | Redis port |

### 17.4 Health Checks

```
+------------------------------------------------------------------+
|                    K8S HEALTH CHECKS                              |
+------------------------------------------------------------------+

   Liveness Probe:
   +-------------------------------------------------------------+
   |  Path: /actuator/health/liveness                            |
   |  Port: 8081                                                 |
   |  Initial: 60s | Period: 15s | Timeout: 10s                  |
   +-------------------------------------------------------------+

   Readiness Probe:
   +-------------------------------------------------------------+
   |  Path: /actuator/health/readiness                           |
   |  Port: 8081                                                 |
   |  Initial: 30s | Period: 10s | Timeout: 5s                   |
   +-------------------------------------------------------------+
```

---

## 18. Config Reconciliation Architecture

### 18.1 Overview

The reconcile task ensures consistency between database and Nacos configuration.

```
+------------------------------------------------------------------+
|                    RECONCILIATION TASK                            |
+------------------------------------------------------------------+

   Scheduled (Every 5 minutes)
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

### 18.2 Reconciliation Tasks

| Task | Description |
|------|-------------|
| `RouteReconcileTask` | Ensures routes are synced to Nacos |
| `ServiceReconcileTask` | Ensures services are synced to Nacos |
| `AuthPolicyReconcileTask` | Ensures auth policies are synced |

---

## 19. Testing Architecture

### 19.1 Test Coverage

| Module | Tests | Coverage Areas |
|--------|-------|----------------|
| **my-gateway** | 332 | Filters, Auth, Rate Limiting, Strategies |
| **gateway-admin** | 229 | API, Services, Repository, Integration |

### 19.2 Test Categories

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

### 19.3 Test Namespace Isolation

Tests use isolated Nacos namespace (`gateway-test`) to avoid polluting production configuration:

```yaml
# application-test.yml
spring:
  cloud:
    nacos:
      discovery:
        namespace: gateway-test
      config:
        namespace: gateway-test
```

The namespace is auto-created if it doesn't exist.

---

## 20. Performance Optimizations

The gateway implements several performance optimizations to ensure high throughput and low latency.

### 20.1 JWT Validation Cache

Avoids repeated signature verification for the same token:

```
+------------------------------------------------------------------+
|                    JWT VALIDATION CACHE                           |
+------------------------------------------------------------------+

   Incoming Request with JWT
            │
            ▼
   +-----------------+
   | Cache Lookup    │ ── Hit ──▶ Return cached Claims (O(1))
   +--------+--------+
            │ Miss
            ▼
   +-----------------+
   | Verify Signature│
   | Parse Claims    │
   +--------+--------+
            │
            ▼
   +-----------------+
   | Cache Result    │
   | (with TTL)      │
   +-----------------+

   Cache Features:
   - Max size: 10,000 entries
   - Auto-expiration based on JWT exp claim
   - Scheduled cleanup every 60 seconds
   - Memory-efficient eviction (oldest 20% when full)
```

**Configuration:**
```yaml
# JWT cache is enabled by default
# Automatic cleanup runs every 60 seconds
# Max cache size: 10,000 entries
```

**Performance Impact:** ~90% reduction in JWT verification overhead for repeated tokens.

### 20.2 Shadow Quota for Redis Failover

Graceful degradation when Redis becomes unavailable:

```
+------------------------------------------------------------------+
|                    SHADOW QUOTA FAILOVER                          |
+------------------------------------------------------------------+

   Normal Operation (Redis Healthy):
   +-------------------------------------------------------------+
   |  1. Record global QPS snapshot every second                |
   |  2. Monitor cluster node count via service discovery       |
   |  3. Calculate: localQuota = globalQPS / nodeCount          |
   |  4. Store as "shadow quota" for failover                   |
   +-------------------------------------------------------------+

   Redis Failure Detected:
   +-------------------------------------------------------------+
   |  1. Switch to local rate limiting mode                      |
   |  2. Inherit pre-calculated shadow quota (no reset!)         |
   |  3. Continue limiting at approximately same rate            |
   |  4. Backend receives stable traffic (no spike!)             |
   +-------------------------------------------------------------+

   Redis Recovery:
   +-------------------------------------------------------------+
   |  1. Gradual traffic shifting (10% per second)              |
   |  2. Prevent thundering herd to Redis                        |
   |  3. Full recovery in 10 seconds                             |
   +-------------------------------------------------------------+

   Example:
   - Global limit: 10,000 QPS, Nodes: 5
   - Shadow quota: 10,000 / 5 = 2,000 QPS per node
   - Redis fails → Each node continues at ~2,000 QPS
   - Backend traffic: stable at ~10,000 QPS
```

**Configuration:**
```yaml
gateway:
  rate-limiter:
    shadow-quota:
      enabled: true
      min-node-count: 1
```

### 20.3 WebClient Connection Pool

Optimized HTTP connection pooling for outbound calls:

```
+------------------------------------------------------------------+
|                    WEBCLIENT CONNECTION POOL                      |
+------------------------------------------------------------------+

   Configuration:
   +-------------------------------------------------------------+
   |  maxConnections: 100          (total pool size)             |
   |  maxConnectionsPerHost: 20    (per-target limit)            |
   |  pendingAcquireTimeout: 30s   (wait for available conn)     |
   |  pendingAcquireMaxCount: 500  (max waiting requests)        |
   |  idleTimeout: 60s             (evict idle connections)      |
   |  maxLifeTime: 5min            (force refresh connections)   |
   |  connectTimeout: 5s           (TCP connection timeout)      |
   |  responseTimeout: 30s         (full response timeout)       |
   +-------------------------------------------------------------+

   Features:
   - Shared connection pool for OAuth2, heartbeat, trace replay
   - Automatic connection refresh prevents stale connections
   - Compression enabled (gzip)
```

### 20.4 Hybrid Health Checker

Combines passive and active health checks with local caching:

```
+------------------------------------------------------------------+
|                    HYBRID HEALTH CHECKER                          |
+------------------------------------------------------------------+

   Passive Check (Zero Overhead):
   +-------------------------------------------------------------+
   |  recordSuccess(serviceId, ip, port)                         |
   |  - Called after each successful request                     |
   |  - Updates local health cache                               |
   |  - No additional network calls                               |
   +-------------------------------------------------------------+

   Active Check (On-demand):
   +-------------------------------------------------------------+
   |  checkHealth(serviceId, ip, port)                           |
   |  - Triggered when passive check indicates unhealthy         |
   |  - HTTP call to /actuator/health endpoint                   |
   |  - Failure threshold: 3 consecutive failures                |
   +-------------------------------------------------------------+

   Local Cache (Caffeine):
   +-------------------------------------------------------------+
   |  Maximum size: 10,000 instances                             |
   |  Expiration: 5 minutes                                      |
   |  Stats recording enabled                                    |
   +-------------------------------------------------------------+

   Network Flap Protection:
   +-------------------------------------------------------------+
   |  - Ignore mass status changes (>10 at once)                 |
   |  - Prevents cascading false negatives                       |
   +-------------------------------------------------------------+
```

**Configuration:**
```yaml
gateway:
  health:
    batch-size: 50
    failure-threshold: 3
    recovery-time: 30000
    idle-threshold: 300000
    network-flap-threshold: 10
```

### 20.5 Non-Blocking Lock Optimization (CAS + tryLock)

Local rate limiter uses hybrid locking to avoid blocking EventLoop threads:

```
+------------------------------------------------------------------+
|                    NON-BLOCKING LOCK STRATEGY                     |
+------------------------------------------------------------------+

   Fast Path (Low Contention - Optimistic):
   +-------------------------------------------------------------+
   |  if (currentCount.compareAndSet(count, count + 1)) {       |
   |      return true;  // Success without blocking              |
   |  }                                                          |
   +-------------------------------------------------------------+

   Slow Path (High Contention - Never Blocks!):
   +-------------------------------------------------------------+
   |  if (lock.tryLock()) {                                      |
   |      try {                                                  |
   |          // Double-check under lock                         |
   |          if (count < maxRequests) {                         |
   |              currentCount.incrementAndGet();                |
   |              return true;                                   |
   |          }                                                  |
   |          return false;                                      |
   |      } finally {                                            |
   |          lock.unlock();                                     |
   |      }                                                      |
   |  }                                                          |
   |  return false;  // Immediately reject - no blocking!        |
   +-------------------------------------------------------------+

   Benefits:
   - No thread blocking in reactive context
   - High throughput under normal load (CAS)
   - Safe degradation under extreme contention (tryLock)
   - Prevents EventLoop thread starvation
```

### 20.6 Access Log File Rotation (CAS Atomic Update)

Atomic file path updates for daily log rotation:

```java
// Use CAS to atomically update file path if date changed
if (!logFileState.compareAndSet(currentState, newState)) {
    // CAS update - if another thread already updated, use their path
    currentState = logFileState.get();
}
```

### 20.7 Instance Discovery Optimization

O(1) contains check instead of O(n):

```java
// Optimized: Use Set for O(1) contains() check instead of List's O(n)
private Set<String> discoverGatewayInstances() {
    // Returns Set for fast lookup
}
```

### 20.8 Performance Summary

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

## 21. Audit Logs Architecture

### 21.1 Overview

Audit logs system tracks all configuration changes for compliance and troubleshooting.

```
+------------------------------------------------------------------+
|                    AUDIT LOGS ARCHITECTURE                        |
+------------------------------------------------------------------+

   Configuration Operation (CREATE/UPDATE/DELETE)
          |
          v
   +-------------------+
   | Controller Layer  |
   | (Route, Service,  |
   |  Strategy, Auth)  |
   +--------+----------+
            |
            v
   +-------------------+
   | AuditLogService   |
   | - Record operation|
   | - Capture old/new |
   | - Compute diff    |
   +--------+----------+
            |
            v
   +-------------------+
   | AuditLogRepository|
   | (MySQL Storage)   |
   +-------------------+
            |
            | Scheduled Cleanup
            v
   +-------------------+
   | AuditLogCleanup   |
   | Scheduler         |
   | (Delete > 30 days)|
   +-------------------+
```

### 21.2 Data Model

```java
@Entity
public class AuditLogEntity {
    private Long id;
    private String instanceId;       // Instance isolation
    private String operator;         // Who made the change
    private String operationType;    // CREATE, UPDATE, DELETE, etc.
    private String targetType;       // ROUTE, SERVICE, STRATEGY, AUTH_POLICY
    private String targetId;         // ID of changed object
    private String targetName;       // Display name
    private String oldValue;         // JSON before change
    private String newValue;         // JSON after change
    private String ipAddress;        // Client IP
    private Date createdAt;          // Timestamp
}
```

### 21.3 Diff Computation

```
+------------------------------------------------------------------+
|                    DIFF COMPUTATION                               |
+------------------------------------------------------------------+

   oldValue JSON     newValue JSON
        |                |
        v                v
   +------------------------------------------+
   |         JSON Diff Algorithm               |
   |  - Compare field by field                 |
   |  - Detect added, removed, modified fields |
   |  - Generate structured diff report        |
   +------------------------------------------+
            |
            v
   +-------------------+
   | DiffResult        |
   | changes: [{       |
   |   type: "modified"|
   |   field: "qps"    |
   |   oldValue: "100" |
   |   newValue: "200" |
   | }]                |
   +-------------------+
```

### 21.4 Rollback Mechanism

```
+------------------------------------------------------------------+
|                    ROLLBACK FLOW                                  |
+------------------------------------------------------------------+

   User selects audit log entry
          |
          v
   +-------------------+
   | Get oldValue JSON |
   +--------+----------+
            |
            v
   +-------------------+
   | Validate oldValue |
   | (Schema check)    |
   +--------+----------+
            |
            v
   +-------------------+
   | Apply oldValue    |
   | to current config |
   +--------+----------+
            |
            v
   +-------------------+
   | Publish to Nacos  |
   +--------+----------+
            |
            v
   +-------------------+
   | Record ROLLBACK   |
   | operation         |
   +-------------------+
```

---

## 22. System Diagnostic Architecture

### 22.1 Overview

System diagnostic performs comprehensive health checks across all gateway components.

```
+------------------------------------------------------------------+
|                    DIAGNOSTIC ARCHITECTURE                        |
+------------------------------------------------------------------+

   User Request (quick/full)
          |
          v
   +-------------------+
   | DiagnosticService |
   | - Coordinate checks|
   | - Aggregate results|
   +--------+----------+
            |
      +-----+-----+-----+-----+-----+-----+
      |     |     |     |     |     |     |
      v     v     v     v     v     v     v
   +-----+ +-----+ +-----+ +-----+ +-----+ +-----+
   | DB  | |Redis| |Nacos| |Route| |Auth | |Inst |
   |Check| |Check| |Check| |Check| |Check| |Check|
   +-----+ +-----+ +-----+ +-----+ +-----+ +-----+
      |     |     |     |     |     |     |
      +-----+-----+-----+-----+-----+-----+
            |
            v
   +-------------------+
   | DiagnosticReport  |
   | - Overall score   |
   | - Recommendations |
   | - Component status|
   +-------------------+
```

### 22.2 Check Types

| Check | Implementation |
|-------|----------------|
| **Database** | JPA health check, query timing, connection pool status |
| **Redis** | Ping test, INFO command parsing, memory analysis |
| **Nacos** | Config read/write test, service discovery test |
| **Routes** | Load all routes, validate predicates/filters |
| **Auth** | Test JWT validation, check policy consistency |
| **Instances** | Heartbeat status, pod health via K8s API |
| **Performance** | JVM metrics, thread count, CPU/memory usage |

### 22.3 Scoring Algorithm

```
+------------------------------------------------------------------+
|                    HEALTH SCORE CALCULATION                       |
+------------------------------------------------------------------+

   Component Scores:
   - Database:     100 if HEALTHY, 50 if WARNING, 0 if CRITICAL
   - Redis:        100 if HEALTHY, 50 if WARNING, 0 if CRITICAL
   - Nacos:        100 if HEALTHY, 50 if WARNING, 0 if CRITICAL
   - Routes:       Based on enabled/valid route percentage
   - Auth:         100 if policies valid, 0 if issues
   - Instances:    Based on healthy instance percentage
   - Performance:  Based on CPU/memory thresholds

   Overall Score = Weighted Average:
   - Database:     20%
   - Redis:        15%
   - Nacos:        20%
   - Routes:       15%
   - Auth:         10%
   - Instances:    10%
   - Performance:  10%
```

---

## 23. Traffic Topology Architecture

### 23.1 Overview

Traffic topology visualizes real-time request flow through the gateway using ECharts force-directed graph.

```
+------------------------------------------------------------------+
|                    TOPOLOGY DATA COLLECTION                       |
+------------------------------------------------------------------+

   Gateway Access Logs
          |
          v
   +-------------------+
   | Access Log Parser |
   | - Extract clientIP|
   | - Extract routeId |
   | - Extract service |
   | - Aggregate metrics|
   +--------+----------+
            |
            v
   +-------------------+
   | Topology Graph    |
   | Builder           |
   | - Create nodes    |
   | - Create edges    |
   | - Compute metrics |
   +--------+----------+
            |
            v
   +-------------------+
   | Frontend ECharts  |
   | Force-directed    |
   | Graph             |
   +-------------------+
```

### 23.2 Node Types

| Node Type | Source | Size Logic |
|-----------|--------|------------|
| `gateway` | Instance config | Fixed size (60) |
| `route` | Route definitions | Fixed size (40) |
| `service` | Service registry | Fixed size (50) |
| `client` | Client IP aggregation | Dynamic (15-30) based on request count |

### 23.3 Edge Metrics

```
+------------------------------------------------------------------+
|                    EDGE METRIC COMPUTATION                        |
+------------------------------------------------------------------+

   For each client -> route -> service path:

   Edge Metrics:
   - requestCount: Sum of requests on this path
   - avgLatency: Weighted average of response times
   - errorRate: (4xx + 5xx) / total requests

   Edge Width:
   - Width = min(8, max(1, requestCount / 50))

   Edge Color:
   - Green: errorRate < 5%
   - Orange: errorRate 5-10%
   - Red: errorRate > 10%
```

---

## 24. Filter Chain Analysis Architecture

### 24.1 Overview

Filter chain analysis tracks execution statistics for each filter in the request processing pipeline.

```
+------------------------------------------------------------------+
|                    FILTER CHAIN TRACKING                          |
+------------------------------------------------------------------+

   Request Processing
          |
          v
   +-------------------+
   | Filter Execution  |
   | (Each Filter)     |
   +--------+----------+
            |
            | Record: filterName, duration, success/error
            v
   +-------------------+
   | FilterChainTracker|
   | - Atomic counters |
   | - Ring buffer for |
   |   recent traces   |
   +--------+----------+
            |
            v
   +-------------------+
   | Statistics Store  |
   | (ConcurrentHashMap|
   |  per filter)      |
   +-------------------+
```

### 24.2 Statistics Collection

```java
public class FilterStats {
    private String filterName;
    private int order;
    private AtomicLong totalCount = new AtomicLong();
    private AtomicLong successCount = new AtomicLong();
    private AtomicLong failureCount = new AtomicLong();
    private AtomicLong totalDurationMicros = new AtomicLong();
    private AtomicLong maxDurationMicros = new AtomicLong();
    private AtomicLong minDurationMicros = new AtomicLong(Long.MAX_VALUE);
}
```

### 24.3 Trace Recording

```
+------------------------------------------------------------------+
|                    TRACE RECORD STRUCTURE                         |
+------------------------------------------------------------------+

   FilterChainRecord:
   - traceId: Unique request ID
   - createdAt: Timestamp
   - totalDurationMs: Total request duration
   - successCount: Filters that succeeded
   - failureCount: Filters that failed
   - executions: [
       {
         filter: "AuthenticationGlobalFilter",
         order: -250,
         durationMs: 5,
         durationMicros: 5200,
         success: true,
         error: null
       },
       {
         filter: "RateLimiterFilter",
         order: -200,
         durationMs: 2,
         durationMicros: 2100,
         success: false,
         error: "Rate limit exceeded"
       }
     ]
```

---

## 25. Request Replay Architecture

### 25.1 Overview

Request replay allows developers to replay captured requests with modifications for debugging.

```
+------------------------------------------------------------------+
|                    REQUEST REPLAY FLOW                            |
+------------------------------------------------------------------+

   Select Trace Record
          |
          v
   +-------------------+
   | Load Trace Data   |
   | - method          |
   | - path            |
   | - headers         |
   | - body            |
   +--------+----------+
            |
            v
   +-------------------+
   | User Modifications|
   | (Optional)        |
   | - Edit path       |
   | - Edit headers    |
   | - Edit body       |
   +--------+----------+
            |
            v
   +-------------------+
   | Replay Execution  |
   | - WebClient call  |
   | - Capture response|
   +--------+----------+
            |
            v
   +-------------------+
   | Compare Results   |
   | - Status match    |
   | - Latency diff    |
   | - Body diff       |
   +-------------------+
```

### 25.2 Replayable Request Structure

```java
public class ReplayableRequest {
    private Long traceId;
    private String traceUuid;
    private String method;
    private String path;
    private String queryString;
    private Map<String, String> headers;
    private Map<String, String> originalHeaders;  // For diff
    private String requestBody;
    private String originalRequestBody;          // For diff
    private int originalStatusCode;
    private String originalResponseBody;
    private long originalLatencyMs;
}
```

### 25.3 Comparison Algorithm

```
+------------------------------------------------------------------+
|                    RESPONSE COMPARISON                            |
+------------------------------------------------------------------+

   Original Response    Replayed Response
          |                    |
          v                    v
   +------------------------------------------+
   |           Comparison Engine               |
   |                                          |
   | 1. Status comparison:                    |
   |    statusMatch = (orig == replay)        |
   |                                          |
   | 2. Latency comparison:                   |
   |    latencyDiff = replay - orig           |
   |                                          |
   | 3. Body comparison (JSON):               |
   |    - Parse both JSONs                    |
   |    - Field-by-field comparison           |
   |    - Detect added/removed/modified       |
   +------------------------------------------+
            |
            v
   +-------------------+
   | ComparisonResult  |
   +-------------------+
```

---

## 26. AI Copilot Architecture

### 26.1 Overview

AI Copilot integrates multiple large language model providers for intelligent gateway assistance.

```
+------------------------------------------------------------------+
|                    AI COPILOT ARCHITECTURE                        |
+------------------------------------------------------------------+

   User Request
          |
          v
   +-------------------+
   | CopilotController |
   | - Chat            |
   | - Generate route  |
   | - Analyze error   |
   | - Optimize        |
   +--------+----------+
            |
            v
   +-------------------+
   | CopilotService    |
   | - Provider routing|
   | - Context building|
   | - Response parsing|
   +--------+----------+
            |
      +-----+-----+-----+-----+-----+
      |     |     |     |     |     |
      v     v     v     v     v     v
   +-----+ +-----+ +-----+ +-----+ +-----+
   |Qwen | |DeepS| |OpenAI| |Anthr| |Ollam|
   |API  | |eek  | | API  | |opic | |a    |
   +-----+ +-----+ +-----+ +-----+ +-----+
```

### 26.2 Provider Configuration

```java
public class AiProviderConfig {
    private String provider;        // qwen, deepseek, openai, anthropic, ollama
    private String apiKey;          // API key (encrypted)
    private String baseUrl;         // Custom API endpoint (optional)
    private String model;           // Model name
    private String region;          // DOMESTIC or OVERSEAS
    private boolean isValid;        // API key validated
}
```

### 26.3 Context Building

```
+------------------------------------------------------------------+
|                    CONTEXT BUILDING FOR AI                        |
+------------------------------------------------------------------+

   For Route Generation:
   +------------------------------------------+
   |  Context:                                |
   |  - Gateway capabilities description      |
   |  - Available predicates/filters          |
   |  - Current services list                 |
   |  - User description                      |
   +------------------------------------------+

   For Error Analysis:
   +------------------------------------------+
   |  Context:                                |
   |  - Gateway architecture                  |
   |  - Common error patterns                 |
   |  - Error message to analyze              |
   |  - Current configuration                 |
   +------------------------------------------+

   For Optimization:
   +------------------------------------------+
   |  Context:                                |
   |  - Current metrics (CPU, memory, QPS)    |
   |  - Route configuration                   |
   |  - Strategy settings                     |
   |  - Performance tuning guidelines         |
   +------------------------------------------+
```

### 26.4 Response Processing

```
+------------------------------------------------------------------+
|                    AI RESPONSE PROCESSING                         |
+------------------------------------------------------------------+

   AI Response (Markdown)
          |
          v
   +-------------------+
   | Parse Response    |
   | - Extract JSON    |
   | - Extract text    |
   +--------+----------+
            |
            v
   +-------------------+
   | Validate JSON     |
   | (for route config)|
   +--------+----------+
            |
            | Valid?
      +-----+-----+
      Yes    No
      |       |
      v       v
   Return   Return
   Config   Error
   to User  Message
```

---

## 27. Stress Test Architecture

### 27.1 Overview

Stress test tool simulates concurrent load on gateway endpoints to measure performance.

```
+------------------------------------------------------------------+
|                    STRESS TEST ARCHITECTURE                       |
+------------------------------------------------------------------+

   User Request
          |
          v
   +-------------------+
   | StressTestService |
   | - Create test plan|
   | - Coordinate load |
   +--------+----------+
            |
            v
   +-------------------+
   | Load Generator    |
   | (Virtual Users)   |
   | - Concurrent pools|
   | - Request queue   |
   +--------+----------+
            |
      +-----+-----+-----+-----+
      |     |     |     |     |
      v     v     v     v     v
   HTTP Requests to Target
            |
            v
   +-------------------+
   | Metrics Collector |
   | - Latency tracking|
   | - Success/failure |
   | - Response size   |
   +--------+----------+
            |
            v
   +-------------------+
   | Statistics Engine |
   | - Percentile calc |
   | - QPS calculation |
   +-------------------+
```

### 27.2 Load Generation

```java
public class StressTestConfig {
    private String targetUrl;
    private String method;
    private Map<String, String> headers;
    private String body;
    private int concurrentUsers;     // Virtual users
    private int totalRequests;       // Total requests to send
    private int targetQps;           // Optional QPS limit
    private int rampUpSeconds;       // Gradual load increase
}
```

### 27.3 Execution Flow

```
+------------------------------------------------------------------+
|                    LOAD GENERATION FLOW                           |
+------------------------------------------------------------------+

   Test Started
          |
          v
   +-------------------+
   | Create Executor   |
   | Pool (concurrent  |
   | users)            |
   +--------+----------+
            |
            | Ramp-up phase (if configured)
            v
   +-------------------+
   | Gradual User      |
   | Addition          |
   | - Start with 1    |
   | - Add users over  |
   |   rampUpSeconds   |
   +--------+----------+
            |
            | Steady state
            v
   +-------------------+
   | Request Dispatch  |
   | - Each user sends |
   |   requests        |
   | - Track results   |
   +--------+----------+
            |
            | Test completed
            v
   +-------------------+
   | Final Statistics  |
   | Computation       |
   +-------------------+
```

### 27.4 Metrics Collection

```
+------------------------------------------------------------------+
|                    METRICS COLLECTION                             |
+------------------------------------------------------------------+

   Per-Request Metrics:
   - startTime: Request start timestamp
   - endTime: Response received timestamp
   - latencyMs: endTime - startTime
   - statusCode: HTTP status code
   - success: statusCode in [200-299]
   - responseSize: Bytes received

   Aggregated Metrics:
   - minLatency, maxLatency, avgLatency
   - P50, P90, P95, P99 latencies (sorted array percentile)
   - requestsPerSecond: totalRequests / totalTimeSeconds
   - errorRate: failedRequests / totalRequests * 100
   - throughput: totalBytes / totalTimeSeconds
```

---

## 28. Summary

This API Gateway architecture demonstrates:

- **Clean separation** of control plane (admin) and data plane (gateway)
- **Extensible design** via SPI and Strategy patterns
- **High availability** with fallback caching and graceful degradation
- **Real-time configuration** with < 1 second propagation latency
- **Enterprise features** including multi-auth, circuit breaking, and rate limiting
- **Multi-service routing** with gray release support for canary deployments
- **SSL termination** with dynamic certificate loading and expiry monitoring
- **Kubernetes deployment** with one-click instance creation
- **Namespace isolation** for multi-tenancy support
- **Heartbeat monitoring** for real-time instance health tracking
- **Performance optimizations** including JWT cache, shadow quota, non-blocking locks
- **Comprehensive testing** with 561 tests ensuring reliability
- **Audit logging** for compliance and configuration rollback
- **System diagnostic** for proactive health monitoring
- **Traffic topology** for real-time traffic visualization
- **Filter chain analysis** for performance debugging
- **Request replay** for issue troubleshooting
- **AI Copilot** for intelligent configuration assistance
- **Stress testing** for performance validation

---

For feature documentation, see [FEATURES.md](FEATURES.md).
For quick start guide, see [QUICK_START.md](QUICK_START.md).