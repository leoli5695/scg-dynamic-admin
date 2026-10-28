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
│ Cache (-46)     │ (Optional)
└─────────────────┘
       ↓
  Client Response
```

Both orders are negative and below `NettyWriteResponseFilter` (-1), which is what makes a response
decorator see the client-bound body at all. `CACHE` (-46) is installed *inside*
`RESPONSE_TRANSFORM` (-45), so the cache stores the final post-transform body
(`FilterOrderConstants.CACHE` / `.RESPONSE_TRANSFORM`).

---

## Supported Operations

| Operation | Config key | Description |
|-----------|-----------|-------------|
| **Protocol Transform** | `protocolTransform` | Convert between JSON and XML (`sourceFormat`, `targetFormat`, `customContentType`) |
| **Field Filtering** | `fieldMapping.removeFields` | Remove sensitive/internal fields |
| **Field Mapping** | `fieldMapping.mappings` | Copy/rename fields (`transform: COPY\|RENAME`, `sourcePath`, `targetPath`, `defaultValue`) |
| **Field Injection** | `fieldMapping.addFields` | Add constant fields |
| **Response Wrapping** | `fieldMapping.wrapInEnvelope` / `unwrapFromEnvelope` | Wrap into / unwrap from a single-field envelope |
| **Data Masking** | `dataMasking` | Mask string field values (`fieldPath`, `maskType`, `pattern`, `replacement`, `keepLength`, `keepPosition`) |

Execution conditions: `transformOnStatusCodes`, `skipOnContentTypes`, `maxBodySize` (default 10 MB)
and `errorHandling` (default `RETURN_ORIGINAL`) — see `ResponseTransformConfig`.

---

## Configuration

The real config object is `ResponseTransformConfig` (`my-gateway/.../model/ResponseTransformConfig.java`),
carried in the strategy's `config` field:

```json
{
  "enabled": true,
  "protocolTransform": {
    "enabled": false,
    "sourceFormat": "AUTO",
    "targetFormat": "JSON",
    "customContentType": null
  },
  "fieldMapping": {
    "enabled": true,
    "removeFields": ["$.internal_id", "$.debug_info"],
    "mappings": [
      {"sourcePath": "$.user_data", "targetPath": "$.user", "transform": "RENAME"}
    ],
    "addFields": {"$.gateway": "scg"},
    "wrapInEnvelope": null,
    "unwrapFromEnvelope": null
  },
  "dataMasking": {
    "enabled": false,
    "rules": [
      {"fieldPath": "$.phone", "maskType": "PARTIAL", "keepLength": 4, "keepPosition": "END"}
    ]
  },
  "transformOnStatusCodes": [200],
  "skipOnContentTypes": ["image/*", "application/octet-stream"],
  "maxBodySize": 10485760,
  "errorHandling": "RETURN_ORIGINAL"
}
```

> The previously documented `transformRules[]` array with `type: FIELD_REMOVE | FIELD_MAP | JSON_TO_XML`
> and the `responseWrapper{wrapperField, includeMetadata}` object are **not implemented** — no code
> reads those keys. The equivalent capabilities are `fieldMapping.removeFields`,
> `fieldMapping.mappings` and `protocolTransform` / `fieldMapping.wrapInEnvelope` respectively.
> A rule-list engine and an envelope with a generated `metadata` block remain unimplemented design intent.

---

## Transform Operations

### Remove fields (`fieldMapping.removeFields`)

```json
{
  "fieldMapping": {"enabled": true, "removeFields": ["$.internal_id"]}
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

### Map / rename fields (`fieldMapping.mappings`)

`transform` is `COPY` (default, keeps the source) or `RENAME` (removes the source). Paths use
dot notation with an optional `$.` prefix. `defaultValue` is applied as a text node when the source
path is missing.

```json
{
  "fieldMapping": {
    "enabled": true,
    "mappings": [
      {"sourcePath": "$.user_data", "targetPath": "$.user", "transform": "RENAME"}
    ]
  }
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

### Protocol conversion (`protocolTransform`)

```json
{
  "protocolTransform": {"enabled": true, "sourceFormat": "JSON", "targetFormat": "XML"}
}
```

`sourceFormat` accepts `AUTO` (sniffs `{`/`[` as JSON, `<` as XML), `JSON` or `XML`; `targetFormat`
accepts `JSON` or `XML`. There is no per-rule `rootElement` option.

### Envelope wrapping (`fieldMapping.wrapInEnvelope`)

```json
{
  "fieldMapping": {"enabled": true, "wrapInEnvelope": "data"}
}
```

Before (Backend):
```json
{
  "user": {"name": "John"}
}
```

After (Client):
```json
{
  "data": {
    "user": {"name": "John"}
  }
}
```

`unwrapFromEnvelope` takes a dot path (e.g. `response.data`) and replaces the body with that node.
Only one of wrap/unwrap applies per response (wrap is checked first). No `metadata` object
(`transformed`, `timestamp`) is added — that part of the original design is **not implemented**.

### Data masking (`dataMasking`)

```json
{
  "dataMasking": {
    "enabled": true,
    "rules": [
      {"fieldPath": "$.phone", "maskType": "PARTIAL", "replacement": "*", "keepLength": 4, "keepPosition": "END"}
    ]
  }
}
```

Masking only applies to textual field values; non-text fields are left untouched. `maskType` is
`FULL` (whole value → `replacement`), `PARTIAL` (keeps `keepLength` chars at `keepPosition`
`START`/`END`) or `CUSTOM` (regex `pattern` replaced by `replacement`); unknown values behave as `FULL`.

---

## Use Cases

### API Versioning

Map legacy API field names:

```json
{
  "fieldMapping": {
    "enabled": true,
    "mappings": [
      {"sourcePath": "$.userId", "targetPath": "$.id", "transform": "RENAME"},
      {"sourcePath": "$.created_at", "targetPath": "$.createTime", "transform": "RENAME"}
    ]
  }
}
```

### Data Filtering

Remove internal fields:

```json
{
  "fieldMapping": {
    "enabled": true,
    "removeFields": ["$.internal_id", "$._metadata", "$.debug_info"]
  }
}
```

### Legacy System Integration

Modern JSON API to XML system:

```json
{
  "protocolTransform": {"enabled": true, "sourceFormat": "JSON", "targetFormat": "XML"}
}
```

---

## API Endpoints

Configure via the Strategy API. There is **no `/api/strategies/response-transform` path**:
`StrategyController` is `@RequestMapping("/api/strategies")` and identifies a strategy by its
`strategyId` (`PUT /{id}`, `GET /{id}`, `DELETE /{id}`), with `GET /type/{type}` for lookup by type.
The type constant is `StrategyDefinition.TYPE_RESPONSE_TRANSFORM = "RESPONSE_TRANSFORM"`.
`POST`/`PUT`/`DELETE` under `/api/**` require the `ADMIN` authority.

```bash
curl -X PUT http://localhost:9090/api/strategies/external-api-response-transform \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "strategyId": "external-api-response-transform",
    "strategyName": "External API response transform",
    "strategyType": "RESPONSE_TRANSFORM",
    "scope": "ROUTE",
    "routeId": "external-api",
    "enabled": true,
    "config": {
      "enabled": true,
      "fieldMapping": {
        "enabled": true,
        "removeFields": ["$.internal_id"]
      }
    }
  }'
```

Other endpoints: `GET /api/strategies`, `GET /api/strategies/{id}`,
`GET /api/strategies/type/RESPONSE_TRANSFORM`, `GET /api/strategies/global`,
`GET /api/strategies/route/{routeId}`, `DELETE /api/strategies/{id}`,
`POST /api/strategies/{id}/enable`, `POST /api/strategies/{id}/disable`.

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