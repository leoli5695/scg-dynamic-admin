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
 * 证据评估:
 * - 记录 calledTools / dataTypes / failedTools，由 EvidenceAssessor 如实评估回答
 *   有无实时数据佐证（不再输出假置信度百分比）
 * <p>
 * 限制:
 * - 最大调用轮数: 15（防止无限循环）
 * - 循环总时间预算: {@link #TOOL_LOOP_TOTAL_TIMEOUT_MS}（超时中断并返回降级回复）
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
    private final EvidenceAssessor evidenceAssessor;
    private final AiConfigRepository aiConfigRepository;
    private final ObjectMapper objectMapper;
    private final PendingActionStore pendingActionStore;

    // ===================== 配置常量 =====================

    /**
     * 最大工具调用轮数（防止无限循环）
     */
    public static final int MAX_TOOL_CALL_ITERATIONS = 15;

    /**
     * 单次工具调用循环的总时间预算（毫秒）。
     * <p>
     * 每一轮都要同步调用一次大模型（单次读超时可达数分钟），若不设总时限，
     * 15 轮最坏情况会把请求线程阻塞极久。这里在每轮开始前检查总耗时，超预算
     * 即中断循环并返回已得到的内容，给用户一个可解释的降级回复。
     * 单个工具的 HTTP 调用超时由共享 RestTemplate（连接 5s / 读取 30s）保证。
     */
    public static final long TOOL_LOOP_TOTAL_TIMEOUT_MS = 90_000L;

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
        return runToolCallLoop(config, model, messages, tools, CopilotProgressListener.NOOP);
    }

    /**
     * 执行工具调用循环（带进度回调）。
     * <p>
     * 与非流式版本逻辑一致，额外在每轮开始与每个工具执行前后通过 {@code listener}
     * 推送中间态，供 SSE 端点实时下发，改善长耗时多轮调用的等待体验。
     *
     * @param listener 进度回调，非 null（非流式路径传 {@link CopilotProgressListener#NOOP}）
     */
    public ToolCallLoopResult runToolCallLoop(AiConfig config, String model,
                                               List<Map<String, Object>> messages,
                                               List<Map<String, Object>> tools,
                                               CopilotProgressListener listener) {

        String effectiveModel = (model != null && !model.isEmpty()) ? model : config.getModel();
        String provider = config.getProvider();

        // 工具调用统计（用于证据评估，不再算假置信度）
        Set<String> calledTools = new HashSet<>();
        Set<String> failedTools = new HashSet<>();
        Set<String> dataTypes = new HashSet<>();
        long totalTokens = 0; // 累计各轮模型调用的 token 用量（成本闸门核算）

        long loopStart = System.currentTimeMillis();

        // 执行循环
        for (int iteration = 0; iteration < MAX_TOOL_CALL_ITERATIONS; iteration++) {
            // 总时间预算检查：超过则中断，返回降级回复，避免长时间阻塞请求线程
            long elapsed = System.currentTimeMillis() - loopStart;
            if (elapsed > TOOL_LOOP_TOTAL_TIMEOUT_MS) {
                log.warn("Tool call loop exceeded total budget ({} ms > {} ms), stopping at iteration {}",
                        elapsed, TOOL_LOOP_TOTAL_TIMEOUT_MS, iteration + 1);
                return new ToolCallLoopResult(
                        "本次分析涉及的工具调用较多，已达到时间上限并中断。以上是基于已获取数据的部分结论，请缩小问题范围或分步提问。",
                        calledTools, dataTypes, failedTools, totalTokens);
            }

            log.info("Tool call iteration: {} for provider: {}", iteration + 1, provider);
            listener.onEvent("status", Map.of("stage", "thinking", "iteration", iteration + 1));

            // 调用 AI API（带工具）
            AiAnalysisService.AiResponse response = aiAnalysisService.chatWithTools(
                    provider, effectiveModel, messages, tools);
            totalTokens += response.getTotalTokens();

            // 如果没有工具调用，返回最终回复
            if (!response.hasToolCalls()) {
                log.info("No tool calls, returning final response");
                return new ToolCallLoopResult(
                    response.getContent() != null ? response.getContent() : "AI 未返回有效回复。",
                    calledTools, dataTypes, failedTools, totalTokens
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
                listener.onEvent("tool", Map.of("toolName", toolCall.getName(), "status", "running"));

                // 执行工具
                ToolExecutor.ToolResult result = toolExecutor.execute(
                        toolCall.getName(), toolCall.getArguments());

                long duration = System.currentTimeMillis() - startTime;
                log.info("Tool {} executed in {} ms, success={}",
                        toolCall.getName(), duration, result.isSuccess());

                String toolStatus = result.isPendingConfirmation() ? "confirm"
                        : (result.isSuccess() ? "success" : "error");
                listener.onEvent("tool", Map.of(
                        "toolName", toolCall.getName(), "status", toolStatus, "durationMs", duration));

                // FIX (P0): 模型提议需要确认的写操作时，绝不在循环内执行。
                // 中止循环并把待确认动作交给前端，由用户人工确认后经
                // /api/copilot/execute-action 端点执行（confirmed 来自人，而非模型）。
                if (result.isPendingConfirmation()) {
                    // 登记待确认动作，签发一次性 ticket；执行时以服务端保存的参数为准（防篡改）。
                    String ticket = pendingActionStore.register(result.getToolName(), toolCall.getArguments());

                    Map<String, Object> pendingAction = new LinkedHashMap<>();
                    pendingAction.put("ticket", ticket);
                    pendingAction.put("toolName", result.getToolName());
                    pendingAction.put("arguments", toolCall.getArguments());
                    pendingAction.put("preview", result.getConfirmationPreview());

                    ToolCallLoopResult pendingResult = new ToolCallLoopResult(
                            "我已准备好执行一个需要确认的写操作，请在下方核对详情并点击确认后再执行。",
                            calledTools, dataTypes, failedTools, totalTokens);
                    pendingResult.setPendingAction(pendingAction);

                    log.info("Tool {} requires human confirmation - returning pendingAction to caller",
                            toolCall.getName());
                    return pendingResult;
                }

                if (result.isSuccess()) {
                    evidenceAssessor.recordDataType(toolCall.getName(), dataTypes);
                } else {
                    failedTools.add(toolCall.getName());
                }

                // 将工具结果加入 messages
                messages.add(buildToolResultMessage(provider, toolCall.getId(),
                        toolCall.getName(), result));
            }
        }

        // 超过最大轮数
        log.warn("Max tool call iterations reached: {}", MAX_TOOL_CALL_ITERATIONS);
        return new ToolCallLoopResult(
            "工具调用次数达到上限，请简化您的问题或分步提问。",
            calledTools, dataTypes, failedTools, totalTokens
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

    /**
     * 按意图领域获取 OpenAI 格式的工具定义（降低每轮 token 开销）。
     * 领域不明确时回退到全量工具，保证不会漏掉模型需要的能力。
     *
     * @param domain 意图领域（如 route/service/monitor/trace...），可为 null
     * @return 工具定义列表
     */
    public List<Map<String, Object>> getOpenAITools(String domain) {
        return toolRegistry.getOpenAITools(domain);
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