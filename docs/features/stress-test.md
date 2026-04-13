# Stress Test Tool

> Stress test tool simulates concurrent load to measure Gateway performance.

---

## Overview

Stress test features:

| Feature | Description |
|---------|-------------|
| **Custom Configuration** | Configure target URL, method, headers, body |
| **Concurrent Users** | Simulate multi-user concurrency |
| **Real-time Progress** | View test progress in real-time |
| **Detailed Statistics** | P50, P90, P95, P99 latency distribution |
| **AI Analysis** | AI analysis of test results |
| **Quick Test** | One-click quick test |

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
| `testName` | Test name | - |
| `targetUrl` | Target URL (optional, uses instance URL) | - |
| `path` | Path (appended to instance URL) | - |
| `method` | HTTP method | `GET` |
| `headers` | Request headers | - |
| `body` | Request body (POST/PUT) | - |
| `concurrentUsers` | Number of concurrent users | `10` |
| `totalRequests` | Total number of requests | `1000` |
| `targetQps` | Target QPS limit | - |
| `rampUpSeconds` | Ramp-up duration | `0` |
| `requestTimeoutSeconds` | Request timeout | `30` |

---

## Metrics Collected

| Metric | Description |
|--------|-------------|
| `actualRequests` | Actual requests sent |
| `successfulRequests` | Successful requests (2xx) |
| `failedRequests` | Failed requests (4xx/5xx) |
| `minResponseTimeMs` | Minimum response time |
| `maxResponseTimeMs` | Maximum response time |
| `avgResponseTimeMs` | Average response time |
| `p50ResponseTimeMs` | 50th percentile latency |
| `p90ResponseTimeMs` | 90th percentile latency |
| `p95ResponseTimeMs` | 95th percentile latency |
| `p99ResponseTimeMs` | 99th percentile latency |
| `requestsPerSecond` | Actual QPS |
| `errorRate` | Error rate percentage |
| `throughputKbps` | Throughput KB/s |

---

## Test Status

| Status | Description |
|--------|-------------|
| `RUNNING` | Currently executing |
| `COMPLETED` | Successfully completed |
| `STOPPED` | Stopped by user |
| `FAILED` | Execution failed |

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

1. **Gradual Ramp-up**: Use rampUp to avoid sudden load spikes
2. **Monitor Concurrency**: Monitor Gateway status during testing
3. **Multiple Tests**: Compare results from multiple tests with different parameters
4. **AI Analysis**: Leverage AI to analyze test results
5. **Production Caution**: Be cautious when testing in production environments

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - Monitoring during tests
- [AI-Powered Analysis](ai-analysis.md) - Result analysis