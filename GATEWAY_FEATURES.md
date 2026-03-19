# Gateway 功能文档

## 一、项目架构

### 1.1 模块组成
| 模块 | 端口 | 职责 |
|------|------|------|
| `my-gateway` | 80 | 网关核心服务，处理请求路由、限流、认证等 |
| `gateway-admin` | 8080 | 管理后台，提供REST API管理路由、服务、策略配置 |

### 1.2 配置中心
- 使用 Nacos 作为配置中心和服务注册中心
- 所有配置通过 Nacos 动态下发，支持实时更新

---

## 二、路由管理

### 2.1 路由配置结构
```json
{
  "id": "route-uuid",
  "order": 0,
  "uri": "lb://service-name 或 static://service-id",
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ]
}
```

### 2.2 路由 API
| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/routes` | 获取所有路由 |
| GET | `/api/routes/{id}` | 获取单个路由 |
| POST | `/api/routes` | 创建路由 |
| PUT | `/api/routes/{id}` | 更新路由 |
| DELETE | `/api/routes/{id}` | 删除路由 |
| POST | `/api/routes/{id}/enable` | 启用路由 |
| POST | `/api/routes/{id}/disable` | 禁用路由 |

### 2.3 Nacos 配置存储
- 路由索引：`config.gateway.metadata.routes-index` (JSON数组，存储所有路由ID)
- 单个路由：`config.gateway.route-{routeId}` (路由详细配置)

---

## 三、服务发现

### 3.1 两种服务发现方式

#### 3.1.1 Nacos 服务发现 (`lb://`)
- 协议格式：`lb://{serviceName}`
- 服务名对应 Nacos 注册中心的服务
- 自动发现服务实例，支持动态扩缩容
- 示例：`lb://demo-service`

#### 3.1.2 固定节点服务发现 (`static://`)
- 协议格式：`static://{serviceId}`
- serviceId 是创建服务时返回的 UUID
- 实例通过管理后台手动配置
- 示例：`static://778d6a6a-cb11-4b31-a968-5ed7dfd5a38f`

### 3.2 服务管理 API
| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/services` | 获取所有服务（含实例健康状态） |
| GET | `/api/services/{name}` | 获取单个服务 |
| POST | `/api/services` | 创建服务 |
| PUT | `/api/services/{name}` | 更新服务 |
| DELETE | `/api/services/{name}` | 删除服务 |
| GET | `/api/services/{name}/usage` | 检查服务被哪些路由引用 |
| GET | `/api/services/nacos-discovery` | 获取 Nacos 已注册的服务名列表 |

### 3.3 服务配置结构
```json
{
  "name": "static-service",
  "loadBalancer": "round-robin",
  "instances": [
    {"ip": "127.0.0.1", "port": 9000, "weight": 1},
    {"ip": "127.0.0.1", "port": 9001, "weight": 1}
  ]
}
```

### 3.4 服务创建流程（固定节点）
```
1. POST /api/services 创建服务
   → 返回 serviceId (UUID)
   
2. POST /api/routes 创建路由
   → uri 使用 static://{serviceId}
```

### 3.5 Nacos 配置存储
- 服务索引：`config.gateway.metadata.services-index` (JSON数组，存储所有服务ID)
- 单个服务：`config.gateway.service-{serviceId}` (服务详细配置)

---

## 四、负载均衡

### 4.1 算法
- **加权轮询 (Weighted Round-Robin)**：Nginx 风格平滑加权轮询
- 支持实例权重配置

### 4.2 健康感知路由
- 多实例服务：自动跳过不健康实例
- 单实例服务：保留不健康实例，允许快速失败和自动恢复

---

## 五、健康检查

### 5.1 混合健康检查器 (HybridHealthChecker)

#### 被动健康检查
- 监听请求成功/失败事件
- 连续失败达到阈值标记为不健康
- 自动恢复机制

#### 主动健康检查
- TCP 端口探测
- 定时检测实例可用性
- 周期：30秒

### 5.2 实例健康状态存储
- 数据库表：`SERVICE_INSTANCES`
- 字段：serviceId, ip, port, healthStatus, lastCheckTime

---

## 六、插件策略

### 6.1 限流器 (Rate Limiter)
```json
{
  "routeId": "route-uuid",
  "qps": 100,
  "timeUnit": "second",
  "burstCapacity": 200,
  "keyResolver": "ip",
  "keyType": "combined",
  "enabled": true
}
```

| 参数 | 说明 |
|------|------|
| qps | 每秒/分钟/小时请求数限制 |
| timeUnit | 时间单位：second/minute/hour |
| burstCapacity | 突发容量 |
| keyResolver | 限流维度：ip/user/header/global |
| keyType | 键类型：route/ip/combined |

### 6.2 IP 过滤器
```json
{
  "routeId": "route-uuid",
  "mode": "blacklist",
  "ipList": ["192.168.1.100", "10.0.0.0/8"],
  "enabled": true
}
```

| 参数 | 说明 |
|------|------|
| mode | 模式：blacklist(黑名单) / whitelist(白名单) |
| ipList | IP列表，支持 CIDR 格式 |

### 6.3 超时配置
```json
{
  "routeId": "route-uuid",
  "connectTimeout": 5000,
  "responseTimeout": 30000,
  "enabled": true
}
```

| 参数 | 说明 |
|------|------|
| connectTimeout | TCP连接超时(毫秒) |
| responseTimeout | 响应超时(毫秒) |

### 6.4 熔断器 (Circuit Breaker)
```json
{
  "routeId": "route-uuid",
  "failureRateThreshold": 50.0,
  "slowCallDurationThreshold": 60000,
  "slowCallRateThreshold": 80.0,
  "waitDurationInOpenState": 30000,
  "slidingWindowSize": 10,
  "minimumNumberOfCalls": 5,
  "enabled": true
}
```

| 参数 | 说明 |
|------|------|
| failureRateThreshold | 失败率阈值(%) |
| slowCallDurationThreshold | 慢调用阈值(毫秒) |
| waitDurationInOpenState | 熔断开启后等待时间(毫秒) |
| slidingWindowSize | 滑动窗口大小 |
| minimumNumberOfCalls | 最小调用次数 |

### 6.5 认证配置
```json
{
  "routeId": "route-uuid",
  "authType": "JWT",
  "enabled": true,
  "secretKey": "your-secret-key"
}
```

| authType | 说明 | 必要参数 |
|----------|------|----------|
| JWT | JWT Token认证 | secretKey |
| API_KEY | API Key认证 | apiKey |
| OAUTH2 | OAuth2认证 | clientId, clientSecret, tokenEndpoint |

### 6.6 插件 API
| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/plugins` | 获取所有插件配置 |
| POST | `/api/plugins/batch` | 批量更新插件配置 |

#### 限流器 API
| 方法 | 路径 |
|------|------|
| GET | `/api/plugins/rate-limiters` |
| GET | `/api/plugins/rate-limiters/{routeId}` |
| POST | `/api/plugins/rate-limiters` |
| PUT | `/api/plugins/rate-limiters/{routeId}` |
| DELETE | `/api/plugins/rate-limiters/{routeId}` |

#### IP过滤器 API
| 方法 | 路径 |
|------|------|
| GET | `/api/plugins/ip-filters` |
| GET | `/api/plugins/ip-filters/{routeId}` |
| POST | `/api/plugins/ip-filters` |
| PUT | `/api/plugins/ip-filters/{routeId}` |
| DELETE | `/api/plugins/ip-filters/{routeId}` |

#### 超时配置 API
| 方法 | 路径 |
|------|------|
| GET | `/api/plugins/timeouts` |
| GET | `/api/plugins/timeouts/{routeId}` |
| POST | `/api/plugins/timeouts` |
| PUT | `/api/plugins/timeouts/{routeId}` |
| DELETE | `/api/plugins/timeouts/{routeId}` |

#### 熔断器 API
| 方法 | 路径 |
|------|------|
| GET | `/api/plugins/circuit-breakers` |
| GET | `/api/plugins/circuit-breakers/{routeId}` |
| POST | `/api/plugins/circuit-breakers` |
| PUT | `/api/plugins/circuit-breakers/{routeId}` |
| DELETE | `/api/plugins/circuit-breakers/{routeId}` |

### 6.7 Nacos 配置存储
- 插件配置：`gateway-plugins.json` (所有插件配置统一存储)

---

## 七、配置自动刷新机制

### 7.1 监听器驱动更新
所有配置变化通过 Nacos 监听器实时感知：
- `RouteRefresher`：监听路由配置变化
- `ServiceRefresher`：监听服务配置变化
- `StrategyRefresher`：监听插件配置变化

### 7.2 定时同步兜底
- 每分钟同步一次，确保缓存一致性
- 发现遗漏配置自动补载

---

## 八、全局过滤器

| 过滤器 | Order | 功能 |
|--------|-------|------|
| StaticProtocolGlobalFilter | 10000 | 拦截 static:// 协议，转换为 lb:// |
| DiscoveryLoadBalancerFilter | 10150 | 为 static:// 路由提供负载均衡 |
| HybridRateLimiterFilter | 10200 | 限流 |
| IPFilterGlobalFilter | 10300 | IP黑白名单过滤 |
| CircuitBreakerGlobalFilter | 10400 | 熔断保护 |
| AuthGlobalFilter | 10500 | 认证 |

---

## 九、典型使用场景

### 场景1：Nacos 服务发现
```
1. 微服务注册到 Nacos
2. 创建路由：uri = lb://service-name
3. 网关自动发现服务实例
```

### 场景2：固定节点服务
```
1. 创建服务：POST /api/services
   → 返回 serviceId: "xxx-xxx-xxx"
2. 创建路由：uri = static://xxx-xxx-xxx
3. 网关使用配置的固定节点
```

### 场景3：配置限流
```
1. 创建路由
2. 创建限流配置：POST /api/plugins/rate-limiters
3. 配置自动下发生效
```