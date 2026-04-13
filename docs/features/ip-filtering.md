# IP Filtering

> IP 过滤提供黑名单/白名单访问控制，支持 CIDR 格式。

---

## Overview

IP 过滤在认证之前执行，快速拒绝恶意请求：

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

**为什么在认证之前？**
- 快速拒绝，节省认证开销
- 防止恶意 IP 消耗系统资源

---

## Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| `blacklist` | 黑名单模式 | 阻止已知恶意 IP |
| `whitelist` | 白名单模式 | 仅允许特定 IP 访问 |

---

## IP Formats

| Format | Example | Description |
|--------|---------|-------------|
| Exact | `192.168.1.100` | 精确匹配 |
| Wildcard | `192.168.1.*` | 通配符匹配 |
| CIDR | `192.168.1.0/24` | CIDR 网段 |

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

黑名单中的 IP 被拒绝，其他 IP 允许访问。

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

仅白名单中的 IP 允许访问，其他 IP 被拒绝。

---

## Trusted Proxies

当 Gateway 位于反向代理后面时，需要配置可信代理：

```yaml
gateway:
  trusted-proxies:
    enabled: true
    proxies:
      - "10.0.0.1"    # Nginx proxy
      - "10.0.0.2"    # Load balancer
```

Gateway 会从 `X-Forwarded-For` Header 中提取真实客户端 IP。

### IP Extraction Logic

```
X-Forwarded-For: 10.0.0.100, 10.0.0.1, 10.0.0.2

Trusted Proxies: [10.0.0.1, 10.0.0.2]

Extract: Rightmost non-trusted IP = 10.0.0.100
```

---

## Error Response

IP 被阻止时返回：

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

通过 Strategy API 配置：

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

1. **白名单优先**：内部 API 使用白名单模式
2. **定期更新黑名单**：根据安全日志更新恶意 IP
3. **CIDR 优化**：使用 CIDR 替代大量单独 IP
4. **可信代理配置**：确保正确提取真实 IP
5. **结合监控**：记录被阻止的 IP 用于分析

---

## Related Features

- [Authentication](authentication.md) - 认证配置
- [Rate Limiting](rate-limiting.md) - 按IP限流
- [Request Tracing](request-tracing.md) - 安全事件追踪