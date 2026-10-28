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
  "permittedNumberOfCallsInHalfOpenState": 1,
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
| `permittedNumberOfCallsInHalfOpenState` | HALF_OPEN test count | `1` |

Defaults verified against `my-gateway/src/main/java/com/leoli/gateway/model/CircuitBreakerConfig.java:21,26,31,36,41,46`
and the runtime fallbacks in `my-gateway/src/main/java/com/leoli/gateway/filter/resilience/CircuitBreakerGlobalFilter.java:198-204`
(`permittedNumberOfCallsInHalfOpenState` defaults to **1**, not 3; it is read from the strategy
config map by the filter and is not a field of `CircuitBreakerConfig`).

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

Returned when circuit is open — HTTP status **503 Service Unavailable**, body written by
`GatewayResponseHelper.writeCircuitBreakerOpen` (`util/GatewayResponseHelper.java:240-256`):

```json
{
  "httpStatus": 503,
  "code": 55301,
  "error": "Service Unavailable",
  "message": "The circuit breaker is open after backend failures. Requests are temporarily blocked; wait for recovery and check backend health.",
  "data": null,
  "routeId": "critical-service"
}
```

- `code` = `ErrorCode.CIRCUIT_BREAKER_OPEN` (55301).
- `error` = the HTTP reason phrase, not the enum name.
- `message` comes from `exception/GatewayErrorMessages.java:51`.

---

## Sliding Window Types

> **Not configurable in this project.** The window type cannot be selected: `CircuitBreakerGlobalFilter.java:207-217`
> builds the Resilience4j config with `.slidingWindowSize(...)` only and never calls
> `.slidingWindowType(...)`. Grepping `slidingWindowType|SlidingWindowType|COUNT_BASED|TIME_BASED`
> across `my-gateway/src/main/java` and `gateway-admin/src/main/java` returns **zero hits**.
> The effective type is therefore always Resilience4j's default, `COUNT_BASED`; `TIME_BASED` cannot
> be enabled through any strategy config, API or property.

Resilience4j itself supports two window types; only the first one is reachable here:

| Type | Description | Available here |
|------|-------------|----------------|
| `COUNT_BASED` | Count-based sliding window | Yes — always used (hardcoded default) |
| `TIME_BASED` | Time-based sliding window | **No — not implemented** |

### COUNT_BASED (the only mode in effect)

`slidingWindowSize` is interpreted as a **number of calls**. Statistics for the most recent N calls:

```
Sliding Window Size: 10 calls

Call history: [S][S][F][S][F][F][S][S][F][F]
              ↑ latest 10 calls

Failure count: 5
Failure rate: 5/10 = 50%
```

### TIME_BASED (not available)

Resilience4j's time-based mode would count calls within the most recent N **seconds**:

```
Sliding Window Size: 10 seconds

Time window: [T-10s ... T-0s]
Calls in window: 100
Failures: 60
Failure rate: 60%
```

This gateway never sets `slidingWindowType`, so the illustration above does not describe any
behaviour you can obtain — with `slidingWindowSize: 10` the window is always 10 **calls**.

---

## Monitoring

Circuit breaker status can be monitored in UI:

- Current state (CLOSED/OPEN/HALF_OPEN)
- Failure rate
- Slow call rate
- Recent call statistics

---

## API Endpoints

Circuit breaker is a **strategy type**, managed through the generic Strategy API
(`StrategyController.java`, `@RequestMapping("/api/strategies")`). There is **no**
`PUT /api/strategies/circuit-breaker` — the path segment after `/api/strategies/` is the
**strategyId**, and the type is carried in the body (`strategyType`) or selected via the
`/type/{type}` listing.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/strategies/type/CIRCUIT_BREAKER` | List all circuit breaker strategies (`:65`) |
| `GET` | `/api/strategies/{id}` | Get one strategy (`:53`) |
| `POST` | `/api/strategies` | Create a strategy (`:93`) |
| `PUT` | `/api/strategies/{id}` | Update a strategy — `{id}` is the **strategyId** (`:120`) |
| `DELETE` | `/api/strategies/{id}` | Delete a strategy (`:153`) |
| `POST` | `/api/strategies/{id}/enable` \| `/disable` | Toggle (`:185`, `:221`) |

The type constant is `StrategyDefinition.TYPE_CIRCUIT_BREAKER = "CIRCUIT_BREAKER"`
(`model/StrategyDefinition.java:72`). The body must be a **complete `StrategyDefinition`** —
the breaker parameters live inside its `config` map, not at the top level. All POST/PUT/PATCH/DELETE
under `/api/**` require an `ADMIN` JWT (`SecurityConfig.java:122-125`).

```bash
curl -X PUT "http://localhost:9090/api/strategies/cb-critical-service?instanceId=tj9j963zq2x1" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -d '{
    "strategyId": "cb-critical-service",
    "strategyName": "Critical service circuit breaker",
    "strategyType": "CIRCUIT_BREAKER",
    "scope": "ROUTE",
    "routeId": "critical-service",
    "priority": 100,
    "enabled": true,
    "config": {
      "routeId": "critical-service",
      "failureRateThreshold": 50.0,
      "slowCallDurationThreshold": 60000,
      "slowCallRateThreshold": 80.0,
      "waitDurationInOpenState": 30000,
      "slidingWindowSize": 10,
      "minimumNumberOfCalls": 5,
      "permittedNumberOfCallsInHalfOpenState": 1,
      "enabled": true
    }
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