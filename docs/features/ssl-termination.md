# SSL Termination

> Gateway 提供 HTTPS 终止功能，支持动态证书管理和到期监控。

---

## Overview

Gateway 在端口 8443 提供 HTTPS 服务，支持：
- 多域名证书（SNI）
- 动态热加载（无需重启）
- 证书到期监控和告警

---

## Configuration

```yaml
gateway:
  ssl:
    enabled: true
    port: 8443
    cert-path: /opt/certificates
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `enabled` | 是否启用 SSL | `false` |
| `port` | HTTPS 端口 | `8443` |
| `cert-path` | 证书存储路径 | `/opt/certificates` |

---

## Certificate Formats

| Format | Extension | Description |
|--------|-----------|-------------|
| PEM | `.pem`, `.crt`, `.key` | 证书和私钥分离 |
| PKCS12 | `.p12`, `.pfx` | 证书和私钥打包 |
| JKS | `.jks` | Java KeyStore |

### PEM Format

需要上传两个文件：
- `certificate.pem` - 证书文件
- `private.key` - 私钥文件

### PKCS12 Format

单个文件包含证书和私钥：
- `certificate.p12` - 需要密码解密

---

## Certificate Upload

### PEM Upload

```bash
POST /api/ssl/upload
Content-Type: multipart/form-data

file: certificate.pem
key: private.key
domain: api.example.com
```

### PKCS12 Upload

```bash
POST /api/ssl/upload-pkcs12
Content-Type: multipart/form-data

file: certificate.p12
password: changeit
domain: api.example.com
```

### API Example

```bash
# Upload PEM certificate
curl -X POST http://localhost:9090/api/ssl/upload \
  -F "file=@/path/to/certificate.pem" \
  -F "key=@/path/to/private.key" \
  -F "domain=api.example.com"

# Upload PKCS12 certificate
curl -X POST http://localhost:9090/api/ssl/upload-pkcs12 \
  -F "file=@/path/to/certificate.p12" \
  -F "password=changeit" \
  -F "domain=api.example.com"
```

---

## Certificate Status

| Status | Condition | Action |
|--------|-----------|--------|
| `VALID` | 到期时间 > 30 天 | 正常使用 |
| `EXPIRING_SOON` | 到期时间 < 30 天 | 发送告警邮件 |
| `EXPIRED` | 已过期 | 阻止使用，告警 |

---

## Multi-Domain Support (SNI)

Gateway 支持多域名证书，通过 SNI 选择对应证书：

```
Client Request (HTTPS)
        │
        ▼
┌─────────────────┐
│ SSL Handshake   │
│ (SNI Selection) │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Certificate     │
│ Store           │
│                 │
│ api.example.com │ → use api.example.com cert
│ admin.example.cn│ → use admin.example.cn cert
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ SSL Termination │
│ (HTTP → Backend)│
└─────────────────┘
```

---

## Hot Reload

证书更新无需重启 Gateway：

```
┌─────────────────────────────────────────────┐
│ Certificate Upload                           │
│                                              │
│   1. Upload to Admin API                     │
│   2. Parse certificate                       │
│   3. Store in DB + File System               │
│   4. Publish to Nacos (config change event)  │
│   5. Gateway receives event                  │
│   6. Hot-reload certificate                  │
│                                              │
│   Total latency: < 1 second                  │
└─────────────────────────────────────────────┘
```

---

## Expiry Monitoring

### Alert Configuration

```yaml
alerts:
  ssl:
    expiry-warning-days: 30
    expiry-critical-days: 7
```

### Alert Actions

- **Warning (30天前)**: 发送邮件提醒
- **Critical (7天前)**: 发送紧急邮件 + UI 高亮显示
- **Expired**: 证书自动禁用，发送错误告警

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/ssl` | List all certificates |
| `GET` | `/api/ssl/{id}` | Get certificate details |
| `POST` | `/api/ssl/upload` | Upload PEM certificate |
| `POST` | `/api/ssl/upload-pkcs12` | Upload PKCS12 certificate |
| `DELETE` | `/api/ssl/{id}` | Delete certificate |

### List Certificates

```bash
curl http://localhost:9090/api/ssl
```

Response:
```json
[
  {
    "id": 1,
    "domain": "api.example.com",
    "status": "VALID",
    "validFrom": "2024-01-01",
    "validTo": "2025-01-01",
    "daysRemaining": 200
  }
]
```

---

## Certificate Lifecycle

```
┌─────────────────────────────────────────────┐
│         CERTIFICATE LIFECYCLE                │
│                                              │
│   Upload Certificate                         │
│         │                                    │
│         ▼                                    │
│   ┌─────────────────┐                        │
│   │ Parse Certificate│                        │
│   │ - Extract domain │                        │
│   │ - Extract expiry │                        │
│   │ - Validate chain│                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Store in DB     │                        │
│   │ + File System   │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Push to Gateway │                        │
│   │ (Hot-reload)    │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Expiry Monitor  │                        │
│   │ (Scheduled)     │                        │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

---

## Best Practices

1. **证书存储**：使用安全的文件路径，限制访问权限
2. **到期监控**：设置告警阈值，提前更换证书
3. **证书备份**：定期备份证书文件
4. **私钥保护**：私钥文件不通过 API 返回，仅存储

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - 配置告警通知
- [Email Notifications](email-notifications.md) - 邮件告警配置