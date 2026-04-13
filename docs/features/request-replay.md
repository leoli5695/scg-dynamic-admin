# Request Replay Debugger

> 请求重放调试器支持修改并重放捕获的请求，对比原始响应和新响应。支持批量重放、并发控制、WebSocket/SSE 检测、深度 JSON 对比。

---

## Overview

请求重放流程：

```
┌─────────────────────────────────────────────┐
│         REQUEST REPLAY FLOW                  │
│                                              │
│   Select Trace Record                        │
│          │                                   │
│          ▼                                   │
│   ┌─────────────────┐                        │
│   │ Load Trace Data │                        │
│   │ - method        │                        │
│   │ - path          │                        │
│   │ - headers       │                        │
│   │ - body          │                        │
│   │ - replayType    │                        │
│   │ - replayable    │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Type Detection  │                        │
│   │ HTTP? WEBSOCKET?│                        │
│   │ SSE?            │                        │
│   └────────┬────────┘                        │
│            │                                 │
│    ┌───────┴───────┐                         │
│    │               │                         │
│    ▼               ▼                         │
│  HTTP          WEBSOCKET/SSE                 │
│  Replayable    Not Replayable                │
│    │               │                         │
│    ▼               ▼                         │
│   Edit         Return Metadata               │
│   Execute      with Warning                  │
│    │                                         │
│    ▼                                         │
│   ┌─────────────────┐                        │
│   │ Compare Results │                        │
│   │ - Status match  │                        │
│   │ - Latency diff  │                        │
│   │ - JSON deep diff│                        │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

---

## Request Type Detection

系统自动检测请求类型，决定是否可重放：

| Type | Detection | Replayable | Behavior |
|------|-----------|------------|----------|
| **HTTP** | 普通 HTTP 请求 | ✅ Yes | 可完整重放 |
| **WEBSOCKET** | Upgrade 头 或 ws:// | ❌ No | 返回错误提示 |
| **SSE** | Accept: text/event-stream | ❌ No | 返回错误提示 |

**WebSocket/SSE 不可重放原因**：使用持久流连接，无法用 HTTP Client 重现。

---

## Features

| Feature | Description |
|---------|-------------|
| **Request Capture** | 自动捕获错误和慢请求 |
| **Request Editing** | 修改路径、Headers、Body、Query 参数 |
| **Quick Replay** | 直接重放原始请求 |
| **Response Comparison** | 对比原始和新响应 |
| **JSON Deep Diff** | 深度对比 JSON 字段差异 |
| **Batch Replay** | 批量重放多个请求 |
| **Concurrency Control** | 并发数和请求间隔控制 |
| **Custom Target** | 指定重放目标 URL |
| **Sensitive Filter** | 自动过滤敏感 Header |
| **Error Classification** | 错误类型分类（连接/客户端/服务端） |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/replay/prepare/{traceId}` | 准备重放请求（检测类型） |
| `POST` | `/api/replay/execute/{traceId}` | 执行单个重放 |
| `POST` | `/api/replay/batch` | 批量重放 |
| `GET` | `/api/replay/batch/{sessionId}` | 查询批量状态 |
| `DELETE` | `/api/replay/batch/{sessionId}` | 取消批量重放 |

### Prepare Replay

```bash
GET /api/replay/prepare/123
```

Response (HTTP 请求):
```json
{
  "traceId": 123,
  "traceUuid": "abc-123",
  "method": "POST",
  "path": "/api/users",
  "queryString": "debug=true",
  "headers": {
    "Content-Type": "application/json"
  },
  "requestBody": "{\"name\": \"test\"}",
  "originalStatusCode": 500,
  "originalResponseBody": "{\"error\": \"timeout\"}",
  "originalLatencyMs": 1500,
  "replayType": "HTTP",
  "replayable": true
}
```

Response (WebSocket 请求):
```json
{
  "traceId": 123,
  "replayType": "WEBSOCKET",
  "replayable": false,
  "method": "GET",
  "path": "/ws/chat"
}
```

---

## Single Replay Execution

```bash
POST /api/replay/execute/123?instanceId=gateway-1
{
  "modifiedPath": "/api/v2/users",
  "modifiedQueryString": "debug=false",
  "modifiedHeaders": {
    "Authorization": "Bearer new-token"
  },
  "removedHeaders": ["X-Debug"],
  "modifiedBody": "{\"name\": \"test2\"}",
  "customTargetUrl": "http://test-env:8080",
  "compareWithOriginal": true
}
```

Response:
```json
{
  "success": true,
  "traceId": 123,
  "traceUuid": "abc-123",
  "method": "POST",
  "requestUrl": "http://test-env:8080/api/v2/users?debug=false",
  "statusCode": 200,
  "latencyMs": 45,
  "responseBody": "{\"id\": 1, \"name\": \"test2\"}",
  "responseHeaders": {
    "Content-Type": "application/json"
  },
  "comparison": {
    "originalStatus": 500,
    "replayedStatus": 200,
    "statusMatch": false,
    "originalLatencyMs": 1500,
    "replayedLatencyMs": 45,
    "latencyDiffMs": -1455,
    "bodyMatch": false,
    "bodyDiff": [
      {"field": "error", "originalValue": "timeout", "replayedValue": null, "type": "REMOVED"},
      {"field": "id", "originalValue": null, "replayedValue": 1, "type": "ADDED"},
      {"field": "name", "originalValue": "test", "replayedValue": "test2", "type": "CHANGED"}
    ]
  }
}
```

---

## Batch Replay

批量重放多个请求，支持并发控制和进度跟踪：

```bash
POST /api/replay/batch?instanceId=gateway-1
{
  "traceIds": [123, 124, 125, 126],
  "maxConcurrent": 5,
  "requestIntervalMs": 100,
  "compareWithOriginal": true
}
```

Response (Session ID):
```json
{
  "sessionId": "batch-replay-abc123",
  "totalTraces": 4,
  "status": "RUNNING"
}
```

### Query Batch Status

```bash
GET /api/replay/batch/batch-replay-abc123
```

Response:
```json
{
  "sessionId": "batch-replay-abc123",
  "totalTraces": 4,
  "completedTraces": 3,
  "failedTraces": 0,
  "progress": 75,
  "status": "RUNNING",
  "startTime": 1705309800000,
  "results": [
    {"success": true, "traceId": 123, "statusCode": 200, "latencyMs": 45},
    {"success": true, "traceId": 124, "statusCode": 200, "latencyMs": 52},
    {"success": true, "traceId": 125, "statusCode": 200, "latencyMs": 38}
  ]
}
```

### Cancel Batch Replay

```bash
DELETE /api/replay/batch/batch-replay-abc123
```

---

## Concurrency Control

批量重放参数：

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxConcurrent` | 5 | 最大并发请求数 |
| `requestIntervalMs` | 100 | 请求间隔（毫秒） |

使用 Semaphore 限制并发，避免压垮目标服务。

---

## Error Classification

重放失败时返回错误类型：

| ErrorType | Description | Example |
|-----------|-------------|---------|
| `CONNECTION_ERROR` | 网络连接问题 | Connection refused, Timeout |
| `CLIENT_ERROR` | HTTP 4xx 错误 | 400 Bad Request, 401 Unauthorized |
| `SERVER_ERROR` | HTTP 5xx 错误 | 500 Internal Error, 503 Unavailable |
| `UNSUPPORTED_TYPE` | WebSocket/SSE 不支持 | WebSocket requests cannot be replayed |

### User-Friendly Error Messages

系统将技术错误转换为友好提示：

| Technical Error | User-Friendly Message |
|-----------------|----------------------|
| Connection refused | "无法连接到目标服务 [URL] - 连接被拒绝。请检查：1. 目标服务是否运行 2. 端口是否正确 3. 防火墙配置" |
| Timeout | "连接超时 [URL] - 目标服务响应时间过长" |
| Unknown host | "无法解析主机名 [URL] - 请检查域名配置" |

---

## Sensitive Header Filtering

重放时自动过滤敏感 Header：

| Header | Behavior |
|--------|----------|
| `Authorization` | 编辑版本中移除，需手动添加新值 |
| `Cookie` | 同上 |
| `Set-Cookie` | 同上 |
| `Proxy-Authorization` | 同上 |
| `Host` | 重放时自动移除（会自动设置） |
| `Content-Length` | 重放时自动移除（会自动计算） |

---

## Request Modifications

支持的修改：

| Modification | Description |
|--------------|-------------|
| `modifiedPath` | 修改请求路径 |
| `modifiedQueryString` | 修改 Query 参数 |
| `modifiedHeaders` | 添加/修改 Headers |
| `removedHeaders` | 移除指定 Headers |
| `modifiedBody` | 修改请求体 |
| `customTargetUrl` | 指定重放目标 URL |

---

## JSON Deep Diff Algorithm

使用 JsonDiffService 进行深度对比：

```
┌─────────────────────────────────────────────┐
│         JSON DIFF ALGORITHM                  │
│                                              │
│   Original JSON      Replayed JSON           │
│          │                  │                │
│          ▼                  ▼                │
│   ┌─────────────────────────────────┐        │
│   │        JsonDiffService           │        │
│   │                                  │        │
│   │ 1. Parse both JSONs              │        │
│   │ 2. Flatten to path-based map     │        │
│   │ 3. Compare each path:            │        │
│   │    - Check existence             │        │
│   │    - Check value equality        │        │
│   │ 4. Generate diff list:           │        │
│   │    - ADDED: new field            │        │
│   │    - REMOVED: missing field      │        │
│   │    - CHANGED: value diff         │        │
│   │ 5. Support nested objects/arrays │        │
│   └─────────────────────────────────┘        │
│            │                                 │
│            ▼                                 │
│   List<JsonDiff>                             │
│   [{path, type, orig, replayed}]             │
└─────────────────────────────────────────────┘
```

---

## Use Cases

| Use Case | How to Use |
|----------|------------|
| **Debug Production Issues** | 重放失败请求，修改参数定位问题 |
| **Test Fixes** | 修改请求验证修复效果 |
| **API Version Migration** | 修改 path 对比新旧 API 响应差异 |
| **Performance Testing** | 对比不同配置的响应时间变化 |
| **批量验证** | 批量重放验证修复是否解决所有类似问题 |
| **环境切换** | 使用 customTargetUrl 在测试环境重放 |

---

## Replay Headers Added

重放请求会添加特殊 Header：

| Header | Value | Purpose |
|--------|-------|---------|
| `X-Replay-Trace-Id` | 原始 traceId | 关联原始请求 |
| `X-Replay-Mode` | `debug` | 标识为调试请求 |

---

## Best Practices

1. **敏感数据**: 重放前检查请求是否包含敏感数据
2. **类型检测**: WebSocket/SSE 请求无法重放，使用原始记录分析
3. **修改验证**: 修改参数后验证请求格式有效性
4. **批量控制**: 批量重放设置合理的并发数和间隔
5. **生产谨慎**: 生产环境重放需谨慎，避免影响正常流量
6. **进度监控**: 批量重放时定期查询进度
7. **错误分类**: 关注 errorType 区分问题类型

---

## Related Features

- [Request Tracing](request-tracing.md) - 请求追踪数据源
- [Filter Chain Analysis](filter-chain-analysis.md) - Filter 执行分析
- [AI Copilot](ai-copilot.md) - AI 可调用工具分析重放结果