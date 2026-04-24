-- 更新压测分析提示词，添加依赖项说明

-- 更新中文版本
UPDATE prompts SET content = '你是一个专业的性能测试工程师和Java应用运维专家。请根据以下压力测试结果和服务器监控数据，给出全面的性能分析报告。

请用{langName}回答。

{stressTestData}

{metricsData}

## ⚠️ 分析要求（强制执行）

**【依赖项说明 - 重要】**
分析时必须区分核心性能和扩展功能性能：
- **数据库**：仅用于 gateway-admin 配置存储，**不影响网关运行时性能**。网关从 Nacos/Consul 获取配置，数据库问题只影响管理后台。
- **Redis**：仅用于分布式限流，如未配置或不可用，网关自动使用本地限流，**不影响核心路由转发**。
- **Nacos/Consul**：服务发现和配置中心，是网关核心依赖，如不可用会影响路由。

**分析原则**：
- 网关核心性能 = 路由转发 + 过滤器链 + 负载均衡
- 数据库/Redis 问题属于"扩展功能性能"，不应作为核心瓶颈判定依据

请输出详细的分析报告，包含：

## 1. 测试概览
- 测试配置和基本结果摘要
- 成功率和错误率评估

## 2. 响应时间分析
- 平均响应时间、P50/P90/P95/P99 分布分析
- 响应时间是否在合理范围内
- 是否存在长尾延迟问题

## 3. 吞吐量分析
- 实际 QPS 与并发用户数的关系
- 吞吐量是否达到预期
- 是否存在吞吐量瓶颈

## 4. 服务器资源分析
- CPU、内存、GC、线程等资源在压测期间的表现
- 资源使用率是否合理
- 是否存在资源瓶颈（CPU饱和、内存泄漏、GC频繁等）

## 5. 问题诊断
- 发现的性能问题和异常
- 可能的根本原因分析
- 错误请求的可能原因

## 6. 性能评估
- 系统整体性能评分（1-10分）
- 当前配置下的承载能力评估
- 与行业基准的对比

## 7. 优化建议
- 针对发现的性能瓶颈提出具体优化建议
- JVM调优建议
- 网关配置优化建议
- 扩容或架构调整建议

请用Markdown格式输出，分析要专业、详细、有数据支撑。如果某些监控数据缺失，请基于已有数据进行分析并标注。', updated_at = NOW() WHERE prompt_key = 'task.stressTestAnalysis.zh';

-- 更新英文版本
UPDATE prompts SET content = 'You are a professional performance testing engineer and Java application operations expert. Please provide a comprehensive performance analysis report based on the following stress test results and server monitoring data.

Please answer in {langName}.

{stressTestData}

{metricsData}

## ⚠️ Analysis Requirements (Mandatory)

**【Dependency Explanation - Important】**
When analyzing, distinguish between core performance and extended functionality performance:
- **Database**: Only used for gateway-admin config storage, **does NOT affect gateway runtime performance**. Gateway gets config from Nacos/Consul, database issues only affect admin console.
- **Redis**: Only used for distributed rate limiting. If not configured or unavailable, gateway automatically uses local rate limiting, **does NOT affect core route forwarding**.
- **Nacos/Consul**: Service discovery and config center, is gateway core dependency, if unavailable will affect routing.

**Analysis Principle**:
- Gateway core performance = route forwarding + filter chain + load balancing
- Database/Redis issues belong to "extended functionality performance", should NOT be used as core bottleneck judgment basis

Please output a detailed analysis report containing:

## 1. Test Overview
- Test configuration and basic result summary
- Success rate and error rate evaluation

## 2. Response Time Analysis
- Average response time, P50/P90/P95/P99 distribution analysis
- Whether response time is within reasonable range
- Whether there are tail latency issues

## 3. Throughput Analysis
- Relationship between actual QPS and concurrent users
- Whether throughput meets expectations
- Whether there are throughput bottlenecks

## 4. Server Resource Analysis
- CPU, memory, GC, threads performance during stress test
- Whether resource utilization is reasonable
- Whether there are resource bottlenecks

## 5. Problem Diagnosis
- Performance issues and anomalies found
- Possible root cause analysis
- Possible causes of error requests

## 6. Performance Evaluation
- Overall system performance score (1-10)
- Capacity evaluation under current configuration
- Comparison with industry benchmarks

## 7. Optimization Recommendations
- Specific optimization suggestions for performance bottlenecks
- JVM tuning recommendations
- Gateway configuration optimization
- Scaling or architecture adjustment suggestions

Please output in Markdown format. Analysis should be professional, detailed, and data-supported. If some monitoring data is missing, please analyze based on available data and annotate.', updated_at = NOW() WHERE prompt_key = 'task.stressTestAnalysis.en';