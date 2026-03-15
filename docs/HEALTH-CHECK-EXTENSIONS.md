# 健康检查系统扩展功能实现

## ✅ 已完成的核心功能

### 1. 数据库持久化（JPA）

**文件清单：**
- ✅ `ServiceInstanceHealth.java` - JPA 实体
- ✅ `ServiceInstanceHealthRepository.java` - JPA Repository
- ✅ `InstanceHealthService.java` - Service 层（已集成）
- ✅ `pom.xml` - 添加 JPA 依赖

**配置项：**
```yaml
gateway:
  health:
    db-sync-enabled: false  # 默认关闭，可配置开启
```

**使用方法：**
1. 设置 `db-sync-enabled: true` 启用数据库同步
2. 不健康实例会自动保存到数据库
3. 支持 H2（开发）和 MySQL（生产）

---

## ⬜ 待实现功能

### 2. Nacos 元数据同步

**实现方案：**

```java
@Component
public class NacosMetadataSyncer {
    
    @Autowired
    private NamingService namingService;
    
    /**
     * 同步健康状态到 Nacos
     */
    public void syncToNacos(InstanceHealthDTO health, String gatewayId) {
        try {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("healthy", String.valueOf(health.isHealthy()));
            metadata.put("unhealthy-source", "GATEWAY_HEALTH_CHECK");
            metadata.put("unhealthy-time", String.valueOf(System.currentTimeMillis()));
            metadata.put("unhealthy-gateway", gatewayId);
            
            namingService.registerInstance(
                health.getServiceId(),
                health.getIp(),
                health.getPort(),
                metadata
            );
            
            log.info("Synced instance {}:{}:{} to Nacos", 
                     health.getServiceId(), health.getIp(), health.getPort());
        } catch (Exception e) {
            log.error("Failed to sync to Nacos", e);
        }
    }
}
```

**配置项：**
```yaml
gateway:
  health:
    nacos-sync-enabled: false  # 是否同步到 Nacos
```

---

### 3. 告警通知（可扩展入口）

#### 3.1 告警接口定义

```java
package com.example.gateway.admin.alert;

/**
 * 告警通知器接口
 */
public interface AlertNotifier {
    
    /**
     * 发送告警
     * @param title 标题
     * @param content 内容
     * @param level 告警级别
     */
    void sendAlert(String title, String content, AlertLevel level);
    
    /**
     * 是否支持该通知方式
     */
    boolean isSupported();
}

enum AlertLevel {
    INFO,      // 信息
    WARNING,   // 警告
    ERROR,     // 错误
    CRITICAL   // 严重
}
```

#### 3.2 钉钉通知实现

```java
@Component
@Slf4j
public class DingTalkAlertNotifier implements AlertNotifier {
    
    @Value("${gateway.health.alert.dingtalk.enabled:false}")
    private boolean enabled;
    
    @Value("${gateway.health.alert.dingtalk.webhook:}")
    private String webhookUrl;
    
    @Override
    public void sendAlert(String title, String content, AlertLevel level) {
        if (!enabled || webhookUrl == null) {
            return;
        }
        
        try {
            // 构建钉钉消息
            DingTalkMessage message = new DingTalkMessage();
            message.setMsgtype("markdown");
            message.setMarkdown(new Markdown(title, buildMarkdownContent(title, content, level)));
            
            // 发送 HTTP 请求
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.postForEntity(
                webhookUrl,
                message,
                String.class
            );
            
            log.info("Sent DingTalk alert: {}", title);
        } catch (Exception e) {
            log.error("Failed to send DingTalk alert", e);
        }
    }
    
    private String buildMarkdownContent(String title, String content, AlertLevel level) {
        StringBuilder sb = new StringBuilder();
        sb.append("#### ").append(title).append("\n\n");
        sb.append("> ").append(content).append("\n\n");
        sb.append("**告警级别**: ").append(level).append("\n");
        sb.append("**时间**: ").append(new Date()).append("\n");
        return sb.toString();
    }
    
    @Override
    public boolean isSupported() {
        return enabled && webhookUrl != null;
    }
}
```

#### 3.3 邮件通知实现

```java
@Component
@Slf4j
public class EmailAlertNotifier implements AlertNotifier {
    
    @Autowired
    private JavaMailSender mailSender;
    
    @Value("${gateway.health.alert.email.enabled:false}")
    private boolean enabled;
    
    @Value("${gateway.health.alert.email.to:}")
    private String toEmail;
    
    @Override
    public void sendAlert(String title, String content, AlertLevel level) {
        if (!enabled || toEmail == null) {
            return;
        }
        
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(toEmail);
            message.setSubject("[网关告警] " + title);
            message.setText(buildEmailContent(title, content, level));
            message.setSentDate(new Date());
            
            mailSender.send(message);
            log.info("Sent email alert to: {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send email alert", e);
        }
    }
    
    private String buildEmailContent(String title, String content, AlertLevel level) {
        return String.format(
            "告警标题：%s\n\n告警内容：%s\n\n告警级别：%s\n\n时间：%s",
            title, content, level, new Date()
        );
    }
    
    @Override
    public boolean isSupported() {
        return enabled && toEmail != null;
    }
}
```

#### 3.4 告警服务

```java
@Service
@Slf4j
public class AlertService {
    
    @Autowired
    private List<AlertNotifier> notifiers;
    
    /**
     * 发送告警（所有启用的通知器）
     */
    public void sendAlert(String title, String content, AlertLevel level) {
        for (AlertNotifier notifier : notifiers) {
            if (notifier.isSupported()) {
                try {
                    notifier.sendAlert(title, content, level);
                } catch (Exception e) {
                    log.error("Notifier {} failed to send alert", 
                             notifier.getClass().getSimpleName(), e);
                }
            }
        }
    }
    
    /**
     * 发送实例不健康告警
     */
    public void sendInstanceUnhealthyAlert(InstanceHealthDTO health) {
        String title = String.format("实例不健康：%s:%d", health.getIp(), health.getPort());
        String content = String.format(
            "服务：%s\nIP: %s\n端口：%d\n原因：%s\n失败次数：%d",
            health.getServiceId(),
            health.getIp(),
            health.getPort(),
            health.getUnhealthyReason(),
            health.getConsecutiveFailures()
        );
        
        sendAlert(title, content, AlertLevel.ERROR);
    }
}
```

**配置项：**
```yaml
gateway:
  health:
    alert:
      # 钉钉配置
      dingtalk:
        enabled: true
        webhook: https://oapi.dingtalk.com/robot/send?access_token=xxx
      
      # 邮件配置
      email:
        enabled: true
        to: admin@example.com
```

---

### 4. 多网关节点支持

#### 4.1 网关注册

```java
@Component
public class GatewayRegistry {
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Value("${gateway.admin.url:http://localhost:8080}")
    private String adminUrl;
    
    @Value("${gateway.id:gateway-1}")
    private String gatewayId;
    
    /**
     * 网关注册（启动时）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerGateway() {
        try {
            GatewayInfo info = new GatewayInfo();
            info.setId(gatewayId);
            info.setHost(getLocalHost());
            info.setPort(getLocalPort());
            info.setStartTime(System.currentTimeMillis());
            info.setStatus("ONLINE");
            
            restTemplate.postForObject(
                adminUrl + "/api/gateway/registry",
                info,
                Void.class
            );
            
            log.info("Gateway {} registered successfully", gatewayId);
        } catch (Exception e) {
            log.warn("Failed to register gateway", e);
        }
    }
    
    /**
     * 网关心跳（定时）
     */
    @Scheduled(fixedRate = 30000) // 每 30 秒
    public void heartbeat() {
        try {
            restTemplate.postForObject(
                adminUrl + "/api/gateway/heartbeat/" + gatewayId,
                null,
                Void.class
            );
        } catch (Exception e) {
            log.warn("Gateway heartbeat failed", e);
        }
    }
}
```

#### 4.2 Admin 端管理

```java
@RestController
@RequestMapping("/api/gateway")
public class GatewayRegistryController {
    
    @Autowired
    private GatewayRegistryService registryService;
    
    /**
     * 网关注册
     */
    @PostMapping("/registry")
    public ResponseEntity<Void> register(@RequestBody GatewayInfo info) {
        registryService.register(info);
        return ResponseEntity.ok().build();
    }
    
    /**
     * 网关心跳
     */
    @PostMapping("/heartbeat/{gatewayId}")
    public ResponseEntity<Void> heartbeat(@PathVariable String gatewayId) {
        registryService.updateHeartbeat(gatewayId);
        return ResponseEntity.ok().build();
    }
    
    /**
     * 获取所有网关
     */
    @GetMapping("/registry")
    public ResponseEntity<List<GatewayInfo>> getAllGateways() {
        return ResponseEntity.ok(registryService.getAllGateways());
    }
}
```

**配置项：**
```yaml
gateway:
  id: gateway-1  # 每个网关唯一标识
```

---

## 📊 完整配置示例

```yaml
gateway:
  health:
    # 基础配置
    enabled: true
    failure-threshold: 3
    recovery-time: 30000
    idle-threshold: 300000
    
    # 数据库同步
    db-sync-enabled: true
    
    # Nacos 同步
    nacos-sync-enabled: false
    
    # 告警配置
    alert:
      dingtalk:
        enabled: true
        webhook: https://oapi.dingtalk.com/robot/send?access_token=xxx
      
      email:
        enabled: false
        to: admin@example.com
    
    # Admin 地址
    admin-url: http://localhost:8080
    
    # 网关 ID（多网关场景）
    gateway-id: gateway-1
  
  health-check:
    endpoint: /actuator/health
    timeout: 3000
```

---

## 🎯 使用指南

### 启用数据库持久化

1. 设置配置：
```yaml
gateway:
  health:
    db-sync-enabled: true
```

2. 确保数据库表存在：
```bash
mysql -u root -p your_db < V2__add_health_check_fields.sql
```

3. 重启服务即可自动同步

---

### 启用钉钉告警

1. 创建钉钉机器人，获取 webhook

2. 配置：
```yaml
gateway:
  health:
    alert:
      dingtalk:
        enabled: true
        webhook: https://oapi.dingtalk.com/robot/send?access_token=YOUR_TOKEN
```

3. 当实例不健康时自动发送告警

---

### 多网关部署

1. 为每个网关设置唯一 ID：
```bash
# 网关 1
export GATEWAY_ID=gateway-1

# 网关 2
export GATEWAY_ID=gateway-2
```

2. Admin 端统一管理所有网关的健康状态

---

## 📝 总结

### 已完成
- ✅ 核心健康检查功能（被动 + 主动）
- ✅ 本地缓存存储
- ✅ Admin API 查询
- ✅ 前端展示组件
- ✅ 数据库持久化（JPA，可选）

### 待实现（留下扩展入口）
- ⬜ Nacos 元数据同步（需要 NamingService）
- ⬜ 告警通知（钉钉/邮件接口已定义）
- ⬜ 多网关节点支持（架构已预留）

所有扩展功能都通过配置项控制，按需启用，不影响核心功能。
