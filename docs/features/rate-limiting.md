# Rate Limiting

> Gateway provides distributed rate limiting functionality, supporting Redis + local hybrid mode with graceful degradation.

---

## Overview

Gateway uses a hybrid rate limiting architecture:
- **Redis Rate Limiting**: Distributed scenarios, multi-instance shared counters
- **Local Rate Limiting**: Automatic fallback when Redis fails

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
| `qps` | Requests per second limit | - |
| `burstCapacity` | Burst capacity | `2 * qps` |
| `keyType` | Rate limiting key type | `ip` |
| `enabled` | Whether to enable | `true` |

### Key Types

| Key Type | Description | Use Case |
|----------|-------------|----------|
| `ip` | By client IP | Prevent single IP abuse |
| `route` | Shared by route | Global protection |
| `combined` | Route + IP combination | Fine-grained control |
| `header` | By header value | Rate limit by user/tenant |
| `user` | By user ID | Post-authentication rate limiting |

---

## Multi-Dimensional Rate Limiting

Supports hierarchical rate limiting: Global → Tenant → User → IP

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

When Redis fails, simple fallback to local rate limiting can cause traffic spikes:

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

When Redis recovers, gradual traffic migration:

```
Redis Recovery Timeline:

Second 0:  10% traffic to Redis, 90% local
Second 1:  20% traffic to Redis, 80% local
...
Second 9:  100% traffic to Redis, fully recovered
```

---

## Sliding Window Algorithm

Uses sliding window for precise rate limiting:

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

Local rate limiter uses CAS + tryLock strategy to avoid blocking EventLoop:

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

Returned when rate limit is triggered:

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

Rate limiting configuration is managed via Strategy API:

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

1. **Reasonable QPS Settings**: Set based on backend capacity
2. **Burst Capacity**: Set appropriate burstCapacity to allow short traffic spikes
3. **Monitor Redis**: Ensure Redis health to avoid fallback
4. **Key Type Selection**: Choose appropriate Key Type based on business scenario
5. **Gradual Recovery**: Monitor traffic stability after Redis recovery

---

## Related Features

- [Circuit Breaker](circuit-breaker.md) - Circuit breaker protection
- [Monitoring & Alerts](monitoring-alerts.md) - Rate limiting monitoring
- [Request Tracing](request-tracing.md) - Record rate limiting events