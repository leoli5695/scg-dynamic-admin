# Authentication

> Gateway 支持多种认证方式：JWT、API Key、Basic Auth、HMAC Signature、OAuth2。

---

## Overview

Gateway 使用策略模式实现多认证方式，不同路由可配置不同的认证策略。

```
┌─────────────────────────────────────────────┐
│         AuthProcessor (Interface)            │
│                                              │
│   + validate(exchange, config): Mono<Boolean>│
│   + getType(): AuthType                      │
└─────────────────────────────────────────────┘
                  │
    ┌─────────────┼─────────────┬─────────────┐
    │             │             │             │
    ▼             ▼             ▼             ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│   JWT   │ │ API Key │ │  Basic  │ │  HMAC   │
│Processor│ │Processor│ │Processor│ │Processor│
└─────────┘ └─────────┘ └─────────┘ └─────────┘
```

---

## Supported Types

| Type | Processor | Use Case |
|------|-----------|----------|
| `JWT` | `JwtAuthProcessor` | 无状态 API 认证 |
| `API_KEY` | `ApiKeyAuthProcessor` | 简单合作伙伴访问 |
| `BASIC` | `BasicAuthProcessor` | 用户名密码认证 |
| `HMAC` | `HmacSignatureAuthProcessor` | API 签名验证 |
| `OAUTH2` | `OAuth2AuthProcessor` | 第三方 SSO |

---

## JWT Authentication

### Configuration

```json
{
  "routeId": "secure-api",
  "authType": "JWT",
  "secretKey": "your-256-bit-secret",
  "issuer": "my-app",
  "audience": "api-users",
  "enabled": true
}
```

| Parameter | Description |
|-----------|-------------|
| `secretKey` | JWT 签名密钥 |
| `issuer` | 签发者验证 |
| `audience` | 受众验证 |
| `clockSkew` | 时间偏差容忍（秒） |

### Token Validation

Gateway 验证：
1. 签名有效性
2. `iss` (issuer) 匹配
3. `aud` (audience) 匹配
4. `exp` (expiry) 未过期
5. `nbf` (not before) 有效

### JWT Cache

Gateway 内置 JWT Claims 缓存，避免重复验证：

```
┌─────────────────────────────────────────────┐
│         JWT Validation Cache                 │
│                                              │
│   Incoming Request with JWT                  │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Cache Lookup    │── Hit ──▶ Return cached│
│   └────────┬────────┘                        │
│            │ Miss                            │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Verify Signature│                        │
│   │ Parse Claims    │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Cache Result    │                        │
│   │ (TTL = JWT exp) │                        │
│   └─────────────────┘                        │
│                                              │
│   Performance: ~90% reduction in overhead     │
└─────────────────────────────────────────────┘
```

### Example Request

```bash
curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  https://api.example.com/users
```

---

## API Key Authentication

### Configuration

```json
{
  "routeId": "partner-api",
  "authType": "API_KEY",
  "headerName": "X-API-Key",
  "apiKey": "sk-your-api-key",
  "enabled": true
}
```

| Parameter | Description |
|-----------|-------------|
| `headerName` | API Key Header 名称 |
| `apiKey` | 期望的 API Key 值 |

### Example Request

```bash
curl -H "X-API-Key: sk-your-api-key" \
  https://api.example.com/partner/data
```

---

## Basic Authentication

### Configuration

```json
{
  "routeId": "internal-api",
  "authType": "BASIC",
  "username": "admin",
  "password": "password123",
  "enabled": true
}
```

### Example Request

```bash
curl -u admin:password123 \
  https://api.example.com/internal/config
```

---

## HMAC Signature Authentication

### Configuration

```json
{
  "routeId": "webhook-api",
  "authType": "HMAC",
  "secretKey": "hmac-secret",
  "algorithm": "HmacSHA256",
  "enabled": true
}
```

### Signature Generation

客户端生成签名：

```java
String payload = requestBody;
String timestamp = String.valueOf(System.currentTimeMillis());
String data = timestamp + "\n" + payload;

Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secretKey.getBytes(), "HmacSHA256"));
String signature = Base64.encode(mac.doFinal(data.getBytes()));
```

### Request Headers

```
X-Signature: base64-encoded-signature
X-Timestamp: 1234567890
```

### Server Validation

Gateway 验证：
1. 时间戳在有效范围内（防重放攻击）
2. 签名匹配
3. 算法正确

---

## OAuth2 Authentication

### Configuration

```json
{
  "routeId": "sso-api",
  "authType": "OAUTH2",
  "introspectionUrl": "https://oauth.example.com/introspect",
  "clientId": "gateway-client",
  "clientSecret": "client-secret",
  "enabled": true
}
```

### Token Introspection

Gateway 调用 OAuth2 服务验证 Token：

```
┌─────────────────┐     ┌─────────────────┐
│     Gateway     │────▶│   OAuth2 Server │
│                 │     │                 │
│ Token in Header │     │ Introspection   │
│                 │◀────│ Response        │
└─────────────────┘     └─────────────────┘
                          │
                          ▼
                    {"active": true, ...}
```

---

## Route-Auth Binding

认证策略与路由绑定：

```json
{
  "routeId": "secure-api",
  "authPolicyId": 1,
  "enabled": true
}
```

### API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/auth-policies` | List auth policies |
| `POST` | `/api/auth-policies` | Create auth policy |
| `PUT` | `/api/auth-policies/{id}` | Update auth policy |
| `DELETE` | `/api/auth-policies/{id}` | Delete auth policy |
| `POST` | `/api/routes/{routeId}/auth-binding` | Bind auth to route |

---

## Error Responses

认证失败返回：

```json
{
  "code": 40101,
  "error": "Unauthorized",
  "message": "JWT token expired",
  "data": null
}
```

### Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `40101` | 401 | 未授权访问 |
| `40102` | 401 | 无效的 Token |
| `40103` | 401 | Token 已过期 |
| `40104` | 401 | 认证凭据无效 |
| `40105` | 401 | API 密钥无效 |
| `40106` | 401 | 签名验证失败 |
| `40301` | 403 | 禁止访问 |
| `40302` | 403 | 权限不足 |
| `40303` | 403 | IP 地址被禁止访问 |

---

## Best Practices

1. **JWT 安全**：使用强密钥，定期更换
2. **API Key 管理**：为不同合作伙伴分配不同 Key
3. **HMAC 时间窗口**：设置合理的时间戳验证窗口（如 5 分钟）
4. **OAuth2 缓存**：适当缓存 introspection 结果
5. **认证失败日志**：记录失败详情用于安全审计

---

## Related Features

- [Route Management](route-management.md) - 路由配置
- [IP Filtering](ip-filtering.md) - IP 访问控制
- [Request Tracing](request-tracing.md) - 认证失败请求追踪