# Advanced Features

Implementation details for the gateway's three runtime plugins.

---

## A. Distributed Rate Limiting

**Files:** `ratelimiter/RedisRateLimiter.java`, `ratelimiter/RateLimiterGlobalFilter.java`

**Strategy:** Redis sliding-window (primary) + Sentinel QPS (fallback)

```
Request
  │
  ▼
Redis available? ──Yes──► Redis sliding window ──Allow──► next filter
       │                          │
       No                       Reject
       │                          │
       ▼                          ▼
  Sentinel QPS               HTTP 429
  ──Allow──► next filter
  ──Reject──► HTTP 429
```

**Core Redis logic (sliding window):**

```java
// RedisRateLimiter.tryAcquire()
long now = System.currentTimeMillis();
long windowStart = now - windowMs;

connection.zRemRangeByScore(key, 0, windowStart);       // evict old entries
Long count = connection.zCard(key);                      // count in window
if (count < limit) {
    connection.zAdd(key, now, String.valueOf(now));       // record request
    connection.expire(key, ttlSeconds);
    return true;   // allowed
}
return false;      // rejected → 429
```

**Config fields (`gateway-plugins.json`):**

| Field | Description |
|-------|-------------|
| `qps` | Max requests per `timeUnit` |
| `timeUnit` | `second` / `minute` / `hour` |
| `burstCapacity` | Max burst (Redis key TTL extends) |
| `keyType` | `ip` / `route` / `combined` / `header` |
| `keyPrefix` | Redis key prefix (default `rate_limit:`) |

Redis health check runs every 10 s; recovers after 5 s cooldown.

---

## B. IP Access Control

**File:** `filter/IPFilterGlobalFilter.java` (order -100)

Supports exact IP, wildcard (`192.168.1.*`), and CIDR (`192.168.1.0/24`) matching.

```java
// Core matching
private boolean matches(String ip, List<String> patterns) {
    for (String p : patterns) {
        if (p.contains("/")  && matchesCidr(ip, p)) return true;
        if (p.contains("*")  && ip.matches(p.replace(".", "\\.").replace("*", "\\d{1,3}"))) return true;
        if (p.equals(ip)) return true;
    }
    return false;
}
```

**Mode behavior:**

| `mode` | Allow if | Reject if |
|--------|----------|-----------|
| `whitelist` | IP matches list | IP not in list |
| `blacklist` | IP not in list | IP matches list |

Client IP is read from `X-Forwarded-For` header first, then `RemoteAddress`.  
Rejected → HTTP **403 Forbidden**.

---

## C. Per-route Timeout

**File:** `filter/TimeoutGlobalFilter.java` (order -200)

Writes timeout values into SCG route metadata. `NettyRoutingFilter` (order `Integer.MAX_VALUE`) reads them and applies at the Netty `HttpClient` level.

```java
metadata.put(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR,  config.getConnectTimeout());  // Integer ms
metadata.put(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, config.getResponseTimeout()); // Integer ms
// Rebuild Route with new metadata and write back
exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async()...metadata(metadata).build());
```

| Field | Scope | On expiry |
|-------|-------|-----------|
| `connectTimeout` | TCP handshake | HTTP 504 |
| `responseTimeout` | Request sent → full response | HTTP 504 |

> **Why not `Mono.timeout()`?** SCG's own `NettyRoutingFilter` already handles timeouts via route metadata. Using `Mono.timeout()` would bypass Netty and cause 500 errors instead of 504.

---

## D. Extending with a New Plugin

1. Add a config section to `gateway-plugins.json`
2. Add a parser block in `PluginConfigManager.parseConfig()`
3. Implement `GlobalFilter` + `Ordered`, inject `PluginConfigManager`
4. Annotate with `@Component` — Spring auto-registers it into the filter chain

Filter order reference:

| Filter | Order | Runs before |
|--------|-------|-------------|
| `TimeoutGlobalFilter` | -200 | All others |
| `IPFilterGlobalFilter` | -100 | Rate limiter |
| `RateLimiterGlobalFilter` | -50 | Routing |
| `StaticProtocolGlobalFilter` | 10001 | LB filter |
| `NacosLoadBalancerFilter` | 10150 | NettyRoutingFilter |
