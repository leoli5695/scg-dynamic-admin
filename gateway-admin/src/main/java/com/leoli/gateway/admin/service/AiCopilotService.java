package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiConfig;
import com.leoli.gateway.admin.prompt.PromptService;
import com.leoli.gateway.admin.repository.AiConfigRepository;
import com.leoli.gateway.admin.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Copilot Service.
 * Provides an intelligent assistant for gateway configuration and troubleshooting.
 * <p>
 * Features:
 * - Natural language to route configuration
 * - Error analysis and debugging suggestions
 * - Performance optimization recommendations
 * - Configuration best practices guidance
 * - Context-aware responses based on current gateway state
 * - Smart intent detection for targeted responses
 * - Function Calling / Tool Calling support (AI can call tools)
 * <p>
 * 提示词管理委托给 PromptService 服务：
 * - 意图检测：PromptService.detectIntent()
 * - 提示词构建：PromptService.buildSystemPrompt()
 * <p>
 * 工具调用：
 * - 工具注册：ToolRegistry.getAllTools()
 * - 工具执行：ToolExecutor.execute()
 * - AI 自动决定调用工具并处理结果
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCopilotService {

    private final AiConfigRepository aiConfigRepository;
    private final AiAnalysisService aiAnalysisService;
    private final DiagnosticService diagnosticService;
    private final PrometheusService prometheusService;
    private final ObjectMapper objectMapper;

    // 提示词管理服务（统一使用 PromptService）
    private final PromptService promptService;

    // 工具调用服务
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;

    // 路由和服务管理（用于生成器参考）
    private final RouteService routeService;
    private final ServiceService serviceService;
    private final GatewayInstanceService gatewayInstanceService;

    // Conversation history per session
    private final ConcurrentHashMap<String, List<ChatMessage>> conversationHistory = new ConcurrentHashMap<>();

    // 轻量级上下文记忆：记录每个 session 的最后意图（用于多轮对话延续）
    private final ConcurrentHashMap<String, String> sessionLastIntent = new ConcurrentHashMap<>();

    // ===================== 工具调用配置 =====================

    // 最大工具调用轮数（防止无限循环）
    private static final int MAX_TOOL_CALL_ITERATIONS = 5;

    // 工具执行超时时间（毫秒）
    private static final int TOOL_CALL_TIMEOUT_MS = 30000;

    // ===================== 常见问题本地兜底 =====================

    // 中文常见问题本地回复（避免调用AI，降低延迟和成本）
    private static final Map<String, String> LOCAL_FALLBACKS_ZH = createLocalFallbacksZH();

    private static Map<String, String> createLocalFallbacksZH() {
        Map<String, String> fallbacks = new LinkedHashMap<>();
        // 问候类
        fallbacks.put("你好", "你好！我是网关 AI Copilot 智能助手。我可以帮你配置路由、排查故障、优化性能等。有什么问题随时问我！");
        fallbacks.put("您好", "您好！我是网关 AI Copilot。有什么我可以帮你的吗？");
        fallbacks.put("hi", "Hi！有什么问题吗？我可以帮你配置路由、排查问题等。");
        fallbacks.put("hello", "Hello！我是网关智能助手，随时为你提供帮助。");

        // 感谢类
        fallbacks.put("谢谢", "不客气！有问题随时问我。");
        fallbacks.put("感谢", "很高兴能帮到你！如有其他问题，随时找我。");
        fallbacks.put("thanks", "You're welcome! Feel free to ask anytime.");

        // 简单功能确认
        fallbacks.put("你是谁", "我是网关管理系统的 AI Copilot 智能助手，专注于帮助你配置和管理 Spring Cloud Gateway。");
        fallbacks.put("你能做什么", "我可以帮你：\n1. **配置路由**：生成路由配置、解释断言/过滤器\n2. **排查故障**：分析错误日志、诊断健康状态\n3. **优化性能**：调优建议、JVM参数、限流阈值\n4. **管理实例**：K8s部署、副本调整\n5. **监控告警**：指标解读、告警降噪\n\n有什么需要帮忙的？");
        fallbacks.put("你能帮我什么", "我可以帮你配置路由、排查故障、优化性能、管理网关实例等。具体问吧！");

        return fallbacks;
    }

    // 英文常见问题本地回复
    private static final Map<String, String> LOCAL_FALLBACKS_EN = createLocalFallbacksEN();

    private static Map<String, String> createLocalFallbacksEN() {
        Map<String, String> fallbacks = new LinkedHashMap<>();
        fallbacks.put("hello", "Hello! I'm the Gateway AI Copilot. I can help you configure routes, troubleshoot issues, optimize performance, and more. What can I help you with?");
        fallbacks.put("hi", "Hi there! How can I assist you today?");
        fallbacks.put("thanks", "You're welcome! Feel free to ask anytime.");
        fallbacks.put("thank you", "Happy to help! Let me know if you have more questions.");
        fallbacks.put("who are you", "I'm the AI Copilot for Spring Cloud Gateway management system. I specialize in helping you configure and manage your gateway.");
        fallbacks.put("what can you do", "I can help you:\n1. **Configure routes**: Generate configs, explain predicates/filters\n2. **Troubleshoot**: Analyze errors, diagnose health\n3. **Optimize**: Performance tuning, JVM settings, rate limits\n4. **Manage instances**: K8s deployment, replica scaling\n5. **Monitor**: Metrics interpretation, alert management\n\nWhat do you need help with?");

        return fallbacks;
    }

    /**
     * 检查是否匹配本地兜底回复
     *
     * @return 如果匹配返回回复内容，否则返回 null
     */
    private String checkLocalFallback(String userMessage, String language) {
        String lowerMessage = userMessage.toLowerCase().trim();

        Map<String, String> fallbacks = "zh".equals(language) ? LOCAL_FALLBACKS_ZH : LOCAL_FALLBACKS_EN;

        // 完全匹配检查
        if (fallbacks.containsKey(lowerMessage)) {
            return fallbacks.get(lowerMessage);
        }

        // 模糊匹配：检查是否以关键词开头（如 "你好呀"、"谢谢你啊"）
        for (Map.Entry<String, String> entry : fallbacks.entrySet()) {
            if (lowerMessage.startsWith(entry.getKey()) && lowerMessage.length() <= entry.getKey().length() + 5) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 检测用户消息语言
     */
    private String detectLanguage(String message) {
        // 简单判断：如果包含中文字符，则为中文
        for (char c : message.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                return "zh";
            }
        }
        return "en";
    }

    /**
     * 使用轻量模型提炼复杂问题意图（仅在低置信度时使用）
     */
    private String refineIntentWithAi(String userMessage, String language) {
        // 构建简单的意图提炼提示词
        String prompt = String.format("""
                请分析以下用户问题的意图，返回一个简短的意图类别关键词（单个词）。
                
                用户问题：%s
                语言：%s
                
                可能的意图类别：
                - route（路由配置）
                - service（服务配置）
                - strategy（策略配置）
                - auth（认证配置）
                - monitor（监控诊断）
                - instance（实例管理）
                - alert（告警管理）
                - debug（问题排查）
                - performance（性能优化）
                - general（一般问题）
                
                只返回一个意图关键词，不要解释。""", userMessage, language);

        try {
            // 使用轻量模型快速提炼
            String response = callAiApi(prompt, null, "qwen-turbo");
            String intent = response.trim().toLowerCase();

            // 验证返回的意图是否有效
            if (promptService.isValidIntent(intent)) {
                log.info("AI refined intent: {} for message: {}", intent, userMessage);
                return intent;
            }
        } catch (Exception e) {
            log.warn("Intent refinement failed: {}", e.getMessage());
        }

        return "general";
    }

    /**
     * Chat with the AI Copilot.
     */
    public ChatResponse chat(String sessionId, String userMessage, String context, String instanceId) {
        return chat(sessionId, userMessage, context, instanceId, null, null);
    }

    /**
     * Chat with the AI Copilot with specified provider and model.
     * 支持工具调用循环（Function Calling）.
     */
    public ChatResponse chat(String sessionId, String userMessage, String context, String instanceId,
                             String provider, String model) {
        log.info("AI Copilot chat: session={}, context={}, instance={}, provider={}, model={}",
                sessionId, context, instanceId, provider, model);

        try {
            // Get conversation history
            List<ChatMessage> history = conversationHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());

            // 检测语言
            String language = detectLanguage(userMessage);

            // === 本地兜底（常见问题直接回复）===
            String localResponse = checkLocalFallback(userMessage, language);
            if (localResponse != null) {
                log.info("Local fallback matched for: {}", userMessage);
                history.add(new ChatMessage("user", userMessage));
                history.add(new ChatMessage("assistant", localResponse));
                return new ChatResponse(true, localResponse, null);
            }

            // 智能意图识别（用于构建针对性提示词）
            PromptService.IntentResult intentResult = promptService.detectIntent(userMessage);

            // 上下文记忆延续
            String lastIntent = sessionLastIntent.get(sessionId);
            if (intentResult.score() < 5 && lastIntent != null) {
                log.info("Using lastIntent as fallback: {} (current score: {})", lastIntent, intentResult.score());
                intentResult = new PromptService.IntentResult(lastIntent, 3, false, true);
            }

            log.info("Final intent: {}, score: {}", intentResult.intent(), intentResult.score());
            sessionLastIntent.put(sessionId, intentResult.intent());

            // 构建系统提示词（使用 PromptService）
            String effectiveContext = (context != null && !context.isEmpty()) ? context : intentResult.intent();
            String systemPrompt = promptService.buildSystemPrompt(language, effectiveContext);

            // === 工具调用循环 ===
            // 构建 messages 数组（Function Calling 格式）
            List<Map<String, Object>> messages = buildMessagesForTools(history, systemPrompt, userMessage);

            // 获取工具定义
            List<Map<String, Object>> tools = toolRegistry.getOpenAITools();

            // 获取 AI 提供商配置
            AiConfig config = getAiConfig(provider);

            // 工具调用循环（带置信度）
            ToolCallLoopResult loopResult = runToolCallLoopWithConfidence(config, model, messages, tools);
            
            // 构建最终响应（添加置信度标记）
            String finalResponse = loopResult.getContent();
            
            // 在响应末尾添加置信度信息（仅在非本地兜底情况下）
            if (loopResult.getConfidence() > 0) {
                String confidenceMark = language.equals("zh") 
                    ? String.format("\n\n> AI 置信度：%d%%（%s）", loopResult.getConfidence(), loopResult.getConfidenceReason())
                    : String.format("\n\n> AI Confidence: %d%% (%s)", loopResult.getConfidence(), loopResult.getConfidenceReason());
                finalResponse = finalResponse + confidenceMark;
            }

            // 更新历史记录
            history.add(new ChatMessage("user", userMessage));
            history.add(new ChatMessage("assistant", finalResponse));

            // 限制历史大小
            while (history.size() > 20) {
                history.remove(0);
            }

            return new ChatResponse(true, finalResponse, null, loopResult.getConfidence(), loopResult.getConfidenceReason());

        } catch (Exception e) {
            log.error("AI Copilot chat failed", e);
            return new ChatResponse(false, null, "AI 服务错误: " + e.getMessage());
        }
    }

    /**
     * 工具调用循环（Function Calling 核心）
     * 返回包含置信度信息的结果
     */
    private ToolCallLoopResult runToolCallLoopWithConfidence(AiConfig config, String model,
                                   List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools) {

        String effectiveModel = (model != null && !model.isEmpty()) ? model : config.getModel();
        String provider = config.getProvider();

        // 工具调用统计
        Set<String> calledTools = new HashSet<>();
        int successfulTools = 0;
        int totalToolCalls = 0;
        Set<String> dataTypes = new HashSet<>();  // 获取的数据类型

        // 最多调用 MAX_TOOL_CALL_ITERATIONS 轮
        for (int iteration = 0; iteration < MAX_TOOL_CALL_ITERATIONS; iteration++) {
            log.info("Tool call iteration: {} for provider: {}", iteration + 1, provider);

            // 调用 AI API（带工具）
            AiAnalysisService.AiResponse response = aiAnalysisService.chatWithTools(
                    provider, effectiveModel, messages, tools);

            // 如果没有工具调用，返回最终回复
            if (!response.hasToolCalls()) {
                log.info("No tool calls, returning final response");
                
                // 计算置信度
                int confidence = calculateConfidence(calledTools, successfulTools, dataTypes);
                String reason = buildConfidenceReason(calledTools, dataTypes);
                
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

                // 记录工具调用
                calledTools.add(toolCall.getName());
                totalToolCalls++;

                // 执行工具
                ToolExecutor.ToolResult result = toolExecutor.execute(
                        toolCall.getName(), toolCall.getArguments());

                long duration = System.currentTimeMillis() - startTime;
                log.info("Tool {} executed in {} ms, success={}",
                        toolCall.getName(), duration, result.isSuccess());

                if (result.isSuccess()) {
                    successfulTools++;
                    // 根据工具类型记录数据类型
                    recordDataType(toolCall.getName(), dataTypes);
                }

                // 将工具结果加入 messages
                messages.add(buildToolResultMessage(provider, toolCall.getId(),
                        toolCall.getName(), result));
            }
        }

        // 超过最大轮数
        log.warn("Max tool call iterations reached: {}", MAX_TOOL_CALL_ITERATIONS);
        int confidence = calculateConfidence(calledTools, successfulTools, dataTypes);
        String reason = buildConfidenceReason(calledTools, dataTypes) + " (达到调用上限)";
        
        return new ToolCallLoopResult(
            "工具调用次数达到上限，请简化您的问题或分步提问。",
            Math.max(50, confidence - 20),  // 降低置信度
            reason, calledTools, dataTypes
        );
    }

    /**
     * 根据工具类型记录获取的数据类型
     */
    private void recordDataType(String toolName, Set<String> dataTypes) {
        if (toolName.startsWith("list_routes") || toolName.startsWith("get_route")) {
            dataTypes.add("route");
        } else if (toolName.startsWith("list_services") || toolName.startsWith("get_service") 
                || toolName.startsWith("nacos_service")) {
            dataTypes.add("service");
        } else if (toolName.startsWith("get_gateway_metrics") || toolName.startsWith("get_history_metrics")) {
            dataTypes.add("metrics");
        } else if (toolName.startsWith("run_diagnostic")) {
            dataTypes.add("diagnostic");
        } else if (toolName.startsWith("audit")) {
            dataTypes.add("audit");
        } else if (toolName.startsWith("list_instances") || toolName.startsWith("get_instance")) {
            dataTypes.add("instance");
        }
    }

    /**
     * 计算置信度分数
     * 
     * 基础分数: 70
     * 工具调用验证: +15
     * 数据完整性: +10 (有 route + service + metrics)
     * 多工具验证: +5 (超过3个不同工具)
     */
    private int calculateConfidence(Set<String> calledTools, int successfulTools, Set<String> dataTypes) {
        int confidence = 70;  // 基础分数

        // 工具调用加分
        if (!calledTools.isEmpty()) {
            confidence += 15;
        }

        // 数据完整性加分
        if (dataTypes.contains("route") && dataTypes.contains("service")) {
            confidence += 5;
        }
        if (dataTypes.contains("metrics")) {
            confidence += 3;
        }
        if (dataTypes.contains("diagnostic")) {
            confidence += 2;
        }

        // 多工具验证加分
        if (calledTools.size() >= 3) {
            confidence += 5;
        }

        // 成功率影响
        if (successfulTools > 0 && calledTools.size() > 0) {
            double successRate = successfulTools / calledTools.size();
            if (successRate >= 0.9) {
                confidence += 5;
            } else if (successRate < 0.5) {
                confidence -= 10;
            }
        }

        // 限制范围
        return Math.min(100, Math.max(0, confidence));
    }

    /**
     * 构建置信度原因说明
     */
    private String buildConfidenceReason(Set<String> calledTools, Set<String> dataTypes) {
        if (calledTools.isEmpty()) {
            return "未调用工具验证";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已调用工具: ").append(calledTools.size()).append("个");
        
        if (!dataTypes.isEmpty()) {
            sb.append("，验证数据: ").append(String.join("+", dataTypes));
        }

        return sb.toString();
    }

    /**
     * 工具调用循环结果（包含置信度）
     */
    private static class ToolCallLoopResult {
        private final String content;
        private final int confidence;
        private final String confidenceReason;
        private final Set<String> calledTools;
        private final Set<String> dataTypes;

        ToolCallLoopResult(String content, int confidence, String confidenceReason,
                          Set<String> calledTools, Set<String> dataTypes) {
            this.content = content;
            this.confidence = confidence;
            this.confidenceReason = confidenceReason;
            this.calledTools = calledTools;
            this.dataTypes = dataTypes;
        }

        String getContent() { return content; }
        int getConfidence() { return confidence; }
        String getConfidenceReason() { return confidenceReason; }
        Set<String> getCalledTools() { return calledTools; }
        Set<String> getDataTypes() { return dataTypes; }
    }

    /**
     * 构建 Function Calling 格式的 messages 数组
     */
    private List<Map<String, Object>> buildMessagesForTools(List<ChatMessage> history,
                                                            String systemPrompt,
                                                            String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 1. System prompt
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 2. 历史对话（不包含 tool 相关消息，因为格式不同）
        for (ChatMessage msg : history) {
            // 只添加普通 user/assistant 消息
            if ("user".equals(msg.role) || "assistant".equals(msg.role)) {
                messages.add(Map.of("role", msg.role, "content", msg.content));
            }
        }

        // 3. 当前用户消息
        messages.add(Map.of("role", "user", "content", userMessage));

        return messages;
    }

    /**
     * 构建 assistant 的 tool_calls 消息
     */
    private Map<String, Object> buildAssistantToolCallsMessage(AiAnalysisService.AiResponse response) {
        // OpenAI 格式：assistant 消息带 tool_calls
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
        message.put("content", response.getContent());  // 可能为 null
        message.put("tool_calls", toolCalls);

        return message;
    }

    /**
     * 构建工具结果消息（按提供商格式）
     */
    private Map<String, Object> buildToolResultMessage(String provider, String toolCallId,
                                                       String toolName, ToolExecutor.ToolResult result) {
        String resultJson = result.toJson();

        // 根据提供商返回不同格式
        return switch (provider) {
            case "CLAUDE" -> aiAnalysisService.buildClaudeToolResultMessage(toolCallId, resultJson);
            case "GEMINI" -> aiAnalysisService.buildGeminiToolResultMessage(toolName, resultJson);
            default -> aiAnalysisService.buildOpenAIToolResultMessage(toolCallId, resultJson);
        };
    }

    /**
     * 获取 AI 配置
     */
    private AiConfig getAiConfig(String provider) {
        if (provider != null && !provider.isEmpty()) {
            return aiConfigRepository.findByProvider(provider)
                    .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));
        }

        return aiConfigRepository.findFirstValidConfig()
                .orElseThrow(() -> new RuntimeException("未配置 AI 服务。请在监控页面配置 AI 提供商。"));
    }

    /**
     * Clear conversation history.
     */
    public void clearHistory(String sessionId) {
        conversationHistory.remove(sessionId);
        sessionLastIntent.remove(sessionId);  // 同时清除意图记忆
    }

    /**
     * Generate route configuration from natural language.
     */
    public RouteGenerationResult generateRoute(String description, String instanceId) {
        return generateRoute(description, instanceId, null, null);
    }

    /**
     * Generate route configuration from natural language with specified provider and model.
     */
    public RouteGenerationResult generateRoute(String description, String instanceId, String provider, String model) {
        log.info("Generating route from description: {}, provider={}, model={}", description, provider, model);

        // 获取现有服务列表作为参考
        String serviceList = getExistingServiceNames(instanceId);
        // 获取现有路由命名风格作为参考
        String routeNameExamples = getExistingRouteNameExamples(instanceId);

        // 从数据库获取提示词模板（支持动态更新）
        String promptTemplate = promptService.getPrompt("task.generateRoute.zh",
                "你是 Spring Cloud Gateway 网关的路由配置专家。请根据用户描述生成路由配置。返回有效的 JSON。");

        // 替换模板变量
        String prompt = promptTemplate
                .replace("{description}", description)
                .replace("{serviceList}", serviceList)
                .replace("{routeNameExamples}", routeNameExamples);

        try {
            String response = callAiApi(prompt, provider, model);
            String json = extractJson(response);
            return new RouteGenerationResult(true, json, null);
        } catch (Exception e) {
            log.error("Route generation failed", e);
            return new RouteGenerationResult(false, null, "路由生成失败: " + e.getMessage());
        }
    }

    /**
     * 获取现有服务名称列表
     */
    private String getExistingServiceNames(String instanceId) {
        try {
            List<?> services;
            if (instanceId != null && !instanceId.isEmpty()) {
                services = serviceService.getAllServicesByInstanceId(instanceId);
            } else {
                services = serviceService.getAllServices();
            }

            if (services == null || services.isEmpty()) {
                return "暂无已有服务，请用户指定服务名";
            }

            StringBuilder sb = new StringBuilder();
            for (Object s : services) {
                if (s instanceof Map) {
                    Object name = ((Map<?, ?>) s).get("name");
                    if (name != null) {
                        sb.append("- ").append(name).append("\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to get existing services: {}", e.getMessage());
            return "服务列表获取失败，请用户指定服务名";
        }
    }

    /**
     * 获取现有路由命名示例
     */
    private String getExistingRouteNameExamples(String instanceId) {
        try {
            List<?> routes;
            if (instanceId != null && !instanceId.isEmpty()) {
                routes = routeService.getAllRoutesByInstanceId(instanceId);
            } else {
                routes = routeService.getAllRoutes();
            }

            if (routes == null || routes.isEmpty()) {
                return "暂无已有路由，命名建议：xxx-api、xxx-service-route";
            }

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Object r : routes) {
                if (r instanceof Map) {
                    Object name = ((Map<?, ?>) r).get("routeName");
                    if (name != null) {
                        sb.append("- ").append(name).append("\n");
                        count++;
                        if (count >= 5) break;  // 只显示5个示例
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to get existing routes: {}", e.getMessage());
            return "路由列表获取失败，命名建议：xxx-api、xxx-service-route";
        }
    }

    /**
     * Analyze an error and provide debugging suggestions.
     */
    public DebugAnalysis analyzeError(String errorMessage, String instanceId) {
        return analyzeError(errorMessage, instanceId, null, null);
    }

    /**
     * Analyze an error and provide debugging suggestions with specified provider and model.
     * 
     * 组合方案实现：
     * 1. 先智能过滤少量相关路由（避免 prompt 过长）
     * 2. 再让 AI 用 Function Calling 深入查询（自主决定调用工具）
     */
    public DebugAnalysis analyzeError(String errorMessage, String instanceId, String provider, String model) {
        log.info("Analyzing error for instance: {}, provider={}, model={}", instanceId, provider, model);

        try {
            // === Step 1: 智能过滤相关路由 ===
            String errorPath = extractPathFromError(errorMessage);
            String relevantRoutesInfo = findRelevantRoutesForError(errorPath, instanceId);
            log.info("Smart route filtering: errorPath={}, relevantRoutes found", errorPath);

            // 获取诊断数据
            Map<String, Object> diagnostics = getDiagnosticsSummary(instanceId);

            // 获取实时监控指标
            Map<String, Object> metrics = null;
            try {
                metrics = prometheusService.getGatewayMetrics();
            } catch (Exception e) {
                log.warn("Failed to get metrics for error analysis: {}", e.getMessage());
            }
            String metricsSummary = formatMetricsForErrorAnalysis(metrics);

            // 获取路由数量和服务数量
            int routeCount = getRouteCount(instanceId);
            int serviceCount = getServiceCount(instanceId);

            // === Step 2: 构建 Function Calling 消息 ===
            // 从数据库获取提示词模板（支持动态更新）
            String systemPrompt = promptService.getPrompt("task.analyzeError.zh",
                    "你是 Spring Cloud Gateway 网关的错误诊断专家。用中文回答，Markdown格式。以工具查询数据为绝对权威。");

            // 构建用户消息（包含错误信息和预过滤的相关路由）
            String userMessage = String.format("""
                    ## 错误信息
                    %s
                    
                    ## 当前系统状态
                    %s
                    
                    ## 实时监控指标
                    %s
                    
                    ## 系统规模
                    - 路由数量: %d
                    - 服务数量: %d
                    
                    ## 智能过滤的相关路由（预分析）
                    %s
                    
                    请分析这个错误，如果需要更详细的路由/服务配置，请主动调用工具查询。
                    """, errorMessage, formatDiagnostics(diagnostics), metricsSummary, routeCount, serviceCount, relevantRoutesInfo);

            // 构建 messages 数组
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userMessage));

            // 获取工具定义
            List<Map<String, Object>> tools = toolRegistry.getOpenAITools();

            // 获取 AI 配置
            AiConfig config = getAiConfig(provider);

            // === Step 3: Function Calling 循环 ===
            ToolCallLoopResult loopResult = runToolCallLoopWithConfidence(config, model, messages, tools);
            
            // 构建最终响应（添加置信度标记）
            String finalAnalysis = loopResult.getContent();
            String confidenceMark = String.format("\n\n> AI 置信度：%d%%（%s）", 
                loopResult.getConfidence(), loopResult.getConfidenceReason());
            finalAnalysis = finalAnalysis + confidenceMark;

            return new DebugAnalysis(true, finalAnalysis, null, loopResult.getConfidence(), loopResult.getConfidenceReason());

        } catch (Exception e) {
            log.error("Error analysis failed", e);
            return new DebugAnalysis(false, null, "错误分析失败: " + e.getMessage());
        }
    }

    /**
     * 格式化监控指标用于错误分析
     */
    private String formatMetricsForErrorAnalysis(Map<String, Object> metrics) {
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
     * 获取路由数量
     */
    private int getRouteCount(String instanceId) {
        try {
            List<?> routes;
            if (instanceId != null && !instanceId.isEmpty()) {
                routes = routeService.getAllRoutesByInstanceId(instanceId);
            } else {
                routes = routeService.getAllRoutes();
            }
            return routes != null ? routes.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取服务数量
     */
    private int getServiceCount(String instanceId) {
        try {
            List<?> services;
            if (instanceId != null && !instanceId.isEmpty()) {
                services = serviceService.getAllServicesByInstanceId(instanceId);
            } else {
                services = serviceService.getAllServices();
            }
            return services != null ? services.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ===================== 智能路由过滤（用于错误分析） =====================

    /**
     * 从错误信息中提取请求路径
     * 支持格式：
     * - "请求路径: /api/v2/users/123"
     * - "path: /api/v2/users/123"
     * - "No matching route found for path /api/v2/users/123"
     */
    private String extractPathFromError(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return null;
        }

        // 尝试多种格式匹配
        String[] patterns = {
            "请求路径:", "request path:", "path:",
            "for path", "路径:", "url:", "URI:"
        };

        for (String pattern : patterns) {
            int idx = errorMessage.toLowerCase().indexOf(pattern.toLowerCase());
            if (idx >= 0) {
                // 从 pattern 后面提取路径
                int start = idx + pattern.length();
                String remaining = errorMessage.substring(start).trim();
                
                // 提取第一个看起来像路径的部分（以 / 开头）
                int pathStart = remaining.indexOf('/');
                if (pathStart >= 0) {
                    String path = remaining.substring(pathStart).trim();
                    // 清理路径（去掉末尾的非路径字符）
                    path = path.split("[\\s,\\n\\r]")[0];
                    return path;
                }
            }
        }

        // 如果没找到明确的路径标记，尝试直接找以 / 开头的路径
        int slashIdx = errorMessage.indexOf('/');
        if (slashIdx >= 0) {
            String path = errorMessage.substring(slashIdx).trim();
            path = path.split("[\\s,\\n\\r]")[0];
            return path;
        }

        return null;
    }

    /**
     * 智能过滤与错误路径相关的路由
     * 最多返回 5 条相关路由，避免 prompt 过长
     */
    private String findRelevantRoutesForError(String errorPath, String instanceId) {
        if (errorPath == null || errorPath.isEmpty()) {
            // 无错误路径时，只返回路由数量提示
            int count = getRouteCount(instanceId);
            if (count == 0) {
                return "**当前无任何路由配置**";
            } else if (count <= 10) {
                return "**路由数量较少（" + count + "条），可使用工具 `list_routes` 查看完整列表**";
            } else {
                return "**路由数量较多（" + count + "条），建议使用工具 `list_routes` 或 `get_route_detail` 查询特定路由**";
            }
        }

        try {
            List<?> allRoutes;
            if (instanceId != null && !instanceId.isEmpty()) {
                allRoutes = routeService.getAllRoutesByInstanceId(instanceId);
            } else {
                allRoutes = routeService.getAllRoutes();
            }

            if (allRoutes == null || allRoutes.isEmpty()) {
                return "**当前无任何路由配置**";
            }

            // 从错误路径提取关键词
            String[] pathSegments = errorPath.split("/");
            Set<String> keywords = new HashSet<>();
            for (String seg : pathSegments) {
                if (seg.length() >= 2) {  // 忽略太短的片段
                    keywords.add(seg.toLowerCase());
                }
            }

            // 过滤相关路由（predicates 中的 pattern 包含关键词）
            List<Map<String, Object>> relevantRoutes = new ArrayList<>();
            for (Object r : allRoutes) {
                if (r instanceof Map) {
                    Map<?, ?> route = (Map<?, ?>) r;
                    String routeName = route.get("routeName") != null ? route.get("routeName").toString() : "";
                    String predicatesStr = "";
                    
                    // 检查 predicates
                    Object predicates = route.get("predicates");
                    if (predicates instanceof List) {
                        for (Object p : (List<?>) predicates) {
                            if (p instanceof Map) {
                                Object args = ((Map<?, ?>) p).get("args");
                                if (args instanceof Map) {
                                    Object pattern = ((Map<?, ?>) args).get("pattern");
                                    if (pattern != null) {
                                        predicatesStr += pattern.toString() + " ";
                                    }
                                }
                            }
                        }
                    }

                    // 判断是否相关：路由名或 predicates 包含关键词
                    boolean isRelevant = false;
                    String lowerPredicates = predicatesStr.toLowerCase();
                    String lowerName = routeName.toLowerCase();
                    
                    for (String kw : keywords) {
                        if (lowerPredicates.contains(kw) || lowerName.contains(kw)) {
                            isRelevant = true;
                            break;
                        }
                    }

                    if (isRelevant) {
                        Map<String, Object> simplified = new LinkedHashMap<>();
                        simplified.put("routeName", routeName);
                        simplified.put("predicates", predicates);
                        simplified.put("enabled", route.get("enabled"));
                        relevantRoutes.add(simplified);
                    }

                    // 最多 5 条相关路由
                    if (relevantRoutes.size() >= 5) {
                        break;
                    }
                }
            }

            // 构建结果
            if (relevantRoutes.isEmpty()) {
                return "**未找到与路径 `" + errorPath + "` 相关的路由配置**\n" +
                       "**可能原因**：路径不匹配任何现有路由的 predicates\n" +
                       "**建议**：使用工具 `list_routes` 查看所有路由配置";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("**与路径 `").append(errorPath).append("` 可能相关的路由（").append(relevantRoutes.size()).append("条）**:\n");
            
            try {
                String routesJson = objectMapper.writeValueAsString(relevantRoutes);
                sb.append("```json\n").append(routesJson).append("\n```\n");
            } catch (Exception e) {
                // JSON 序列化失败时，简化输出
                for (Map<String, Object> route : relevantRoutes) {
                    sb.append("- ").append(route.get("routeName")).append("\n");
                }
            }

            sb.append("\n**提示**：可使用工具 `get_route_detail` 查看完整配置，或 `list_routes` 查看所有路由");
            return sb.toString();

        } catch (Exception e) {
            log.warn("Failed to find relevant routes: {}", e.getMessage());
            return "**路由查询失败，请使用工具 `list_routes` 手动查询**";
        }
    }

    /**
     * Get performance optimization suggestions.
     */
    public OptimizationResult suggestOptimizations(String instanceId) {
        return suggestOptimizations(instanceId, null, null);
    }

    /**
     * Get performance optimization suggestions with specified provider and model.
     */
    public OptimizationResult suggestOptimizations(String instanceId, String provider, String model) {
        log.info("Getting optimization suggestions for instance: {}, provider={}, model={}", instanceId, provider, model);

        Map<String, Object> diagnostics = getDiagnosticsSummary(instanceId);

        // 获取实时指标数据
        Map<String, Object> metrics = null;
        try {
            metrics = prometheusService.getGatewayMetrics();
        } catch (Exception e) {
            log.warn("Failed to get metrics for optimization suggestions: {}", e.getMessage());
        }

        String metricsSummary = formatMetricsForOptimization(metrics);

        // 获取系统规模信息
        int routeCount = getRouteCount(instanceId);
        int serviceCount = getServiceCount(instanceId);
        String instanceSpec = getInstanceSpecInfo(instanceId);

        // 从数据库获取提示词模板（支持动态更新）
        String promptTemplate = promptService.getPrompt("task.suggestOptimizations.zh",
                "你是 Spring Cloud Gateway 网关的性能优化专家。请基于以下数据给出优化建议。");

        // 替换模板变量
        String prompt = promptTemplate
                .replace("{diagnostics}", formatDiagnostics(diagnostics))
                .replace("{metricsSummary}", metricsSummary)
                .replace("{routeCount}", String.valueOf(routeCount))
                .replace("{serviceCount}", String.valueOf(serviceCount))
                .replace("{instanceSpec}", instanceSpec);

        try {
            String suggestions = callAiApi(prompt, provider, model);
            return new OptimizationResult(true, suggestions, null);
        } catch (Exception e) {
            log.error("Optimization suggestion failed", e);
            return new OptimizationResult(false, null, "优化建议生成失败: " + e.getMessage());
        }
    }

    /**
     * 获取实例规格信息
     */
    private String getInstanceSpecInfo(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return "未指定实例";
        }
        try {
            var instance = gatewayInstanceService.getInstanceByInstanceId(instanceId);
            if (instance != null) {
                String specType = instance.getSpecType();
                Double cpuCores = instance.getCpuCores();
                Integer memoryMB = instance.getMemoryMB();
                return String.format("%s（CPU: %.1f核, 内存: %dMB）",
                        specType != null ? specType : "未知",
                        cpuCores != null ? cpuCores : 0,
                        memoryMB != null ? memoryMB : 0);
            }
        } catch (Exception e) {
            log.warn("Failed to get instance spec: {}", e.getMessage());
        }
        return "规格信息获取失败";
    }

    /**
     * 格式化监控指标用于优化建议
     */
    private String formatMetricsForOptimization(Map<String, Object> metrics) {
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

    private String formatBytes(double bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = (int) (Math.log(bytes) / Math.log(1024));
        unitIndex = Math.min(unitIndex, units.length - 1);
        double value = bytes / Math.pow(1024, unitIndex);
        return String.format("%.2f %s", value, units[unitIndex]);
    }

    /**
     * Explain a configuration concept.
     */
    public String explainConcept(String concept) {
        return explainConcept(concept, null, null, null);
    }

    /**
     * Explain a configuration concept with specified provider and model.
     */
    public String explainConcept(String concept, String provider, String model) {
        return explainConcept(concept, provider, model, null);
    }

    /**
     * Explain a configuration concept with system context.
     * 
     * @param concept Concept name to explain
     * @param provider AI provider
     * @param model AI model
     * @param instanceId Gateway instance ID (for context)
     */
    public String explainConcept(String concept, String provider, String model, String instanceId) {
        log.info("Explaining concept: {}, instanceId={}", concept, instanceId);

        // 获取系统上下文
        int routeCount = getRouteCount(instanceId);
        int serviceCount = getServiceCount(instanceId);

        // 通过意图识别确定概念所属领域，注入对应的领域专业知识
        String language = detectLanguage(concept);
        PromptService.IntentResult intentResult = promptService.detectIntent(concept);
        String domainKnowledge = promptService.buildSystemPrompt(language, intentResult.intent());
        log.info("Concept '{}' matched domain: {} (score: {})", concept, intentResult.intent(), intentResult.score());

        // 从数据库获取提示词模板（支持动态更新）
        String promptTemplate = promptService.getPrompt("task.explainConcept.zh",
                "你是 Spring Cloud Gateway 网关的技术专家。请解释以下概念。");

        // 替换模板变量
        String prompt = promptTemplate
                .replace("{domainKnowledge}", domainKnowledge)
                .replace("{concept}", concept)
                .replace("{routeCount}", String.valueOf(routeCount))
                .replace("{serviceCount}", String.valueOf(serviceCount));

        try {
            return callAiApi(prompt, provider, model);
        } catch (Exception e) {
            log.error("Concept explanation failed", e);
            return "概念解释失败: " + e.getMessage();
        }
    }

    // ============== Private Methods ==============

    /**
     * 构建丰富的上下文数据（包含路由、服务、指标等）
     */
    private String buildRichContextData(String instanceId, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前时间: ").append(new Date()).append("\n");

        if (instanceId != null) {
            sb.append("实例 ID: ").append(instanceId).append("\n");

            try {
                // 获取诊断信息
                Map<String, Object> diagnostics = getDiagnosticsSummary(instanceId);
                sb.append("\n【系统状态】\n");
                sb.append(formatDiagnostics(diagnostics));

                // 获取实时指标（用于性能和调试场景）
                if ("performance".equals(context) || "debug".equals(context) || "monitor".equals(context)) {
                    try {
                        Map<String, Object> metrics = prometheusService.getGatewayMetrics();
                        sb.append("\n【实时指标】\n");

                        // CPU
                        Map<String, Object> cpu = (Map<String, Object>) metrics.get("cpu");
                        if (cpu != null) {
                            sb.append("- CPU使用率: 进程 ")
                                    .append(String.format("%.1f%%", getDoubleValue(cpu, "processUsage")))
                                    .append(", 系统 ")
                                    .append(String.format("%.1f%%", getDoubleValue(cpu, "systemUsage")))
                                    .append("\n");
                        }

                        // 内存
                        Map<String, Object> memory = (Map<String, Object>) metrics.get("jvmMemory");
                        if (memory != null) {
                            sb.append("- 堆内存使用率: ")
                                    .append(String.format("%.1f%%", getDoubleValue(memory, "heapUsagePercent")))
                                    .append("\n");
                        }

                        // HTTP请求
                        Map<String, Object> http = (Map<String, Object>) metrics.get("httpRequests");
                        if (http != null) {
                            sb.append("- 每秒请求数(QPS): ")
                                    .append(String.format("%.2f", getDoubleValue(http, "requestsPerSecond")))
                                    .append("\n");
                            sb.append("- 平均响应时间: ")
                                    .append(String.format("%.2f", getDoubleValue(http, "avgResponseTimeMs")))
                                    .append(" ms\n");
                            sb.append("- 错误率: ")
                                    .append(String.format("%.2f%%", getDoubleValue(http, "errorRate")))
                                    .append("\n");
                        }

                        // 线程
                        Map<String, Object> threads = (Map<String, Object>) metrics.get("threads");
                        if (threads != null) {
                            sb.append("- 活跃线程数: ")
                                    .append(getIntValue(threads, "liveThreads"))
                                    .append("\n");
                        }
                    } catch (Exception e) {
                        sb.append("- 指标获取失败: ").append(e.getMessage()).append("\n");
                    }
                }

            } catch (Exception e) {
                sb.append("无法获取网关状态: ").append(e.getMessage()).append("\n");
            }
        }

        // 添加安全提醒
        sb.append("\n【安全提醒】请勿在对话中分享 API Key、密码或其他敏感信息。\n");

        return sb.toString();
    }

    private String buildFullPrompt(List<ChatMessage> history, String userMessage,
                                   String systemPrompt, String contextData) {
        StringBuilder sb = new StringBuilder();

        sb.append(systemPrompt).append("\n\n");
        sb.append("上下文信息:\n").append(contextData).append("\n\n");

        if (!history.isEmpty()) {
            sb.append("历史对话:\n");
            for (ChatMessage msg : history) {
                sb.append(msg.role).append(": ").append(msg.content).append("\n");
            }
            sb.append("\n");
        }

        sb.append("用户: ").append(userMessage);

        return sb.toString();
    }

    private String callAiApi(String prompt) {
        return callAiApi(prompt, null, null);
    }

    private String callAiApi(String prompt, String provider, String model) {
        // If provider is specified, use it; otherwise get the first valid config
        if (provider != null && !provider.isEmpty()) {
            return aiAnalysisService.callAiApi(provider, model, prompt);
        }

        // Get enabled AI config
        AiConfig config = aiConfigRepository.findFirstValidConfig()
                .orElseThrow(() -> new RuntimeException("未配置 AI 服务。请在监控页面配置 AI 提供商或在 AI Copilot 中选择一个。"));

        String effectiveModel = (model != null && !model.isEmpty()) ? model : config.getModel();
        return aiAnalysisService.callAiApi(config.getProvider(), effectiveModel, prompt);
    }

    private String extractJson(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }

        return response;
    }

    private Map<String, Object> getDiagnosticsSummary(String instanceId) {
        Map<String, Object> summary = new LinkedHashMap<>();

        try {
            DiagnosticService.DiagnosticReport report = diagnosticService.runQuickDiagnostic();
            summary.put("健康评分", report.getOverallScore());
            summary.put("数据库状态", report.getDatabase() != null ? report.getDatabase().getStatus() : "未知");
            summary.put("Redis状态", report.getRedis() != null ? report.getRedis().getStatus() : "未知");
            summary.put("配置中心状态", report.getConfigCenter() != null ? report.getConfigCenter().getStatus() : "未知");
        } catch (Exception e) {
            summary.put("诊断错误", e.getMessage());
        }

        return summary;
    }

    private String formatDiagnostics(Map<String, Object> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : diagnostics.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    // 辅助方法：从 Map 中获取 double 值
    private double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    // 辅助方法：从 Map 中获取 int 值
    private int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    // ============== Data Classes ==============

    private static class ChatMessage {
        String role;
        String content;

        ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class ChatResponse {
        private boolean success;
        private String response;
        private String error;
        private int confidence;  // AI 置信度 (0-100)
        private String confidenceReason;  // 置信度原因说明

        public ChatResponse(boolean success, String response, String error) {
            this.success = success;
            this.response = response;
            this.error = error;
            this.confidence = 70;  // 默认中等置信度
            this.confidenceReason = "基于基础分析";
        }

        public ChatResponse(boolean success, String response, String error, int confidence, String confidenceReason) {
            this.success = success;
            this.response = response;
            this.error = error;
            this.confidence = confidence;
            this.confidenceReason = confidenceReason;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            if (response != null) map.put("response", response);
            if (error != null) map.put("error", error);
            map.put("confidence", confidence);
            map.put("confidenceReason", confidenceReason);
            return map;
        }

        // Getters
        public boolean isSuccess() {
            return success;
        }

        public String getResponse() {
            return response;
        }

        public String getError() {
            return error;
        }

        public int getConfidence() {
            return confidence;
        }

        public String getConfidenceReason() {
            return confidenceReason;
        }
    }

    public static class RouteGenerationResult {
        private boolean success;
        private String config;
        private String error;

        public RouteGenerationResult(boolean success, String config, String error) {
            this.success = success;
            this.config = config;
            this.error = error;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            if (config != null) map.put("config", config);
            if (error != null) map.put("error", error);
            return map;
        }

        // Getters
        public boolean isSuccess() {
            return success;
        }

        public String getConfig() {
            return config;
        }

        public String getError() {
            return error;
        }
    }

    public static class DebugAnalysis {
        private boolean success;
        private String analysis;
        private String error;
        private int confidence;
        private String confidenceReason;

        public DebugAnalysis(boolean success, String analysis, String error) {
            this.success = success;
            this.analysis = analysis;
            this.error = error;
            this.confidence = 70;
            this.confidenceReason = "基于基础分析";
        }

        public DebugAnalysis(boolean success, String analysis, String error, int confidence, String confidenceReason) {
            this.success = success;
            this.analysis = analysis;
            this.error = error;
            this.confidence = confidence;
            this.confidenceReason = confidenceReason;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            if (analysis != null) map.put("analysis", analysis);
            if (error != null) map.put("error", error);
            map.put("confidence", confidence);
            map.put("confidenceReason", confidenceReason);
            return map;
        }

        // Getters
        public boolean isSuccess() {
            return success;
        }

        public String getAnalysis() {
            return analysis;
        }

        public String getError() {
            return error;
        }

        public int getConfidence() {
            return confidence;
        }

        public String getConfidenceReason() {
            return confidenceReason;
        }
    }

    public static class OptimizationResult {
        private boolean success;
        private String suggestions;
        private String error;

        public OptimizationResult(boolean success, String suggestions, String error) {
            this.success = success;
            this.suggestions = suggestions;
            this.error = error;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            if (suggestions != null) map.put("suggestions", suggestions);
            if (error != null) map.put("error", error);
            return map;
        }

        // Getters
        public boolean isSuccess() {
            return success;
        }

        public String getSuggestions() {
            return suggestions;
        }

        public String getError() {
            return error;
        }
    }
}