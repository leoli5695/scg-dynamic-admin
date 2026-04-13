# SSL Termination

> Gateway provides HTTPS termination with support for dynamic certificate management and expiry monitoring.

---

## Overview

Gateway provides HTTPS service on port 8443, supporting:
- Multi-domain certificates (SNI)
- Dynamic hot reload (no restart required)
- Certificate expiry monitoring and alerts

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
| `enabled` | Whether to enable SSL | `false` |
| `port` | HTTPS port | `8443` |
| `cert-path` | Certificate storage path | `/opt/certificates` |

---

## Certificate Formats

| Format | Extension | Description |
|--------|-----------|-------------|
| PEM | `.pem`, `.crt`, `.key` | Certificate and private key separate |
| PKCS12 | `.p12`, `.pfx` | Certificate and private key packaged |
| JKS | `.jks` | Java KeyStore |

### PEM Format

Requires uploading two files:
- `certificate.pem` - Certificate file
- `private.key` - Private key file

### PKCS12 Format

Single file containing certificate and private key:
- `certificate.p12` - Requires password to decrypt

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
| `VALID` | Expiry > 30 days | Normal use |
| `EXPIRING_SOON` | Expiry < 30 days | Send alert email |
| `EXPIRED` | Already expired | Block usage, alert |

---

## Multi-Domain Support (SNI)

Gateway supports multi-domain certificates, selecting the appropriate certificate via SNI:

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

Certificate updates do not require Gateway restart:

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

- **Warning (30 days before)**: Send email reminder
- **Critical (7 days before)**: Send urgent email + UI highlight
- **Expired**: Certificate automatically disabled, error alert sent

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

1. **Certificate Storage**: Use secure file paths, restrict access permissions
2. **Expiry Monitoring**: Set alert thresholds, replace certificates in advance
3. **Certificate Backup**: Regularly backup certificate files
4. **Private Key Protection**: Private key files not returned via API, stored only

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - Configure alert notifications
- [Email Notifications](email-notifications.md) - Email alert configuration