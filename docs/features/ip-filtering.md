# IP Filtering

> IP filtering provides blacklist/whitelist access control, supporting CIDR format.

---

## Overview

IP filtering executes before authentication, quickly rejecting malicious requests:

```
Request Flow:
  IP Filter (-490) → IP whitelist/blacklist → 403 if blocked
       ↓
  Security (-480) → Security hardening
       ↓
  Access Log (-400) → Logging
       ↓
  Authentication (-256) → Auth check
```

> Order values come from `FilterOrderConstants`: `IP_FILTER = -490`, `SECURITY_GLOBAL = -480`,
> `ACCESS_LOG = -400`, `AUTHENTICATION = -256`. Note that **`IP_FILTER (-490)` runs BEFORE
> `SECURITY_GLOBAL (-480)`** — the WAF was deliberately moved from -500 to -480 so the cheap IP
> rejection happens first and a blacklisted address cannot make the gateway buffer and regex-scan a
> full request body. `TraceIdGlobalFilter` runs before all of them at `Ordered.HIGHEST_PRECEDENCE`
> (the `TRACE_ID = -300` constant is a historical reference only, per its own javadoc).

**Why before authentication?**
- Quick rejection, saving authentication overhead
- Prevent malicious IPs from consuming system resources

---

## Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| `blacklist` | Blacklist mode | Block known malicious IPs |
| `whitelist` | Whitelist mode | Only allow specific IPs to access |

---

## IP Formats

| Format | Example | Description |
|--------|---------|-------------|
| Exact | `192.168.1.100` | Exact match |
| Wildcard | `192.168.1.*` | Wildcard match |
| CIDR | `192.168.1.0/24` | CIDR network segment |

### CIDR Examples

```
192.168.1.0/24  → 192.168.1.0 - 192.168.1.255
10.0.0.0/8      → 10.0.0.0 - 10.255.255.255
172.16.0.0/12   → 172.16.0.0 - 172.31.255.255
```

---

## Configuration

### Blacklist Mode

The block below is the strategy's `config` map. `routeId` is **not** a config key — route binding is
done by the strategy-level `scope`/`routeId` fields (see [API Endpoints](#api-endpoints)).

```json
{
  "mode": "blacklist",
  "ipList": [
    "192.168.1.100",
    "10.0.0.0/8",
    "172.16.*"
  ],
  "enabled": true
}
```

IPs in the blacklist are rejected, other IPs are allowed.

### Whitelist Mode

```json
{
  "mode": "whitelist",
  "ipList": [
    "10.0.0.0/8",
    "192.168.0.0/16"
  ],
  "enabled": true
}
```

Only IPs in the whitelist are allowed, other IPs are rejected.

`IPFilterGlobalFilter` reads exactly three keys from this map: `enabled` (default `true`), `mode`
(default `blacklist`; any other value raises `CONFIG_ERROR`) and `ipList`.

---

## Trusted Proxies

When Gateway is behind a reverse proxy, trusted proxies need to be configured. The prefix is
**`gateway.trusted-proxy`** (singular) and the list field is **`proxyIps`** (relaxed binding also
accepts `proxy-ips`). There is no `gateway.trusted-proxies` prefix and no `proxies` key — using them
is silently ignored and the gateway falls back to the direct peer address.

```yaml
gateway:
  trusted-proxy:
    enabled: true              # default true; false = always use the direct remote address
    proxy-ips:                 # binds to TrustedProxyProperties.proxyIps
      - "10.0.0.1"             # Nginx proxy
      - "10.0.0.2"             # Load balancer
    trust-private-networks: false   # trust 10/8, 172.16/12, 192.168/16, 127/8 as proxies
    position: FIRST            # FIRST | LAST, default FIRST
    max-forwarded-ips: 10      # longer/malformed chains fall back to the direct peer
```

Gateway extracts the real client IP from the `X-Forwarded-For` Header.

### IP Extraction Logic

```
X-Forwarded-For: 10.0.0.100, 10.0.0.1, 10.0.0.2

Trusted Proxies: [10.0.0.1, 10.0.0.2]

Extract: Rightmost non-trusted IP = 10.0.0.100
```

---

## Error Response

When an IP is blocked, `IPFilterGlobalFilter` calls `GatewayResponseHelper.writeForbidden(...)`, which
produces the gateway's standard error envelope (`{httpStatus, code, error, message, data}`):

```json
{
  "httpStatus": 403,
  "code": 40301,
  "error": "Forbidden",
  "message": "Client IP 203.0.113.7 is blocked by the IP blacklist",
  "data": null
}
```

- `httpStatus` is the numeric HTTP status (the key is **not** `status`).
- `code` is the **numeric** `ErrorCode` value (the key is **not** `errorCode`, and it is not a string).
- `error` is `HttpStatus.getReasonPhrase()`, i.e. `"Forbidden"` — not an ErrorCode name.
- `message` is the filter's text: `"Client IP {ip} is blocked by the IP blacklist"` in blacklist mode,
  `"Client IP {ip} is not in the IP whitelist"` in whitelist mode.

> **Which code?** `writeForbidden` uses `ErrorCode.FORBIDDEN` = **40301**. `ErrorCode.IP_BLOCKED`
> (40303) exists in the enum but has **zero references** in main code — the IP filter never emits it,
> so do not key alerting or client logic on 40303.

An unsupported `mode` value (anything other than `blacklist`/`whitelist`) instead raises
`GatewayException(ErrorCode.CONFIG_ERROR, "Unsupported IP filter mode: ...")`.

---

## API Endpoints

Configure via the Strategy API. There is **no `/api/strategies/ip-filter` path**: `StrategyController`
is `@RequestMapping("/api/strategies")` and identifies a strategy by its `strategyId`
(`PUT /{id}`, `GET /{id}`, `DELETE /{id}`), with `GET /type/{type}` for lookup by type. The IP filter
type constant is `StrategyDefinition.TYPE_IP_FILTER = "IP_FILTER"`.

Create a new IP-filter strategy:

```bash
curl -X POST http://localhost:9090/api/strategies \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "strategyId": "ip-filter-internal-api",
    "strategyName": "Internal API IP whitelist",
    "strategyType": "IP_FILTER",
    "scope": "ROUTE",
    "routeId": "internal-api",
    "priority": 100,
    "enabled": true,
    "config": {
      "mode": "whitelist",
      "ipList": ["10.0.0.0/8"],
      "enabled": true
    }
  }'
```

Update an existing one (`{strategyId}`, not a type name):

```bash
curl -X PUT http://localhost:9090/api/strategies/ip-filter-internal-api \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "strategyId": "ip-filter-internal-api",
    "strategyName": "Internal API IP whitelist",
    "strategyType": "IP_FILTER",
    "scope": "ROUTE",
    "routeId": "internal-api",
    "enabled": true,
    "config": { "mode": "whitelist", "ipList": ["10.0.0.0/8"], "enabled": true }
  }'
```

Other useful endpoints: `GET /api/strategies`, `GET /api/strategies/{id}`,
`GET /api/strategies/type/IP_FILTER`, `GET /api/strategies/global`, `GET /api/strategies/route/{routeId}`,
`DELETE /api/strategies/{id}`, `POST /api/strategies/{id}/enable`, `POST /api/strategies/{id}/disable`.

> **Authentication**: `POST`/`PUT`/`PATCH`/`DELETE` under `/api/**` require the `ADMIN` authority, and
> every other `/api/**` request requires an authenticated JWT. All examples above must carry
> `Authorization: Bearer <token>`.

> **Route binding lives on the strategy, not in `config`.** `routeId` is a top-level
> `StrategyDefinition` field; `IPFilterGlobalFilter` only reads `enabled` (default `true`), `mode`
> (default `blacklist`) and `ipList` from the `config` map for the matched route.

---

## Best Practices

1. **Whitelist First**: Use whitelist mode for internal APIs
2. **Regular Blacklist Updates**: Update malicious IPs based on security logs
3. **CIDR Optimization**: Use CIDR instead of large numbers of individual IPs
4. **Trusted Proxy Configuration**: Ensure correct real IP extraction
5. **Combine with Monitoring**: Log blocked IPs for analysis

---

## Related Features

- [Authentication](authentication.md) - Authentication configuration
- [Rate Limiting](rate-limiting.md) - IP-based rate limiting
- [Request Tracing](request-tracing.md) - Security event tracing