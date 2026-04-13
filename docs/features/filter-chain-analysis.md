# Filter Chain Analysis

> Filter Chain 分析功能追踪每个 Filter 的执行统计，包括 P50/P95/P99 分位数、慢请求检测、时间占比分析，帮助精准定位性能瓶颈。

---

## Overview

追踪每个 Filter 的执行情况：

```
┌─────────────────────────────────────────────┐
│         FILTER CHAIN TRACKING                │
│                                              │
│   Request Processing                         │
│          │                                   │
│          ▼                                   │
│   ┌─────────────────┐                        │
│   │ Filter Execution│                        │
│   │ (Each Filter)   │                        │
│   └────────┬────────┘                        │
│            │                                 │
│            │ Record:                         │
│            │ - filterName                    │
│            │ - order                         │
│            │ - duration (nanos)              │
│            │ - success/error                 │
│            ▼                                 │
│   ┌─────────────────┐                        │
│   │ Statistics Store│                        │
│   │ (ConcurrentHashMap)                      │
│   │ + Sliding Window (100)                   │
│   │ for Percentile Calc                      │
│   └─────────────────┘                        │
└─────────────────────────────────────────────┘
```

---

## Statistics per Filter

每个 Filter 维护以下统计指标：

| Metric | Description | Precision |
|--------|-------------|-----------|
| `totalCount` | 总执行次数 | 原子计数器 |
| `successCount` | 成功次数 | 原子计数器 |
| `failureCount` | 失败次数 | 原子计数器 |
| `successRate` | 成功率百分比 | 实时计算 |
| `avgDurationMicros` | 平均执行时间（微秒） | 原子累加 |
| `avgDurationMs` | 平均执行时间（毫秒） | 转换 |
| `maxDurationMicros` | 最大执行时间 | CAS 更新 |
| `minDurationMicros` | 最小执行时间 | CAS 更新 |

### Percentile Statistics (滑动窗口)

| Metric | Description |
|--------|-------------|
| `p50Micros` / `p50Ms` | 中位数响应时间 |
| `p95Micros` / `p95Ms` | 95% 请求响应时间 |
| `p99Micros` / `p99Ms` | 99% 请求响应时间 |

**滑动窗口机制**：保留最近 100 次执行时间，用于分位数计算，避免历史数据干扰。

---

## Slow Request Detection

### Threshold Configuration

- **默认阈值**: 1000ms（1 秒）
- **可动态调整**: 通过 API 或 AI Copilot 工具修改
- **触发告警**: 单个 Filter 或整个链超过阈值时记录

### Slow Request Tracking

当请求总耗时超过阈值时：
1. 记录到慢请求列表
2. 增加慢请求计数器
3. 保留完整 Filter 执行明细

---

## Trace Record Structure

单个请求的 Filter Chain 记录：

```json
{
  "traceId": "abc-123",
  "createdAt": 1705309800000,
  "totalDurationMs": 150,
  "successCount": 8,
  "failureCount": 2,
  "filterCount": 10,
  "executions": [
    {
      "filter": "AuthenticationGlobalFilter",
      "order": -250,
      "durationMs": 5,
      "durationMicros": 5200,
      "success": true,
      "error": null
    },
    {
      "filter": "RateLimiterFilter",
      "order": -200,
      "durationMs": 2,
      "durationMicros": 2100,
      "success": false,
      "error": "Rate limit exceeded"
    }
  ],
  "timeBreakdown": [
    {
      "filter": "AuthenticationGlobalFilter",
      "durationMs": 5,
      "percentage": "3.3%"
    },
    {
      "filter": "LoadBalancerFilter",
      "durationMs": 45,
      "percentage": "30.0%"
    }
  ]
}
```

**timeBreakdown** 字段显示每个 Filter 占总时间的百分比，快速定位瓶颈。

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/internal/filter-chain/stats` | Filter 统计摘要 |
| `GET` | `/internal/filter-chain/slowest-filters` | 最慢 Filter 排名 |
| `GET` | `/internal/filter-chain/slow` | 慢请求列表 |
| `GET` | `/internal/filter-chain/trace/{traceId}` | 单个 trace 详情 |
| `POST` | `/internal/filter-chain/threshold` | 设置慢请求阈值 |
| `DELETE` | `/internal/filter-chain/stats` | 清除统计 |

### Get Summary Report

```bash
curl http://localhost:8080/internal/filter-chain/stats
```

Response:
```json
{
  "totalRecords": 1000,
  "filterCount": 15,
  "slowRequestCount": 23,
  "slowThresholdMs": 1000,
  "filters": [
    {
      "filterName": "AuthenticationGlobalFilter",
      "order": -250,
      "totalCount": 10000,
      "successCount": 9900,
      "failureCount": 100,
      "successRate": "99.00%",
      "avgDurationMs": "5.200",
      "p50Ms": "4.500",
      "p95Ms": "8.200",
      "p99Ms": "15.300"
    }
  ],
  "slowestFilters": [
    {
      "filterName": "LoadBalancerFilter",
      "avgDurationMs": "45.200"
    }
  ]
}
```

### Get Slowest Filters Ranking

```bash
curl http://localhost:8080/internal/filter-chain/slowest-filters?limit=10
```

按平均耗时排序，快速定位瓶颈 Filter。

---

## Performance Optimizations

实现采用多项优化：

| Optimization | Description |
|--------------|-------------|
| **ConcurrentLinkedDeque** | 高并发队列存储记录 |
| **Atomic Counters** | 原子计数器减少锁竞争 |
| **Sliding Window** | 滑动窗口分位数计算，避免全量排序 |
| **Rolling Window** | 最多保留 1000 条记录，自动淘汰旧记录 |

---

## AI Copilot Integration

AI Copilot 可调用以下工具分析 Filter Chain：

| Tool | Capability |
|------|------------|
| `get_filter_chain_stats` | 获取统计摘要、慢请求数量 |
| `get_slowest_filters` | 获取最慢 Filter 排名 |
| `get_slow_requests` | 获取慢请求列表及 traceId |
| `get_filter_trace_detail` | 获取单个 trace 的完整执行明细 |
| `set_slow_threshold` | 动态调整慢请求阈值（需确认） |
| `suggest_filter_reorder` | 分析并给出 Filter 重排序建议 |

### suggest_filter_reorder 输出

```
分析内容：
- Filter 执行耗时排名
- 可提前执行的 Filter（认证/限流类）
- 必须在路由匹配后执行的 Filter（负载均衡/重试类）
- 性能趋势对比（当前时间段 vs 前一时间段）
- 预期性能提升估算
```

---

## Use Cases

| Use Case | How to Use |
|----------|------------|
| **性能调优** | 查看最慢 Filter 排名，优化耗时最高的 Filter |
| **故障排查** | 查看失败率高 Filter，结合 error 信息定位问题 |
| **容量规划** | 分析 P95/P99 响应时间，评估系统容量 |
| **优化验证** | 对比优化前后的统计数据，验证效果 |
| **Filter 顺序优化** | 使用 `suggest_filter_reorder` 工具获取建议 |

---

## Best Practices

1. **定期分析**: 监控 P95/P99 分位数而非只看平均值
2. **关注异常**: 失败率 > 5% 的 Filter 需调查
3. **时间占比**: 关注 timeBreakdown 中占比 > 30% 的 Filter
4. **阈值调整**: 根据业务响应时间要求调整 slowThresholdMs
5. **滚动窗口**: 保留 1000 条记录足够分析，无需增加

---

## Related Features

- [Request Tracing](request-tracing.md) - 请求追踪
- [Request Replay](request-replay.md) - 请求重放调试
- [Monitoring & Alerts](monitoring-alerts.md) - 监控
- [AI Copilot](ai-copilot.md) - AI 工具调用分析
- [ARCHITECTURE.md](../ARCHITECTURE.md) - Filter Chain 架构