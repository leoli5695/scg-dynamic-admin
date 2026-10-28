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

> Note: `ENABLE` / `DISABLE` are **written** by the code (e.g. `RouteController.java:221,255`,
> `StrategyController.java:205,241`, `TokenQuotaService.java:207`) but are **not** returned by
> `GET /api/audit-logs/operation-types` (`AuditLogController.java:175-184` only lists
> CREATE / UPDATE / DELETE / ROLLBACK). So they appear in data but not in the UI dropdown.

---

## Target Types

| Target Type | Description |
|-------------|-------------|
| `ROUTE` | Route configuration |
| `SERVICE` | Service configuration |
| `STRATEGY` | Strategy configuration |
| `AUTH_POLICY` | Authentication policy |

> Note: the four values above are exactly what `GET /api/audit-logs/target-types` returns
> (`AuditLogController.java:158-167`). The code additionally writes other target types that the
> dropdown does not expose, e.g. `KUBERNETES_CLUSTER` (`KubernetesController.java:83,137,163`),
> `KUBERNETES_DEPLOYMENT`, `TOKEN_QUOTA` (`TokenQuotaService.java:207`) and `PROVIDER_CREDENTIAL`.

---

## Data Model

```java
public class AuditLogEntity {
    private Long id;
    private String instanceId;       // Instance isolation
    private String operator;         // Operator
    private String operatorType;     // MANUAL (user) or AI_COPILOT (AI assistant)
    private String operationType;    // CREATE/UPDATE/DELETE/...
    private String targetType;       // ROUTE/SERVICE/STRATEGY/...
    private String targetId;         // Target ID
    private String targetName;       // Target name
    private String oldValue;         // JSON before change (masked on serialization)
    private String newValue;         // JSON after change (masked on serialization)
    private String ipAddress;        // Client IP
    private LocalDateTime createdAt; // Timestamp (@CreationTimestamp)
}
```

Source: `gateway-admin/src/main/java/com/leoli/gateway/admin/model/AuditLogEntity.java:45-94`.
These are the **only** persisted fields — there is no `summary`, `riskLevel`,
`affectedRoutes` or `recommendation` column.

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
| `GET` | `/api/audit-logs/export/csv` | Export audit logs as CSV |
| `GET` | `/api/audit-logs/export/json` | Export audit logs as JSON |
| `GET` | `/api/audit-logs/target-types` | Target type options for the UI dropdown |
| `GET` | `/api/audit-logs/operation-types` | Operation type options for the UI dropdown |
| `GET` | `/api/audit-logs/cleanup/stats` | Preview how many rows a cleanup would delete |
| `POST` | `/api/audit-logs/cleanup` | Delete logs older than N days |
| `POST` | `/api/audit-logs/clear-all` | Clear all audit logs |
| `DELETE` | `/api/audit-logs/{id}` | Delete one audit log |

> There is **no** `GET /api/audit-logs/export` with a `format` query parameter — the format is part
> of the path (`AuditLogController.java:336` `/export/csv`, `:407` `/export/json`).
>
> **Auth:** `/api/audit-logs/export/**` requires the `ADMIN` authority
> (`SecurityConfig.java:113`, a P0 secret-exfiltration fix, because exported snapshots embed whole
> objects such as `AuthConfig.secretKey`). All other `/api/**` requests require at least a valid JWT
> (`SecurityConfig.java:127 anyRequest().authenticated()`), and every POST/PUT/PATCH/DELETE under
> `/api/**` requires `ADMIN`.

### List Audit Logs (Enhanced)

```bash
curl "http://localhost:9090/api/audit-logs?targetType=ROUTE&operationType=UPDATE&startTime=2024-01-01T00:00:00&endTime=2024-01-15T23:59:59&page=0&size=100" \
  -H "Authorization: Bearer <JWT>"
```

### Query Parameters

Actual signature: `AuditLogController.java:53-64`.

| Parameter | Description | Example |
|-----------|-------------|---------|
| `instanceId` | Filter by gateway instance | `tj9j963zq2x1` |
| `targetType` | Filter by target type | `ROUTE`, `STRATEGY` |
| `targetId` | Filter by target ID | `route-123` |
| `operationType` | Filter by operation | `CREATE`, `UPDATE`, `DELETE` |
| `startTime` | Start of range (ISO date-time) | `2024-01-01T00:00:00` |
| `endTime` | End of range (ISO date-time) | `2024-01-15T23:59:59` |
| `page` | Zero-based page index (default `0`) | `0` |
| `size` | Page size (default `20`) | `100` |

> `operator`, `targetName`, `limit` and `offset` are **not** supported query parameters — passing
> them is silently ignored.

Response (all admin endpoints wrap payloads in `ApiResponse` = `{code, message, data, traceId, timestamp}`):
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "logs": [
      {
        "id": 123,
        "instanceId": "tj9j963zq2x1",
        "operationType": "UPDATE",
        "targetType": "ROUTE",
        "targetId": "user-route",
        "targetName": "User Service Route",
        "operator": "admin",
        "operatorType": "MANUAL",
        "ipAddress": "192.168.1.100",
        "createdAt": "2024-01-15T10:30:00"
      }
    ],
    "total": 150,
    "page": 0,
    "size": 100,
    "totalPages": 2
  }
}
```

> There is no `hasMore` field and no `summary` field — pagination is expressed via
> `page` / `size` / `totalPages` (`AuditLogController.java:72-78`).

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

Implementation (`AuditLogController.java:129-152` + `AuditLogService.rollback`):

- The request body is an **optional** `Map<String,String>`; it is not used to identify the operator.
- `operator` is taken from the **authenticated principal** (`AuditLogController.java:137`, a P1
  anti-impersonation fix), so a caller cannot attribute a rollback to someone else.
- Rolling back a `CREATE` entry means deleting the created object; entries with an empty
  `oldValue` and a non-`CREATE` operation are rejected with `"No previous version to rollback to"`.
- Response is `ApiResponse<Map>`; on success `data` is
  `{success: true, message: "Rollback successful", targetType, targetId, rolledBackFromLogId}`,
  on failure `data` is `{success: false, message: ...}` returned with HTTP 400 semantics
  (`ApiResponse.badRequest`).
- Requires `ADMIN` (POST under `/api/**`).

Process:
1. Load historical version configuration
2. Validate configuration validity
3. Apply configuration to current
4. Publish to Nacos
5. Record ROLLBACK operation

---

## Configuration

Only the retention window is configurable; the cleanup schedule is hardcoded.

```properties
# optional, default 30 — read by both AuditLogController.java:46 and AuditLogCleanupScheduler.java:26
audit.log.retention-days=30
```

- The property key is **`audit.log.retention-days`** (not `audit.retention-days`).
- The cleanup job is **not** configurable: `AuditLogCleanupScheduler.java:33` hardcodes
  `@Scheduled(cron = "0 0 2 * * ?")` (daily at 02:00). There is no `audit.cleanup-schedule` key.
- Neither `gateway-admin/src/main/resources/application.yml` nor `application-prod.yml` contains an
  `audit:` section, so the default (30 days) applies unless the property is supplied externally
  (env var `AUDIT_LOG_RETENTION_DAYS`, JVM `-D`, …).
- On-demand cleanup is available via `POST /api/audit-logs/cleanup?days=N`
  (`AuditLogController.java:244-246`).

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
GET /api/audit-logs/stats?instanceId=tj9j963zq2x1
```

The only supported query parameter is the optional `instanceId`
(`AuditLogController.java:193-196`). There is **no `period` parameter** — the statistics are
computed by database aggregation over the whole retained log (cached 30s), not over a time window.

Response `data` mirrors the `AuditLogStats` record (`model/AuditLogStats.java`) — exactly these
seven keys, nothing else:

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "total": 150,
    "today": 12,
    "creates": 20,
    "updates": 100,
    "deletes": 15,
    "rollbacks": 15,
    "errorRate": 10.0
  }
}
```

> Not implemented: `operationsByType`, `operationsByTarget`, `topOperators` and `rollbackRate` do
> not exist in the response. `errorRate` is a percentage computed as `deletes / total * 100`
> (`AuditLogStats.of`), not a failure ratio.

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

Export audit logs for external analysis. The format is selected **by the path**, not by a
`format` query parameter, and both endpoints require an `ADMIN` JWT
(`SecurityConfig.java:113`) — an unauthenticated or non-admin call gets 401/403.

```bash
# Export as JSON
curl "http://localhost:9090/api/audit-logs/export/json?startTime=2024-01-01T00:00:00&endTime=2024-01-15T23:59:59" \
  -H "Authorization: Bearer <ADMIN_JWT>" -o audit-logs.json

# Export as CSV
curl "http://localhost:9090/api/audit-logs/export/csv?startTime=2024-01-01T00:00:00&endTime=2024-01-15T23:59:59" \
  -H "Authorization: Bearer <ADMIN_JWT>" -o audit-logs.csv
```

Supported filters (`AuditLogController.java:338-346`, `:409-417`): `instanceId`, `targetType`,
`targetId`, `operationType`, `startTime`, `endTime`, `limit` (default `10000`).
The body is a raw byte stream (`ResponseEntity<byte[]>`), not an `ApiResponse` wrapper.

### Change Summary

**Not implemented.** Audit records carry no AI-generated summary: `AuditLogEntity`
(`model/AuditLogEntity.java:45-94`) has only `id, instanceId, operator, operatorType,
operationType, targetType, targetId, targetName, oldValue, newValue, ipAddress, createdAt`.
There is no `summary`, `riskLevel`, `affectedRoutes` or `recommendation` field anywhere in the
audit model, so a response like the one below is **not** produced by this system:

```json
{
  "id": 123,
  "summary": "Changed predicates: Path=/api/** to /api/v2/**, added Method=POST filter",
  "riskLevel": "LOW",
  "affectedRoutes": ["user-service", "order-service"],
  "recommendation": "Verify that all clients support /api/v2/** path"
}
```

What does exist: `GET /api/audit-logs/{id}/diff` returns a field-level comparison derived from the
stored `oldValue` / `newValue` JSON snapshots (see [Diff Comparison](#diff-comparison)).
`riskLevel` / `affectedRoutes` appear only in the AI Copilot **pending-action preview**
(`ToolExecutor.java:399-434`), which is unrelated to audit logs.

### Rollback Safety Check

**Not implemented.** There is no pre-rollback safety validation: grepping
`rollbackCheck|canRollback` across `gateway-admin/src/main/java` returns zero hits, and no
version-conflict / dependency / impact-analysis check runs before a rollback. The JSON below is
therefore **not** a real response:

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

The real behaviour (`AuditLogController.java:129-152` → `AuditLogService.rollback`) is:

1. Load the audit log by `id`; when it does not exist the service returns
   `{success: false, message: "Audit log not found"}`, which the controller surfaces as
   `ApiResponse.badRequest`.
2. If `oldValue` is empty, only a `CREATE` entry can be rolled back (rollback = delete); anything
   else returns `{success: false, message: "No previous version to rollback to"}`.
3. `performRollback(targetType, targetId, oldValue, operationType)` applies the stored snapshot.
4. Return `{success, message, targetType, targetId, rolledBackFromLogId}`; `operator` comes from the
   authenticated principal, never from the request body.

---

## AI Copilot Integration

AI Copilot can query and analyze audit logs. The audit/rollback-related tools actually registered in
`gateway-admin/src/main/java/com/leoli/gateway/admin/tool/ToolRegistry.java` are:

| Tool | Capability | Source |
|------|------------|--------|
| `audit_query` | Query audit logs with filters (operation type, target type, time range) | `ToolRegistry.java:876` |
| `audit_diff` | Get detailed before/after comparison for one audit log (`logId`) | `ToolRegistry.java:916` |
| `rollback_route` | Roll a route back to a historical version by audit log ID (flagged as risky) | `ToolRegistry.java:343` |

> `audit_stats` and `suggest_rollback` are **not** registered tools — they do not exist. There is
> also no tool literally named `rollback`; `ToolRegistry.java:1113` maps the *keyword* `rollback`
> (and `回滚`) to the `audit` / `route` intent categories, which is intent routing, not a tool.

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