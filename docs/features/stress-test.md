# Stress Test Tool

> Stress test tool simulates concurrent load to measure Gateway performance.

---

## Overview

Execution and measurement rules:

- Up to three tasks are reserved per admin process, including tasks waiting to
  start. Transaction rollback and execution failure release the reservation.
- Stopping a task cancels its underlying HTTP requests. Final counts are saved
  only after request accounting finishes. Stop requests must reach the admin node
  that started the task; task ownership is currently local to that process.
- Duration bounds request dispatch. Already-dispatched requests are drained until
  their individual timeouts; stopping cancels them immediately.
- `targetQps` is an upper dispatch rate; zero or an omitted value is unlimited.
  Random body placeholders are resolved for every request. HEAD and OPTIONS retain
  their configured methods.
- Latency summaries and percentiles include successful and failed attempts, with
  millisecond resolution. The fixed histogram has a five-minute overflow bucket
  represented by the maximum observed latency. Failed/cancelled attempts are shown
  in the error distribution.
- Response bodies are discarded as they arrive and their bytes are counted.
  UTF-8 response size is measured in bytes rather than Java string characters.
  Statistics storage is bounded independently of the total request count.
- Live RPS uses a rolling window; report RPS uses total accounted attempts divided
  by elapsed execution time. Frontend polling does not overlap or reuse results
  after switching gateway instances.

Stress test features:

| Feature | Description |
|---------|-------------|
| **Custom Configuration** | Configure target URL, method, headers, body |
| **Concurrent Users** | Simulate multi-user concurrency |
| **Real-time Progress** | View test progress in real-time |
| **Detailed Statistics** | P50, P90, P95, P99 latency distribution |
| **AI Analysis** | AI analysis of test results |
| **Quick Test** | One-click quick test |
| **Export Reports** | Export the test report as Markdown (the only implemented format) |
| **Share Results** | Generate shareable links for test results |

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
| `durationSeconds` | Duration-based test; when > 0 it wins and `totalRequests` is left null | - |
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
| `CREATED` | Record persisted, execution not started yet (initial status) |
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
| `GET` | `/api/stress-test/{testId}/export` | Export report |
| `POST` | `/api/stress-test/{testId}/share` | Create share link |
| `GET` | `/api/stress-test/share/{shareId}` | Get shared test |

### Start Test

`instanceId` is a **query parameter** holding the instance's string ID (e.g. `gateway-1`),
not a body field; the JSON body is the configuration described above. `POST /api/**`
requires an authenticated user with the `ADMIN` authority, and starting a test also
requires the `STRESS_TEST` license feature.

```bash
curl -X POST "http://localhost:9090/api/stress-test/start?instanceId=gateway-1" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "testName": "Load Test",
    "concurrentUsers": 20,
    "totalRequests": 5000
  }'
```

Response:

```json
{
  "code": 200,
  "message": "Stress test started",
  "timestamp": 1735689600000,
  "data": {"testId": 1, "status": "CREATED"}
}
```

### Test Result Example

Test records are returned inside the same `ApiResponse` envelope; latency fields are
floating point (`Double`), not integers:

```json
{
  "code": 200,
  "message": "Success",
  "timestamp": 1735689600000,
  "data": {
    "id": 1,
    "instanceId": "gateway-1",
    "testName": "API Gateway Load Test",
    "status": "COMPLETED",
    "actualRequests": 10000,
    "successfulRequests": 9950,
    "failedRequests": 50,
    "minResponseTimeMs": 5.0,
    "maxResponseTimeMs": 2500.0,
    "avgResponseTimeMs": 45.2,
    "p50ResponseTimeMs": 35.0,
    "p90ResponseTimeMs": 80.0,
    "p95ResponseTimeMs": 120.0,
    "p99ResponseTimeMs": 500.0,
    "requestsPerSecond": 833.3,
    "errorRate": 0.5,
    "throughputKbps": 1250.0,
    "startTime": "2026-10-28T10:00:00",
    "endTime": "2026-10-28T10:00:12"
  }
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

## Architecture & Implementation Details

### Transaction-Async Execution Pattern (Bug Fix)

**Fixed: 2026-04-25**

**Problem: Stress test not executing, stuck in CREATED status**

Original code had a classic Spring transaction + async execution concurrency issue:

```java
// ❌ Original problematic code
@Transactional
public StressTest createAndStartTest(...) {
    test = stressTestRepository.save(test);  // Transaction NOT committed yet
    startTestAsync(test.getId());             // Async thread starts immediately
    return test;
}

private void startTestAsync(Long testId) {
    orchestrationExecutor.submit(() -> executeTest(testId));
}

private void executeTest(Long testId) {
    StressTest test = stressTestRepository.findById(testId).orElse(null);
    // ❌ Returns NULL because transaction not committed!
    if (test == null) return;  // Test stuck in CREATED status
}
```

**Root Cause:**
1. Main thread saves test but transaction not committed
2. Async thread starts and queries test immediately
3. Database transaction isolation: async thread can't see uncommitted data
4. Async thread finds null and exits
5. Test status remains CREATED forever

**Solution: Use TransactionSynchronization**

```java
// ✅ Fixed code
@Transactional
public StressTest createAndStartTest(...) {
    test = stressTestRepository.save(test);
    
    // Register callback to start async AFTER transaction commits
    final Long testId = test.getId();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("Transaction committed, starting async test: {}", testId);
                startTestAsync(testId, targetQps, timeout);
            }
        }
    );
    
    return test;
}

// Now async thread starts AFTER transaction commits
// Database can see committed test record
// Test executes successfully: CREATED → RUNNING → COMPLETED
```

**Benefits:**
- Guaranteed test execution after data is visible
- No race condition between transaction commit and async start
- Clean separation of transaction and async boundaries

---

## Best Practices

1. **Gradual Ramp-up**: Use rampUp to avoid sudden load spikes
2. **Monitor Concurrency**: Monitor Gateway status during testing
3. **Multiple Tests**: Compare results from multiple tests with different parameters
4. **AI Analysis**: Leverage AI to analyze test results
5. **Production Caution**: Be cautious when testing in production environments
6. **Export Reports**: Export results for documentation and sharing
7. **Share Collaboratively**: Use share links to collaborate with team members

---

## Database Schema

### stress_test Table

The table is created by JPA (`ddl-auto=update`) from the `StressTest` entity — there are
no Flyway/Liquibase migrations. Column defaults such as `GET`, `10`, `0`, `30` and
`CREATED` are applied in Java (entity field initializers / `createAndStartTest`), not by
the database:

```sql
CREATE TABLE stress_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id VARCHAR(100) NOT NULL,        -- gateway instance ID (string), no foreign key
    test_name VARCHAR(100),
    target_url VARCHAR(500) NOT NULL,
    method VARCHAR(10),                       -- 'GET' when not specified
    headers TEXT,                             -- JSON
    body TEXT,
    concurrent_users INT NOT NULL,            -- 10 when not specified
    total_requests INT,                       -- NULL for duration-based tests
    duration_seconds INT,                     -- alternative to total_requests
    ramp_up_seconds INT,                      -- 0 when not specified
    request_timeout_seconds INT,              -- 30 when not specified
    target_qps INT,                           -- NULL = unlimited

    -- Results
    status VARCHAR(20),                       -- CREATED | RUNNING | COMPLETED | STOPPED | FAILED
    start_time DATETIME,
    end_time DATETIME,
    actual_requests INT,
    successful_requests INT,
    failed_requests INT,
    error_rate DOUBLE,
    min_response_time_ms DOUBLE,
    max_response_time_ms DOUBLE,
    avg_response_time_ms DOUBLE,
    p50_response_time_ms DOUBLE,
    p90_response_time_ms DOUBLE,
    p95_response_time_ms DOUBLE,
    p99_response_time_ms DOUBLE,
    requests_per_second DOUBLE,
    throughput_kbps DOUBLE,
    response_time_distribution TEXT,          -- JSON histogram
    error_distribution TEXT,                  -- JSON error breakdown

    -- AI Analysis
    ai_analysis_result TEXT,

    -- Metadata
    created_by VARCHAR(100),
    created_at DATETIME,
    updated_at DATETIME
);
```

Two differences worth noting versus earlier revisions of this document: `instance_id` is
a `VARCHAR(100)` string with **no** `gateway_instance` foreign key, and there is **no
`path` column** — the configured `path` is folded into `target_url` when the test starts
and used while dispatching requests. Latency columns are `DOUBLE`, and the timestamps are
`start_time` / `end_time`.

### stress_test_share Table

```sql
CREATE TABLE stress_test_share (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    share_id VARCHAR(36) NOT NULL UNIQUE,
    test_id BIGINT NOT NULL,
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    view_count INT DEFAULT 0,
    created_by VARCHAR(100),
    
    CONSTRAINT fk_stress_test_share_test FOREIGN KEY (test_id) 
        REFERENCES stress_test(id) ON DELETE CASCADE
);

-- Indexes for efficient queries
CREATE INDEX idx_stress_test_share_id ON stress_test_share(share_id);
CREATE INDEX idx_stress_test_share_expires ON stress_test_share(expires_at);
CREATE INDEX idx_stress_test_share_test ON stress_test_share(test_id);
```

### Key Fields

| Field | Description |
|-------|-------------|
| `share_id` | Unique identifier for shareable URL |
| `expires_at` | Expiration timestamp (NULL = never expires) |
| `view_count` | Number of times shared report was viewed |
| `created_by` | User who created the share link |

---

## Export Reports

Export a stress test report for documentation and analysis.

### Supported Formats

**Markdown is the only implemented export format.** `GET /api/stress-test/{testId}/export`
declares a `format` query parameter (default `markdown`) and logs it, but the value is
never used: the handler always calls `exportAsMarkdown(testId)` and returns
`MediaType.TEXT_MARKDOWN` with a `stress-test-report-{testId}.md` attachment
(`StressTestController.exportReport`). PDF, Excel and JSON exports do **not** exist — no
PDF/spreadsheet library (iText, PDFBox, OpenPDF, POI, EasyExcel) is declared in any module
`pom.xml`.

| Format | Status |
|--------|--------|
| **Markdown** | Implemented — always returned, regardless of `format` |
| **PDF** | Not implemented (`format=pdf` still returns Markdown) |
| **Excel** | Not implemented |
| **JSON** | Not implemented as an export; use `GET /api/stress-test/{testId}` or `/{testId}/metrics` for structured data |

### Export API

```bash
curl -X GET "http://localhost:9090/api/stress-test/{testId}/export" \
  -H "Authorization: Bearer <token>" \
  --output stress-test-report.md
```

### Export Parameters

| Parameter | Description | Values |
|-----------|-------------|--------|
| `format` | Accepted but **ignored**; the response is always Markdown | default `markdown` |

### Export Content

The generated Markdown report contains:

- **Header**: test name, target URL, method, status, start/end time
- **Test Configuration**: concurrent users, total requests or duration, ramp-up, target QPS, request timeout
- **Test Results**: total/successful/failed requests, error rate, requests per second, throughput
- **Response Time Distribution**: min, max, avg, P50, P90, P95, P99 (ms)
- **AI Analysis**: included only when an analysis result is stored for the test

Reports are plain text — there are no embedded charts or images.

---

## Share Results

Generate shareable links to collaborate with team members.

### Share Feature

| Feature | Description |
|---------|-------------|
| **Unique URL** | Random UUID share ID, unique per link |
| **Expiration** | Optional, in **hours**; omitted (or `<= 0`) means the link never expires |
| **Access Control** | `GET /api/stress-test/share/{shareId}` is permit-all (no token); creating a link requires `ADMIN` |
| **Full Report** | Returns the test record plus the generated Markdown report |
| **Completed Only** | Sharing is rejected unless the test status is `COMPLETED` |

### Share API

`expiresIn` is a **query parameter** (hours), not a JSON body field:

```bash
# Create a share link that expires in 24 hours
curl -X POST "http://localhost:9090/api/stress-test/{testId}/share?expiresIn=24" \
  -H "Authorization: Bearer <token>"

# Permanent link (no expiresIn)
curl -X POST "http://localhost:9090/api/stress-test/{testId}/share" \
  -H "Authorization: Bearer <token>"
```

**Response:**

```json
{
  "code": 200,
  "message": "Success",
  "timestamp": 1735689600000,
  "data": {
    "shareId": "3f2b8c1e-6d4a-4c9e-9b7a-1e5d0a2c7b88",
    "shareUrl": "/api/stress-test/share/3f2b8c1e-6d4a-4c9e-9b7a-1e5d0a2c7b88",
    "expiresIn": "24 hours"
  }
}
```

`shareUrl` is a relative API path (resolve it against the admin base URL); the response
carries no `expiresAt` field. `expiresIn` is the string `"permanent"` when no expiry was
requested. The computed expiry timestamp is stored in `stress_test_share.expires_at`.

### Access Shared Results

```bash
curl http://localhost:9090/api/stress-test/share/{shareId}
```

Returns `{"test": {...}, "markdown": "# Stress Test Report..."}` and increments
`view_count`. Expired or unknown share IDs return an error payload. A link can be revoked
with `DELETE /api/stress-test/share/{shareId}`.

### Share Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `expiresIn` | Query parameter, link lifetime in **hours**; `null` or `<= 0` = never expires | permanent |

---

## AI-Powered Analysis

The stress test tool includes AI-powered analysis capabilities for intelligent insights.

### Analysis Features

- **Performance Bottleneck Detection**: Identify performance issues
- **Resource Utilization**: Analyze resource usage patterns
- **Error Pattern Recognition**: Detect common error patterns
- **Optimization Recommendations**: Get actionable recommendations
- **Comparison Analysis**: Compare with previous tests

### Analysis API

```bash
curl -X GET "http://localhost:9090/api/stress-test/{testId}/analyze?provider=BAILIAN&language=zh" \
  -H "Authorization: Bearer <token>"
```

`provider` (default `BAILIAN`) and `language` (default `zh`) are optional query
parameters. The test must be `COMPLETED`, and the call requires the `AI_COPilot` license
feature. The result is cached in `ai_analysis_result`: once stored, later calls return
the cached text instead of invoking the model again.

**Response:**

```json
{
  "code": 200,
  "message": "Success",
  "timestamp": 1735689600000,
  "data": {
    "analysis": "## Performance Analysis\n\n### Summary\nThe test shows good overall performance..."
  }
}
```

`data.analysis` is the only field returned — the analysis text (Markdown, produced from the
test statistics plus Prometheus metrics for the test window) is a single string; there is
no separate `recommendations` array or numeric `score` field.

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - Monitoring during tests
- [AI-Powered Analysis](ai-analysis.md) - Result analysis
- [AI Copilot](ai-copilot.md) - AI assistant for configuration
