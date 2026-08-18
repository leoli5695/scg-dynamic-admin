package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.prompt.PromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI 分析提示词构建器.
 * 从 AiAnalysisService 拆分而来，负责构建用于 AI 分析的提示词.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPromptBuilder {

    private final PromptService promptService;

    /**
     * 构建时间段分析prompt
     */
    String buildTimeRangeAnalysisPrompt(String metricsData, long startTime, long endTime, String language) {
        String langName = "zh".equals(language) ? "中文" : "English";
        long durationMinutes = (endTime - startTime) / 60;

        return String.format("""
                你是一个专业的Java应用运维专家、性能分析师和JVM调优专家。请分析以下Gateway网关在指定时间段内的监控数据。
                
                这段时间可能进行了压力测试或遇到了性能问题，请仔细分析各项指标的峰值、平均值和变化趋势。
                
                请用%s回答。
                
                %s
                
                请输出详细的分析报告，包含：
                
                ## 1. 时间段概览
                - 分析时间段的基本情况
                
                ## 2. 性能分析
                - 各指标（CPU、内存、请求、响应时间）的峰值和平均值分析
                - 是否存在性能瓶颈
                - 指标之间的关联性（如CPU和请求量的关系）
                
                ## 3. JVM内存与GC分析（重点）
                - **内存区域分析**：分析Eden、Survivor、Old区的使用情况和变化趋势
                - **GC行为分析**：
                  - Young GC频率是否合理（正常<10次/分钟）
                  - Full GC是否发生（正常应极少或无）
                  - GC平均耗时是否正常（Young GC正常<50ms，Full GC正常<500ms）
                - **GC开销评估**：GC时间占比是否合理（正常<5%，警告5-10%，严重>10%）
                - **内存分配速率**：对象创建速度是否过高
                - **晋升分析**：Old区内存增长是否异常
                
                ## 4. GC调优建议（如有问题）
                根据数据分析结果，给出具体的JVM参数调优建议：
                - 如果Young GC频繁：建议增大Eden区（-XX:NewSize或调整-XX:SurvivorRatio）
                - 如果Full GC频繁：建议增大Old区或整个堆（-Xmx），检查是否有大对象直接晋升
                - 如果GC开销过高：建议增大堆内存或切换GC算法（如G1GC）
                - 如果Old区持续增长：建议排查内存泄漏或长生命周期对象
                
                ## 5. 问题诊断
                - 发现的异常或问题
                - 可能的根本原因分析
                - 对比正常情况，指出异常点
                
                ## 6. 性能评估
                - 系统在此时间段的整体表现评分（1-10分）
                - 吞吐量评估
                - 资源利用率评估
                - JVM健康度评估
                
                ## 7. 优化建议
                - 针对发现的问题提出具体的优化建议
                - JVM参数调整建议（如有GC问题）
                - 配置调整建议
                - 容量规划建议
                
                请用Markdown格式输出，要专业、详细、有数据支撑。GC调优建议要具体，包含具体的JVM参数值。
                """, langName, metricsData);
    }

    /**
     * 构建压力测试分析 prompt
     * Uses PromptService for dynamic configuration, falls back to inline defaults.
     */
    String buildStressTestAnalysisPrompt(String stressTestData, String metricsData, String language) {
        // 尝试从 PromptService 获取模板
        String templateKey = "task.stressTestAnalysis." + language;
        String template = promptService.getPrompt(templateKey);

        if (template != null && !template.isEmpty()) {
            // 使用动态模板
            String langName = "zh".equals(language) ? "中文" : "English";
            return template
                    .replace("{langName}", langName)
                    .replace("{stressTestData}", stressTestData)
                    .replace("{metricsData}", metricsData.isEmpty() ? "【服务器监控数据】\n暂无 Prometheus 监控数据" : metricsData);
        }

        // 默认内联提示词（兜底）- 与数据库模板保持完全一致
        String langName = "zh".equals(language) ? "中文" : "English";

        return String.format("""
                                你是一个专业的性能测试工程师和Java应用运维专家。请根据以下压力测试结果和服务器监控数据，给出**严格、深入**的性能分析报告。
                                
                                请用%s回答。
                                
                                %s
                                
                                %s
                                
                                ---
                                
                                ## ⚠️ 分析要求（强制执行）

                                **【依赖项说明 - 重要】**
                                分析时必须区分核心性能和扩展功能性能：
                                - **数据库**：仅用于 gateway-admin 配置存储，**不影响网关运行时性能**。网关从 Nacos/Consul 获取配置，数据库问题只影响管理后台。
                                - **Redis**：仅用于分布式限流，如未配置或不可用，网关自动使用本地限流，**不影响核心路由转发**。
                                - **Nacos/Consul**：服务发现和配置中心，是网关核心依赖，如不可用会影响路由。
                                
                                **分析原则**：
                                - 网关核心性能 = 路由转发 + 过滤器链 + 负载均衡
                                - 数据库/Redis 问题属于"扩展功能性能"，不应作为核心瓶颈判定依据
                                
                                **【CPU 指标说明 - 重要】**
                                监控数据包含两种 CPU 指标，必须正确理解：
                                - **进程CPU（process_cpu_usage）**：网关进程本身的 CPU 使用率，这才是判断网关性能瓶颈的关键指标
                                - **系统CPU（system_cpu_usage）**：整个操作系统的 CPU 使用率，包含所有进程
                                
                                **CPU 满载判定规则**（使用进程CPU）：
                                - 进程CPU > 95%% → ❌ 网关进程满载，是性能瓶颈
                                - 进程CPU > 80%% → ⚠️ 网关进程高负载，接近瓶颈
                                - 进程CPU < 80%% → ✅ 网关进程正常，无瓶颈
                                
                                **注意**：不要混淆进程CPU和系统CPU！如果进程CPU很低但系统CPU很高，说明瓶颈在其他进程而非网关。
                                
                                **【内存泄漏检测 - 最高优先级】**
                                必须对比压测前、压测中、压测后的内存变化：
                                1. 压测前内存基准值（取压测开始前5分钟的平均值）
                                2. 压测期间内存峰值
                                3. **压测结束后内存值**（取压测结束后5分钟的平均值）
                                
                                **判定规则**：
                                - ✅ 正常：压测后内存回落到压测前水平 ±10%%
                                - ⚠️ 警告：压测后内存比压测前高出 10%%-30%%，可能存在对象未及时释放
                                - ❌ 严重：压测后内存比压测前高出 30%%+，**高度疑似内存泄漏**
                                
                                如果发现内存未回落，必须：
                                - 明确指出"内存泄漏风险"
                                - 建议进行堆转储分析（jmap -histo:live）
                                - 建议检查连接池、缓存、ThreadLocal 是否正确关闭
                                
                                **【响应时间异常检测】**
                                对比压测前后的响应时间：
                                - 如果压测后响应时间明显高于压测前，说明系统未完全恢复
                                - 分析是否存在请求排队、连接池耗尽等问题
                                
                                ---
                                
                                ## 输出格式（严格遵循）
                                
                                ### 1. 压测核心指标对比表

                                | 指标 | 压测前 | 压测峰值 | 压测后 | 评价 |
                                |------|--------|----------|--------|------|
                                | 进程CPU | X%% | Y%% | Z%% | 是否满载 |
                                | 系统CPU | X%% | Y%% | Z%% | 整体负载 |
                                | 内存 | X GB | Y GB | Z GB | **是否泄漏** |
                                | 响应时间 | X ms | Y ms | Z ms | 是否恢复 |
                                | 错误率 | 0%% | Y%% | Z%% | 是否有错误 |
                                
                                ### 2. 进程CPU专项分析（必填 - 最高优先级）

                **进程CPU变化趋势**：
                - 压测前基准：X%
                - 压测峰值：Y%
                - 压测后恢复：Z%

                **进程CPU判定**（必须明确输出以下结论之一）：
                - [ ] 进程CPU < 80%% → ✅ **网关进程正常，无瓶颈**
                - [ ] 进程CPU 80%%-95%% → ⚠️ **网关进程高负载，接近瓶颈**
                - [ ] 进程CPU > 95%% → ❌ **网关进程满载，是性能瓶颈**

                **重要**：如果进程CPU < 80%%，必须明确结论"网关不是性能瓶颈"！

                ### 3. 系统CPU分析

                **系统CPU变化趋势**：
                - 压测前基准：X%
                - 压测峰值：Y%
                - 压测后恢复：Z%

                **系统CPU判定**：
                - 如果系统CPU > 95%% 但进程CPU < 80%% → ⚠️ **系统整体负载高，但瓶颈不在网关，可能在其他进程（如压测客户端、数据库、Redis等）**

                ### 4. 内存专项分析（必填）
                                
                                **内存变化趋势**：
                                - 压测前基准：X GB
                                - 压测峰值：Y GB（上升 Z%%）
                                - 压测后状态：W GB
                                
                                **内存泄漏判定**：
                                - [ ] 压测后内存已回落到基准水平 ✅
                                - [ ] 压测后内存仍高于基准 10%%+ ⚠️ 需关注
                                - [ ] 压测后内存仍高于基准 30%%+ ❌ **疑似内存泄漏**
                                
                                **如果疑似泄漏，列出可能原因**：
                                1. 连接池未正确关闭（HttpClient、Redis、DB）
                                2. 大对象缓存未清理
                                3. ThreadLocal 未 remove
                                4. 响应体未完全消费
                                
                                ### 5. 响应时间分布分析
                                
                                分析 P50/P90/P95/P99 是否合理：
                                - P99 > 3×P50 → 存在长尾延迟问题
                                - 最大响应时间 > 5×P99 → 存在极端慢请求
                                
                                ### 6. 吞吐量瓶颈分析
                                
                                计算系统承载能力：
                                - 当前配置下最大 QPS = 实测QPS × 安全系数(0.8)
                                - CPU 满载时理论 QPS 上限
                                - 是否需要扩容
                                
                                ### 7. 问题诊断（按严重程度排序）
                                
                                | 问题 | 严重程度 | 可能原因 | 建议措施 |
                                |------|----------|----------|----------|
                                | 进程CPU满载 | HIGH/MEDIUM/LOW | ... | ... |
                                | 内存未回落 | ... | ... | ... |
                                | 响应时间长尾 | ... | ... | ... |
                                
                                ### 8. 性能评分（严格扣分）
                                
                                **评分规则**：
                                - 基准分：100
                                - 进程CPU满载（>95%%持续）：-20 分
                                - 内存泄漏（压测后 > 压测前 30%%）：-30 分
                                - 内存未完全回落（10%%-30%%）：-15 分
                                - 错误率 > 0.1%%：-10 分
                                - 响应时间 P99 > 500ms：-10 分
                                - Redis/DB 延迟 WARNING：-5 分
                                
                                **最终评分**：X/100 分
                                
                                **评分解读**：
                                - 90-100：优秀，系统健康
                                - 70-89：良好，有小问题需关注
                                - 50-69：一般，存在明显问题需优化
                                - <50：差，存在严重问题需立即处理
                                
                                ### 7. 优化建议（按优先级）
                                
                                **高优先级（立即处理）**：
                                1. [如果有内存泄漏] 执行堆分析，定位泄漏对象
                                2. [如果CPU满载] 扩容或优化代码
                                
                                **中优先级（一周内）**：
                                1. 调整 JVM 参数（如有 GC 问题）
                                2. 优化连接池配置
                                
                                **低优先级（持续优化）**：
                                1. 监控告警阈值调整
                                2. 性能基线建立
                                
                                ---
                                
                                ## 📋 健康状态总结
                                
                                | 组件 | 状态 | 说明 |
                                |------|------|------|
                                | 内存 | ✅/⚠️/❌ | 是否泄漏/是否回落 |
                                | CPU | ✅/⚠️/❌ | 是否满载 |
                                | 响应时间 | ✅/⚠️/❌ | 是否恢复 |
                                | 错误率 | ✅/⚠️/❌ | 是否有错误 |
                                
                                **总体结论**：系统在压测后 [已完全恢复 / 存在遗留问题需关注 / 存在严重问题需立即处理]
                                
                                ---
                                
                                请用 Markdown 格式输出，分析要**严格、有数据支撑、不回避问题**。如果某些监控数据缺失，请明确标注"数据缺失"并基于已有数据进行分析。
                                """, langName, stressTestData, metricsData.isEmpty() ? "【服务器监控数据】\n暂无 Prometheus 监控数据" : metricsData);
    }

    /**
     * 构建 Pod 对比分析 Prompt.
     */
    String buildPodComparisonPrompt(Map<String, String> podMetricsMap, long startTime, long endTime, String language) {
        String langName = "zh".equals(language) ? "中文" : "English";
        long durationMinutes = (endTime - startTime) / 60;

        StringBuilder podDataSection = new StringBuilder();
        podDataSection.append("## 各Pod监控数据\n\n");
        for (Map.Entry<String, String> entry : podMetricsMap.entrySet()) {
            podDataSection.append(entry.getValue()).append("\n\n---\n\n");
        }

        return String.format("""
                你是一个专业的性能测试工程师和Java应用运维专家。请分析以下网关实例各Pod在压测期间的性能对比数据。

                请用%s回答。

                压测持续时间: %d 分钟

                %s

                ## 分析要求

                请输出详细的分析报告，包含：

                ### 1. Pod性能对比总览
                - 列出所有Pod的CPU、内存、请求量、响应时间对比表
                - 识别负载最高的Pod和负载最低的Pod

                ### 2. 负载均衡分析（重点）
                - 分析各Pod之间是否存在负载不均衡
                - 如果某个Pod负载明显高于其他Pod，分析可能原因：
                  - 负载均衡策略配置问题
                  - 请求路由倾斜（特定路由请求集中到某个Pod）
                  - 连接池或缓存热点
                - 给出具体的负载均衡优化建议

                ### 3. 各Pod性能详细分析
                - 对每个Pod分别分析：
                  - CPU使用率是否正常
                  - 内存使用是否存在泄漏风险
                  - GC表现是否健康
                - 标注异常Pod（如有）

                ### 4. 问题诊断
                - 发现的异常或问题
                - 可能的根本原因分析

                ### 5. 优化建议
                - 负载均衡策略调整建议
                - JVM参数调整建议（如有GC问题）
                - 配置调整建议
                - 容量规划建议（如需要扩容）

                请用Markdown格式输出，重点关注负载均衡问题。如果发现某个Pod负载异常高，必须给出具体的排查和优化建议。
                """, langName, durationMinutes, podDataSection.toString());
    }
}
