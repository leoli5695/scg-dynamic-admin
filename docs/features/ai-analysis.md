# AI-Powered Analysis

> AI 分析功能集成多个大模型，提供智能指标分析和告警内容生成。

---

## Overview

Gateway 支持以下 AI Provider：

| Provider | Models | Region |
|----------|--------|--------|
| **OpenAI** | GPT-4, GPT-3.5-turbo | Overseas |
| **Anthropic** | Claude 3 | Overseas |
| **Qwen** | qwen-plus, qwen-turbo | Domestic (China) |
| **DeepSeek** | deepseek-chat | Domestic (China) |
| **Ollama** | llama2, mistral | Local |

---

## Features

| Feature | Description |
|---------|-------------|
| **Metrics Analysis** | 分析当前指标，识别异常 |
| **Alert Generation** | AI 生成告警内容和建议 |
| **Trend Prediction** | 预测资源需求趋势 |
| **Anomaly Detection** | 检测异常流量模式 |

---

## Configuration

```json
{
  "provider": "qwen",
  "apiKey": "your-api-key",
  "model": "qwen-plus",
  "baseUrl": "https://api.qwen.ai",
  "language": "zh"
}
```

| Parameter | Description |
|-----------|-------------|
| `provider` | AI Provider 名称 |
| `apiKey` | API Key（加密存储） |
| `model` | 模型名称 |
| `baseUrl` | 自定义 API 端点（可选） |
| `language` | 输出语言（zh/en） |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/ai/providers` | List available providers |
| `GET` | `/api/ai/providers/{provider}/models` | Get available models |
| `POST` | `/api/ai/validate` | Validate API key |
| `POST` | `/api/ai/config` | Save AI configuration |
| `POST` | `/api/ai/analyze` | Analyze metrics |

### Analyze Metrics

```bash
curl -X POST http://localhost:9090/api/ai/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "metrics": {...},
    "language": "zh"
  }'
```

Response:
```json
{
  "analysis": "当前系统状态整体健康...",
  "recommendations": [
    "建议增加 Redis 内存分配",
    "考虑对高频路由启用缓存"
  ],
  "alerts": []
}
```

---

## AI Alert Content

告警邮件内容由 AI 生成：

```
Subject: [WARNING] Gateway CPU 使用率过高

正文：
当前 Gateway CPU 使用率已达 85%，超过设定的阈值（80%）。

影响分析：
1. 可能导致请求处理延迟增加
2. 高峰期可能出现请求超时

建议措施：
1. 检查是否有异常流量来源
2. 考虑增加 Gateway 实例
3. 优化资源消耗高的路由

点击此处查看详细监控数据...
```

---

## Context Building

AI 分析时会构建上下文：

```
Metrics Analysis Context:
┌─────────────────────────────────────────────┐
│  - Gateway architecture description          │
│  - Current metrics (CPU, memory, QPS)        │
│  - Historical data trends                    │
│  - Route configuration                       │
│  - Recent error patterns                     │
└─────────────────────────────────────────────┘
```

---

## Best Practices

1. **选择合适 Provider**：国内用 Qwen/DeepSeek，海外用 OpenAI/Claude
2. **本地部署**：敏感场景使用 Ollama 本地模型
3. **API Key 安全**：定期更换，限制权限
4. **定期分析**：设置定时 AI 分析任务
5. **结合监控**：AI 分析作为监控补充

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - 监控数据源
- [Email Notifications](email-notifications.md) - AI 生成邮件内容
- [AI Copilot Assistant](ai-copilot.md) - AI 配置助手