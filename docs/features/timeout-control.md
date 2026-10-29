# Timeout Control

> Timeout control protects Gateway and backend services from long waits.

---

## Overview

Gateway supports two timeout configurations:
- **Connect Timeout**: TCP connection establishment time
- **Response Timeout**: Complete response wait time

---

## Configuration

### No Strategy Configured

When no timeout strategy is configured the gateway writes **no** route timeout metadata, so the global `spring.cloud.gateway.httpclient.connect-timeout` / `response-timeout` apply (5s / 300s by default; the response timeout is deliberately long because reasoning models can take minutes before the first byte). Route metadata takes priority over those globals inside Spring Cloud Gateway, so the `gateway.timeout` defaults below are only used to fill in fields a configured strategy omits:

```yaml
gateway:
  timeout:
    default-connect-timeout: ${GATEWAY_TIMEOUT_CONNECT:1000}
    default-response-timeout: ${GATEWAY_TIMEOUT_RESPONSE:30000}
```

### Timeout Strategy (User-Defined)

Configure via Strategy API:

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
| `connectTimeout` | TCP connection timeout (ms) | `1000` |
| `responseTimeout` | Response timeout (ms) | `30000` |

---

## Priority Logic

| Scenario | Connect Timeout |
|----------|-----------------|
| No timeout strategy configured | Global `httpclient.connect-timeout` (5s) |
| Timeout strategy with user-defined value | User's value |
| Timeout strategy without explicit value | `gateway.timeout.default-connect-timeout` (1s) |

---

## Timeout Types

Two independent timeouts, both in milliseconds:

| Type | What it bounds | Where the effective value comes from |
|------|----------------|--------------------------------------|
| **Connect timeout** | The TCP handshake to the upstream; fires before any request byte is written | `RouteMetadataUtils.CONNECT_TIMEOUT_ATTR` in route metadata, else `spring.cloud.gateway.httpclient.connect-timeout` |
| **Response timeout** | Total time from request sent to response received | `RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR` in route metadata, else `spring.cloud.gateway.httpclient.response-timeout` |

`TimeoutGlobalFilter` enforces neither timeout itself. It writes the two values into the route's
metadata (`RouteMetadataUtils` is Spring Cloud Gateway's own
`org.springframework.cloud.gateway.support.RouteMetadataUtils`, imported at
`TimeoutGlobalFilter.java:12`) and SCG's `NettyRoutingFilter` reads them. That is why the filter is a
no-op when a route has no timeout strategy: it deliberately leaves the metadata untouched so the
global `httpclient` values keep applying (`TimeoutGlobalFilter.java:49`).

### Two classes named `TimeoutConfig` - only one is on the gateway path

`gateway-admin/.../model/StrategyConfig.TimeoutConfig` (nested class, `StrategyConfig.java:569`) is
what the console and the validator use. Its field defaults are `connectTimeout = 5000`,
`responseTimeout = 30000`, `enabled = true`, so a strategy created through the console carries `5000`
explicitly and the gateway honours `5000`.

`my-gateway/.../model/TimeoutConfig.java` declares the same field names and the same defaults but is
**not referenced by any main-source code**. The filter reads a raw `Map<String, Object>` from
`StrategyManager.getTimeoutConfig(routeId)` (`TimeoutGlobalFilter.java:47`,
`StrategyManager.java:602`) and falls back per key to `TimeoutProperties`
(`gateway.timeout.default-connect-timeout` = 1000, `default-response-timeout` = 30000,
`TimeoutProperties.java:20,27,33`). So a strategy whose config map simply omits `connectTimeout` gets
**1000**, not the 5000 that either POJO declares. Do not read the gateway's `TimeoutConfig` defaults
as the effective fallback.

### Validation bounds (admin side, applied when the strategy is saved)

`StrategyConfigValidator.validateTimeout` (`:216-240`) rejects a strategy when:

- `connectTimeout <= 0`, or `connectTimeout > 60000` (60s ceiling)
- `responseTimeout <= 0`, or `responseTimeout > 300000` (5min ceiling)
- `responseTimeout < connectTimeout`

These bounds are checked on save, not when the gateway applies the values - the gateway takes
whatever is in the config map.
