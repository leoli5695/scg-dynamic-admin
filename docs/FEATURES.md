# Features Overview

Complete guide to all production-grade features.

---

## 1. Dynamic Route Management

**Storage:** Nacos `gateway-routes.json`

- Create/delete routes via REST API — **effective immediately, no restart**
- Support multiple URI schemes: `static://`, `lb://`, `http://`
- Hot-reload mechanism: Nacos push → clear cache → rebuild routes (< 1s)

---

## 2. Static Service Management (`static://`)

**Storage:** Nacos `gateway-services.json`

For services not registered in Nacos — configure IP:Port list directly.

- Dynamic instance management (add/remove/weight adjustment)
- Load balancing strategies: `round-robin`, `weighted`, `random`
- **Weighted round-robin**: Deterministic distribution (e.g., weight 1:2 → exactly 1 to A, 2 to B every 3 requests)

---

## 3. Plugin System

All plugins managed in single Nacos config: `gateway-plugins.json`

### 3.1 Rate Limiter (Order: -50)

**Implementation:** Redis ZSET sliding window

| Key Type | Behavior |
|----------|----------|
| `ip` | Per-client IP counting |
| `route` | Shared per route |
| `combined` | Route + IP |
| `header` | By request header value |

Exceed limit → **HTTP 429** with headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`

**Config Example:**
```json
{
  "routeId": "api",
  "qps": 100,
  "timeUnit": "second",
  "burstCapacity": 200,
  "keyType": "ip"
}
```

---

### 3.2 IP Access Control (Order: -280)

**Modes:** Whitelist (allow only) or Blacklist (block)

**IP Formats Supported:**
- Exact: `192.168.1.100`
- Wildcard: `192.168.1.*`
- CIDR: `192.168.1.0/24`

Blocked → **HTTP 403 Forbidden**

**Why Order -280?** Runs **before authentication** to reject malicious IPs early, avoiding unnecessary JWT validation (37% TPS improvement).

---

### 3.3 Authentication Framework (Order: -250) ⭐

**Design Pattern:** Strategy Pattern + Auto-Discovery

#### Architecture

```java
// Unified interface
public interface AuthProcessor {
    Mono<Void> process(ServerWebExchange exchange, AuthConfig config);
    String getAuthType();
}

// Auto-registered by Spring
@Component
public class JwtAuthProcessor implements AuthProcessor { ... }
@Component
public class ApiKeyAuthProcessor implements AuthProcessor { ... }
@Component
public class OAuth2AuthProcessor implements AuthProcessor { ... }

// Manager routes to appropriate processor
@Component
public class AuthManager {
    @Autowired
    public AuthManager(List<AuthProcessor> processors) {
        // Auto-register all by authType
    }
}
```

#### Supported Types

| Type | Processor | Status | Use Case |
|------|-----------|--------|----------|
| **JWT** | `JwtAuthProcessor` | ✅ Production | Stateless API auth |
| **API Key** | `ApiKeyAuthProcessor` | ✅ Production | Simple partner access |
| **OAuth2** | `OAuth2AuthProcessor` | ✅ Basic | Third-party SSO |
| **LDAP** | `LdapAuthProcessor` | ⚠️ Template | Enterprise AD (placeholder) |
| **SAML** | `SamlAuthProcessor` | ⚠️ Template | SSO (placeholder) |

#### Why This Design?

✅ **Open-Closed Principle** — Add new auth types without modifying existing code  
✅ **Auto-Discovery** — Spring automatically registers `@Component`  
✅ **Zero Configuration** — No manual registration needed  
✅ **Flexibility** — Different routes can use different auth methods

#### How to Extend

Add custom auth in 3 steps:

```java
@Component
public class DingTalkAuthProcessor extends AbstractAuthProcessor {
    @Override
    public String getAuthType() { return "DINGTALK"; }
    
    @Override
    public Mono<Void> process(...) {
        // Validate with DingTalk API
        return Mono.empty(); // Success
    }
}
// That's it! Spring auto-registers.
```

#### Config Examples

**JWT:**
```json
{
  "routeId": "secure-api",
  "authType": "JWT",
  "secretKey": "your-32-char-secret"
}
```

**API Key:**
```json
{
  "routeId": "internal-api",
  "authType": "API_KEY",
  "apiKey": "sk-your-key"
}
```

#### Performance Impact

With IP filtering before auth:
- **TPS:** 620 → **850** (+37%)
- **Latency:** 18ms → **12ms** (-33%)

---

### 3.4 Timeout Control (Order: -200)

Per-route connect and response timeouts.

| Field | Scope | On Expiry |
|-------|-------|-----------|
| `connectTimeout` | TCP handshake | HTTP 504 |
| `responseTimeout` | Full request-response cycle | HTTP 504 |

**Config:**
```json
{
  "routeId": "slow-api",
  "connectTimeout": 5000,
  "responseTimeout": 30000
}
```

---

### 3.5 Circuit Breaker (Order: -100) ⭐

**Implementation:** Resilience4j

Prevents cascading failures by monitoring downstream health.

**Behavior:**
```
CLOSED (Normal) 
  ↓ Failure rate > threshold
OPEN (Reject all → HTTP 503)
  ↓ After waitDuration
HALF_OPEN (Test one request)
  ↓ Success        ↓ Failure
CLOSED           OPEN
```

**Config:**
```json
{
  "routeId": "user-service",
  "failureRateThreshold": 50,
  "waitDurationInOpenState": 30000,
  "slidingWindowSize": 10
}
```

**Why Important?**
- ✅ Protects downstream from overload
- ✅ Fast failure (immediate rejection)
- ✅ Automatic recovery
- ✅ Per-route isolation

---

### 3.6 Distributed Tracing (Order: -300) ⭐

Generates/propagates TraceId across all microservices.

**How It Works:**
```
Client Request
  ↓
Gateway generates X-Trace-Id: abc-123
  ↓
MDC.put("traceId") → All logs: [traceId=abc-123]
  ↓
Forward with header: X-Trace-Id: abc-123
  ↓
Response includes: X-Trace-Id: abc-123
```

**Benefits:**
- ✅ End-to-end visibility
- ✅ Simplified debugging (correlate logs by TraceId)
- ✅ Performance bottleneck identification
- ✅ Compliance audit trail

---

## 4. Audit Logging

**Implementation:** Spring AOP

Automatically records all configuration changes:
- Who changed what (operator)
- What was changed (target)
- When it happened (timestamp)
- From which IP (ipAddress)

**Why Important for Production?**
- ✅ Security compliance
- ✅ Change tracking
- ✅ Incident investigation
- ✅ Accountability

---

## 📊 Complete Filter Chain

```
Request Flow:
┌─────────────────────────────┐
│ TraceId (-300)              │ ← First: Full visibility
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ IP Filter (-280)            │ ← Coarse: Fast rejection
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ Authentication (-250)       │ ← Fine: User identity
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ Timeout (-200)              │ ← Protect downstream
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ Circuit Breaker (-100)      │ ← Prevent cascade failure
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ Rate Limiter (-50)          │ ← Last: Prevent overload
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ Routing (10001+)            │ ← Core: Forward to backend
└─────────────────────────────┘
```

**Design Philosophy:**
1. **Observability first** (TraceId sees everything)
2. **Coarse before fine** (IP filter before auth)
3. **Protection before function** (Timeout/Circuit before routing)
4. **Fast failure** (Reject early, save resources)

---

## 🎯 Feature Comparison

| Feature | Complexity | Production Ready? | When to Use |
|---------|------------|-------------------|-------------|
| **JWT Auth** | ⭐⭐ | ✅ Yes | Default for APIs |
| **API Key** | ⭐ | ✅ Yes | Simple partner access |
| **OAuth2** | ⭐⭐⭐⭐ | ✅ With config | Third-party integration |
| **LDAP/SAML** | ⭐⭐⭐⭐⭐ | ⚠️ Template | Enterprise (implement on demand) |
| **Circuit Breaker** | ⭐⭐⭐ | ✅ Yes | Critical downstream services |
| **TraceId** | ⭐ | ✅ Yes | Always enable |
| **Rate Limiter** | ⭐⭐ | ✅ Yes | Public APIs |
| **IP Filter** | ⭐ | ✅ Yes | Internal networks |

---

**Last Updated:** 2024-03-09  
**Version:** v1.0.0
