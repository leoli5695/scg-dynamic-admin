# Audit Logs

> 审计日志记录所有配置变更，支持差异对比和配置回滚。

---

## Overview

审计日志功能：
- 记录所有 CREATE/UPDATE/DELETE 操作
- 显示变更前后差异
- 支持回滚到历史版本

---

## Tracked Operations

| Operation | Description |
|-----------|-------------|
| `CREATE` | 新建配置 |
| `UPDATE` | 更新配置 |
| `DELETE` | 删除配置 |
| `ENABLE` | 启用配置 |
| `DISABLE` | 禁用配置 |
| `ROLLBACK` | 回滚配置 |

---

## Target Types

| Target Type | Description |
|-------------|-------------|
| `ROUTE` | 路由配置 |
| `SERVICE` | 服务配置 |
| `STRATEGY` | 策略配置 |
| `AUTH_POLICY` | 认证策略 |

---

## Data Model

```java
public class AuditLogEntity {
    private Long id;
    private String instanceId;       // 实例隔离
    private String operator;         // 操作者
    private String operationType;    // CREATE/UPDATE/DELETE
    private String targetType;       // ROUTE/SERVICE/STRATEGY
    private String targetId;         // 目标 ID
    private String targetName;       // 目标名称
    private String oldValue;         // 变更前 JSON
    private String newValue;         // 变更后 JSON
    private String ipAddress;        // 客户端 IP
    private Date createdAt;          // 时间戳
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

流程：
1. 加载历史版本配置
2. 验证配置有效性
3. 应用配置到当前
4. 发布到 Nacos
5. 记录 ROLLBACK 操作

---

## Configuration

```yaml
audit:
  retention-days: 30
  cleanup-schedule: "0 0 2 * * ?"
```

---

## Best Practices

1. **定期清理**：设置合理保留天数
2. **回滚验证**：回滚前验证配置有效性
3. **操作审计**：记录操作者用于追溯
4. **关键变更**：重要变更单独记录
5. **定期备份**：导出审计日志备份

---

## Related Features

- [Route Management](route-management.md) - 路由审计
- [Service Discovery](service-discovery.md) - 服务审计
- [Authentication](authentication.md) - 认证策略审计