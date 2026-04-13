# Request Replay Debugger

> Request Replay Debugger supports modifying and replaying captured requests, comparing original and new responses. Supports batch replay, concurrency control, WebSocket/SSE detection, and deep JSON comparison.

---

## Overview

Request replay workflow:

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

System automatically detects request type, determining if replayable:

| Type | Detection | Replayable | Behavior |
|------|-----------|------------|----------|
| **HTTP** | Normal HTTP request | Yes | Can fully replay |
| **WEBSOCKET** | Upgrade header or ws:// | No | Return error message |
| **SSE** | Accept: text/event-stream | No | Return error message |

**Why WebSocket/SSE cannot be replayed**: Uses persistent stream connections, cannot be reproduced with HTTP Client.

---

## Features

| Feature | Description |
|---------|-------------|
| **Request Capture** | Automatically capture errors and slow requests |
| **Request Editing** | Modify path, Headers, Body, Query parameters |
| **Quick Replay** | Directly replay original request |
| **Response Comparison** | Compare original and new responses |
| **JSON Deep Diff** | Deep compare JSON field differences |
| **Batch Replay** | Batch replay multiple requests |
| **Concurrency Control** | Concurrency count and request interval control |
| **Custom Target** | Specify replay target URL |
| **Sensitive Filter** | Automatically filter sensitive Headers |
| **Error Classification** | Error type classification (connection/client/server) |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/replay/prepare/{traceId}` | Prepare replay request (detect type) |
| `POST` | `/api/replay/execute/{traceId}` | Execute single replay |
| `POST` | `/api/replay/batch` | Batch replay |
| `GET` | `/api/replay/batch/{sessionId}` | Query batch status |
| `DELETE` | `/api/replay/batch/{sessionId}` | Cancel batch replay |

### Prepare Replay

```bash
GET /api/replay/prepare/123
```

Response (HTTP Request):
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

Response (WebSocket Request):
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

Batch replay multiple requests with concurrency control and progress tracking:

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

Batch replay parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxConcurrent` | 5 | Maximum concurrent requests |
| `requestIntervalMs` | 100 | Request interval (milliseconds) |

Use Semaphore to limit concurrency, avoid overwhelming target service.

---

## Error Classification

Returns error type on replay failure:

| ErrorType | Description | Example |
|-----------|-------------|---------|
| `CONNECTION_ERROR` | Network connection issue | Connection refused, Timeout |
| `CLIENT_ERROR` | HTTP 4xx error | 400 Bad Request, 401 Unauthorized |
| `SERVER_ERROR` | HTTP 5xx error | 500 Internal Error, 503 Unavailable |
| `UNSUPPORTED_TYPE` | WebSocket/SSE not supported | WebSocket requests cannot be replayed |

### User-Friendly Error Messages

System converts technical errors to friendly messages:

| Technical Error | User-Friendly Message |
|-----------------|----------------------|
| Connection refused | "Cannot connect to target service [URL] - Connection refused. Please check: 1. Target service is running 2. Port is correct 3. Firewall configuration" |
| Timeout | "Connection timeout [URL] - Target service response time too long" |
| Unknown host | "Cannot resolve hostname [URL] - Please check domain configuration" |

---

## Sensitive Header Filtering

Automatically filter sensitive Headers during replay:

| Header | Behavior |
|--------|----------|
| `Authorization` | Remove in edit version, need to manually add new value |
| `Cookie` | Same as above |
| `Set-Cookie` | Same as above |
| `Proxy-Authorization` | Same as above |
| `Host` | Automatically remove during replay (will be set automatically) |
| `Content-Length` | Automatically remove during replay (will be calculated automatically) |

---

## Request Modifications

Supported modifications:

| Modification | Description |
|--------------|-------------|
| `modifiedPath` | Modify request path |
| `modifiedQueryString` | Modify Query parameters |
| `modifiedHeaders` | Add/Modify Headers |
| `removedHeaders` | Remove specified Headers |
| `modifiedBody` | Modify request body |
| `customTargetUrl` | Specify replay target URL |

---

## JSON Deep Diff Algorithm

Use JsonDiffService for deep comparison:

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
| **Debug Production Issues** | Replay failed requests, modify parameters to locate issues |
| **Test Fixes** | Modify requests to verify fix effectiveness |
| **API Version Migration** | Modify path to compare old and new API response differences |
| **Performance Testing** | Compare response time changes under different configurations |
| **Batch Verification** | Batch replay to verify if fix resolves all similar issues |
| **Environment Switch** | Use customTargetUrl to replay in test environment |

---

## Replay Headers Added

Replay requests add special Headers:

| Header | Value | Purpose |
|--------|-------|---------|
| `X-Replay-Trace-Id` | Original traceId | Associate with original request |
| `X-Replay-Mode` | `debug` | Mark as debug request |

---

## Best Practices

1. **Sensitive Data**: Check if request contains sensitive data before replay
2. **Type Detection**: WebSocket/SSE requests cannot be replayed, use original records for analysis
3. **Modification Validation**: Validate request format validity after modifying parameters
4. **Batch Control**: Set reasonable concurrency and interval for batch replay
5. **Production Caution**: Be cautious when replaying in production, avoid affecting normal traffic
6. **Progress Monitoring**: Periodically query progress during batch replay
7. **Error Classification**: Focus on errorType to distinguish problem types

---

## Related Features

- [Request Tracing](request-tracing.md) - Request tracing data source
- [Filter Chain Analysis](filter-chain-analysis.md) - Filter execution analysis
- [AI Copilot](ai-copilot.md) - AI can call tools to analyze replay results