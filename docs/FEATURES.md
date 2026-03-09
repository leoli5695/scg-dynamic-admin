# Features Overview

Complete guide to all production-grade features.

---

## 1. Dynamic Route Management

**Storage:** Nacos `gateway-routes.json`

- Create/delete routes via REST API 鈥?**effective immediately, no restart**
- Support multiple URI schemes: `static://`, `lb://`, `http://`
- Hot-reload mechanism: Nacos push 鈫?clear cache 鈫?rebuild routes (< 1s)

---

## 2. Static Service Management (`static://`)

**Storage:** Nacos `gateway-services.json`

For services not registered in Nacos 鈥?configure IP:Port list directly.

- Dynamic instance management (add/remove/weight adjustment)
- Load balancing strategies: `round-robin`, `weighted`, `random`
- **Weighted round-robin**: Deterministic distribution (e.g., weight 1:2 鈫?exactly 1 to A, 2 to B every 3 requests)

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

Exceed limit 鈫?**HTTP 429** with headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`

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

Blocked 鈫?**HTTP 403 Forbidden**

**Why Order -280?** Runs **before authentication** to reject malicious IPs early, avoiding unnecessary JWT validation (37% TPS improvement).

---

### 3.3 Authentication Framework (Order: -250) 猸?

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
| **JWT** | `JwtAuthProcessor` | 鉁?Production | Stateless API auth |
| **API Key** | `ApiKeyAuthProcessor` | 鉁?Production | Simple partner access |
| **OAuth2** | `OAuth2AuthProcessor` | 鉁?Basic | Third-party SSO |
| **LDAP** | `LdapAuthProcessor` | 鈿狅笍 Template | Enterprise AD (placeholder) |
| **SAML** | `SamlAuthProcessor` | 鈿狅笍 Template | SSO (placeholder) |

#### Why This Design?

鉁?**Open-Closed Principle** 鈥?Add new auth types without modifying existing code  
鉁?**Auto-Discovery** 鈥?Spring automatically registers `@Component`  
鉁?**Zero Configuration** 鈥?No manual registration needed  
鉁?**Flexibility** 鈥?Different routes can use different auth methods

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
- **TPS:** 620 鈫?**850** (+37%)
- **Latency:** 18ms 鈫?**12ms** (-33%)

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

### 3.5 Circuit Breaker (Order: -100) 猸?

**Implementation:** Resilience4j

Prevents cascading failures by monitoring downstream health.

**Behavior:**
```
CLOSED (Normal) 
  鈫?Failure rate > threshold
OPEN (Reject all 鈫?HTTP 503)
  鈫?After waitDuration
HALF_OPEN (Test one request)
  鈫?Success        鈫?Failure
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
- 鉁?Protects downstream from overload
- 鉁?Fast failure (immediate rejection)
- 鉁?Automatic recovery
- 鉁?Per-route isolation

---

### 3.6 Distributed Tracing (Order: -300) 猸?

Generates/propagates TraceId across all microservices.

**How It Works:**
```
Client Request
  鈫?
Gateway generates X-Trace-Id: abc-123
  鈫?
MDC.put("traceId") 鈫?All logs: [traceId=abc-123]
  鈫?
Forward with header: X-Trace-Id: abc-123
  鈫?
Response includes: X-Trace-Id: abc-123
```

**Benefits:**
- 鉁?End-to-end visibility
- 鉁?Simplified debugging (correlate logs by TraceId)
- 鉁?Performance bottleneck identification
- 鉁?Compliance audit trail

---

## 4. Audit Logging

**Implementation:** Spring AOP

Automatically records all configuration changes:
- Who changed what (operator)
- What was changed (target)
- When it happened (timestamp)
- From which IP (ipAddress)

**Why Important for Production?**
- 鉁?Security compliance
- 鉁?Change tracking
- 鉁?Incident investigation
- 鉁?Accountability

---

## 馃搳 Complete Filter Chain

```
Request Flow:
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹?TraceId (-300)              鈹?鈫?First: Full visibility
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
           鈫?
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹?IP Filter (-280)            鈹?鈫?Coarse: Fast rejection
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
           鈫?
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹?Authentication (-250)       鈹?鈫?Fine: User identity
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
           鈫?
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹?Timeout (-200)              鈹?鈫?Protect downstream
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
           鈫?
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹?Circuit Breaker (-100)      鈹?鈫?Prevent cascade failure
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
           鈫?
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹?Rate Limiter (-50)          鈹?鈫?Last: Prevent overload
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
           鈫?
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹?Routing (10001+)            鈹?鈫?Core: Forward to backend
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
```

**Design Philosophy:**
1. **Observability first** (TraceId sees everything)
2. **Coarse before fine** (IP filter before auth)
3. **Protection before function** (Timeout/Circuit before routing)
4. **Fast failure** (Reject early, save resources)

---

## 馃幆 Feature Comparison

| Feature | Complexity | Production Ready? | When to Use |
|---------|------------|-------------------|-------------|
| **JWT Auth** | 猸愨瓙 | 鉁?Yes | Default for APIs |
| **API Key** | 猸?| 鉁?Yes | Simple partner access |
| **OAuth2** | 猸愨瓙猸愨瓙 | 鉁?With config | Third-party integration |
| **LDAP/SAML** | 猸愨瓙猸愨瓙猸?| 鈿狅笍 Template | Enterprise (implement on demand) |
| **Circuit Breaker** | 猸愨瓙猸?| 鉁?Yes | Critical downstream services |
| **TraceId** | 猸?| 鉁?Yes | Always enable |
| **Rate Limiter** | 猸愨瓙 | 鉁?Yes | Public APIs |
| **IP Filter** | 猸?| 鉁?Yes | Internal networks |

---

**Last Updated:** 2024-03-09  
**Version:** v1.0.0
