# Retry

> Retry strategy automatically retries on request failure, improving system fault tolerance.

---

## Overview

Gateway supports configurable retry strategy, automatically retrying in the following scenarios:
- Connection failure
- Response timeout
- HTTP 5xx errors
- Network exceptions

```
Request Flow:
  ...
  Circuit Breaker (-100) → Check circuit state
       ↓
  Retry (2147483647) → Retry on failure
       ↓
  RouteToRequestUrlFilter (10000) → Forward to backend
```

---

## Configuration

```json
{
  "routeId": "unstable-api",
  "maxAttempts": 3,
  "retryIntervalMs": 1000,
  "retryOnStatusCodes": [500, 502, 503, 504],
  "retryOnExceptions": [
    "java.net.ConnectException",
    "java.net.SocketTimeoutException",
    "java.io.IOException"
  ],
  "enabled": true
}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `maxAttempts` | Maximum retry attempts (including initial request) | `3` |
| `retryIntervalMs` | Retry interval (milliseconds) | `1000` |
| `retryOnStatusCodes` | HTTP status codes that trigger retry | `[500, 502, 503, 504]` |
| `retryOnExceptions` | Full class path of exceptions that trigger retry | See defaults below |
| `enabled` | Whether to enable retry | `true` |

---

## Retry Triggers

### HTTP Status Codes

Default status codes for retry:

| Status | Description | Retry? |
|--------|-------------|--------|
| `500` | Internal Server Error | Yes |
| `502` | Bad Gateway | Yes |
| `503` | Service Unavailable | Yes |
| `504` | Gateway Timeout | Yes |
| `400` | Bad Request | No |
| `401` | Unauthorized | No |
| `403` | Forbidden | No |
| `404` | Not Found | No |
| `429` | Too Many Requests | No |

### Exceptions

Default exception types for retry:

| Exception | Description | Retry? |
|-----------|-------------|--------|
| `java.net.ConnectException` | Connection failure | Yes |
| `java.net.SocketTimeoutException` | Socket timeout | Yes |
| `java.io.IOException` | IO exception | Yes |
| `NotFoundException` | Service instance not found | Yes |

> **Note:** Exception types require the full Java class path name.

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
          │ Wait     │
          │ (retryMs)│
          └──────────┘
                │
                ▼
          Retry (if attempts < maxAttempts)
```

---

## Retry Interval

Retry uses fixed interval strategy:

```
┌─────────────────────────────────────────────┐
│         FIXED INTERVAL RETRY                 │
│                                              │
│   Request Failed                              │
│         │                                     │
│         ▼                                     │
│   Retry 1: wait 1000ms                        │
│         │                                     │
│         ▼                                     │
│   Retry 2: wait 1000ms                        │
│         │                                     │
│         ▼                                     │
│   Retry 3: wait 1000ms                        │
│         │                                     │
│         ▼                                     │
│   Max Attempts Reached → Return Error         │
└─────────────────────────────────────────────┘
```

**Configuration Example:**

```json
{
  "maxAttempts": 3,
  "retryIntervalMs": 500
}
```

Retry intervals: 500ms, 500ms, 500ms...

---

## Error Response

Returned after all retries fail:

```json
{
  "code": 50201,
  "error": "Upstream Error",
  "message": "Request failed after 3 attempts",
  "data": null
}
```

---

## Circuit Breaker Interaction

Retry works with circuit breaker:

```
┌─────────────────────────────────────────────┐
│         RETRY + CIRCUIT BREAKER               │
│                                              │
│   1. Request arrives                          │
│   2. Circuit Breaker check (CLOSED)           │
│   3. Send request                             │
│   4. If failure:                              │
│      - Record failure in Circuit Breaker      │
│      - Retry after interval                   │
│   5. If all retries fail:                     │
│      - Increment Circuit Breaker failure rate │
│      - Return error to client                 │
│   6. If failure rate > threshold:             │
│      - Circuit Breaker → OPEN                 │
│      - No more requests forwarded             │
└─────────────────────────────────────────────┘
```

**Important:** Retry failures count towards circuit breaker failure statistics and may trigger circuit break.

---

## Use Cases

### Transient Failure Recovery

Automatic recovery from brief network jitter:

```json
{
  "maxAttempts": 2,
  "retryIntervalMs": 100,
  "retryOnExceptions": ["java.net.ConnectException"]
}
```

### Slow Backend Recovery

Backend service occasional timeout:

```json
{
  "maxAttempts": 3,
  "retryIntervalMs": 500,
  "retryOnStatusCodes": [504]
}
```

### High-Availability Service

Critical services need higher fault tolerance:

```json
{
  "maxAttempts": 5,
  "retryIntervalMs": 200,
  "retryOnStatusCodes": [500, 502, 503, 504],
  "retryOnExceptions": [
    "java.net.ConnectException",
    "java.net.SocketTimeoutException",
    "java.io.IOException"
  ]
}
```

---

## API Endpoints

Configure via Strategy API:

```bash
curl -X PUT http://localhost:9090/api/strategies/retry \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "unstable-api",
    "maxAttempts": 3,
    "retryIntervalMs": 1000,
    "retryOnStatusCodes": [500, 502, 503, 504],
    "enabled": true
  }'
```

---

## Best Practices

1. **Reasonable Retry Count**: Usually 2-3 times, too many will increase latency
2. **Appropriate Interval**: Too short intervals pressure backend, too long affects user experience
3. **Distinguish Error Types**: Only retry recoverable errors (5xx, timeout, connection failure)
4. **Combine with Circuit Breaker**: Avoid retry exacerbating backend issues
5. **Monitor Retry Rate**: High retry rate indicates backend instability
6. **Exception Class Full Path**: Use full Java class path when configuring exception types

---

## Related Features

- [Circuit Breaker](circuit-breaker.md) - Circuit breaker protection
- [Timeout Control](timeout-control.md) - Timeout configuration
- [Monitoring & Alerts](monitoring-alerts.md) - Retry monitoring