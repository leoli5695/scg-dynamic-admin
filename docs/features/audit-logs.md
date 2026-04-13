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
| `GET` | `/api/audit-logs` | List audit logs |
| `GET` | `/api/audit-logs/{id}/diff` | Get change diff |
| `POST` | `/api/audit-logs/{id}/rollback` | Rollback config |
| `GET` | `/api/audit-logs/timeline/{instanceId}` | Get timeline |

### List Audit Logs

```bash
curl "http://localhost:9090/api/audit-logs?targetType=ROUTE&limit=100"
```

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
      "createdAt": "2024-01-15T10:30:00Z"
    }
  ]
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