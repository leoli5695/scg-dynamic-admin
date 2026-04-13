# Request Body Transformation

> 请求体转换功能支持协议转换、字段映射、数据脱敏。

---

## Overview

请求体转换在认证之前执行，修改请求内容：

```
Request Flow:
  ...
  Request Transform (-255) → Modify request body
       ↓
  Request Validation (-254) → Validate schema
       ↓
  Authentication (-250) → Auth check
```

---

## Supported Operations

| Operation | Description |
|-----------|-------------|
| **Protocol Conversion** | JSON ↔ XML |
| **Field Mapping** | 重命名、添加、删除字段 |
| **Data Masking** | 脱敏敏感字段（密码、token） |
| **Field Injection** | 注入静态或动态值 |

---

## Configuration

```json
{
  "routeId": "legacy-api",
  "enabled": true,
  "transformRules": [
    {
      "type": "FIELD_MAP",
      "sourcePath": "$.user_name",
      "targetPath": "$.username"
    },
    {
      "type": "FIELD_MASK",
      "path": "$.password",
      "maskChar": "***"
    },
    {
      "type": "FIELD_INJECT",
      "path": "$.request_time",
      "value": "${timestamp}"
    },
    {
      "type": "FIELD_REMOVE",
      "path": "$.debug_info"
    },
    {
      "type": "XML_TO_JSON",
      "rootElement": "request"
    }
  ]
}
```

---

## Transform Rules

### FIELD_MAP

字段映射/重命名：

```json
{
  "type": "FIELD_MAP",
  "sourcePath": "$.user_name",
  "targetPath": "$.username"
}
```

Input:
```json
{"user_name": "john"}
```

Output:
```json
{"username": "john"}
```

### FIELD_MASK

数据脱敏：

```json
{
  "type": "FIELD_MASK",
  "path": "$.password",
  "maskChar": "***"
}
```

Input:
```json
{"password": "secret123"}
```

Output:
```json
{"password": "***"}
```

### FIELD_INJECT

字段注入：

```json
{
  "type": "FIELD_INJECT",
  "path": "$.request_time",
  "value": "${timestamp}"
}
```

注入的值：
- `${timestamp}` - 当前时间戳
- `${request.header.X-Id}` - 请求头值
- `${route.id}` - 路由 ID
- 静态字符串

### FIELD_REMOVE

删除字段：

```json
{
  "type": "FIELD_REMOVE",
  "path": "$.internal_id"
}
```

### XML_TO_JSON

XML 转 JSON：

```json
{
  "type": "XML_TO_JSON",
  "rootElement": "request"
}
```

Input:
```xml
<request>
  <user_name>john</user_name>
  <age>30</age>
</request>
```

Output:
```json
{"user_name": "john", "age": 30}
```

### JSON_TO_XML

JSON 转 XML：

```json
{
  "type": "JSON_TO_XML",
  "rootElement": "request"
}
```

---

## Use Cases

### Legacy API Integration

现代 API 调用遗留系统（字段名不同）：

```json
{
  "transformRules": [
    {"type": "FIELD_MAP", "sourcePath": "$.userId", "targetPath": "$.user_id"},
    {"type": "FIELD_MAP", "sourcePath": "$.createTime", "targetPath": "$.created_at"}
  ]
}
```

### Data Sanitization

移除敏感字段后转发：

```json
{
  "transformRules": [
    {"type": "FIELD_REMOVE", "path": "$.password"},
    {"type": "FIELD_REMOVE", "path": "$.token"},
    {"type": "FIELD_MASK", "path": "$.credit_card", "maskChar": "****"}
  ]
}
```

### Protocol Bridge

XML 系统对接 JSON API：

```json
{
  "transformRules": [
    {"type": "XML_TO_JSON", "rootElement": "data"}
  ]
}
```

---

## API Endpoints

通过 Strategy API 配置：

```bash
curl -X PUT http://localhost:9090/api/strategies/request-transform \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "legacy-api",
    "enabled": true,
    "transformRules": [...]
  }'
```

---

## Best Practices

1. **路径表达式**：使用 JSONPath 格式 (`$.field`)
2. **顺序执行**：规则按配置顺序执行
3. **性能考虑**：大 JSON 转换可能影响性能
4. **测试验证**：在生产前充分测试转换结果
5. **日志记录**：记录转换前后内容用于调试

---

## Related Features

- [Request Validation](request-validation.md) - 请求验证
- [Response Transform](response-transform.md) - 响应转换
- [Mock Response](mock-response.md) - Mock 响应