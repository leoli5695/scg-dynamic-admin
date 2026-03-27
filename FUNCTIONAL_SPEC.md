# Gateway Admin - 企业级API网关管理平台功能规格文档

> 版本：1.0  
> 更新时间：2026-03-24  
> 作者：leoli

---

## 一、系统架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端 (React + Ant Design)                 │
│                     http://localhost:3000                       │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    管理控制台 (Gateway Admin)                     │
│                     http://localhost:9090                       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │ 路由管理  │ │ 服务管理  │ │ 策略管理  │ │ 监控告警  │           │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │ SSL证书  │ │ 追踪管理  │ │ AI配置   │ │ 审计日志  │           │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘           │
└─────────────────────────────────────────────────────────────────┘
                                  │
                    ┌─────────────┼─────────────┐
                    ▼             ▼             ▼
            ┌───────────┐  ┌───────────┐  ┌───────────┐
            │   Nacos   │  │   Redis   │  │   MySQL   │
            │  :8848    │  │   :6379   │  │  :3306    │
            └───────────┘  └───────────┘  └───────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                      API网关 (Gateway Core)                      │
│                     http://localhost:80                          │
│                  https://localhost:8443                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │ 认证过滤器 │ │ 限流过滤器 │ │ 熔断过滤器 │ │ 追踪过滤器 │           │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │ IP过滤器  │ │ 超时过滤器 │ │ 缓存过滤器 │ │ 日志过滤器 │           │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘           │
└─────────────────────────────────────────────────────────────────┘
                                  │
                    ┌─────────────┼─────────────┐
                    ▼             ▼             ▼
            ┌───────────┐  ┌───────────┐  ┌───────────┐
            │ Service A │  │ Service B │  │ Service C │
            │ 后端服务   │  │ 后端服务   │  │ 后端服务   │
            └───────────┘  └───────────┘  └───────────┘
```

---

## 二、核心模块功能详解

### 2.1 路由管理 (Route Management)

#### 2.1.1 路由基础配置

| 配置项 | 类型 | 说明 | 示例 |
|--------|------|------|------|
| routeId | String | 路由唯一标识 | `route-001` |
| routeName | String | 路由名称 | `用户服务路由` |
| uri | String | 目标服务URI | `lb://user-service` |
| predicates | List | 路由断言条件 | `Path=/api/user/**` |
| filters | List | 路由过滤器 | `StripPrefix=1` |
| order | Integer | 路由优先级 | `0` |
| enabled | Boolean | 是否启用 | `true` |

#### 2.1.2 支持的路由断言 (Predicates)

| 断言类型 | 说明 | 示例 |
|----------|------|------|
| Path | 路径匹配 | `/api/user/**` |
| Method | HTTP方法 | `GET,POST` |
| Header | 请求头匹配 | `X-Request-Id=\d+` |
| Query | 查询参数 | `token=abc` |
| Cookie | Cookie匹配 | `sessionid=xxx` |
| After | 时间之后 | `2024-01-01T00:00:00` |
| Before | 时间之前 | `2024-12-31T23:59:59` |
| Between | 时间区间 | `2024-01-01...2024-12-31` |
| RemoteAddr | 远程地址 | `192.168.1.1/24` |
| Weight | 权重分发 | `group1,80` |
| Host | 主机名匹配 | `**.example.com` |

#### 2.1.3 支持的路由过滤器 (Filters)

| 过滤器类型 | 说明 | 参数 |
|------------|------|------|
| StripPrefix | 去除路径前缀 | `parts=1` |
| AddRequestHeader | 添加请求头 | `X-Response-Foo=Bar` |
| AddResponseHeader | 添加响应头 | `X-Response-Foo=Bar` |
| RemoveRequestHeader | 移除请求头 | `X-Request-Foo` |
| RemoveResponseHeader | 移除响应头 | `X-Response-Foo` |
| SetPath | 设置路径 | `/api/v1/user` |
| RewritePath | 重写路径 | `/api/(?<segment>.*)=/$\{segment}` |
| AddRequestParameter | 添加请求参数 | `foo=bar` |
| Retry | 重试 | `retries=3,status=500` |
| RequestRateLimiter | 请求限流 | `replenishRate=10,burstCapacity=20` |
| Hystrix | 熔断 | `name=fallbackCmd` |
| PrefixPath | 添加路径前缀 | `/api` |
| SetStatus | 设置响应状态 | `404` |
| SetRequestHeader | 设置请求头 | `X-Request-Foo=Bar` |
| SetResponseHeader | 设置响应头 | `X-Response-Foo=Bar` |

#### 2.1.4 路由管理功能

- ✅ 创建路由
- ✅ 编辑路由
- ✅ 删除路由
- ✅ 启用/禁用路由
- ✅ 复制路由
- ✅ 路由排序
- ✅ 路由测试（模拟请求）
- ✅ 批量导入/导出
- ✅ 路由分组管理
- ✅ 路由绑定服务

#### 2.1.5 路由与服务绑定

```
路由可以绑定多个后端服务实例：
- 绑定后可实现负载均衡
- 支持权重分配
- 支持灰度发布
```

---

### 2.2 服务管理 (Service Management)

#### 2.2.1 服务定义

| 配置项 | 类型 | 说明 |
|--------|------|------|
| serviceId | String | 服务唯一标识 |
| serviceName | String | 服务名称 |
| serviceType | Enum | 服务类型（STATIC/NACOS/CONSUL） |
| instances | List | 服务实例列表 |
| enabled | Boolean | 是否启用 |

#### 2.2.2 服务实例配置

| 配置项 | 类型 | 说明 |
|--------|------|------|
| instanceId | String | 实例ID |
| host | String | 主机地址 |
| port | Integer | 端口号 |
| weight | Integer | 权重（1-100） |
| metadata | Map | 元数据 |
| healthStatus | Enum | 健康状态（UP/DOWN/UNKNOWN） |

#### 2.2.3 服务发现支持

| 类型 | 说明 | 配置中心 |
|------|------|----------|
| STATIC | 静态服务 | 手动配置实例 |
| NACOS | Nacos服务发现 | Nacos Server |
| CONSUL | Consul服务发现 | Consul Server |

#### 2.2.4 服务管理功能

- ✅ 创建服务
- ✅ 编辑服务
- ✅ 删除服务
- ✅ 启用/禁用服务
- ✅ 添加/删除实例
- ✅ 实例权重调整
- ✅ 实例健康状态查看
- ✅ 服务与路由关联

#### 2.2.5 健康检查

| 检查类型 | 说明 | 参数 |
|----------|------|------|
| HTTP | HTTP健康检查 | `path=/health,interval=30s` |
| TCP | TCP端口检查 | `port=8080,interval=30s` |
| PASSIVE | 被动检查 | 基于请求响应判断 |

---

### 2.3 策略管理 (Strategy Management)

策略是网关的核心功能，支持全局策略和路由级策略。

#### 2.3.1 策略类型总览

| 策略类型 | 说明 | 作用域 |
|----------|------|--------|
| RATE_LIMITER | 限流策略 | GLOBAL/ROUTE |
| CIRCUIT_BREAKER | 熔断策略 | GLOBAL/ROUTE |
| AUTH | 认证策略 | GLOBAL/ROUTE |
| IP_FILTER | IP过滤策略 | GLOBAL/ROUTE |
| TIMEOUT | 超时策略 | GLOBAL/ROUTE |
| CACHE | 缓存策略 | ROUTE |
| RETRY | 重试策略 | ROUTE |
| HEADER_OP | 请求头操作 | GLOBAL/ROUTE |
| GRAY | 灰度发布策略 | ROUTE |

#### 2.3.2 限流策略 (RATE_LIMITER)

```yaml
策略配置:
  qps: 100                    # 每秒请求数
  burstCapacity: 200          # 突发容量
  timeUnit: SECOND            # 时间单位（SECOND/MINUTE/HOUR）
  keyResolver: IP             # 限流Key解析器
  keyType: COMBINED           # Key类型
```

**限流Key解析器类型：**

| 类型 | 说明 | Key格式 |
|------|------|---------|
| IP | 基于IP限流 | `rate_limit:ip:{ip}` |
| USER | 基于用户限流 | `rate_limit:user:{userId}` |
| HEADER | 基于请求头限流 | `rate_limit:header:{value}` |
| GLOBAL | 全局限流 | `rate_limit:global` |
| COMBINED | 组合限流 | `rate_limit:combined:{routeId}:{ip}` |

**限流实现架构：**

```
┌─────────────────────────────────────────────┐
│           HybridRateLimiterFilter           │
├─────────────────────────────────────────────┤
│                                             │
│   Redis可用？ ──YES──► Redis分布式限流       │
│       │                                     │
│       NO                                    │
│       │                                     │
│       ▼                                     │
│   Shadow Quota管理器                        │
│       │                                     │
│       ▼                                     │
│   本地限流（令牌桶算法）                      │
│                                             │
│   Redis恢复时：渐进式流量切换（10%/秒）       │
└─────────────────────────────────────────────┘
```

**Shadow Quota机制（核心亮点）：**
- 定期记录全局QPS快照
- 监控集群节点数
- 预计算本地配额 = globalQPS / nodeCount
- Redis故障时平滑降级
- Redis恢复时渐进式切换

#### 2.3.3 熔断策略 (CIRCUIT_BREAKER)

```yaml
策略配置:
  failureRateThreshold: 50    # 失败率阈值（%）
  slowCallRateThreshold: 80   # 慢调用率阈值（%）
  slowCallDurationThreshold: 2s   # 慢调用时间阈值
  minimumNumberOfCalls: 10    # 最小调用次数
  waitDurationInOpenState: 10s   # 熔断等待时间
  slidingWindowSize: 100      # 滑动窗口大小
  slidingWindowType: COUNT_BASED  # 窗口类型
```

**熔断状态机：**

```
CLOSED ──(失败率>阈值)──► OPEN ──(等待时间到)──► HALF_OPEN
   ▲                                                  │
   │                                                  │
   └────────────(成功)────────────────────────────────┘
   │                                                  │
   └────────────(失败)────────────────────────────► OPEN
```

#### 2.3.4 认证策略 (AUTH)

**支持的认证方式：**

| 类型 | 说明 | 参数 |
|------|------|------|
| API_KEY | API密钥认证 | `apiKey, headerName` |
| BASIC | Basic认证 | `username, password` |
| JWT | JWT令牌认证 | `secret, issuer, audience` |
| HMAC | HMAC签名认证 | `accessKey, secretKey, algorithm` |
| OAUTH2 | OAuth2认证 | `clientId, clientSecret, tokenUrl` |

**认证流程：**

```
请求 ──► 认证过滤器 ──► 匹配认证策略 ──► 调用认证处理器
                                              │
                      ┌───────────────────────┼───────────────────────┐
                      ▼                       ▼                       ▼
               API_KEY处理器            JWT处理器              HMAC处理器
                      │                       │                       │
                      └───────────────────────┼───────────────────────┘
                                              ▼
                                      认证结果（通过/拒绝）
```

#### 2.3.5 IP过滤策略 (IP_FILTER)

```yaml
策略配置:
  mode: WHITELIST            # 模式（WHITELIST/BLACKLIST）
  ipList:                    # IP列表
    - 192.168.1.0/24
    - 10.0.0.1
    - 172.16.0.0/16
```

**支持格式：**
- 单个IP：`192.168.1.1`
- IP范围：`192.168.1.1-192.168.1.100`
- CIDR：`192.168.1.0/24`

#### 2.3.6 超时策略 (TIMEOUT)

```yaml
策略配置:
  connectTimeout: 5000       # 连接超时（ms）
  responseTimeout: 30000     # 响应超时（ms）
```

#### 2.3.7 缓存策略 (CACHE)

```yaml
策略配置:
  enabled: true
  ttl: 300                   # 缓存时间（秒）
  cacheKey: PATH_QUERY       # 缓存Key类型
  methods:                   # 缓存的HTTP方法
    - GET
  excludePaths:              # 排除路径
    - /api/auth/**
```

#### 2.3.8 重试策略 (RETRY)

```yaml
策略配置:
  retries: 3                 # 重试次数
  statuses:                  # 触发重试的状态码
    - 500
    - 502
    - 503
  methods:                   # 触发重试的方法
    - GET
  backoff:                   # 退避策略
    firstBackoff: 100ms
    maxBackoff: 500ms
    factor: 2
```

#### 2.3.9 请求头操作策略 (HEADER_OP)

```yaml
策略配置:
  operations:
    - type: ADD              # 操作类型（ADD/SET/REMOVE）
      header: X-Request-Id
      value: "${uuid}"
    - type: SET
      header: X-Forwarded-For
      value: "${remoteAddr}"
    - type: REMOVE
      header: X-Internal-Header
```

#### 2.3.10 灰度发布策略 (GRAY)

```yaml
策略配置:
  rules:
    - name: rule1
      weight: 20             # 权重（%）
      conditions:            # 匹配条件
        - header: X-Version
          operator: EQ
          value: v2
    - name: rule2
      weight: 80
      conditions: []
```

#### 2.3.11 策略管理功能

- ✅ 创建策略
- ✅ 编辑策略
- ✅ 删除策略
- ✅ 启用/禁用策略
- ✅ 复制策略
- ✅ 策略绑定路由
- ✅ 全局策略配置
- ✅ 策略优先级调整

---

### 2.4 监控模块 (Monitoring)

#### 2.4.1 实时监控指标

| 指标分类 | 指标名称 | 说明 |
|----------|----------|------|
| **JVM** | heap_used | 堆内存使用量 |
| | heap_max | 最大堆内存 |
| | heap_usage_percent | 堆内存使用率 |
| | non_heap_used | 非堆内存使用量 |
| | thread_count | 线程数 |
| | daemon_thread_count | 守护线程数 |
| | gc_count | GC次数 |
| | gc_time | GC耗时 |
| **CPU** | process_cpu | 进程CPU使用率 |
| | system_cpu | 系统CPU使用率 |
| **HTTP** | total_requests | 总请求数 |
| | success_requests | 成功请求数 |
| | failed_requests | 失败请求数 |
| | error_rate | 错误率 |
| | avg_response_time | 平均响应时间 |
| | max_response_time | 最大响应时间 |
| | p95_response_time | P95响应时间 |
| | p99_response_time | P99响应时间 |
| **路由** | route_requests | 路由请求数 |
| | route_latency | 路由延迟 |
| | route_errors | 路由错误数 |

#### 2.4.2 历史趋势图表

- ✅ 内存使用趋势
- ✅ CPU使用趋势
- ✅ QPS趋势
- ✅ 响应时间趋势
- ✅ 错误率趋势
- ✅ GC趋势

#### 2.4.3 Prometheus集成

```
指标端点: http://gateway:8081/actuator/prometheus

支持的指标类型:
- Counter: 计数器
- Gauge: 仪表盘
- Histogram: 直方图
- Summary: 摘要
```

---

### 2.5 告警模块 (Alert)

#### 2.5.1 告警规则配置

| 规则类型 | 阈值配置 | 说明 |
|----------|----------|------|
| CPU告警 | processThreshold: 80% | 进程CPU使用率超限 |
| | systemThreshold: 90% | 系统CPU使用率超限 |
| 内存告警 | heapThreshold: 85% | 堆内存使用率超限 |
| HTTP告警 | errorRateThreshold: 5% | 错误率超限 |
| | responseTimeThreshold: 2000ms | 响应时间超限 |
| 实例告警 | unhealthyThreshold: 1 | 不健康实例数 |
| 线程告警 | activeThreshold: 90% | 线程使用率超限 |

#### 2.5.2 告警渠道

| 渠道 | 配置 | 说明 |
|------|------|------|
| 邮件 | SMTP配置 | 支持HTML格式邮件 |
| 钉钉 | Webhook | 钉钉机器人通知 |

#### 2.5.3 邮件配置

```yaml
SMTP配置:
  host: smtp.example.com
  port: 465
  username: alert@example.com
  password: xxxxxx
  useSsl: true
  useStartTls: true
  fromEmail: alert@example.com
  fromName: Gateway Alert
```

#### 2.5.4 告警内容

```
邮件内容包含:
- 告警类型和级别
- 当前指标值
- 阈值配置
- 告警时间
- AI智能分析（可选）
- 建议处理措施
```

#### 2.5.5 告警历史

- ✅ 告警记录查询
- ✅ 告警状态（已发送/发送失败）
- ✅ 清空历史记录
- ✅ 告警统计

---

### 2.6 AI智能分析模块

#### 2.6.1 支持的AI提供商

| 提供商 | API格式 | 模型 |
|--------|---------|------|
| Qwen | 阿里云API | qwen-turbo, qwen-plus |
| OpenAI | OpenAI兼容 | gpt-3.5-turbo, gpt-4 |
| DeepSeek | OpenAI兼容 | deepseek-chat |
| Kimi | OpenAI兼容 | moonshot-v1 |
| GLM | 智谱API | glm-4 |

#### 2.6.2 AI分析功能

- ✅ 实时指标分析
- ✅ 历史时段分析
- ✅ 异常原因诊断
- ✅ 性能优化建议
- ✅ 多语言支持（中文/英文）

#### 2.6.3 分析内容

```
AI分析报告包含:
1. 当前系统状态评估
2. 发现的问题列表
3. 问题原因分析
4. 性能瓶颈识别
5. 优化建议
6. GC行为分析
7. 线程状态分析
```

---

### 2.7 SSL证书管理

#### 2.7.1 证书类型

| 类型 | 格式 | 说明 |
|------|------|------|
| PEM | .pem/.crt | PEM格式证书 |
| PKCS12 | .p12/.pfx | PKCS12格式证书 |

#### 2.7.2 证书管理功能

- ✅ 上传证书
- ✅ 查看证书列表
- ✅ 启用/禁用证书
- ✅ 删除证书
- ✅ 证书详情查看
- ✅ 证书过期提醒

#### 2.7.3 动态SSL加载

```
HTTPS服务:
- 端口: 8443
- 动态加载证书（无需重启）
- 定时刷新（30秒）
- 多域名支持
```

#### 2.7.4 SNI支持

- 支持多域名证书
- 根据域名返回对应证书
- 未配置域名友好错误页面

---

### 2.8 请求追踪模块

#### 2.8.1 追踪类型

| 类型 | 说明 | 保留时间 |
|------|------|----------|
| ERROR | 错误请求追踪 | 7天 |
| SLOW | 慢请求追踪 | 3天 |
| ALL | 全量追踪（可选） | 1天 |

#### 2.8.2 追踪信息

```
追踪记录包含:
- traceId: 追踪ID
- routeId: 路由ID
- method: HTTP方法
- path: 请求路径
- queryString: 查询参数
- requestHeaders: 请求头
- requestBody: 请求体
- statusCode: 响应状态码
- targetInstance: 目标实例
- latencyMs: 延迟时间
- errorMessage: 错误信息
- clientIp: 客户端IP
- userAgent: 用户代理
- traceTime: 追踪时间
```

#### 2.8.3 追踪功能

- ✅ 错误请求追踪
- ✅ 慢请求追踪
- ✅ 追踪详情查看
- ✅ 请求重放
- ✅ 重放结果对比
- ✅ 清理旧记录

#### 2.8.4 请求重放

```
重放功能:
- 支持指定网关URL
- 保留原始请求头
- 添加重放标识
- 显示重放结果
- 记录重放次数
```

---

### 2.9 审计日志模块

#### 2.9.1 审计事件

| 事件类型 | 说明 |
|----------|------|
| CREATE | 创建操作 |
| UPDATE | 更新操作 |
| DELETE | 删除操作 |
| LOGIN | 登录操作 |
| LOGOUT | 登出操作 |

#### 2.9.2 审计记录

```
审计记录包含:
- 操作时间
- 操作用户
- 操作类型
- 操作模块
- 操作详情
- IP地址
- 操作结果
```

#### 2.9.3 审计功能

- ✅ 审计日志查询
- ✅ 按模块筛选
- ✅ 按时间范围筛选
- ✅ 按用户筛选
- ✅ 导出日志

---

### 2.10 系统管理

#### 2.10.1 用户管理

| 功能 | 说明 |
|------|------|
| 用户登录 | JWT令牌认证 |
| 用户登出 | 令牌失效 |
| 密码修改 | 修改密码 |
| 会话管理 | 查看活跃会话 |

#### 2.10.2 配置中心集成

| 配置中心 | 说明 |
|----------|------|
| Nacos | 阿里云配置中心 |
| Consul | HashiCorp配置中心 |

#### 2.10.3 配置热更新

- ✅ 路由配置热更新
- ✅ 服务配置热更新
- ✅ 策略配置热更新
- ✅ 无需重启网关

---

## 三、前端UI模块

### 3.1 页面列表

| 页面 | 路由 | 功能 |
|------|------|------|
| 登录页 | /login | 用户登录 |
| 路由管理 | /routes | 路由CRUD |
| 服务管理 | /services | 服务CRUD |
| 策略管理 | /strategies | 策略CRUD |
| 监控页面 | /monitor | 实时监控 |
| 告警配置 | /alerts | 告警配置 |
| 证书管理 | /certificates | SSL证书管理 |
| 追踪页面 | /traces | 请求追踪 |
| AI分析 | /monitor | AI智能分析弹窗 |

### 3.2 通用功能

- ✅ 国际化（中文/英文）
- ✅ 深色/浅色主题
- ✅ 响应式布局
- ✅ 左侧菜单固定
- ✅ 面包屑导航
- ✅ 全局搜索
- ✅ 操作确认对话框
- ✅ 加载状态提示
- ✅ 错误提示

### 3.3 表格功能

- ✅ 分页
- ✅ 排序
- ✅ 筛选
- ✅ 批量操作
- ✅ 行展开
- ✅ 自定义列

---

## 四、技术栈

### 4.1 后端技术

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行环境 |
| Spring Boot | 3.2.4 | 应用框架 |
| Spring Cloud Gateway | 4.x | 网关核心 |
| Spring Security | 6.x | 安全框架 |
| Spring Data JPA | 3.x | 数据访问 |
| Nacos Client | 2.x | 配置中心客户端 |
| Redis | - | 分布式缓存/限流 |
| MySQL | 8.x | 关系数据库 |
| Prometheus | - | 监控指标 |
| Reactor Netty | - | 响应式网络 |

### 4.2 前端技术

| 技术 | 版本 | 用途 |
|------|------|------|
| React | 18 | UI框架 |
| TypeScript | 5.x | 类型系统 |
| Ant Design | 5.x | UI组件库 |
| React Router | 6.x | 路由管理 |
| Axios | 1.x | HTTP客户端 |
| i18next | - | 国际化 |
| Recharts | - | 图表库 |

### 4.3 运维技术

| 技术 | 用途 |
|------|------|
| Docker | 容器化 |
| Nacos | 服务发现/配置中心 |
| Prometheus | 监控系统 |
| Grafana | 可视化面板 |
| Redis | 缓存/限流 |

---

## 五、API接口概览

### 5.1 路由API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/routes | 获取路由列表 |
| GET | /api/routes/{id} | 获取路由详情 |
| POST | /api/routes | 创建路由 |
| PUT | /api/routes/{id} | 更新路由 |
| DELETE | /api/routes/{id} | 删除路由 |
| POST | /api/routes/{id}/toggle | 启用/禁用路由 |
| POST | /api/routes/test | 测试路由 |

### 5.2 服务API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/services | 获取服务列表 |
| GET | /api/services/{id} | 获取服务详情 |
| POST | /api/services | 创建服务 |
| PUT | /api/services/{id} | 更新服务 |
| DELETE | /api/services/{id} | 删除服务 |
| GET | /api/services/{id}/instances | 获取服务实例 |
| POST | /api/services/{id}/instances | 添加服务实例 |

### 5.3 策略API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/strategies | 获取策略列表 |
| GET | /api/strategies/{id} | 获取策略详情 |
| POST | /api/strategies | 创建策略 |
| PUT | /api/strategies/{id} | 更新策略 |
| DELETE | /api/strategies/{id} | 删除策略 |
| POST | /api/strategies/{id}/bind | 绑定路由 |

### 5.4 监控API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/monitor/metrics | 获取实时指标 |
| GET | /api/monitor/history | 获取历史趋势 |
| GET | /api/monitor/routes | 获取路由统计 |

### 5.5 告警API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/alerts/config | 获取告警配置 |
| POST | /api/alerts/config | 保存告警配置 |
| GET | /api/alerts/history | 获取告警历史 |
| DELETE | /api/alerts/history | 清空告警历史 |
| POST | /api/alerts/test | 发送测试邮件 |

### 5.6 追踪API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/traces/stats | 获取追踪统计 |
| GET | /api/traces/errors | 获取错误追踪 |
| GET | /api/traces/slow | 获取慢请求追踪 |
| GET | /api/traces/{id} | 获取追踪详情 |
| POST | /api/traces/{id}/replay | 重放请求 |

### 5.7 SSL证书API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/ssl | 获取证书列表 |
| GET | /api/ssl/{id} | 获取证书详情 |
| POST | /api/ssl/pem | 上传PEM证书 |
| POST | /api/ssl/pkcs12 | 上传PKCS12证书 |
| DELETE | /api/ssl/{id} | 删除证书 |

### 5.8 AI分析API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/ai/config | 获取AI配置 |
| POST | /api/ai/config | 保存AI配置 |
| POST | /api/ai/analyze | 执行AI分析 |

---

## 六、部署架构

### 6.1 单机部署

```
┌─────────────────────────────────────────┐
│              单机部署架构                │
├─────────────────────────────────────────┤
│                                         │
│  ┌─────────────────────────────────┐   │
│  │        Docker Compose           │   │
│  │                                 │   │
│  │  ┌─────────┐  ┌─────────┐       │   │
│  │  │ Nacos   │  │ Redis   │       │   │
│  │  │ :8848   │  │ :6379   │       │   │
│  │  └─────────┘  └─────────┘       │   │
│  │                                 │   │
│  │  ┌─────────┐  ┌─────────┐       │   │
│  │  │ Gateway │  │ Admin   │       │   │
│  │  │ :80     │  │ :9090   │       │   │
│  │  └─────────┘  └─────────┘       │   │
│  │                                 │   │
│  │  ┌─────────┐  ┌─────────┐       │   │
│  │  │ MySQL   │  │Prometheus│      │   │
│  │  │ :3306   │  │ :9090   │       │   │
│  │  └─────────┘  └─────────┘       │   │
│  └─────────────────────────────────┘   │
│                                         │
└─────────────────────────────────────────┘
```

### 6.2 集群部署（规划中）

```
┌───────────────────────────────────────────────────────────────┐
│                       Kubernetes Cluster                       │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐│
│  │   Ingress       │  │   Ingress       │  │   Ingress       ││
│  │   Controller    │  │   Controller    │  │   Controller    ││
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘│
│           │                    │                    │         │
│           └────────────────────┼────────────────────┘         │
│                                │                              │
│                                ▼                              │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │                    Gateway Service                       │ │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐     │ │
│  │  │ Pod 1   │  │ Pod 2   │  │ Pod 3   │  │ Pod N   │     │ │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘     │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │                    Admin Service                         │ │
│  │  ┌─────────┐  ┌─────────┐                                │ │
│  │  │ Pod 1   │  │ Pod 2   │                                │ │
│  │  └─────────┘  └─────────┘                                │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │                    Stateful Services                     │ │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐                  │ │
│  │  │ Nacos   │  │ Redis   │  │ MySQL   │                  │ │
│  │  │ Cluster │  │ Cluster │  │ Primary │                  │ │
│  │  └─────────┘  └─────────┘  └─────────┘                  │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

---

## 七、后续迭代规划

### 7.1 短期优化（1-2周）

| 优先级 | 功能 | 说明 |
|--------|------|------|
| P0 | 单元测试 | 核心模块测试覆盖 |
| P0 | 集成测试 | API集成测试 |
| P1 | 文档完善 | API文档、部署文档 |
| P1 | 性能测试 | 压测报告 |
| P2 | Docker化 | Dockerfile、docker-compose |
| P2 | CI/CD | 自动化构建部署 |

### 7.2 中期优化（1-2月）

| 优先级 | 功能 | 说明 |
|--------|------|------|
| P1 | 安全加固 | 敏感信息加密、RBAC |
| P1 | 审计增强 | 完整审计日志 |
| P2 | 全链路追踪 | OpenTelemetry集成 |
| P2 | 性能优化 | 连接池、缓存优化 |
| P3 | 多租户 | 租户隔离支持 |

### 7.3 长期规划（3-6月）

| 优先级 | 功能 | 说明 |
|--------|------|------|
| P1 | 云原生改造 | K8s Operator |
| P1 | 实例管理 | 控制台管理网关实例 |
| P2 | 多集群支持 | 跨集群部署 |
| P2 | 商业化 | 许可证、计费 |
| P3 | 插件系统 | 自定义插件扩展 |

---

## 八、技术亮点

### 8.1 Shadow Quota限流降级

```
创新点：
- 首创Shadow Quota概念
- Redis故障时平滑降级到本地限流
- Redis恢复时渐进式流量切换
- 解决分布式限流单点故障问题
```

### 8.2 动态配置热更新

```
创新点：
- 基于Nacos的配置热更新
- 无需重启网关
- 秒级生效
- 配置版本管理
```

### 8.3 AI智能分析

```
创新点：
- 告警内容AI增强
- 多AI提供商支持
- 多语言输出
- 问题诊断建议
```

### 8.4 动态SSL证书

```
创新点：
- 动态加载证书
- 无需重启
- 多域名支持
- 友好错误页面
```

---

## 九、适用场景

| 场景 | 说明 |
|------|------|
| 中小企业API管理 | 完整的API网关解决方案 |
| 微服务架构 | 服务发现、负载均衡、熔断限流 |
| API安全 | 多种认证方式、IP过滤 |
| 运维监控 | 完整的监控告警体系 |
| 开发测试 | 请求追踪、重放调试 |

---

## 十、总结

Gateway Admin是一个功能完整的企业级API网关管理平台，具备：

- **完整的网关功能**：路由、认证、限流、熔断、监控
- **可视化管理后台**：React前端，操作友好
- **高可用设计**：Shadow Quota降级、动态配置
- **可扩展架构**：插件化设计、策略模式

适合作为中小企业API管理解决方案，也可作为微服务架构的基础设施组件。

---

*文档版本: 1.0*  
*最后更新: 2026-03-24*