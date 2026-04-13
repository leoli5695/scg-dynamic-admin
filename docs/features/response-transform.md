# Response Body Transformation

> Response body transformation supports modifying backend responses before returning them to clients.

---

## Overview

Response body transformation executes before the response is returned:

```
Backend Response
       ↓
┌─────────────────┐
│ Response        │
│ Transform (-45) │
└─────────────────┘
       ↓
┌─────────────────┐
│ Cache (50)      │ (Optional)
└─────────────────┘
       ↓
  Client Response
```

---

## Supported Operations

| Operation | Description |
|-----------|-------------|
| **Field Filtering** | Remove sensitive/internal fields |
| **Field Mapping** | Rename/restructure fields |
| **Format Conversion** | Format conversion (XML → JSON) |
| **Response Wrapping** | Wrap into unified format |

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

Remove fields:

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

Field mapping:

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

Convert JSON to XML:

```json
{
  "type": "JSON_TO_XML",
  "rootElement": "response"
}
```

### Response Wrapper

Wrap response:

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

Map legacy API field names:

```json
{
  "transformRules": [
    {"type": "FIELD_MAP", "sourcePath": "$.userId", "targetPath": "$.id"},
    {"type": "FIELD_MAP", "sourcePath": "$.created_at", "targetPath": "$.createTime"}
  ]
}
```

### Data Filtering

Remove internal fields:

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

Modern JSON API to XML system:

```json
{
  "transformRules": [
    {"type": "JSON_TO_XML", "rootElement": "response"}
  ]
}
```

---

## API Endpoints

Configure via Strategy API:

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

1. **Remove Sensitive Data**: Ensure internal information is not leaked
2. **Field Mapping**: Maintain consistent naming conventions
3. **Performance Considerations**: Large response body transformations may affect latency
4. **Testing & Validation**: Thoroughly test transformation logic
5. **Logging**: Log transformed responses for debugging

---

## Related Features

- [Request Transform](request-transform.md) - Request transformation
- [Response Caching](response-caching.md) - Cache transformed responses
- [Mock Response](mock-response.md) - Mock responses