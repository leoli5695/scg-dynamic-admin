# System Diagnostic

> 系统诊断功能提供全面的健康检查，帮助识别潜在问题。

---

## Overview

系统诊断检查所有关键组件：

```
┌─────────────────────────────────────────────┐
│         DIAGNOSTIC ARCHITECTURE              │
│                                              │
│   User Request (quick/full)                  │
│          │                                   │
│          ▼                                   │
│   ┌─────────────────┐                        │
│   │ Diagnostic      │                        │
│   │ Service         │                        │
│   └────────┬────────┘                        │
│            │                                 │
│   ┌────┴────┬────┬────┬────┬────┐           │
│   │    │    │    │    │    │    │           │
│   ▼    ▼    ▼    ▼    ▼    ▼    ▼           │
│  DB  Redis Nacos Route Auth Inst Perf        │
│                                              │
│   ┌─────────────────┐                        │
│   │ Diagnostic      │                        │
│   │ Report          │                        │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

---

## Diagnostic Types

| Type | Duration | Description |
|------|----------|-------------|
| `quick` | ~2-5 seconds | 快速检查关键组件 |
| `full` | ~30-60 seconds | 完整诊断详细分析 |

---

## Components Checked

| Component | Checks |
|-----------|--------|
| **Database** | 连接状态、查询延迟、表完整性 |
| **Redis** | 连接状态、内存使用、Key 数量 |
| **Nacos** | 连接状态、配置同步状态 |
| **Routes** | 路由数量、启用状态、无效路由 |
| **Authentication** | 认证策略状态、JWT 验证测试 |
| **Gateway Instances** | 实例数量、健康状态、心跳检查 |
| **Performance** | CPU、内存、线程数、JVM 指标 |

---

## Health Status

| Status | Description |
|--------|-------------|
| `HEALTHY` | 所有检查通过 |
| `WARNING` | 发现轻微问题 |
| `CRITICAL` | 发现严重问题 |
| `NOT_CONFIGURED` | 组件未配置 |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/diagnostic/quick` | Quick diagnostic |
| `GET` | `/api/diagnostic/full` | Full diagnostic |

### Response Example

```json
{
  "overallScore": 85,
  "status": "HEALTHY",
  "duration": "5.2s",
  "recommendations": [
    "Consider increasing Redis memory allocation",
    "Review expired SSL certificates"
  ],
  "database": {
    "status": "HEALTHY",
    "metrics": {
      "connectionPoolSize": 10,
      "activeConnections": 3,
      "avgQueryLatencyMs": 12
    }
  },
  "redis": {
    "status": "WARNING",
    "warnings": ["Memory usage above 80%"],
    "metrics": {
      "connected": true,
      "memoryUsageMB": 512,
      "keyCount": 15000
    }
  },
  "nacos": {
    "status": "HEALTHY",
    "metrics": {
      "connected": true,
      "configSyncStatus": "SYNCED"
    }
  }
}
```

---

## Scoring Algorithm

```
Component Scores:
- Database:    100 if HEALTHY, 50 if WARNING, 0 if CRITICAL
- Redis:       100 if HEALTHY, 50 if WARNING, 0 if CRITICAL
- Nacos:       100 if HEALTHY, 50 if WARNING, 0 if CRITICAL
- Routes:      Based on enabled/valid route percentage
- Auth:        100 if policies valid, 0 if issues
- Instances:   Based on healthy instance percentage
- Performance: Based on CPU/memory thresholds

Overall Score = Weighted Average:
- Database:    20%
- Redis:       15%
- Nacos:       20%
- Routes:      15%
- Auth:        10%
- Instances:   10%
- Performance: 10%
```

---

## Best Practices

1. **定期诊断**：设置定时诊断任务
2. **关注 WARNING**：及时处理预警问题
3. **完整诊断**：重大变更后执行完整诊断
4. **监控历史**：对比历史诊断结果
5. **AI 分析**：结合 AI 分析诊断报告

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - 监控数据源
- [AI-Powered Analysis](ai-analysis.md) - AI 分析诊断结果
- [Gateway Instance Management](instance-management.md) - 实例健康检查