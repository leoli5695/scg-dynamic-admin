# Request Body Transformation

> Request body transformation supports protocol conversion, field mapping, and data masking.

---

## Overview

Request body transformation executes before authentication, modifying request content:

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
| **Field Mapping** | Rename, add, delete fields |
| **Data Masking** | Mask sensitive fields (password, token) |
| **Field Injection** | Inject static or dynamic values |

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

Field mapping/renaming:

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

Data masking:

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

Field injection:

```json
{
  "type": "FIELD_INJECT",
  "path": "$.request_time",
  "value": "${timestamp}"
}
```

Injectable values:
- `${timestamp}` - Current timestamp
- `${request.header.X-Id}` - Request header value
- `${route.id}` - Route ID
- Static strings

### FIELD_REMOVE

Delete field:

```json
{
  "type": "FIELD_REMOVE",
  "path": "$.internal_id"
}
```

### XML_TO_JSON

XML to JSON:

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

JSON to XML:

```json
{
  "type": "JSON_TO_XML",
  "rootElement": "request"
}
```

---

## Use Cases

### Legacy API Integration

Modern API calling legacy system (different field names):

```json
{
  "transformRules": [
    {"type": "FIELD_MAP", "sourcePath": "$.userId", "targetPath": "$.user_id"},
    {"type": "FIELD_MAP", "sourcePath": "$.createTime", "targetPath": "$.created_at"}
  ]
}
```

### Data Sanitization

Remove sensitive fields before forwarding:

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

XML system connecting to JSON API:

```json
{
  "transformRules": [
    {"type": "XML_TO_JSON", "rootElement": "data"}
  ]
}
```

---

## API Endpoints

Configure via Strategy API:

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

1. **Path Expressions**: Use JSONPath format (`$.field`)
2. **Sequential Execution**: Rules execute in configuration order
3. **Performance Considerations**: Large JSON transformations may impact performance
4. **Testing Validation**: Thoroughly test transformation results before production
5. **Logging**: Log content before and after transformation for debugging

---

## Related Features

- [Request Validation](request-validation.md) - Request validation
- [Response Transform](response-transform.md) - Response transformation
- [Mock Response](mock-response.md) - Mock response