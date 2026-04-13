# Circuit Breaker

> 熔断器保护后端服务免受级联故障，使用 Resilience4j 实现。

---

## Overview

熔断器状态机：

```
┌─────────────────────────────────────────────┐
│         CIRCUIT BREAKER STATE MACHINE        │
│                                              │
│   CLOSED (Normal)                            │
│       │                                      │
│       │ Failure rate > threshold             │
│       ▼                                      │
│   OPEN (Reject All)                          │
│       │                                      │
│       │ After waitDuration                   │
│       ▼                                      │
│   HALF_OPEN (Test)                           │
│       │                                      │
│   ┌───┴───┐                                  │
│   │       │                                  │
│ Success  Failure                             │
│   │       │                                  │
│   ▼       ▼                                  │
│ CLOSED   OPEN                                │
└─────────────────────────────────────────────┘
```

---

## Configuration

```json
{
  "routeId": "critical-service",
  "failureRateThreshold": 50.0,
  "slowCallDurationThreshold": 60000,
  "slowCallRateThreshold": 80.0,
  "waitDurationInOpenState": 30000,
  "slidingWindowSize": 10,
  "minimumNumberOfCalls": 5,
  "permittedNumberOfCallsInHalfOpenState": 3,
  "enabled": true
}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `failureRateThreshold` | 失败率阈值 (%) | `50` |
| `slowCallDurationThreshold` | 慢调用阈值 (ms) | `60000` |
| `slowCallRateThreshold` | 慢调用率阈值 (%) | `80` |
| `waitDurationInOpenState` | OPEN 状态等待时间 (ms) | `30000` |
| `slidingWindowSize` | 滑动窗口大小 | `10` |
| `minimumNumberOfCalls` | 最小调用次数 | `5` |
| `permittedNumberOfCallsInHalfOpenState` | HALF_OPEN 测试次数 | `3` |

---

## State Descriptions

### CLOSED (正常)

- 所有请求正常转发
- 记录成功/失败/慢调用统计
- 失败率超过阈值时切换到 OPEN

### OPEN (熔断)

- 所有请求立即返回错误（不转发）
- 等待 `waitDurationInOpenState` 后切换到 HALF_OPEN

### HALF_OPEN (试探)

- 允许 `permittedNumberOfCallsInHalfOpenState` 个请求通过
- 成功 → 切换到 CLOSED
- 失败 → 切换回 OPEN

---

## Error Response

熔断触发时返回：

```json
{
  "code": 55301,
  "error": "Service Unavailable",
  "message": "Circuit breaker is open, please try again later",
  "data": null,
  "routeId": "critical-service"
}
```

---

## Sliding Window Types

| Type | Description |
|------|-------------|
| `COUNT_BASED` | 基于调用次数的滑动窗口 |
| `TIME_BASED` | 基于时间的滑动窗口 |

### COUNT_BASED

最近 N 次调用的统计：

```
Sliding Window Size: 10 calls

Call history: [S][S][F][S][F][F][S][S][F][F]
              ↑ latest 10 calls

Failure count: 5
Failure rate: 5/10 = 50%
```

### TIME_BASED

最近 N 秒内的调用统计：

```
Sliding Window Size: 10 seconds

Time window: [T-10s ... T-0s]
Calls in window: 100
Failures: 60
Failure rate: 60%
```

---

## Monitoring

熔断状态可在 UI 监控：

- 当前状态 (CLOSED/OPEN/HALF_OPEN)
- 失败率
- 慢调用率
- 最近调用统计

---

## API Endpoints

通过 Strategy API 配置：

```bash
curl -X PUT http://localhost:9090/api/strategies/circuit-breaker \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "critical-service",
    "failureRateThreshold": 50.0,
    "waitDurationInOpenState": 30000,
    "slidingWindowSize": 10,
    "enabled": true
  }'
```

---

## Best Practices

1. **阈值设置**：根据服务容错能力调整
2. **等待时间**：给后端服务恢复时间
3. **最小调用次数**：避免少量请求触发误熔断
4. **监控告警**：熔断时发送告警通知
5. **结合 Retry**：熔断后可配合重试策略

---

## Related Features

- [Rate Limiting](rate-limiting.md) - 限流保护
- [Retry](retry.md) - 重试策略
- [Timeout Control](timeout-control.md) - 超时控制
- [Monitoring & Alerts](monitoring-alerts.md) - 状态监控