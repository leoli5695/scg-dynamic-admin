package com.leoli.gateway.admin.service.copilot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * ============================================================================
 * 置信度计算器
 * ============================================================================
 * <p>
 * 根据工具调用结果计算 AI 回复的置信度分数 (0-100)。
 * <p>
 * 计算规则:
 * - 基础分数: 70
 * - 工具调用加分: +15
 * - 数据完整性加分: +5 (route+service), +3 (metrics), +2 (diagnostic)
 * - 多工具验证加分: +5 (>=3工具)
 * - 成功率影响: +5 (>=90%), -10 (<50%)
 * <p>
 * 置信度等级:
 * - 90-100: 高置信度（多工具验证 + 完整数据）
 * - 80-89: 较高置信度（工具验证 + 基础数据）
 * - 70-79: 中等置信度（仅基础分析）
 * - 60-69: 较低置信度（部分工具失败）
 * - 0-59: 低置信度（无工具验证或大量失败）
 *
 * @author leoli
 */
@Slf4j
@Component
public class ConfidenceCalculator {

    // ===================== 置信度计算常量 =====================

    /**
     * 基础置信度分数（无工具验证时的默认分数）
     */
    public static final int BASE_CONFIDENCE = 70;

    /**
     * 工具调用加分
     */
    public static final int TOOL_CALL_BONUS = 15;

    /**
     * 数据完整性加分 - route + service
     */
    public static final int DATA_COMPLETE_BONUS = 5;

    /**
     * 指标数据加分
     */
    public static final int METRICS_BONUS = 3;

    /**
     * 诊断数据加分
     */
    public static final int DIAGNOSTIC_BONUS = 2;

    /**
     * 多工具验证加分阈值
     */
    public static final int MULTI_TOOL_THRESHOLD = 3;

    /**
     * 多工具验证加分
     */
    public static final int MULTI_TOOL_BONUS = 5;

    /**
     * 高成功率加分阈值
     */
    public static final double HIGH_SUCCESS_RATE_THRESHOLD = 0.9;

    /**
     * 高成功率加分
     */
    public static final int HIGH_SUCCESS_RATE_BONUS = 5;

    /**
     * 低成功率扣分阈值
     */
    public static final double LOW_SUCCESS_RATE_THRESHOLD = 0.5;

    /**
     * 低成功率扣分
     */
    public static final int LOW_SUCCESS_RATE_PENALTY = 10;

    // ===================== 核心计算方法 =====================

    /**
     * 计算置信度分数
     *
     * @param calledTools    调用的工具集合
     * @param successfulTools 成功执行的工具数量
     * @param dataTypes      获取的数据类型集合
     * @return 置信度分数 (0-100)
     */
    public int calculate(Set<String> calledTools, int successfulTools, Set<String> dataTypes) {
        int confidence = BASE_CONFIDENCE;

        // 工具调用加分
        if (calledTools != null && !calledTools.isEmpty()) {
            confidence += TOOL_CALL_BONUS;
        }

        // 数据完整性加分
        if (dataTypes != null) {
            if (dataTypes.contains("route") && dataTypes.contains("service")) {
                confidence += DATA_COMPLETE_BONUS;
            }
            if (dataTypes.contains("metrics")) {
                confidence += METRICS_BONUS;
            }
            if (dataTypes.contains("diagnostic")) {
                confidence += DIAGNOSTIC_BONUS;
            }
        }

        // 多工具验证加分
        if (calledTools != null && calledTools.size() >= MULTI_TOOL_THRESHOLD) {
            confidence += MULTI_TOOL_BONUS;
        }

        // 成功率影响
        if (successfulTools > 0 && calledTools != null && calledTools.size() > 0) {
            double successRate = (double) successfulTools / calledTools.size();
            if (successRate >= HIGH_SUCCESS_RATE_THRESHOLD) {
                confidence += HIGH_SUCCESS_RATE_BONUS;
            } else if (successRate < LOW_SUCCESS_RATE_THRESHOLD) {
                confidence -= LOW_SUCCESS_RATE_PENALTY;
            }
        }

        // 限制范围
        int result = Math.min(100, Math.max(0, confidence));
        log.debug("Calculated confidence: {} (tools={}, successful={}, dataTypes={})",
                result, calledTools != null ? calledTools.size() : 0, successfulTools, dataTypes);

        return result;
    }

    /**
     * 构建置信度原因说明
     *
     * @param calledTools 调用的工具集合
     * @param dataTypes   获取的数据类型集合
     * @return 置信度原因说明字符串
     */
    public String buildReason(Set<String> calledTools, Set<String> dataTypes) {
        if (calledTools == null || calledTools.isEmpty()) {
            return "未调用工具验证";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已调用工具: ").append(calledTools.size()).append("个");

        if (dataTypes != null && !dataTypes.isEmpty()) {
            sb.append("，验证数据: ").append(String.join("+", dataTypes));
        }

        return sb.toString();
    }

    /**
     * 记录工具获取的数据类型
     *
     * @param toolName  工具名称
     * @param dataTypes 数据类型集合（会被修改）
     */
    public void recordDataType(String toolName, Set<String> dataTypes) {
        if (toolName == null || dataTypes == null) {
            return;
        }

        // 根据工具名称推断数据类型
        if (toolName.contains("route") || toolName.equals("get_routes")) {
            dataTypes.add("route");
        }
        if (toolName.contains("service") || toolName.equals("get_services")) {
            dataTypes.add("service");
        }
        if (toolName.contains("metrics") || toolName.contains("monitor") || toolName.contains("prometheus")) {
            dataTypes.add("metrics");
        }
        if (toolName.contains("diagnostic") || toolName.contains("health") || toolName.contains("diagnose")) {
            dataTypes.add("diagnostic");
        }
        if (toolName.contains("log") || toolName.contains("access_log")) {
            dataTypes.add("log");
        }
        if (toolName.contains("filter") || toolName.contains("filter_chain")) {
            dataTypes.add("filter");
        }
        if (toolName.contains("instance") || toolName.contains("gateway_instance")) {
            dataTypes.add("instance");
        }

        log.debug("Recorded data type from tool: {} -> {}", toolName, dataTypes);
    }

    // ===================== 辅助方法 =====================

    /**
     * 获取默认置信度
     */
    public int getDefaultConfidence() {
        return BASE_CONFIDENCE;
    }

    /**
     * 获取最大置信度
     */
    public int getMaxConfidence() {
        return 100;
    }

    /**
     * 获取最小置信度
     */
    public int getMinConfidence() {
        return 0;
    }

    /**
     * 判断是否为高置信度（>=90）
     */
    public boolean isHighConfidence(int confidence) {
        return confidence >= 90;
    }

    /**
     * 判断是否为中等置信度（70-89）
     */
    public boolean isMediumConfidence(int confidence) {
        return confidence >= 70 && confidence < 90;
    }

    /**
     * 判断是否为低置信度（<70）
     */
    public boolean isLowConfidence(int confidence) {
        return confidence < 70;
    }
}