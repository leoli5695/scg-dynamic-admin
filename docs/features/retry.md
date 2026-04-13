# Retry

> 重试策略在请求失败时自动重试，提高系统容错能力。

---

## Overview

Gateway 支持配置重试策略，在以下场景自动重试：
- 连接超时
- 响应超时
- HTTP 5xx 错误
- 网络异常

```
Request Flow:
  ...
  Circuit Breaker (-100) → Check circuit state
       ↓
  Retry (9999) → Retry on failure
       ↓
  RouteToRequestUrlFilter (10000) → Forward to backend
```

---

## Configuration

```json
{
  "routeId": "unstable-api",
  "maxRetries": 3,
  "retryOnStatuses": [500, 502, 503, 504],
  "retryOnExceptions": ["timeout", "connection"],
  "backoff": {
    "type": "EXPONENTIAL",
    "initialDelay": 100,
    "maxDelay": 1000,
    "multiplier": 2
  },
  "enabled": true
}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `maxRetries` | 最大重试次数 | `3` |
| `retryOnStatuses` | 触发重试的 HTTP 状态码 | `[500, 502, 503, 504]` |
| `retryOnExceptions` | 触发重试的异常类型 | `["timeout", "connection"]` |
| `backoff` | 退避策略配置 | 见下方 |

---

## Retry Triggers

### HTTP Status Codes

| Status | Description | Retry? |
|--------|-------------|--------|
| `500` | Internal Server Error | ✅ |
| `502` | Bad Gateway | ✅ |
| `503` | Service Unavailable | ✅ |
| `504` | Gateway Timeout | ✅ |
| `400` | Bad Request | ❌ |
| `401` | Unauthorized | ❌ |
| `403` | Forbidden | ❌ |
| `404` | Not Found | ❌ |
| `429` | Too Many Requests | ❌ |

### Exceptions

| Exception Type | Description | Retry? |
|----------------|-------------|--------|
| `timeout` | 响应超时 | ✅ |
| `connection` | 连接失败 | ✅ |
| `read_error` | 读取错误 | ✅ |
| `write_error` | 写入错误 | ✅ |

---

## Backoff Strategies

### Fixed Backoff

每次重试间隔固定：

```json
{
  "backoff": {
    "type": "FIXED",
    "delay": 500
  }
}
```

重试间隔：500ms, 500ms, 500ms...

### Linear Backoff

每次重试间隔线性增加：

```json
{
  "backoff": {
    "type": "LINEAR",
    "initialDelay": 100,
    "maxDelay": 2000
  }
}
```

重试间隔：100ms, 200ms, 300ms, 400ms...

### Exponential Backoff

每次重试间隔指数增加（推荐）：

```json
{
  "backoff": {
    "type": "EXPONENTIAL",
    "initialDelay": 100,
    "maxDelay": 10000,
    "multiplier": 2
  }
}
```

重试间隔：100ms, 200ms, 400ms, 800ms, 1600ms...

```
┌─────────────────────────────────────────────┐
│         EXPONENTIAL BACKOFF                   │
│                                              │
│   Request Failed                              │
│         │                                     │
│         ▼                                     │
│   Retry 1: wait 100ms                         │
│         │                                     │
│         ▼                                     │
│   Retry 2: wait 200ms (100 * 2)               │
│         │                                     │
│         ▼                                     │
│   Retry 3: wait 400ms (200 * 2)               │
│         │                                     │
│         ▼                                     │
│   Retry 4: wait 800ms (400 * 2)               │
│         │                                     │
│         ▼                                     │
│   Max Retries Reached → Return Error          │
└─────────────────────────────────────────────┘
```

---

## Retry Flow

```
Request arrives
      │
      ▼
┌─────────────────┐
│ Send to Backend │
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
   Success   Failure
    │         │
    ▼         ▼
  Return    ┌─────────────────┐
  Response  │ Check Retryable │
            └────────┬────────┘
                     │
                ┌────┴────┐
                │         │
               Yes       No
                │         │
                ▼         ▼
          ┌──────────┐  Return Error
          │ Backoff  │
          │ Wait     │
          └──────────┘
                │
                ▼
          Retry (if maxRetries not reached)
```

---

## Error Response

重试全部失败后返回：

```json
{
  "code": 50201,
  "error": "Upstream Error",
  "message": "Request failed after 3 retries",
  "data": null,
  "retries": 3,
  "lastError": "503 Service Unavailable"
}
```

---

## Circuit Breaker Interaction

重试与熔断配合使用：

```
┌─────────────────────────────────────────────┐
│         RETRY + CIRCUIT BREAKER               │
│                                              │
│   1. Request arrives                          │
│   2. Circuit Breaker check (CLOSED)           │
│   3. Send request                             │
│   4. If failure:                              │
│      - Record failure in Circuit Breaker      │
│      - Retry with backoff                     │
│   5. If all retries fail:                     │
│      - Increment Circuit Breaker failure rate │
│      - Return error to client                 │
│   6. If failure rate > threshold:             │
│      - Circuit Breaker → OPEN                 │
│      - No more requests forwarded             │
└─────────────────────────────────────────────┘
```

**重要：** 重试失败会计入熔断器失败统计，可能触发熔断。

---

## Use Cases

### Transient Failure Recovery

短暂网络抖动的自动恢复：

```json
{
  "maxRetries": 2,
  "retryOnExceptions": ["connection"],
  "backoff": {
    "type": "FIXED",
    "delay": 100
  }
}
```

### Slow Backend Recovery

后端服务偶尔超时：

```json
{
  "maxRetries": 3,
  "retryOnStatuses": [504],
  "backoff": {
    "type": "EXPONENTIAL",
    "initialDelay": 500,
    "maxDelay": 5000
  }
}
```

### High-Availability Service

关键服务需要更高容错：

```json
{
  "maxRetries": 5,
  "retryOnStatuses": [500, 502, 503, 504],
  "retryOnExceptions": ["timeout", "connection"],
  "backoff": {
    "type": "EXPONENTIAL",
    "initialDelay": 200,
    "maxDelay": 10000
  }
}
```

---

## API Endpoints

通过 Strategy API 配置：

```bash
curl -X PUT http://localhost:9090/api/strategies/retry \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "unstable-api",
    "maxRetries": 3,
    "retryOnStatuses": [500, 502, 503, 504],
    "backoff": {
      "type": "EXPONENTIAL",
      "initialDelay": 100,
      "maxDelay": 1000
    },
    "enabled": true
  }'
```

---

## Best Practices

1. **合理重试次数**：通常 2-3 次，过多会增加延迟
2. **指数退避**：避免对后端造成更大压力
3. **区分错误类型**：只对可恢复错误重试（5xx、超时）
4. **结合熔断**：避免重试加剧后端问题
5. **监控重试率**：高重试率说明后端不稳定
6. **设置最大延迟**：避免用户等待过久

---

## Related Features

- [Circuit Breaker](circuit-breaker.md) - 熔断保护
- [Timeout Control](timeout-control.md) - 超时配置
- [Monitoring & Alerts](monitoring-alerts.md) - 重试监控