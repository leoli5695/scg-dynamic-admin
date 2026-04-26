# Access Log Configuration

## Overview

The gateway provides comprehensive access logging with JSON format output, supporting multiple deployment modes and real-time log viewing.

## Features

- **JSON Format Logging**: Structured logs for easy aggregation and analysis
- **Multiple Deployment Modes**: LOCAL, DOCKER, K8S, CUSTOM
- **Real-time Viewing**: Direct Pod stdout reading for Kubernetes environments
- **Configurable Content**: Headers, body, auth info sampling rate
- **Sensitive Data Masking**: Automatic masking of passwords, tokens, secrets
- **Binary Content Detection**: Skip logging for file uploads/downloads

## Deployment Modes

| Mode | Log Output | Recommended For |
|------|------------|-----------------|
| **K8S** | stdout | Kubernetes deployments (cloud-native best practice) |
| **DOCKER** | stdout + file | Docker Compose with volume mount |
| **LOCAL** | file | Local development/testing |
| **CUSTOM** | file (custom path) | Custom storage requirements |

### Kubernetes Mode (Recommended)

When deployed in Kubernetes, the gateway outputs logs to stdout in JSON format. Kubernetes automatically redirects container logs to `/var/log/containers/*.log`.

**Benefits:**
- No file I/O overhead
- Automatic log collection by Fluent Bit DaemonSet
- Easy cross-node aggregation

**Configuration:**
```json
{
  "enabled": true,
  "deployMode": "K8S",
  "logToConsole": true,
  "logDirectory": ""
}
```

## Real-time Log Viewing (K8S)

For Kubernetes deployments, you can view logs directly from Pod stdout without deploying Fluent Bit:

**Usage:**
1. Navigate to **Access Log** page
2. Select **K8S** deployment mode
3. Switch to **View Logs** tab
4. Select Namespace and Pod
5. View real-time logs (last 500 lines)

**Note:** Logs are collected in real-time, not stored in database. For persistent storage and historical queries, integrate with Elasticsearch.

## API Endpoints

### Kubernetes Pod Logs

```
GET /api/access-log/k8s/pods?clusterId=1&namespace=gateway
```

Returns list of gateway pods for selection.

```
GET /api/access-log/k8s/entries?clusterId=1&namespace=gateway&podName=my-gateway-xxx&tailLines=500
```

Returns real-time log entries from Pod stdout.

### Local File Logs

```
GET /api/access-log/entries?page=0&size=50
```

Returns log entries from local file.

## Log Entry Structure

Each log entry is a JSON object:

```json
{
  "@timestamp": "2024-04-25T10:30:00.000Z",
  "traceId": "abc123",
  "requestId": "def456",
  "routeId": "route-api",
  "serviceId": "backend-service",
  "method": "GET",
  "path": "/api/users",
  "query": "id=1",
  "clientIp": "192.168.1.100",
  "statusCode": 200,
  "durationMs": 45,
  "authType": "JWT",
  "authPolicy": "jwt-policy",
  "authUser": "user@example.com",
  "userAgent": "Mozilla/5.0"
}
```

## Advanced Configuration (Optional)

For persistent storage with Elasticsearch, you can deploy Fluent Bit to collect and forward logs:

### Kubernetes DaemonSet

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: fluent-bit-config
  namespace: gateway
data:
  fluent-bit.conf: |
    [SERVICE]
        Flush         5
        Log_Level     info

    [INPUT]
        Name              tail
        Path              /var/log/containers/*gateway*.log
        Parser            json
        Tag               gateway.access

    [OUTPUT]
        Name              elasticsearch
        Match             gateway.*
        Host              elasticsearch
        Port              9200
        Index             gateway-access
```

## Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `enabled` | Enable/disable access logging | `false` |
| `deployMode` | Deployment mode | `LOCAL` |
| `logToConsole` | Output to stdout | `true` (K8S) |
| `logDirectory` | File log directory | `./logs/access` |
| `logFormat` | JSON or TEXT | `JSON` |
| `logLevel` | MINIMAL, NORMAL, VERBOSE | `NORMAL` |
| `logRequestHeaders` | Include request headers | `true` |
| `logResponseHeaders` | Include response headers | `true` |
| `logRequestBody` | Include request body | `false` |
| `logResponseBody` | Include response body | `false` |
| `maxBodyLength` | Max body characters to log | `2048` |
| `samplingRate` | Sampling rate (0-100%) | `100` |
| `sensitiveFields` | Fields to mask | `password, token, secret...` |

## Sensitive Data Masking

The gateway automatically masks sensitive fields in request/response bodies:

- `password`, `token`, `authorization`
- `apiKey`, `api_key`, `accessKey`
- `clientSecret`, `client_secret`
- `cookie`

Example masked output:
```json
{
  "requestBody": "{\"password\":\"***MASKED***\",\"username\":\"admin\"}"
}
```

## Binary Content Handling

The gateway detects and skips logging for:
- File uploads (multipart/form-data)
- File downloads (application/octet-stream, images, etc.)
- WebSocket/SSE connections

These are logged with placeholders:
```json
{
  "requestBody": "[BINARY_FILE_UPLOAD]",
  "responseBody": "[BINARY_FILE_DOWNLOAD]"
}
```

## Related

- [Monitoring & Alerts](./monitoring-alerts.md)
- [Request Tracing](./request-tracing.md)
- [Kubernetes Integration](./kubernetes-integration.md)