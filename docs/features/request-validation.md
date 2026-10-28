# Request Validation

> Request validation supports JSON Schema validation, required field checking, and type constraints.

---

## Overview

Request validation executes **after authentication and after transformation**
(`AUTHENTICATION = -256` → `REQUEST_TRANSFORM = -255` →
`REQUEST_VALIDATION = -254`, see `FilterOrderConstants`):

```
Request Flow:
  Authentication (-256) → Auth check (signs the original body)
       ↓
  Request Transform (-255) → Modify request body
       ↓
  Request Validation (-254) → Validate schema
```

---

## Validation Types

| Type | Description |
|------|-------------|
| **JSON Schema** | Validate complete structure per specification (`schemaValidation`) |
| **Required Fields** | Check required fields (`fieldValidation.requiredFields`) |
| **Type Constraints** | Field type, range, length and regex validation (`fieldValidation.typeConstraints`) |
| **Enum Constraints** | Allowed-value lists (`fieldValidation.enumConstraints`) |
| **Custom Validators** | SCRIPT / BEAN / HTTP validators (`customValidators`) |

---

## Configuration

The real configuration (`RequestValidationConfig`) consists of
`schemaValidation`, `fieldValidation`, and `customValidators`, selected by
`validationMode` (`SCHEMA` / `CUSTOM` / `HYBRID`, default `HYBRID`).

> **Note**: An earlier design used a flat `validationRules` array with rule
> types `FORMAT` / `RANGE` / `REQUIRED` / `REGEX` plus a top-level `jsonSchema`
> object. That structure is **not implemented** - configuring it has no effect.
> The equivalent capabilities exist as the nested objects below.

```json
{
  "routeId": "user-api",
  "enabled": true,
  "validationMode": "HYBRID",
  "schemaValidation": {
    "enabled": true,
    "schemaSource": "INLINE",
    "inlineSchema": "{\"type\":\"object\",\"properties\":{\"username\":{\"type\":\"string\",\"minLength\":3},\"email\":{\"type\":\"string\",\"format\":\"email\"}},\"required\":[\"username\",\"email\"]}"
  },
  "fieldValidation": {
    "enabled": true,
    "requiredFields": ["username", "email"],
    "typeConstraints": [
      { "fieldPath": "age", "expectedType": "integer", "min": 0, "max": 150,
        "errorMessage": "Age must be between 0 and 150" },
      { "fieldPath": "phone", "expectedType": "string", "pattern": "^\\d{11}$",
        "errorMessage": "Phone must be 11 digits" }
    ],
    "enumConstraints": [
      { "fieldPath": "status", "allowedValues": ["ACTIVE", "DISABLED"] }
    ]
  },
  "customValidators": [
    { "name": "nonEmptyEmail", "fieldPath": "email", "validatorType": "SCRIPT",
      "script": "value != null && value.length() > 0" }
  ]
}
```

Other top-level fields: `validateOnMethods` (default `POST, PUT, PATCH`),
`validateOnContentTypes` (default `application/json`), `stopOnFirstError`
(default `true`), `errorResponse` (`format` STANDARD/DETAILED,
`includeSchemaErrors`, `customMessage`, `statusCode` default 400).

---

## Validation Mechanisms

### Schema Validation

`schemaValidation.schemaSource` selects the schema origin:

- `INLINE` - schema string in `inlineSchema`
- `REF` - `schemaRef` references a schema stored in gateway-admin
- `URL` - `schemaUrl` fetched remotely (cached `schemaCacheTtl` seconds, default 300)

Format checks (`email`, `date`, `uri`, `uuid`, ...) are expressed inside the
JSON Schema itself, not as standalone rules.

### Required Fields

`fieldValidation.requiredFields` is a plain list of field paths:

```json
{ "requiredFields": ["username", "email", "items[*].productId"] }
```

### Type Constraints (range / length / regex)

Each `typeConstraints[]` entry carries `fieldPath`, `expectedType`
(`string`, `number`, `integer`, `boolean`, `array`, `object`), numeric
`min`/`max`, string `minLength`/`maxLength`, regex `pattern`, and an optional
`errorMessage`:

```json
{ "fieldPath": "age", "expectedType": "integer", "min": 0, "max": 150 }
```

```json
{ "fieldPath": "phone", "expectedType": "string", "pattern": "^\\d{11}$" }
```

### Enum Constraints

```json
{ "fieldPath": "status", "allowedValues": ["ACTIVE", "DISABLED"],
  "errorMessage": "Invalid status" }
```

### Custom Validators

`customValidators[]`: `name`, `fieldPath`, `validatorType`
(`SCRIPT` - Groovy/SpEL expression, `BEAN` - Spring bean method, `HTTP` -
external validation service), and `script`.

---

## Error Response

Validation failure returns HTTP status `errorResponse.statusCode` (default
400). With the default `errorResponse.format: DETAILED` the body is:

```json
{
  "error": "Validation Error",
  "message": "Request validation failed",
  "errors": [
    {"field": "email", "message": "Invalid email format"},
    {"field": "age", "message": "Age must be between 0 and 150"}
  ]
}
```

With `format: STANDARD` only `error` and `message` are returned (no `errors`
array). `message` can be overridden via `errorResponse.customMessage`.

---

## API Endpoints

Configure via Strategy API. There is no `PUT /api/strategies/request-validation`
endpoint: create with `POST /api/strategies` (type constant
`REQUEST_VALIDATION`, uppercase), update by strategy ID with
`PUT /api/strategies/{strategyId}`, and list by type with
`GET /api/strategies/type/REQUEST_VALIDATION`. Writes require an ADMIN JWT.

```bash
curl -X POST http://localhost:9090/api/strategies \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -d '{
    "strategyId": "validation-user-api",
    "strategyName": "User API validation",
    "strategyType": "REQUEST_VALIDATION",
    "scope": "ROUTE",
    "routeId": "user-api",
    "enabled": true,
    "config": {
      "fieldValidation": {
        "enabled": true,
        "requiredFields": ["username", "email"]
      }
    }
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