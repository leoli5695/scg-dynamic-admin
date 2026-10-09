package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.AiConfig;
import com.leoli.gateway.admin.prompt.PromptService;
import com.leoli.gateway.admin.repository.AiConfigRepository;
import com.leoli.gateway.admin.service.copilot.*;
import com.leoli.gateway.admin.service.copilot.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

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
 * Architecture (委托模式):
 * - ChatSessionManager: 会话历史和意图记忆管理
 * - IntentRouter: 意图检测和本地兜底回复
 * - ToolCallOrchestrator: 工具调用循环编排
 * - ContextDataProvider: 上下文数据收集
 * - CopilotResponseBuilder: 响应格式化
 * - PromptService: 提示词管理
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCopilotService {

    // ===================== 核心依赖 =====================
    
    private final AiConfigRepository aiConfigRepository;
    private final AiAnalysisService aiAnalysisService;

    // ===================== 委托组件 =====================
    
    private final ChatSessionManager chatSessionManager;
    private final IntentRouter intentRouter;
    private final SemanticIntentClassifier semanticIntentClassifier;
    private final ToolCallOrchestrator toolCallOrchestrator;
    private final ContextDataProvider contextDataProvider;
    private final CopilotResponseBuilder responseBuilder;
    private final PromptService promptService;

    // ===================== Chat API =====================

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
        return chat(sessionId, userMessage, context, instanceId, provider, model, CopilotProgressListener.NOOP);
    }

    /**
     * Chat with the AI Copilot（带进度回调，用于 SSE 流式推送中间态）.
     *
     * @param listener 进度回调，非 null；非流式路径传 {@link CopilotProgressListener#NOOP}
     */
    public ChatResponse chat(String sessionId, String userMessage, String context, String instanceId,
                             String provider, String model, CopilotProgressListener listener) {
        log.info("AI Copilot chat: session={}, context={}, instance={}, provider={}, model={}",
                sessionId, context, instanceId, provider, model);

        try {
            // 1. 意图检测（委托给 IntentRouter）
            IntentResult intentResult = intentRouter.processIntent(
                    sessionId, userMessage, chatSessionManager.getLastIntent(sessionId));
            
            String language = intentResult.getLanguage();

            // 2. 本地兜底（常见问题快速响应）
            if (intentResult.hasLocalFallback()) {
                log.info("Local fallback matched for: {}", userMessage);
                chatSessionManager.addToHistory(sessionId, "user", userMessage);
                chatSessionManager.addToHistory(sessionId, "assistant", intentResult.getLocalFallback());
                return new ChatResponse(true, intentResult.getLocalFallback(), null);
            }

            // 3. 关键词意图（快路）
            log.info("Keyword intent: {}, score: {}", intentResult.getIntent(), intentResult.getScore());

            // 4. 提前解析 AI 配置（语义兜底分类也要用）
            AiConfig config = toolCallOrchestrator.getAiConfig(provider);
            String effectiveModel = (model != null && !model.isEmpty()) ? model : config.getModel();

            // 5. 领域判定：显式 context 最优先；否则关键词命中具体领域就直接用（零额外调用）；
            //    关键词没命中（general/unknown/空）才发一次极小的语义分类兜底。
            String domain = intentResult.getIntent();
            boolean keywordMissed = domain == null || domain.isEmpty()
                    || "general".equals(domain) || "unknown".equals(domain);
            if (keywordMissed) {
                String semantic = semanticIntentClassifier.classify(config.getProvider(), effectiveModel, userMessage);
                if (semantic != null && !semantic.isEmpty() && !"general".equals(semantic)) {
                    domain = semantic;
                    log.info("Semantic fallback refined domain: {}", domain);
                }
            }
            if (context != null && !context.isEmpty()) {
                domain = context; // 前端显式指定域时以其为准
            }
            if (domain == null || domain.isEmpty()) {
                domain = "general";
            }
            chatSessionManager.setLastIntent(sessionId, domain);
            listener.onEvent("status", Map.of(
                    "stage", "intent",
                    "intent", domain,
                    "language", language != null ? language : "zh"));

            // 6. 构建系统提示词（委托给 PromptService）
            String systemPrompt = promptService.buildSystemPrompt(language, domain);

            // 7. 构建消息（委托给 ToolCallOrchestrator）——使用历史快照，避免并发遍历时被写坏
            List<ChatMessage> history = chatSessionManager.getHistorySnapshot(sessionId);
            List<Map<String, Object>> messages = toolCallOrchestrator.buildMessagesForTools(
                    history, systemPrompt, userMessage, instanceId);

            // 8. 按领域收窄工具（拿不准时 getOpenAITools 内部 fail-safe 回退全量）
            List<Map<String, Object>> tools = toolCallOrchestrator.getOpenAITools(domain);

            // 9. 执行工具调用循环（委托给 ToolCallOrchestrator）
            ToolCallLoopResult loopResult = toolCallOrchestrator.runToolCallLoop(config, effectiveModel, messages, tools, listener);

            // 9. 构建最终响应（附证据说明，不再有假置信度百分比）
            EvidenceAssessor.Evidence evidence = responseBuilder.assessEvidence(
                    loopResult.getCalledTools(), loopResult.getDataTypes(), loopResult.getFailedTools(), language);
            String finalResponse = responseBuilder.addEvidenceMark(loopResult.getContent(), evidence);

            // 10. 更新历史记录
            chatSessionManager.addToHistory(sessionId, "user", userMessage);
            chatSessionManager.addToHistory(sessionId, "assistant", finalResponse);

            ChatResponse chatResponse = new ChatResponse(true, finalResponse, null, evidence.toMap());
            // FIX (P0): 把待人工确认的写操作透传给前端，由其渲染确认弹窗。
            chatResponse.setPendingAction(loopResult.getPendingAction());
            return chatResponse;

        } catch (CopilotStreamAbortedException abort) {
            // 流式路径客户端断开：安静中止，不当作错误，也不返回响应（调用方会感知断开）
            log.debug("AI Copilot chat aborted (client disconnected)");
            throw abort;
        } catch (Exception e) {
            log.error("AI Copilot chat failed", e);
            return new ChatResponse(false, null, "AI 服务错误: " + e.getMessage());
        }
    }

    /**
     * Clear conversation history.
     */
    public void clearHistory(String sessionId) {
        chatSessionManager.clearHistory(sessionId);
    }

    // ===================== Route Generation =====================

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

        // 获取现有服务列表和路由命名风格（委托给 ContextDataProvider）
        String serviceList = contextDataProvider.getExistingServiceNames(instanceId);
        String routeNameExamples = contextDataProvider.getExistingRouteNameExamples(instanceId);

        // 获取提示词模板
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

    // ===================== Error Analysis =====================

    /**
     * Analyze an error and provide debugging suggestions.
     */
    public DebugAnalysis analyzeError(String errorMessage, String instanceId) {
        return analyzeError(errorMessage, instanceId, null, null);
    }

    /**
     * Analyze an error and provide debugging suggestions with specified provider and model.
     */
    public DebugAnalysis analyzeError(String errorMessage, String instanceId, String provider, String model) {
        log.info("Analyzing error for instance: {}, provider={}, model={}", instanceId, provider, model);

        try {
            // 智能过滤相关路由（委托给 ContextDataProvider）
            String errorPath = contextDataProvider.extractPathFromError(errorMessage);
            String relevantRoutesInfo = contextDataProvider.findRelevantRoutesForError(errorPath, instanceId);
            log.info("Smart route filtering: errorPath={}", errorPath);

            // 获取诊断数据和指标（委托给 ContextDataProvider）
            Map<String, Object> diagnostics = contextDataProvider.getDiagnosticsSummary(instanceId);
            Map<String, Object> metrics = contextDataProvider.getGatewayMetrics();
            
            String diagnosticsSummary = responseBuilder.formatDiagnostics(diagnostics);
            String metricsSummary = responseBuilder.formatMetricsForErrorAnalysis(metrics);
            
            int routeCount = contextDataProvider.getRouteCount(instanceId);
            int serviceCount = contextDataProvider.getServiceCount(instanceId);

            // 构建提示词
            String systemPrompt = promptService.getPrompt("task.analyzeError.zh",
                    "你是 Spring Cloud Gateway 网关的错误诊断专家。用中文回答，Markdown格式。以工具查询数据为绝对权威。");

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
                    """, errorMessage, diagnosticsSummary, metricsSummary, routeCount, serviceCount, relevantRoutesInfo);

            // 构建消息
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userMessage));

            // 获取工具定义和配置（错误分析属排障域，按域筛选工具，避免全量塞入）
            List<Map<String, Object>> tools = toolCallOrchestrator.getOpenAITools("debug");
            AiConfig config = toolCallOrchestrator.getAiConfig(provider);

            // 执行工具调用循环
            ToolCallLoopResult loopResult = toolCallOrchestrator.runToolCallLoop(config, model, messages, tools);

            EvidenceAssessor.Evidence evidence = responseBuilder.assessEvidence(
                    loopResult.getCalledTools(), loopResult.getDataTypes(), loopResult.getFailedTools(), "zh");
            String finalAnalysis = responseBuilder.addEvidenceMark(loopResult.getContent(), evidence);

            return new DebugAnalysis(true, finalAnalysis, null, evidence.toMap());

        } catch (Exception e) {
            log.error("Error analysis failed", e);
            return new DebugAnalysis(false, null, "错误分析失败: " + e.getMessage());
        }
    }

    // ===================== Optimization Suggestions =====================

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

        // 获取诊断数据和指标（委托给 ContextDataProvider）
        Map<String, Object> diagnostics = contextDataProvider.getDiagnosticsSummary(instanceId);
        Map<String, Object> metrics = contextDataProvider.getGatewayMetrics();
        
        String diagnosticsSummary = responseBuilder.formatDiagnostics(diagnostics);
        String metricsSummary = responseBuilder.formatMetricsForOptimization(metrics);
        String instanceSpec = contextDataProvider.getInstanceSpecInfo(instanceId);
        
        int routeCount = contextDataProvider.getRouteCount(instanceId);
        int serviceCount = contextDataProvider.getServiceCount(instanceId);

        // 获取提示词模板
        String promptTemplate = promptService.getPrompt("task.suggestOptimizations.zh",
                "你是 Spring Cloud Gateway 网关的性能优化专家。请基于以下数据给出优化建议。");

        String prompt = promptTemplate
                .replace("{diagnostics}", diagnosticsSummary)
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

    // ===================== Concept Explanation =====================

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
     */
    public String explainConcept(String concept, String provider, String model, String instanceId) {
        log.info("Explaining concept: {}, instanceId={}", concept, instanceId);

        int routeCount = contextDataProvider.getRouteCount(instanceId);
        int serviceCount = contextDataProvider.getServiceCount(instanceId);

        // 通过意图识别确定领域
        String language = intentRouter.detectLanguage(concept);
        PromptService.IntentResult intentResult = promptService.detectIntent(concept);
        String domainKnowledge = promptService.buildSystemPrompt(language, intentResult.intent());
        log.info("Concept '{}' matched domain: {} (score: {})", concept, intentResult.intent(), intentResult.score());

        String promptTemplate = promptService.getPrompt("task.explainConcept.zh",
                "你是 Spring Cloud Gateway 网关的技术专家。请解释以下概念。");

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

    // ===================== Private Methods =====================

    private String callAiApi(String prompt) {
        return callAiApi(prompt, null, null);
    }

    private String callAiApi(String prompt, String provider, String model) {
        if (provider != null && !provider.isEmpty()) {
            return aiAnalysisService.callAiApi(provider, model, prompt);
        }

        AiConfig config = aiConfigRepository.findFirstValidConfig()
                .orElseThrow(() -> new RuntimeException("未配置 AI 服务。请在监控页面配置 AI 提供商。"));

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

    // ===================== Data Classes =====================

    public static class ChatResponse {
        private boolean success;
        private String response;
        private String error;
        /** 证据评估（level/note/toolCount/dataTypes/failedTools），取代原先的假置信度百分比。 */
        private Map<String, Object> evidence;
        private Map<String, Object> pendingAction;

        public ChatResponse(boolean success, String response, String error) {
            this(success, response, error, null);
        }

        public ChatResponse(boolean success, String response, String error, Map<String, Object> evidence) {
            this.success = success;
            this.response = response;
            this.error = error;
            this.evidence = evidence;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            if (response != null) map.put("response", response);
            if (error != null) map.put("error", error);
            if (evidence != null) map.put("evidence", evidence);
            if (pendingAction != null) map.put("pendingAction", pendingAction);
            return map;
        }

        public boolean isSuccess() { return success; }
        public String getResponse() { return response; }
        public String getError() { return error; }
        public Map<String, Object> getEvidence() { return evidence; }
        public Map<String, Object> getPendingAction() { return pendingAction; }
        public void setPendingAction(Map<String, Object> pendingAction) { this.pendingAction = pendingAction; }
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

        public boolean isSuccess() { return success; }
        public String getConfig() { return config; }
        public String getError() { return error; }
    }

    public static class DebugAnalysis {
        private boolean success;
        private String analysis;
        private String error;
        private Map<String, Object> evidence;

        public DebugAnalysis(boolean success, String analysis, String error) {
            this(success, analysis, error, null);
        }

        public DebugAnalysis(boolean success, String analysis, String error, Map<String, Object> evidence) {
            this.success = success;
            this.analysis = analysis;
            this.error = error;
            this.evidence = evidence;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            if (analysis != null) map.put("analysis", analysis);
            if (error != null) map.put("error", error);
            if (evidence != null) map.put("evidence", evidence);
            return map;
        }

        public boolean isSuccess() { return success; }
        public String getAnalysis() { return analysis; }
        public String getError() { return error; }
        public Map<String, Object> getEvidence() { return evidence; }
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

        public boolean isSuccess() { return success; }
        public String getSuggestions() { return suggestions; }
        public String getError() { return error; }
    }
}