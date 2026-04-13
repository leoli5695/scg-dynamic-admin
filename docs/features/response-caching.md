# Response Caching

> 响应缓存功能基于 Caffeine 实现内存缓存，加速 GET/HEAD 请求。

---

## Overview

响应缓存对 GET/HEAD 请求生效：

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
| `ttl` | 缓存过期时间 (秒) | `300` |
| `maxSize` | 最大缓存条目数 | `1000` |

---

## Cache Headers

| Header | Description |
|--------|-------------|
| `X-Cache` | `HIT` 或 `MISS` |
| `Age` | 缓存时间（秒） |

Response Headers:
```
X-Cache: HIT
Age: 120
```

---

## Cache Key

缓存 Key 由以下组成：
- Route ID
- 请求 URL（含 Query 参数）
- Vary Headers（如配置）

### Vary Headers

配置区分缓存的 Header：

```json
{
  "varyHeaders": ["Accept", "Accept-Language"]
}
```

不同 `Accept` Header 的请求缓存分开存储。

---

## Cache Invalidation

### TTL Expiration

自动过期：

```yaml
# Default 5 minutes
ttl: 300
```

### Manual Clear

通过 API 清除缓存：

```bash
DELETE /api/cache/{routeId}
```

### Skip Cache

请求携带以下 Header 不缓存：
- `Cache-Control: no-cache`
- `Pragma: no-cache`
- `Authorization` (存在时)

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

通过 Strategy API 配置：

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

清除缓存：
```bash
curl -X DELETE http://localhost:9090/api/cache/static-api
```

---

## Best Practices

1. **合理 TTL**：根据数据更新频率设置
2. **缓存大小**：根据内存限制设置
3. **Vary Headers**：区分不同客户端缓存
4. **监控命中率**：优化缓存策略
5. **主动失效**：数据更新时清除缓存

---

## Related Features

- [Response Transform](response-transform.md) - 缓存转换后响应
- [Monitoring & Alerts](monitoring-alerts.md) - 缓存监控