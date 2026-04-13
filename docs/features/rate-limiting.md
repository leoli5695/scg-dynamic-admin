# Rate Limiting

> Gateway 提供分布式限流功能，支持 Redis + 本地混合模式，优雅降级。

---

## Overview

Gateway 使用混合限流架构：
- **Redis 限流**：分布式场景，多实例共享计数
- **本地限流**：Redis 故障时自动降级

```
┌─────────────────────────────────────────────┐
│         HYBRID RATE LIMITER                  │
│                                              │
│   Request arrives                            │
│         │                                    │
│         ▼                                    │
│   ┌─────────────────┐                        │
│   │ Redis Available?│                        │
│   └────────┬────────┘                        │
│            │                                 │
│       ┌────┴────┐                            │
│       │         │                            │
│      Yes       No                            │
│       │         │                            │
│       ▼         ▼                            │
│   ┌─────────┐ ┌─────────┐                    │
│   │  Redis  │ │  Local  │                    │
│   │  Limit  │ │  Limit  │                    │
│   │         │ │(Shadow) │                    │
│   └─────────┘ └─────────┘                    │
│       │         │                            │
│       └────┬────┘                            │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │   Allowed?      │                        │
│   └────────┬────────┘                        │
│            │                                 │
│       ┌────┴────┐                            │
│       │         │                            │
│      Yes       No                            │
│       │         │                            │
│       ▼         ▼                            │
│   Continue    429                            │
│   Request     Rejected                       │
└─────────────────────────────────────────────┘
```

---

## Configuration

### Basic Rate Limiting

```json
{
  "routeId": "public-api",
  "qps": 100,
  "burstCapacity": 200,
  "keyType": "ip",
  "enabled": true
}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `qps` | 每秒请求数限制 | - |
| `burstCapacity` | 突发容量 | `2 * qps` |
| `keyType` | 限流 Key 类型 | `ip` |
| `enabled` | 是否启用 | `true` |

### Key Types

| Key Type | Description | Use Case |
|----------|-------------|----------|
| `ip` | 按客户端 IP | 防单 IP 滥用 |
| `route` | 按路由共享 | 全局保护 |
| `combined` | 路由 + IP 组合 | 精细控制 |
| `header` | 按 Header 值 | 按用户/租户限流 |
| `user` | 按用户 ID | 认证后限流 |

---

## Multi-Dimensional Rate Limiting

支持层级限流：全局 → 租户 → 用户 → IP

### Configuration

```json
{
  "routeId": "tenant-api",
  "enabled": true,
  "dimensions": [
    {
      "type": "GLOBAL",
      "qps": 10000,
      "burstCapacity": 20000
    },
    {
      "type": "TENANT",
      "keySource": "header:X-Tenant-Id",
      "qps": 1000,
      "burstCapacity": 2000
    },
    {
      "type": "USER",
      "keySource": "header:X-User-Id",
      "qps": 100,
      "burstCapacity": 200
    },
    {
      "type": "IP",
      "qps": 10,
      "burstCapacity": 20
    }
  ]
}
```

### Dimension Check Order

```
Request arrives
      │
      ▼
┌─────────────────┐
│ Global Limit    │── Exceeded ──▶ 429
└────────┬────────┘
         │ Passed
         ▼
┌─────────────────┐
│ Tenant Limit    │── Exceeded ──▶ 429
└────────┬────────┘
         │ Passed
         ▼
┌─────────────────┐
│ User Limit      │── Exceeded ──▶ 429
└────────┬────────┘
         │ Passed
         ▼
┌─────────────────┐
│ IP Limit        │── Exceeded ──▶ 429
└────────┬────────┘
         │ Passed
         ▼
    Forward Request
```

---

## Redis Failover (Shadow Quota)

### Problem

Redis 故障时，简单降级到本地限流会导致流量激增：

```
Scenario: 5 gateway nodes, global limit = 10,000 QPS

Before Redis failure:
  - Each node handles ~2,000 QPS (10,000 / 5)

Redis fails (naive fallback):
  - All nodes reset counters to 0
  - Each node allows up to local limit
  - Backend may receive 10,000+ QPS → cascading failure
```

### Solution: Shadow Quota

```
┌─────────────────────────────────────────────┐
│         SHADOW QUOTA FAILOVER                │
│                                              │
│   Redis Healthy:                             │
│   - Record global QPS snapshot every 1s      │
│   - Monitor cluster node count               │
│   - Calculate: shadowQuota = globalQPS/nodes │
│                                              │
│   Redis Fails:                               │
│   - Switch to local mode                     │
│   - Use pre-calculated shadow quota          │
│   - No counter reset!                        │
│                                              │
│   Example:                                   │
│   - Global: 10,000 QPS, Nodes: 5             │
│   - Shadow quota: 10,000 / 5 = 2,000 QPS     │
│   - Each node continues at ~2,000 QPS        │
│   - Backend traffic: stable at ~10,000 QPS   │
└─────────────────────────────────────────────┘
```

### Recovery Strategy

Redis 恢复时，渐进式流量迁移：

```
Redis Recovery Timeline:

Second 0:  10% traffic to Redis, 90% local
Second 1:  20% traffic to Redis, 80% local
...
Second 9:  100% traffic to Redis, fully recovered
```

---

## Sliding Window Algorithm

使用滑动窗口实现精确限流：

```
┌─────────────────────────────────────────────┐
│         SLIDING WINDOW                       │
│                                              │
│   Window Size: 1 second                      │
│   Sub-windows: 10 (100ms each)               │
│                                              │
│   Time: ─────────────────────────────▶       │
│         [0][1][2][3][4][5][6][7][8][9]       │
│                              ^ current       │
│                                              │
│   Count = sum of sub-windows                 │
│   If Count > limit → reject                  │
└─────────────────────────────────────────────┘
```

---

## Non-Blocking Lock Optimization

本地限流器使用 CAS + tryLock 策略，避免阻塞 EventLoop：

```java
// Fast path: CAS for low contention
if (currentCount.compareAndSet(count, count + 1)) {
    return true;  // Success without blocking
}

// Slow path: tryLock for high contention (never blocks!)
if (lock.tryLock()) {
    try {
        if (count < maxRequests) {
            currentCount.incrementAndGet();
            return true;
        }
        return false;
    } finally {
        lock.unlock();
    }
}

// tryLock failed - immediately reject
return false;  // Prevents EventLoop thread starvation
```

---

## Error Response

限流触发时返回：

```json
{
  "code": 52901,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Limit: 100 requests per 1000ms",
  "data": null,
  "retryAfter": 1
}
```

Headers:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1234567890
Retry-After: 1
```

---

## API Endpoints

限流配置通过 Strategy API 管理：

```bash
# Configure rate limiting
curl -X PUT http://localhost:9090/api/strategies/rate-limiter \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "public-api",
    "qps": 100,
    "burstCapacity": 200,
    "keyType": "ip",
    "enabled": true
  }'
```

---

## Best Practices

1. **合理设置 QPS**：根据后端容量设置
2. **突发容量**：设置适当的 burstCapacity 允许短暂高峰
3. **监控 Redis**：确保 Redis 健康，避免降级
4. **Key Type 选择**：根据业务场景选择合适的 Key Type
5. **渐进式恢复**：Redis 恢复后观察流量稳定性

---

## Related Features

- [Circuit Breaker](circuit-breaker.md) - 熔断保护
- [Monitoring & Alerts](monitoring-alerts.md) - 限流监控
- [Request Tracing](request-tracing.md) - 记录限流事件