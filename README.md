# Spring Cloud Gateway Dynamic Management Demo

基于 Spring Cloud Gateway 4.1 + Spring Boot 3.2 + Nacos 2.4.3 构建的动态网关管理系统，提供可视化配置控制台和实时热更新。

<div align="center">

[![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-4.1.0-blue)](https://spring.io/projects/spring-cloud-gateway)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-green)](https://spring.io/projects/spring-boot)
[![Nacos](https://img.shields.io/badge/Nacos-2.4.3-orange)](https://nacos.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 📋 功能概览

通过 Web 控制台即可动态管理路由、服务、插件，无需修改任何 YAML 文件。

| 功能 | 说明 |
|------|------|
| ✅ 可视化控制台 | 基于 Thymeleaf 的 Web 管理界面 |
| ✅ 实时热更新 | 配置变更即时生效，无需重启 |
| ✅ 动态路由 | 支持 Path/Host/Method/Header 等多种条件 |
| ✅ 负载均衡 | 轮询/加权轮询/随机，支持实例权重 |
| ✅ 限流插件 | 基于 Sentinel 和 Redis，支持窗口期和请求数配置 |
| ✅ IP 访问控制 | 白名单/黑名单模式 |
| ✅ 自定义请求头 | 支持变量替换（如 `${random.uuid}`） |
| ✅ 超时插件 | 按路由配置连接超时和响应超时，超时返回 504 |

---

## 🏗️ 系统架构

```
┌─────────────┐
│   Client    │
└──────╤──────┘
               │
               ▼
┌─────────────────────────┐
│        My-Gateway             │
│  ───────────────────────  │
│  TimeoutGlobalFilter          │
│  IPFilterGlobalFilter          │
│  RateLimiterGlobalFilter       │
│  DynamicCustomHeaderFilter     │
│  NacosLoadBalancerFilter       │
│  StaticProtocolGlobalFilter    │
└────────────┬────────────┘
                    │
                    ▼
           ┌────────────┐
           │ Backend Svc │
           └────────────┘
```

**配置流转:**
```
Gateway Admin → Nacos Config Center → My-Gateway (实时同步)
```

---

## 🚀 快速开始

### 环境要求
- JDK 17+
- Maven 3.8+
- Nacos 2.4.3
- Redis 6.0+

### Step 1: 启动依赖服务

**启动 Nacos:**
```bash
cd nacos/bin
# Linux/Mac
sh startup.sh -m standalone
# Windows
startup.cmd -m standalone
```

**启动 Redis:**
```bash
redis-server
```

### Step 2: 初始化 Nacos 配置

在 Nacos 控制台（Namespace: **public**）创建以下配置：

**gateway-routes.json**
```json
{
  "routes": [
    {
      "id": "user-route",
      "uri": "static://user-service",
      "predicates": [{"name": "Path", "args": {"pattern": "/api/**"}}]
    }
  ]
}
```

**gateway-services.json**
```json
{
  "version": "1.0",
  "services": [
    {
      "name": "user-service",
      "loadBalancer": "weighted",
      "instances": [
        {"ip": "127.0.0.1", "port": 9000, "weight": 1, "healthy": true, "enabled": true},
        {"ip": "127.0.0.1", "port": 9001, "weight": 2, "healthy": true, "enabled": true}
      ]
    }
  ]
}
```

**gateway-plugins.json**
```json
{
  "version": "1.0",
  "plugins": {
    "rateLimiters": [],
    "ipFilters": [],
    "timeouts": [
      {
        "routeId": "user-route",
        "connectTimeout": 5000,
        "responseTimeout": 10000,
        "enabled": true
      }
    ]
  }
}
```

### Step 3: 启动服务

```bash
# Demo 服务（实例1）
cd demo-service
mvn spring-boot:run

# Demo 服务（实例2，另开终端）
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=9001"

# My-Gateway
cd my-gateway
mvn spring-boot:run

# Gateway Admin
cd gateway-admin
mvn spring-boot:run
```

### Step 4: 访问地址

| 组件 | URL | 说明 |
|------|-----|------|
| **Gateway Admin 控制台** | http://localhost:8080 | Web 管理界面 |
| **网关入口** | http://localhost:80 | 请求入口 |
| **Demo 服务** | http://localhost:9000 | 示例后端服务 |
| **Nacos 控制台** | http://localhost:8848/nacos | 配置中心 |

---

## 🔧 插件使用

### 超时插件

支持按路由配置连接超时和响应超时，超时后网关返回 **HTTP 504 Gateway Timeout**。

```json
{
  "routeId": "user-route",
  "connectTimeout": 3000,
  "responseTimeout": 5000,
  "enabled": true
}
```

| 参数 | 说明 | 单位 |
|------|------|------|
| `connectTimeout` | TCP 连接超时 | 毫秒 |
| `responseTimeout` | 发起请求到收到完整响应的总超时 | 毫秒 |

> **原理**：通过写入 SCG 路由 metadata（`RouteMetadataUtils`），由 `NettyRoutingFilter` 在 Netty 底层应用超时。

### 限流插件

基于 Sentinel 或 Redis，支持按路由限制请求速率。

```json
{
  "routeId": "user-route",
  "maxRequests": 100,
  "windowSeconds": 60,
  "enabled": true
}
```

### IP 访问控制插件

支持白名单和黑名单模式，拒绝时返回 **HTTP 403 Forbidden**。

```json
{
  "routeId": "user-route",
  "mode": "whitelist",
  "ipList": ["192.168.1.0/24", "127.0.0.1"],
  "enabled": true
}
```

### 负载均衡策略

`gateway-services.json` 中 `loadBalancer` 支持三种策略：

| 策略值 | 说明 |
|---------|------|
| `round-robin` | 轮询（默认） |
| `weighted` | **确定性加权轮询**，严格按 `weight` 比例分配 |
| `random` | 随机 |

---

## 📁 项目结构

```
scg-dynamic-admin-demo/
├── gateway-admin/           # Web 管理控制台
│   ├── controller/          # REST API + 页面控制器
│   ├── model/               # 配置模型（PluginConfig, RouteConfig...）
│   ├── service/             # 业务逻辑层
│   └── resources/templates/ # Thymeleaf 页面
├── my-gateway/              # 网关核心服务
│   ├── filter/              # 全局过滤器
│   │   ├── TimeoutGlobalFilter.java        # 超时过滤器
│   │   ├── IPFilterGlobalFilter.java       # IP 访问控制
│   │   ├── NacosLoadBalancerFilter.java    # Nacos 负载均衡
│   │   └── StaticProtocolGlobalFilter.java # static:// 协议处理
│   ├── ratelimiter/         # 限流实现（Sentinel + Redis）
│   ├── plugin/              # 插件配置管理器
│   └── route/               # 动态路由加载
├── demo-service/            # 示例后端服务
├── nacos/                   # Nacos 服务器（子模块）
└── README.md
```

---

## ⚠️ 运行环境说明

本项目为**学习/演示**用途，生产环境部署还需考虑：

- **持久化**：目前数据全部存储在 Nacos 配置中，建议生产进入持久层
- **安全**：鉴权认证、SSL/TLS
- **监控**： Prometheus 指标、分布式链路追踪
- **高可用**：集群部署、Nacos 集群、Redis 集群

---

## 📄 文档

- **[INTEGRATION_GUIDE.md](./INTEGRATION_GUIDE.md)** - 详细架构说明和集成指南
- **[ADVANCED_FEATURES.md](./ADVANCED_FEATURES.md)** - 高级功能：分布式限流、IP 访问控制、CORS

---

## 📄 License

MIT License - see [LICENSE](LICENSE) file.

---

<div align="center">

**Made with ❤️ by leoli**

If you find this helpful, please give it a ⭐ Star!

</div>
