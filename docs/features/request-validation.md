# Request Validation

> Request validation supports JSON Schema validation, required field checking, and type constraints.

---

## Overview

Request validation executes after transformation, before authentication:

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
| **JSON Schema** | Validate complete structure per specification |
| **Required Fields** | Check required fields |
| **Type Constraints** | Field type validation |
| **Format Validation** | Format validation (email, date) |
| **Range Validation** | Numeric range validation |

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

Format validation:

```json
{
  "path": "$.email",
  "type": "FORMAT",
  "format": "email"
}
```

Supported formats:
- `email` - Email address
- `date` - ISO date format
- `uri` - URL format
- `uuid` - UUID format

### RANGE

Numeric range:

```json
{
  "path": "$.age",
  "type": "RANGE",
  "min": 0,
  "max": 150
}
```

### REQUIRED

Required field:

```json
{
  "path": "$.username",
  "type": "REQUIRED"
}
```

### REGEX

Regular expression:

```json
{
  "path": "$.phone",
  "type": "REGEX",
  "pattern": "^\\d{11}$"
}
```

### JSON_SCHEMA

Complete JSON Schema:

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

Validation failure returns:

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

Configure via Strategy API:

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

1. **Clear Error Messages**: Provide clear validation error messages
2. **JSON Schema**: Use JSON Schema for complex structures
3. **Required First**: Validate required fields first
4. **Performance Optimization**: Avoid overly complex regular expressions
5. **Internationalization**: Support multi-language error messages

---

## Related Features

- [Request Transform](request-transform.md) - Request transformation
- [Mock Response](mock-response.md) - Mock response (test validation rules)