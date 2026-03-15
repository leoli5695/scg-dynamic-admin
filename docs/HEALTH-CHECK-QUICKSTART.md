# 健康检查系统快速启动指南

## 🚀 5 分钟快速上手

### Step 1: 数据库升级（1 分钟）

```bash
# 进入 MySQL
mysql -u root -p your_gateway_db

# 执行 SQL 脚本
source d:\source\gateway-admin\src\main\resources\db\migration\V2__add_health_check_fields.sql;

# 验证
DESC service_instances;
```

---

### Step 2: 启动 Admin 服务（1 分钟）

```bash
cd d:\source\gateway-admin

# 设置环境变量（可选）
set GATEWAY_ADMIN_URL=http://localhost:8080

# 启动
mvn spring-boot:run
```

**验证：** 访问 http://localhost:8080/api/gateway/health/overview

---

### Step 3: 启动网关服务（1 分钟）

```bash
cd d:\source\my-gateway

# 设置环境变量
set GATEWAY_ADMIN_URL=http://localhost:8080
set GATEWAY_ID=gateway-1

# 启动
mvn spring-boot:run
```

**验证：** 查看日志中是否有 "DiscoveryLoadBalancerFilter initialized with ... HealthChecker"

---

### Step 4: 测试健康检查（2 分钟）

#### 测试 1: 被动健康检查

```bash
# 发送请求到网关（正常服务）
curl http://localhost/user-service/api/users

# 查看网关日志，应该看到：
# "Recorded success for instance x.x.x.x:xxxx"
```

#### 测试 2: 模拟服务宕机

1. 停止后端服务实例
2. 发送请求到网关
3. 连续失败 3 次后，查看网关日志：
   ```
   "Instance x.x.x.x:xxxx marked as unhealthy (failures=3)"
   ```

#### 测试 3: 查看 Admin API

```bash
# 查询健康状态概览
curl http://localhost:8080/api/gateway/health/overview

# 查询特定服务的健康状态
curl http://localhost:8080/api/gateway/services/user-service/instances/health
```

---

## 📊 预期输出示例

### Admin API 响应

```json
{
  "totalInstances": 5,
  "healthyCount": 4,
  "unhealthyCount": 1,
  "healthRate": "80.00%",
  "serviceStats": {
    "user-service": {
      "total": 3,
      "healthy": 2,
      "unhealthy": 1
    },
    "order-service": {
      "total": 2,
      "healthy": 2,
      "unhealthy": 0
    }
  }
}
```

### 网关日志

```
2026-03-11 10:00:00 INFO  - DiscoveryLoadBalancerFilter initialized with StaticDiscoveryService and HealthChecker
2026-03-11 10:00:05 DEBUG - Recorded success for instance 192.168.1.100:8080
2026-03-11 10:00:10 WARN  - Recorded failure for instance 192.168.1.101:8080 - Connection refused
2026-03-11 10:00:15 WARN  - Recorded failure for instance 192.168.1.101:8080 - Connection refused
2026-03-11 10:00:20 WARN  - Instance 192.168.1.101:8080 marked as unhealthy (failures=3)
2026-03-11 10:00:25 INFO  - Synced 1 unhealthy instances to admin [gateway-1]
```

---

## 🔍 故障排查

### 问题 1: 网关启动失败

**错误：** `BeanCreationException: Error creating bean with name 'discoveryLoadBalancerFilter'`

**解决：** 确保 Spring 自动扫描到了 `HybridHealthChecker` 组件

```java
// 确认 @Component 注解存在
@Component
@Slf4j
public class HybridHealthChecker { ... }
```

---

### 问题 2: Admin 接收不到同步

**检查：**
1. 网络连通性：`telnet localhost 8080`
2. Admin 服务是否正常运行
3. 网关配置中的 `admin-url` 是否正确

```yaml
gateway:
  health:
    admin-url: http://localhost:8080  # 确认地址正确
```

---

### 问题 3: 主动健康检查未触发

**检查调度器是否启用：**

```java
// 启动类添加 @EnableScheduling
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {
    public static void main(String[] args) { ... }
}
```

**查看日志：**
```
grep "Starting active health check" my-gateway/logs/app.log
```

---

## 🎯 下一步

### 基础功能（已完成）
- ✅ 被动健康检查
- ✅ 主动健康检查
- ✅ 同步到 Admin
- ✅ 前端展示组件

### 进阶功能（可选）
- ⬜ 数据库持久化
- ⬜ Nacos 元数据同步
- ⬜ 告警通知（邮件/钉钉）
- ⬜ 多网关节点支持

---

## 📚 参考文档

- [完整使用指南](./HEALTH-CHECK-GUIDE.md)
- [实现总结](./HEALTH-CHECK-IMPLEMENTATION.md)
- [API 文档](./API.md)

---

## ✅ 验证清单

- [ ] Admin 服务启动成功
- [ ] 网关服务启动成功
- [ ] 数据库表结构已升级
- [ ] 能够调用 Admin API 查询健康状态
- [ ] 网关日志中有健康检查记录
- [ ] 模拟服务宕机能触发不健康标记

---

**版本：** v1.0  
**最后更新：** 2026-03-11
