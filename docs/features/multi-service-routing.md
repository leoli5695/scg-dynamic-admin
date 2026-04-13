# Multi-Service Routing & Gray Release

> 多服务路由允许单个路由将流量分发到多个后端服务，支持灰度发布和 A/B 测试。

---

## Overview

传统网关只能将请求路由到单一后端服务。多服务路由功能允许：
- 将流量按权重分发到多个版本
- 基于请求特征（Header/Cookie/Query）进行精准路由
- 实现灰度发布、A/B 测试、金丝雀部署

---

## Configuration

```json
{
  "id": "user-route",
  "mode": "MULTI",
  "services": [
    {
      "serviceId": "user-v1",
      "serviceName": "User Service V1",
      "weight": 90,
      "type": "DISCOVERY",
      "enabled": true
    },
    {
      "serviceId": "user-v2",
      "serviceName": "User Service V2",
      "weight": 10,
      "type": "DISCOVERY",
      "enabled": true
    }
  ],
  "grayRules": {
    "enabled": true,
    "rules": [
      {
        "type": "HEADER",
        "name": "X-Version",
        "value": "v2",
        "targetVersion": "user-v2"
      }
    ]
  }
}
```

### Field Description

| Field | Type | Description |
|-------|------|-------------|
| `mode` | String | `SINGLE` (默认) 或 `MULTI` |
| `services` | Array | 目标服务列表 |
| `grayRules` | Object | 灰度规则配置 |

### Service Binding

| Field | Type | Description |
|-------|------|-------------|
| `serviceId` | String | 服务标识符 |
| `serviceName` | String | 服务显示名称 |
| `weight` | Integer | 权重 (1-100) |
| `type` | String | `DISCOVERY` 或 `STATIC` |
| `enabled` | Boolean | 是否启用 |

---

## Gray Rule Types

| Type | Description | Match Example |
|------|-------------|---------------|
| `HEADER` | 匹配 HTTP Header | `X-Version: v2` |
| `COOKIE` | 匹配 Cookie 值 | `version=v2` |
| `QUERY` | 匹配 URL 参数 | `?version=v2` |
| `WEIGHT` | 按权重百分比 | 10% 流量到 v2 |

### HEADER Rule

```json
{
  "type": "HEADER",
  "name": "X-Version",
  "value": "v2",
  "targetVersion": "user-v2"
}
```

请求包含 `X-Version: v2` Header 时，强制路由到 `user-v2`。

### COOKIE Rule

```json
{
  "type": "COOKIE",
  "name": "version",
  "value": "beta",
  "targetVersion": "user-beta"
}
```

Cookie `version=beta` 的用户路由到 beta 服务。

### QUERY Rule

```json
{
  "type": "QUERY",
  "name": "preview",
  "value": "true",
  "targetVersion": "user-preview"
}
```

URL 包含 `?preview=true` 时路由到预览服务。

### WEIGHT Rule

```json
{
  "type": "WEIGHT",
  "value": "10",
  "targetVersion": "user-v2"
}
```

10% 的流量随机分配到 `user-v2`。

---

## Rule Matching Logic

```
Request arrives
      │
      ▼
┌─────────────────┐
│ Check Gray Rules│
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
   Match    No Match
    │         │
    ▼         ▼
  Route to  Use Weight
  Target    Distribution
  Service
```

**First-match-wins:** 规按配置顺序依次检查，首个匹配的规则生效。

### Multiple Rules Example

```json
{
  "grayRules": {
    "enabled": true,
    "rules": [
      {
        "type": "HEADER",
        "name": "X-Force-V2",
        "value": "true",
        "targetVersion": "user-v2"
      },
      {
        "type": "COOKIE",
        "name": "beta_user",
        "value": "yes",
        "targetVersion": "user-v2"
      },
      {
        "type": "WEIGHT",
        "value": "5",
        "targetVersion": "user-v2"
      }
    ]
  },
  "services": [
    {"serviceId": "user-v1", "weight": 95},
    {"serviceId": "user-v2", "weight": 5}
  ]
}
```

匹配顺序：
1. 检查 `X-Force-V2` Header → 匹配则路由到 v2
2. 检查 `beta_user` Cookie → 匹配则路由到 v2
3. 无匹配则按权重：95% v1, 5% v2

---

## Use Cases

### Canary Deployment (金丝雀部署)

逐步将流量从旧版本迁移到新版本：

| Stage | V1 Weight | V2 Weight | Description |
|-------|-----------|-----------|-------------|
| Stage 1 | 99% | 1% | 新版本上线，极小流量测试 |
| Stage 2 | 95% | 5% | 观察新版本稳定性 |
| Stage 3 | 80% | 20% | 扩大测试范围 |
| Stage 4 | 50% | 50% | 平滑过渡 |
| Stage 5 | 0% | 100% | 完全切换 |

```json
{
  "mode": "MULTI",
  "services": [
    {"serviceId": "user-v1", "weight": 95},
    {"serviceId": "user-v2", "weight": 5}
  ]
}
```

### A/B Testing

同时运行不同版本，收集用户反馈：

```json
{
  "mode": "MULTI",
  "services": [
    {"serviceId": "user-design-a", "weight": 50},
    {"serviceId": "user-design-b", "weight": 50}
  ]
}
```

### Internal Testing

内部用户/测试人员路由到新版本：

```json
{
  "grayRules": {
    "rules": [
      {
        "type": "HEADER",
        "name": "X-Internal",
        "value": "true",
        "targetVersion": "user-v2"
      }
    ]
  }
}
```

### Beta User Program

白名单用户体验新功能：

```json
{
  "grayRules": {
    "rules": [
      {
        "type": "COOKIE",
        "name": "beta_user",
        "value": "true",
        "targetVersion": "user-beta"
      }
    ]
  }
}
```

---

## Weight-Based Load Balancing

使用平滑加权轮询算法：

```
Instances: [A(weight=1), B(weight=2), C(weight=1)]
Total Weight: 4

Selection Sequence: A -> B -> B -> C -> A -> B -> B -> C -> ...
```

**特点：**
- 权重高的服务被选中概率更高
- 分布均匀，避免集中请求

---

## API Endpoints

通过 Route API 配置多服务路由，在 route 的 `strategies` 中设置：

```bash
curl -X POST http://localhost:9090/api/routes \
  -H "Content-Type: application/json" \
  -d '{
    "routeName": "User API",
    "uri": "lb://user-service",
    "predicates": [
      {"name": "Path", "args": {"pattern": "/api/user/**"}}
    ],
    "strategies": {
      "multiService": {
        "enabled": true,
        "mode": "MULTI",
        "services": [
          {"serviceId": "user-v1", "weight": 90},
          {"serviceId": "user-v2", "weight": 10}
        ],
        "grayRules": {
          "enabled": true,
          "rules": [
            {"type": "HEADER", "name": "X-Version", "value": "v2", "targetVersion": "user-v2"}
          ]
        }
      }
    }
  }'
```

---

## Best Practices

1. **渐进式发布**：从小比例开始，逐步增加
2. **监控告警**：密切监控新版本错误率和响应时间
3. **快速回滚**：发现问题时立即调整权重或禁用新版本
4. **用户隔离**：使用 Header/Cookie 精准控制测试用户
5. **版本命名**：使用清晰的版本标识，如 `user-v1`, `user-v2`

---

## Related Features

- [Route Management](route-management.md) - 基础路由配置
- [Service Discovery](service-discovery.md) - 服务发现机制
- [Monitoring & Alerts](monitoring-alerts.md) - 监控新版本性能