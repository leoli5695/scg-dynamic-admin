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

Clear cache via API:

```bash
DELETE /api/cache/{routeId}
```

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

Configure via Strategy API:

```bash
curl -X PUT http://localhost:9090/api/strategies/cache \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "static-api",
    "ttl": 300,
    "maxSize": 1000,
    "enabled": true
  }'
```

Clear cache:
```bash
curl -X DELETE http://localhost:9090/api/cache/static-api
```

---

## Best Practices

1. **Appropriate TTL**: Set based on data update frequency
2. **Cache Size**: Set based on memory constraints
3. **Vary Headers**: Differentiate cache for different clients
4. **Monitor Hit Rate**: Optimize caching strategy
5. **Proactive Invalidation**: Clear cache when data is updated

---

## Related Features

- [Response Transform](response-transform.md) - Cache transformed responses
- [Monitoring & Alerts](monitoring-alerts.md) - Cache monitoring
