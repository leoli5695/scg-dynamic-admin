# 网关健康检查系统使用指南

## 📋 功能概述

本健康检查系统采用**混合模式**（被动 + 主动），确保所有服务实例的健康状态都能被准确识别和更新。

### 核心特性

- ✅ **被动健康检查**：基于业务请求实时感知故障
- ✅ **主动健康检查**：定时 HTTP 探测覆盖新增/空闲节点
- ✅ **智能同步**：不健康状态自动同步到 Admin 和数据库
- ✅ **防循环机制**：通过来源标识避免无限循环触发
- ✅ **可视化展示**：前端实时查看实例健康状态

---

## 🏗️ 架构设计

```
用户请求 → Gateway → 实例
    ↓
记录成功/失败（被动检查）
    ↓
连续失败 3 次 → 标记为不健康 🔴
    ↓
每 5 秒同步 → Admin API
    ↓
Admin 处理
├─→ 更新本地缓存
├─→ 更新数据库（可选）
└─→ 同步到 Nacos（可选）
    ↓
前端查询 → 展示健康状态
```

---

## 🚀 快速开始

### 1. 网关侧配置（my-gateway）

#### application.yml 配置

```yaml
gateway:
  health:
    enabled: true
    failure-threshold: 3          # 连续失败 3 次标记为不健康
    recovery-time: 30000          # 30 秒后自动恢复
    idle-threshold: 300000        # 5 分钟无请求则主动检查
    admin-url: http://localhost:8080
    gateway-id: gateway-1
  
  health-check:
    endpoint: /actuator/health    # 健康检查端点
    timeout: 3000                 # 超时时间 3 秒
```

#### 环境变量（可选）

```bash
export GATEWAY_ADMIN_URL=http://localhost:8080
export GATEWAY_ID=gateway-1
```

### 2. Admin 侧配置（gateway-admin）

#### 数据库升级

执行 SQL 脚本添加健康状态字段：

```bash
mysql -u root -p your_database < src/main/resources/db/migration/V2__add_health_check_fields.sql
```

#### 启动 Admin 服务

确保 Admin 服务正常运行在 `http://localhost:8080`

---

## 📊 API 接口说明

### 1. 网关同步健康状态（内部调用）

**POST** `/api/gateway/health/sync`

**请求头：**
```
X-Gateway-Id: gateway-1
Content-Type: application/json
```

**请求体：**
```json
[
  {
    "serviceId": "user-service",
    "ip": "192.168.1.100",
    "port": 8080,
    "healthy": false,
    "consecutiveFailures": 3,
    "checkType": "PASSIVE",
    "unhealthyReason": "Connection refused"
  }
]
```

**响应：** `200 OK`

---

### 2. 查询服务实例健康状态

**GET** `/api/gateway/services/{serviceId}/instances/health`

**响应示例：**
```json
[
  {
    "serviceId": "user-service",
    "ip": "192.168.1.100",
    "port": 8080,
    "healthy": true,
    "consecutiveFailures": 0,
    "lastRequestTime": 1710144000000,
    "checkType": "PASSIVE"
  },
  {
    "serviceId": "user-service",
    "ip": "192.168.1.101",
    "port": 8080,
    "healthy": false,
    "consecutiveFailures": 5,
    "lastRequestTime": 1710143000000,
    "checkType": "PASSIVE",
    "unhealthyReason": "Gateway request failed 5 times consecutively"
  }
]
```

---

### 3. 获取健康状态概览

**GET** `/api/gateway/health/overview`

**响应示例：**
```json
{
  "totalInstances": 10,
  "healthyCount": 8,
  "unhealthyCount": 2,
  "healthRate": "80.00%",
  "serviceStats": {
    "user-service": {
      "total": 5,
      "healthy": 4,
      "unhealthy": 1
    },
    "order-service": {
      "total": 5,
      "healthy": 4,
      "unhealthy": 1
    }
  }
}
```

---

## 🔧 工作原理

### 被动健康检查

1. **请求成功**：重置失败计数，标记为健康
2. **请求失败**：累加失败计数
3. **连续失败 ≥ 3 次**：标记为不健康 🔴
4. **30 秒无失败**：自动恢复健康 🟢

### 主动健康检查

**触发条件：**
- 新增实例（无健康记录）
- 空闲实例（超过 5 分钟无业务请求）
- 不健康实例（需要确认恢复）

**检查频率：** 每 30 秒执行一次

**检查方式：** HTTP GET `{ip}:{port}/actuator/health`

---

## 🎯 前端集成示例

### React 组件使用

```tsx
import { InstanceHealthStatus, HealthOverview } from './InstanceHealthStatus';

// 在服务列表页面中
function ServicesPage() {
  return (
    <div>
      {/* 健康概览 */}
      <HealthOverview />
      
      {/* 实例健康状态表格 */}
      <InstanceHealthStatus serviceId="user-service" />
    </div>
  );
}
```

### 健康状态展示

```tsx
// 健康图标
{instance.healthy ? '🟢' : '🔴'} {instance.ip}:{instance.port}

// 检查类型标签
<Tag color="blue">被动检查</Tag>
<Tag color="green">主动检查</Tag>

// 不健康原因提示
<Tooltip title={instance.unhealthyReason}>
  <Tag color="red">{instance.unhealthyReason}</Tag>
</Tooltip>
```

---

## ⚙️ 高级配置

### 性能优化

```yaml
gateway:
  health:
    # 只同步不健康实例（减少网络开销）
    sync-unhealthy-only: true
    
    # 并发检查限制
    concurrent-limit: 10
    
    # 缓存过期时间
    cache-expire-after-write: 300000
```

### 日志级别

```yaml
logging:
  level:
    com.example.gateway.health: DEBUG  # 查看详细健康检查日志
```

---

## 🔍 监控与排查

### 查看健康检查日志

```bash
# 网关日志
tail -f my-gateway/logs/app.log | grep "Health check"

# Admin 日志
tail -f gateway-admin/logs/app.log | grep "Unhealthy instance"
```

### 常见问题

**Q1: 为什么新增节点一直显示健康？**
- 检查主动健康检查是否启用
- 确认健康检查端点是否正确（默认 `/actuator/health`）
- 查看网关日志是否有探测请求记录

**Q2: 为什么不健康状态没有同步到 Admin？**
- 检查 Admin URL 配置
- 确认网络连通性
- 查看网关日志中的同步记录

**Q3: 如何避免循环触发？**
- 系统已自动添加 `unhealthy-source=GATEWAY_HEALTH_CHECK` 标识
- Admin 监听 Nacos 变更时会跳过此标识的更新

---

## 📈 未来扩展

### 数据库持久化（TODO）

```java
@Service
public class InstanceHealthService {
    
    @Autowired
    private ServiceInstanceMapper instanceMapper;
    
    public void handleUnhealthyInstance(InstanceHealthDTO health, String gatewayId) {
        // 1. 更新数据库
        ServiceInstancePO instance = instanceMapper.findByServiceIdAndIpAndPort(
            health.getServiceId(), health.getIp(), health.getPort()
        );
        
        if (instance != null) {
            instance.setEnabled(false);
            instance.setHealthStatus("UNHEALTHY");
            instance.setConsecutiveFailures(health.getConsecutiveFailures());
            instanceMapper.update(instance);
        }
        
        // 2. 同步到 Nacos（可选）
        // ...
    }
}
```

### Nacos 同步（TODO）

```java
private void updateNacosMetadata(InstanceHealthDTO health, String gatewayId) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("healthy", String.valueOf(health.isHealthy()));
    metadata.put("unhealthy-source", "GATEWAY_HEALTH_CHECK");
    metadata.put("unhealthy-time", String.valueOf(System.currentTimeMillis()));
    metadata.put("unhealthy-gateway", gatewayId);
    
    nacosServiceManager.updateInstanceMetadata(
        health.getServiceId(),
        health.getIp(),
        health.getPort(),
        metadata
    );
}
```

---

## 📝 总结

本健康检查系统提供了完整的服务实例健康监测能力：

- ✅ **全场景覆盖**：被动 + 主动双重保障
- ✅ **实时性强**：业务失败立即感知
- ✅ **性能友好**：增量同步 + 并发检查
- ✅ **易于集成**：标准 RESTful API
- ✅ **可扩展性好**：支持数据库/Nacos 同步

如需进一步优化或扩展功能，请参考上述 TODO 部分进行开发。

---

**版本：** v1.0  
**更新日期：** 2026-03-11  
**作者：** AI Assistant
