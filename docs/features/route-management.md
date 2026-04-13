# Route Management

> 路由定义了如何将传入请求转发到后端服务。

---

## Overview

路由是 API Gateway 的核心功能，定义了请求 URL 模式与后端服务的映射关系。

**配置存储位置：** Nacos `gateway-routes.json`

---

## Route Structure

```json
{
  "id": "user-service-route",
  "routeName": "User Service Route",
  "uri": "lb://user-service",
  "order": 0,
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/user/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ],
  "enabled": true
}
```

### Field Description

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Unique route identifier |
| `routeName` | String | Display name for the route |
| `uri` | String | Target service URI (see URI Schemes) |
| `order` | Integer | Route priority (lower = higher priority) |
| `predicates` | Array | Conditions to match incoming requests |
| `filters` | Array | Transformations applied to request/response |
| `enabled` | Boolean | Whether route is active |

---

## URI Schemes

| Scheme | Description | Example |
|--------|-------------|---------|
| `lb://` | Dynamic service discovery via Nacos/Consul | `lb://user-service` |
| `static://` | Static service discovery (fixed instances) | `static://backend-service` |
| `http://` | Direct HTTP endpoint | `http://192.168.1.10:8080` |
| `https://` | Direct HTTPS endpoint | `https://api.example.com` |

### lb:// (Load Balanced)

使用服务发现（Nacos/Consul）动态获取实例：

```json
{
  "uri": "lb://user-service"
}
```

Gateway 会自动从服务注册中心获取 `user-service` 的所有实例，并进行负载均衡。

### static:// (Static Instances)

使用静态配置的实例列表：

```json
{
  "uri": "static://backend-service"
}
```

需要在 `gateway-services.json` 中配置实例：

```json
{
  "name": "backend-service",
  "instances": [
    {"ip": "192.168.1.10", "port": 8080, "weight": 1},
    {"ip": "192.168.1.11", "port": 8080, "weight": 2}
  ]
}
```

---

## Predicates

Predicates 定义了路由匹配条件。

### Available Predicates

| Predicate | Description | Example |
|-----------|-------------|---------|
| `Path` | URL path pattern | `/api/user/**` |
| `Host` | Host header match | `**.example.com` |
| `Method` | HTTP method | `GET,POST` |
| `Header` | Header existence/match | `X-Request-Id, \d+` |
| `Query` | Query parameter | `userId` |
| `After` | After time | `2024-01-01T00:00:00+08:00` |
| `Before` | Before time | `2024-12-31T23:59:59+08:00` |
| `Between` | Between time range | `2024-01-01T00:00:00+08:00, 2024-12-31T23:59:59+08:00` |
| `RemoteAddr` | Client IP match | `192.168.1.1/24` |

### Examples

**Path Predicate:**

```json
{
  "name": "Path",
  "args": {"pattern": "/api/user/**"}
}
```

匹配所有 `/api/user/` 开头的请求。

**Method Predicate:**

```json
{
  "name": "Method",
  "args": {"methods": "GET,POST"}
}
```

仅匹配 GET 和 POST 请求。

**Header Predicate:**

```json
{
  "name": "Header",
  "args": {"header": "X-Version", "regexp": "v1"}
}
```

匹配包含 `X-Version: v1` 头的请求。

**Combined Predicates:**

```json
{
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/user/**"}},
    {"name": "Method", "args": {"methods": "GET"}},
    {"name": "Header", "args": {"header": "X-Api-Key"}}
  ]
}
```

所有条件必须同时满足（AND 逻辑）。

---

## Filters

Filters 用于修改请求或响应。

### Common Filters

| Filter | Description | Example |
|--------|-------------|---------|
| `StripPrefix` | Remove path prefix | `StripPrefix(parts=1)` |
| `AddRequestHeader` | Add header to request | `AddRequestHeader(X-Custom, value)` |
| `AddResponseHeader` | Add header to response | `AddResponseHeader(X-Response-Time, ${time})` |
| `RequestRateLimiter` | Rate limiting | See Rate Limiting doc |
| `CircuitBreaker` | Circuit breaker | See Circuit Breaker doc |

### Examples

**StripPrefix:**

```json
{
  "name": "StripPrefix",
  "args": {"parts": "1"}
}
```

请求 `/api/user/123` → 转发为 `/user/123`

**AddRequestHeader:**

```json
{
  "name": "AddRequestHeader",
  "args": {"name": "X-Gateway", "value": "true"}
}
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/routes` | List all routes |
| `GET` | `/api/routes/{id}` | Get route by ID |
| `POST` | `/api/routes` | Create route |
| `PUT` | `/api/routes/{id}` | Update route |
| `DELETE` | `/api/routes/{id}` | Delete route |
| `POST` | `/api/routes/{id}/enable` | Enable route |
| `POST` | `/api/routes/{id}/disable` | Disable route |

### Create Route

```bash
curl -X POST http://localhost:9090/api/routes \
  -H "Content-Type: application/json" \
  -d '{
    "routeName": "My First Route",
    "uri": "lb://my-service",
    "predicates": [
      {"name": "Path", "args": {"pattern": "/api/my-service/**"}}
    ],
    "filters": [
      {"name": "StripPrefix", "args": {"parts": "1"}}
    ],
    "enabled": true
  }'
```

### Update Route

```bash
curl -X PUT http://localhost:9090/api/routes/user-service-route \
  -H "Content-Type: application/json" \
  -d '{
    "routeName": "Updated Route",
    "uri": "lb://user-service-v2",
    "predicates": [
      {"name": "Path", "args": {"pattern": "/api/user/**"}}
    ],
    "enabled": true
  }'
```

### Enable/Disable Route

```bash
# Enable
curl -X POST http://localhost:9090/api/routes/user-service-route/enable

# Disable
curl -X POST http://localhost:9090/api/routes/user-service-route/disable
```

---

## Route Priority

路由按 `order` 字段排序，数值越小优先级越高：

```json
[
  {"id": "route-1", "order": 0, "predicates": [{"name": "Path", "args": {"pattern": "/api/user/**"}}]},
  {"id": "route-2", "order": 1, "predicates": [{"name": "Path", "args": {"pattern": "/api/**"}}]}
]
```

请求 `/api/user/123` 会优先匹配 `route-1`，因为它的 order 更小。

---

## Hot Reload

路由配置变更会自动同步到 Gateway：

1. Admin API 更新路由 → 保存到 MySQL
2. 发布到 Nacos → Gateway 监听变更
3. Gateway 更新路由表 (< 1 second)

**无需重启 Gateway！**

---

## Best Practices

1. **命名规范**：使用有意义的前缀，如 `/api/user/**`
2. **优先级管理**：精确匹配的路由设置更低的 order
3. **版本控制**：通过 Header Predicate 实现版本路由
4. **健康检查**：定期检查路由对应服务的健康状态

---

## Related Features

- [Multi-Service Routing](multi-service-routing.md) - 多服务路由和灰度发布
- [Service Discovery](service-discovery.md) - 服务发现机制
- [Rate Limiting](rate-limiting.md) - 路由级别的限流配置
- [Authentication](authentication.md) - 路由级别的认证配置