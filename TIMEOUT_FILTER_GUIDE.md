# 超时过滤器配置说明

## 概述

超时过滤器允许为不同的路由配置不同的超时时间，包括连接超时、读取超时和响应超时。

## 配置参数

| 参数名 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| routeId | String | - | 路由 ID（必填） |
| connectTimeout | int | 5000 | 连接超时（毫秒） |
| readTimeout | int | 10000 | 读取超时（毫秒） |
| responseTimeout | int | 30000 | 响应超时（毫秒） |
| enabled | boolean | true | 是否启用 |

## Nacos 配置示例

在 `gateway-plugins.json` 中添加超时配置：

```json
{
  "version": "1.0",
  "plugins": {
    "rateLimiters": [
      {
        "routeId": "user-route",
        "qps": 100,
        "timeUnit": "second",
        "burstCapacity": 200,
        "keyResolver": "ip",
        "keyType": "combined",
        "keyPrefix": "rate_limit:",
        "enabled": true
      }
    ],
    "timeouts": [
      {
        "routeId": "user-route",
        "connectTimeout": 5000,
        "readTimeout": 10000,
        "responseTimeout": 30000,
        "enabled": true
      },
      {
        "routeId": "order-route",
        "connectTimeout": 3000,
        "readTimeout": 5000,
        "responseTimeout": 15000,
        "enabled": true
      },
      {
        "routeId": "payment-route",
        "connectTimeout": 10000,
        "readTimeout": 60000,
        "responseTimeout": 120000,
        "enabled": true
      }
    ]
  }
}
```

## 通过 API 管理超时配置

### 1. 创建超时配置

```bash
POST http://localhost:8080/api/plugins/timeout
Content-Type: application/json

{
  "routeId": "user-route",
  "connectTimeout": 5000,
  "readTimeout": 10000,
  "responseTimeout": 30000,
  "enabled": true
}
```

### 2. 更新超时配置

```bash
PUT http://localhost:8080/api/plugins/timeout/user-route
Content-Type: application/json

{
  "routeId": "user-route",
  "connectTimeout": 8000,
  "readTimeout": 15000,
  "responseTimeout": 45000,
  "enabled": true
}
```

### 3. 删除超时配置

```bash
DELETE http://localhost:8080/api/plugins/timeout/user-route
```

### 4. 获取所有超时配置

```bash
GET http://localhost:8080/api/plugins/timeouts
```

### 5. 获取指定路由的超时配置

```bash
GET http://localhost:8080/api/plugins/timeout/user-route
```

## 超时类型说明

### 连接超时 (connectTimeout)
- **定义**：建立 TCP 连接的超时时间
- **默认值**：5000ms (5 秒)
- **建议**：内网服务可设置较短（如 3 秒），外网服务可适当延长

### 读取超时 (readTimeout)
- **定义**：从服务器读取数据的超时时间
- **默认值**：10000ms (10 秒)
- **建议**：根据接口响应时间调整，快速接口可设置较短

### 响应超时 (responseTimeout)
- **定义**：整个请求响应的总超时时间
- **默认值**：30000ms (30 秒)
- **建议**：包含连接 + 读取的总时间，应大于前两者之和

## 使用场景

### 1. 快速接口
对于响应快的接口（如查询用户信息）：
```json
{
  "routeId": "user-info",
  "connectTimeout": 2000,
  "readTimeout": 3000,
  "responseTimeout": 5000,
  "enabled": true
}
```

### 2. 慢接口
对于处理时间长的接口（如生成报表）：
```json
{
  "routeId": "report-generate",
  "connectTimeout": 5000,
  "readTimeout": 60000,
  "responseTimeout": 120000,
  "enabled": true
}
```

### 3. 外部支付接口
对于调用第三方支付接口：
```json
{
  "routeId": "payment",
  "connectTimeout": 10000,
  "readTimeout": 60000,
  "responseTimeout": 120000,
  "enabled": true
}
```

## 注意事项

1. **超时优先级**：响应超时 > 读取超时 > 连接超时
2. **合理设置**：避免设置过短导致正常请求失败，也避免过长占用资源
3. **监控告警**：建议配合监控系统，当频繁超时时及时告警
4. **与限流配合**：超时和限流可以同时使用，互不影响

## 日志示例

启用超时配置后，网关会输出如下日志：

```
✅ Loaded timeout config for route user-route: connect=5000ms, read=10000ms, response=30000ms
⏱️ Timeout - Route 'user-route': connect=5000ms, read=10000ms, response=30000ms
Total 1 timeout configs loaded
```

当请求超时时：

```
Applying timeout for route user-route: connect=5000, read=10000, response=30000
Response timeout exceeded
```
