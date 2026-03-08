# Spring Cloud Gateway Integration Guide

## Project Overview

This project demonstrates how to build a **dynamic API gateway management system** by extending Spring Cloud Gateway with a custom admin console. The system consists of two core modules:

1. **gateway-admin** - Web-based management console for visual configuration
2. **my-gateway** - Modified Spring Cloud Gateway with dynamic routing and plugin support

---

## 1. Gateway Admin Console

### Purpose
Provides a web UI and REST API to manage gateway configurations (routes, services, plugins) without editing YAML files manually.

### Key Components

#### A. NacosConfigManager
**Location:** `gateway-admin/src/main/java/com/example/gatewayadmin/config/NacosConfigManager.java`

**Responsibilities:**
- Manages Nacos ConfigService connection
- Pushes configuration updates to Nacos server
- Listens for configuration changes

**Configuration Data IDs:**
- `gateway-routes.json` - Route definitions
- `gateway-services.json` - Service instance configurations
- `gateway-plugins.json` - Plugin configurations

```java
// Example: Push route configuration to Nacos
public void updateRoutes(String routesJson) {
    configService.publishConfig(
        "gateway-routes.json", 
        "DEFAULT_GROUP", 
        routesJson
    );
}
```

#### B. REST Controllers

**RouteController** (`/api/routes`)
- `POST /api/routes` - Create new route
- `DELETE /api/routes/{routeId}` - Delete route
- `GET /api/routes` - List all routes

**ServiceController** (`/api/services`)
- `POST /api/services` - Create service with instances
- `DELETE /api/services/{name}` - Delete service
- `GET /api/services/nacos-discovery` - Get services from Nacos

**PluginController** (`/api/plugins`)
- `POST /api/plugins/rate-limiter` - Configure rate limiter
- `POST /api/plugins/custom-header` - Configure custom headers

#### C. Data Models

**RouteDefinition** - Mirrors Spring Cloud Gateway's route structure:
```java
{
  "id": "user-route",
  "uri": "lb://user-service",
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/users/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ]
}
```

**ServiceDefinition** - Custom service model:
```java
{
  "name": "user-service",
  "loadBalancer": "round-robin",
  "instances": [
    {"ip": "127.0.0.1", "port": 8081, "weight": 1.0}
  ]
}
```

### How It Works

1. User creates route/service/plugin via web UI
2. Admin console validates and formats configuration
3. Configuration is pushed to Nacos as JSON
4. My-Gateway listens for changes and applies them in real-time

---

## 2. My-Gateway (Modified Spring Cloud Gateway)

### Core Modifications

To enable dynamic configuration, we modified Spring Cloud Gateway at three key points:

#### A. Dynamic Route Loading

**Component:** `NacosRouteDefinitionLocator`

**Location:** `my-gateway/src/main/java/com/example/mygateway/route/NacosRouteDefinitionLocator.java`

**What it does:**
- Replaces default route definition locator
- Listens to `gateway-routes.json` in Nacos
- Parses JSON and converts to `RouteDefinition` objects
- Caches routes with TTL (10 seconds)
- Automatically refreshes when configuration changes

**Implementation:**
```java
@Override
public Flux<RouteDefinition> getRouteDefinitions() {
    // Check cache first
    if (!cachedRoutes.isEmpty() && !cacheExpired()) {
        return Flux.fromIterable(cachedRoutes);
    }
    
    // Load from Nacos
    String content = configService.getConfig("gateway-routes.json", "DEFAULT_GROUP", 5000);
    
    // Parse JSON (supports multiple formats)
    List<RouteDefinition> routes = parseRoutes(content);
    
    // Update cache
    cachedRoutes = routes;
    
    return Flux.fromIterable(routes);
}
```

**Supported JSON Formats:**
1. Wrapped array: `{"version": "1.0", "routes": [...]}`
2. Direct array: `[...]`
3. Map format: `{routeId: {...}}`

#### B. Custom Global Filters

##### 1. NacosLoadBalancerFilter

**Order:** 10150 (replaces ReactiveLoadBalancerClientFilter)

**Location:** `my-gateway/src/main/java/com/example/mygateway/filter/NacosLoadBalancerFilter.java`

**Purpose:**
- Integrates directly with Nacos service discovery
- Selects service instances based on load balancing strategy
- Supports weighted round-robin, random, and least connections

**How it works:**
```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    URI url = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
    
    // Only process lb:// protocol
    if ("lb".equals(url.getScheme())) {
        String serviceId = url.getHost();
        
        // Get healthy instances from Nacos
        List<Instance> instances = nacosNamingService.getAllInstances(serviceId);
        
        // Filter healthy and enabled instances
        List<Instance> healthyInstances = instances.stream()
            .filter(Instance::isHealthy)
            .filter(Instance::isEnabled)
            .collect(Collectors.toList());
        
        // Select instance using load balancer
        Instance selected = selectInstance(healthyInstances, strategy);
        
        // Reconstruct URI: lb://user-service -> http://127.0.0.1:8081
        URI finalUri = new URI("http://" + selected.getIp() + ":" + selected.getPort());
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, finalUri);
    }
    
    return chain.filter(exchange);
}
```

**Load Balancing Strategies:**
- **Round Robin** - Distributes requests evenly
- **Weighted Round Robin** - Considers instance weights from Nacos
- **Random** - Random selection
- **Least Connections** - Routes to instance with fewest active connections

##### 2. StaticProtocolGlobalFilter

**Order:** 10001

**Location:** `my-gateway/src/main/java/com/example/mygateway/filter/StaticProtocolGlobalFilter.java`

**Purpose:**
- Handles `static://` protocol for fixed node routing
- Reads service instances from `gateway-services.json`
- Useful for services not registered in Nacos

**How it works:**
```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    Object routeObj = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    URI routeUri = routeObj.getUri();
    
    // Intercept static:// protocol
    if ("static".equalsIgnoreCase(routeUri.getScheme())) {
        // Resolve from gateway-services.json
        URI resolvedUri = resolveFromGatewayServices(routeUri.getHost());
        
        if (resolvedUri != null) {
            // Reconstruct final HTTP URI
            URI finalUri = new URI(
                "http",
                null,
                resolvedUri.getHost(),
                resolvedUri.getPort(),
                exchange.getRequest().getPath().value(),
                exchange.getRequest().getQuery(),
                null
            );
            
            exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, finalUri);
        }
    }
    
    return chain.filter(exchange);
}
```

**Configuration Example (gateway-services.json):**
```json
{
  "services": [
    {
      "name": "legacy-service",
      "loadBalancer": "round-robin",
      "instances": [
        {"ip": "192.168.1.100", "port": 8080, "weight": 1.0}
      ]
    }
  ]
}
```

##### 3. DynamicCustomHeaderGlobalFilter

**Order:** 10250 (after load balancer)

**Location:** `my-gateway/src/main/java/com/example/mygateway/filter/DynamicCustomHeaderGlobalFilter.java`

**Purpose:**
- Adds custom headers based on plugin configuration
- Supports variable placeholders
- Executes after route is determined

**How it works:**
```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String routeId = getRouteId(exchange);
    
    // Check if route has custom header config
    if (!pluginConfigManager.hasCustomHeaders(routeId)) {
        return chain.filter(exchange);
    }
    
    // Get headers from config
    Map<String, String> customHeaders = pluginConfigManager.getCustomHeadersForRoute(routeId);
    
    // Add headers to request
    ServerHttpRequest request = exchange.getRequest();
    ServerHttpRequest.Builder builder = request.mutate();
    
    for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
        String headerName = entry.getKey();
        String headerValue = resolveHeaderValue(entry.getValue(), exchange);
        builder.header(headerName, headerValue);
    }
    
    return chain.filter(exchange.mutate().request(builder.build()).build());
}
```

**Variable Resolution:**
```java
private String resolveHeaderValue(String value, ServerWebExchange exchange) {
    // Support ${random.uuid}
    if (value.contains("${random.uuid}")) {
        value = value.replace("${random.uuid}", UUID.randomUUID().toString());
    }
    
    // Support ${client.ip}
    if (value.contains("${client.ip}")) {
        String ip = exchange.getRequest().getRemoteAddress()
            .getAddress().getHostAddress();
        value = value.replace("${client.ip}", ip);
    }
    
    // Support ${request.path}
    if (value.contains("${request.path}")) {
        value = value.replace("${request.path}", exchange.getRequest().getPath().value());
    }
    
    return value;
}
```

#### C. Plugin System

##### PluginConfigManager

**Location:** `my-gateway/src/main/java/com/example/mygateway/plugin/PluginConfigManager.java`

**Responsibilities:**
- Listens to `gateway-plugins.json` in Nacos
- Parses plugin configurations
- Provides API for filters to query plugin settings

**Configuration Structure:**
```json
{
  "plugins": {
    "customHeaders": [
      {
        "routeId": "user-route",
        "enabled": true,
        "headers": {
          "X-Request-Id": "${random.uuid}",
          "X-Forwarded-For": "${client.ip}"
        }
      }
    ],
    "rateLimiters": [
      {
        "routeId": "api-route",
        "qps": 100,
        "burstCapacity": 200
      }
    ]
  }
}
```

##### NacosPluginConfigListener

**Location:** `my-gateway/src/main/java/com/example/mygateway/config/NacosPluginConfigListener.java`

**Purpose:**
- Initializes Nacos ConfigService
- Adds listener for `gateway-plugins.json`
- Triggers PluginConfigManager.updateConfig() on changes

```java
@EventListener(ApplicationReadyEvent.class)
public void init() {
    ConfigService configService = NacosFactory.createConfigService(props);
    
    configService.addListener("gateway-plugins.json", "DEFAULT_GROUP", new Listener() {
        @Override
        public void receiveConfigInfo(String configInfo) {
            pluginConfigManager.updateConfig(configInfo);
        }
    });
}
```

---

## 3. Integration Architecture

### Complete Request Flow

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────────┐
│         My-Gateway (Port 80)            │
│                                         │
│  ┌──────────────────────────────────┐  │
│  │  NacosRouteDefinitionLocator     │  │◄─── gateway-routes.json
│  └──────────────────────────────────┘  │
│                                         │
│  ┌──────────────────────────────────┐  │
│  │  StaticProtocolGlobalFilter      │  │◄─── gateway-services.json
│  │  (Order: 10001)                  │  │
│  └──────────────────────────────────┘  │
│                                         │
│  ┌──────────────────────────────────┐  │
│  │  NacosLoadBalancerFilter         │  │◄─── Nacos Discovery
│  │  (Order: 10150)                  │  │
│  └──────────────────────────────────┘  │
│                                         │
│  ┌──────────────────────────────────┐  │
│  │  DynamicCustomHeaderGlobalFilter │  │◄─── gateway-plugins.json
│  │  (Order: 10250)                  │  │
│  └──────────────────────────────────┘  │
└──────────────┬──────────────────────────┘
               │
               ▼
        ┌─────────────┐
        │Backend Svc  │
        └─────────────┘
```

### Configuration Flow

```
┌──────────────────┐
│  Gateway Admin   │
│   (Port 8081)    │
└────────┬─────────┘
         │ REST API
         ▼
┌──────────────────┐
│  NacosConfigMgr  │
└────────┬─────────┘
         │ Publish Config
         ▼
┌──────────────────┐
│  Nacos Server    │
│  (Port 8848)     │
│  ──────────────  │
│  - gateway-      │
│    routes.json   │
│  - gateway-      │
│    services.json │
│  - gateway-      │
│    plugins.json  │
└────────┬─────────┘
         │ Listen & Notify
         ▼
┌──────────────────┐
│   My-Gateway     │
│  ──────────────  │
│  - Route Locator │
│  - Plugin Mgr    │
│  - LB Filter     │
└──────────────────┘
```

---

## 4. Key Implementation Details

### A. Filter Order Strategy

Filters must execute in correct order:

1. **StaticProtocolGlobalFilter (10001)** - Resolves static:// URIs early
2. **NacosLoadBalancerFilter (10150)** - Replaces default LB filter
3. **DynamicCustomHeaderGlobalFilter (10250)** - Runs after route is confirmed

**Why this order?**
- Static protocol resolution happens before load balancing
- Load balancer selects instance before adding headers
- Custom headers are added last (may include instance info)

### B. Cache Strategy

Both routes and services use caching to reduce Nacos load:

```java
private static final long CACHE_TTL_MS = 10000; // 10 seconds

// Check cache before loading from Nacos
if (cachedRoutes != null && (now - lastLoadTime) < CACHE_TTL_MS) {
    return cachedRoutes;
}

// Load from Nacos and update cache
cachedRoutes = loadFromNacos();
lastLoadTime = now;
```

**Benefits:**
- Reduces Nacos RPC calls
- Improves response time
- Prevents cascading failures during Nacos downtime

### C. Hot Update Mechanism

Configuration changes take effect immediately without restart:

1. User updates config via Admin UI
2. Admin publishes new config to Nacos
3. Nacos notifies registered listeners
4. My-Gateway receives notification
5. Cache is cleared or updated
6. Next request uses new configuration

**Example:**
```java
configService.addListener("gateway-routes.json", "DEFAULT_GROUP", new Listener() {
    @Override
    public void receiveConfigInfo(String configInfo) {
        log.info("Route config updated");
        // Clear cache to force reload on next request
        cachedRoutes = Collections.emptyList();
        lastLoadTime = 0;
    }
});
```

### D. Load Balancing Implementation

**Weighted Round-Robin:**
```java
private Instance selectByWeightedRoundRobin(List<Instance> instances) {
    // Calculate total weight
    double totalWeight = instances.stream()
        .mapToDouble(Instance::getWeight)
        .sum();
    
    // Random selection based on weight proportion
    double random = Math.random() * totalWeight;
    double weightSum = 0;
    
    for (Instance instance : instances) {
        weightSum += instance.getWeight();
        if (random <= weightSum) {
            return instance;
        }
    }
    
    return instances.get(instances.size() - 1);
}
```

---

## 5. Plugin Development Guide

### Creating a Custom Plugin

**Step 1: Define Plugin Configuration**

Add to `gateway-plugins.json`:
```json
{
  "plugins": {
    "yourPlugin": [
      {
        "routeId": "your-route",
        "enabled": true,
        "config": {
          "key1": "value1"
        }
      }
    ]
  }
}
```

**Step 2: Create Plugin Manager**

Extend `PluginConfigManager` to parse your config:
```java
public Map<String, String> getYourPluginConfig(String routeId) {
    // Query plugin config for specific route
    return pluginConfig.getYourPlugin().stream()
        .filter(p -> p.getRouteId().equals(routeId))
        .findFirst()
        .map(YourPlugin::getConfig)
        .orElse(Collections.emptyMap());
}
```

**Step 3: Implement GlobalFilter**

```java
@Component
@Slf4j
public class YourCustomFilter implements GlobalFilter, Ordered {
    
    private final PluginConfigManager configManager;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = getRouteId(exchange);
        
        // Get plugin config
        Map<String, String> config = configManager.getYourPluginConfig(routeId);
        
        if (config.isEmpty()) {
            return chain.filter(exchange);
        }
        
        // Apply plugin logic
        // ...
        
        return chain.filter(exchange);
    }
    
    @Override
    public int getOrder() {
        return 10300; // Choose appropriate order
    }
}
```

---

## 6. Deployment Notes

### Prerequisites

- JDK 17+
- Nacos 2.4.3 (standalone for demo, cluster for production)
- Redis 6.0+ (for rate limiter plugin)
- Maven 3.8+

### Configuration Files

**application.yml (My-Gateway):**
```yaml
spring:
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        namespace: public
        file-extension: json
    gateway:
      discovery:
        locator:
          enabled: false  # Disable default locator
```

**bootstrap.yml (Gateway-Admin):**
```yaml
spring:
  application:
    name: gateway-admin
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        namespace: public
```

### Startup Sequence

1. Start Nacos Server
2. Start Redis (optional, for rate limiting)
3. Start Gateway-Admin (Port 8081)
4. Start My-Gateway (Port 80)
5. Access Admin Console: http://localhost:8081

---

## 7. Troubleshooting

### Issue: Routes Not Loading

**Symptoms:** Requests return 404, routes not appearing in logs

**Solution:**
1. Check Nacos configuration exists
2. Verify Nacos server address in application.yml
3. Check My-Gateway logs for parsing errors
4. Ensure JSON format is valid

### Issue: Load Balancer Not Working

**Symptoms:** `Unable to find instance for service` error

**Solution:**
1. Verify service is registered in Nacos
2. Check service instances are healthy
3. Confirm Nacos namespace matches
4. Review NacosLoadBalancerFilter logs

### Issue: Plugins Not Applying

**Symptoms:** Custom headers not added, rate limiting not working

**Solution:**
1. Check `gateway-plugins.json` exists in Nacos
2. Verify plugin is enabled for route
3. Confirm filter order is correct
4. Check PluginConfigManager logs

---

## 8. Future Enhancements

Potential improvements for production use:

1. **Authentication & Authorization**
   - JWT/OAuth2 integration
   - RBAC for admin console
   - API access control

2. **Advanced Rate Limiting**
   - Distributed Redis rate limiting
   - Sentinel integration
   - Adaptive rate limiting

3. **Monitoring & Observability**
   - Prometheus metrics
   - Distributed tracing (Sleuth/Zipkin)
   - Grafana dashboards

4. **High Availability**
   - Gateway cluster deployment
   - Nacos cluster
   - Redis cluster

5. **Advanced Routing**
   - Canary releases
   - A/B testing
   - Traffic mirroring

---

## Conclusion

This project demonstrates how to extend Spring Cloud Gateway with:
- ✅ Dynamic route loading from Nacos
- ✅ Visual management console
- ✅ Custom load balancing strategies
- ✅ Plugin architecture
- ✅ Real-time configuration updates

The codebase serves as a foundation for building enterprise-grade API gateways while maintaining simplicity for learning and prototyping.

For questions or contributions, visit: https://github.com/leoli5695/scg-dynamic-admin-demo
