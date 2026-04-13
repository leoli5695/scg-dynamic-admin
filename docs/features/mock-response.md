# Mock Response

> Mock 响应功能支持返回静态/动态/模板响应，用于前后端协作和测试。

---

## Overview

Mock 响应在认证之后执行，可跳过后端调用：

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
| **STATIC** | 固定响应体 | 简单 Mock |
| **DYNAMIC** | 按条件选择响应 | 模拟不同场景 |
| **TEMPLATE** | 模板引擎生成响应 | 模拟真实数据结构 |

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

### Template Mock (Handlebars)

```json
{
  "routeId": "test-api",
  "enabled": true,
  "mockMode": "TEMPLATE",
  "templateMock": {
    "templateEngine": "HANDLEBARS",
    "template": "{\"id\": \"{{id}}\", \"name\": \"{{name}}\", \"email\": \"{{email}}\"}",
    "variables": {
      "id": "{{random.uuid}}",
      "name": "{{random.name}}",
      "email": "{{random.email}}"
    },
    "extractFromRequest": [
      {
        "source": "PATH",
        "name": "userId",
        "expression": "/users/{id}"
      }
    ]
  }
}
```

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
          "body": "{\"version\": \"v2\", \"data\": {...}}"
        }
      },
      {
        "matchType": "QUERY",
        "queryConditions": {
          "preview": "true"
        },
        "response": {
          "statusCode": 200,
          "body": "{\"preview\": true, \"data\": {...}}"
        }
      }
    ],
    "defaultResponse": {
      "statusCode": 200,
      "body": "{\"version\": \"v1\", \"data\": {...}}"
    }
  }
}
```

### Error Simulation

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

10% 的请求返回模拟错误（500/503/504 随机）。

### Delayed Mock

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
    "networkConditions": "3G"
  }
}
```

### Pass Through (Bypass Mock)

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

携带特定 Header 或 Query 参数时跳过 Mock，转发到真实后端。

---

## Template Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `{{random.uuid}}` | 随机 UUID | `a1b2c3d4-e5f6-...` |
| `{{random.name}}` | 随机姓名 | `John Doe` |
| `{{random.email}}` | 随机邮箱 | `john@example.com` |
| `{{random.int}}` | 随机整数 | `12345` |
| `{{timestamp}}` | 当前时间戳 | `2024-01-15T10:30:00Z` |
| `{{request.header.X-Id}}` | 请求头值 | 从请求获取 |
| `{{request.path}}` | 请求路径 | `/api/users` |

---

## Delay Presets

| Network Condition | Typical Latency |
|-------------------|-----------------|
| `FAST` | 固定延迟 |
| `4G` | +0-100ms 随机 |
| `3G` | +300-800ms |
| `SLOW_3G` | +1000-3000ms |

---

## Use Cases

### Frontend-Backend Collaboration

前端开发时后端 API 未就绪：

```json
{
  "mockMode": "TEMPLATE",
  "templateMock": {
    "template": "{\"users\": [{{#each users}},{\"id\": \"{{this.id}}\", \"name\": \"{{this.name}}\"}{{/each}}]}"
  }
}
```

### Error Handling Test

测试前端错误处理逻辑：

```json
{
  "errorSimulation": {
    "enabled": true,
    "errorRate": 50,
    "errorStatusCodes": [500]
  }
}
```

50% 的请求返回 500 错误。

### Performance Test

模拟慢网络场景：

```json
{
  "delay": {
    "enabled": true,
    "networkConditions": "SLOW_3G"
  }
}
```

### API Versioning Mock

不同版本返回不同响应：

```json
{
  "mockMode": "DYNAMIC",
  "dynamicMock": {
    "conditions": [
      {
        "matchType": "HEADER",
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

通过 Strategy API 配置：

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

1. **标识 Mock**：添加 `X-Mock: true` Header 便于识别
2. **模板数据**：使用模板变量模拟真实数据结构
3. **禁用切换**：生产环境确保禁用 Mock 或设置 `enabled: false`
4. **错误模拟**：测试前端错误处理逻辑
5. **延迟测试**：测试超时和加载状态
6. **Pass Through**：为测试人员保留跳过 Mock 的能力

---

## Related Features

- [Request Validation](request-validation.md) - 验证 Mock 请求
- [Response Transform](response-transform.md) - 转换 Mock 响应