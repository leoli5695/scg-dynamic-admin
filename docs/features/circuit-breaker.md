# Circuit Breaker

> Circuit breaker protects backend services from cascading failures, implemented using Resilience4j.

---

## Overview

Circuit breaker state machine:

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
| `failureRateThreshold` | Failure rate threshold (%) | `50` |
| `slowCallDurationThreshold` | Slow call threshold (ms) | `60000` |
| `slowCallRateThreshold` | Slow call rate threshold (%) | `80` |
| `waitDurationInOpenState` | OPEN state wait duration (ms) | `30000` |
| `slidingWindowSize` | Sliding window size | `10` |
| `minimumNumberOfCalls` | Minimum number of calls | `5` |
| `permittedNumberOfCallsInHalfOpenState` | HALF_OPEN test count | `3` |

---

## State Descriptions

### CLOSED (Normal)

- All requests are forwarded normally
- Records success/failure/slow call statistics
- Switches to OPEN when failure rate exceeds threshold

### OPEN (Circuit Open)

- All requests immediately return error (not forwarded)
- Waits for `waitDurationInOpenState` before switching to HALF_OPEN

### HALF_OPEN (Testing)

- Allows `permittedNumberOfCallsInHalfOpenState` requests through
- Success → Switch to CLOSED
- Failure → Switch back to OPEN

---

## Error Response

Returned when circuit is open:

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
| `COUNT_BASED` | Count-based sliding window |
| `TIME_BASED` | Time-based sliding window |

### COUNT_BASED

Statistics for the most recent N calls:

```
Sliding Window Size: 10 calls

Call history: [S][S][F][S][F][F][S][S][F][F]
              ↑ latest 10 calls

Failure count: 5
Failure rate: 5/10 = 50%
```

### TIME_BASED

Statistics for calls within the most recent N seconds:

```
Sliding Window Size: 10 seconds

Time window: [T-10s ... T-0s]
Calls in window: 100
Failures: 60
Failure rate: 60%
```

---

## Monitoring

Circuit breaker status can be monitored in UI:

- Current state (CLOSED/OPEN/HALF_OPEN)
- Failure rate
- Slow call rate
- Recent call statistics

---

## API Endpoints

Configure via Strategy API:

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

1. **Threshold Settings**: Adjust based on service fault tolerance
2. **Wait Duration**: Give backend service time to recover
3. **Minimum Call Count**: Avoid false circuit breaks from small number of requests
4. **Monitoring Alerts**: Send alert notifications when circuit breaks
5. **Combine with Retry**: Coordinate with retry strategy after circuit break

---

## Related Features

- [Rate Limiting](rate-limiting.md) - Rate limiting protection
- [Retry](retry.md) - Retry strategy
- [Timeout Control](timeout-control.md) - Timeout control
- [Monitoring & Alerts](monitoring-alerts.md) - Status monitoring