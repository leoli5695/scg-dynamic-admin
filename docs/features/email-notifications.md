# Email Notifications

> 邮件通知功能支持 SMTP 配置，发送告警和系统通知。

---

## Overview

邮件通知用于：
- 告警通知
- SSL 证书到期提醒
- 系统状态报告

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
| `host` | SMTP 服务器地址 |
| `port` | SMTP 端口 |
| `username` | 认证用户名 |
| `password` | 认证密码 |
| `from` | 发件人地址 |
| `useStartTls` | 是否使用 STARTTLS |

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

告警邮件使用 HTML 模板：

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

1. **SMTP 配置**：使用企业邮箱服务
2. **STARTTLS**：启用加密传输
3. **告警分级**：不同级别发送不同邮件
4. **接收人管理**：根据告警类型设置接收人
5. **测试验证**：配置后发送测试邮件

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - 告警触发源
- [SSL Termination](ssl-termination.md) - 证书到期提醒
- [Gateway Instance Management](instance-management.md) - 实例状态告警
- [AI-Powered Analysis](ai-analysis.md) - AI 生成邮件内容