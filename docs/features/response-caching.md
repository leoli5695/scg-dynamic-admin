# Response Caching

> Response caching uses Caffeine for in-memory caching to accelerate GET/HEAD requests.

---

## Overview

Response caching applies to GET/HEAD requests:

```
GET/HEAD Request
       ↓
┌─────────────────┐
│ Cache Check     │── HIT ──▶ Return cached (X-Cache: HIT)
└─────────────────┘
       │ MISS
       ↓
┌─────────────────┐
│ Execute Request │
└─────────────────┘
       ↓
┌─────────────────┐
│ 2xx Response?   │── No ──▶ Don't cache
└─────────────────┘
       │ Yes
       ↓
┌─────────────────┐
│ Cache-Control:  │── Yes ──▶ Don't cache
│ forbids cache?  │
└─────────────────┘
       │ No
       ↓
   Cache Response
```

---

## Configuration

Keys read from the strategy's `config` map by `CacheGlobalFilter` (`routeId` and `enabled` are
strategy-level fields, see [API Endpoints](#api-endpoints)):

```json
{
  "routeId": "static-api",
  "ttlSeconds": 300,
  "maxSize": 1000,
  "enabled": true
}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `ttlSeconds` | Cache expiration time (seconds) | `60` |
| `maxSize` | Maximum number of cache entries | `10000` |
| `maxCacheBytes` | Per-route estimated retained bytes (body, key and headers); new entries are skipped when full | `67108864` (64 MiB) |

The instance-wide estimated retained-byte budget is 256 MiB by default (`gateway.response-cache.max-bytes` / `GATEWAY_RESPONSE_CACHE_MAX_BYTES`). Both byte budgets and the entry-count limit apply. Expiration, eviction and strategy invalidation release the reservation. These budgets cover retained entries, not all JVM or in-flight response memory.

---

## Cache Headers

| Header | Description |
|--------|-------------|
| `X-Cache` | `HIT` or `MISS` |
| `Age` | Cache age (seconds) |

Response Headers:
```
X-Cache: HIT
Age: 120
```

---

## Cache Key

Cache key consists of:
- Route ID
- Raw request path and query parameters (percent escapes remain distinct)
- Vary Headers (if configured)

### Vary Headers

Configure headers to differentiate cache:

```json
{
  "varyHeaders": ["Accept", "Accept-Language"]
}
```

Requests with different `Accept` headers are cached separately.

Host and the three CORS headers (`Origin`, `Access-Control-Request-Method`, `Access-Control-Request-Headers`) are always part of the key. Other upstream `Vary` headers must be included in `varyHeaders` for the response to be cached. `Vary: *` is never cached.

---

## Cache Invalidation

### TTL Expiration

Automatic expiration:

```yaml
# Default 60 seconds
ttlSeconds: 60
```

### Manual Clear

There is **no cache-clear HTTP endpoint**: `DELETE /api/cache/{routeId}` does not exist in
`gateway-admin` or `my-gateway` (no `CacheController`, and no `api/cache` route anywhere).
A per-route cache entry can only be dropped by TTL expiry/eviction, or by invalidating the
route's `CACHE` strategy. That strategy-driven path is real: `CacheGlobalFilter.initCacheInvalidation`
registers a `StrategyManager.StrategyChangeListener`, and `invalidateIfCache` removes the route's
Caffeine instance whenever a `CACHE` (or `CORS`) strategy for that route changes or is removed,
and drops every route cache when the strategy set is cleared. So updating, disabling or deleting
the route's `CACHE` strategy through the Strategy API forces a rebuild with the new
`ttlSeconds` / `maxSize` / `maxCacheBytes`.

The originally documented on-demand "clear cache via API" endpoint is **not implemented**.

### Skip Cache

Requests with the following headers are not cached:
- `Cache-Control: no-cache` or `no-store`
- `Pragma: no-cache`
- `Authorization`, `Cookie`, API key or HMAC access key headers

Requests with a verified authentication identity also bypass the shared cache, even if the original credential header has been removed. Personalized responses are not stored in this cache. Responses declaring `private`, `no-store` or `no-cache`, or setting cookies, are not stored. A response is cached only after its body finishes successfully; cancelled or failed streams are discarded.

---

## Use Cases

| Scenario | TTL | MaxSize |
|----------|-----|---------|
| Static Resources | 3600 | 5000 |
| API Responses | 60 | 1000 |
| Configuration | 300 | 100 |
| Public Reference Data | 30 | 2000 |

---

## API Endpoints

Configure via the Strategy API. There is **no `/api/strategies/cache` path**: `StrategyController`
is `@RequestMapping("/api/strategies")` and identifies a strategy by its `strategyId`
(`PUT /{id}`, `GET /{id}`, `DELETE /{id}`), with `GET /type/{type}` for lookup by type.
The cache type constant is `StrategyDefinition.TYPE_CACHE = "CACHE"`.
`POST`/`PUT`/`DELETE` under `/api/**` require the `ADMIN` authority (`SecurityConfig`), so every
example below must carry a JWT.

Create:

```bash
curl -X POST http://localhost:9090/api/strategies \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "strategyId": "static-api-cache",
    "strategyName": "Static API cache",
    "strategyType": "CACHE",
    "scope": "ROUTE",
    "routeId": "static-api",
    "enabled": true,
    "config": {
      "ttlSeconds": 300,
      "maxSize": 1000,
      "varyHeaders": ["Accept", "Accept-Language"]
    }
  }'
```

Update (`{strategyId}`, not a type name). The config key is `ttlSeconds`, not `ttl`
(`CacheGlobalFilter` reads `ttlSeconds`, `maxSize`, `maxCacheBytes` and `varyHeaders`):

```bash
curl -X PUT http://localhost:9090/api/strategies/static-api-cache \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "strategyId": "static-api-cache",
    "strategyName": "Static API cache",
    "strategyType": "CACHE",
    "scope": "ROUTE",
    "routeId": "static-api",
    "enabled": true,
    "config": {
      "ttlSeconds": 300,
      "maxSize": 1000
    }
  }'
```

Other endpoints: `GET /api/strategies`, `GET /api/strategies/{id}`,
`GET /api/strategies/type/CACHE`, `GET /api/strategies/global`,
`GET /api/strategies/route/{routeId}`, `DELETE /api/strategies/{id}`,
`POST /api/strategies/{id}/enable`, `POST /api/strategies/{id}/disable`.

Clearing a route's cache: no endpoint exists. Disable or delete the `CACHE` strategy
(`POST /api/strategies/{id}/disable`, `DELETE /api/strategies/{id}`) to invalidate that route's
cache instance — see [Manual Clear](#manual-clear).

---

## Best Practices

1. **Appropriate TTL**: Set based on data update frequency
2. **Cache Size**: Set based on memory constraints
3. **Vary Headers**: Differentiate cache for different clients
4. **Monitor Hit Rate**: Optimize caching strategy
5. **Proactive Invalidation**: Update/disable the route's `CACHE` strategy when data is updated — there is no cache-clear endpoint

---

## Related Features

- [Response Transform](response-transform.md) - Cache transformed responses
- [Monitoring & Alerts](monitoring-alerts.md) - Cache monitoring
