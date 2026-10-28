# SSL Termination

> Gateway provides HTTPS termination with support for dynamic certificate management and expiry monitoring.

---

## Overview

Gateway provides HTTPS service on port 8443, supporting:
- Multi-domain certificates (SNI)
- Dynamic hot reload (no restart required)
- Certificate expiry status tracking (`VALID` / `EXPIRING_SOON` / `EXPIRED`)

---

## Configuration

```yaml
gateway:
  ssl:
    # Enable HTTPS on the SSL port
    enabled: ${GATEWAY_SSL_ENABLED:true}
    # Auto-load certificates from Admin API
    auto-load: ${GATEWAY_SSL_AUTO_LOAD:true}
    # Certificate refresh interval (ms)
    refresh-interval: ${GATEWAY_SSL_REFRESH_INTERVAL:30000}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `enabled` | Whether to enable SSL (`GATEWAY_SSL_ENABLED`) | `true` |
| `port` | HTTPS port (`gateway.ssl.port`, read by `SslServerConfig`) | `8443` |
| `auto-load` | Pull certificates from the Admin API on startup (`GATEWAY_SSL_AUTO_LOAD`) | `true` |
| `refresh-interval` | Certificate refresh interval in ms (`GATEWAY_SSL_REFRESH_INTERVAL`) | `30000` |

> Certificates are **not** read from a local directory: there is no `cert-path` option.
> They are stored in the admin database, published to the config center
> (`config.gateway.metadata.ssl-certificates-index` plus per-certificate dataIds),
> and loaded by the gateway's `SslCertificateRefresher`.

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

All upload endpoints live under `/api/ssl` and require an authenticated admin user
(`POST` on `/api/**` needs the `ADMIN` authority). Responses are wrapped in
`{code, message, data}`.

### PEM Upload (content)

`POST /api/ssl/pem` takes the certificate and key as text, either in a JSON body
(`certName` / `domain` / `certContent` / `keyContent`) or as query parameters of the
same names, plus an optional `instanceId` query parameter:

```bash
curl -X POST "http://localhost:9090/api/ssl/pem?instanceId=gateway-1" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "certName": "api-example-com",
    "domain": "api.example.com",
    "certContent": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
    "keyContent": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"
  }'
```

### PEM Upload (files)

`POST /api/ssl/pem/files` is the `multipart/form-data` variant. The file parts are
named `certFile` and `keyFile`; `certName` and `domain` are required form fields and
`instanceId` is optional:

```bash
curl -X POST "http://localhost:9090/api/ssl/pem/files" \
  -H "Authorization: Bearer <token>" \
  -F "certName=api-example-com" \
  -F "domain=api.example.com" \
  -F "certFile=@/path/to/certificate.pem" \
  -F "keyFile=@/path/to/private.key"
```

### PKCS12 / JKS Keystore Upload

`POST /api/ssl/keystore` accepts the keystore as base64 text; `POST /api/ssl/keystore/file`
accepts it as a `keystoreFile` multipart part. Both take `certName`, `domain`,
`certType` (`PKCS12` or `JKS`), `password` and an optional `instanceId`:

```bash
# Keystore content (base64-encoded PKCS12)
curl -X POST "http://localhost:9090/api/ssl/keystore" \
  -H "Authorization: Bearer <token>" \
  --data-urlencode "certName=api-example-com" \
  --data-urlencode "domain=api.example.com" \
  --data-urlencode "certType=PKCS12" \
  --data-urlencode "keystoreContent=<base64 of certificate.p12>" \
  --data-urlencode "password=changeit"

# Keystore file
curl -X POST "http://localhost:9090/api/ssl/keystore/file" \
  -H "Authorization: Bearer <token>" \
  -F "certName=api-example-com" \
  -F "domain=api.example.com" \
  -F "certType=PKCS12" \
  -F "keystoreFile=@/path/to/certificate.p12" \
  -F "password=changeit"
```

---

## Certificate Status

| Status | Condition | Action |
|--------|-----------|--------|
| `VALID` | Days to expiry > 30 | Normal use |
| `EXPIRING_SOON` | Days to expiry <= 30 | Status recorded, surfaced by `/api/ssl/expiring` |
| `EXPIRED` | Already expired | Status recorded; the certificate is only taken out of service when it is disabled (`enabled=false`) |

The 30-day threshold is hardcoded (`SslCertificate.updateExpiryStatus()`), not configurable.

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
│   3. Store in DB                             │
│   4. Publish to Nacos (config change event)  │
│   5. Gateway receives event                  │
│   6. Hot-reload certificate                  │
│                                              │
│   Latency: listener / periodic sync          │
└─────────────────────────────────────────────┘
```

---

Certificate changes use listeners plus periodic reconciliation. The default polling interval is 60 seconds (`gateway.config-sync.interval-ms` / `GATEWAY_CONFIG_SYNC_INTERVAL_MS`); missed listener events take up to one interval plus fetch/apply time. Config-center outages delay updates until recovery.

## Expiry Monitoring

### Status Refresh

There is no `alerts.ssl.*` configuration block (no `expiry-warning-days` /
`expiry-critical-days` keys exist). Expiry status is derived from the certificate's
`validTo` with a **hardcoded 30-day threshold** (`SslCertificate.updateExpiryStatus()`),
which runs whenever the validity dates are set (i.e. at upload/parse time) and can be
re-run for all certificates on demand:

```bash
curl -X POST -H "Authorization: Bearer <token>" \
  http://localhost:9090/api/ssl/refresh-status
```

`updateExpiryStatuses()` is **not** wired to a scheduler — the call above is the only
bulk refresh trigger. Each record also stores `daysToExpiry`.

### Querying Expiring Certificates

```bash
# Certificates expiring within N days (default 30)
curl -H "Authorization: Bearer <token>" \
  "http://localhost:9090/api/ssl/expiring?days=30"

# Counts by status: total / valid / expiringSoon / expired (+ expiringList)
curl -H "Authorization: Bearer <token>" http://localhost:9090/api/ssl/stats
```

### What Actually Happens on Expiry

- **Expiring (<= 30 days)**: status becomes `EXPIRING_SOON`; visible through the endpoints above
- **Expired**: status becomes `EXPIRED`
- **No email alert**: the alert pipeline (`AlertCheckService`) covers CPU, memory, HTTP
  error rate, response time, thread count and instance-down conditions — certificate
  expiry is not one of them
- **No automatic disable**: expired certificates keep serving until they are disabled
  explicitly via `PUT /api/ssl/{id}/enabled?enabled=false` (the gateway skips
  certificates whose `enabled` flag is not `true`)

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/ssl` | List all certificates (optional `enabled`, `instanceId` filters) |
| `GET` | `/api/ssl/{id}` | Get certificate details |
| `GET` | `/api/ssl/domain/{domain}` | Get the certificate bound to a domain |
| `GET` | `/api/ssl/stats` | Counts by status plus the expiring list |
| `GET` | `/api/ssl/expiring` | Certificates expiring within `days` (default 30) |
| `POST` | `/api/ssl/pem` | Upload PEM certificate (content in JSON body or query params) |
| `POST` | `/api/ssl/pem/files` | Upload PEM certificate (`certFile` + `keyFile` multipart) |
| `POST` | `/api/ssl/keystore` | Upload PKCS12/JKS keystore (base64 content) |
| `POST` | `/api/ssl/keystore/file` | Upload PKCS12/JKS keystore (`keystoreFile` multipart) |
| `PUT` | `/api/ssl/{id}` | Update certificate name/content/key |
| `PUT` | `/api/ssl/{id}/enabled` | Enable or disable a certificate (`?enabled=true|false`) |
| `POST` | `/api/ssl/refresh-status` | Recompute expiry status for all certificates |
| `DELETE` | `/api/ssl/{id}` | Delete certificate |

There is no `/api/ssl/upload` or `/api/ssl/upload-pkcs12` endpoint.

### List Certificates

```bash
curl -H "Authorization: Bearer <token>" http://localhost:9090/api/ssl
```

Response (all admin endpoints wrap the payload in `ApiResponse`):
```json
{
  "code": 200,
  "message": "Success",
  "timestamp": 1735689600000,
  "data": [
    {
      "id": 1,
      "instanceId": "gateway-1",
      "certName": "api-example-com",
      "domain": "api.example.com",
      "certType": "PEM",
      "status": "VALID",
      "validFrom": "2024-01-01T00:00:00",
      "validTo": "2025-01-01T00:00:00",
      "daysToExpiry": 200,
      "enabled": true
    }
  ]
}
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
│   │ (config center) │                        │
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
│   │ Expiry Status   │                        │
│   │ (on write/API)  │                        │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

---

## Best Practices

1. **Certificate Storage**: certificates live in the admin database and are published to the
   config center — there is no certificate directory on the gateway, so restrict access to both
2. **Expiry Monitoring**: the warning threshold is fixed at 30 days (not configurable); poll
   `/api/ssl/expiring` and replace certificates in advance
3. **Certificate Backup**: regularly back up the certificate records (database / config center)
4. **Private Key Protection**: private keys are **not** masked — `keyContent` is stored in the
   `ssl_certificate` table, published to the config center and returned by `GET /api/ssl/{id}`,
   so keep the admin API and config center access tightly restricted

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - Configure alert notifications
- [Email Notifications](email-notifications.md) - Email alert configuration