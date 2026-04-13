# Email Notifications

> Email notifications support SMTP configuration for sending alerts and system notifications.

---

## Overview

Email notifications are used for:
- Alert notifications
- SSL certificate expiration reminders
- System status reports

---

## Configuration

```json
{
  "host": "smtp.example.com",
  "port": 587,
  "username": "alerts@example.com",
  "password": "password",
  "from": "Gateway Alerts <alerts@example.com>",
  "useStartTls": true,
  "enabled": true
}
```

| Parameter | Description |
|-----------|-------------|
| `host` | SMTP server address |
| `port` | SMTP port |
| `username` | Authentication username |
| `password` | Authentication password |
| `from` | Sender address |
| `useStartTls` | Whether to use STARTTLS |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/email/config` | Get email config |
| `PUT` | `/api/email/config` | Update email config |
| `POST` | `/api/email/test` | Send test email |

### Test Email

```bash
curl -X POST http://localhost:9090/api/email/test \
  -H "Content-Type: application/json" \
  -d '{
    "to": "admin@example.com"
  }'
```

Response:
```json
{
  "success": true,
  "message": "Test email sent successfully"
}
```

---

## Email Templates

Alert emails use HTML templates:

```html
Subject: [ALERT] Gateway Alert

<html>
  <head>
    <style>
      .alert { color: red; }
      .recommendation { color: green; }
    </style>
  </head>
  <body>
    <h1>Gateway Alert</h1>
    <p class="alert">CPU usage exceeded threshold</p>
    <p>Current: 85%, Threshold: 80%</p>
    <h2>Recommendations</h2>
    <ul class="recommendation">
      <li>Check for abnormal traffic</li>
      <li>Consider adding instances</li>
    </ul>
  </body>
</html>
```

---

## Notification Scenarios

| Scenario | Trigger | Template |
|----------|---------|----------|
| **CPU Alert** | CPU > threshold | cpu-alert.html |
| **Memory Alert** | Memory > threshold | memory-alert.html |
| **Error Rate Alert** | Error rate > threshold | error-alert.html |
| **SSL Expiry** | Certificate expiring | ssl-expiry.html |
| **Instance Down** | Heartbeat missed | instance-down.html |

---

## Best Practices

1. **SMTP Configuration**: Use enterprise email services
2. **STARTTLS**: Enable encrypted transmission
3. **Alert Levels**: Send different emails for different severity levels
4. **Recipient Management**: Set recipients based on alert type
5. **Test Validation**: Send test email after configuration

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - Alert trigger source
- [SSL Termination](ssl-termination.md) - Certificate expiration reminders
- [Gateway Instance Management](instance-management.md) - Instance status alerts
- [AI-Powered Analysis](ai-analysis.md) - AI-generated email content