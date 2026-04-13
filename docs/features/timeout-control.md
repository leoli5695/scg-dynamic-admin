# Timeout Control

> Timeout control protects Gateway and backend services from long waits.

---

## Overview

Gateway supports two timeout configurations:
- **Connect Timeout**: TCP connection establishment time
- **Response Timeout**: Complete response wait time

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
| `connectTimeout` | TCP connection timeout (ms) | `5000` |
| `responseTimeout` | Response timeout (ms) | `30000` |

---

## Timeout Types

### Connect Timeout

Timeout for TCP connection establishment:

```
Gateway ───────▶ Backend

TCP SYN ───────▶ (waiting)
      │
      │ connectTimeout exceeded
      ▼
   Connection Failed
```

Factors affecting:
- Network latency
- Whether backend service is started
- Network congestion

### Response Timeout

Timeout from request sent to response completed:

```
Gateway ───────▶ Backend

Request ───────▶ Processing
      │
      │ responseTimeout exceeded
      ▼
   Timeout Error (504)
```

Factors affecting:
- Backend processing time
- Data transfer time
- Backend load

---

## Error Response

Returned when timeout is triggered:

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

Configure via Strategy API:

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

1. **Reasonable Settings**: Set based on backend average response time
2. **Tiered Configuration**: Set different timeouts for different APIs
3. **Monitoring Alerts**: Send alerts when timeouts are frequent
4. **Combine with Circuit Breaker**: Treat timeout as failure, count towards circuit breaker statistics
5. **Long Connection Scenarios**: Appropriately increase timeout duration

---

## Related Features

- [Circuit Breaker](circuit-breaker.md) - Circuit breaker (timeout treated as failure)
- [Retry](retry.md) - Retry after timeout
- [Monitoring & Alerts](monitoring-alerts.md) - Timeout monitoring