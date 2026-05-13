package com.leoli.gateway.admin.service.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiConfig;
import com.leoli.gateway.admin.repository.AiConfigRepository;
import com.leoli.gateway.admin.service.AiAnalysisService;
import com.leoli.gateway.admin.service.ToolExecutor;
import com.leoli.gateway.admin.service.copilot.model.ChatMessage;
import com.leoli.gateway.admin.service.copilot.model.ToolCallLoopResult;
import com.leoli.gateway.admin.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ============================================================================
 * 工具调用编排器
 * ============================================================================
 * <p>
 * 编排 AI 与工具的交互，实现 Function Calling 工具调用循环。
 * <p>
 * 功能:
 * - 执行工具调用循环（最多 MAX_TOOL_CALL_ITERATIONS 轮）
 * - 构建 Function Calling 格式的消息
 * - 处理工具执行结果
 * - 计算置信度
 * <p>
 * 工具调用流程:
 * 1. AI 分析用户问题，决定是否需要调用工具
 * 2. 如果需要，AI 返回工具调用请求
 * 3. 执行工具，返回结果给 AI
 * 4. AI 综合工具结果，生成最终回复
 * 5. 重复步骤 1-4，直到 AI 不再调用工具或达到上限
 * <p>
 * 置信度计算:
 * - 根据工具调用数量、成功率、数据完整性计算
 * - 由 ConfidenceCalculator 组件负责
 * <p>
 * 限制:
 * - 最大调用轮数: 15（防止无限循环）
 * - 工具执行超时: 30秒
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallOrchestrator {

    private final AiAnalysisService aiAnalysisService;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ConfidenceCalculator confidenceCalculator;
    private final AiConfigRepository aiConfigRepository;
    private final ObjectMapper objectMapper;

    // ===================== 配置常量 =====================

    /**
     * 最大工具调用轮数（防止无限循环）
     */
    public static final int MAX_TOOL_CALL_ITERATIONS = 15;

    /**
     * 工具执行超时时间（毫秒）
     */
    public static final int TOOL_CALL_TIMEOUT_MS = 30000;

    // ===================== 核心方法 =====================

    /**
     * 执行工具调用循环
     * <p>
     * 返回包含最终回复和置信度的结果
     *
     * @param config  AI 配置
     * @param model   模型名称（可选，覆盖配置）
     * @param messages 消息列表
     * @param tools   工具定义列表
     * @return 工具调用循环结果
     */
    public ToolCallLoopResult runToolCallLoop(AiConfig config, String model,
                                               List<Map<String, Object>> messages,
                                               List<Map<String, Object>> tools) {

        String effectiveModel = (model != null && !model.isEmpty()) ? model : config.getModel();
        String provider = config.getProvider();

        // 工具调用统计
        Set<String> calledTools = new HashSet<>();
        int successfulTools = 0;
        Set<String> dataTypes = new HashSet<>();

        // 执行循环
        for (int iteration = 0; iteration < MAX_TOOL_CALL_ITERATIONS; iteration++) {
            log.info("Tool call iteration: {} for provider: {}", iteration + 1, provider);

            // 调用 AI API（带工具）
            AiAnalysisService.AiResponse response = aiAnalysisService.chatWithTools(
                    provider, effectiveModel, messages, tools);

            // 如果没有工具调用，返回最终回复
            if (!response.hasToolCalls()) {
                log.info("No tool calls, returning final response");

                int confidence = confidenceCalculator.calculate(calledTools, successfulTools, dataTypes);
                String reason = confidenceCalculator.buildReason(calledTools, dataTypes);

                return new ToolCallLoopResult(
                    response.getContent() != null ? response.getContent() : "AI 未返回有效回复。",
                    confidence, reason, calledTools, dataTypes
                );
            }

            log.info("AI requested {} tool calls: {}",
                    response.getToolCalls().size(),
                    response.getToolCalls().stream().map(AiAnalysisService.ToolCall::getName).toList());

            // 将 assistant 的 tool_calls 消息加入 messages
            messages.add(buildAssistantToolCallsMessage(response));

            // 执行每个工具调用
            for (AiAnalysisService.ToolCall toolCall : response.getToolCalls()) {
                long startTime = System.currentTimeMillis();

                calledTools.add(toolCall.getName());

                // 执行工具
                ToolExecutor.ToolResult result = toolExecutor.execute(
                        toolCall.getName(), toolCall.getArguments());

                long duration = System.currentTimeMillis() - startTime;
                log.info("Tool {} executed in {} ms, success={}",
                        toolCall.getName(), duration, result.isSuccess());

                if (result.isSuccess()) {
                    successfulTools++;
                    confidenceCalculator.recordDataType(toolCall.getName(), dataTypes);
                }

                // 将工具结果加入 messages
                messages.add(buildToolResultMessage(provider, toolCall.getId(),
                        toolCall.getName(), result));
            }
        }

        // 超过最大轮数
        log.warn("Max tool call iterations reached: {}", MAX_TOOL_CALL_ITERATIONS);
        int confidence = confidenceCalculator.calculate(calledTools, successfulTools, dataTypes);
        String reason = confidenceCalculator.buildReason(calledTools, dataTypes) + " (达到调用上限)";

        return new ToolCallLoopResult(
            "工具调用次数达到上限，请简化您的问题或分步提问。",
            Math.max(50, confidence - 20),
            reason, calledTools, dataTypes
        );
    }

    // ===================== 消息构建 =====================

    /**
     * 构建 Function Calling 格式的 messages 数组
     *
     * @param history      对话历史
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @param instanceId   实例ID（可选）
     * @return 消息列表
     */
    public List<Map<String, Object>> buildMessagesForTools(List<ChatMessage> history,
                                                            String systemPrompt,
                                                            String userMessage,
                                                            String instanceId) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 1. System prompt
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 2. 注入当前上下文信息（实例ID）
        if (instanceId != null && !instanceId.isEmpty()) {
            String contextInfo = String.format(
                "\n\n【当前上下文】\n- 当前选择的网关实例ID: %s\n- 你可以直接使用这个 instanceId 调用工具（如 get_filter_chain_stats、get_slowest_filters），无需先调用 list_instances 获取实例列表。",
                instanceId
            );
            Map<String, Object> firstMsg = messages.get(0);
            String enhancedSystemPrompt = firstMsg.get("content") + contextInfo;
            messages.set(0, Map.of("role", "system", "content", enhancedSystemPrompt));
        }

        // 3. 历史对话（不包含 tool 相关消息）
        for (ChatMessage msg : history) {
            if ("tool".equals(msg.getRole())) {
                continue;  // 历史中不包含 tool 消息，格式不同
            }
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }

        // 4. 当前用户消息
        messages.add(Map.of("role", "user", "content", userMessage));

        return messages;
    }

    /**
     * 构建 assistant 的 tool_calls 消息
     * <p>
     * OpenAI 格式：assistant 消息带 tool_calls
     *
     * @param response AI 响应
     * @return assistant 消息 Map
     */
    public Map<String, Object> buildAssistantToolCallsMessage(AiAnalysisService.AiResponse response) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (AiAnalysisService.ToolCall tc : response.getToolCalls()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tc.getName());
            try {
                function.put("arguments", objectMapper.writeValueAsString(tc.getArguments()));
            } catch (Exception e) {
                function.put("arguments", "{}");
            }

            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", tc.getId());
            toolCall.put("type", "function");
            toolCall.put("function", function);

            toolCalls.add(toolCall);
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", response.getContent());
        message.put("tool_calls", toolCalls);

        return message;
    }

    /**
     * 构建工具结果消息（按提供商格式）
     *
     * @param provider    提供商
     * @param toolCallId  工具调用ID
     * @param toolName    工具名称
     * @param result      工具执行结果
     * @return 工具结果消息 Map
     */
    public Map<String, Object> buildToolResultMessage(String provider, String toolCallId,
                                                       String toolName, ToolExecutor.ToolResult result) {
        String resultJson = result.toJson();

        return switch (provider) {
            case "CLAUDE" -> aiAnalysisService.buildClaudeToolResultMessage(toolCallId, resultJson);
            case "GEMINI" -> aiAnalysisService.buildGeminiToolResultMessage(toolName, resultJson);
            default -> aiAnalysisService.buildOpenAIToolResultMessage(toolCallId, resultJson);
        };
    }

    // ===================== 配置获取 =====================

    /**
     * 获取 AI 配置
     *
     * @param provider 提供商（可选）
     * @return AI 配置
     */
    public AiConfig getAiConfig(String provider) {
        if (provider != null && !provider.isEmpty()) {
            return aiConfigRepository.findByProvider(provider)
                    .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));
        }

        return aiConfigRepository.findFirstValidConfig()
                .orElseThrow(() -> new RuntimeException("未配置 AI 服务。请在监控页面配置 AI 提供商。"));
    }

    /**
     * 获取 OpenAI 格式的工具定义
     *
     * @return 工具定义列表
     */
    public List<Map<String, Object>> getOpenAITools() {
        return toolRegistry.getOpenAITools();
    }

    // ===================== 辅助方法 =====================

    /**
     * 判断是否应该继续工具调用循环
     *
     * @param response AI 响应
     * @return 是否应该继续
     */
    public boolean shouldContinueLoop(AiAnalysisService.AiResponse response) {
        return response.hasToolCalls();
    }
}