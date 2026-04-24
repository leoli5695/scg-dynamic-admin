# Gateway Error Codes

> Gateway unified response format and error code specification

---

## Response Format

### Standard Error Response

All gateway error responses use a unified JSON format:

```json
{
    "httpStatus": 429,
    "code": 52901,
    "error": "Rate Limit Exceeded",
    "message": "Request rate limit exceeded",
    "data": null
}
```

### Field Description

| Field | Type | Description |
|-------|------|-------------|
| `httpStatus` | int | HTTP status code (e.g., 400, 401, 429, 503) |
| `code` | int | Business error code for programmatic handling |
| `error` | string | Error type (short English description) |
| `message` | string | Detailed error message |
| `data` | null | Error response always null |

### Extended Fields (Context-specific)

Some errors include additional fields:

```json
// Rate Limit error
{
    "httpStatus": 429,
    "code": 52901,
    "error": "Rate Limit Exceeded",
    "message": "Rate limit exceeded. Limit: 5 requests per 60000ms",
    "data": null,
    "limit": 5,
    "remaining": 0,
    "retryAfter": "1s"
}

// Circuit Breaker error
{
    "httpStatus": 503,
    "code": 55301,
    "error": "Circuit Breaker Open",
    "message": "Circuit breaker is open, please try again later",
    "data": null,
    "routeId": "user-service-route"
}
```

**retryAfter format explanation:**
- `"1s"` - 1 second
- `"60s"` - 60 seconds
- `"2min"` - 2 minutes
- `"1h"` - 1 hour

---

## Error Code Format

Business error code format: `GW{Category}{Sequence}`

- **Category**: Error category
  - `1` = Client Error (4xx)
  - `2` = Server Error (5xx)
  - `3` = Gateway Error (5xx)
- **Sequence**: Sequence number 001-999

---

## Client Errors (4xx)

### 400 - Bad Request

| Code | Error | Description |
|------|-------|-------------|
| 40001 | Bad Request | Invalid request parameters |
| 40002 | Invalid Parameter | Parameter validation failed |
| 40003 | Missing Parameter | Required parameter missing |

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
| 40301 | Forbidden | Access forbidden |
| 40302 | Access Denied | Insufficient permissions |
| 40303 | IP Blocked | IP address blocked |
| 40304 | Rate Limited | Too many requests |

### 404 - Not Found

| Code | Error | Description |
|------|-------|-------------|
| 40401 | Not Found | Resource not found |
| 40402 | Route Not Found | Route not found |
| 40403 | Service Not Found | Service not found |

### 405 - Method Not Allowed

| Code | Error | Description |
|------|-------|-------------|
| 40501 | Method Not Allowed | Request method not allowed |

### 422 - Unprocessable Entity

| Code | Error | Description |
|------|-------|-------------|
| 42201 | Validation Failed | Data validation failed |
| 42202 | Invalid Request Body | Invalid request body format |
| 42203 | Schema Validation Failed | Schema validation failed |
| 42204 | XSS Attack Detected | XSS attack detected |
| 42205 | SQL Injection Detected | SQL injection attack detected |

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
| 55301 | Circuit Breaker Open | Circuit breaker is open |

### 504 - Gateway Timeout

| Code | Error | Description |
|------|-------|-------------|
| 50401 | Gateway Timeout | Gateway timeout |
| 50402 | Upstream Timeout | Upstream service timeout |

---

## Gateway Specific Errors (5xx)

### Rate Limiting (529xx)

| Code | HTTP Status | Error | Description | Extra Fields |
|------|-------------|-------|-------------|--------------|
| 52901 | 429 | Rate Limit Exceeded | Request rate limit exceeded | `limit`, `remaining`, `retryAfter` |
| 52902 | 429 | Burst Limit Exceeded | Burst traffic limit exceeded | `limit`, `remaining`, `retryAfter` |

### Circuit Breaker (553xx)

| Code | HTTP Status | Error | Description | Extra Fields |
|------|-------------|-------|-------------|--------------|
| 55301 | 503 | Circuit Breaker Open | Circuit breaker is open | `routeId` |

### Transform Errors (550xx)

| Code | HTTP Status | Error | Description |
|------|-------------|-------|-------------|
| 55001 | 500 | Request Transform Error | Request transformation failed |
| 55002 | 500 | Response Transform Error | Response transformation failed |

### Cache Errors (550xx)

| Code | HTTP Status | Error | Description |
|------|-------------|-------|-------------|
| 55003 | 500 | Cache Error | Cache error |

### SSL/TLS Errors (550xx)

| Code | HTTP Status | Error | Description |
|------|-------------|-------|-------------|
| 55004 | 500 | SSL Error | SSL certificate error |
| 55005 | 500 | Certificate Expired | Certificate has expired |

---

## Rate Limit Response Headers

Rate limit responses include standard HTTP headers:

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Rate limit threshold (request count) |
| `X-RateLimit-Remaining` | Remaining available requests |
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
| `GatewayException.java` | Gateway exception base class |
| `RateLimitException.java` | Rate limit exception |
| `GatewayResponseHelper.java` | Response builder utility |
| `ScgGlobalExceptionHandler.java` | Global exception handler |