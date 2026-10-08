package com.leoli.gateway.admin.service.copilot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * ============================================================================
 * 工具调用循环结果
 * ============================================================================
 * <p>
 * 封装 Function Calling 工具调用循环的执行结果，包含:
 * - AI 最终回复内容
 * - 置信度分数 (0-100)
 * - 置信度原因说明
 * - 调用的工具集合
 * - 获取的数据类型集合
 * <p>
 * 置信度计算规则:
 * - 基础分数: 70
 * - 工具调用加分: +15
 * - 数据完整性加分: +5 (route+service), +3 (metrics), +2 (diagnostic)
 * - 多工具验证加分: +5 (>=3工具)
 * - 成功率影响: +5 (>=90%), -10 (<50%)
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallLoopResult {

    /**
     * AI 最终回复内容
     */
    private String content;

    /**
     * 置信度分数 (0-100)
     */
    private int confidence;

    /**
     * 置信度原因说明
     */
    private String confidenceReason;

    /**
     * 调用的工具名称集合
     */
    private Set<String> calledTools;

    /**
     * 获取的数据类型集合
     * 如: route, service, metrics, diagnostic, log 等
     */
    private Set<String> dataTypes;

    /**
     * FIX (P0): 待人工确认的写操作。当模型提议 requiresConfirmation 的工具时，
     * 此字段承载 {toolName, arguments, preview}，由前端渲染确认弹窗；
     * 用户确认后经 /api/copilot/execute-action 端点执行，模型无法自确认。
     * 为 null 表示本轮无需确认的写操作。
     */
    private java.util.Map<String, Object> pendingAction;

    /**
     * 保留原有 5 参构造，避免新增字段后 @AllArgsConstructor 破坏既有调用点。
     */
    public ToolCallLoopResult(String content, int confidence, String confidenceReason,
                              Set<String> calledTools, Set<String> dataTypes) {
        this.content = content;
        this.confidence = confidence;
        this.confidenceReason = confidenceReason;
        this.calledTools = calledTools;
        this.dataTypes = dataTypes;
    }

    /**
     * 判断是否成功执行
     */
    public boolean isSuccess() {
        return content != null && !content.isEmpty();
    }

    /**
     * 获取工具调用数量
     */
    public int getToolCount() {
        return calledTools != null ? calledTools.size() : 0;
    }

    /**
     * 判断是否调用了多个工具（>=3）
     */
    public boolean hasMultipleTools() {
        return getToolCount() >= 3;
    }

    /**
     * 判断是否获取了完整数据（route + service + metrics）
     */
    public boolean hasCompleteData() {
        if (dataTypes == null) return false;
        return dataTypes.contains("route") && dataTypes.contains("service");
    }
}