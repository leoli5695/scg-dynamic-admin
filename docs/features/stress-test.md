# Stress Test Tool

> 压力测试工具模拟并发负载，测量 Gateway 性能。

---

## Overview

压力测试功能：

| Feature | Description |
|---------|-------------|
| **Custom Configuration** | 配置目标 URL、方法、Headers、Body |
| **Concurrent Users** | 模拟多用户并发 |
| **Real-time Progress** | 实时查看测试进度 |
| **Detailed Statistics** | P50, P90, P95, P99 延迟分布 |
| **AI Analysis** | AI 分析测试结果 |
| **Quick Test** | 一键快速测试 |

---

## Test Configuration

```json
{
  "testName": "API Gateway Load Test",
  "targetUrl": "http://gateway:80",
  "path": "/api/users",
  "method": "GET",
  "headers": {"Authorization": "Bearer token"},
  "body": null,
  "concurrentUsers": 10,
  "totalRequests": 10000,
  "targetQps": 1000,
  "rampUpSeconds": 5,
  "requestTimeoutSeconds": 30
}
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `testName` | 测试名称 | - |
| `targetUrl` | 目标 URL（可选，使用实例 URL） | - |
| `path` | 路径（追加到实例 URL） | - |
| `method` | HTTP 方法 | `GET` |
| `headers` | 请求 Headers | - |
| `body` | 请求 Body（POST/PUT） | - |
| `concurrentUsers` | 并发用户数 | `10` |
| `totalRequests` | 总请求数 | `1000` |
| `targetQps` | 目标 QPS 限制 | - |
| `rampUpSeconds` | 渐进加载时间 | `0` |
| `requestTimeoutSeconds` | 请求超时 | `30` |

---

## Metrics Collected

| Metric | Description |
|--------|-------------|
| `actualRequests` | 实际发送请求数 |
| `successfulRequests` | 成功请求数 (2xx) |
| `failedRequests` | 失败请求数 (4xx/5xx) |
| `minResponseTimeMs` | 最小响应时间 |
| `maxResponseTimeMs` | 最大响应时间 |
| `avgResponseTimeMs` | 平均响应时间 |
| `p50ResponseTimeMs` | 50th 百分位延迟 |
| `p90ResponseTimeMs` | 90th 百分位延迟 |
| `p95ResponseTimeMs` | 95th 百分位延迟 |
| `p99ResponseTimeMs` | 99th 百分位延迟 |
| `requestsPerSecond` | 实际 QPS |
| `errorRate` | 错误率百分比 |
| `throughputKbps` | 吞吐量 KB/s |

---

## Test Status

| Status | Description |
|--------|-------------|
| `RUNNING` | 正在执行 |
| `COMPLETED` | 成功完成 |
| `STOPPED` | 用户停止 |
| `FAILED` | 执行失败 |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/stress-test/instance/{instanceId}` | List tests |
| `POST` | `/api/stress-test/start` | Start test |
| `POST` | `/api/stress-test/quick` | Quick test |
| `GET` | `/api/stress-test/{testId}/status` | Get status |
| `POST` | `/api/stress-test/{testId}/stop` | Stop test |
| `DELETE` | `/api/stress-test/{testId}` | Delete record |
| `GET` | `/api/stress-test/{testId}/analyze` | AI analysis |

### Start Test

```bash
curl -X POST http://localhost:9090/api/stress-test/start \
  -H "Content-Type: application/json" \
  -d '{
    "instanceId": 1,
    "testName": "Load Test",
    "concurrentUsers": 20,
    "totalRequests": 5000
  }'
```

### Test Result Example

```json
{
  "id": 1,
  "testName": "API Gateway Load Test",
  "status": "COMPLETED",
  "actualRequests": 10000,
  "successfulRequests": 9950,
  "failedRequests": 50,
  "minResponseTimeMs": 5,
  "maxResponseTimeMs": 2500,
  "avgResponseTimeMs": 45,
  "p50ResponseTimeMs": 35,
  "p90ResponseTimeMs": 80,
  "p95ResponseTimeMs": 120,
  "p99ResponseTimeMs": 500,
  "requestsPerSecond": 833.3,
  "errorRate": 0.5,
  "throughputKbps": 1250
}
```

---

## Execution Flow

```
┌─────────────────────────────────────────────┐
│         LOAD GENERATION FLOW                 │
│                                              │
│   Test Started                               │
│          │                                   │
│          ▼                                   │
│   ┌─────────────────┐                        │
│   │ Create Executor │                        │
│   │ Pool            │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            │ Ramp-up phase                   │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Gradual User    │                        │
│   │ Addition        │                        │
│   │ - Start with 1  │                        │
│   │ - Add over      │                        │
│   │   rampUpSeconds │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            │ Steady state                    │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Request Dispatch│                        │
│   │ - Track results │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            │ Test completed                  │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Final Statistics│                        │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

---

## Best Practices

1. **渐进加载**：使用 rampUp 避免突发负载
2. **监控并发**：测试期间监控 Gateway 状态
3. **多次测试**：不同参数多次测试对比
4. **AI 分析**：利用 AI 分析测试结果
5. **生产谨慎**：生产环境测试需谨慎

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - 测试期间监控
- [AI-Powered Analysis](ai-analysis.md) - 结果分析