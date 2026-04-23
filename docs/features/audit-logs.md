# Audit Logs

> Audit logs record all configuration changes, supporting diff comparison and configuration rollback.

---

## Overview

Audit log features:
- Record all CREATE/UPDATE/DELETE operations
- Display before and after change differences
- Support rollback to historical versions

---

## Tracked Operations

| Operation | Description |
|-----------|-------------|
| `CREATE` | Create configuration |
| `UPDATE` | Update configuration |
| `DELETE` | Delete configuration |
| `ENABLE` | Enable configuration |
| `DISABLE` | Disable configuration |
| `ROLLBACK` | Rollback configuration |

---

## Target Types

| Target Type | Description |
|-------------|-------------|
| `ROUTE` | Route configuration |
| `SERVICE` | Service configuration |
| `STRATEGY` | Strategy configuration |
| `AUTH_POLICY` | Authentication policy |

---

## Data Model

```java
public class AuditLogEntity {
    private Long id;
    private String instanceId;       // Instance isolation
    private String operator;         // Operator
    private String operationType;    // CREATE/UPDATE/DELETE
    private String targetType;       // ROUTE/SERVICE/STRATEGY
    private String targetId;         // Target ID
    private String targetName;       // Target name
    private String oldValue;         // JSON before change
    private String newValue;         // JSON after change
    private String ipAddress;        // Client IP
    private Date createdAt;          // Timestamp
}
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/audit-logs` | List audit logs with filtering |
| `GET` | `/api/audit-logs/{id}` | Get single audit log detail |
| `GET` | `/api/audit-logs/{id}/diff` | Get change diff |
| `POST` | `/api/audit-logs/{id}/rollback` | Rollback config |
| `GET` | `/api/audit-logs/timeline/{instanceId}` | Get timeline |
| `GET` | `/api/audit-logs/stats` | Get audit statistics |
| `GET` | `/api/audit-logs/export` | Export audit logs (CSV/JSON) |

### List Audit Logs (Enhanced)

```bash
curl "http://localhost:9090/api/audit-logs?targetType=ROUTE&operationType=UPDATE&startTime=2024-01-01&endTime=2024-01-15&operator=admin&limit=100"
```

### Query Parameters

| Parameter | Description | Example |
|-----------|-------------|---------|
| `targetType` | Filter by target type | `ROUTE`, `STRATEGY` |
| `operationType` | Filter by operation | `CREATE`, `UPDATE`, `DELETE` |
| `operator` | Filter by operator | `admin` |
| `startTime` | Start time range | `2024-01-01T00:00:00Z` |
| `endTime` | End time range | `2024-01-15T23:59:59Z` |
| `targetId` | Filter by target ID | `route-123` |
| `targetName` | Filter by target name (partial) | `user-service` |
| `limit` | Max results | `100` |
| `offset` | Pagination offset | `0` |

Response:
```json
{
  "logs": [
    {
      "id": 123,
      "operationType": "UPDATE",
      "targetType": "ROUTE",
      "targetId": "user-route",
      "targetName": "User Service Route",
      "operator": "admin",
      "createdAt": "2024-01-15T10:30:00Z",
      "ipAddress": "192.168.1.100",
      "summary": "Changed predicates: Path=/api/** to /api/v2/**"
    }
  ],
  "total": 150,
  "hasMore": true
}
```

---

## Diff Comparison

```bash
GET /api/audit-logs/{id}/diff
```

Response:
```json
{
  "changes": [
    {
      "type": "modified",
      "field": "qps",
      "oldValue": "100",
      "newValue": "200"
    },
    {
      "type": "added",
      "field": "burstCapacity",
      "oldValue": null,
      "newValue": "400"
    }
  ]
}
```

---

## Rollback

```bash
POST /api/audit-logs/{id}/rollback
```

Process:
1. Load historical version configuration
2. Validate configuration validity
3. Apply configuration to current
4. Publish to Nacos
5. Record ROLLBACK operation

---

## Configuration

```yaml
audit:
  retention-days: 30
  cleanup-schedule: "0 0 2 * * ?"
```

---

## Best Practices

1. **Regular Cleanup**: Set reasonable retention days
2. **Rollback Validation**: Validate configuration validity before rollback
3. **Operation Audit**: Record operators for traceability
4. **Critical Changes**: Separately record important changes
5. **Regular Backup**: Export audit log backups

---

## Related Features

- [Route Management](route-management.md) - Route audit
- [Service Discovery](service-discovery.md) - Service audit
- [Authentication](authentication.md) - Authentication policy audit

---

## Enhanced Features (New)

### Audit Statistics

```bash
GET /api/audit-logs/stats?period=7d
```

Response:
```json
{
  "period": "7d",
  "totalOperations": 150,
  "operationsByType": {
    "CREATE": 20,
    "UPDATE": 100,
    "DELETE": 15,
    "ROLLBACK": 15
  },
  "operationsByTarget": {
    "ROUTE": 80,
    "STRATEGY": 40,
    "AUTH_POLICY": 30
  },
  "topOperators": [
    { "operator": "admin", "count": 120 },
    { "operator": "system", "count": 30 }
  ],
  "rollbackRate": "10%",
  "errorRate": "2%"
}
```

### Timeline View

Visual timeline of configuration changes:

```
┌────────────────────────────────────────────────────────────────────┐
│ CONFIGURATION TIMELINE                                              │
│                                                                     │
│ Jan 10 │ Jan 11 │ Jan 12 │ Jan 13 │ Jan 14 │ Jan 15 │ Jan 16       │
│        │        │        │        │        │        │               │
│   ●────│────────│────●───│────────│────●───│────────│──●            │
│   CREATE│       │  UPDATE│       │  UPDATE│       │ DELETE         │
│  route-A│       │  route-A│      │  route-B│      │ route-C        │
│                                                                     │
│ Legend: ● CREATE/UPDATE, ○ DELETE, ◐ ROLLBACK                      │
└────────────────────────────────────────────────────────────────────┘
```

### Export Functionality

Export audit logs for external analysis:

```bash
# Export as JSON
curl "http://localhost:9090/api/audit-logs/export?format=json&startTime=2024-01-01&endTime=2024-01-15" -o audit-logs.json

# Export as CSV
curl "http://localhost:9090/api/audit-logs/export?format=csv&startTime=2024-01-01&endTime=2024-01-15" -o audit-logs.csv
```

### Change Summary

Each audit log now includes an AI-generated summary:

```json
{
  "id": 123,
  "summary": "Changed predicates: Path=/api/** to /api/v2/**, added Method=POST filter",
  "riskLevel": "LOW",
  "affectedRoutes": ["user-service", "order-service"],
  "recommendation": "Verify that all clients support /api/v2/** path"
}
```

### Rollback Safety Check

Before rollback, system performs safety validation:

| Check | Description |
|-------|-------------|
| **Version Conflict** | Check if config has been modified since audit log |
| **Dependency Check** | Verify dependent configs still exist |
| **Validation** | Validate config schema and constraints |
| **Impact Analysis** | Estimate impact of rollback |

```json
{
  "rollbackCheck": {
    "canRollback": true,
    "warnings": [
      "Config has been modified 2 times since this version"
    ],
    "recommendations": [
      "Review current config before rollback"
    ]
  }
}
```

---

## AI Copilot Integration

AI Copilot can query and analyze audit logs:

| Tool | Capability |
|------|------------|
| `audit_query` | Query audit logs with filters |
| `audit_diff` | Get detailed change comparison |
| `audit_stats` | Get audit statistics |
| `suggest_rollback` | AI suggests which version to rollback |

### Example: AI Analysis

```
User: "What changes were made to user-service route last week?"

AI Response:
┌─────────────────────────────────────────────────────────────┐
│ AUDIT ANALYSIS: user-service route                          │
│                                                             │
│ Period: Jan 8 - Jan 15, 2024                                │
│ Total changes: 3                                            │
│                                                             │
│ Change 1 (Jan 10):                                          │
│ - Added Method predicate: GET, POST                         │
│ - Operator: admin                                           │
│                                                             │
│ Change 2 (Jan 12):                                          │
│ - Modified StripPrefix parts: 1 → 2                         │
│ - Operator: admin                                           │
│                                                             │
│ Change 3 (Jan 14):                                          │
│ - Added rate limiting: qps=100                              │
│ - Operator: system                                          │
│                                                             │
│ Recommendation: Current config looks stable.                │
│ No rollback needed.                                         │
└─────────────────────────────────────────────────────────────┘
```