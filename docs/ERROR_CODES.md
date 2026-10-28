# Gateway Error Codes

> Gateway unified response format and error code specification

---

## Response Format

### Standard Error Response

Most gateway error responses use the following JSON shape, but it is **not** universal — see the
"Non-unified responses" note below:

```json
{
    "httpStatus": 429,
    "code": 52901,
    "error": "Too Many Requests",
    "message": "Request rate limit exceeded",
    "data": null
}
```

### Field Description

| Field | Type | Description |
|-------|------|-------------|
| `httpStatus` | int | HTTP status code (e.g., 400, 401, 429, 503) |
| `code` | int | Business error code for programmatic handling — **branch on this, not on `error`** |
| `error` | string | See note below: HTTP reason phrase **or** ErrorCode English name depending on the code path |
| `message` | string | Detailed error message |
| `data` | null | Error response always null |

> **`error` field is not a stable identifier.** Two code paths produce it:
> - Responses written directly by `GatewayResponseHelper` set `error` to the **HTTP reason phrase**
>   (`GatewayResponseHelper.java:65`). This covers the rate-limit path (`error` = `"Too Many Requests"`,
>   `:168`), the circuit-breaker path (`error` = `"Service Unavailable"`, `:247`), and the generic
>   `writeForbidden`/`writeBadRequest`/`writeUnauthorized`/`writeNotFound` helpers.
>   `ScgGlobalExceptionHandler.java:304` does the same for non-`GatewayException` errors.
> - Errors thrown as a `GatewayException` (route/service not found, validation, upstream) are rendered
>   via `GatewayException.toErrorMap()`, which sets `error` to the **ErrorCode English name**
>   (`GatewayException.java:68`), e.g. `"Route Not Found"`, `"Service Not Found"`, `"Validation Failed"`.
>
> Consequence: the strings `"Rate Limit Exceeded"` and `"Circuit Breaker Open"` shown in older revisions
> of this document **never appear** in a real response. A client doing `if (data.error === "Rate Limit Exceeded")`
> will silently fail. Always switch on the numeric `code` field instead.

> **Non-unified responses.** Some gateway paths do not use the shape above at all:
> - `SecurityGlobalFilter` body-scan budget exhaustion returns **HTTP 503 with an empty body**
>   (`SecurityGlobalFilter.java:337-338`, `:409-410`).
> - Oversized request body returns **HTTP 413** with `{"error":"PAYLOAD_TOO_LARGE","message":...}`
>   (`SecurityGlobalFilter.java:418-423`) — no `httpStatus`/`code`/`data`.
> - WAF block returns **HTTP 400** with `{"error":"Security threat detected","threats":[...]}`
>   (`SecurityGlobalFilter.java:494-509`) — no `httpStatus`/`code`.
> - License feature gate returns **HTTP 403** with `{code:40303, message, data, timestamp}`
>   (`LicenseFeatureHelper.java:199-208`) — no `httpStatus`/`error`.
> - CORS preflight rejection uses a **bare 3-digit `code`** `403` (`CorsGlobalFilter.java:236`), and
>   `InternalFilterChainController.java:371` returns a bare `404` — both violate the 5-digit code format.

### Extended Fields (Context-specific)

Some errors include additional fields:

```json
// Rate Limit error (written by GatewayResponseHelper.writeRateLimited)
{
    "httpStatus": 429,
    "code": 52901,
    "error": "Too Many Requests",
    "message": "Rate limit exceeded. Limit: 5 requests per 60 s",
    "data": null,
    "retryAfter": "1 s"
}

// Circuit Breaker error (written by GatewayResponseHelper.writeCircuitBreakerOpen)
{
    "httpStatus": 503,
    "code": 55301,
    "error": "Service Unavailable",
    "message": "The circuit breaker is open after backend failures. Requests are temporarily blocked; wait for recovery and check backend health.",
    "data": null,
    "routeId": "user-service-route"
}
```

> **Note:** `limit` and `remaining` are **not** body fields on the 429 response. `writeRateLimited`
> only puts `httpStatus`/`code`/`error`/`message`/`data`/`retryAfter` into the body
> (`GatewayResponseHelper.java:166-171`); `limit` and `remaining` are sent as **response headers**
> (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `:160-161`), and `X-RateLimit-Remaining` is hardcoded
> to `"0"` on the 429 path.

**retryAfter format explanation** (`GatewayResponseHelper.formatRetryAfter`, `:201-210` — note the space
between the number and the unit):
- `"1 s"` - 1 second
- `"60 s"` - 60 seconds
- `"2 min"` - 2 minutes
- `"1 h"` - 1 hour

(The window shown in the `message` is formatted by `formatWindowSize`, `:217-221`, e.g. `60000ms` renders
as `"60 s"`, not `"60000ms"`.)

---

## Error Code Format

Business error codes are plain integers whose **leading digits mirror the HTTP status family**, e.g.
`40001`/`40101`/`40301`/`40401` (4xx client errors), `50001`/`50201`/`50301`/`50401` (5xx server errors),
and gateway-specific `529xx` (rate limiting) / `550xx` / `553xx`. There is **no `GW` prefix** and no
`{Category}{Sequence}` layout in the actual enum.

> **Known defect:** the javadoc in `ErrorCode.java:11-13` still describes the format as
> `GW{Category}{Sequence}` with `Category 1=Client / 2=Server / 3=Gateway`. That description does not
> match the enum values (which start with `4`/`5`) and should be corrected in code as well.

---

## Client Errors (4xx)

### 400 - Bad Request

| Code | Error | Description |
|------|-------|-------------|
| 40001 | Bad Request | Invalid request parameters |
| 40002 | Invalid Parameter | Parameter validation failed |
| 40003 | Missing Parameter | **Dead code** — `ErrorCode.MISSING_PARAMETER` has zero references in main code |

### 401 - Unauthorized

| Code | Error | Description |
|------|-------|-------------|
| 40101 | Unauthorized | Unauthorized access |
| 40102 | Invalid Token | Invalid token |
| 40103 | Token Expired | Token has expired |
| 40104 | Invalid Credentials | Invalid authentication credentials |
| 40105 | Invalid API Key | Invalid API key |
| 40106 | Invalid Signature | Signature verification failed (HMAC) |

### 403 - Forbidden

| Code | Error | Description |
|------|-------|-------------|
| 40301 | Forbidden | Access forbidden (also emitted by IP blocking and AI content guard — see notes) |
| 40302 | Access Denied | **Dead code** — `ErrorCode.ACCESS_DENIED` has zero references in main code |
| 40303 | IP Blocked | **Not used for IP blocking** — see notes below |
| 40304 | Rate Limited | **Dead code**, and mis-grouped here — `ErrorCode.java:40` maps it to `HttpStatus.TOO_MANY_REQUESTS` (429), not 403 |

> **40303 clarification.** `ErrorCode.IP_BLOCKED` (40303) has **zero references** in main code. Actual IP
> blocking goes through `IPFilterGlobalFilter.java:47` → `GatewayResponseHelper.writeForbidden` →
> `ErrorCode.FORBIDDEN` = **40301** with `error` = `"Forbidden"`. The value `40303` is instead reused by
> `LicenseFeatureHelper.java:199-208` to mean "current license edition does not include this feature"; that
> body contains only `code`/`message`/`data`/`timestamp` (no `httpStatus`, no `error`).
>
> **40301 has two `error` strings.** `writeForbidden` emits `error` = `"Forbidden"`, while the AI content
> guard (`AiSecurityGuardFilter.java:188-192`) emits `code` 40301 with `error` = `"Content Blocked"` plus a
> `categories` array.

### 404 - Not Found

| Code | Error | Description |
|------|-------|-------------|
| 40401 | Not Found | Resource not found |
| 40402 | Route Not Found | Route not found |
| 40403 | Service Not Found | Service not found |

### 405 - Method Not Allowed

| Code | Error | Description |
|------|-------|-------------|
| 40501 | Method Not Allowed | **Dead code** — `ErrorCode.METHOD_NOT_ALLOWED` has zero references in main code |

### 422 - Unprocessable Entity

| Code | Error | Description |
|------|-------|-------------|
| 42201 | Validation Failed | Data validation failed |
| 42202 | Invalid Request Body | Invalid request body format |
| 42203 | Schema Validation Failed | Schema validation failed |
| 42204 | XSS Attack Detected | **Dead code** — see note below |
| 42205 | SQL Injection Detected | **Dead code** — see note below |

> **42204 / 42205 are never emitted, and are mis-grouped under 422.** The factory methods
> `ValidationException.xssDetected()` (`ValidationException.java:57`) and `sqlInjectionDetected()`
> (`:64`) have zero callers in main code. The real WAF response is emitted by `SecurityGlobalFilter.java:494-509`
> as **HTTP 400** with body `{"error":"Security threat detected","threats":[...]}` (no `code`/`httpStatus`).
> Furthermore `ErrorCode.java:54-55` maps both 42204 and 42205 to `HttpStatus.BAD_REQUEST` (400), which
> contradicts their placement in this 422 section.

---

## Server Errors (5xx)

### 500 - Internal Server Error

| Code | Error | Description |
|------|-------|-------------|
| 50001 | Internal Server Error | Internal server error |
| 50002 | Configuration Error | Configuration error |
| 50003 | Serialization Error | Serialization error |

### 502 - Bad Gateway

| Code | Error | Description |
|------|-------|-------------|
| 50201 | Upstream Error | Upstream service error |

### 503 - Service Unavailable

| Code | Error | Description |
|------|-------|-------------|
| 50301 | Service Unavailable | Service unavailable |
| 50302 | No Healthy Instances | No available service instances |
| 50303 | Connection Refused | Connection refused |
| 55301 | Circuit Breaker Open | Circuit breaker is open — but the emitted `error` field is `"Service Unavailable"` (see 553xx note) |

### 504 - Gateway Timeout

| Code | Error | Description |
|------|-------|-------------|
| 50401 | Gateway Timeout | Gateway timeout |
| 50402 | Upstream Timeout | Upstream service timeout |

---

## Gateway Specific Errors (5xx)

### Rate Limiting (529xx)

| Code | HTTP Status | Error (`error` field) | Description | Extra Fields |
|------|-------------|-------|-------------|--------------|
| 52901 | 429 | `Too Many Requests` | Request rate limit exceeded | `retryAfter` in body; `limit`/`remaining` are **headers only** |
| 52902 | 429 | `Token Quota Exceeded` | Token quota pre-check rejected the request | `tenantId`, `exceededType` |
| 52903 | 429 | `Token Window Rate Limited` | Token window rate limit exceeded | `tenantId`, `exceededType` |

> **52901:** emitted by `GatewayResponseHelper.writeRateLimited` (`:154-177`). `error` is the reason phrase
> `"Too Many Requests"`, **not** `"Rate Limit Exceeded"`. Body has only `retryAfter` as an extra field; there
> are **no `limit`/`remaining` body fields** — those exist only as `X-RateLimit-Limit` / `X-RateLimit-Remaining`
> headers (`:160-161`).
>
> **52902 / 52903:** emitted by `TokenQuotaPreCheckFilter.java:534-541` and `:645` respectively — **not** by
> `ErrorCode.BURST_LIMIT_EXCEEDED`, which has zero references in main code. The old "Burst Limit Exceeded"
> label and its `limit`/`remaining`/`retryAfter` fields never appear; the real body carries `error`
> `"Token Quota Exceeded"` (52902) / `"Token Window Rate Limited"` (52903) plus `tenantId` and `exceededType`.

> **Related payload code — 41301.** `TokenQuotaPreCheckFilter.java:189` emits **HTTP 413** with
> `code` 41301 and `error` `"Payload Too Large"` when the request body exceeds the token-quota estimate.
> This code is not defined in `ErrorCode.java` and was previously undocumented.

### Circuit Breaker (553xx)

| Code | HTTP Status | Error (`error` field) | Description | Extra Fields |
|------|-------------|-------|-------------|--------------|
| 55301 | 503 | `Service Unavailable` | Circuit breaker is open | `routeId` |

> Emitted by `GatewayResponseHelper.writeCircuitBreakerOpen` (`:240-247`), called from
> `CircuitBreakerGlobalFilter.java:87`. The `error` field is the reason phrase `"Service Unavailable"`,
> **not** `"Circuit Breaker Open"`. `message` comes from `GatewayErrorMessages.forCode(CIRCUIT_BREAKER_OPEN)`.

### Transform Errors (550xx) — dead codes

| Code | HTTP Status | Error | Description |
|------|-------------|-------|-------------|
| 55001 | 500 | Request Transform Error | **Dead code** — zero references in main code |
| 55002 | 500 | Response Transform Error | **Dead code** — zero references in main code |

### Cache Errors (550xx) — dead codes

| Code | HTTP Status | Error | Description |
|------|-------------|-------|-------------|
| 55003 | 500 | Cache Error | **Dead code** — zero references in main code |

### SSL/TLS Errors (550xx) — dead codes

| Code | HTTP Status | Error | Description |
|------|-------------|-------|-------------|
| 55004 | 500 | SSL Error | **Dead code** — zero references in main code |
| 55005 | 500 | Certificate Expired | **Dead code** — zero references in main code |

> The entire 550xx block (`REQUEST_TRANSFORM_ERROR`, `RESPONSE_TRANSFORM_ERROR`, `CACHE_ERROR`, `SSL_ERROR`,
> `CERTIFICATE_EXPIRED`) is defined in `ErrorCode.java` but has **zero references** in main code and is never
> emitted.

---

## Rate Limit Response Headers

Rate limit responses include standard HTTP headers:

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Rate limit threshold (request count) |
| `X-RateLimit-Remaining` | Remaining available requests (hardcoded to `"0"` on the 429 path, `GatewayResponseHelper.java:161`) |
| `Retry-After` | Retry wait time (seconds) |

---

## Usage Examples

### Frontend Error Handling

```javascript
async function handleResponse(response) {
    const data = await response.json();
    
    // Check httpStatus first
    if (data.httpStatus >= 400) {
        // Use code for programmatic handling
        switch (data.code) {
            case 52901:  // Rate limit
                console.log(`Rate limited, retry after ${data.retryAfter}`);
                break;
            case 40101:  // Unauthorized
                console.log('Please login again');
                break;
            default:
                console.log(data.message);
        }
    }
}
```

### Java Error Handling

```java
// Using ErrorCode enum
ErrorCode error = ErrorCode.fromCode(response.getCode());
switch (error) {
    case RATE_LIMIT_EXCEEDED:
        // Handle rate limit
        break;
    case UNAUTHORIZED:
        // Handle unauthorized
        break;
    default:
        // Handle other errors
}
```

---

## Implementation Files

| File | Description |
|------|-------------|
| `ErrorCode.java` | Error code enum definition |
| `GatewayErrorMessages.java` | **Actual source of the `message` text** — `forCode(ErrorCode)` returns long, guidance-style sentences (e.g. `:50` for `TOKEN_EXPIRED`), not the short phrases shown in the tables above |
| `GatewayException.java` | Gateway exception base class (`toErrorMap()` sets `error` = ErrorCode English name) |
| `RateLimitException.java` | Rate limit exception (**never thrown in main code**; its `toErrorMap` would add `limit`/`remaining`/`retryAfter` but the live path is `GatewayResponseHelper.writeRateLimited`) |
| `GatewayResponseHelper.java` | Response builder utility (sets `error` = HTTP reason phrase) |
| `ScgGlobalExceptionHandler.java` | Global exception handler |