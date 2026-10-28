# Request Body Transformation

> Request body transformation supports protocol conversion, field mapping, and data masking.

---

## Overview

Request body transformation executes **after** authentication, so HMAC body
signatures cover the original wire payload (`AUTHENTICATION = -256` runs before
`REQUEST_TRANSFORM = -255`, see `FilterOrderConstants`):

```
Request Flow:
  Authentication (-256) → Auth check (signs the original body)
       ↓
  Request Transform (-255) → Modify request body
       ↓
  Request Validation (-254) → Validate schema
```

---

## Supported Operations

| Operation | Description |
|-----------|-------------|
| **Protocol Conversion** | JSON ↔ XML (`protocolTransform`) |
| **Field Mapping** | Rename / copy fields (`fieldMapping.mappings`, RENAME/COPY) |
| **Field Removal** | Delete fields (`fieldMapping.removeFields`) |
| **Data Masking** | Mask sensitive fields (`dataMasking.rules`: password, token) |
| **Field Injection** | Inject static values (`fieldMapping.addFields`) |

---

## Configuration

The real configuration (`RequestTransformConfig`) consists of three nested
objects: `protocolTransform`, `fieldMapping`, and `dataMasking`.

> **Note**: An earlier design used a flat `transformRules` array with rule
> types `FIELD_MAP` / `FIELD_MASK` / `FIELD_INJECT` / `FIELD_REMOVE` /
> `XML_TO_JSON`. That structure is **not implemented** - configuring it has no
> effect. The equivalent capabilities exist as the nested objects below.

```json
{
  "routeId": "legacy-api",
  "enabled": true,
  "protocolTransform": {
    "enabled": true,
    "sourceFormat": "XML",
    "targetFormat": "JSON"
  },
  "fieldMapping": {
    "enabled": true,
    "mappings": [
      { "sourcePath": "user_name", "targetPath": "username", "transform": "RENAME" }
    ],
    "removeFields": ["debug_info"],
    "addFields": { "channel": "open-api" }
  },
  "dataMasking": {
    "enabled": true,
    "rules": [
      { "fieldPath": "password", "maskType": "FULL", "replacement": "***" }
    ]
  }
}
```

Other top-level fields: `transformOnContentTypes`, `skipOnMethods`,
`maxBodySize` (default 1 MB), `validateAfterTransform`.

---

## Transform Mechanisms

### Field Mapping (RENAME / COPY)

`fieldMapping.mappings[]` entries carry `sourcePath`, `targetPath`,
`transform` (`RENAME` keeps no original field, `COPY` keeps both) and an
optional `defaultValue` used when the source field is missing. Paths use
simple dot notation (e.g. `user.name`, `data.items.id`).

```json
{
  "fieldMapping": {
    "enabled": true,
    "mappings": [
      { "sourcePath": "user_name", "targetPath": "username", "transform": "RENAME" }
    ]
  }
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

### Field Removal

`fieldMapping.removeFields[]` deletes fields from the body:

```json
{
  "fieldMapping": {
    "enabled": true,
    "removeFields": ["internal_id"]
  }
}
```

### Field Injection (addFields)

`fieldMapping.addFields` is a `path → value` map of fields added to the body.
Values are inserted **as static strings**; the previously documented dynamic
expressions (`${timestamp}`, `${request.header.X-Id}`, `${route.id}`) are
**not implemented**.

```json
{
  "fieldMapping": {
    "enabled": true,
    "addFields": { "channel": "open-api" }
  }
}
```

### Data Masking

`dataMasking.rules[]` (`MaskingRule`): `fieldPath`, `maskType`
(`FULL` / `PARTIAL` / `CUSTOM`), `replacement` (default `"***"`),
`pattern` (regex for CUSTOM), and for PARTIAL: `keepLength` / `keepPosition`
(`START` / `END`).

```json
{
  "dataMasking": {
    "enabled": true,
    "rules": [
      { "fieldPath": "password", "maskType": "FULL", "replacement": "***" }
    ]
  }
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

### Protocol Conversion (XML ↔ JSON)

Handled by `protocolTransform`, not by `XML_TO_JSON` / `JSON_TO_XML` rule
types (which are not implemented). Fields: `sourceFormat` (`JSON`, `XML`,
`FORM`), `targetFormat` (`JSON`, `XML`), `preserveOriginalContentType`,
`customContentType`.

XML to JSON:

```json
{
  "protocolTransform": {
    "enabled": true,
    "sourceFormat": "XML",
    "targetFormat": "JSON"
  }
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

---

## Use Cases

### Legacy API Integration

Modern API calling legacy system (different field names):

```json
{
  "fieldMapping": {
    "enabled": true,
    "mappings": [
      { "sourcePath": "userId", "targetPath": "user_id", "transform": "RENAME" },
      { "sourcePath": "createTime", "targetPath": "created_at", "transform": "RENAME" }
    ]
  }
}
```

### Data Sanitization

Remove sensitive fields before forwarding:

```json
{
  "fieldMapping": {
    "enabled": true,
    "removeFields": ["password", "token"]
  },
  "dataMasking": {
    "enabled": true,
    "rules": [
      { "fieldPath": "credit_card", "maskType": "FULL", "replacement": "****" }
    ]
  }
}
```

### Protocol Bridge

XML system connecting to JSON API:

```json
{
  "protocolTransform": {
    "enabled": true,
    "sourceFormat": "XML",
    "targetFormat": "JSON"
  }
}
```

---

## API Endpoints

Configure via Strategy API. There is no `PUT /api/strategies/request-transform`
endpoint: create with `POST /api/strategies` (type constant `REQUEST_TRANSFORM`,
uppercase), update by strategy ID with `PUT /api/strategies/{strategyId}`, and
list by type with `GET /api/strategies/type/REQUEST_TRANSFORM`. Writes require
an ADMIN JWT.

```bash
curl -X POST http://localhost:9090/api/strategies \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -d '{
    "strategyId": "transform-legacy-api",
    "strategyName": "Legacy API transform",
    "strategyType": "REQUEST_TRANSFORM",
    "scope": "ROUTE",
    "routeId": "legacy-api",
    "enabled": true,
    "config": {
      "fieldMapping": {
        "enabled": true,
        "mappings": [
          { "sourcePath": "user_name", "targetPath": "username", "transform": "RENAME" }
        ]
      }
    }
  }'
```

---

## Best Practices

1. **Path Expressions**: Use simple dot notation (`user.name`, `data.items.id`)
2. **Sequential Execution**: Rules execute in configuration order
3. **Performance Considerations**: Large JSON transformations may impact performance
4. **Testing Validation**: Thoroughly test transformation results before production
5. **Logging**: Log content before and after transformation for debugging

---

## Related Features

- [Request Validation](request-validation.md) - Request validation
- [Response Transform](response-transform.md) - Response transformation
- [Mock Response](mock-response.md) - Mock response