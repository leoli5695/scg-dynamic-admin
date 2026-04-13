# Service Discovery

> 服务发现支持动态（Nacos/Consul）和静态两种模式，提供负载均衡能力。

---

## Overview

Gateway 支持两种服务发现协议：

| Protocol | Description | Use Case |
|----------|-------------|----------|
| `lb://` | 动态服务发现 (Nacos/Consul) | 服务注册到服务中心的场景 |
| `static://` | 静态服务发现 | 固定 IP 地址、外部 API |

---

## Dynamic Discovery (lb://)

### Configuration

在路由 URI 中使用 `lb://` 协议：

```json
{
  "uri": "lb://user-service"
}
```

Gateway 自动从 Nacos/Consul 获取 `user-service` 的所有实例。

### Service Registration

后端服务需要注册到 Nacos：

```yaml
# user-service application.yml
spring:
  application:
    name: user-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: gateway-prod
```

### Namespace/Group Override

支持跨命名空间/分组的服务发现：

```json
{
  "routeId": "cross-namespace-route",
  "uri": "lb://external-service",
  "strategies": {
    "serviceNamespace": "external-ns",
    "serviceGroup": "EXTERNAL_GROUP"
  }
}
```

**应用场景：**
- 网关在 `gateway-prod` namespace
- 需要调用 `external-ns` namespace 的服务

---

## Static Discovery (static://)

### Configuration

使用 `static://` 协议：

```json
{
  "uri": "static://legacy-backend"
}
```

然后在 `gateway-services.json` 中定义实例：

```json
{
  "name": "legacy-backend",
  "loadBalancer": "weighted",
  "instances": [
    {
      "ip": "192.168.1.10",
      "port": 8080,
      "weight": 1,
      "enabled": true
    },
    {
      "ip": "192.168.1.11",
      "port": 8080,
      "weight": 2,
      "enabled": true
    }
  ]
}
```

### Instance Fields

| Field | Type | Description |
|-------|------|-------------|
| `ip` | String | 实例 IP 地址 |
| `port` | Integer | 实例端口 |
| `weight` | Integer | 负载均衡权重 (1-100) |
| `enabled` | Boolean | 是否启用 |

### Use Cases

- **Legacy Systems**: 未接入服务注册中心的遗留系统
- **External APIs**: 第三方 API 服务
- **Fixed Endpoints**: 固定地址的内部服务

---

## Load Balancing Strategies

| Strategy | Description | Best For |
|----------|-------------|----------|
| `weighted` | 平滑加权轮询 | 实例性能不均等 |
| `round-robin` | 顺序轮询 | 实例性能相近 |
| `random` | 随机选择 | 简单场景 |
| `consistent-hash` | 基于 Hash (IP/Header) | 会话保持 |

### Weighted Round-Robin

```
Instances: [A(weight=1), B(weight=2), C(weight=1)]

Algorithm:
  AtomicInteger counter
  int index = counter.getAndIncrement() % totalWeight(4)
  
  // index 0 -> A
  // index 1,2 -> B
  // index 3 -> C

Result: A -> B -> B -> C -> A -> B -> B -> C -> ...
```

### Consistent Hash

基于客户端 IP 或 Header 进行 Hash：

```json
{
  "loadBalancer": "consistent-hash",
  "hashKey": "ip"  // or "header:X-Session-Id"
}
```

**应用场景：**
- 会话保持
- 缓存命中率优化

---

## Health-Aware Routing

### Features

- **自动跳过不健康实例**
- **禁用实例不参与负载均衡**
- **健康状态实时同步到 UI**

### Health Check

Gateway 支持混合健康检查：

```
┌─────────────────────────────────────────────┐
│         Hybrid Health Checker                │
│                                              │
│   Passive Check (零开销):                    │
│   - 每次请求成功 → 更新健康缓存              │
│   - 无额外网络调用                           │
│                                              │
│   Active Check (按需):                       │
│   - 连续失败时触发                           │
│   - HTTP 调用 /actuator/health              │
│   - 失败阈值: 3 次连续失败                   │
│                                              │
│   Local Cache (Caffeine):                    │
│   - 最大 10,000 实例                         │
│   - 过期时间 5 分钟                          │
└─────────────────────────────────────────────┘
```

### Configuration

```yaml
gateway:
  health:
    batch-size: 50
    failure-threshold: 3
    recovery-time: 30000
    idle-threshold: 300000
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/services` | List all services |
| `GET` | `/api/services/{name}` | Get service details |
| `POST` | `/api/services` | Create service (static) |
| `PUT` | `/api/services/{name}` | Update service |
| `DELETE` | `/api/services/{name}` | Delete service |
| `GET` | `/api/services/{name}/instances` | Get instances |

### Create Static Service

```bash
curl -X POST http://localhost:9090/api/services \
  -H "Content-Type: application/json" \
  -d '{
    "name": "legacy-backend",
    "loadBalancer": "weighted",
    "instances": [
      {"ip": "192.168.1.10", "port": 8080, "weight": 1, "enabled": true},
      {"ip": "192.168.1.11", "port": 8080, "weight": 2, "enabled": true}
    ]
  }'
```

---

## Service Discovery SPI

Gateway 支持可扩展的服务发现 SPI：

```
┌─────────────────────────────────────────────┐
│       DiscoveryService (SPI Interface)       │
│                                              │
│   + getInstances(serviceId): List<Instance>  │
│   + watch(serviceId, listener): void         │
└─────────────────────────────────────────────┘
                  │
    ┌─────────────┼─────────────┐
    │             │             │
    ▼             ▼             ▼
┌─────────┐ ┌─────────┐ ┌─────────┐
│ Nacos   │ │ Consul  │ │ Static  │
│Discovery│ │Discovery│ │Discovery│
│ Service │ │ Service │ │ Service │
└─────────┘ └─────────┘ └─────────┘
```

---

## Best Practices

1. **命名规范**：服务名使用 `-` 分隔，如 `user-service`
2. **权重设置**：根据实例性能设置合理权重
3. **健康检查**：后端服务暴露 `/actuator/health` 端点
4. **命名空间隔离**：不同环境使用不同 namespace
5. **优雅下线**：先禁用实例，再停止服务

---

## Related Features

- [Route Management](route-management.md) - 路由 URI 配置
- [Multi-Service Routing](multi-service-routing.md) - 多服务路由
- [Circuit Breaker](circuit-breaker.md) - 实例熔断保护