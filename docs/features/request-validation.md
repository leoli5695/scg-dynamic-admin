# Request Validation

> 请求验证功能支持 JSON Schema 验证、必填字段检查、类型约束。

---

## Overview

请求验证在转换之后、认证之前执行：

```
Request Flow:
  Request Transform (-255) → Modify request body
       ↓
  Request Validation (-254) → Validate schema
       ↓
  Authentication (-250) → Auth check
```

---

## Validation Types

| Type | Description |
|------|-------------|
| **JSON Schema** | 按规范验证完整结构 |
| **Required Fields** | 检查必填字段 |
| **Type Constraints** | 字段类型验证 |
| **Format Validation** | 格式验证（email、date） |
| **Range Validation** | 数值范围验证 |

---

## Configuration

```json
{
  "routeId": "user-api",
  "enabled": true,
  "validationRules": [
    {
      "path": "$.email",
      "type": "FORMAT",
      "format": "email",
      "errorMessage": "Invalid email format"
    },
    {
      "path": "$.age",
      "type": "RANGE",
      "min": 0,
      "max": 150,
      "errorMessage": "Age must be between 0 and 150"
    },
    {
      "path": "$.username",
      "type": "REQUIRED",
      "errorMessage": "Username is required"
    },
    {
      "path": "$.phone",
      "type": "REGEX",
      "pattern": "^\\d{11}$",
      "errorMessage": "Phone must be 11 digits"
    }
  ],
  "requiredFields": ["username", "email"],
  "jsonSchema": {
    "type": "object",
    "properties": {
      "username": {"type": "string", "minLength": 3},
      "email": {"type": "string", "format": "email"}
    },
    "required": ["username", "email"]
  }
}
```

---

## Validation Rules

### FORMAT

格式验证：

```json
{
  "path": "$.email",
  "type": "FORMAT",
  "format": "email"
}
```

支持格式：
- `email` - 邮箱地址
- `date` - ISO 日期格式
- `uri` - URL 格式
- `uuid` - UUID 格式

### RANGE

数值范围：

```json
{
  "path": "$.age",
  "type": "RANGE",
  "min": 0,
  "max": 150
}
```

### REQUIRED

必填字段：

```json
{
  "path": "$.username",
  "type": "REQUIRED"
}
```

### REGEX

正则表达式：

```json
{
  "path": "$.phone",
  "type": "REGEX",
  "pattern": "^\\d{11}$"
}
```

### JSON_SCHEMA

完整 JSON Schema：

```json
{
  "jsonSchema": {
    "type": "object",
    "properties": {
      "username": {"type": "string", "minLength": 3, "maxLength": 50},
      "age": {"type": "integer", "minimum": 0, "maximum": 150}
    },
    "required": ["username"]
  }
}
```

---

## Error Response

验证失败返回：

```json
{
  "status": 400,
  "error": "Validation Failed",
  "details": [
    {"field": "email", "message": "Invalid email format"},
    {"field": "age", "message": "Age must be between 0 and 150"}
  ]
}
```

---

## API Endpoints

通过 Strategy API 配置：

```bash
curl -X PUT http://localhost:9090/api/strategies/request-validation \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "user-api",
    "enabled": true,
    "validationRules": [...]
  }'
```

---

## Best Practices

1. **明确错误信息**：提供清晰的验证错误提示
2. **JSON Schema**：复杂结构使用 JSON Schema
3. **必填优先**：先验证必填字段
4. **性能优化**：避免过度复杂的正则
5. **国际化**：支持多语言错误信息

---

## Related Features

- [Request Transform](request-transform.md) - 请求转换
- [Mock Response](mock-response.md) - Mock 响应（测试验证规则）