-- 加强Filter性能分析的提示词指导
-- 让AI直接调用Filter工具，跳过不必要的list_instances等调用

-- ========================================
-- 更新中文 performance 领域提示词（加强版）
-- ========================================

UPDATE prompts SET content = '
## 性能优化指南

### ⚠️ 重要：工具调用决策规则

**判断用户问题类型，直接调用对应工具，不要先调用其他工具！**

**如果用户问题包含以下关键词，立即调用 Filter 工具：**
- Filter、过滤器、Filter链、耗时、最慢、性能分析

**立即调用的工具（按顺序）：**
1. `get_filter_chain_stats` - 获取实例ID对应的Filter统计
2. `get_slowest_filters` - 获取最慢Filter排名

**⚠️ 不要先调用这些工具（浪费时间）：**
- ❌ `list_instances` - 不需要先获取实例列表，用户已指定或系统有默认实例
- ❌ `get_gateway_metrics` - Filter分析不需要整体指标，除非用户问整体性能

---

### Filter 链性能分析（专属工具）

当用户问"分析Filter链"、"哪个Filter耗时最长"等：

```
工具调用示例：
1. get_filter_chain_stats(instanceId="xxx")
2. get_slowest_filters(instanceId="xxx", limit=10)
```

**关键指标解读：**
| 指标 | 说明 | 关注阈值 |
|------|------|----------|
| selfTimeMs | Filter自身耗时（不含下游） | > 50ms 需优化 |
| avgSelfTimeMs | 平均自身耗时 | > 10ms 需关注 |
| totalCount | 执行次数 | 高频+高耗时=瓶颈 |

---

### 整体性能分析（非Filter问题）

当用户问"系统性能"、"响应慢"等（不含Filter关键词）：

```
工具调用顺序：
1. get_gateway_metrics()
2. get_history_metrics(hours=1)
3. run_quick_diagnostic()
```

---

### 性能指标解读

| 指标 | 正常值 | 关注阈值 | 危险阈值 |
|------|--------|----------|----------|
| avgResponseTimeMs | < 100ms | > 500ms | > 2000ms |
| errorRatePercent | < 0.1% | > 1% | > 5% |
| heapUsagePercent | < 70% | > 80% | > 90% |
| gcOverheadPercent | < 1% | > 5% | > 10% |

---

### 性能优化建议

1. **Filter 优化**：调整顺序、优化实现、考虑异步化
2. **JVM 调优**：堆内存、GC算法、线程池
3. **路由优化**：合并路由、简化断言
4. **限流调优**：QPS阈值、burst容量

', updated_at = NOW() WHERE prompt_key = 'domain.performance.zh';

-- ========================================
-- 更新英文 performance 领域提示词（加强版）
-- ========================================

UPDATE prompts SET content = '
## Performance Optimization Guide

### ⚠️ IMPORTANT: Tool Call Decision Rules

**Identify user question type and call the corresponding tools DIRECTLY. Do NOT call other tools first!**

**If user question contains these keywords, call Filter tools IMMEDIATELY:**
- Filter, filter chain, duration, slowest, performance analysis

**Tools to call IMMEDIATELY (in order):**
1. `get_filter_chain_stats` - Get Filter statistics for the instance
2. `get_slowest_filters` - Get slowest Filter ranking

**⚠️ DO NOT call these tools first (wastes time):**
- ❌ `list_instances` - Not needed, user specified instance or system has default
- ❌ `get_gateway_metrics` - Filter analysis doesn''t need overall metrics unless user asks about overall performance

---

### Filter Chain Performance Analysis (Specialized Tools)

When user asks "analyze filter chain", "which filter is slowest":

```
Tool call example:
1. get_filter_chain_stats(instanceId="xxx")
2. get_slowest_filters(instanceId="xxx", limit=10)
```

**Key Metrics:**
| Metric | Description | Attention Threshold |
|--------|-------------|---------------------|
| selfTimeMs | Filter own time (no downstream) | > 50ms needs optimization |
| avgSelfTimeMs | Average own time | > 10ms needs attention |
| totalCount | Execution count | High freq + high time = bottleneck |

---

### Overall Performance Analysis (Non-Filter Questions)

When user asks "system performance", "slow response" (no Filter keywords):

```
Tool call order:
1. get_gateway_metrics()
2. get_history_metrics(hours=1)
3. run_quick_diagnostic()
```

---

### Performance Metrics Reference

| Metric | Normal | Attention | Critical |
|--------|--------|-----------|----------|
| avgResponseTimeMs | < 100ms | > 500ms | > 2000ms |
| errorRatePercent | < 0.1% | > 1% | > 5% |
| heapUsagePercent | < 70% | > 80% | > 90% |
| gcOverheadPercent | < 1% | > 5% | > 10% |

---

### Optimization Recommendations

1. **Filter Optimization**: Adjust order, optimize implementation, async processing
2. **JVM Tuning**: Heap memory, GC algorithm, thread pool
3. **Route Optimization**: Merge routes, simplify predicates
4. **Rate Limit Tuning**: QPS threshold, burst capacity

', updated_at = NOW() WHERE prompt_key = 'domain.performance.en';

-- 验证更新
SELECT prompt_key, LEFT(content, 100) as content_preview FROM prompts WHERE prompt_key LIKE 'domain.performance%';