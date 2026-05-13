package com.leoli.gateway.admin.service.copilot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ============================================================================
 * 响应构建器
 * ============================================================================
 * <p>
 * 格式化和构建 AI Copilot 的响应数据。
 * <p>
 * 功能:
 * - 格式化监控指标（用于错误分析和优化建议）
 * - 格式化诊断数据
 * - 格式化字节大小
 * - 构建丰富上下文数据
 * - 添加置信度标记
 * <p>
 * 格式化原则:
 * - 数据可视化：使用 Markdown 格式便于阅读
 * - 简洁明了：只展示关键指标
 * - 单位友好：自动转换单位（B/KB/MB/GB）
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CopilotResponseBuilder {

    private final ContextDataProvider contextDataProvider;

    // ===================== 监控指标格式化 =====================

    /**
     * 格式化监控指标用于错误分析
     * <p>
     * 只展示关键指标：QPS、响应时间、错误率、内存、CPU
     *
     * @param metrics 监控指标 Map
     * @return 格式化后的字符串
     */
    public String formatMetricsForErrorAnalysis(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return "指标数据获取失败";
        }

        StringBuilder sb = new StringBuilder();

        // HTTP 请求统计
        Map<String, Object> http = (Map<String, Object>) metrics.get("httpRequests");
        if (http != null) {
            sb.append("- **QPS**: ").append(String.format("%.2f", getDoubleValue(http, "requestsPerSecond"))).append("\n");
            sb.append("- **平均响应时间**: ").append(String.format("%.2f ms", getDoubleValue(http, "avgResponseTimeMs"))).append("\n");
            sb.append("- **错误率**: ").append(String.format("%.2f%%", getDoubleValue(http, "errorRate"))).append("\n");
        }

        // JVM 内存
        Map<String, Object> jvmMemory = (Map<String, Object>) metrics.get("jvmMemory");
        if (jvmMemory != null) {
            sb.append("- **堆内存使用率**: ").append(String.format("%.1f%%", getDoubleValue(jvmMemory, "heapUsagePercent"))).append("\n");
        }

        // CPU
        Map<String, Object> cpu = (Map<String, Object>) metrics.get("cpu");
        if (cpu != null) {
            sb.append("- **CPU 使用率**: ").append(String.format("%.1f%%", getDoubleValue(cpu, "processUsage"))).append("\n");
        }

        return sb.toString();
    }

    /**
     * 格式化监控指标用于优化建议
     * <p>
     * 展示完整指标：内存详情、CPU、HTTP、GC、线程
     *
     * @param metrics 监控指标 Map
     * @return 格式化后的字符串
     */
    public String formatMetricsForOptimization(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return "指标数据获取失败";
        }

        StringBuilder sb = new StringBuilder();

        // JVM 内存
        Map<String, Object> jvmMemory = (Map<String, Object>) metrics.get("jvmMemory");
        if (jvmMemory != null) {
            sb.append("- **堆内存使用率**: ")
                    .append(String.format("%.1f%%", getDoubleValue(jvmMemory, "heapUsagePercent")))
                    .append("\n");
            sb.append("- **堆内存已用**: ")
                    .append(formatBytes(getDoubleValue(jvmMemory, "heapUsed")))
                    .append("\n");
            sb.append("- **堆内存最大**: ")
                    .append(formatBytes(getDoubleValue(jvmMemory, "heapMax")))
                    .append("\n");
        }

        // CPU
        Map<String, Object> cpu = (Map<String, Object>) metrics.get("cpu");
        if (cpu != null) {
            sb.append("- **CPU 使用率**: 进程 ")
                    .append(String.format("%.1f%%", getDoubleValue(cpu, "processUsage")))
                    .append(", 系统 ")
                    .append(String.format("%.1f%%", getDoubleValue(cpu, "systemUsage")))
                    .append("\n");
        }

        // HTTP 请求
        Map<String, Object> http = (Map<String, Object>) metrics.get("httpRequests");
        if (http != null) {
            sb.append("- **每秒请求数(QPS)**: ")
                    .append(String.format("%.2f", getDoubleValue(http, "requestsPerSecond")))
                    .append("\n");
            sb.append("- **平均响应时间**: ")
                    .append(String.format("%.2f ms", getDoubleValue(http, "avgResponseTimeMs")))
                    .append("\n");
            sb.append("- **错误率**: ")
                    .append(String.format("%.2f%%", getDoubleValue(http, "errorRate")))
                    .append("\n");
        }

        // GC
        Map<String, Object> gc = (Map<String, Object>) metrics.get("gc");
        if (gc != null) {
            sb.append("- **GC 开销**: ")
                    .append(String.format("%.2f%%", getDoubleValue(gc, "gcOverheadPercent")))
                    .append("\n");
        }

        // 线程
        Map<String, Object> threads = (Map<String, Object>) metrics.get("threads");
        if (threads != null) {
            sb.append("- **活跃线程数**: ")
                    .append(getIntValue(threads, "liveThreads"))
                    .append("\n");
        }

        return sb.toString();
    }

    // ===================== 诊断数据格式化 =====================

    /**
     * 格式化诊断数据
     *
     * @param diagnostics 诊断数据 Map
     * @return 格式化后的字符串
     */
    public String formatDiagnostics(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "诊断数据获取失败";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : diagnostics.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    // ===================== 上下文数据构建 =====================

    /**
     * 构建丰富上下文数据
     * <p>
     * 包含：路由数量、服务数量、诊断摘要、实例规格
     *
     * @param instanceId 实例ID
     * @param context    额外上下文（可选）
     * @return 格式化后的上下文字符串
     */
    public String buildRichContextData(String instanceId, String context) {
        StringBuilder sb = new StringBuilder();

        sb.append("**系统规模**:\n");
        sb.append("- 路由数量: ").append(contextDataProvider.getRouteCount(instanceId)).append("\n");
        sb.append("- 服务数量: ").append(contextDataProvider.getServiceCount(instanceId)).append("\n");

        sb.append("\n**系统健康状态**:\n");
        Map<String, Object> diagnostics = contextDataProvider.getDiagnosticsSummary(instanceId);
        sb.append(formatDiagnostics(diagnostics));

        sb.append("\n**实例信息**:\n");
        sb.append("- ").append(contextDataProvider.getInstanceSpecInfo(instanceId)).append("\n");

        if (context != null && !context.isEmpty()) {
            sb.append("\n**额外上下文**:\n");
            sb.append(context).append("\n");
        }

        return sb.toString();
    }

    // ===================== 置信度标记 =====================

    /**
     * 添加置信度标记到响应末尾
     * <p>
     * 格式：
     * - 高置信度(≥90): ✅ 高置信度
     * - 中置信度(70-89): ⚡ 中置信度
     * - 低置信度(<70): ⚠️ 低置信度
     *
     * @param response      响应内容
     * @param confidence    置信度分数
     * @param confidenceReason 置信度原因
     * @param language      语言 ("zh" 或 "en")
     * @return 添加置信度标记后的响应
     */
    public String addConfidenceMark(String response, int confidence, String confidenceReason, String language) {
        if (response == null) {
            return null;
        }

        String mark;
        String label;

        if (confidence >= 90) {
            mark = "✅";
            label = "zh".equals(language) ? "高置信度" : "High Confidence";
        } else if (confidence >= 70) {
            mark = "⚡";
            label = "zh".equals(language) ? "中置信度" : "Medium Confidence";
        } else {
            mark = "⚠️";
            label = "zh".equals(language) ? "低置信度" : "Low Confidence";
        }

        return response + "\n\n---\n" + mark + " **" + label + " (" + confidence + "%)**: " + confidenceReason;
    }

    // ===================== 辅助方法 =====================

    /**
     * 格式化字节大小
     * <p>
     * 自动转换为合适的单位（B/KB/MB/GB）
     *
     * @param bytes 字节大小
     * @return 格式化后的字符串
     */
    public String formatBytes(double bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = (int) (Math.log(bytes) / Math.log(1024));
        unitIndex = Math.min(unitIndex, units.length - 1);
        double value = bytes / Math.pow(1024, unitIndex);
        return String.format("%.2f %s", value, units[unitIndex]);
    }

    /**
     * 从 Map 中获取 double 值
     *
     * @param map 数据 Map
     * @param key 键名
     * @return double 值，不存在则返回 0.0
     */
    public double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    /**
     * 从 Map 中获取 int 值
     *
     * @param map 数据 Map
     * @param key 键名
     * @return int 值，不存在则返回 0
     */
    public int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    // ===================== 完整提示词构建 =====================

    /**
     * 构建完整提示词
     *
     * @param systemPrompt 系统提示词模板
     * @param contextData  上下文数据
     * @param userMessage  用户消息
     * @return 完整提示词
     */
    public String buildFullPrompt(String systemPrompt, String contextData, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt);

        if (contextData != null && !contextData.isEmpty()) {
            sb.append("\n\n**当前系统上下文**:\n").append(contextData);
        }

        sb.append("\n\n**用户问题**: ").append(userMessage);

        return sb.toString();
    }
}