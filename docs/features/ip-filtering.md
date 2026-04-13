# IP Filtering

> IP filtering provides blacklist/whitelist access control, supporting CIDR format.

---

## Overview

IP filtering executes before authentication, quickly rejecting malicious requests:

```
Request Flow:
  Security (-500) → Security hardening
       ↓
  IP Filter (-490) → IP whitelist/blacklist → 403 if blocked
       ↓
  Access Log (-400) → Logging
       ↓
  Authentication (-250) → Auth check
```

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

```json
{
  "routeId": "public-api",
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
  "routeId": "internal-api",
  "mode": "whitelist",
  "ipList": [
    "10.0.0.0/8",
    "192.168.0.0/16"
  ],
  "enabled": true
}
```

Only IPs in the whitelist are allowed, other IPs are rejected.

---

## Trusted Proxies

When Gateway is behind a reverse proxy, trusted proxies need to be configured:

```yaml
gateway:
  trusted-proxies:
    enabled: true
    proxies:
      - "10.0.0.1"    # Nginx proxy
      - "10.0.0.2"    # Load balancer
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

When IP is blocked, returns:

```json
{
  "status": 403,
  "error": "Forbidden",
  "message": "IP address is blocked",
  "errorCode": "IP_BLOCKED"
}
```

---

## API Endpoints

Configure via Strategy API:

```bash
curl -X PUT http://localhost:9090/api/strategies/ip-filter \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "internal-api",
    "mode": "whitelist",
    "ipList": ["10.0.0.0/8"],
    "enabled": true
  }'
```

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