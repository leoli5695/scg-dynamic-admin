# Timeout Control

> Timeout control protects Gateway and backend services from long waits.

---

## Overview

Gateway supports two timeout configurations:
- **Connect Timeout**: TCP connection establishment time
- **Response Timeout**: Complete response wait time

---

## Configuration

### Default Timeout (No Strategy)

When no timeout strategy is configured, Gateway applies default values from `application.yml`:

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
| No timeout strategy configured | Default: 1s |
| Timeout strategy with user-defined value | User's value |
| Timeout strategy without explicit value | Default: 1s |

---

## Timeout Types