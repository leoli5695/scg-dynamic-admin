# Monitoring & Alerts

> Gateway 提供实时监控和告警功能，支持 JVM、CPU、HTTP 等指标。

---

## Overview

监控指标分类：

| Category | Metrics |
|----------|---------|
| **JVM** | Heap usage, GC count/time, thread count |
| **CPU** | Process usage, System usage |
| **Memory** | Used/Max memory |
| **HTTP** | Requests/sec, Avg response time, Error rate |
| **Status** | 2xx/4xx/5xx distribution |

---

## Metrics API

```bash
# Get current metrics
GET /api/monitor/metrics

# Get historical metrics
GET /api/monitor/history?range=1h
```

### Response Example

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "jvm": {
    "heapUsed": 512,
    "heapMax": 1024,
    "heapUsagePercent": 50,
    "gcCount": 10,
    "gcTimeMs": 100
  },
  "cpu": {
    "processUsage": 45,
    "systemUsage": 30
  },
  "http": {
    "requestsPerSecond": 1234,
    "avgResponseTimeMs": 45,
    "errorRatePercent": 0.5,
    "status2xx": 9950,
    "status4xx": 30,
    "status5xx": 20
  }
}
```

---

## Alert Thresholds

```json
{
  "cpu": {
    "processThreshold": 80,
    "systemThreshold": 90
  },
  "memory": {
    "heapThreshold": 85
  },
  "http": {
    "errorRateThreshold": 5,
    "responseTimeThreshold": 2000
  }
}
```

| Metric | Threshold | Action |
|--------|-----------|--------|
| CPU Process | > 80% | WARNING alert |
| CPU System | > 90% | CRITICAL alert |
| Heap Usage | > 85% | WARNING alert |
| Error Rate | > 5% | WARNING alert |
| Response Time | > 2000ms | WARNING alert |

---

## Alert Notification

### Email Notification

配置 SMTP：

```json
{
  "host": "smtp.example.com",
  "port": 587,
  "username": "alerts@example.com",
  "password": "password",
  "from": "Gateway Alerts <alerts@example.com>",
  "useStartTls": true
}
```

### AI-Generated Content

告警邮件由 AI 生成，包含：
- 问题描述
- 影响范围
- 建议措施

---

## Dashboard

Admin UI 提供实时监控仪表板：
- JVM 内存图表
- CPU 使用趋势
- HTTP QPS 曲线
- 响应时间分布
- 错误率统计

---

## Prometheus Integration

Gateway 暴露 Prometheus 指标：

```
# Endpoint
GET /actuator/prometheus

# Key Metrics
gateway_requests_total
gateway_requests_duration_seconds
gateway_active_connections
jvm_memory_used_bytes
process_cpu_usage
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/monitor/metrics` | Current metrics |
| `GET` | `/api/monitor/history` | Historical metrics |
| `POST` | `/api/monitor/analyze` | AI analysis |
| `GET` | `/api/alerts/config` | Get alert config |
| `PUT` | `/api/alerts/config` | Update alert config |
| `GET` | `/api/alerts/history` | Alert history |

---

## Best Practices

1. **阈值调整**：根据业务场景调整阈值
2. **告警分级**：区分 WARNING 和 CRITICAL
3. **通知渠道**：多渠道通知（邮件、钉钉）
4. **历史分析**：定期分析历史数据
5. **AI 分析**：利用 AI 发现潜在问题

---

## Related Features

- [AI-Powered Analysis](ai-analysis.md) - AI 分析功能
- [Email Notifications](email-notifications.md) - 邮件配置
- [Request Tracing](request-tracing.md) - 错误请求追踪