# 秒杀接口压测配置指南

> 使用网关控制台的压测功能模块对秒杀接口进行性能测试

---

## 前置条件

1. **库存预热**: 先调用预热接口将库存加载到 Redis
2. **活动配置**: 确保秒杀活动状态为"进行中"
3. **网关配置**: 在网关控制台配置秒杀服务的路由实例

---

## 秒杀压测配置

### 基础配置

```json
{
  "testName": "秒杀核心引擎压测",
  "targetUrl": "http://gateway:80",
  "path": "/seckill/do",
  "method": "POST",
  "headers": {
    "Content-Type": "application/json",
    "X-Trace-Id": "{{randomTraceId}}"
  },
  "body": {
    "userId": "{{randomUserId}}",
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

网关压测工具支持动态参数替换：

### 用户ID随机化

```json
{
  "userId": "{{randomUserId}}"
}
```

生成范围：10001 - 99999

### TraceId 自动生成

```json
{
  "X-Trace-Id": "{{randomTraceId}}"
}
```

自动生成 UUID 格式的追踪ID

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

```bash
curl -X POST http://localhost:8080/warmup/stock \
  -H "Content-Type: application/json" \
  -d '{"seckillId": 1, "stockCount": 1000, "shardCount": 8}'
```

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

```bash
curl -X GET "http://localhost:9090/api/stress-test/{testId}/export?format=pdf" \
  --output seckill-stress-test-report.pdf
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