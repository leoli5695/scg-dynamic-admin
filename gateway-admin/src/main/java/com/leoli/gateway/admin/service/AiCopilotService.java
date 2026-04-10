package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiConfig;
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
 * 提示词管理委托给 AiCopilotPrompts 服务：
 * - 意图检测：AiCopilotPrompts.detectIntent()
 * - 提示词构建：AiCopilotPrompts.buildSystemPrompt()
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

    // 提示词管理服务
    private final AiCopilotPrompts aiCopilotPrompts;

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
        String prompt = aiCopilotPrompts.getIntentRefinementPrompt(userMessage, language);

        try {
            // 使用轻量模型快速提炼
            String response = callAiApi(prompt, null, "qwen-turbo");
            String intent = response.trim().toLowerCase();

            // 验证返回的意图是否有效
            if (aiCopilotPrompts.isValidIntent(intent)) {
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
            AiCopilotPrompts.IntentResult intentResult = aiCopilotPrompts.detectIntent(userMessage);

            // 上下文记忆延续
            String lastIntent = sessionLastIntent.get(sessionId);
            if (intentResult.score < 5 && lastIntent != null) {
                log.info("Using lastIntent as fallback: {} (current score: {})", lastIntent, intentResult.score);
                intentResult = new AiCopilotPrompts.IntentResult(lastIntent, 3);
            }

            log.info("Final intent: {}, score: {}", intentResult.intent, intentResult.score);
            sessionLastIntent.put(sessionId, intentResult.intent);

            // 构建系统提示词
            String effectiveContext = (context != null && !context.isEmpty()) ? context : intentResult.intent;
            String systemPrompt = aiCopilotPrompts.buildSystemPrompt(language, effectiveContext);

            // === 工具调用循环 ===
            // 构建 messages 数组（Function Calling 格式）
            List<Map<String, Object>> messages = buildMessagesForTools(history, systemPrompt, userMessage);

            // 获取工具定义
            List<Map<String, Object>> tools = toolRegistry.getOpenAITools();

            // 获取 AI 提供商配置
            AiConfig config = getAiConfig(provider);

            // 工具调用循环
            String finalResponse = runToolCallLoop(config, model, messages, tools);

            // 更新历史记录
            history.add(new ChatMessage("user", userMessage));
            history.add(new ChatMessage("assistant", finalResponse));

            // 限制历史大小
            while (history.size() > 20) {
                history.remove(0);
            }

            return new ChatResponse(true, finalResponse, null);

        } catch (Exception e) {
            log.error("AI Copilot chat failed", e);
            return new ChatResponse(false, null, "AI 服务错误: " + e.getMessage());
        }
    }

    /**
     * 工具调用循环（Function Calling 核心）
     */
    private String runToolCallLoop(AiConfig config, String model,
                                   List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools) {

        String effectiveModel = (model != null && !model.isEmpty()) ? model : config.getModel();
        String provider = config.getProvider();

        // 最多调用 MAX_TOOL_CALL_ITERATIONS 轮
        for (int iteration = 0; iteration < MAX_TOOL_CALL_ITERATIONS; iteration++) {
            log.info("Tool call iteration: {} for provider: {}", iteration + 1, provider);

            // 调用 AI API（带工具）
            AiAnalysisService.AiResponse response = aiAnalysisService.chatWithTools(
                    provider, effectiveModel, messages, tools);

            // 如果没有工具调用，返回最终回复
            if (!response.hasToolCalls()) {
                log.info("No tool calls, returning final response");
                return response.getContent() != null ? response.getContent() : "AI 未返回有效回复。";
            }

            log.info("AI requested {} tool calls: {}",
                    response.getToolCalls().size(),
                    response.getToolCalls().stream().map(AiAnalysisService.ToolCall::getName).toList());

            // 将 assistant 的 tool_calls 消息加入 messages
            messages.add(buildAssistantToolCallsMessage(response));

            // 执行每个工具调用
            for (AiAnalysisService.ToolCall toolCall : response.getToolCalls()) {
                long startTime = System.currentTimeMillis();

                // 执行工具
                ToolExecutor.ToolResult result = toolExecutor.execute(
                        toolCall.getName(), toolCall.getArguments());

                long duration = System.currentTimeMillis() - startTime;
                log.info("Tool {} executed in {} ms, success={}",
                        toolCall.getName(), duration, result.isSuccess());

                // 将工具结果加入 messages
                messages.add(buildToolResultMessage(provider, toolCall.getId(),
                        toolCall.getName(), result));
            }
        }

        // 超过最大轮数
        log.warn("Max tool call iterations reached: {}", MAX_TOOL_CALL_ITERATIONS);
        return "工具调用次数达到上限，请简化您的问题或分步提问。";
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

        String prompt = String.format("""
                你是 Spring Cloud Gateway 网关的路由配置专家。请根据用户描述生成路由配置。
                                
                **请用中文简要说明配置含义，然后返回 JSON 配置。**
                                
                ## 用户需求
                "%s"
                                
                ## 项目已有的后端服务（请使用这些服务名）
                %s
                                
                ## 现有路由命名风格示例
                %s
                                
                ## 请生成符合项目 RouteDefinition 格式的配置
                                
                返回 JSON 配置（包含以下字段）：
                ```json
                {
                  "routeName": "路由名称（参考现有命名风格，如 xxx-api）",
                  "mode": "SINGLE（单服务）或 MULTI（多服务/灰度）",
                  "serviceId": "服务ID（必须是上述已有服务之一）",
                  "order": "路由优先级，数字越小优先级越高（默认0）",
                  "predicates": [
                    {"name": "Path", "args": {"pattern": "/api/xxx/**"}}
                  ],
                  "filters": [
                    {"name": "StripPrefix", "args": {"parts": "1"}}
                  ],
                  "enabled": true
                }
                ```
                                
                ## 常用 Predicate 类型
                - **Path**: 路径匹配，args: {"pattern": "/api/users/**"}
                - **Method**: HTTP方法，args: {"methods": "GET,POST"}
                - **Header**: 请求头，args: {"header": "X-Request-Id", "regexp": "\\d+"}
                - **Query**: 查询参数，args: {"param": "userId"}
                - **Host**: 主机名，args: {"pattern": "**.example.com"}
                                
                ## 常用 Filter 类型
                - **StripPrefix**: 去除路径前缀，args: {"parts": "N"}
                - **RewritePath**: 重写路径，args: {"regexp": "/api/(?<segment>.*)", "replacement": "/$segment"}
                - **AddRequestHeader**: 添加请求头，args: {"name": "X-Source", "value": "gateway"}
                - **SetStatus**: 设置响应码，args: {"status": "404"}
                                
                ## 输出格式
                
                1. 先用中文简要解释配置含义（1-2句话）
                2. 返回完整的 JSON 配置
                3. **对于不确定的参数，必须在 JSON 后添加注释说明**
                
                ## 需要添加注释的常见情况
                
                **StripPrefix parts 参数**（取决于后端期望的路径格式）：
                当用户说"去掉前缀"但未说明去掉几段时，必须添加注释：
                > 💡 **说明**：`StripPrefix parts=N` 会去掉路径前 N 段。
                > - 如果后端 API 是 `/xxx/123` → 用 `parts=1`（去掉 `/api`）
                > - 如果后端 API 是 `/123` → 用 `parts=2`（去掉 `/api/xxx`）
                
                **RewritePath 正则**（取决于具体重写需求）：
                如果使用了 RewritePath，需添加注释说明重写效果。
                
                **order 优先级**（如果路径可能与其他路由冲突）：
                如果生成的路径可能与其他通配路由冲突，需提示用户调整 order。
                
                **serviceId**（如果用户指定的服务不在已有服务列表中）：
                需提示用户确认服务名是否正确。
                
                JSON 必须有效，可以被直接解析使用。注释放在 JSON 代码块之后。
                """, description, serviceList, routeNameExamples);

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
            String systemPrompt = String.format("""
                    你是 Spring Cloud Gateway 网关的错误诊断专家。请分析错误并提供排查建议。
                    
                    **请用中文回答，使用 Markdown 格式。**
                    
                    ## 你可以使用以下工具进行深入诊断：
                    - `list_routes`: 查看所有路由配置列表
                    - `get_route_detail`: 获取指定路由的完整配置（参数: routeName）
                    - `get_service_detail`: 获取指定服务的完整配置（参数: serviceName）
                    - `run_diagnosis`: 运行系统诊断，检查数据库、Redis、配置中心等
                    - `get_metrics`: 获取实时监控指标
                    
                    **工具使用策略**：
                    - 如果已提供的路由信息不够详细，主动调用 `get_route_detail` 查看完整配置
                    - 如果怀疑后端服务问题，调用 `get_service_detail` 查看服务实例状态
                    - 如果怀疑系统级问题，调用 `run_diagnosis` 检查基础设施
                    - 如果需要更详细指标，调用 `get_metrics` 获取实时数据
                    
                    ## 项目特定错误码含义
                    
                    | 状态码 | 项目中的含义 | 常见原因 |
                    |--------|-------------|----------|
                    | **404** | 路由未匹配 | 1. predicates 配置不正确<br>2. 路由被禁用<br>3. Path pattern 不匹配请求路径 |
                    | **502** | 后端服务不可用 | 1. 服务实例 IP:Port 配置错误<br>2. 后端服务未启动<br>3. 网络不通（防火墙/安全组） |
                    | **503** | 服务实例全部下线 | 1. 服务 enabled=false<br>2. 所有实例 enabled=false<br>3. 服务实例列表为空 |
                    | **504** | 后端响应超时 | 1. TIMEOUT 策略配置过短<br>2. 后端服务处理慢<br>3. 网络延迟高 |
                    | **429** | 触发限流 | 1. RATE_LIMITER 策略阈值过低<br>2. 突发流量超过 burstCapacity<br>3. 客户端请求过于频繁 |
                    | **401/403** | 认证失败 | 1. Auth 策略配置错误<br>2. JWT/API Key 无效或过期<br>3. 请求未携带认证信息 |
                    
                    ## 项目配置格式
                    
                    **路由配置（RouteDefinition）**：
                    ```json
                    {"routeName": "xxx-api", "mode": "SINGLE", "serviceId": "xxx-service", "order": 0,
                     "predicates": [{"name": "Path", "args": {"pattern": "/api/xxx/**"}}],
                     "filters": [{"name": "StripPrefix", "args": {"parts": "1"}}], "enabled": true}
                    ```
                    
                    **服务配置（ServiceDefinition）**：
                    ```json
                    {"name": "xxx-service", "loadBalancer": "weighted",
                     "instances": [{"ip": "192.168.1.100", "port": 8080, "weight": 100, "enabled": true}]}
                    ```
                    
                    **策略配置（StrategyDefinition）**：
                    - TIMEOUT: {"strategyType": "TIMEOUT", "config": {"timeoutMs": 5000}}
                    - RATE_LIMITER: {"strategyType": "RATE_LIMITER", "config": {"qps": 100, "burstCapacity": 200}}
                    
                    ## 输出格式
                    
                    ### 1. 错误类型判断
                    ### 2. 根因分析（按概率排序）
                    ### 3. 排查步骤（具体命令和检查点）
                    ### 4. 修复建议（使用项目 JSON 格式）
                    ### 5. 预防措施
                    """);

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
            String finalAnalysis = runToolCallLoop(config, model, messages, tools);

            return new DebugAnalysis(true, finalAnalysis, null);

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

        String prompt = String.format("""
                你是 Spring Cloud Gateway 网关的性能优化专家。请基于以下数据给出优化建议。
                                
                **请用中文回答，使用 Markdown 格式。**
                                
                ## 当前系统状态
                                
                %s
                                
                ## 实时监控指标
                                
                %s
                                
                ## 系统规模
                                
                - **路由数量**: %d
                - **服务数量**: %d
                - **实例规格**: %s
                                
                ## 请针对以下方面给出优化建议
                
                ### 1. 路由配置优化
                - 当前有 %d 个路由，建议检查路由匹配顺序（order 字段）
                - 高频路由应设置较小的 order 值（如 0）
                - 避免过于复杂的正则表达式 predicates
                
                **项目路由配置格式（RouteDefinition）**：
                ```json
                {
                  "routeName": "路由名称",
                  "mode": "SINGLE",
                  "serviceId": "后端服务ID",
                  "order": 0,
                  "predicates": [{"name": "Path", "args": {"pattern": "/api/xxx/**"}}],
                  "filters": [{"name": "StripPrefix", "args": {"parts": "1"}}],
                  "enabled": true
                }
                ```
                                
                ### 2. 连接池调优
                - 根据实例规格和并发量，建议合适的连接池大小
                - HTTP 连接池：maxConnections、acquireTimeout
                - Redis 连接池（如果配置）：maxActive、maxIdle
                                
                ### 3. 缓存策略
                - 响应缓存（CACHE 策略）：适用于热点数据的 GET 请求
                - 本地缓存（Caffeine）可减少 Redis 网络开销
                
                **项目缓存策略格式（StrategyDefinition）**：
                ```json
                {
                  "strategyType": "CACHE",
                  "scope": "ROUTE",
                  "routeId": "目标路由ID",
                  "config": {
                    "cacheTtlSeconds": 60,
                    "cacheKeyPattern": "path+query"
                  }
                }
                ```
                                
                ### 4. 限流与熔断
                - 根据 QPS 和后端容量，建议合理的限流阈值
                - 熔断策略：failureRateThreshold、slidingWindowSize
                
                **项目限流策略格式（RATE_LIMITER）**：
                ```json
                {
                  "strategyType": "RATE_LIMITER",
                  "scope": "ROUTE",
                  "routeId": "目标路由ID",
                  "priority": 10,
                  "config": {
                    "qps": 100,
                    "burstCapacity": 200,
                    "keyType": "ip"
                  }
                }
                ```
                
                **项目熔断策略格式（CIRCUIT_BREAKER）**：
                ```json
                {
                  "strategyType": "CIRCUIT_BREAKER",
                  "scope": "ROUTE",
                  "routeId": "目标路由ID",
                  "config": {
                    "failureRateThreshold": 50,
                    "slidingWindowSize": 100,
                    "minimumNumberOfCalls": 10,
                    "waitDurationInOpenState": "30s"
                  }
                }
                ```
                                
                ### 5. JVM 参数调优
                - 根据实例规格和当前内存使用情况给出 GC 配置建议
                - 直接内存（DirectMemory）配置：Netty 需要足够的 off-heap 内存
                - 注意：如果实例是 K8s 部署，JVM 堆内存应小于容器内存限制
                                
                ## 输出要求
                                
                对于每个建议：
                1. **当前问题分析**：指出可能存在的问题或可优化点
                2. **具体配置示例**：使用上述项目格式的 JSON 配置示例（可直接使用）
                3. **预期效果**：说明优化后能达到的效果
                                
                **重要**：所有配置示例必须使用项目特有的 JSON 格式（RouteDefinition、StrategyDefinition），不要使用 Spring Cloud Gateway 的 yaml 格式或 resilience4j 格式。
                                
                如果当前状态已经很健康（评分 ≥ 80），重点给出预防性建议和容量规划建议。
                """, formatDiagnostics(diagnostics), metricsSummary, routeCount, serviceCount, instanceSpec, routeCount);

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
        AiCopilotPrompts.IntentResult intentResult = aiCopilotPrompts.detectIntent(concept);
        String domainKnowledge = aiCopilotPrompts.buildSystemPrompt(language, intentResult.intent);
        log.info("Concept '{}' matched domain: {} (score: {})", concept, intentResult.intent, intentResult.score);

        String prompt = String.format("""
                你是 Spring Cloud Gateway 网关的技术专家。请解释以下概念。

                **请用中文回答，使用 Markdown 格式。**

                ## 项目专有知识（回答时必须基于以下信息）
                %s

                ## 需要解释的概念
                %s

                ## 用户当前系统规模
                - 路由数量: %d
                - 服务数量: %d

                ## 请按以下结构回答

                ### 1. 什么是 %s
                - 用简洁的语言定义这个概念
                - 说明它在网关中的作用

                ### 2. 工作原理
                - 解释核心机制
                - 关键参数说明

                ### 3. 使用场景
                - 什么时候需要使用
                - 适用与不适用的情况

                ### 4. 项目配置示例
                - **必须使用项目特有的 JSON 格式**（参考上方"项目专有知识"中的字段和配置格式），不要使用 Spring Cloud Gateway 的 yaml 格式
                - 提供完整的、可直接使用的配置示例
                - 如涉及路由，说明 SINGLE/MULTI 两种模式
                - 如涉及服务，说明 STATIC（static://）和 NACOS（lb://）两种服务类型的区别

                ### 5. 常见问题与最佳实践
                - 配置时容易犯的错误
                - 性能/稳定性建议
                - 针对当前系统规模的建议（路由 %d 条，服务 %d 个）

                ### 6. 相关概念
                - 列出 2-3 个相关概念，方便用户进一步学习
                """, domainKnowledge, concept, routeCount, serviceCount, concept, routeCount, serviceCount);

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

        public ChatResponse(boolean success, String response, String error) {
            this.success = success;
            this.response = response;
            this.error = error;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            if (response != null) map.put("response", response);
            if (error != null) map.put("error", error);
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

        public DebugAnalysis(boolean success, String analysis, String error) {
            this.success = success;
            this.analysis = analysis;
            this.error = error;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            if (analysis != null) map.put("analysis", analysis);
            if (error != null) map.put("error", error);
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