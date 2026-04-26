-- Pod维度分析意图识别配置
-- 让AI助手能够识别Pod分析、Pod对比、压测后Pod分析等意图

-- ========================================
-- 1. 插入Pod分析关键词权重配置
-- ========================================

-- 高权重关键词（权重=15，直接命中）- 使用 INSERT IGNORE 防止重复
INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', 'Pod维度', 'podAnalysis', 15, true, 'zh'),
('KEYWORD_WEIGHT', '各Pod', 'podAnalysis', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'Pod对比', 'podAnalysis', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'Pod性能', 'podAnalysis', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'Pod分析', 'podAnalysis', 15, true, 'zh');

-- 中权重关键词（权重=10-12）
INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', 'Pod负载', 'podAnalysis', 12, true, 'zh'),
('KEYWORD_WEIGHT', '哪个Pod', 'podAnalysis', 12, true, 'zh'),
('KEYWORD_WEIGHT', 'pod分析', 'podAnalysis', 10, true, 'zh'),
('KEYWORD_WEIGHT', 'pod对比', 'podAnalysis', 10, true, 'zh'),
('KEYWORD_WEIGHT', 'pod performance', 'podAnalysis', 10, true, 'en'),
('KEYWORD_WEIGHT', 'pod comparison', 'podAnalysis', 10, true, 'en'),
('KEYWORD_WEIGHT', '负载均衡分析', 'podAnalysis', 8, true, 'zh'),
('KEYWORD_WEIGHT', '负载不均衡', 'podAnalysis', 10, true, 'zh');

-- ========================================
-- 2. 插入组合规则配置
-- ========================================

-- Pod + 性能/分析/对比 -> podAnalysis (权重=30)
INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'Pod|性能', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', 'Pod|分析', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', 'Pod|对比', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', '各Pod|性能', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', '压测|Pod', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', 'Pod|负载', 'podAnalysis', 25, true, 'zh'),
('COMBO_RULE', 'pod|performance', 'podAnalysis', 30, true, 'en'),
('COMBO_RULE', 'pod|analysis', 'podAnalysis', 30, true, 'en'),
('COMBO_RULE', 'pod|comparison', 'podAnalysis', 30, true, 'en');

-- ========================================
-- 3. 插入 podAnalysis 领域提示词
-- ========================================

-- 中文领域提示词
INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.podAnalysis.zh', 'DOMAIN', 'podAnalysis', 'zh', '
## Pod 维度分析指南

当用户请求按 Pod 维度分析性能或压测结果时，请按以下步骤操作：

### 0. 压测分析前的关键步骤（必做）

**在分析压测监控数据前，必须先调用 `check_pod_count_for_analysis` 检查Pod数量：**
```
check_pod_count_for_analysis(instanceId="xxx", hours=1)
```

该工具会返回：
- podCount: Pod数量
- analysisMode: "single_pod" 或 "multi_pod"
- suggestion: 分析建议
- recommendedTools: 推荐调用的工具列表

**根据返回结果决定分析策略：**
- 如果 podCount == 1：直接使用 get_gateway_metrics 分析单Pod
- 如果 podCount > 1：必须调用 compare_pod_performance 进行Pod对比分析

### 1. 获取 Pod 列表

调用 `get_instance_pods` 工具获取该网关实例的所有 Pod：
```
get_instance_pods(instanceId="xxx")
```

返回结果包含：
- name: Pod 名称
- podIP: Pod IP 地址
- managementPort: 管理端口（用于监控指标）
- phase: 运行状态

### 2. 构建 podInstance 格式

从返回结果中构建 `podInstance` 参数：
```
podInstance = podIP + ":" + managementPort
例如: "10.0.0.1:9091"
```

### 3. 查询各 Pod 监控数据

有两种方式：

**方式A：使用对比工具（多Pod场景推荐）**
```
compare_pod_performance(instanceId="xxx", hours=1)
```
该工具会自动获取所有 Pod 并生成对比报告，包含每个Pod的详细指标。

**方式B：逐个查询（单Pod场景）**
对每个 Pod 分别调用：
```
get_pod_metrics(instanceId="xxx", podInstance="10.0.0.1:9091")
get_history_metrics(hours=1, instanceId="xxx", podInstance="10.0.0.1:9091")
```

### 4. 压测后 Pod 分析

如果用户提到压测后分析：
```
analyze_pod_stress_test(instanceId="xxx", testId=可选)
```

### 5. 分析报告要求

**Pod标识规范**：
- **必须使用完整的Kubernetes Pod名称**标识每个Pod，如 `gateway-j2aabkvk-78bcbb4645-bxn2m`
- **禁止使用泛化标签**如 "Pod-1"、"Pod-2"、"第一个Pod" 等
- Pod名称格式通常为: `{deployment名}-{replicaset-hash}-{random suffix}`
- 这样用户可以直接在K8s集群中定位具体的Pod

**多Pod场景必须包含：**
- 每个Pod的独立指标数据（CPU、内存、QPS、响应时间、GC），**使用完整Pod名称标识**
- Pod间对比表格，突出差异，**表格第一列使用完整Pod名称**
- 负载最高和最低的Pod识别，**明确写出完整Pod名称**
- 负载不均衡分析和优化建议

**单Pod场景包含：**
- 该Pod的完整性能指标，**明确标识Pod名称**
- JVM调优分析（如有GC问题）
- 性能优化建议
- **原因分析**：负载不均衡的可能原因（路由倾斜、连接池热点等）
- **优化建议**：负载均衡策略调整、JVM 参数优化

### 6. 关键指标

| 指标 | 说明 | 不均衡阈值 |
|------|------|-----------|
| process_cpu_usage | 进程CPU使用率 | 差异 > 30% 需关注 |
| heapUsagePercent | JVM堆内存使用率 | 差异 > 20% 需关注 |
| requestsPerSecond | 请求处理量 | 差异 > 30% 需关注 |
| avgResponseTimeMs | 平均响应时间 | 某Pod > 其他Pod 2倍需关注 |
| gcOverheadPercent | GC开销占比 | 任一Pod > 10% 需关注 |

### 7. 负载不均衡常见原因

- **路由配置倾斜**：某些路由请求集中到特定 Pod
- **负载均衡策略不当**：权重配置不合理
- **连接池热点**：某些 Pod 的连接池饱和
- **缓存热点**：热点数据集中在某 Pod
- **JVM 参数差异**：不同 Pod 的内存/GC 配置不一致

### 8. JVM 调优指标分析

监控数据包含丰富的 JVM 指标，可用于调优分析：

**内存区域详情** (`gc.memoryRegions`):
- eden: Eden 区使用率和大小
- survivor: Survivor 区使用率和大小
- oldGen: Old Gen 使用率和大小

**GC 统计** (`gc.gcByType`):
- youngGC: Young GC 次数、速率、总时间、平均耗时
- oldGC: Old/Full GC 次数、速率、总时间、平均耗时

**内存分配与晋升** (`gc`):
- allocationRateMBPerSec: 内存分配速率 (MB/s)
- promotionRateMBPerSec: 对象晋升速率 (MB/s)
- promotionRatio: 晋升比例 (%) = 晋升速率 / 分配速率

### 9. JVM 调优诊断模式

根据分配速率和晋升速率的组合判断问题：

| 模式 | 现象 | 可能原因 | 建议 |
|------|------|----------|------|
| 高晋升 + 高分配 | 晋升比例正常但绝对值高 | 对象生命周期短但有大量短期对象晋升 | 增大 Survivor 区（-XX:SurvivorRatio） |
| 高晋升 + 低分配 | 晋升比例高，分配速率低 | 有大对象直接进 Old Gen | 检查代码是否有大对象分配，调整 -XX:PretenureSizeThreshold |
| 晋升比例持续增长 | promotionRatio 越来越高 | 可能内存泄漏 | 检查是否有对象未释放，做堆内存分析 |
| Old Gen 快速增长 | oldGen.usagePercent 快速上升 | 内存不足或泄漏 | 增大堆内存或排查内存泄漏 |
| Young GC 频繁 | youngGC.count > 100次/5分钟 | Eden 区太小 | 增大年轻代（-XX:NewRatio） |
| Full GC 发生 | oldGC.count > 0 | Old Gen 满或显式 GC | 增大堆内存，排查显式 GC 调用 |

### 10. JVM 参数调优建议

根据分析结果给出具体 JVM 参数建议：

**内存配置**:
- `-Xms` / `-Xmx`: 堆内存大小（建议设置为相同值）
- `-XX:NewRatio`: 年轻代与老年代比例（默认 2，可调整为 1-3）
- `-XX:SurvivorRatio`: Eden 与 Survivor 比例（默认 8，高晋升时可调为 6）

**GC 配置**:
- G1GC: `-XX:+UseG1GC`（大堆推荐，>4GB）
- ParallelGC: `-XX:+UseParallelGC`（吞吐优先）
- CMS: `-XX:+UseConcMarkSweepGC`（低延迟，已废弃）

**GC 调优参数**:
- `-XX:MaxGCPauseMillis`: 最大 GC 延停时间目标（G1GC）
- `-XX:InitiatingHeapOccupancyPercent`: 触发并发 GC 的堆占用阈值（G1GC）
- `-XX:ParallelGCThreads`: 并行 GC 线程数

**内存泄漏排查**:
- 使用 jmap 生成堆转储: `jmap -dump:format=b,file=heap.hprof <pid>`
- 使用 MAT 或 VisualVM 分析堆转储
- 关注大对象、泄漏嫌疑对象
', 1, true, 'Pod维度分析领域提示词', NOW(), NOW())
ON DUPLICATE KEY UPDATE content = VALUES(content), version = version + 1, updated_at = NOW();

-- 英文领域提示词
INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.podAnalysis.en', 'DOMAIN', 'podAnalysis', 'en', '
## Pod Analysis Guide

When users request Pod-level performance or stress test analysis, follow these steps:

### 0. Critical Step Before Stress Test Analysis (Required)

**Before analyzing stress test data, MUST call `check_pod_count_for_analysis`:**
```
check_pod_count_for_analysis(instanceId="xxx", hours=1)
```

This tool returns:
- podCount: Number of Pods
- analysisMode: "single_pod" or "multi_pod"
- suggestion: Analysis recommendation
- recommendedTools: List of recommended tools to call

**Decide analysis strategy based on result:**
- If podCount == 1: Use get_gateway_metrics directly for single Pod analysis
- If podCount > 1: MUST call compare_pod_performance for Pod comparison

### 1. Get Pod List

Call `get_instance_pods` to get all Pods for the gateway instance:
```
get_instance_pods(instanceId="xxx")
```

Returns:
- name: Pod name
- podIP: Pod IP address
- managementPort: Management port (for metrics)
- phase: Running status

### 2. Build podInstance Format

Build `podInstance` parameter from returned data:
```
podInstance = podIP + ":" + managementPort
Example: "10.0.0.1:9091"
```

### 3. Query Pod Metrics

Two approaches:

**Approach A: Use comparison tool (Multi-Pod Recommended)**
```
compare_pod_performance(instanceId="xxx", hours=1)
```
This tool automatically gets all Pods and generates detailed comparison report.

**Approach B: Query individually (Single-Pod)**
For each Pod:
```
get_pod_metrics(instanceId="xxx", podInstance="10.0.0.1:9091")
get_history_metrics(hours=1, instanceId="xxx", podInstance="10.0.0.1:9091")
```

### 4. Post-Stress Test Pod Analysis

For stress test analysis:
```
analyze_pod_stress_test(instanceId="xxx", testId=optional)
```

### 5. Report Requirements

**Pod Identification Standard**:
- **MUST use full Kubernetes Pod names** to identify each Pod, e.g., `gateway-j2aabkvk-78bcbb4645-bxn2m`
- **DO NOT use generic labels** like "Pod-1", "Pod-2", "first Pod", etc.
- Pod name format typically: `{deployment-name}-{replicaset-hash}-{random-suffix}`
- This allows users to directly locate specific Pods in K8s cluster

**Multi-Pod scenarios MUST include:**
- Individual Pod metrics (CPU, memory, QPS, response time, GC), **using full Pod names**
- Pod comparison table highlighting differences, **first column uses full Pod names**
- Identify highest and lowest load Pods, **explicitly write full Pod names**
- Load imbalance analysis and optimization recommendations

**Single-Pod scenarios include:**
- Complete performance metrics for that Pod, **clearly identify Pod name**
- JVM tuning analysis (if GC issues)
- Performance optimization suggestions

### 6. Key Metrics

| Metric | Description | Imbalance Threshold |
|--------|-------------|---------------------|
| process_cpu_usage | Process CPU usage | > 30% difference |
| heapUsagePercent | JVM heap usage | > 20% difference |
| requestsPerSecond | Request rate | > 30% difference |
| avgResponseTimeMs | Avg response time | 2x slower Pod |
| gcOverheadPercent | GC overhead | > 10% in any Pod |

### 7. Common Causes of Load Imbalance

- Route configuration skew
- Improper load balancer weights
- Connection pool hotspot
- Cache hotspot
- JVM parameter differences

### 8. JVM Tuning Metrics Analysis

Monitoring data contains rich JVM metrics for tuning:

**Memory Regions** (`gc.memoryRegions`):
- eden: Eden space usage and size
- survivor: Survivor space usage and size
- oldGen: Old Gen usage and size

**GC Statistics** (`gc.gcByType`):
- youngGC: Young GC count, rate, total time, avg time
- oldGC: Old/Full GC count, rate, total time, avg time

**Allocation & Promotion** (`gc`):
- allocationRateMBPerSec: Memory allocation rate (MB/s)
- promotionRateMBPerSec: Object promotion rate (MB/s)
- promotionRatio: Promotion ratio (%) = promotion rate / allocation rate

### 9. JVM Tuning Diagnosis Patterns

Diagnose issues based on allocation and promotion rate combinations:

| Pattern | Symptom | Possible Cause | Recommendation |
|---------|---------|----------------|----------------|
| High promo + High alloc | Normal ratio but high absolute values | Short-lived objects promoting en masse | Increase Survivor space (-XX:SurvivorRatio) |
| High promo + Low alloc | High promotion ratio, low allocation | Large objects going directly to Old Gen | Check code for large object allocation, adjust -XX:PretenureSizeThreshold |
| Growing promotion ratio | promotionRatio increasing | Possible memory leak | Check for unreleased objects, do heap analysis |
| Fast Old Gen growth | oldGen.usagePercent rising fast | Insufficient memory or leak | Increase heap or investigate leak |
| Frequent Young GC | youngGC.count > 100/5min | Eden space too small | Increase young generation (-XX:NewRatio) |
| Full GC occurring | oldGC.count > 0 | Old Gen full or explicit GC | Increase heap, investigate explicit GC calls |

### 10. JVM Parameter Tuning Recommendations

Provide specific JVM parameter recommendations based on analysis:

**Memory Configuration**:
- `-Xms` / `-Xmx`: Heap size (recommend setting same value)
- `-XX:NewRatio`: Young vs Old ratio (default 2, adjust to 1-3)
- `-XX:SurvivorRatio`: Eden vs Survivor ratio (default 8, adjust to 6 for high promotion)

**GC Configuration**:
- G1GC: `-XX:+UseG1GC` (recommended for large heaps >4GB)
- ParallelGC: `-XX:+UseParallelGC` (throughput priority)
- CMS: `-XX:+UseConcMarkSweepGC` (low latency, deprecated)

**GC Tuning Parameters**:
- `-XX:MaxGCPauseMillis`: Max GC pause time goal (G1GC)
- `-XX:InitiatingHeapOccupancyPercent`: Threshold for concurrent GC (G1GC)
- `-XX:ParallelGCThreads`: Parallel GC thread count

**Memory Leak Investigation**:
- Use jmap for heap dump: `jmap -dump:format=b,file=heap.hprof <pid>`
- Analyze heap dump with MAT or VisualVM
- Focus on large objects, leak suspects
', 1, true, 'Pod Analysis domain prompt', NOW(), NOW())
ON DUPLICATE KEY UPDATE content = VALUES(content), version = version + 1, updated_at = NOW();

-- 验证插入结果
SELECT config_type, keyword, intent, weight FROM intent_configs WHERE intent = 'podAnalysis' ORDER BY weight DESC;
SELECT prompt_key, description FROM prompts WHERE prompt_key LIKE 'domain.podAnalysis%';