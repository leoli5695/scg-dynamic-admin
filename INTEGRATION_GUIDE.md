# Integration Guide

Technical reference for the Spring Cloud Gateway Dynamic Management System.

---

## Architecture Overview

```
┌──────────────────────────────────────────────┐
│              Gateway Admin (8080)            │
│  RouteController / ServiceController /       │
│  PluginController  →  NacosConfigManager     │
└──────────────────────┬───────────────────────┘
                       │ publish JSON config
                       ▼
              ┌─────────────────┐
              │   Nacos (8848)  │
              │  routes.json    │
              │  services.json  │
              │  plugins.json   │
              └────────┬────────┘
          listen+notify│
                       ▼
┌──────────────────────────────────────────────┐
│                My-Gateway (80)               │
│                                              │
│  NacosRouteDefinitionLocator                 │
│    └─ Nacos listener → RefreshRoutesEvent    │
│                                              │
│  Global Filter Chain (by order):             │
│    TimeoutGlobalFilter        (-200)         │
│    IPFilterGlobalFilter       (-100)         │
│    RateLimiterGlobalFilter     (-50)         │
│    StaticProtocolGlobalFilter (10001)        │
│    NacosLoadBalancerFilter    (10150)        │
└──────────────────────┬───────────────────────┘
                       │ HTTP
                       ▼
              ┌─────────────────┐
              │  Backend Service │
              └─────────────────┘
```

---

## Config Data IDs (Nacos, DEFAULT_GROUP)

| Data ID | Description |
|---------|-------------|
| `gateway-routes.json` | Route definitions |
| `gateway-services.json` | Static service instances (for `static://`) |
| `gateway-plugins.json` | Plugin config: rate limiter, IP filter, timeout |

---

## 1. Dynamic Route Loading

**Class:** `NacosRouteDefinitionLocator`

- Implements SCG's `RouteDefinitionLocator`
- Caches routes (TTL 10 s), reloads on cache miss or Nacos change
- On config change: clears cache → publishes `RefreshRoutesEvent` → SCG rebuilds route table immediately

```java
// Nacos listener → immediate SCG refresh
configService.addListener(dataId, "DEFAULT_GROUP", new Listener() {
    public void receiveConfigInfo(String configInfo) {
        cachedRoutes = Collections.emptyList();
        lastLoadTime = 0;
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
    }
});
```

**Supported JSON formats for `gateway-routes.json`:**

```json
{
  "version": "1.0",
  "routes": [
    {
      "id": "demo-route",
      "uri": "static://demo-service",
      "predicates": [{"name": "Path", "args": {"pattern": "/api/**"}}],
      "filters": [{"name": "StripPrefix", "args": {"parts": "1"}}]
    }
  ]
}
```

---

## 2. static:// Protocol (`StaticProtocolGlobalFilter`, order 10001)

Resolves `static://<service-name>` URIs to real HTTP addresses using `gateway-services.json`.

**Flow:**
1. Read `routeUri.getScheme()` — if `static`, look up service in config
2. Filter healthy + enabled instances
3. Select instance by load balancing strategy
4. Overwrite `GATEWAY_REQUEST_URL_ATTR` with `http://<ip>:<port>/<path>`

**Load balancing strategies:**

| Value | Algorithm |
|-------|-----------|
| `round-robin` (default) | Timestamp-based modulo |
| `random` | `Random.nextInt()` |
| `weighted` / `weight` | Deterministic weighted round-robin via `AtomicLong` counter + expanded slot list |

```java
// Weighted round-robin core
List<ServiceInstanceConfig> expanded = new ArrayList<>();
for (ServiceInstanceConfig inst : instances) {
    int w = (int) Math.max(1, inst.getWeight());
    for (int i = 0; i < w; i++) expanded.add(inst);
}
long idx = weightedCounter.getAndIncrement() % expanded.size();
return expanded.get((int) idx);
```

Cache TTL: 10 s. Nacos listener clears cache immediately on config update/delete.

---

## 3. Nacos Discovery Load Balancing (`NacosLoadBalancerFilter`, order 10150)

Handles `lb://<service-name>` URIs using Nacos naming service.

- Queries healthy + enabled instances from Nacos
- Delegates to SCG's `ReactorServiceInstanceLoadBalancer` if configured, else falls back to weighted round-robin
- Reconstructs URI: `lb://svc` → `http://ip:port/path`

---

## 4. Plugin System

### PluginConfigManager

Central in-memory store for all plugin configs. Updated by `NacosPluginConfigListener` when `gateway-plugins.json` changes. Uses `AtomicReference<PluginConfig>` for thread safety.

```java
// Filters query configs like this:
RateLimiterConfig cfg = pluginConfigManager.getRateLimiterForRoute(routeId);
TimeoutConfig tc      = pluginConfigManager.getTimeoutForRoute(routeId);
Map<?,?> ipCfg       = pluginConfigManager.getIPFilterForRoute(routeId);
```

### gateway-plugins.json structure

```json
{
  "version": "1.0",
  "plugins": {
    "rateLimiters": [
      { "routeId": "demo-route", "qps": 100, "burstCapacity": 200,
        "timeUnit": "second", "keyType": "ip", "enabled": true }
    ],
    "ipFilters": [
      { "routeId": "demo-route", "mode": "whitelist",
        "ipList": ["192.168.1.0/24", "127.0.0.1"], "enabled": true }
    ],
    "timeouts": [
      { "routeId": "demo-route", "connectTimeout": 3000,
        "responseTimeout": 5000, "enabled": true }
    ]
  }
}
```

---

## 5. Per-route Timeout (`TimeoutGlobalFilter`, order -200)

Injects timeout values into SCG route metadata before `NettyRoutingFilter` runs.

```java
Map<String, Object> metadata = new HashMap<>(route.getMetadata());
metadata.put(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR,  config.getConnectTimeout());  // Integer ms
metadata.put(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, config.getResponseTimeout()); // Integer ms
// Rebuild Route and write back to exchange
Route newRoute = Route.async().id(...).metadata(metadata).build();
exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, newRoute);
```

> `NettyRoutingFilter` reads these keys via `getLong()` and applies them at the Netty `HttpClient` level.  
> On timeout → HTTP **504 Gateway Timeout**.

---

## 6. IP Access Control (`IPFilterGlobalFilter`, order -100)

Reads `mode` (`whitelist` | `blacklist`) and `ipList` from `PluginConfigManager`.  
Supports exact IP and CIDR (`192.168.1.0/24`) matching.  
Rejected requests → HTTP **403 Forbidden**.

---

## 7. Rate Limiting (`RateLimiterGlobalFilter`, order -50)

Dual-backend strategy:

1. **Redis** (primary) — sliding window, distributed across gateway instances
2. **Sentinel** (fallback) — activates automatically when Redis is unavailable (5 s cooldown)

Rate limit key types: `ip` | `route` | `combined` | `header`.  
Rejected requests → HTTP **429 Too Many Requests**.

---

## 8. Real-time Update Summary

| Config changed | Propagation path | Latency |
|----------------|-----------------|---------|
| Route add/update/delete | Nacos → `NacosRouteDefinitionLocator` listener → `RefreshRoutesEvent` | < 1 s |
| Service add/update/delete | Nacos → `StaticProtocolGlobalFilter` listener → cache cleared | < 1 s |
| Plugin add/update/delete | Nacos → `NacosPluginConfigListener` → `PluginConfigManager.updateConfig()` | < 1 s |

---

## 9. Adding a Custom Plugin

1. Add config section to `gateway-plugins.json`
2. Parse it in `PluginConfigManager.parseConfig()`
3. Implement a `GlobalFilter` that reads config via `PluginConfigManager`
4. Register as `@Component` with appropriate `@Order`

```java
@Component
public class MyPlugin implements GlobalFilter, Ordered {
    @Autowired PluginConfigManager configManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = getRouteId(exchange);
        // query config, apply logic
        return chain.filter(exchange);
    }

    @Override public int getOrder() { return -30; }
}
```

---

For questions or contributions: https://github.com/leoli5695/scg-dynamic-admin-demo
