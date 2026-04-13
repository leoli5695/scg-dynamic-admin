# Response Body Transformation

> 响应体转换功能支持在返回客户端前修改后端响应。

---

## Overview

响应体转换在响应返回前执行：

```
Backend Response
       ↓
┌─────────────────┐
│ Response        │
│ Transform (-45) │
└─────────────────┘
       ↓
┌─────────────────┐
│ Cache (50)      │ (可选)
└─────────────────┘
       ↓
  Client Response
```

---

## Supported Operations

| Operation | Description |
|-----------|-------------|
| **Field Filtering** | 移除敏感/内部字段 |
| **Field Mapping** | 重命名/重组字段 |
| **Format Conversion** | 格式转换（XML → JSON） |
| **Response Wrapping** | 包装成统一格式 |

---

## Configuration

```json
{
  "routeId": "external-api",
  "enabled": true,
  "transformRules": [
    {
      "type": "FIELD_REMOVE",
      "path": "$.internal_id"
    },
    {
      "type": "FIELD_REMOVE",
      "path": "$.debug_info"
    },
    {
      "type": "FIELD_MAP",
      "sourcePath": "$.user_data",
      "targetPath": "$.user"
    },
    {
      "type": "JSON_TO_XML",
      "rootElement": "response"
    }
  ],
  "responseWrapper": {
    "enabled": true,
    "wrapperField": "data",
    "includeMetadata": true
  }
}
```

---

## Transform Rules

### FIELD_REMOVE

删除字段：

```json
{
  "type": "FIELD_REMOVE",
  "path": "$.internal_id"
}
```

Before:
```json
{
  "internal_id": 12345,
  "public_id": "abc",
  "name": "John"
}
```

After:
```json
{
  "public_id": "abc",
  "name": "John"
}
```

### FIELD_MAP

字段映射：

```json
{
  "type": "FIELD_MAP",
  "sourcePath": "$.user_data",
  "targetPath": "$.user"
}
```

Before:
```json
{"user_data": {"name": "John"}}
```

After:
```json
{"user": {"name": "John"}}
```

### JSON_TO_XML

JSON 转 XML：

```json
{
  "type": "JSON_TO_XML",
  "rootElement": "response"
}
```

### Response Wrapper

包装响应：

```json
{
  "responseWrapper": {
    "enabled": true,
    "wrapperField": "data",
    "includeMetadata": true
  }
}
```

Before (Backend):
```json
{
  "internal_id": 12345,
  "user_data": {"name": "John"}
}
```

After (Client):
```json
{
  "data": {
    "user": {"name": "John"}
  },
  "metadata": {
    "transformed": true,
    "timestamp": "2024-01-15T10:30:00Z"
  }
}
```

---

## Use Cases

### API Versioning

旧版本 API 字段名映射：

```json
{
  "transformRules": [
    {"type": "FIELD_MAP", "sourcePath": "$.userId", "targetPath": "$.id"},
    {"type": "FIELD_MAP", "sourcePath": "$.created_at", "targetPath": "$.createTime"}
  ]
}
```

### Data Filtering

移除内部字段：

```json
{
  "transformRules": [
    {"type": "FIELD_REMOVE", "path": "$.internal_id"},
    {"type": "FIELD_REMOVE", "path": "$._metadata"},
    {"type": "FIELD_REMOVE", "path": "$.debug_info"}
  ]
}
```

### Legacy System Integration

现代 JSON API 对接 XML 系统：

```json
{
  "transformRules": [
    {"type": "JSON_TO_XML", "rootElement": "response"}
  ]
}
```

---

## API Endpoints

通过 Strategy API 配置：

```bash
curl -X PUT http://localhost:9090/api/strategies/response-transform \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "external-api",
    "enabled": true,
    "transformRules": [...]
  }'
```

---

## Best Practices

1. **移除敏感数据**：确保不泄露内部信息
2. **字段映射**：保持一致的命名风格
3. **性能考虑**：大响应体转换可能影响延迟
4. **测试验证**：充分测试转换逻辑
5. **日志记录**：记录转换后响应用于调试

---

## Related Features

- [Request Transform](request-transform.md) - 请求转换
- [Response Caching](response-caching.md) - 缓存转换后响应
- [Mock Response](mock-response.md) - Mock 响应