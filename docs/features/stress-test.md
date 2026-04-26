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
| **Export Reports** | Export test results to PDF, Excel, JSON, Markdown |
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
| `GET` | `/api/stress-test/{testId}/export` | Export report |
| `POST` | `/api/stress-test/{testId}/share` | Create share link |
| `GET` | `/api/stress-test/share/{shareId}` | Get shared test |

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

```sql
CREATE TABLE stress_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id BIGINT NOT NULL,
    test_name VARCHAR(255),
    target_url VARCHAR(500),
    path VARCHAR(500),
    method VARCHAR(10) DEFAULT 'GET',
    headers TEXT,
    body TEXT,
    concurrent_users INT DEFAULT 10,
    total_requests INT DEFAULT 1000,
    target_qps INT,
    ramp_up_seconds INT DEFAULT 0,
    request_timeout_seconds INT DEFAULT 30,
    
    -- Results
    status VARCHAR(20) DEFAULT 'PENDING',
    actual_requests INT DEFAULT 0,
    successful_requests INT DEFAULT 0,
    failed_requests INT DEFAULT 0,
    min_response_time_ms BIGINT,
    max_response_time_ms BIGINT,
    avg_response_time_ms DOUBLE,
    p50_response_time_ms BIGINT,
    p90_response_time_ms BIGINT,
    p95_response_time_ms BIGINT,
    p99_response_time_ms BIGINT,
    requests_per_second DOUBLE,
    error_rate DOUBLE,
    throughput_kbps DOUBLE,
    
    -- AI Analysis
    ai_analysis_result TEXT,
    
    -- Metadata
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME,
    completed_at DATETIME,
    
    CONSTRAINT fk_stress_test_instance FOREIGN KEY (instance_id) 
        REFERENCES gateway_instance(id) ON DELETE CASCADE
);
```

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

Export stress test results in multiple formats for reporting and analysis.

### Supported Formats

| Format | Description | Use Case |
|--------|-------------|----------|
| **PDF** | Professional formatted report | Presentations, documentation |
| **Excel** | Spreadsheet with charts | Data analysis, custom reports |
| **JSON** | Raw data export | Integration, automation |
| **Markdown** | Text-based report | Documentation, README files |

### Export API

```bash
curl -X GET "http://localhost:9090/api/stress-test/{testId}/export?format=pdf" \
  -H "Accept: application/pdf" \
  --output stress-test-report.pdf
```

### Export Parameters

| Parameter | Description | Values |
|-----------|-------------|--------|
| `format` | Export format | `pdf`, `excel`, `json`, `markdown` |

### Export Content

Exported reports include:

- **Test Configuration**: Target URL, method, concurrency settings
- **Summary Statistics**: Total requests, success rate, error rate
- **Latency Distribution**: Min, max, avg, P50, P90, P95, P99
- **Performance Metrics**: QPS, throughput
- **AI Analysis**: Intelligent analysis and recommendations (if available)
- **Charts**: Visual representation of results

---

## Share Results

Generate shareable links to collaborate with team members.

### Share Feature

| Feature | Description |
|---------|-------------|
| **Unique URL** | Generate unique shareable link |
| **Expiration** | Links expire after configurable period |
| **Access Control** | View-only access to shared results |
| **Full Report** | Includes all test details and AI analysis |

### Share API

```bash
# Create share link
curl -X POST "http://localhost:9090/api/stress-test/{testId}/share" \
  -H "Content-Type: application/json" \
  -d '{
    "expiresInDays": 7
  }'
```

**Response:**

```json
{
  "shareId": "abc123def456",
  "shareUrl": "http://localhost:9090/share/abc123def456",
  "expiresAt": "2024-01-15T00:00:00Z"
}
```

### Access Shared Results

Navigate to the share URL to view the test results:

```
http://localhost:9090/share/{shareId}
```

### Share Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `expiresInDays` | Number of days until link expires | `7` |

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
curl -X GET "http://localhost:9090/api/stress-test/{testId}/analyze"
```

**Response:**

```json
{
  "analysis": "## Performance Analysis\n\n### Summary\nThe test shows good overall performance...",
  "recommendations": [
    "Consider increasing connection pool size",
    "Enable response caching for frequently accessed endpoints"
  ],
  "score": 85
}
```

---

## Related Features

- [Monitoring & Alerts](monitoring-alerts.md) - Monitoring during tests
- [AI-Powered Analysis](ai-analysis.md) - Result analysis
- [AI Copilot](ai-copilot.md) - AI assistant for configuration