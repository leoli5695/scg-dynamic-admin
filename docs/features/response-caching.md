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
│ no-cache?       │
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
  "ttl": 300,
  "maxSize": 1000,
  "enabled": true
}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `ttl` | Cache expiration time (seconds) | `300` |
| `maxSize` | Maximum number of cache entries | `1000` |

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
- Request URL (including query parameters)
- Vary Headers (if configured)

### Vary Headers

Configure headers to differentiate cache:

```json
{
  "varyHeaders": ["Accept", "Accept-Language"]
}
```

Requests with different `Accept` headers are cached separately.

---

## Cache Invalidation

### TTL Expiration

Automatic expiration:

```yaml
# Default 5 minutes
ttl: 300
```

### Manual Clear

Clear cache via API:

```bash
DELETE /api/cache/{routeId}
```

### Skip Cache

Requests with the following headers are not cached:
- `Cache-Control: no-cache`
- `Pragma: no-cache`
- `Authorization` (when present)

---

## Use Cases

| Scenario | TTL | MaxSize |
|----------|-----|---------|
| Static Resources | 3600 | 5000 |
| API Responses | 60 | 1000 |
| Configuration | 300 | 100 |
| User Data | 30 | 2000 |

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