# Authentication

> Gateway supports multiple authentication methods: JWT, API Key, Basic Auth, HMAC Signature, OAuth2.

---

## Overview

Gateway uses strategy pattern to implement multiple authentication methods, different routes can be configured with different authentication strategies.

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
| `JWT` | `JwtAuthProcessor` | Stateless API authentication |
| `API_KEY` | `ApiKeyAuthProcessor` | Simple partner access |
| `BASIC` | `BasicAuthProcessor` | Username/password authentication |
| `HMAC` | `HmacSignatureAuthProcessor` | API signature verification |
| `OAUTH2` | `OAuth2AuthProcessor` | Third-party SSO |

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
| `secretKey` | JWT signing key |
| `issuer` | Issuer validation |
| `audience` | Audience validation |
| `clockSkew` | Clock skew tolerance (seconds) |

### Token Validation

Gateway validates:
1. Signature validity
2. `iss` (issuer) match
3. `aud` (audience) match
4. `exp` (expiry) not expired
5. `nbf` (not before) valid

### JWT Cache

Gateway has built-in JWT Claims cache to avoid repeated validation:

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
| `headerName` | API Key header name |
| `apiKey` | Expected API Key value |

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

Client generates signature:

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

Gateway validates:
1. Timestamp is within valid range (prevent replay attacks)
2. Signature matches
3. Algorithm is correct

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

Gateway calls OAuth2 server to validate token:

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

Authentication strategy bound to route:

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

Returned when authentication fails:

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
| `40101` | 401 | Unauthorized access |
| `40102` | 401 | Invalid token |
| `40103` | 401 | Token expired |
| `40104` | 401 | Invalid credentials |
| `40105` | 401 | Invalid API key |
| `40106` | 401 | Signature verification failed |
| `40301` | 403 | Forbidden |
| `40302` | 403 | Insufficient permissions |
| `40303` | 403 | IP address blocked |

---

## Best Practices

1. **JWT Security**: Use strong keys, rotate regularly
2. **API Key Management**: Assign different keys to different partners
3. **HMAC Time Window**: Set reasonable timestamp validation window (e.g., 5 minutes)
4. **OAuth2 Caching**: Appropriately cache introspection results
5. **Authentication Failure Logs**: Record failure details for security auditing

---

## Related Features

- [Route Management](route-management.md) - Route configuration
- [IP Filtering](ip-filtering.md) - IP access control
- [Request Tracing](request-tracing.md) - Authentication failure request tracing