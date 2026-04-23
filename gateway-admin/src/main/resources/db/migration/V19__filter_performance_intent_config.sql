-- Filter性能分析意图识别配置
-- 让AI助手能够识别Filter链、耗时分析相关问题，直接调用正确的工具

-- ========================================
-- 1. 插入Filter性能分析关键词权重配置
-- ========================================

-- 高权重关键词（权重=15，直接命中）
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', 'Filter链', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', '过滤器链', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'filter chain', 'performance', 15, true, 'en'),
('KEYWORD_WEIGHT', 'Filter耗时', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', '过滤器耗时', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'filter duration', 'performance', 15, true, 'en'),
('KEYWORD_WEIGHT', '哪个Filter', 'performance', 12, true, 'zh'),
('KEYWORD_WEIGHT', '最慢的Filter', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'slowest filter', 'performance', 15, true, 'en'),
('KEYWORD_WEIGHT', 'Filter性能', 'performance', 12, true, 'zh'),
('KEYWORD_WEIGHT', '过滤器性能', 'performance', 12, true, 'zh'),
('KEYWORD_WEIGHT', 'filter performance', 'performance', 12, true, 'en');

-- 中权重关键词（权重=8）
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', '耗时分析', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', '性能分析', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', '响应慢', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', '请求慢', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', '慢请求', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', 'performance analysis', 'performance', 8, true, 'en'),
('KEYWORD_WEIGHT', 'slow response', 'performance', 8, true, 'en'),
('KEYWORD_WEIGHT', 'slow request', 'performance', 8, true, 'en'),
('KEYWORD_WEIGHT', 'latency', 'performance', 6, true, 'en'),
('KEYWORD_WEIGHT', '响应时间', 'performance', 6, true, 'zh'),
('KEYWORD_WEIGHT', '执行时间', 'performance', 6, true, 'zh');

-- ========================================
-- 2. 插入组合规则配置
-- ========================================

-- Filter + 分析/耗时 → performance (权重=20)
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'Filter|分析', 'performance', 20, true, 'zh'),
('COMBO_RULE', 'Filter|耗时', 'performance', 20, true, 'zh'),
('COMBO_RULE', 'Filter|慢', 'performance', 20, true, 'zh'),
('COMBO_RULE', 'Filter|时间', 'performance', 18, true, 'zh'),
('COMBO_RULE', '过滤器|分析', 'performance', 20, true, 'zh'),
('COMBO_RULE', '过滤器|耗时', 'performance', 20, true, 'zh'),
('COMBO_RULE', 'filter|analysis', 'performance', 20, true, 'en'),
('COMBO_RULE', 'filter|duration', 'performance', 20, true, 'en'),
('COMBO_RULE', 'filter|slow', 'performance', 20, true, 'en'),
('COMBO_RULE', 'filter|time', 'performance', 18, true, 'en');

-- 分析 + 数据/统计 → performance (权重=15)
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', '分析|数据', 'performance', 15, true, 'zh'),
('COMBO_RULE', '分析|统计', 'performance', 15, true, 'zh'),
('COMBO_RULE', '分析|指标', 'performance', 15, true, 'zh'),
('COMBO_RULE', 'analyze|data', 'performance', 15, true, 'en'),
('COMBO_RULE', 'analyze|metrics', 'performance', 15, true, 'en');

-- ========================================
-- 3. 更新 performance 领域提示词（添加Filter工具指导）
-- ========================================

-- 更新中文 performance 领域提示词，添加Filter分析工具指导
UPDATE prompts SET content = '
## 性能优化指南

当用户询问性能相关问题时，请使用以下工具获取数据：

### 优先工具调用顺序

**对于 Filter 链性能分析问题**（如"哪个Filter耗时最长"、"分析Filter链"）：

1. **`get_filter_chain_stats`** - 获取 Filter 链整体统计（必先调用）
2. **`get_slowest_filters`** - 获取最慢的 Filter 排名（关键工具）
3. **`analyze_filter_anomaly`** - AI 异常检测分析（可选，深度分析）

**对于整体性能分析问题**：

1. **`get_gateway_metrics`** - 获取实时监控指标
2. **`get_history_metrics`** - 获取历史指标趋势
3. **`run_quick_diagnostic`** - 快速系统诊断

### 性能指标解读

| 指标 | 正常值 | 关注阈值 | 危险阈值 |
|------|--------|----------|----------|
| avgResponseTimeMs | < 100ms | > 500ms | > 2000ms |
| errorRatePercent | < 0.1% | > 1% | > 5% |
| heapUsagePercent | < 70% | > 80% | > 90% |
| gcOverheadPercent | < 1% | > 5% | > 10% |
| processCpuUsage | < 50% | > 70% | > 90% |

### Filter 性能分析要点

- **selfTimeMs**：Filter 自身执行时间（不含下游调用），是关键性能指标
- **totalTimeMs**：累计时间（含下游），用于请求链路分析
- **P95/P99**：百分位耗时，反映长尾请求性能

### 性能优化建议方向

1. **Filter 优化**：调整 Filter 执行顺序、优化耗时 Filter 实现
2. **JVM 调优**：堆内存、GC 算法、线程池配置
3. **路由优化**：合并相似路由、减少复杂断言
4. **限流调优**：QPS 阈值、burst 容量、keyResolver 选择

', updated_at = NOW() WHERE prompt_key = 'domain.performance.zh';

-- 更新英文 performance 领域提示词
UPDATE prompts SET content = '
## Performance Optimization Guide

When user asks about performance, use these tools to get data:

### Priority Tool Call Order

**For Filter Chain Performance Analysis** (e.g., "which filter is slowest", "analyze filter chain"):

1. **`get_filter_chain_stats`** - Get overall filter chain statistics (must call first)
2. **`get_slowest_filters`** - Get slowest filter ranking (key tool)
3. **`analyze_filter_anomaly`** - AI anomaly detection analysis (optional, deep analysis)

**For Overall Performance Analysis**:

1. **`get_gateway_metrics`** - Get real-time monitoring metrics
2. **`get_history_metrics`** - Get historical metric trends
3. **`run_quick_diagnostic`** - Quick system diagnostics

### Performance Metrics Interpretation

| Metric | Normal | Attention | Critical |
|--------|--------|-----------|----------|
| avgResponseTimeMs | < 100ms | > 500ms | > 2000ms |
| errorRatePercent | < 0.1% | > 1% | > 5% |
| heapUsagePercent | < 70% | > 80% | > 90% |
| gcOverheadPercent | < 1% | > 5% | > 10% |
| processCpuUsage | < 50% | > 70% | > 90% |

### Filter Performance Analysis Key Points

- **selfTimeMs**: Filter own execution time (excluding downstream calls) - key performance metric
- **totalTimeMs**: Cumulative time (including downstream) - for request chain analysis
- **P95/P99**: Percentile duration, reflects tail request performance

### Performance Optimization Directions

1. **Filter Optimization**: Adjust filter order, optimize slow filter implementations
2. **JVM Tuning**: Heap memory, GC algorithm, thread pool configuration
3. **Route Optimization**: Merge similar routes, reduce complex predicates
4. **Rate Limit Tuning**: QPS threshold, burst capacity, keyResolver selection

', updated_at = NOW() WHERE prompt_key = 'domain.performance.en';

-- 验证插入结果
SELECT config_type, keyword, intent, weight FROM intent_configs WHERE intent = 'performance' ORDER BY weight DESC;