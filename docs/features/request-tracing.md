# Request Tracing

> 请求追踪功能捕获错误和慢请求，支持多实例查询、按路由/IP筛选、时间范围统计，为请求重放调试提供数据源。

---

## Overview

请求追踪自动捕获：

| Capture Type | Condition | Storage |
|--------------|-----------|---------|
| **ERROR** | HTTP 4xx/5xx | 完整请求/响应 |
| **SLOW** | 超过阈值（可配置） | 完整请求/响应 |
| **ALL** | 采样模式（可选） | 采样请求 |

---

## Trace Data Fields

每条追踪记录包含：

| Field | Description | Max Size |
|-------|-------------|----------|
| `traceId` | UUID 格式追踪 ID | 36 chars |
| `instanceId` | 网关实例 ID | 12 chars |
| `routeId` | 匹配的路由 ID | UUID |
| `method` | HTTP 方法 | GET/POST/PUT/DELETE |
| `uri` | 完整 URI | - |
| `path` | 请求路径 | - |
| `queryString` | Query 参数 | - |
| `requestHeaders` | 请求 Headers JSON | - |
| `requestBody` | 请求体 | 64KB（超截断） |
| `statusCode` | 响应状态码 | 100-599 |
| `responseBody` | 响应体 | 64KB（超截断） |
| `latencyMs` | 响应时间（毫秒） | - |
| `clientIp` | 客户端 IP | - |
| `userAgent` | User-Agent | - |
| `targetInstance` | 目标后端实例 | IP:Port |
| `traceTime` | 时间戳 | LocalDateTime |
| `traceType` | 类型 | ERROR/SLOW/ALL |
| `replayType` | 重放类型 | HTTP/WEBSOCKET/SSE |
| `replayable` | 是否可重放 | true/false |
| `replayCount` | 已重放次数 | - |
| `replayResult` | 最后重放结果 | - |

---

## Multi-Instance Support

所有查询支持按实例筛选：

| API | Instance Filter |
|-----|-----------------|
| `/api/traces` | `?instanceId=gateway-1` |
| `/api/traces/errors` | `?instanceId=gateway-1` |
| `/api/traces/slow` | `?instanceId=gateway-1` |
| `/api/traces/stats` | `?instanceId=gateway-1` |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/traces` | 分页查询所有 traces |
| `GET` | `/api/traces/errors` | 错误请求（4xx/5xx） |
| `GET` | `/api/traces/slow` | 慢请求 |
| `GET` | `/api/traces/{id}` | Trace 详情 |
| `GET` | `/api/traces/stats` | 统计摘要 |
| `GET` | `/api/traces/route/{routeId}` | 按路由查询 |
| `GET` | `/api/traces/client/{ip}` | 按客户端 IP 查询 |
| `GET` | `/api/traces/time-range` | 按时间范围查询 |
| `DELETE` | `/api/traces/{id}` | 删除单个 trace |
| `DELETE` | `/api/traces/old` | 清理旧 traces |

### List Error Traces

```bash
curl "http://localhost:9090/api/traces/errors?instanceId=gateway-1&page=0&size=20"
```

Response:
```json
{
  "content": [
    {
      "id": 123,
      "traceId": "abc-123",
      "instanceId": "gateway-1",
      "routeId": "user-api-route",
      "method": "POST",
      "path": "/api/users",
      "statusCode": 500,
      "latencyMs": 1500,
      "clientIp": "192.168.1.100",
      "traceTime": "2024-01-15T10:30:00",
      "traceType": "ERROR",
      "replayable": true
    }
  ],
  "totalElements": 45,
  "totalPages": 3,
  "page": 0,
  "size": 20
}
```

### Get Trace Statistics

```bash
curl "http://localhost:9090/api/traces/stats?instanceId=gateway-1"
```

Response:
```json
{
  "total": 1234,
  "errorsToday": 45,
  "errorsLastHour": 12,
  "recentErrors": [
    {"traceId": "abc-123", "path": "/api/users", "statusCode": 500}
  ]
}
```

---

## Query by Dimension

### By Route ID

```bash
GET /api/traces/route/user-api-route?instanceId=gateway-1
```

用途：分析特定路由的错误模式和响应时间分布。

### By Client IP

```bash
GET /api/traces/client/192.168.1.100?instanceId=gateway-1
```

用途：追踪特定客户端的请求问题，识别异常客户端。

### By Time Range

```bash
GET /api/traces/time-range?start=2024-01-15T00:00:00&end=2024-01-15T23:59:59&instanceId=gateway-1
```

用途：分析特定时间段的问题，如故障时段排查。

---

## Slow Request Threshold

```bash
GET /api/traces/slow?thresholdMs=1000&instanceId=gateway-1
```

动态阈值查询，响应时间超过阈值的请求。

---

## Trace Type Detection

系统自动检测请求类型并标记：

| replayType | Detection Method | replayable |
|------------|------------------|------------|
| `HTTP` | 默认 | true |
| `WEBSOCKET` | Upgrade 头、ws://、wss:// | false |
| `SSE` | Accept: text/event-stream | false |

---

## Data Retention

清理旧数据：

```bash
DELETE /api/traces/old?daysToKeep=7&instanceId=gateway-1
```

按天保留，默认清理超过指定天数的 traces。

---

## Route Performance Statistics

AI Copilot 可调用的路由级统计：

```bash
# AI Tool: get_route_metrics
GET /internal/traces/route-metrics?hours=1&sortBy=count&limit=10
```

Response:
```json
{
  "timeRange": "1小时",
  "sortBy": "count",
  "totalRoutes": 25,
  "routeMetrics": [
    {
      "routeId": "user-api",
      "count": 15000,
      "avgLatencyMs": 45,
      "minLatencyMs": 10,
      "maxLatencyMs": 500,
      "errorCount": 12,
      "serverErrorCount": 5,
      "errorRate": 0.08
    }
  ]
}
```

排序选项：
- `count`: 按请求数排序（高流量路由）
- `avgLatency`: 按平均延迟排序（慢路由）
- `errorRate`: 按错误率排序（问题路由）

---

## Request Replay

重放捕获的请求用于调试：

```bash
POST /api/traces/{id}/replay?gatewayUrl=http://localhost:8080
```

Gateway 会：
1. 加载原始请求数据
2. 检查 replayable 标志
3. 重新执行请求（添加 X-Replay-Trace-Id Header）
4. 返回比较结果
5. 更新 replayCount 和 replayResult

### Replay Result

```json
{
  "success": true,
  "statusCode": 200,
  "latency": 45,
  "responseBody": "{\"id\": 1}",
  "requestUrl": "http://localhost:8080/api/users",
  "method": "POST"
}
```

---

## AI Copilot Integration

AI Copilot 可调用以下工具分析 traces：

| Tool | Capability |
|------|------------|
| `audit_query` | 查询审计日志（配置变更历史） |
| `get_route_metrics` | 获取路由级性能统计 |

---

## Configuration

```yaml
gateway:
  trace:
    enabled: true
    error-trace-enabled: true
    slow-trace-threshold-ms: 1000
    max-trace-count: 1000
    retention-days: 7
    max-body-size: 65536  # 64KB
```

---

## Body Truncation

请求/响应体超过 64KB 时自动截断：

```
{"data": "...very long content..."} → 
{"data": "...first 64KB...[TRUNCATED]"}
```

---

## Use Cases

| Use Case | Query | Benefit |
|----------|-------|---------|
| **故障排查** | `/errors` + traceId 详情 | 定位失败请求原因 |
| **性能分析** | `/slow?thresholdMs=500` | 识别慢请求模式 |
| **路由优化** | `/route/{routeId}` | 分析特定路由问题 |
| **客户端追踪** | `/client/{ip}` | 识别异常客户端 |
| **时段分析** | `/time-range` | 分析故障时段 |
| **容量规划** | `/stats` + `/route-metrics` | 评估流量分布 |

---

## Best Practices

1. **阈值设置**: 根据业务 P95 响应时间设置慢请求阈值
2. **定期清理**: 设置合理保留天数（7-30天）
3. **按实例查询**: 多实例部署时按 instanceId 筛选
4. **按路由分析**: 高错误率路由优先排查
5. **采样配置**: 生产环境可采样而非全量捕获
6. **敏感数据**: 注意请求体中的敏感信息
7. **重放验证**: 问题修复后重放验证效果

---

## Related Features

- [Request Replay Debugger](request-replay.md) - 详细重放功能
- [Filter Chain Analysis](filter-chain-analysis.md) - 过滤器执行分析
- [Monitoring & Alerts](monitoring-alerts.md) - 监控告警
- [AI Copilot](ai-copilot.md) - AI 工具调用分析