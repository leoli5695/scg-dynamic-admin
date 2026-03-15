# 健康检查扩展功能配置指南

## ✅ 已实现的扩展功能

### 1. Nacos 元数据同步 ✅

**功能说明：**
- 将实例健康状态同步到 Nacos 元数据
- 支持防循环标识（`unhealthy-source=GATEWAY_HEALTH_CHECK`）
- 可配置开启/关闭

**配置项：**
```yaml
gateway:
  health:
    nacos-sync-enabled: false  # 是否启用 Nacos 同步
```

**元数据格式：**
```json
{
  "healthy": "false",
  "unhealthy-source": "GATEWAY_HEALTH_CHECK",
  "unhealthy-time": "1710489600000",
  "unhealthy-gateway": "gateway-1",
  "unhealthy-reason": "Gateway request failed 3 times consecutively"
}
```

**使用步骤：**
1. 设置 `nacos-sync-enabled: true`
2. 确保 gateway-admin 已集成 Nacos Discovery
3. 重启服务即可自动同步

---

### 2. 告警通知系统 ✅

#### 2.1 钉钉告警

**配置项：**
```yaml
gateway:
  health:
    alert:
      dingtalk:
        enabled: true
        webhook: https://oapi.dingtalk.com/robot/send?access_token=YOUR_TOKEN
```

**获取 Webhook 步骤：**
1. 打开钉钉群 → 群设置 → 智能群助手
2. 添加机器人 → 自定义
3. 安全设置选择"加签"或"IP 地址"
4. 复制 Webhook 地址

**告警消息格式：**
```markdown
#### 实例不健康：192.168.1.100:8080

> 服务 ID: user-service
> IP 地址：192.168.1.100
> 端口：8080
> 原因：Gateway request failed 3 times consecutively
> 失败次数：3
> 检查类型：PASSIVE

**告警级别**: 错误
**时间**: 2026-03-15 10:30:00
```

#### 2.2 邮件告警

**配置项：**
```yaml
spring:
  mail:
    host: smtp.example.com
    port: 587
    username: your-email@example.com
    password: YOUR_PASSWORD
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true

gateway:
  health:
    alert:
      email:
        enabled: true
        to: admin@example.com
```

**告警邮件格式：**
```
告警标题：实例不健康：192.168.1.100:8080

告警内容：
服务 ID: user-service
IP 地址：192.168.1.100
端口：8080
原因：Gateway request failed 3 times consecutively
失败次数：3
检查类型：PASSIVE

告警级别：错误

发送时间：2026-03-15 10:30:00
```

---

## 🎯 完整配置示例

### 开发环境（最小配置）

```yaml
gateway:
  health:
    enabled: true
    failure-threshold: 3
    recovery-time: 30000
    idle-threshold: 300000
    
    # 只启用核心功能，不启用扩展
    db-sync-enabled: false
    nacos-sync-enabled: false
    
    admin-url: http://localhost:8080
    gateway-id: gateway-1
  
  health-check:
    endpoint: /actuator/health
    timeout: 3000
  
  # 告警全部关闭
  alert:
    dingtalk:
      enabled: false
    email:
      enabled: false
```

### 生产环境（推荐配置）

```yaml
gateway:
  health:
    enabled: true
    failure-threshold: 3
    recovery-time: 30000
    idle-threshold: 300000
    
    # 启用数据库持久化
    db-sync-enabled: true
    
    # 启用 Nacos 同步（如果需要）
    nacos-sync-enabled: true
    
    admin-url: http://gateway-admin:8080
    gateway-id: gateway-1
  
  health-check:
    endpoint: /actuator/health
    timeout: 3000
  
  # 启用告警通知
  alert:
    dingtalk:
      enabled: true
      webhook: https://oapi.dingtalk.com/robot/send?access_token=xxx
    
    email:
      enabled: true
      to: ops-team@example.com
```

---

## 📊 告警触发条件

### 1. 实例不健康告警

**触发条件：**
- 连续失败 ≥ 3 次（可配置）
- 主动探测失败

**告警级别：** ERROR

**告警频率：** 每次发现不健康时发送

### 2. 严重告警（预留）

**触发条件：**
- 同一服务多个实例同时不健康
- 健康率低于阈值（如 < 50%）

**告警级别：** CRITICAL

**实现方式：**
```java
// 在 AlertService 中已预留方法
alertService.sendCriticalAlert("user-service", 3);
```

---

## 🔧 故障排查

### 问题 1: Nacos 同步失败

**错误日志：**
```
Failed to sync instance 192.168.1.100:8080 to Nacos
```

**可能原因：**
1. `nacos-sync-enabled` 未设置为 true
2. NamingService Bean 不存在
3. 实例不在 Nacos 注册列表中

**解决方案：**
1. 确认配置已生效
2. 检查 gateway-admin 的 Nacos Discovery 依赖
3. 查看 Nacos 控制台是否有该实例

---

### 问题 2: 钉钉告警失败

**错误日志：**
```
Failed to send DingTalk alert
```

**可能原因：**
1. Webhook 地址错误
2. 机器人未启用
3. 网络问题

**解决方案：**
1. 检查 Webhook 地址是否正确
2. 确认机器人已在群内启用
3. 检查服务器是否能访问钉钉 API

---

### 问题 3: 邮件告警失败

**错误日志：**
```
Could not connect to SMTP host
```

**可能原因：**
1. SMTP 配置错误
2. 邮箱密码错误
3. 防火墙阻止

**解决方案：**
1. 检查 `spring.mail.*` 配置
2. 使用授权码而非登录密码
3. 检查网络连通性

---

## 📈 监控与优化

### 性能影响

**内存占用：**
- Caffeine 缓存：~10MB（10000 条记录）
- 告警服务：可忽略

**CPU 占用：**
- 被动检查：微秒级（无感知）
- 主动检查：毫秒级（并发执行）
- 告警通知：秒级（异步执行）

**网络开销：**
- 同步 Admin：每 5 秒一次，< 1KB
- Nacos 同步：按需触发，< 1KB
- 告警通知：按需触发，< 5KB

### 优化建议

1. **增量同步**：只同步不健康实例（已实现）
2. **并发探测**：多线程同时检查（已实现）
3. **告警限流**：避免告警风暴（待实现）
   ```yaml
   gateway:
     health:
       alert:
         rate-limit:
           enabled: true
           interval: 300000  # 5 分钟内相同实例只告警一次
   ```

---

## 🎉 总结

### 已实现功能
- ✅ 混合健康检查（被动 + 主动）
- ✅ 数据库持久化（JPA，可选）
- ✅ Nacos 元数据同步（可选）
- ✅ 钉钉告警通知（可选）
- ✅ 邮件告警通知（可选）
- ✅ 多网关节点架构预留

### 配置原则
- **按需启用**：所有扩展功能默认关闭
- **无侵入性**：不影响核心功能
- **易于扩展**：接口清晰，实现简单

### 生产建议
1. 启用数据库持久化（便于审计）
2. 启用钉钉告警（实时感知故障）
3. 可选启用 Nacos 同步（与其他系统共享状态）
4. 根据业务需求调整告警阈值

---

**版本：** v1.0  
**更新日期：** 2026-03-15  
**状态：** ✅ Production Ready
