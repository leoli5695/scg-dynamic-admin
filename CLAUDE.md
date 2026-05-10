# Seckill Core Engine - Project Guidelines for AI Assistants

## 项目概述

这是一个生产级高并发秒杀核心引擎，具备以下核心能力：
- 分库分表方案（8库 × 16表）
- Redis热点分片设计（8个分片避免单Key热点）
- RocketMQ事务消息保证最终一致性
- 攒批落库优化（TPS从2000提升到10000+）
- 多层降级方案（Redis降级、MQ降级）
- 完善的补偿机制（定时任务兜底）

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 17 |
| Spring Boot | 3.2.5 |
| ShardingSphere | 5.5.3 |
| Redis | Lettuce / Redisson 3.27.2 |
| RocketMQ | 2.3.0 |
| MyBatis-Plus | 3.5.5 |
| Caffeine | 3.1.8 (本地缓存) |

## Commit Message 规范

### 格式要求

```
<type>: <subject> (P级别)

<body>

🤖 Generated with [Qoder][https://qoder.com]
```

### 类型分类

| Type | 说明 | 示例 |
|------|------|------|
| `feat` | 新功能 | feat: add anti-scraping mechanism (P1) |
| `fix` | Bug修复 | fix: resolve ZSET dedup issue (P2) |
| `perf` | 性能优化 | perf: optimize batch flush duration (P1) |
| `refactor` | 重构 | refactor: extract magic numbers to constants (P2) |
| `test` | 测试 | test: add degradation E2E test (P2) |
| `config` | 配置变更 | config: add test-mode defaults (P0) |
| `docs` | 文档 | docs: update API documentation |
| `chore` | 构建/工具 | chore: update dependencies |

### 优先级分级

| P级别 | 说明 | 处理时效 |
|------|------|----------|
| **P0** | 安全漏洞、生产阻塞 | 立即修复 |
| **P1** | 性能瓶颈、核心功能缺失 | 1-2天内 |
| **P2** | 代码质量、监控完善 | 本周内 |
| **P3** | 文档、测试补充 | 低优先级 |

### Body 规范

```
Changes:
- 具体改动点1
- 具体改动点2
- 具体改动点3

Configuration:
- 配置项说明

Integration:
- 集成说明
```

### 示例 Commit

```
feat: enhance anti-scraping with Lua sliding window (P1)

Changes:
- Replace fixed window counter with Lua sliding window
- Avoid boundary burst problem (精确限流)
- Add random value to ZSET member for dedup

Configuration:
- seckill.anti-scraping.ip-rate-limit: 10 (default)
- seckill.anti-scraping.user-rate-limit: 5 (default)

🤖 Generated with [Qoder][https://qoder.com]
```

## 代码风格规范

### 异常处理

```java
// 业务异常：返回业务错误码
@ExceptionHandler(StockInsufficientException.class)
public SeckillResponse handle(StockInsufficientException e) {
    return SeckillResponse.fail(SeckillResult.STOCK_INSUFFICIENT);
}

// 系统异常：返回 systemError + ERROR日志
@ExceptionHandler(RedisFailureException.class)
public SeckillResponse handle(RedisFailureException e) {
    log.error("Redis故障: {}", e.getMessage(), e.getCause());
    return SeckillResponse.systemError("系统繁忙，请稍后再试");
}
```

### 错误码规范

| 类别 | 范围 | 示例 |
|------|------|------|
| 业务错误 | -1 ~ -10 | STOCK_INSUFFICIENT(0), ALREADY_BOUGHT(-1) |
| 系统错误 | -99 | SYSTEM_ERROR |
| Redis故障 | -100 ~ -199 | REDIS_CONNECTION_FAILURE(-100) |
| DB故障 | -200 ~ -299 | DB_CONNECTION_FAILURE(-201) |
| MQ故障 | -300 ~ -399 | MQ_BROKER_FAILURE(-302) |

### Lua脚本规范

```lua
-- 必须添加注释说明
-- FIX: 使用 timestamp:random 作为 member，避免同毫秒去重

-- KEYS 和 ARGV 必须有文档说明
-- KEYS[1]: rate limit key
-- ARGV[1]: window size in milliseconds
-- ARGV[4]: random value for uniqueness

-- 返回值必须有说明
-- Returns:
-- -1: rate limited
-- >= 0: allowed, returns current count
```

## 测试规范

### 测试命名

```java
@Test
@DisplayName("E2E: Redis降级 + MQ降级同时触发 - 订单仍能正确创建")
void doubleDegradation_orderStillCreated() {
    // 测试内容
}
```

### 测试场景覆盖

必须覆盖以下场景：
1. 正常流程测试
2. 单降级测试（Redis/MQ）
3. 双降级测试（Redis + MQ）
4. 并发正确性测试
5. 库存守恒验证

## 配置规范

### 测试环境

```yaml
seckill:
  production-mode: false  # 测试模式
  db:
    user: root            # 测试默认值
    password: 123456      # 测试默认值
```

### 生产环境

```yaml
seckill:
  production-mode: true   # 生产模式
  db:
    user: ${SECKILL_DB_USER}    # 必须设置环境变量
    password: ${SECKILL_DB_PASSWORD}
```

## 监控指标

### Prometheus 端点

```
GET /actuator/prometheus
```

### 关键指标

| 指标名 | 说明 | 类型 |
|--------|------|------|
| `seckill_order_created_total` | 订单创建数 | Counter |
| `seckill_order_create_latency_seconds` | 订单创建延迟 | Timer (P99) |
| `seckill_transaction_timeout_total` | 事务超时数 | Counter |
| `seckill_stock_rollback_total` | 库存回补数 | Counter |
| `seckill_anti_scraping_ip_blocked` | IP封禁数 | Counter |

## 注意事项

### 不要做的事

1. **不要在生产代码中使用硬编码密码**
   - 使用环境变量或配置中心

2. **不要使用固定窗口计数器做限流**
   - 使用滑动窗口避免边界突刺

3. **不要用纯时间戳做ZSET成员**
   - 使用 `timestamp:random` 避免同毫秒去重

4. **不要在异常处理中混用业务错误和系统错误**
   - 业务错误 → `SeckillResponse.fail()`
   - 系统错误 → `SeckillResponse.systemError()`

### 必须做的事

1. **每个优化必须有测试覆盖**
   - 单测验证正确性
   - 并发测试验证线程安全

2. **Lua脚本必须有详细注释**
   - KEYS/ARGV 说明
   - 返回值说明
   - FIX注释说明修改原因

3. **降级逻辑必须有端到端测试**
   - 单降级场景
   - 双降级场景

4. **Commit message 必须遵循规范**
   - P级别标注
   - Changes列表
   - Configuration说明（如有）