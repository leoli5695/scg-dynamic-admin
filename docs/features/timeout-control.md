# Timeout Control

> 超时控制保护 Gateway 和后端服务免受长时间等待。

---

## Overview

Gateway 支持两种超时配置：
- **连接超时**：TCP 连接建立时间
- **响应超时**：完整响应等待时间

---

## Configuration

```json
{
  "routeId": "slow-api",
  "connectTimeout": 5000,
  "responseTimeout": 30000,
  "enabled": true
}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `connectTimeout` | TCP 连接超时 (ms) | `5000` |
| `responseTimeout` | 响应超时 (ms) | `30000` |

---

## Timeout Types

### Connect Timeout

TCP 连接建立的超时时间：

```
Gateway ───────▶ Backend

TCP SYN ───────▶ (等待)
      │
      │ connectTimeout exceeded
      ▼
   Connection Failed
```

影响因素：
- 网络延迟
- 后端服务是否启动
- 网络拥塞

### Response Timeout

从请求发送到响应完成的超时时间：

```
Gateway ───────▶ Backend

Request ───────▶ Processing
      │
      │ responseTimeout exceeded
      ▼
   Timeout Error (504)
```

影响因素：
- 后端处理时间
- 数据传输时间
- 后端负载

---

## Error Response

超时触发时返回：

```json
{
  "code": 50401,
  "error": "Gateway Timeout",
  "message": "Response timeout exceeded",
  "data": null
}
```

---

## API Endpoints

通过 Strategy API 配置：

```bash
curl -X PUT http://localhost:9090/api/strategies/timeout \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "slow-api",
    "connectTimeout": 5000,
    "responseTimeout": 60000,
    "enabled": true
  }'
```

---

## Best Practices

1. **合理设置**：根据后端平均响应时间设置
2. **分级配置**：不同 API 设置不同超时
3. **监控告警**：超时频繁时发送告警
4. **结合熔断**：超时视为失败，触发熔断统计
5. **长连接场景**：适当增加超时时间

---

## Related Features

- [Circuit Breaker](circuit-breaker.md) - 熔断器（超时视为失败）
- [Retry](retry.md) - 超时后重试
- [Monitoring & Alerts](monitoring-alerts.md) - 超时监控