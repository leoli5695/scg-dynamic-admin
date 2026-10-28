# Email Notifications

> Email notifications support SMTP configuration for sending alerts and system notifications.

---

## Overview

Email notifications are used for:
- Alert notifications (CPU / memory / HTTP error rate / response time / thread count / down instances)
- SMTP connectivity test emails

> **Not implemented:** SSL certificate expiry reminders and periodic "system status reports" do not
> send email. `SslCertificateService.java` contains no mail/notification code (grep `mail|notif`
> returns zero hits), and no `SSL_EXPIRY` alert type exists. Certificate expiry is only surfaced in
> the UI/API.

---

## Configuration

Email settings are persisted as an `EmailConfig` entity
(`gateway-admin/src/main/java/com/leoli/gateway/admin/model/EmailConfig.java`). **The field names
below are the real ones** — `host`, `port`, `username`, `password` and `from` do not exist, so
submitting them would be silently ignored.

```json
{
  "configName": "Default SMTP Config",
  "smtpHost": "smtp.example.com",
  "smtpPort": 465,
  "smtpUsername": "alerts@example.com",
  "smtpPassword": "password",
  "useSsl": true,
  "useStartTls": false,
  "fromEmail": "alerts@example.com",
  "fromName": "Gateway Alerts",
  "enabled": true
}
```

| Parameter | Description | Default | Source |
|-----------|-------------|---------|--------|
| `configName` | Config profile name | `Default SMTP Config` | `EmailConfig.java:26` |
| `smtpHost` | SMTP server address | *(none)* | `:32` |
| `smtpPort` | SMTP port | **`465`** | `:38` |
| `smtpUsername` | Authentication username | *(none)* | `:44` |
| `smtpPassword` | Authentication password | *(none)* | `:50` |
| `useSsl` | Use implicit SSL/TLS | **`true`** | `:56` |
| `useStartTls` | Use STARTTLS | **`false`** | `:62` |
| `fromEmail` | Sender address | *(none)* | `:68` |
| `fromName` | Sender display name (a **separate** field, not part of `fromEmail`) | *(none)* | `:74` |
| `enabled` | Whether this config is active | `true` | `:80` |
| `testStatus` / `lastTestTime` / `lastTestError` | Read-only result of the last test email | *(none)* | `:86,92,98` |

Note the defaults: the port is **465 with `useSsl: true`**, not 587 with STARTTLS. If your provider
only offers STARTTLS on 587, set `smtpPort: 587`, `useSsl: false`, `useStartTls: true`.

---

## API Endpoints

Base path `@RequestMapping("/api/email")` (`EmailConfigController.java:26`). There is **no**
`/api/email/config` (singular); updates are per-config and require the `{id}` path variable.

| Method | Path | Description | Source |
|--------|------|-------------|--------|
| `GET` | `/api/email/configs` | List email configs | `:37` |
| `GET` | `/api/email/info` | Active SMTP info, password excluded | `:54` |
| `GET` | `/api/email/configs/{id}` | Get one config | `:68` |
| `POST` | `/api/email/configs` | Create a config | `:88` |
| `PUT` | `/api/email/configs/{id}` | Update a config | `:121` |
| `DELETE` | `/api/email/configs/{id}` | Delete a config | `:165` |
| `POST` | `/api/email/test` | Send test email | `:189` |
| `GET` | `/api/email/status` | Whether email is configured (`data.configured`) | `:225` |
| `POST` | `/api/email/reload` | Reload the SMTP configuration from the database | `:236` |

**Auth:** every POST/PUT/DELETE under `/api/**` requires the `ADMIN` authority and even GETs require
a valid JWT (`SecurityConfig.java:122-127`). The examples below therefore need an
`Authorization: Bearer <ADMIN_JWT>` header; without it you get 401/403.

### Test Email

```bash
curl -X POST http://localhost:9090/api/email/test \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -d '{
    "to": "admin@example.com"
  }'
```

Response — a standard `ApiResponse` (`{code, message, data, traceId, timestamp}`); there is **no
top-level `success` boolean** (`EmailConfigController.java:189-219`):

```json
{
  "code": 200,
  "message": "Test email sent to admin@example.com",
  "data": null,
  "timestamp": 1704067200000
}
```

On failure the same endpoint returns `code: 500` with
`message: "Failed to send email. Check SMTP configuration."`; a missing/empty `to` returns
`code: 400` with `message: "Recipient email is required"`. The outcome is also recorded on the
active config (`testStatus`, `lastTestTime`, `lastTestError`).

---

## Email Templates

There are **no `.html` template files** in this project. `find gateway-admin/src/main/resources
-iname "*.html"` returns only `static/index.html` (the SPA shell). Alert mail bodies are built in
Java as inline text blocks by
`gateway-admin/src/main/java/com/leoli/gateway/admin/service/AlertEmailBuilder.java`:

| Method | Purpose | Source |
|--------|---------|--------|
| `buildAlertEmailBody(alertType, level, metricName, …)` | Entry point; returns a full `<!DOCTYPE html>` document | `:16` |
| `buildAlertTitle(alertType, level, language)` | Subject line | `:29` |
| `buildTestEmailBody(language)` | Test-email body | `:36` |
| `buildZhBody(...)` | Chinese HTML body (text block at `:55-85`) | `:52` |
| `buildEnBody(...)` | English HTML body (text block at `:93+`) | `:90` |

The body carries the alert type, level, metric name, current value, threshold and instance, in the
user's language — it is generated from live values, not from a static template asset, and it is not
AI-written.

---

## Notification Scenarios

Alerts are produced by `AlertCheckService`, which compares collected metrics against the alert
threshold config and, when a threshold is crossed, sends through `EmailSenderService`
(`AlertCheckService.java:307 sendEmailWithResult`, `:505`/`:534 sendEmail`). There are no
per-scenario template files — all of them render via `AlertEmailBuilder`.

| Scenario | Alert type | Trigger | Default threshold | Status |
|----------|-----------|---------|-------------------|--------|
| **CPU (process)** | `CPU_PROCESS` | `cpu.processUsage` > threshold | 80 % (`AlertCheckService.java:48,138`) | Implemented |
| **CPU (system)** | `CPU_SYSTEM` | `cpu.systemUsage` > threshold | 80 % (`:48,139`) | Implemented |
| **Memory** | `MEMORY_HEAP` | `jvmMemory.heapUsagePercent` > threshold | 85 % (`:49,159`) | Implemented |
| **Error rate** | `HTTP_ERROR_RATE` | `httpRequests.errorRate` > threshold | 5 % (`:50,180`) | Implemented |
| **Response time** | `RESPONSE_TIME` | `httpRequests.avgResponseTimeMs` > threshold | 2000 ms (`:51,181`) | Implemented |
| **Thread count** | `THREAD_COUNT` | `threads.liveThreads` > threshold (only evaluated when threshold > 50) | 90 (`:52,201,205-207`) | Implemented |
| **Instance down** | `INSTANCE_DOWN` | count of instances whose status != `UP` >= threshold | 1 (`:53,230`) | Implemented (indirect) |
| **SSL expiry** | — | — | — | **Not implemented** |

Notes:
- A `CRITICAL` alert is only sent when the value exceeds `threshold * 1.2` (`AlertCheckService.java:55-56`).
- "Instance down" is indirect: a missed heartbeat only flips the instance status to `ERROR`
  (`InstanceHealthCheckTask.java:104-113`) and sends nothing itself; the scheduled alert check then
  counts non-`UP` instances and emails `INSTANCE_DOWN`.
- Only CPU, memory, HTTP, thread and instance categories are checked — no error-count, latency
  percentile or certificate-expiry scenario exists.

---

## Best Practices

1. **SMTP Configuration**: Use enterprise email services
2. **Encryption**: The defaults are `smtpPort: 465` + `useSsl: true` (implicit TLS). For a
   STARTTLS-only provider set `smtpPort: 587`, `useSsl: false`, `useStartTls: true`.
3. **Alert Levels**: WARNING is sent at the threshold, CRITICAL only above `threshold * 1.2`
4. **Recipient Management**: Set recipients based on alert type
5. **Test Validation**: Send test email after configuration (`POST /api/email/test`), then check
   `GET /api/email/status` and the config's `testStatus` / `lastTestError`

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - Alert trigger source
- [SSL Termination](ssl-termination.md) - Certificate management (expiry is **not** emailed today)
- [Gateway Instance Management](instance-management.md) - Instance status alerts
- [AI-Powered Analysis](ai-analysis.md) - AI analysis (alert email bodies are **not** AI-generated; see [Email Templates](#email-templates))