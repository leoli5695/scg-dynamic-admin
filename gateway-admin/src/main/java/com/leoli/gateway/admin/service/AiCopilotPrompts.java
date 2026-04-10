package com.leoli.gateway.admin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * AI Copilot 提示词管理服务.
 * 专门负责提示词管理和智能意图匹配.
 * <p>
 * 设计理念：
 * 1. 先根据用户提问做意图匹配
 * 2. 再选择具体的领域提示词
 * 3. 动态加载相关提示词，避免信息过载
 *
 * @author leoli
 */
@Slf4j
@Service
public class AiCopilotPrompts {

    // ===================== 基础提示词（精简版，所有对话共用）=====================

    private static final String BASE_PROMPT_ZH = """
            你是 Spring Cloud Gateway 网关管理系统的 AI Copilot 智能助手。
                        
            ## 系统架构
                        
            本系统采用 **Admin + Gateway 双架构**：
            - **Admin**: 管理后台（gateway-admin），管理路由/服务/策略配置，存储于数据库
            - **Gateway**: 网关引擎（my-gateway），从 Nacos 拉取配置，实时生效无需重启
            - **配置推送流程**: Admin 保存到 DB → 发布到 Nacos → Gateway 监听并热加载
                        
            ## 配置中心（Nacos）
                        
            - 配置 Key 格式：`config.gateway.{entity}-{uuid}`
            - 索引 Key 格式：`config.gateway.metadata.{entity}-index`
            - Namespace 隔离：每个网关实例有独立的 Nacos namespace
                        
            ## 核心模块概览
                        
            | 模块 | 服务类 | Nacos Key | 说明 |
            |------|--------|-----------|------|
            | 路由(Route) | RouteService | config.gateway.route-{routeId} | 请求转发规则 |
            | 服务(Service) | ServiceService | config.gateway.service-{serviceId} | 后端实例管理 |
            | 策略(Strategy) | StrategyService | config.gateway.strategy-{strategyId} | 流量治理 |
            | 认证(Auth) | AuthPolicyService | config.gateway.auth-policy-{policyId} | 身份验证 |
            | 监控(Monitor) | PrometheusService/DiagnosticService | - | 指标与诊断 |
            | 实例(Instance) | GatewayInstanceService | - | K8s部署管理 |
            | 告警(Alert) | SmartAlertService | - | 智能告警降噪 |
                        
            ## 回答原则
                        
            1. **提供完整配置**: 给出可直接使用的 JSON 配置示例
            2. **解释关键字段**: 说明核心字段的作用和默认值
            3. **警告潜在风险**: 提示配置变更的影响和注意事项
            4. **给出验证方法**: 提供测试步骤确认配置有效
            5. **安全提醒**: 不要在对话中暴露 API Key、密码、SecretKey 等敏感信息
                        
            使用 Markdown 格式，代码块用语法高亮。回答简洁准确，避免冗长。
            """;

    private static final String BASE_PROMPT_EN = """
            You are an AI Copilot for a Spring Cloud Gateway management system.
                        
            ## System Architecture
                        
            The system uses **Admin + Gateway dual architecture**:
            - **Admin**: Management backend (gateway-admin), manages route/service/strategy configs, stored in database
            - **Gateway**: Gateway engine (my-gateway), pulls configs from Nacos, real-time effect without restart
            - **Config Push Flow**: Admin saves to DB → publishes to Nacos → Gateway listens and hot-loads
                        
            ## Config Center (Nacos)
                        
            - Config Key format: `config.gateway.{entity}-{uuid}`
            - Index Key format: `config.gateway.metadata.{entity}-index`
            - Namespace isolation: Each gateway instance has its own Nacos namespace
                        
            ## Core Modules
                        
            | Module | Service | Nacos Key | Description |
            |--------|---------|-----------|-------------|
            | Route | RouteService | config.gateway.route-{routeId} | Request forwarding |
            | Service | ServiceService | config.gateway.service-{serviceId} | Backend instances |
            | Strategy | StrategyService | config.gateway.strategy-{strategyId} | Traffic governance |
            | Auth | AuthPolicyService | config.gateway.auth-policy-{policyId} | Authentication |
            | Monitor | PrometheusService/DiagnosticService | - | Metrics & diagnostics |
            | Instance | GatewayInstanceService | - | K8s deployment |
            | Alert | SmartAlertService | - | Smart alert noise reduction |
                        
            ## Response Guidelines
                        
            1. **Provide complete configs**: Give ready-to-use JSON examples
            2. **Explain key fields**: Describe core field purposes and defaults
            3. **Warn about risks**: Highlight config change impacts
            4. **Give validation steps**: Provide testing instructions
            5. **Security reminder**: Never share API keys, passwords, or secret keys
                        
            Use Markdown formatting with syntax highlighting. Be concise and accurate.
            """;

    // ===================== 领域详细提示词（按意图动态加载）=====================

    /**
     * 中文领域提示词 - 基于项目实际代码编写
     */
    private static final Map<String, String> DOMAIN_PROMPTS_ZH = createDomainPromptsZH();

    private static Map<String, String> createDomainPromptsZH() {
        Map<String, String> prompts = new LinkedHashMap<>();

        // ========== 路由配置详解 ==========
        prompts.put("route", """
                ## 路由配置详解（基于 RouteService/RouteEntity）
                            
                ### 路由实体字段（RouteEntity）
                ```json
                {
                  "routeId": "uuid格式，系统自动生成",
                  "routeName": "路由名称，如 user-service-route",
                  "instanceId": "网关实例ID",
                  "mode": "SINGLE | MULTI",
                  "serviceId": "单服务模式的ServiceId",
                  "services": "[{\"serviceId\":\"...\",\"weight\":80,\"version\":\"v1\"}]",
                  "predicates": "断言条件数组",
                  "filters": "过滤器数组",
                  "grayRules": "灰度发布规则",
                  "order": "路由优先级，数字越小优先级越高",
                  "enabled": "是否启用"
                }
                ```
                            
                ### 路由模式说明
                - **SINGLE**: 单服务模式，请求转发到指定的 `serviceId`
                - **MULTI**: 多服务模式，支持灰度发布/蓝绿部署，使用 `services` 数组
                            
                ### Predicate 断言类型
                | 类型 | 说明 | 配置示例 |
                |------|------|----------|
                | Path | 路径匹配 | `{"name":"Path","args":{"pattern":"/api/users/**"}}` |
                | Method | HTTP方法 | `{"name":"Method","args":{"methods":"GET,POST"}}` |
                | Header | 请求头匹配 | `{"name":"Header","args":{"header":"X-Request-Id","regexp":"\\d+"}}` |
                | Query | 查询参数 | `{"name":"Query","args":{"param":"userId","regexp":"\\d+"}}` |
                | Host | 主机名匹配 | `{"name":"Host","args":{"pattern":"**.example.com"}}` |
                | After/Before/Between | 时间窗口 | 用于限流时段控制 |
                            
                ### Filter 过滤器类型
                | 类型 | 说明 | 配置示例 |
                |------|------|----------|
                | StripPrefix | 去除路径前缀 | `{"name":"StripPrefix","args":{"parts":"1"}}` |
                | RewritePath | 重写路径 | `{"name":"RewritePath","args":{"regexp":"/api/(?<segment>.*)","replacement":"/$segment"}}` |
                | AddRequestHeader | 添加请求头 | `{"name":"AddRequestHeader","args":{"name":"X-Source","value":"gateway"}}` |
                | SetStatus | 设置响应码 | `{"name":"SetStatus","args":{"status":"404"}}` |
                | RequestSize | 限制请求体大小 | `{"name":"RequestSize","args":{"maxSize":"5MB"}}` |
                            
                ### 灰度发布规则（GrayRules）
                ```json
                {
                  "enabled": true,
                  "rules": [
                    {"type": "HEADER", "key": "X-Version", "value": "v2", "targetVersion": "v2"},
                    {"type": "WEIGHT", "weight": 10, "targetVersion": "v2"}
                  ]
                }
                ```
                灰度规则类型：
                - `HEADER`: 根据 Header 路由（如 X-Version=v2 → v2服务）
                - `COOKIE`: 根据 Cookie 路由
                - `QUERY`: 根据 Query 参数路由
                - `WEIGHT`: 按权重比例路由（10%流量 → v2服务）
                            
                ### Nacos 配置存储
                - Key: `config.gateway.route-{routeId}`
                - 索引: `config.gateway.metadata.routes-index`
                - Namespace: 使用网关实例对应的 Nacos namespace
                            
                ### 常见配置示例
                            
                **基础路由配置**:
                ```json
                {
                  "routeName": "user-api",
                  "mode": "SINGLE",
                  "serviceId": "user-service",
                  "predicates": [{"name":"Path","args":{"pattern":"/api/users/**"}}],
                  "filters": [{"name":"StripPrefix","args":{"parts":"1"}}],
                  "order": 0,
                  "enabled": true
                }
                ```
                            
                **灰度发布路由**:
                ```json
                {
                  "routeName": "order-api-gray",
                  "mode": "MULTI",
                  "services": [
                    {"serviceId":"order-service-v1","weight":90,"version":"v1"},
                    {"serviceId":"order-service-v2","weight":10,"version":"v2"}
                  ],
                  "predicates": [{"name":"Path","args":{"pattern":"/api/orders/**"}}],
                  "grayRules": {
                    "enabled": true,
                    "rules": [{"type":"HEADER","key":"X-Version","value":"v2","targetVersion":"v2"}]
                  }
                }
                ```
                """);

        // ========== 服务配置详解 ==========
        prompts.put("service", """
                ## 服务配置详解（基于 ServiceService/ServiceDefinition）
                            
                ### 服务实体字段
                ```json
                {
                  "serviceId": "服务唯一标识",
                  "serviceName": "服务名称，如 user-service",
                  "instanceId": "网关实例ID",
                  "loadBalancer": "weighted | round-robin | random | consistent-hash",
                  "instances": "后端实例列表",
                  "enabled": "是否启用"
                }
                ```
                            
                ### 后端实例配置（ServiceInstance）
                ```json
                {
                  "ip": "192.168.1.100",
                  "port": 8080,
                  "weight": 100,
                  "enabled": true,
                  "metadata": {"version": "v1", "zone": "cn-east"}
                }
                ```
                字段说明：
                - `ip`: 服务实例IP地址
                - `port`: 服务端口
                - `weight`: 权重值（1-100），用于 weighted 负载均衡
                - `enabled`: 是否启用该实例
                - `metadata`: 元数据，可存储版本号、区域等信息
                            
                ### 负载均衡策略（LoadBalancer）
                | 策略 | 说明 | 适用场景 |
                |------|------|----------|
                | weighted | 权重轮询 | 需要流量分配控制，如灰度发布 |
                | round-robin | 简单轮询 | 实例性能相近 |
                | random | 随机选择 | 简单场景 |
                | consistent-hash | 一致性哈希 | 会话粘滞、缓存命中优化 |
                            
                ### 本地缓存机制
                ServiceService 使用 `ConcurrentHashMap<String, ServiceDefinition>` 本地缓存服务定义，
                提高查询性能，避免频繁访问数据库。
                            
                ### 实例健康状态管理
                - `addInstance()`: 添加新实例
                - `removeInstance()`: 移除实例
                - `updateInstanceStatus()`: 更新实例状态
                            
                ### Nacos 配置存储
                - Key: `config.gateway.service-{serviceId}`
                - 累引: `config.gateway.metadata.services-index`
                            
                ### 配置示例
                            
                **基础服务配置**:
                ```json
                {
                  "serviceName": "user-service",
                  "loadBalancer": "weighted",
                  "instances": [
                    {"ip":"192.168.1.1","port":8080,"weight":100,"enabled":true},
                    {"ip":"192.168.1.2","port":8080,"weight":100,"enabled":true}
                  ],
                  "enabled": true
                }
                ```
                            
                **一致性哈希服务**:
                ```json
                {
                  "serviceName": "session-service",
                  "loadBalancer": "consistent-hash",
                  "instances": [
                    {"ip":"10.0.1.1","port":8080,"weight":100,"enabled":true,"metadata":{"zone":"a"}}
                  ]
                }
                ```
                """);

        // ========== 策略配置详解 ==========
        prompts.put("strategy", """
                ## 策略配置详解（基于 StrategyService）
                            
                ### 策略类型枚举（StrategyType）
                | 类型 | 说明 | 核心配置字段 |
                |------|------|-------------|
                | RATE_LIMITER | 限流 | qps, burstCapacity, keyType(ip/user/route) |
                | MULTI_DIM_RATE_LIMITER | 多维限流 | globalQuota, tenantQuota, userQuota, ipQuota |
                | CIRCUIT_BREAKER | 熔断 | failureRateThreshold(50%), slidingWindowSize(100), waitDurationInOpenState |
                | TIMEOUT | 超时控制 | timeoutMs(3000), connectTimeoutMs(500) |
                | RETRY | 重试策略 | maxRetries(3), retryIntervalMs(100), retryOnStatusCodes |
                | CORS | 跨域配置 | allowedOrigins, allowedMethods, allowedHeaders, maxAge |
                | IP_FILTER | IP黑白名单 | allowList, denyList |
                | CACHE | 响应缓存 | cacheTtlSeconds, cacheKeyPattern |
                | SECURITY | 安全策略 | sqlInjectionFilter, xssFilter |
                | MOCK_RESPONSE | Mock响应 | mockMode(STATIC/DYNAMIC/TEMPLATE), mockData |
                            
                ### 策略作用域（StrategyScope）
                - **GLOBAL**: 全局策略，作用于所有路由，优先级最低
                - **ROUTE**: 路由绑定策略，作用于特定路由，优先级高于GLOBAL
                            
                ### 策略优先级
                系统按 priority 字段排序执行策略，数字越小优先级越高：
                1. IP_FILTER（安全拦截）
                2. AUTH（认证）
                3. RATE_LIMITER（限流）
                4. CIRCUIT_BREAKER（熔断）
                5. TIMEOUT（超时）
                6. RETRY（重试）
                7. CACHE（缓存）
                            
                ### 限流配置详解（RATE_LIMITER）
                ```json
                {
                  "strategyType": "RATE_LIMITER",
                  "scope": "ROUTE",
                  "routeId": "user-service-route",
                  "priority": 10,
                  "config": {
                    "qps": 100,
                    "burstCapacity": 200,
                    "keyType": "ip",
                    "keyResolver": "ip"
                  }
                }
                ```
                - `qps`: 每秒允许的请求数
                - `burstCapacity`: 突发容量，应对短时流量峰值
                - `keyType`: 限流维度 - ip（按IP）、user（按用户）、route（按路由）
                            
                ### 多维限流配置（MULTI_DIM_RATE_LIMITER）
                ```json
                {
                  "strategyType": "MULTI_DIM_RATE_LIMITER",
                  "config": {
                    "globalQuota": 10000,
                    "tenantQuota": {"tenant-1": 5000, "tenant-2": 3000},
                    "userQuota": {"user-001": 100, "default": 50},
                    "ipQuota": {"192.168.1.100": 200, "default": 100}
                  }
                }
                ```
                            
                ### 熔断配置详解（CIRCUIT_BREAKER）
                ```json
                {
                  "strategyType": "CIRCUIT_BREAKER",
                  "config": {
                    "failureRateThreshold": 50,
                    "slidingWindowSize": 100,
                    "minimumNumberOfCalls": 10,
                    "waitDurationInOpenState": "30s",
                    "slowCallRateThreshold": 80,
                    "slowCallDurationThreshold": "2s"
                  }
                }
                ```
                - `failureRateThreshold`: 失败率阈值（%），超过则触发熔断
                - `slidingWindowSize`: 滑动窗口大小（请求次数）
                - `waitDurationInOpenState`: 熔断开启后等待恢复时间
                            
                ### Nacos 配置存储
                - Key: `config.gateway.strategy-{strategyId}`
                - 累引: `config.gateway.metadata.strategies-index`
                            
                ### 配置建议
                - 限流阈值应根据后端容量设置，建议先压测确定后端最大QPS
                - 熔断阈值建议 failureRateThreshold=50%，避免过于敏感
                - 超时设置应大于后端平均响应时间+网络延迟
                """);

        // ========== 认证配置详解 ==========
        prompts.put("auth", """
                ## 认证配置详解（基于 AuthPolicyService）
                            
                ### 认证类型枚举（AuthType）
                | 类型 | 说明 | 核心配置 |
                |------|------|----------|
                | JWT | JWT Token验证 | secretKey, jwtIssuer, jwtAlgorithm, jwtClockSkewSeconds |
                | API_KEY | API密钥验证 | apiKeyHeader(X-API-Key), apiKeys映射表 |
                | OAUTH2 | OAuth2认证 | clientId, clientSecret, tokenEndpoint, scope |
                | BASIC | Basic认证 | basicUsers用户密码表, passwordHashAlgorithm |
                | HMAC | HMAC签名验证 | accessKeySecrets密钥对, signatureAlgorithm |
                            
                ### 认证策略实体（AuthPolicyEntity）
                ```json
                {
                  "policyId": "uuid",
                  "policyName": "jwt-auth-policy",
                  "authType": "JWT",
                  "instanceId": "网关实例ID",
                  "priority": 10,
                  "enabled": true,
                  "config": "具体认证配置JSON"
                }
                ```
                            
                ### JWT 认证配置示例
                ```json
                {
                  "authType": "JWT",
                  "config": {
                    "secretKey": "your-secret-key-min-256-bits",
                    "jwtIssuer": "gateway-auth",
                    "jwtAlgorithm": "HS256",
                    "jwtClockSkewSeconds": 60,
                    "jwtHeader": "Authorization",
                    "jwtPrefix": "Bearer "
                  }
                }
                ```
                - `secretKey`: JWT签名密钥，HS256需要至少256位
                - `jwtAlgorithm`: 支持 HS256(HMAC)、RS256(RSA)
                - `jwtClockSkewSeconds`: 时间偏差容忍秒数
                            
                ### API_KEY 认证配置示例
                ```json
                {
                  "authType": "API_KEY",
                  "config": {
                    "apiKeyHeader": "X-API-Key",
                    "apiKeys": {
                      "key-001": {"tenantId": "tenant-1", "permissions": ["read", "write"]},
                      "key-002": {"tenantId": "tenant-2", "permissions": ["read"]}
                    }
                  }
                }
                ```
                            
                ### HMAC 签名认证配置示例
                ```json
                {
                  "authType": "HMAC",
                  "config": {
                    "signatureAlgorithm": "HMAC-SHA256",
                    "accessKeySecrets": {
                      "access-key-001": {"secretKey": "secret-001", "permissions": ["all"]}
                    },
                    "signatureHeaders": ["X-Timestamp", "X-Nonce"],
                    "signatureValiditySeconds": 300
                  }
                }
                ```
                            
                ### 路由-认证绑定关系
                认证策略需要绑定到路由才能生效：
                - 绑定 Key: `config.gateway.auth-routes-{policyId}`
                - 存储内容: 绑定的路由ID列表
                - 一个路由可绑定多个认证策略，按 priority 排序执行
                            
                ### Nacos 配置存储
                - Policy Key: `config.gateway.auth-policy-{policyId}`
                - Routes Key: `config.gateway.auth-routes-{policyId}`
                            
                ### 安全建议
                - JWT密钥不要硬编码，建议从环境变量或密钥管理服务获取
                - API Key应定期轮换
                - HMAC签名时间戳校验防止重放攻击
                """);

        // ========== 监控配置详解 ==========
        prompts.put("monitor", """
                ## 监控与诊断详解（基于 PrometheusService/DiagnosticService）
                            
                ### Prometheus 指标采集
                            
                **指标获取方式**:
                - PrometheusService 从 Prometheus API 查询指标
                - 备用方案：直接从 Gateway `/actuator/prometheus` 端点获取
                - 地址配置: `gateway.prometheus.url=http://localhost:9091`
                            
                **核心指标查询语句**:
                ```promql
                # JVM堆内存使用
                sum(jvm_memory_used_bytes{application="my-gateway",area="heap"})
                            
                # JVM堆内存最大值
                sum(jvm_memory_max_bytes{application="my-gateway",area="heap"})
                            
                # CPU使用率
                system_cpu_usage{application="my-gateway"}
                            
                # HTTP请求速率（QPS）
                sum(rate(http_server_requests_seconds_count{application="my-gateway"}[1m]))
                            
                # 平均响应时间
                sum(rate(http_server_requests_seconds_sum{application="my-gateway"}[1m])) 
                / sum(rate(http_server_requests_seconds_count{application="my-gateway"}[1m]))
                            
                # 错误率（5xx）
                sum(rate(http_server_requests_seconds_count{application="my-gateway",status=~"5.."}[1m])) 
                / sum(rate(http_server_requests_seconds_count{application="my-gateway"}[1m])) * 100
                            
                # GC次数和时间
                sum(increase(jvm_gc_pause_seconds_count{application="my-gateway"}[5m]))
                sum(increase(jvm_gc_pause_seconds_sum{application="my-gateway"}[5m]))
                            
                # 网关路由数量
                spring_cloud_gateway_routes_count{application="my-gateway"}
                ```
                            
                **指标数据结构** (getGatewayMetrics 返回):
                ```json
                {
                  "instances": [{"instance":"xxx","status":"UP"}],
                  "jvmMemory": {"heapUsed":512,"heapMax":1024,"heapUsagePercent":50.0},
                  "cpu": {"systemUsage":45.5,"processUsage":30.2,"availableProcessors":4},
                  "threads": {"liveThreads":50,"daemonThreads":45,"peakThreads":60},
                  "gc": {"gcCount":10,"gcTimeSeconds":0.5,"gcOverheadPercent":1.0},
                  "httpRequests": {"requestsPerSecond":100,"avgResponseTimeMs":50,"errorRate":0.1},
                  "httpStatus": {"status2xx":99.5,"status4xx":0.4,"status5xx":0.1},
                  "process": {"uptimeSeconds":3600,"uptimeFormatted":"1h 0m 0s"},
                  "disk": {"freeGB":50,"totalGB":100,"usedPercent":50.0},
                  "gateway": {"routeCount":10}
                }
                ```
                            
                **历史指标查询** (getHistoryMetrics):
                - 支持查询最近 N 小时的历史数据
                - 用于绘制趋势图表
                - 返回时间序列数据点
                            
                ### 一键诊断服务（DiagnosticService）
                            
                **诊断组件**:
                | 组件 | 检查内容 | 健康状态判定 |
                |------|----------|-------------|
                | Database | 连接池状态、查询响应 | connectionLatency<100ms=HEALTHY |
                | Redis | Ping响应、版本信息 | pingLatency<50ms=HEALTHY |
                | ConfigCenter(Nacos) | 可用性、配置读取 | checkLatency<100ms=HEALTHY |
                | Routes | 路由数量、认证绑定 | 无孤儿绑定=HEALTHY |
                | Auth | 策略数量、绑定数量 | 有策略有绑定=HEALTHY |
                | GatewayInstances | 实例健康数 | 全部健康=HEALTHY |
                | Performance | JVM内存、线程数 | 内存利用率<70%=HEALTHY |
                            
                **健康评分计算** (0-100分):
                - Database CRITICAL: -30分
                - ConfigCenter CRITICAL: -25分
                - Redis CRITICAL: -15分
                - Routes/Auth/GatewayInstances CRITICAL: 各-10分
                            
                **诊断报告结构** (DiagnosticReport):
                ```json
                {
                  "overallScore": 85,
                  "status": "HEALTHY",
                  "database": {"status":"HEALTHY","metrics":{"poolUtilization":"30%"}},
                  "configCenter": {"status":"HEALTHY","metrics":{"available":true}},
                  "redis": {"status":"NOT_CONFIGURED"},
                  "recommendations": ["建议配置Redis以支持分布式限流"]
                }
                ```
                            
                **诊断API**:
                - 全量诊断: `runFullDiagnostic()` - 包含所有组件
                - 快速诊断: `runQuickDiagnostic()` - 仅数据库/Redis/Nacos
                            
                ### 智能告警降噪（SmartAlertService）
                            
                **告警处理流程**:
                1. 去重：同一指纹在5分钟内不重复告警
                2. 速率限制：每种告警类型每分钟最多N条
                3. 分组：WARNING/INFO级别告警合并发送
                4. 抑制：维护期间可抑制非关键告警
                            
                **告警指纹**: `{instanceId}:{alertType}:{metricName}`
                            
                **速率限制配置**:
                | 告警类型 | 每分钟限制 |
                |----------|-----------|
                | CPU/MEMORY | 3 |
                | HTTP_ERROR | 10 |
                | INSTANCE | 2 |
                            
                ### 常用监控端点
                ```bash
                # Gateway健康检查
                curl http://gateway:8080/actuator/health
                            
                # Prometheus指标
                curl http://gateway:8080/actuator/prometheus
                            
                # 路由列表
                curl http://gateway:8080/actuator/gateway/routes
                ```
                """);

        // ========== 实例管理详解 ==========
        prompts.put("instance", """
                ## 网关实例管理详解（基于 GatewayInstanceService）
                            
                ### 实例状态枚举（InstanceStatus）
                | 状态码 | 状态 | 说明 |
                |--------|------|------|
                | 0 | STARTING | 启动中，等待心跳 |
                | 1 | RUNNING | 正常运行，心跳正常 |
                | 2 | ERROR | 异常状态，部署失败或心跳丢失 |
                | 3 | STOPPING | 停止中，正在缩容 |
                | 4 | STOPPED | 已停止，副本数为0 |
                            
                ### 实例实体字段（GatewayInstanceEntity）
                ```json
                {
                  "instanceId": "12位随机ID，如 o0m1rhg5abcd",
                  "instanceName": "生产网关-华东",
                  "clusterId": "K8s集群ID",
                  "clusterName": "prod-cluster",
                  "namespace": "K8s namespace",
                  "nacosNamespace": "Nacos namespace（用于配置隔离）",
                  "deploymentName": "gateway-o0m1rhg5",
                  "serviceName": "gateway-o0m1rhg5-service",
                  "specType": "small | medium | large | xlarge | custom",
                  "cpuCores": 2,
                  "memoryMB": 4096,
                  "replicas": 2,
                  "image": "my-gateway:latest",
                  "serverPort": 9090,
                  "managementPort": 9091,
                  "nodePort": 30080,
                  "nodeIp": "192.168.1.100",
                  "statusCode": 1,
                  "status": "Running"
                }
                ```
                            
                ### 规格类型（InstanceSpec）
                | 规格 | CPU | 内存 | 适用场景 |
                |------|-----|------|----------|
                | small | 1核 | 2GB | 开发测试环境 |
                | medium | 2核 | 4GB | 小规模生产 |
                | large | 4核 | 8GB | 中规模生产，1000 QPS |
                | xlarge | 8核 | 16GB | 大规模生产，5000+ QPS |
                | custom | 自定义 | 自定义 | 特殊需求场景 |
                            
                ### Kubernetes 部署流程
                            
                **创建实例** (createInstance):
                1. 生成 instanceId（12位随机ID）
                2. 创建 Nacos namespace（用于配置隔离）
                3. 创建 K8s Deployment（容器部署）
                4. 创建 K8s Service（NodePort类型）
                5. 等待心跳更新状态为 RUNNING
                            
                **Deployment 配置**:
                - 环境变量注入：
                  - `GATEWAY_INSTANCE_ID`: 实例ID
                  - `NACOS_SERVER_ADDR`: Nacos地址
                  - `NACOS_NAMESPACE`: 配置namespace
                  - `GATEWAY_ADMIN_URL`: Admin服务地址
                  - `REDIS_HOST/REDIS_PORT`: Redis地址（可选）
                  - `SERVER_PORT/MANAGEMENT_SERVER_PORT`: 端口配置
                            
                - 健康检查：
                  - Liveness: `/actuator/health/liveness`（management端口）
                  - Readiness: `/actuator/health/readiness`（management端口）
                            
                - 资源限制：
                  - Limits: CPU/内存按规格设置
                  - Requests: Limits的50%
                            
                **Service 配置**:
                - 类型: NodePort
                - 端口映射: serverPort → NodePort
                            
                ### 心跳机制
                网关实例定时向 Admin 发送心跳：
                - 心跳间隔: 建议30秒
                - 心跳内容: instanceId, metrics, accessUrl
                - 状态更新: STARTING → RUNNING（收到心跳）
                - 心跳丢失: 超过阈值后标记为 ERROR
                            
                ### 实例访问地址优先级（getEffectiveAccessUrl）
                1. manualAccessUrl: 手动配置地址（如SLB域名）
                2. discoveredAccessUrl: K8s发现地址（LoadBalancer IP）
                3. reportedAccessUrl: 心跳上报地址
                4. nodeIp:nodePort: 默认地址
                            
                ### 实例生命周期管理
                            
                **启动实例** (startInstance):
                - 从 STOPPED 状态启动
                - 设置副本数并等待心跳
                            
                **停止实例** (stopInstance):
                - 从 RUNNING 状态停止
                - 设置副本数为0
                            
                **更新副本数** (updateReplicas):
                - 仅 RUNNING 状态可操作
                - 支持1-10副本
                            
                **更新规格** (updateSpec):
                - 支持 CPU/内存调整
                - 触发 Pod 重启
                            
                **更新镜像** (updateImage):
                - 支持镜像版本升级
                - 多副本时滚动更新
                            
                **删除实例** (deleteInstance):
                - 删除数据库相关数据
                - 清理 Nacos namespace 所有配置
                - 删除 K8s Deployment/Service
                            
                ### 配置隔离机制
                每个网关实例使用独立的 Nacos namespace：
                - namespace 名称 = deploymentName（如 gateway-o0m1rhg5）
                - 所有路由/服务/策略配置存储在该 namespace
                - 实例删除时自动清理 namespace
                """);

        // ========== 告警管理详解 ==========
        prompts.put("alert", """
                ## 告警管理详解（基于 SmartAlertService）
                            
                ### 智能告警降噪功能
                            
                **核心特性**:
                1. **告警去重**: 防止同一问题重复告警
                2. **告警分组**: 合并相关告警批量发送
                3. **速率限制**: 控制告警发送频率
                4. **告警抑制**: 维护期间抑制非关键告警
                            
                ### 告警处理流程
                            
                ```
                processAlert(instanceId, alertType, level, metricName, ...)
                ↓
                生成指纹 fingerprint = instanceId:alertType:metricName
                ↓
                检查抑制规则 → 被抑制则不发送
                ↓
                检查速率限制 → 超限则不发送
                ↓
                检查去重窗口 → 5分钟内已发送则不发送
                ↓
                CRITICAL/ERROR → 立即发送
                WARNING/INFO → 加入告警组等待批量发送
                ```
                            
                ### 告警指纹（Fingerprint）
                格式: `{instanceId}:{alertType}:{metricName}`
                示例: `gateway-001:CPU:cpuUsagePercent`
                            
                用于识别相同问题的重复告警。
                            
                ### 速率限制配置
                            
                | 告警类型 | 每分钟限制 | 说明 |
                |----------|-----------|------|
                | CPU | 3 | CPU使用率告警 |
                | MEMORY | 3 | 内存告警 |
                | HTTP_ERROR | 10 | HTTP错误告警 |
                | RESPONSE_TIME | 5 | 响应时间告警 |
                | INSTANCE | 2 | 实例状态告警 |
                | THREAD | 3 | 线程数告警 |
                            
                默认限制: 5条/分钟
                            
                ### 告警分组机制
                            
                - 分组窗口: 10分钟
                - 批量发送间隔: 5分钟
                - 仅分组 WARNING 和 INFO 级别
                - CRITICAL/ERROR 立即发送不分组
                            
                **分组发送内容**:
                ```
                [CPU] 告警摘要 - 最近10分钟共3条告警
                实例: gateway-001
                告警类型: CPU
                告警列表:
                - CPU使用率超过80% (x2)
                - CPU使用率超过90% (x1)
                ```
                            
                ### 告警抑制规则
                            
                **添加抑制**:
                ```
                addSuppressionRule(key, durationMinutes, reason)
                            
                // 示例：抑制所有CPU告警30分钟
                addSuppressionRule("CPU", 30, "进行性能调优")
                ```
                            
                **抑制范围**:
                - `*`: 全局抑制（维护窗口）
                - `{alertType}`: 按类型抑制
                - `{fingerprint}`: 按具体问题抑制
                            
                ### 告警级别（AlertLevel）
                | 级别 | 说明 | 处理方式 |
                |------|------|----------|
                | CRITICAL | 严重告警 | 立即发送，不去重不分组 |
                | ERROR | 错误告警 | 立即发送，不去重 |
                | WARNING | 警告 | 可分组，可去重 |
                | INFO | 信息通知 | 可分组，可去重 |
                            
                ### 告警统计（getStats）
                ```json
                {
                  "totalFingerprints": 50,
                  "activeSuppressions": 2,
                  "pendingGroups": 3,
                  "alertsByType": {"CPU": 10, "MEMORY": 8},
                  "alertsByLevel": {"WARNING": 15, "ERROR": 3}
                }
                ```
                            
                ### 最佳实践
                - 生产环境建议配置多种通知渠道（邮件、钉钉）
                - 设置合理的阈值避免告警疲劳
                - 维护窗口期间使用抑制规则
                - 定期审查告警统计优化配置
                """);

        // ========== 问题诊断详解 ==========
        prompts.put("debug", """
                ## 问题诊断指南（基于 DiagnosticService）
                            
                ### 常见错误及解决方案
                            
                | 状态码 | 常见原因 | 诊断步骤 |
                |--------|----------|----------|
                | 404 | 路由未匹配 | 1. 检查 predicates 配置<br>2. 确认 Path 匹配规则<br>3. 检查路由 enabled 状态 |
                | 502 | 后端服务不可用 | 1. 检查服务实例是否健康<br>2. 确认 IP:Port 是否正确<br>3. 测试网关到后端网络连通性 |
                | 503 | 服务实例全部下线 | 1. 检查服务 enabled 状态<br>2. 确认实例 enabled 字段<br>3. 检查服务实例列表 |
                | 504 | 后端响应超时 | 1. 检查 TIMEOUT 策略配置<br>2. 查看后端服务性能<br>3. 调整 timeoutMs 参数 |
                | 429 | 触发限流 | 1. 检查 RATE_LIMITER 策略<br>2. 查看 qps/burstCapacity 配置<br>3. 检查是否需要调整阈值 |
                | 401/403 | 认证失败 | 1. 检查 Auth 策略绑定<br>2. 确认 JWT/API Key 配置<br>3. 检查请求是否携带认证信息 |
                            
                ### 一键诊断流程
                            
                **全量诊断** (runFullDiagnostic):
                ```
                1. Database 检查
                   - 连接测试（获取连接耗时）
                   - 连接池状态（active/idle/total）
                   - 简单查询测试
                            
                2. Redis 检查
                   - Ping 响应测试
                   - 版本信息获取
                   - 内存使用
                            
                3. ConfigCenter(Nacos) 检查
                   - 可用性测试
                   - 配置读取测试
                   - 响应延迟
                            
                4. Routes 检查
                   - 路由总数统计
                   - 检查无认证绑定的路由
                   - 检查孤儿绑定
                            
                5. Auth 检查
                   - 认证策略数量
                   - 认证绑定数量
                            
                6. GatewayInstances 检查
                   - 实例健康状态
                   - 心跳状态
                            
                7. Performance 检查
                   - JVM 内存利用率
                   - 线程数量
                            
                → 计算整体健康评分（0-100）
                → 生成优化建议
                ```
                            
                **快速诊断** (runQuickDiagnostic):
                仅检查 Database、Redis、ConfigCenter
                            
                ### 健康评分计算
                            
                基础分: 100分
                            
                | 组件 | CRITICAL扣分 | WARNING扣分 |
                |------|-------------|-------------|
                | Database | -30 | -10 |
                | ConfigCenter | -25 | -8 |
                | Redis | -15 | -5 |
                | Routes | -10 | -5 |
                | Auth | -10 | -5 |
                | GatewayInstances | -15 | -5 |
                            
                评分解读:
                - 80-100: HEALTHY（健康）
                - 50-79: WARNING（需关注）
                - 0-49: CRITICAL（需立即处理）
                            
                ### 诊断报告解读
                            
                ```json
                {
                  "overallScore": 85,
                  "status": "HEALTHY",
                  "duration": "150ms",
                  "database": {
                    "status": "HEALTHY",
                    "metrics": {
                      "connectionLatency": "5ms",
                      "poolActive": 10,
                      "poolIdle": 20,
                      "poolUtilization": "33%"
                    }
                  },
                  "redis": {
                    "status": "NOT_CONFIGURED",
                    "metrics": {"configured": false}
                  },
                  "configCenter": {
                    "status": "HEALTHY",
                    "metrics": {
                      "available": true,
                      "checkLatency": "10ms",
                      "serverAddr": "localhost:8848"
                    }
                  },
                  "recommendations": [
                    "Redis未配置，建议启用以支持分布式限流"
                  ]
                }
                ```
                            
                ### 常用诊断命令
                            
                ```bash
                # 查看Gateway路由列表
                curl http://gateway:8080/actuator/gateway/routes
                            
                # 查看Gateway健康状态
                curl http://gateway:8080/actuator/health
                            
                # 查看Prometheus指标
                curl http://gateway:8080/actuator/prometheus
                            
                # 查看环境变量（确认配置）
                curl http://gateway:9091/actuator/env
                            
                # 查看日志配置
                curl http://gateway:9091/actuator/loggers
                ```
                            
                ### 日志排查建议
                            
                Gateway 日志位置:
                - 启动日志: 查看路由加载是否成功
                - 请求日志: 查看 HTTP 请求处理过程
                - 错误日志: 查看异常堆栈
                            
                关键日志关键词:
                - `RouteDefinition`: 路由定义加载
                - `ServiceInstance`: 服务实例选择
                - `Filter`: 过滤器执行
                - `RateLimiter`: 限流触发
                - `CircuitBreaker`: 熔断状态变化
                """);

        // ========== 性能优化详解 ==========
        prompts.put("performance", """
                ## 性能优化指南
                            
                ### 优化方向
                            
                #### 1. 路由配置优化
                            
                **减少 predicate 复杂度**:
                - 避免过于复杂的正则表达式
                - 高频路由设置较小的 order 值（优先匹配）
                - 减少 filter 链长度
                            
                **路由匹配顺序**:
                ```
                order: 0  → 最高优先级（高频API）
                order: 10 → 中等优先级
                order: 100 → 低优先级（兜底路由）
                ```
                            
                #### 2. 连接池调优
                            
                **HTTP Client 配置** (application.yml):
                ```yaml
                spring:
                  cloud:
                    gateway:
                      httpclient:
                        connect-timeout: 3000
                        response-timeout: 30s
                        pool:
                          type: elastic
                          max-connections: 1000
                          acquire-timeout: 10000
                          max-idle-time: 15000
                ```
                            
                参数说明:
                - `max-connections`: 最大连接数，建议根据并发量设置
                - `acquire-timeout`: 获取连接超时时间
                - `max-idle-time`: 连接空闲超时
                            
                #### 3. 限流阈值调整
                            
                **限流策略建议**:
                - `qps`: 设置为后端服务容量的80%
                - `burstCapacity`: 设置为 qps 的2-3倍，应对突发流量
                - 分布式限流需要配置 Redis
                            
                **限流维度选择**:
                | 场景 | 建议 keyType |
                |------|-------------|
                | API开放平台 | user（按用户） |
                | 公网接入 | ip（按IP防滥用） |
                | 内部服务 | route（按路由） |
                            
                #### 4. 熔断策略配置
                            
                ```json
                {
                  "strategyType": "CIRCUIT_BREAKER",
                  "config": {
                    "failureRateThreshold": 50,
                    "slidingWindowSize": 100,
                    "minimumNumberOfCalls": 10,
                    "waitDurationInOpenState": "30s",
                    "slowCallRateThreshold": 80,
                    "slowCallDurationThreshold": "2s"
                  }
                }
                ```
                            
                配置建议:
                - `failureRateThreshold`: 50%失败率触发熔断
                - `minimumNumberOfCalls`: 至少10次请求才计算失败率
                - `waitDurationInOpenState`: 熔断后等待30秒尝试恢复
                            
                #### 5. JVM 参数调优
                            
                **推荐 JVM 参数**:
                ```bash
                # 内存配置（根据规格）
                -Xms2g -Xmx2g          # medium规格
                -Xms4g -Xmx4g          # large规格
                -Xms8g -Xmx8g          # xlarge规格
                            
                # GC配置（G1垃圾收集器）
                -XX:+UseG1GC
                -XX:MaxGCPauseMillis=200
                -XX:G1HeapRegionSize=16m
                            
                # GC日志（便于排查）
                -Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=10m
                ```
                            
                #### 6. 响应缓存策略
                            
                对于热点数据，启用 CACHE 策略:
                ```json
                {
                  "strategyType": "CACHE",
                  "config": {
                    "cacheTtlSeconds": 60,
                    "cacheKeyPattern": "path+query",
                    "cacheSize": 1000
                  }
                }
                ```
                            
                ### 性能指标监控
                            
                **关键指标及告警阈值建议**:
                | 指标 | 计算方式 | 建议阈值 | 说明 |
                |------|----------|----------|------|
                | cpuUsagePercent | process_cpu_usage | > 80% | CPU告警 |
                | heapUsagePercent | heapUsed/heapMax | > 80% | 内存告警 |
                | requestsPerSecond | rate(http_requests_count[1m]) | 根据容量 | QPS监控 |
                | avgResponseTimeMs | rate(sum)/rate(count) | > 500ms | 响应时间告警 |
                | errorRatePercent | 5xx/total | > 1% | 错误率告警 |
                | gcOverheadPercent | gcTime/totalTime | > 10% | GC开销告警 |
                            
                ### 性能测试建议
                            
                **压测工具**:
                - JMeter: 模拟高并发请求
                - wrk: 轻量级HTTP压测
                            
                **压测步骤**:
                1. 单接口压测确定基线性能
                2. 混合场景压测模拟真实流量
                3. 长时间压测检查稳定性
                4. 根据压测结果调整限流阈值
                            
                **压测参数建议**:
                - 并发数: 从100开始逐步增加
                - 持续时间: 至少10分钟观察稳定性
                - 监控: 压测期间监控 CPU/内存/响应时间
                """);

        return prompts;
    }

    /**
     * 英文领域提示词
     */
    private static final Map<String, String> DOMAIN_PROMPTS_EN = createDomainPromptsEN();

    private static Map<String, String> createDomainPromptsEN() {
        Map<String, String> prompts = new LinkedHashMap<>();
        // 英文提示词结构同中文，内容翻译
        prompts.put("route", """
                ## Route Configuration Details
                            
                ### Route Entity Fields
                - routeId: UUID format, auto-generated
                - routeName: Route name, e.g., user-service-route
                - mode: SINGLE | MULTI
                - serviceId: ServiceId for single mode
                - services: Array for multi-service mode (gray release)
                - predicates: Assertion conditions array
                - filters: Filter array
                - grayRules: Gray release rules
                - order: Route priority (lower = higher priority)
                - enabled: Whether enabled
                            
                ### Predicate Types
                | Type | Description | Example |
                |------|-------------|---------|
                | Path | Path matching | {"name":"Path","args":{"pattern":"/api/users/**"}} |
                | Method | HTTP method | {"name":"Method","args":{"methods":"GET,POST"}} |
                | Header | Header matching | {"name":"Header","args":{"header":"X-Request-Id"}} |
                | Query | Query param | {"name":"Query","args":{"param":"userId"}} |
                | Host | Hostname | {"name":"Host","args":{"pattern":"**.example.com"}} |
                            
                ### Gray Release Rules
                - HEADER: Route by header value
                - COOKIE: Route by cookie
                - QUERY: Route by query param
                - WEIGHT: Route by percentage (10% → v2 service)
                            
                ### Nacos Config Key
                - Route: config.gateway.route-{routeId}
                - Index: config.gateway.metadata.routes-index
                """);

        prompts.put("service", """
                ## Service Configuration Details
                            
                ### Service Fields
                - serviceId: Unique identifier
                - serviceName: Service name
                - loadBalancer: weighted | round-robin | random | consistent-hash
                - instances: Backend instance list
                            
                ### Instance Fields
                - ip: Instance IP address
                - port: Service port
                - weight: 1-100, for weighted load balancing
                - enabled: Whether instance is enabled
                - metadata: version, zone, etc.
                            
                ### Load Balancing Strategies
                | Strategy | Description | Use Case |
                |----------|-------------|----------|
                | weighted | Weighted round-robin | Traffic distribution, gray release |
                | round-robin | Simple round-robin | Similar instance performance |
                | random | Random selection | Simple scenarios |
                | consistent-hash | Consistent hashing | Session sticky, cache optimization |
                """);

        prompts.put("strategy", """
                ## Strategy Configuration Details
                            
                ### Strategy Types
                | Type | Description | Key Config |
                |------|-------------|------------|
                | RATE_LIMITER | Rate limiting | qps, burstCapacity, keyType |
                | MULTI_DIM_RATE_LIMITER | Multi-dimensional limiting | globalQuota, tenantQuota, userQuota |
                | CIRCUIT_BREAKER | Circuit breaking | failureRateThreshold, slidingWindowSize |
                | TIMEOUT | Timeout control | timeoutMs, connectTimeoutMs |
                | RETRY | Retry policy | maxRetries, retryIntervalMs |
                | CORS | CORS config | allowedOrigins, allowedMethods |
                | IP_FILTER | IP whitelist/blacklist | allowList, denyList |
                | CACHE | Response caching | cacheTtlSeconds |
                            
                ### Strategy Scope
                - GLOBAL: Global strategy, applies to all routes
                - ROUTE: Route-bound strategy, applies to specific route
                            
                ### Execution Priority
                1. IP_FILTER (security)
                2. AUTH (authentication)
                3. RATE_LIMITER
                4. CIRCUIT_BREAKER
                5. TIMEOUT
                6. RETRY
                7. CACHE
                """);

        prompts.put("auth", """
                ## Authentication Configuration
                            
                ### Auth Types
                | Type | Description | Key Config |
                |------|-------------|------------|
                | JWT | JWT Token | secretKey, jwtIssuer, jwtAlgorithm |
                | API_KEY | API Key | apiKeyHeader, apiKeys |
                | OAUTH2 | OAuth2 | clientId, clientSecret, tokenEndpoint |
                | BASIC | Basic Auth | basicUsers, passwordHashAlgorithm |
                | HMAC | HMAC Signature | accessKeySecrets, signatureAlgorithm |
                            
                ### Route Binding
                Auth policies must be bound to routes to take effect.
                Binding Key: config.gateway.auth-routes-{policyId}
                            
                ### Security Best Practices
                - Don't hardcode JWT secret keys
                - Rotate API keys periodically
                - Use HMAC timestamp validation to prevent replay attacks
                """);

        prompts.put("monitor", """
                ## Monitoring & Diagnostics
                            
                ### Prometheus Metrics
                            
                **Key PromQL Queries**:
                ```promql
                # JVM heap memory
                sum(jvm_memory_used_bytes{application="my-gateway",area="heap"})
                            
                # CPU usage
                system_cpu_usage{application="my-gateway"}
                            
                # Request rate (QPS)
                sum(rate(http_server_requests_seconds_count{application="my-gateway"}[1m]))
                            
                # Error rate (5xx)
                sum(rate(http_server_requests_seconds_count{application="my-gateway",status=~"5.."}[1m]))
                / sum(rate(http_server_requests_seconds_count{application="my-gateway"}[1m]))
                ```
                            
                ### Diagnostic Service
                            
                **Health Score Calculation**:
                - Base: 100 points
                - Database CRITICAL: -30
                - ConfigCenter CRITICAL: -25
                - Redis CRITICAL: -15
                            
                **Score Interpretation**:
                - 80-100: HEALTHY
                - 50-79: WARNING
                - 0-49: CRITICAL
                            
                ### Smart Alert Noise Reduction
                            
                **Features**:
                - Deduplication: Same fingerprint within 5 min
                - Rate limiting: Max alerts per type per minute
                - Grouping: WARNING/INFO alerts batched
                - Suppression: Maintenance window silencing
                """);

        prompts.put("instance", """
                ## Gateway Instance Management
                            
                ### Instance Status
                | Code | Status | Description |
                |------|--------|-------------|
                | 0 | STARTING | Waiting for heartbeat |
                | 1 | RUNNING | Normal, heartbeat OK |
                | 2 | ERROR | Failed or heartbeat lost |
                | 3 | STOPPING | Scaling down |
                | 4 | STOPPED | Stopped, replicas=0 |
                            
                ### Spec Types
                | Spec | CPU | Memory | Use Case |
                |------|-----|--------|----------|
                | small | 1 | 2GB | Dev/Test |
                | medium | 2 | 4GB | Small production |
                | large | 4 | 8GB | 1000 QPS |
                | xlarge | 8 | 16GB | 5000+ QPS |
                            
                ### Kubernetes Deployment
                            
                **Environment Variables**:
                - GATEWAY_INSTANCE_ID
                - NACOS_SERVER_ADDR
                - NACOS_NAMESPACE
                - GATEWAY_ADMIN_URL
                            
                **Health Probes**:
                - Liveness: /actuator/health/liveness
                - Readiness: /actuator/health/readiness
                            
                ### Access URL Priority
                1. manualAccessUrl (SLB/domain)
                2. discoveredAccessUrl (LoadBalancer IP)
                3. reportedAccessUrl (heartbeat)
                4. nodeIp:nodePort (default)
                """);

        prompts.put("alert", """
                ## Alert Management
                            
                ### Smart Alert Noise Reduction
                            
                **Alert Processing Flow**:
                1. Generate fingerprint: instanceId:alertType:metricName
                2. Check suppression rules
                3. Check rate limits
                4. Check dedup window (5 min)
                5. CRITICAL/ERROR → send immediately
                6. WARNING/INFO → group and batch
                            
                **Rate Limits**:
                | Type | Limit/min |
                |------|-----------|
                | CPU | 3 |
                | MEMORY | 3 |
                | HTTP_ERROR | 10 |
                | INSTANCE | 2 |
                            
                **Alert Levels**:
                - CRITICAL: Send immediately, no dedup/group
                - ERROR: Send immediately
                - WARNING: Can group and dedup
                - INFO: Can group and dedup
                """);

        prompts.put("debug", """
                ## Troubleshooting Guide
                            
                ### Common Errors
                            
                | Status | Cause | Solution |
                |--------|-------|----------|
                | 404 | Route not matched | Check predicates config |
                | 502 | Backend unavailable | Check service health, IP:Port |
                | 503 | All instances down | Check enabled status |
                | 504 | Backend timeout | Adjust timeout config |
                | 429 | Rate limit | Adjust RateLimiter config |
                | 401/403 | Auth failed | Check auth policy binding |
                            
                ### Diagnostic Commands
                            
                ```bash
                # Gateway routes
                curl http://gateway:8080/actuator/gateway/routes
                            
                # Health check
                curl http://gateway:8080/actuator/health
                            
                # Prometheus metrics
                curl http://gateway:8080/actuator/prometheus
                ```
                            
                ### Health Score
                - Database CRITICAL: -30 points
                - ConfigCenter CRITICAL: -25 points
                - Redis CRITICAL: -15 points
                """);

        prompts.put("performance", """
                ## Performance Optimization
                            
                ### HTTP Client Pool
                            
                ```yaml
                spring:
                  cloud:
                    gateway:
                      httpclient:
                        pool:
                          max-connections: 1000
                          acquire-timeout: 10000
                ```
                            
                ### Rate Limiting
                            
                - Set qps to 80% of backend capacity
                - burstCapacity = 2-3x qps
                            
                ### Circuit Breaker
                            
                ```json
                {
                  "failureRateThreshold": 50,
                  "slidingWindowSize": 100,
                  "waitDurationInOpenState": "30s"
                }
                ```
                            
                ### JVM Tuning
                            
                ```bash
                -Xms4g -Xmx4g
                -XX:+UseG1GC
                -XX:MaxGCPauseMillis=200
                ```
                            
                ### Alert Thresholds
                            
                | Metric | Threshold |
                |--------|-----------|
                | cpuUsagePercent | > 80% |
                | heapUsagePercent | > 80% |
                | avgResponseTimeMs | > 500ms |
                | errorRatePercent | > 1% |
                """);

        return prompts;
    }

    // ===================== 智能意图识别系统 =====================

    // 否定词列表（被否定时关键词权重清零）
    private static final List<String> NEGATION_WORDS = List.of(
            "不想", "不要", "别", "不是", "不需要", "不用", "没想", "不想要",
            "how to not", "don't", "not", "no", "without", "avoid", "prevent"
    );

    /**
     * 关键词权重配置
     */
    public static class KeywordWeight {
        public final String keyword;
        public final String intent;
        public final int weight;

        public KeywordWeight(String keyword, String intent, int weight) {
            this.keyword = keyword;
            this.intent = intent;
            this.weight = weight;
        }
    }

    /**
     * 组合规则配置
     */
    public static class ComboRule {
        public final List<String> keywords;
        public final String intent;
        public final int bonusScore;

        public ComboRule(List<String> keywords, String intent, int bonusScore) {
            this.keywords = keywords;
            this.intent = intent;
            this.bonusScore = bonusScore;
        }
    }

    /**
     * 意图识别结果
     */
    public static class IntentResult {
        public final String intent;
        public final int score;

        public IntentResult(String intent, int score) {
            this.intent = intent;
            this.score = score;
        }

        public boolean isHighConfidence() {
            return score >= 10;
        }

        public boolean needsAiRefinement() {
            return score < 5;
        }
    }

    // 加权关键词列表
    private static final List<KeywordWeight> KEYWORD_WEIGHTS = new ArrayList<>();

    static {
        // === 高权重关键词（权重=10）===
        // 策略相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("限流", "strategy", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("熔断", "strategy", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("rate limit", "strategy", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("circuit breaker", "strategy", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("限速", "strategy", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("rate limiter", "strategy", 10));

        // 调试相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("404", "debug", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("500", "debug", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("502", "debug", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("503", "debug", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("504", "debug", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("报错", "debug", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("异常", "debug", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("故障", "debug", 10));

        // 监控相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("prometheus", "monitor", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("普罗米修斯", "monitor", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("监控", "monitor", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("告警", "alert", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("alert", "alert", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("指标", "monitor", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("metrics", "monitor", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("诊断", "monitor", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("健康", "monitor", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("dashboard", "monitor", 10));

        // 认证相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("认证", "auth", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("auth", "auth", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("jwt", "auth", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("token", "auth", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("api key", "auth", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("apikey", "auth", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("oauth", "auth", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("鉴权", "auth", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("权限", "auth", 10));

        // 实例相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("网关实例", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("gateway instance", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("部署", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("deploy", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("k8s", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("kubernetes", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("pod", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("deployment", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("副本", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("replicas", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("心跳", "instance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("heartbeat", "instance", 10));

        // 性能相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("压测", "performance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("压力测试", "performance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("benchmark", "performance", 10));
        KEYWORD_WEIGHTS.add(new KeywordWeight("性能测试", "performance", 10));

        // === 中权重关键词（权重=5）===
        // 路由相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("路由", "route", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("route", "route", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("predicate", "route", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("filter", "route", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("过滤器", "route", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("断言", "route", 5));

        // 服务相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("服务", "service", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("service", "service", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("负载均衡", "service", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("load balancer", "service", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("负载", "service", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("后端", "service", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("backend", "service", 5));

        // 策略相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("超时", "strategy", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("timeout", "strategy", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("重试", "strategy", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("retry", "strategy", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("跨域", "strategy", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("cors", "strategy", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("ip黑名单", "strategy", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("ip白名单", "strategy", 5));

        // 调试相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("错误", "debug", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("error", "debug", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("失败", "debug", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("exception", "debug", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("调试", "debug", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("debug", "debug", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("排查", "debug", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("troubleshoot", "debug", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("无法访问", "debug", 5));

        // 性能相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("性能", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("慢", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("延迟", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("latency", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("吞吐量", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("throughput", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("优化", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("optimize", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("tuning", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("响应时间", "performance", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("response time", "performance", 5));

        // 监控相关
        KEYWORD_WEIGHTS.add(new KeywordWeight("cpu", "monitor", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("内存", "monitor", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("memory", "monitor", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("qps", "monitor", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("状态", "monitor", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("status", "monitor", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("日志", "monitor", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("log", "monitor", 5));

        // === 低权重关键词（权重=2）===
        KEYWORD_WEIGHTS.add(new KeywordWeight("转发", "route", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("代理", "route", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("路径", "route", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("path", "route", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("rewrite", "route", 2));

        KEYWORD_WEIGHTS.add(new KeywordWeight("实例", "service", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("instance", "service", 2));

        KEYWORD_WEIGHTS.add(new KeywordWeight("策略", "strategy", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("strategy", "strategy", 2));

        KEYWORD_WEIGHTS.add(new KeywordWeight("配置", "config", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("config", "config", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("设置", "config", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("怎么", "config", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("如何", "config", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("help", "config", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("帮助", "config", 2));

        KEYWORD_WEIGHTS.add(new KeywordWeight("卡", "performance", 2));
        KEYWORD_WEIGHTS.add(new KeywordWeight("slow", "performance", 2));

        // 灰度发布
        KEYWORD_WEIGHTS.add(new KeywordWeight("灰度", "route", 8));
        KEYWORD_WEIGHTS.add(new KeywordWeight("gray", "route", 8));
        KEYWORD_WEIGHTS.add(new KeywordWeight("蓝绿", "route", 8));
        KEYWORD_WEIGHTS.add(new KeywordWeight("blue-green", "route", 8));
        KEYWORD_WEIGHTS.add(new KeywordWeight("版本", "route", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("version", "route", 5));
        KEYWORD_WEIGHTS.add(new KeywordWeight("金丝雀", "route", 8));
        KEYWORD_WEIGHTS.add(new KeywordWeight("canary", "route", 8));
    }

    // 组合规则列表
    private static final List<ComboRule> COMBO_RULES = new ArrayList<>();

    static {
        // 路由 + 服务 → 路由配置场景
        COMBO_RULES.add(new ComboRule(List.of("路由", "服务"), "route", 15));
        COMBO_RULES.add(new ComboRule(List.of("route", "service"), "route", 15));

        // 路由 + 限流/熔断 → 策略绑定
        COMBO_RULES.add(new ComboRule(List.of("路由", "限流"), "strategy", 20));
        COMBO_RULES.add(new ComboRule(List.of("路由", "熔断"), "strategy", 20));
        COMBO_RULES.add(new ComboRule(List.of("route", "rate limit"), "strategy", 20));

        // 报错 + 路由 → 调试
        COMBO_RULES.add(new ComboRule(List.of("报错", "路由"), "debug", 25));
        COMBO_RULES.add(new ComboRule(List.of("错误", "路由"), "debug", 25));
        COMBO_RULES.add(new ComboRule(List.of("404", "路由"), "debug", 25));
        COMBO_RULES.add(new ComboRule(List.of("error", "route"), "debug", 25));

        // 性能 + 路由/服务 → 性能优化
        COMBO_RULES.add(new ComboRule(List.of("性能", "路由"), "performance", 20));
        COMBO_RULES.add(new ComboRule(List.of("慢", "路由"), "performance", 20));
        COMBO_RULES.add(new ComboRule(List.of("性能", "服务"), "performance", 20));
        COMBO_RULES.add(new ComboRule(List.of("慢", "服务"), "performance", 20));
        COMBO_RULES.add(new ComboRule(List.of("performance", "route"), "performance", 20));

        // 压测相关
        COMBO_RULES.add(new ComboRule(List.of("压测", "分析"), "performance", 25));
        COMBO_RULES.add(new ComboRule(List.of("压力测试", "结果"), "performance", 25));
        COMBO_RULES.add(new ComboRule(List.of("压测", "报告"), "performance", 25));

        // 告警配置
        COMBO_RULES.add(new ComboRule(List.of("告警", "配置"), "alert", 20));
        COMBO_RULES.add(new ComboRule(List.of("告警", "阈值"), "alert", 20));
        COMBO_RULES.add(new ComboRule(List.of("alert", "config"), "alert", 20));

        // 认证绑定
        COMBO_RULES.add(new ComboRule(List.of("认证", "路由"), "auth", 20));
        COMBO_RULES.add(new ComboRule(List.of("jwt", "路由"), "auth", 20));
        COMBO_RULES.add(new ComboRule(List.of("auth", "route"), "auth", 20));

        // 实例管理
        COMBO_RULES.add(new ComboRule(List.of("实例", "创建"), "instance", 20));
        COMBO_RULES.add(new ComboRule(List.of("实例", "部署"), "instance", 20));
        COMBO_RULES.add(new ComboRule(List.of("实例", "状态"), "instance", 15));
        COMBO_RULES.add(new ComboRule(List.of("instance", "create"), "instance", 20));
        COMBO_RULES.add(new ComboRule(List.of("k8s", "部署"), "instance", 20));

        // 监控诊断
        COMBO_RULES.add(new ComboRule(List.of("监控", "面板"), "monitor", 15));
        COMBO_RULES.add(new ComboRule(List.of("prometheus", "配置"), "monitor", 20));
        COMBO_RULES.add(new ComboRule(List.of("普罗米修斯", "优化"), "monitor", 20));
        COMBO_RULES.add(new ComboRule(List.of("prometheus", "optimize"), "monitor", 20));
        COMBO_RULES.add(new ComboRule(List.of("诊断", "报告"), "monitor", 15));
    }

    /**
     * 智能意图识别（加权评分 + 组合匹配 + 否定词过滤）
     *
     * @param userMessage 用户消息
     * @return 识别结果：意图类型 + 置信度分数
     */
    public IntentResult detectIntent(String userMessage) {
        String lowerMessage = userMessage.toLowerCase();

        // 1. 计算各意图的基础得分（带否定词过滤）
        Map<String, Integer> intentScores = new HashMap<>();
        for (KeywordWeight kw : KEYWORD_WEIGHTS) {
            String lowerKw = kw.keyword.toLowerCase();
            int index = lowerMessage.indexOf(lowerKw);
            if (index >= 0) {
                // 检查关键词前是否有否定词（前10个字符内）
                boolean isNegated = isKeywordNegated(lowerMessage, index, lowerKw.length());
                if (!isNegated) {
                    intentScores.merge(kw.intent, kw.weight, Integer::sum);
                } else {
                    log.debug("Keyword '{}' is negated, skipping", kw.keyword);
                }
            }
        }

        // 2. 应用组合规则加分（检查整体是否被否定）
        for (ComboRule rule : COMBO_RULES) {
            boolean allMatch = rule.keywords.stream()
                    .allMatch(kw -> lowerMessage.contains(kw.toLowerCase()));
            if (allMatch && !isMessageNegated(lowerMessage)) {
                intentScores.merge(rule.intent, rule.bonusScore, Integer::sum);
                log.debug("Combo rule matched: {} -> {} (+{})", rule.keywords, rule.intent, rule.bonusScore);
            }
        }

        // 3. 找出得分最高的意图
        if (intentScores.isEmpty()) {
            return new IntentResult("general", 0);
        }

        String bestIntent = intentScores.entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("general");

        int bestScore = intentScores.getOrDefault(bestIntent, 0);

        log.info("Intent detection: intent={}, score={}, allScores={}",
                bestIntent, bestScore, intentScores);

        return new IntentResult(bestIntent, bestScore);
    }

    /**
     * 检查关键词是否被否定词修饰
     * 在关键词前10个字符内检查是否存在否定词
     */
    private boolean isKeywordNegated(String message, int keywordIndex, int keywordLength) {
        // 检查关键词前面的内容（最多10个字符）
        int start = Math.max(0, keywordIndex - 10);
        String prefix = message.substring(start, keywordIndex);

        for (String negation : NEGATION_WORDS) {
            if (prefix.contains(negation.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查整个消息是否表达否定意图
     */
    private boolean isMessageNegated(String message) {
        for (String negation : NEGATION_WORDS) {
            if (message.contains(negation.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建动态分层系统提示词
     * 基础提示词 + 按意图加载的领域详细提示词
     *
     * @param language 语言（zh/en）
     * @param context  意图/上下文
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(String language, String context) {
        String basePrompt = "zh".equals(language) ? BASE_PROMPT_ZH : BASE_PROMPT_EN;
        StringBuilder sb = new StringBuilder(basePrompt);

        // 根据意图动态加载领域详细提示词
        Map<String, String> domainPrompts = "zh".equals(language) ? DOMAIN_PROMPTS_ZH : DOMAIN_PROMPTS_EN;
        if (context != null && domainPrompts.containsKey(context)) {
            sb.append("\n\n").append(domainPrompts.get(context));
            log.debug("Loaded domain prompt for intent: {}", context);
        }

        return sb.toString();
    }

    /**
     * 获取意图对应的 AI 提炼提示词
     * （仅在低置信度时使用）
     */
    public String getIntentRefinementPrompt(String userMessage, String language) {
        if ("zh".equals(language)) {
            return String.format("""
                    分析用户问题，识别意图类型。只输出一个意图类型：
                                        
                    用户问题：%s
                                        
                    可选意图类型：
                    - route: 路由配置相关
                    - service: 服务配置相关
                    - strategy: 策略配置（限流/熔断/超时）相关
                    - auth: 认证配置（JWT/API Key/OAuth2）相关
                    - monitor: 监控/指标/Prometheus/Diagnostic相关
                    - alert: 告警配置相关
                    - instance: 网关实例/部署/K8s相关
                    - debug: 调试问题/错误排查
                    - performance: 性能优化相关
                    - config: 一般配置咨询
                                        
                    只输出一个意图类型词，不要解释。
                    """, userMessage);
        } else {
            return String.format("""
                    Analyze the user question and identify the intent type. Output only one intent type:
                                        
                    User question: %s
                                        
                    Available intent types:
                    - route: Route configuration
                    - service: Service configuration
                    - strategy: Strategy (rate limiting/circuit breaker/timeout)
                    - auth: Authentication (JWT/API Key/OAuth2)
                    - monitor: Monitoring/metrics/Prometheus/Diagnostic
                    - alert: Alert configuration
                    - instance: Gateway instance/deployment/K8s
                    - debug: Debugging/error troubleshooting
                    - performance: Performance optimization
                    - config: General configuration help
                                        
                    Output only one intent type word, no explanation.
                    """, userMessage);
        }
    }

    /**
     * 验证意图是否有效
     */
    public boolean isValidIntent(String intent) {
        return DOMAIN_PROMPTS_ZH.containsKey(intent) || "general".equals(intent) || "config".equals(intent);
    }

    /**
     * 获取所有支持的意图类型
     */
    public List<String> getSupportedIntents() {
        return new ArrayList<>(DOMAIN_PROMPTS_ZH.keySet());
    }

    /**
     * 获取关键词权重列表（用于调试/展示）
     */
    public List<KeywordWeight> getKeywordWeights() {
        return new ArrayList<>(KEYWORD_WEIGHTS);
    }

    /**
     * 获取组合规则列表（用于调试/展示）
     */
    public List<ComboRule> getComboRules() {
        return new ArrayList<>(COMBO_RULES);
    }
}