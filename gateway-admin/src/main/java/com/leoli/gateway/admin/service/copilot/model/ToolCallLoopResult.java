package com.leoli.gateway.admin.service.copilot.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.Set;

/**
 * ============================================================================
 * 工具调用循环结果
 * ============================================================================
 * <p>
 * 封装 Function Calling 工具调用循环的执行结果：
 * - AI 最终回复内容
 * - 调用的工具集合 / 命中的数据类型 / 失败的工具集合（用于证据评估）
 * - 待人工确认的写操作（如有）
 * <p>
 * 注意：不再携带 0-100% 的"置信度"——那是按工具数量硬凑的假精确值。回答的可信度
 * 由 {@link com.leoli.gateway.admin.service.copilot.EvidenceAssessor} 依据这里记录的
 * calledTools / dataTypes / failedTools 如实评估。
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
public class ToolCallLoopResult {

    /** AI 最终回复内容 */
    private String content;

    /** 调用的工具名称集合（去重） */
    private Set<String> calledTools;

    /** 命中的数据类型集合，如 route/service/metrics/diagnostic/trace/middleware */
    private Set<String> dataTypes;

    /** 执行失败的工具名称集合（去重） */
    private Set<String> failedTools;

    /** 本次工具调用循环累计消耗的 token 数（各轮模型调用 usage 之和），用于成本闸门核算。 */
    private long totalTokens;

    /**
     * FIX (P0): 待人工确认的写操作。当模型提议 requiresConfirmation 的工具时，
     * 此字段承载 {ticket, toolName, arguments, preview}，由前端渲染确认弹窗；
     * 用户确认后经 /api/copilot/execute-action 端点执行，模型无法自确认。
     * 为 null 表示本轮无需确认的写操作。
     */
    private Map<String, Object> pendingAction;

    public ToolCallLoopResult(String content, Set<String> calledTools, Set<String> dataTypes, Set<String> failedTools) {
        this(content, calledTools, dataTypes, failedTools, 0L);
    }

    public ToolCallLoopResult(String content, Set<String> calledTools, Set<String> dataTypes,
                              Set<String> failedTools, long totalTokens) {
        this.content = content;
        this.calledTools = calledTools;
        this.dataTypes = dataTypes;
        this.failedTools = failedTools;
        this.totalTokens = totalTokens;
    }

    /** 判断是否成功执行 */
    public boolean isSuccess() {
        return content != null && !content.isEmpty();
    }

    /** 获取工具调用数量 */
    public int getToolCount() {
        return calledTools != null ? calledTools.size() : 0;
    }

    /** 判断是否调用了多个工具（>=3） */
    public boolean hasMultipleTools() {
        return getToolCount() >= 3;
    }

    /** 判断是否获取了较完整的数据（route + service） */
    public boolean hasCompleteData() {
        if (dataTypes == null) return false;
        return dataTypes.contains("route") && dataTypes.contains("service");
    }
}
