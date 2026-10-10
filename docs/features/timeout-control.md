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