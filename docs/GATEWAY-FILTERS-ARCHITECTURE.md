# 网关过滤器架构与执行顺序

## 📋 完整的过滤器链

### **标准执行顺序**

```
Request → Gateway
    ↓
[1] IPFilterGlobalFilter        (Ordered.LOWEST_PRECEDENCE - 20)
    ↓
[2] TraceIdGlobalFilter         (-300)                        ← Trace ID + MDC
    ↓
[3] AuthenticationGlobalFilter  (-250)                        ← 统一鉴权
    ↓
[4] LocalRateLimiterFilter      (HIGHEST_PRECEDENCE + 20)     ← Redis + 本地限流
    ↓
[5] TimeoutGlobalFilter         (LOWEST_PRECEDENCE - 10)
    ↓
[6] CircuitBreakerGlobalFilter  (LOWEST_PRECEDENCE)
    ↓
Backend Service
```

---

## ✅ 核心过滤器列表

### **1. IPFilterGlobalFilter**
- **文件：** `IPFilterGlobalFilter.java`
- **功能：** IP 黑白名单过滤
- **优先级：** `LOWEST_PRECEDENCE - 20`
- **配置来源：** StrategyManager（Nacos 配置）

---

### **2. TraceIdGlobalFilter** ✅
- **文件：** [`TraceIdGlobalFilter.java`](d:\source\my-gateway\src\main\java\com\example\gateway\filter\TraceIdGlobalFilter.java)
- **功能：** 
  - ✅ 生成或获取 Trace ID
  - ✅ 添加到请求头 `X-Trace-Id`
  - ✅ 添加到响应头
  - ✅ **MDC 日志追踪**（关键特性）
- **优先级：** `-300`（非常高）
- **Header 名称：** `X-Trace-Id`（注意大小写）

**关键代码：**
```java
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = getOrGenerateTraceId(exchange);
        
        // 添加到 MDC（日志追踪）
        MDC.put("traceId", traceId);
        
        // 添加到请求头和响应头
        return chain.filter(mutatedExchange)
            .doOnSuccess(aVoid -> {
                if (!exchange.getResponse().isCommitted()) {
                    exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);
                }
            })
            .doFinally(signalType -> {
                MDC.clear();  // 清理 MDC
            });
    }
}
```

---

### **3. AuthenticationGlobalFilter** ✅
- **文件：** [`AuthenticationGlobalFilter.java`](d:\source\my-gateway\src\main\java\com\example\gateway\filter\AuthenticationGlobalFilter.java)
- **功能：** 统一鉴权认证
- **优先级：** `-250`
- **配置来源：** StrategyManager（从 Nacos 动态加载）
- **支持的鉴权类型：** JWT、API Key、OAuth2 等

**关键代码：**
```java
@Component
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {
    @Autowired
    private StrategyManager strategyManager;
    @Autowired
    private AuthProcessManager authProcessManager;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = getRouteId(exchange);
        
        // 从 StrategyManager 加载鉴权配置
        AuthConfig authConfig = strategyManager.getConfig(StrategyType.AUTH, routeId);
        
        if (authConfig == null || !authConfig.isEnabled()) {
            return chain.filter(exchange);
        }
        
        // 委托给 AuthProcessManager 处理
        return authProcessManager.authenticate(exchange, authConfig)
            .then(chain.filter(exchange));
    }
}
```

**架构图：**
```
┌──────────────────────────────┐
│ AuthenticationGlobalFilter   │
└──────────┬───────────────────┘
           │
    ┌──────▼────────┐
    │ StrategyManager│ ← 从 Nacos 加载配置
    └──────┬─────────┘
           │
    ┌──────▼────────┐
    │AuthProcessManager│
    └──────┬─────────┘
           │
    ┌──────┴──────────┐
    │                 │
┌───▼───┐       ┌────▼────┐
│ JWT   │       │API Key  │
│Processor│      │Processor│
└───────┘       └─────────┘
```

---

### **4. LocalRateLimiterFilter** ✅
- **文件：** [`LocalRateLimiterFilter.java`](d:\source\my-gateway\src\main\java\com\example\gateway\filter\strategy\LocalRateLimiterFilter.java)
- **功能：** 
  - ✅ Redis 分布式限流（主用）
  - ✅ 本地限流降级（兜底）
  - ✅ 滑动时间窗口算法
- **优先级：** `HIGHEST_PRECEDENCE + 20`
- **配置来源：** 默认配置（后续会集成 StrategyManager）

**关键代码：**
```java
@Component
public class LocalRateLimiterFilter implements GlobalFilter, Ordered {
    @Autowired(required = false)
    private RedisRateLimiter redisRateLimiter;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String rateLimitKey = buildRateLimitKey(exchange, config);
        
        // 优先使用 Redis 分布式限流
        if (redisRateLimiter != null && redisRateLimiter.isRedisAvailable()) {
            boolean allowed = redisRateLimiter.tryAcquire(rateLimitKey, config.qps, config.windowSizeMs);
            
            if (allowed) {
                return chain.filter(exchange);
            } else {
                return rejectRequest(exchange);
            }
        }
        
        // Redis 不可用，降级到本地限流
        RateLimiterWindow window = getOrCreateWindow(rateLimitKey, config);
        
        if (window.tryAcquire()) {
            return chain.filter(exchange);
        } else {
            return rejectRequest(exchange);
        }
    }
}
```

---

### **5. TimeoutGlobalFilter**
- **文件：** `TimeoutGlobalFilter.java`
- **功能：** 连接/读取/响应超时控制
- **优先级：** `LOWEST_PRECEDENCE - 10`

---

### **6. CircuitBreakerGlobalFilter**
- **文件：** `CircuitBreakerGlobalFilter.java`
- **功能：** 熔断保护（基于 Resilience4j）
- **优先级：** `LOWEST_PRECEDENCE`

---

## ❌ **已删除的重复实现**

### **删除的文件（4 个）**

| 文件名 | 原因 | 替代方案 |
|-------|------|---------|
| `TracingFilter.java` | ❌ 功能重复 | ✅ `TraceIdGlobalFilter.java` |
| `AuthFilter.java` | ❌ 破坏架构 | ✅ `AuthenticationGlobalFilter.java` |
| `TracingStrategyConfig.java` | ❌ 不需要 | ✅ 使用 TraceIdGlobalFilter 内置逻辑 |
| `AuthStrategyConfig.java` | ❌ 配置体系冲突 | ✅ 使用 StrategyManager 统一配置 |

---

## 🎯 **设计原则**

### **1. 单一职责**
每个过滤器只负责一个功能：
- ✅ TraceIdGlobalFilter - 只负责 Trace ID
- ✅ AuthenticationGlobalFilter - 只负责鉴权
- ✅ LocalRateLimiterFilter - 只负责限流

### **2. 统一管理**
- ✅ 所有配置来自 StrategyManager（Nacos 集中管理）
- ✅ 避免多个配置源冲突

### **3. 优先级明确**
- ✅ 数字越小优先级越高
- ✅ 安全相关（IP、鉴权）优先级高
- ✅ 容错相关（超时、熔断）优先级低

### **4. MDC 日志追踪**
- ✅ TraceIdGlobalFilter 提供 MDC 支持
- ✅ 所有日志自动包含 Trace ID
- ✅ 便于问题排查和链路追踪

---

## 📊 **性能对比**

### **TraceIdGlobalFilter vs TracingFilter**

| 特性 | TraceIdGlobalFilter（保留） | TracingFilter（已删除） |
|------|---------------------------|------------------------|
| **MDC 支持** | ✅ 有 | ❌ 无 |
| **响应头** | ✅ 添加 Trace ID | ❌ 未添加 |
| **日志格式** | `traceId=xxx` | 普通日志 |
| **优先级** | -300（更高） | HIGHEST_PRECEDENCE |
| **Header 名称** | `X-Trace-Id` | `X-Trace-ID`（不一致） |

**结论：** TraceIdGlobalFilter 更完善，必须保留！

---

### **AuthenticationGlobalFilter vs AuthFilter**

| 特性 | AuthenticationGlobalFilter（保留） | AuthFilter（已删除） |
|------|----------------------------------|---------------------|
| **配置来源** | StrategyManager（Nacos） | 独立配置类 |
| **架构一致性** | ✅ 符合统一架构 | ❌ 破坏架构 |
| **扩展性** | ✅ 支持插件化 Processor | ❌ 硬编码 |
| **可维护性** | ✅ 配置集中管理 | ❌ 配置分散 |

**结论：** AuthenticationGlobalFilter 是架构的核心部分，不能替换！

---

## 🔧 **配置示例**

### **启用所有过滤器**

```yaml
# application.yml
gateway:
  strategies:
    # IP 过滤
    ip-filter:
      enabled: true
      mode: whitelist
      ips:
        - 192.168.1.0/24
    
    # 鉴权（通过 StrategyManager 管理）
    auth:
      enabled: true
      type: JWT
      jwt:
        secret-key: your-secret-key
    
    # 限流
    rate-limiter:
      enabled: true
      qps: 100
      key-type: combined
```

---

## 🎯 **最佳实践**

### **1. 不要重复实现**
- ❌ 不要创建第二个 Trace ID 过滤器
- ❌ 不要创建第二个鉴权过滤器
- ✅ 使用现有的、经过验证的实现

### **2. 遵循架构规范**
- ✅ 配置统一来自 StrategyManager
- ✅ 新过滤器要定义明确的优先级
- ✅ 不要破坏现有的责任链

### **3. MDC 日志追踪**
```java
// 在任意过滤器中打印日志
log.info("Processing request"); 
// 输出：[traceId=xxx-xxx-xxx] Processing request
```

---

## 📚 **相关文件清单**

### **核心过滤器（6 个）**
1. ✅ `IPFilterGlobalFilter.java`
2. ✅ `TraceIdGlobalFilter.java` ← **Trace ID 唯一实现**
3. ✅ `AuthenticationGlobalFilter.java` ← **鉴权唯一实现**
4. ✅ `LocalRateLimiterFilter.java`
5. ✅ `TimeoutGlobalFilter.java`
6. ✅ `CircuitBreakerGlobalFilter.java`

### **支撑组件**
- `StrategyManager.java` - 策略配置管理
- `AuthProcessManager.java` - 鉴权处理器管理
- `RedisRateLimiter.java` - Redis 限流器
- `RedisConfig.java` - Redis 配置

### **已删除（重复实现）**
- ❌ `TracingFilter.java` - 已删除
- ❌ `AuthFilter.java` - 已删除
- ❌ `TracingStrategyConfig.java` - 已删除
- ❌ `AuthStrategyConfig.java` - 已删除

---

**更新日期：** 2026-03-15  
**状态：** ✅ 架构已清理，消除重复实现  
**教训：** 新增功能前必须先了解现有架构！
