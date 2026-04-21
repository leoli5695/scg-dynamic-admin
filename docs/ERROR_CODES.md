# Gateway Error Codes

> Gateway 统一响应格式与错误码规范

---

## Response Format

### Standard Error Response

所有网关错误响应采用统一 JSON 格式：

```json
{
    "httpStatus": 429,
    "code": 52901,
    "error": "Rate Limit Exceeded",
    "message": "请求频率超限",
    "data": null
}
```

### Field Description

| Field | Type | Description |
|-------|------|-------------|
| `httpStatus` | int | HTTP 状态码（如 400, 401, 429, 503） |
| `code` | int | 业务错误码，用于程序化处理 |
| `error` | string | 错误类型（英文简短描述） |
| `message` | string | 详细错误信息（中文描述） |
| `data` | null | 错误响应始终为 null |

### Extended Fields (Context-specific)

某些错误会包含额外字段：

```json
// Rate Limit 错误
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

// Circuit Breaker 错误
{
    "httpStatus": 503,
    "code": 55301,
    "error": "Circuit Breaker Open",
    "message": "Circuit breaker is open, please try again later",
    "data": null,
    "routeId": "user-service-route"
}
```

**retryAfter 格式说明：**
- `"1s"` - 1秒
- `"60s"` - 60秒
- `"2min"` - 2分钟
- `"1h"` - 1小时

---

## Error Code Format

业务错误码格式：`GW{Category}{Sequence}`

- **Category**: 错误类别
  - `1` = Client Error (4xx)
  - `2` = Server Error (5xx)
  - `3` = Gateway Error (5xx)
- **Sequence**: 序号 001-999

---

## Client Errors (4xx)

### 400 - Bad Request

| Code | Error | Description |
|------|-------|-------------|
| 40001 | Bad Request | 请求参数错误 |
| 40002 | Invalid Parameter | 参数校验失败 |
| 40003 | Missing Parameter | 缺少必要参数 |

### 401 - Unauthorized

| Code | Error | Description |
|------|-------|-------------|
| 40101 | Unauthorized | 未授权访问 |
| 40102 | Invalid Token | 无效的令牌 |
| 40103 | Token Expired | 令牌已过期 |
| 40104 | Invalid Credentials | 认证凭据无效 |
| 40105 | Invalid API Key | API密钥无效 |
| 40106 | Invalid Signature | 签名验证失败（HMAC） |

### 403 - Forbidden

| Code | Error | Description |
|------|-------|-------------|
| 40301 | Forbidden | 禁止访问 |
| 40302 | Access Denied | 权限不足 |
| 40303 | IP Blocked | IP地址被禁止访问 |
| 40304 | Rate Limited | 请求过于频繁 |

### 404 - Not Found

| Code | Error | Description |
|------|-------|-------------|
| 40401 | Not Found | 资源不存在 |
| 40402 | Route Not Found | 路由不存在 |
| 40403 | Service Not Found | 服务不存在 |

### 405 - Method Not Allowed

| Code | Error | Description |
|------|-------|-------------|
| 40501 | Method Not Allowed | 请求方法不允许 |

### 422 - Unprocessable Entity

| Code | Error | Description |
|------|-------|-------------|
| 42201 | Validation Failed | 数据校验失败 |
| 42202 | Invalid Request Body | 请求体格式错误 |
| 42203 | Schema Validation Failed | Schema校验失败 |
| 42204 | XSS Attack Detected | 检测到XSS攻击 |
| 42205 | SQL Injection Detected | 检测到SQL注入攻击 |

---

## Server Errors (5xx)

### 500 - Internal Server Error

| Code | Error | Description |
|------|-------|-------------|
| 50001 | Internal Server Error | 服务器内部错误 |
| 50002 | Configuration Error | 配置错误 |
| 50003 | Serialization Error | 序列化错误 |

### 502 - Bad Gateway

| Code | Error | Description |
|------|-------|-------------|
| 50201 | Upstream Error | 上游服务错误 |

### 503 - Service Unavailable

| Code | Error | Description |
|------|-------|-------------|
| 50301 | Service Unavailable | 服务不可用 |
| 50302 | No Healthy Instances | 无可用服务实例 |
| 50303 | Connection Refused | 连接被拒绝 |
| 55301 | Circuit Breaker Open | 熔断器已开启 |

### 504 - Gateway Timeout

| Code | Error | Description |
|------|-------|-------------|
| 50401 | Gateway Timeout | 网关超时 |
| 50402 | Upstream Timeout | 上游服务超时 |

---

## Gateway Specific Errors (5xx)

### Rate Limiting (529xx)

| Code | HTTP Status | Error | Description | Extra Fields |
|------|-------------|-------|-------------|--------------|
| 52901 | 429 | Rate Limit Exceeded | 请求频率超限 | `limit`, `remaining`, `retryAfter` |
| 52902 | 429 | Burst Limit Exceeded | 突发流量超限 | `limit`, `remaining`, `retryAfter` |

### Circuit Breaker (553xx)

| Code | HTTP Status | Error | Description | Extra Fields |
|------|-------------|-------|-------------|--------------|
| 55301 | 503 | Circuit Breaker Open | 熔断器已开启 | `routeId` |

### Transform Errors (550xx)

| Code | HTTP Status | Error | Description |
|------|-------------|-------|-------------|
| 55001 | 500 | Request Transform Error | 请求转换失败 |
| 55002 | 500 | Response Transform Error | 响应转换失败 |

### Cache Errors (550xx)

| Code | HTTP Status | Error | Description |
|------|-------------|-------|-------------|
| 55003 | 500 | Cache Error | 缓存错误 |

### SSL/TLS Errors (550xx)

| Code | HTTP Status | Error | Description |
|------|-------------|-------|-------------|
| 55004 | 500 | SSL Error | SSL证书错误 |
| 55005 | 500 | Certificate Expired | 证书已过期 |

---

## Rate Limit Response Headers

限流响应包含以下标准 HTTP 头：

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | 限流阈值（请求数） |
| `X-RateLimit-Remaining` | 剩余可用请求数 |
| `Retry-After` | 重试等待时间（秒） |

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
| `ErrorCode.java` | 错误码枚举定义 |
| `GatewayException.java` | 网关异常基类 |
| `RateLimitException.java` | 限流异常 |
| `GatewayResponseHelper.java` | 响应构建工具 |
| `ScgGlobalExceptionHandler.java` | 全局异常处理器 |