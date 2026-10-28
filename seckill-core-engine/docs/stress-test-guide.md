# 秒杀接口压测配置指南

> 使用网关控制台的压测功能模块对秒杀接口进行性能测试

> **适用范围**：本指南面向本地开发与 K8s `test` 命名空间的测试环境。`seckill-core-engine` **不在生产运行**
> （生产服务器上只有 `gateway-admin`、`my-gateway` 两个容器），示例中的地址与端口请按实际部署替换。

---

## 前置条件

1. **库存预热**: 先调用预热接口将库存加载到 Redis
2. **活动配置**: 确保秒杀活动状态为"进行中"
3. **网关配置**: 在网关控制台配置秒杀服务的路由实例
4. **身份凭证**: `/seckill/**` 受 OAuth2 JWT 资源服务器保护（`SeckillIdentitySecurity.java:59`
   `.requestMatchers("/seckill/**","/refund/**","/order/**").authenticated()`），压测请求的 `headers`
   必须带上 `Authorization: Bearer <jwt>`；若 `SECKILL_IDENTITY_ISSUER` / `SECKILL_IDENTITY_AUDIENCE`
   与公钥（`public-key-pem` 或 `jwk-set-uri`）未配置，解码器会拒绝一切令牌
   （`application.yml:175-182`），所有请求都会失败。
5. **控制台接口鉴权**: 下文 `/api/stress-test/**` 属于 gateway-admin 接口，`SecurityConfig` 要求
   已登录（GET）/ ADMIN 权限（写操作），curl 示例中的 Cookie 或 `Authorization` 头需自行补齐。

---

## 秒杀压测配置

### 基础配置

```json
{
  "testName": "秒杀核心引擎压测",
  "targetUrl": "http://<gateway-host>:8080",
  "path": "/seckill/do",
  "method": "POST",
  "headers": {
    "Content-Type": "application/json",
    "Authorization": "Bearer <jwt>",
    "X-Trace-Id": "seckill-stress-run-1"
  },
  "body": {
    "userId": {{randomInt:10001-99999}},
    "seckillId": 1,
    "productId": 1,
    "quantity": 1,
    "ipAddress": "127.0.0.1"
  },
  "concurrentUsers": 100,
  "totalRequests": 10000,
  "targetQps": 5000,
  "rampUpSeconds": 10,
  "requestTimeoutSeconds": 30
}
```

> - `targetUrl` 中的主机名与端口需替换为实际网关地址：网关容器监听 **8080**
>   （仓库根 `k8s/my-gateway.yaml` 的 containerPort 8080，生产网关也是 `SERVER_PORT=8080`），
>   `gateway` 这类服务名只能在 K8s / docker 网络内部解析，本机压测请用 `http://localhost:8080`
>   或节点 IP。
> - **占位符只在 request body 中生效**（`StressTestService.java:517-520` 只对 `body` 调用
>   `placeholderResolver.resolve(...)`，headers 原样发出），所以 `X-Trace-Id` 只能写固定值；
>   秒杀侧读到空 `X-Trace-Id` 时会自行生成 UUID 兜底（`SeckillController.java:89-91`）。
> - body 里的 `userId` 会被服务端用 JWT 身份覆盖
>   （`SeckillController.java:86` → `TrustedUserIdentity.requireUserId()`），随机化 userId
>   **不会**产生多用户压测效果；需要多用户请准备多个令牌。

### 参数说明

| 参数 | 秒杀场景建议值 | 说明 |
|------|--------------|------|
| `concurrentUsers` | 50-500 | 模拟并发用户数 |
| `totalRequests` | 1000-50000 | 总请求数量 |
| `targetQps` | 1000-10000 | 目标 QPS（逐步增加） |
| `rampUpSeconds` | 5-30 | 爬坡时间，避免瞬时冲击 |
| `requestTimeoutSeconds` | 30 | 秒杀请求超时时间 |

---

## 动态参数模板

网关压测工具支持在 **request body** 中做动态参数替换（`StressTestPlaceholderResolver`）。
支持的占位符**只有**下列几种，写错的占位符不会被报错，而是**原样保留发送出去**
（`StressTestPlaceholderResolver.java:50-51` “Unknown placeholders are preserved as-is”，
`:103-107` 只打一条 warn），所以拼写错误是静默失败：

| 占位符 | 说明 |
|--------|------|
| `{{randomInt}}` | 1 ~ 1,000,000 的随机整数 |
| `{{randomInt:min-max}}` | 指定闭区间内的随机整数 |
| `{{randomString}}` | 16 位随机字母数字串 |
| `{{randomString:length}}` | 指定长度的随机字母数字串 |
| `{{randomUuid}}` | UUID v4 |
| `{{timestamp}}` | 当前毫秒时间戳 |

### 用户ID随机化

数值型字段不要加引号，否则会变成字符串：

```json
{
  "userId": {{randomInt:10001-99999}}
}
```

字符串型字段才需要引号：

```json
{
  "token": "{{randomString:32}}"
}
```

### TraceId 自动生成

**当前实现无法为每个请求生成随机 TraceId**：headers 不做占位符解析
（`StressTestService.java:517-520` 只解析 body），写成 `"X-Trace-Id": "{{randomUuid}}"`
会把字面量 `{{randomUuid}}` 原样发出去；而 body 里的 `traceId` 字段又会被服务端用 header 值覆盖
（`SeckillController.java:89-93`）。因此 header 里只能写固定值，
秒杀侧读不到 `X-Trace-Id` 时会自己生成 UUID 兜底。

body 中需要唯一串时可用 `{{randomUuid}}`：

```json
{
  "requestId": "{{randomUuid}}"
}
```

---

## 分阶段压测策略

### 第一阶段：基准测试

```json
{
  "testName": "秒杀基准测试",
  "concurrentUsers": 10,
  "totalRequests": 1000,
  "targetQps": 100
}
```

目的：验证接口正常工作，获取基准性能数据

### 第二阶段：中等负载

```json
{
  "testName": "秒杀中等负载",
  "concurrentUsers": 50,
  "totalRequests": 5000,
  "targetQps": 500,
  "rampUpSeconds": 5
}
```

目的：测试中等并发下的性能表现

### 第三阶段：高负载

```json
{
  "testName": "秒杀高负载",
  "concurrentUsers": 200,
  "totalRequests": 20000,
  "targetQps": 2000,
  "rampUpSeconds": 10
}
```

目的：测试高并发场景，观察限流效果

### 第四阶段：极限测试

```json
{
  "testName": "秒杀极限测试",
  "concurrentUsers": 500,
  "totalRequests": 50000,
  "targetQps": 5000,
  "rampUpSeconds": 30
}
```

目的：测试系统极限，观察熔断降级

---

## 压测指标关注点

### 核心指标

| 指标 | 健康阈值 | 异常阈值 |
|------|---------|---------|
| `avgResponseTimeMs` | < 50ms | > 200ms |
| `p99ResponseTimeMs` | < 100ms | > 500ms |
| `requestsPerSecond` | 接近 targetQps | 低于 50% |
| `errorRate` | < 1% | > 10% |
| `successfulRequests` | 库存范围内 | 超出库存 |

### 秒杀特有指标

通过 Prometheus 监控面板观察：

- `seckill_request_total`: 秒杀请求总数
- `seckill_success_total`: 秒杀成功总数
- `seckill_stock_insufficient_total`: 库存不足次数
- `seckill_already_bought_total`: 重复购买次数
- `seckill_lua_seconds`: Lua脚本执行耗时

---

## 压测执行步骤

### Step 1: 预热库存

预热接口是 `POST /warmup/manual`，`seckillId` 是 **query 参数**（不是 JSON body），
库存数量与分片数由服务端从 MySQL 活动配置读取，不接受外部传入
（`WarmupController.java:32,48-51`）。该接口标注 `@InternalApi`，
`seckill.internal-api.enabled=true` 时只允许白名单 IP 调用（默认白名单含 `127.0.0.1`）。

```bash
curl -X POST "http://localhost:8080/warmup/manual?seckillId=1"
```

配套接口：`GET /warmup/status?seckillId=1`（是否已预热）、
`GET /warmup/verify?seckillId=1`（Redis 与 MySQL 库存一致性）、
`POST /warmup/cleanup?seckillId=1`（清理预热数据）。

### Step 2: 启动压测

在网关控制台：
1. 进入 "压力测试" 模块
2. 选择秒杀服务实例
3. 配置压测参数
4. 点击 "开始测试"

### Step 3: 实时监控

观察：
- 压测进度条
- 实时统计数据
- Prometheus Dashboard
- Grafana 告警状态

### Step 4: 结果分析

使用网关的 AI 分析功能：
```bash
curl -X GET http://localhost:9090/api/stress-test/{testId}/analyze
```

### Step 5: 导出报告

导出**只支持 Markdown**。`GET /api/stress-test/{testId}/export` 虽然声明了 `format` 参数，
但实现中从不使用它，恒定调用 `exportAsMarkdown`、`Content-Type: text/markdown`、
文件名 `stress-test-report-{testId}.md`（`StressTestController.java:194-207`）；
仓库中也没有任何 PDF/Excel 生成依赖。

```bash
curl -X GET "http://localhost:9090/api/stress-test/{testId}/export" \
  --output seckill-stress-test-report.md
```

---

## 常见问题处理

### 库存不足导致失败率高

**现象**: errorRate > 80%，大部分请求返回"库存不足"

**原因**: 库存已售罄，这是正常的秒杀结果

**解决**: 重新预热库存，或增大初始库存

### Redis 未预热

**现象**: 所有请求返回"库存未预热"

**解决**: 执行预热接口

### 限流触发

**现象**: QPS 远低于 targetQps

**原因**: 网关限流生效

**调整**: 在网关控制台调整限流配置

### 熔断触发

**现象**: 请求全部失败，返回 503

**原因**: 系统过载，熔断器开启

**等待**: 熔断器自动恢复，或手动重置

---

## 压测最佳实践

1. **逐步增加负载**: 从低并发开始，逐步增加
2. **预留足够库存**: 压测时库存应大于总请求数的 10%
3. **监控同步观察**: 压测时同步观察 Prometheus/Grafana
4. **多轮测试对比**: 进行多轮测试，对比结果变化
5. **AI 分析辅助**: 使用 AI 分析功能获取优化建议
6. **导出报告存档**: 每次压测导出报告用于对比分析