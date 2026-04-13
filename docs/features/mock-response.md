# Mock Response

> Mock response functionality supports returning static/dynamic/template responses, used for frontend-backend collaboration and testing.

---

## Overview

Mock response executes after authentication, can skip backend calls:

```
Request Flow:
  ...
  Authentication (-250) → Auth check
       ↓
  Mock Response (-249) → Return mock data (skip backend)
       ↓
  (No backend call)
```

---

## Mock Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| **STATIC** | Fixed response body | Simple mock |
| **DYNAMIC** | Select response by condition | Simulate different scenarios |
| **TEMPLATE** | Template engine generated response | Simulate real data structure |

---

## Configuration

### Static Mock

```json
{
  "routeId": "test-api",
  "enabled": true,
  "mockMode": "STATIC",
  "staticMock": {
    "statusCode": 200,
    "contentType": "application/json",
    "headers": {
      "X-Mock": "true"
    },
    "body": "{\"id\": 1, \"name\": \"Mock User\"}"
  }
}
```

| Field | Description | Default |
|-------|-------------|---------|
| `statusCode` | HTTP status code | `200` |
| `contentType` | Content-Type | `application/json` |
| `headers` | Custom response headers | `{}` |
| `body` | Response body string | - |
| `bodyFile` | Response body file path | - |

### Dynamic Mock (Conditional)

```json
{
  "routeId": "test-api",
  "enabled": true,
  "mockMode": "DYNAMIC",
  "dynamicMock": {
    "conditions": [
      {
        "matchType": "HEADER",
        "headerConditions": {
          "X-Version": "v2"
        },
        "response": {
          "statusCode": 200,
          "body": "{\"version\": \"v2\"}"
        }
      },
      {
        "matchType": "QUERY",
        "queryConditions": {
          "preview": "true"
        },
        "response": {
          "statusCode": 200,
          "body": "{\"preview\": true}"
        }
      }
    ],
    "defaultResponse": {
      "statusCode": 200,
      "body": "{\"version\": \"v1\"}"
    }
  }
}
```

| Field | Description |
|-------|-------------|
| `conditions` | Condition list, matched in order |
| `defaultResponse` | Default response when no condition matches |

**MockCondition fields:**

| Field | Description | Default |
|-------|-------------|---------|
| `matchType` | Match type: PATH, HEADER, QUERY, BODY | `PATH` |
| `pathPattern` | Ant-style path pattern | - |
| `headerConditions` | Header condition mapping | `{}` |
| `queryConditions` | Query parameter condition mapping | `{}` |
| `bodyConditions` | JSONPath Body condition | `{}` |
| `response` | Response returned when matched | - |

> **Note:** One condition can configure multiple condition types, all must match to be considered a hit.

### Template Mock (Handlebars)

```json
{
  "routeId": "test-api",
  "enabled": true,
  "mockMode": "TEMPLATE",
  "templateMock": {
    "templateEngine": "HANDLEBARS",
    "template": "{\"id\": \"{{id}}\", \"name\": \"{{name}}\"}",
    "variables": {
      "id": "123",
      "name": "Test User"
    },
    "extractFromRequest": [
      {
        "source": "PATH",
        "name": "userId",
        "expression": "/users/{id}"
      },
      {
        "source": "HEADER",
        "name": "token",
        "expression": "X-Token"
      },
      {
        "source": "QUERY",
        "name": "page",
        "expression": "page"
      }
    ]
  }
}
```

| Field | Description | Default |
|-------|-------------|---------|
| `templateEngine` | Template engine: HANDLEBARS, MUSTACHE, JSON_TEMPLATE | `HANDLEBARS` |
| `template` | Template content | - |
| `templateFile` | Template file path | - |
| `variables` | Static variable mapping | `{}` |
| `extractFromRequest` | Extract variables from request | `[]` |

**RequestExtractConfig fields:**

| Field | Description | Default |
|-------|-------------|---------|
| `source` | Extract source: PATH, HEADER, QUERY, BODY | `PATH` |
| `name` | Variable name (referenced in template) | - |
| `expression` | Extract expression | - |
| `defaultValue` | Default value when extraction fails | - |

**Extract expression description:**

| Source | Expression example | Description |
|--------|-------------------|-------------|
| `PATH` | `/users/{id}` | Ant-style path, extract `{id}` |
| `HEADER` | `X-Token` | Header name |
| `QUERY` | `page` | Query parameter name |
| `BODY` | `$.data.id` | JSONPath (not yet implemented) |

---

## Error Simulation

```json
{
  "routeId": "test-api",
  "enabled": true,
  "mockMode": "STATIC",
  "staticMock": {
    "statusCode": 200,
    "body": "{\"success\": true}"
  },
  "errorSimulation": {
    "enabled": true,
    "errorRate": 10,
    "errorStatusCodes": [500, 503, 504],
    "errorBodyTemplate": "{\"error\": \"Simulated error\", \"code\": ${statusCode}}"
  }
}
```

| Field | Description | Default |
|-------|-------------|---------|
| `enabled` | Whether to enable error simulation | `false` |
| `errorRate` | Error rate percentage (0-100) | `0` |
| `errorStatusCodes` | Error status code list | `[500, 503, 504]` |
| `errorBodyTemplate` | Error response template | See default value |

10% of requests return simulated errors (500/503/504 random). `${statusCode}` will be replaced with the actual status code.

---

## Delay Simulation

```json
{
  "routeId": "test-api",
  "enabled": true,
  "mockMode": "STATIC",
  "staticMock": {
    "statusCode": 200,
    "body": "{\"data\": \"test\"}"
  },
  "delay": {
    "enabled": true,
    "fixedDelayMs": 500,
    "randomDelay": {
      "enabled": true,
      "minMs": 100,
      "maxMs": 1000
    },
    "networkConditions": "FAST"
  }
}
```

| Field | Description | Default |
|-------|-------------|---------|
| `enabled` | Whether to enable delay | `false` |
| `fixedDelayMs` | Fixed delay in milliseconds | `0` |
| `randomDelay` | Random delay configuration | See below |
| `networkConditions` | Preset network condition | `FAST` |

**RandomDelayConfig fields:**

| Field | Description | Default |
|-------|-------------|---------|
| `enabled` | Whether to enable random delay | `false` |
| `minMs` | Minimum delay | `100` |
| `maxMs` | Maximum delay | `500` |

**Preset network conditions (added to fixed delay):**

| Condition | Additional delay |
|-----------|-----------------|
| `FAST` | 0ms (only fixed delay) |
| `4G` | 0-100ms random |
| `3G` | 300-800ms |
| `SLOW_3G` | 1000-3000ms |

---

## Pass Through (Bypass Mock)

```json
{
  "routeId": "test-api",
  "enabled": true,
  "passThrough": {
    "enabled": true,
    "conditions": [
      {
        "headerCondition": "X-Mock-Bypass=true"
      },
      {
        "queryCondition": "mock=false"
      }
    ]
  }
}
```

| Field | Description |
|-------|-------------|
| `enabled` | Whether to enable pass through |
| `conditions` | Pass through condition list |

**PassThroughCondition fields:**

| Field | Description | Format |
|-------|-------------|--------|
| `headerCondition` | Header condition | `X-Mock-Bypass=true` |
| `queryCondition` | Query condition | `mock=false` |

Skip mock when specific header or query parameter is present, forward to real backend.

---

## Mock Response Header

All mock responses automatically add an identification header:

```
X-Mock-Response: true
```

For easy identification of whether the response is from mock.

---

## Use Cases

### Frontend-Backend Collaboration

Backend API not ready during frontend development:

```json
{
  "mockMode": "STATIC",
  "staticMock": {
    "statusCode": 200,
    "body": "{\"users\": [{\"id\": 1, \"name\": \"Alice\"}]}"
  }
}
```

### Error Handling Test

Test frontend error handling logic:

```json
{
  "errorSimulation": {
    "enabled": true,
    "errorRate": 50,
    "errorStatusCodes": [500]
  }
}
```

50% of requests return 500 error.

### Performance Test

Simulate slow network scenarios:

```json
{
  "delay": {
    "enabled": true,
    "networkConditions": "SLOW_3G"
  }
}
```

### API Versioning Mock

Different versions return different responses:

```json
{
  "mockMode": "DYNAMIC",
  "dynamicMock": {
    "conditions": [
      {
        "headerConditions": {"X-Version": "v2"},
        "response": {"statusCode": 200, "body": "{\"version\": \"v2\"}"}
      }
    ],
    "defaultResponse": {"statusCode": 200, "body": "{\"version\": \"v1\"}"}
  }
}
```

---

## API Endpoints

Configure via Strategy API:

```bash
curl -X PUT http://localhost:9090/api/strategies/mock-response \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "test-api",
    "enabled": true,
    "mockMode": "STATIC",
    "staticMock": {
      "statusCode": 200,
      "body": "{\"id\": 1}"
    }
  }'
```

---

## Best Practices

1. **Identify Mock**: Automatically adds `X-Mock-Response: true` header for easy identification
2. **Template Data**: Use `variables` and `extractFromRequest` to simulate real data
3. **Disable Toggle**: Ensure `enabled: false` in production environment
4. **Error Simulation**: Test frontend error handling logic
5. **Delay Testing**: Test timeout and loading states
6. **Pass Through**: Preserve ability to skip mock for testers

---

## Related Features

- [Request Validation](request-validation.md) - Validate mock requests
- [Response Transform](response-transform.md) - Transform mock responses