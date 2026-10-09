package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.entity.CopilotFeedbackEntity;
import com.leoli.gateway.admin.license.LicenseFeatureHelper;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.repository.CopilotFeedbackRepository;
import com.leoli.gateway.admin.service.AiAnalysisService;
import com.leoli.gateway.admin.service.AiCopilotService;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.RouteService;
import com.leoli.gateway.admin.service.ToolExecutor;
import com.leoli.gateway.admin.service.copilot.CopilotProgressListener;
import com.leoli.gateway.admin.service.copilot.CopilotStreamAbortedException;
import com.leoli.gateway.admin.service.copilot.CopilotUsageGuard;
import com.leoli.gateway.admin.service.copilot.PendingActionStore;
import com.leoli.gateway.admin.validation.RouteValidator;
import com.leoli.license.client.model.LicenseInfo;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI Copilot Controller.
 * REST endpoints for AI-powered gateway assistance.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/copilot")
@RequiredArgsConstructor
public class AiCopilotController extends BaseController {

    private final AiCopilotService aiCopilotService;
    private final AiAnalysisService aiAnalysisService;
    private final RouteService routeService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final LicenseFeatureHelper licenseFeatureHelper;
    private final ToolExecutor toolExecutor;
    private final PendingActionStore pendingActionStore;
    private final CopilotUsageGuard copilotUsageGuard;
    private final CopilotFeedbackRepository copilotFeedbackRepository;

    /**
     * SSE 流式对话的异步执行器。SseEmitter 要求在独立线程上跑同步管线，绝不能阻塞
     * 容器请求线程。核心 4 / 最大 32、SynchronousQueue（不排队，满了直接拒绝并回退到
     * 非流式体验），守护线程避免阻碍 JVM 退出。
     */
    private final ExecutorService streamExecutor = new ThreadPoolExecutor(
            4, 32, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "copilot-sse");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    @PreDestroy
    public void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    /**
     * Chat with AI Copilot.
     *
     * @param request Chat request with message, context, and optional sessionId
     * @return Chat response
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        log.info("AI Copilot chat request: context={}", request.getContext());

        // License feature check - AI Copilot requires ENTERPRISE edition
        licenseFeatureHelper.checkFeatureOrThrow(LicenseInfo.Feature.AI_COPilot);

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        String operator = getOperator();
        Optional<String> denial = copilotUsageGuard.checkRequestAllowed(operator);
        if (denial.isPresent()) {
            return ResponseEntity.ok(Map.of("success", false, "error", denial.get()));
        }

        AiCopilotService.ChatResponse response = aiCopilotService.chat(
                sessionId,
                request.getMessage(),
                request.getContext(),
                request.getInstanceId(),
                request.getProvider(),
                request.getModel()
        );
        copilotUsageGuard.recordUsage(operator, response.getTotalTokens());

        Map<String, Object> result = response.toMap();
        result.put("sessionId", sessionId);

        return ResponseEntity.ok(result);
    }

    /**
     * FIX (P1 UX): 流式对话（SSE）。
     * <p>
     * 工具调用循环可能耗时较久，非流式 {@code /chat} 期间前端只能显示一个转圈。
     * 此端点实时推送中间态事件，最后推送与 {@code /chat} 相同结构的终态消息：
     * <ul>
     *   <li>{@code status} → {stage: intent|thinking, ...}</li>
     *   <li>{@code tool}   → {toolName, status: running|success|error|confirm, durationMs?}</li>
     *   <li>{@code delta}  → {text}：最终答案的 token 级增量（OpenAI 兼容 provider），打字机效果</li>
     *   <li>{@code message}→ 终态：{success, response, evidence?, pendingAction?, totalTokens?, sessionId}</li>
     *   <li>{@code error}  → {message}</li>
     * </ul>
     * 采用 POST + text/event-stream，前端用 fetch 读取响应流（EventSource 不支持 POST）。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request, HttpServletResponse servletResponse) {
        // POST /api/** 已由 SecurityConfig 要求 ADMIN；此处再校验 license 特性
        licenseFeatureHelper.checkFeatureOrThrow(LicenseInfo.Feature.AI_COPilot);

        // 防止反向代理（nginx 等）缓冲 SSE，导致事件被攒到一起、失去实时性
        servletResponse.setHeader("X-Accel-Buffering", "no");
        servletResponse.setHeader("Cache-Control", "no-cache");

        final String sessionId = (request.getSessionId() == null || request.getSessionId().isEmpty())
                ? UUID.randomUUID().toString() : request.getSessionId();

        // 超时略大于工具循环总预算（90s），避免管线未结束就被容器断开
        SseEmitter emitter = new SseEmitter(120_000L);

        // 成本闸门须在请求线程校验（异步线程拿不到 SecurityContext）；operator 传入异步任务记账
        final String operator = getOperator();
        Optional<String> denial = copilotUsageGuard.checkRequestAllowed(operator);
        if (denial.isPresent()) {
            sendErrorQuietly(emitter, denial.get());
            emitter.complete();
            return emitter;
        }

        try {
            streamExecutor.execute(() -> runChatStream(emitter, request, sessionId, operator));
        } catch (RejectedExecutionException rex) {
            log.warn("Copilot SSE executor saturated; rejecting stream request");
            sendErrorQuietly(emitter, "服务繁忙，请稍后重试");
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 在独立线程上驱动同步的对话管线，把进度事件写入 SseEmitter。
     */
    private void runChatStream(SseEmitter emitter, ChatRequest request, String sessionId, String operator) {
        AtomicBoolean clientGone = new AtomicBoolean(false);
        CopilotProgressListener listener = (type, data) -> {
            if (clientGone.get()) {
                throw new CopilotStreamAbortedException("client already disconnected");
            }
            try {
                emitter.send(SseEmitter.event().name(type).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // 客户端断开：置位并抛出，尽快中断工具循环，避免继续消耗 token
                clientGone.set(true);
                throw new CopilotStreamAbortedException(e);
            }
        };

        try {
            AiCopilotService.ChatResponse response = aiCopilotService.chat(
                    sessionId, request.getMessage(), request.getContext(), request.getInstanceId(),
                    request.getProvider(), request.getModel(), listener);
            copilotUsageGuard.recordUsage(operator, response.getTotalTokens());

            if (clientGone.get()) {
                emitter.complete();
                return;
            }
            Map<String, Object> result = response.toMap();
            result.put("sessionId", sessionId);
            emitter.send(SseEmitter.event().name("message").data(result, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (CopilotStreamAbortedException aborted) {
            log.debug("Copilot SSE client disconnected, stream aborted");
            emitter.complete();
        } catch (Exception e) {
            log.error("Copilot SSE chat failed", e);
            sendErrorQuietly(emitter, e.getMessage() != null ? e.getMessage() : "AI 服务错误");
            emitter.complete();
        }
    }

    /** 尽力下发一个 error 事件；失败（多半已断开）则忽略。 */
    private void sendErrorQuietly(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("message", message), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端已断开，无处可发
        }
    }

    /**
     * 成本闸门：放行返回 null；否则返回 HTTP 200 + {success:false, error:原因}，
     * 让前端按既有"业务失败"路径展示具体原因（而非笼统的 429）。
     */
    private ResponseEntity<Map<String, Object>> guardOrDeny() {
        Optional<String> denial = copilotUsageGuard.checkRequestAllowed(getOperator());
        return denial.map(msg -> ResponseEntity.ok(Map.<String, Object>of("success", false, "error", msg)))
                .orElse(null);
    }

    /**
     * FIX (P0): 执行 AI 提议、并由用户在界面明确确认的写操作。
     * 这是唯一会运行 requiresConfirmation 工具的路径——confirmed 语义来自此
     * 人工触发的调用，而非模型入参。执行会记录审计日志（operator + clientIp）。
     * <p>
     * FIX (P2 加固): 凭 chat 返回的一次性 {@code ticket} 取出<b>服务端保存的</b>
     * 工具名与参数来执行，不信任前端回传的 toolName/arguments——防止把某次确认的
     * 票据替换成任意工具/任意参数。票据一次性消费，过期或重复提交都会被拒绝。
     *
     * @param request 待执行动作票据（来自 chat 返回的 pendingAction.ticket）
     * @return 执行结果
     */
    @PostMapping("/execute-action")
    public ResponseEntity<Map<String, Object>> executeAction(
            @RequestBody ExecuteActionRequest request,
            HttpServletRequest httpRequest) {

        licenseFeatureHelper.checkFeatureOrThrow(LicenseInfo.Feature.AI_COPilot);

        PendingActionStore.Entry entry = pendingActionStore.consume(request.getTicket());
        if (entry == null) {
            log.warn("Execute-action rejected: invalid or expired ticket");
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "确认已失效或已过期，请重新向助手发起该操作"));
        }

        log.info("Executing user-confirmed AI action: tool={}", entry.getToolName());

        ToolExecutor.ToolResult result = toolExecutor.executeConfirmed(
                entry.getToolName(), entry.getArguments(), getOperator(), getIpAddress(httpRequest));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.isSuccess());
        if (result.isSuccess()) {
            body.put("data", result.getData());
            body.put("message", "操作已执行");
        } else {
            body.put("message", result.getError() != null ? result.getError() : "操作执行失败");
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Clear conversation history for a session.
     *
     * @param sessionId Session ID
     * @return Success response
     */
    @DeleteMapping("/chat/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearHistory(@PathVariable String sessionId) {
        log.info("Clearing conversation history for session: {}", sessionId);

        aiCopilotService.clearHistory(sessionId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Conversation history cleared"
        ));
    }

    /**
     * 记录用户对某条 AI 回答的 👍/👎 反馈（问答快照一并落库，供质量度量与后续 RAG/微调）。
     *
     * @param request 反馈内容（sessionId + question + answer + rating[UP|DOWN] + 可选 comment）
     * @return 保存结果
     */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(@RequestBody FeedbackRequest request) {
        licenseFeatureHelper.checkFeatureOrThrow(LicenseInfo.Feature.AI_COPilot);

        String rating = request.getRating() != null ? request.getRating().toUpperCase(Locale.ROOT) : null;
        if (!"UP".equals(rating) && !"DOWN".equals(rating)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "rating 必须是 UP 或 DOWN"));
        }

        CopilotFeedbackEntity entity = new CopilotFeedbackEntity();
        entity.setSessionId(truncate(request.getSessionId(), 128));
        entity.setQuestion(truncate(request.getQuestion(), 4000));
        entity.setAnswer(truncate(request.getAnswer(), 60000));
        entity.setRating(rating);
        entity.setUserComment(truncate(request.getComment(), 1000));
        entity.setOperatorName(getOperator());
        copilotFeedbackRepository.save(entity);

        log.info("Copilot feedback recorded: id={}, rating={}, operator={}", entity.getId(), rating, entity.getOperatorName());
        return ResponseEntity.ok(Map.of("success", true, "id", entity.getId()));
    }

    /** 截断过长文本，避免超出列宽。 */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Generate route configuration from natural language.
     *
     * @param request Route generation request with description
     * @return Generated route configuration
     */
    @PostMapping("/generate-route")
    public ResponseEntity<Map<String, Object>> generateRoute(@RequestBody RouteGenerationRequest request) {
        log.info("Generating route from description: {}", request.getDescription());

        // License feature check
        licenseFeatureHelper.checkFeatureOrThrow(LicenseInfo.Feature.AI_COPilot);

        ResponseEntity<Map<String, Object>> denied = guardOrDeny();
        if (denied != null) return denied;

        AiCopilotService.RouteGenerationResult result = aiCopilotService.generateRoute(
                request.getDescription(),
                request.getInstanceId(),
                request.getProvider(),
                request.getModel()
        );

        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Analyze an error message.
     *
     * @param request Error analysis request
     * @return Debug analysis result
     */
    @PostMapping("/analyze-error")
    public ResponseEntity<Map<String, Object>> analyzeError(@RequestBody ErrorAnalysisRequest request) {
        log.info("Analyzing error for instance: {}, provider={}, model={}",
                request.getInstanceId(), request.getProvider(), request.getModel());

        // License feature check
        licenseFeatureHelper.checkFeatureOrThrow(LicenseInfo.Feature.AI_COPilot);

        ResponseEntity<Map<String, Object>> denied = guardOrDeny();
        if (denied != null) return denied;

        AiCopilotService.DebugAnalysis analysis = aiCopilotService.analyzeError(
                request.getErrorMessage(),
                request.getInstanceId(),
                request.getProvider(),
                request.getModel()
        );
        copilotUsageGuard.recordUsage(getOperator(), analysis.getTotalTokens());

        return ResponseEntity.ok(analysis.toMap());
    }

    /**
     * Get optimization suggestions.
     *
     * @param instanceId Gateway instance ID
     * @param provider   Optional AI provider
     * @param model      Optional AI model
     * @return Optimization suggestions
     */
    @GetMapping("/optimizations/{instanceId}")
    public ResponseEntity<Map<String, Object>> suggestOptimizations(
            @PathVariable String instanceId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model) {
        log.info("Getting optimization suggestions for instance: {}, provider={}, model={}",
                instanceId, provider, model);

        // License feature check
        licenseFeatureHelper.checkFeatureOrThrow(LicenseInfo.Feature.AI_COPilot);

        ResponseEntity<Map<String, Object>> denied = guardOrDeny();
        if (denied != null) return denied;

        AiCopilotService.OptimizationResult result = aiCopilotService.suggestOptimizations(
                instanceId, provider, model);

        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Explain a configuration concept.
     *
     * @param concept    Concept name to explain
     * @param instanceId Optional gateway instance ID (for system context)
     * @param provider   Optional AI provider
     * @param model      Optional AI model
     * @return Concept explanation
     */
    @GetMapping("/explain")
    public ResponseEntity<Map<String, Object>> explainConcept(
            @RequestParam String concept,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model) {
        log.info("Explaining concept: {}, instanceId={}, provider={}, model={}", concept, instanceId, provider, model);

        // License feature check
        licenseFeatureHelper.checkFeatureOrThrow(LicenseInfo.Feature.AI_COPilot);

        ResponseEntity<Map<String, Object>> denied = guardOrDeny();
        if (denied != null) return denied;

        String explanation = aiCopilotService.explainConcept(concept, provider, model, instanceId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "concept", concept,
                "explanation", explanation
        ));
    }

    /**
     * Get all AI providers.
     *
     * @return List of AI providers
     */
    @GetMapping("/providers")
    public ResponseEntity<List<Map<String, Object>>> getProviders() {
        return ResponseEntity.ok(aiAnalysisService.getAllProviders().stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.getId(),
                        "provider", p.getProvider(),
                        "providerName", p.getProviderName(),
                        "region", p.getRegion(),
                        "model", p.getModel() != null ? p.getModel() : "",
                        "isValid", p.getIsValid() != null ? p.getIsValid() : false
                ))
                .toList());
    }

    /**
     * Get supported models for a provider.
     *
     * @param provider Provider name
     * @return List of supported models
     */
    @GetMapping("/providers/{provider}/models")
    public ResponseEntity<List<String>> getModels(@PathVariable String provider) {
        return ResponseEntity.ok(aiAnalysisService.getSupportedModels(provider));
    }

    /**
     * Validate API Key.
     *
     * @param request Validation request
     * @return Validation result
     */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateApiKey(@RequestBody ValidateRequest request) {
        log.info("Validating API key for provider: {}", request.getProvider());

        boolean valid = aiAnalysisService.validateApiKey(
                request.getProvider(),
                request.getApiKey(),
                request.getBaseUrl()
        );

        return ResponseEntity.ok(Map.of(
                "valid", valid,
                "provider", request.getProvider()
        ));
    }

    /**
     * Save AI configuration.
     *
     * @param request Configuration request
     * @return Saved configuration
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody ConfigRequest request) {
        log.info("Saving AI config for provider: {}", request.getProvider());

        aiAnalysisService.saveConfig(
                request.getProvider(),
                request.getModel(),
                request.getApiKey(),
                request.getBaseUrl()
        );

        return ResponseEntity.ok(Map.of(
                "success", true,
                "provider", request.getProvider(),
                "model", request.getModel()
        ));
    }

    // ============== Route Validate & Apply ==============

    /**
     * Validate route configuration JSON.
     * Does not create the route, only validates the format and fields.
     *
     * @param request Validation request with route JSON
     * @return Validation result
     */
    @PostMapping("/validate-route")
    public ResponseEntity<Map<String, Object>> validateRoute(@RequestBody ValidateRouteRequest request) {
        log.info("Validating route configuration");

        try {
            // Parse JSON to RouteDefinition
            RouteDefinition route = objectMapper.readValue(request.getRouteJson(), RouteDefinition.class);

            // Validate using RouteValidator
            List<String> errors = RouteValidator.validate(route);

            if (errors.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "valid", true,
                        "message", "配置校验通过"
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "valid", false,
                        "errors", errors
                ));
            }
        } catch (Exception e) {
            log.warn("Route JSON parse failed: {}", e.getMessage());
            List<String> errors = new ArrayList<>();
            errors.add("JSON解析失败: " + e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "valid", false,
                    "errors", errors
            ));
        }
    }

    /**
     * Apply route configuration (create the route).
     *
     * @param request Apply request with route JSON and instanceId
     * @return Creation result
     */
    @PostMapping("/apply-route")
    public ResponseEntity<Map<String, Object>> applyRoute(
            @RequestBody ApplyRouteRequest request,
            HttpServletRequest httpRequest) {
        log.info("Applying route configuration for instance: {}", request.getInstanceId());

        try {
            // Parse JSON to RouteDefinition
            RouteDefinition route = objectMapper.readValue(request.getRouteJson(), RouteDefinition.class);

            // Validate again (prevent bypass)
            RouteValidator.validateAndThrow(route);

            // Create route
            RouteEntity entity = routeService.createRoute(route, request.getInstanceId());

            // Record audit log
            String newValue = objectMapper.writeValueAsString(route);
            auditLogService.recordAuditLog(
                    request.getInstanceId(),
                    "AI_COPILOT",
                    "CREATE",
                    "ROUTE",
                    entity.getRouteId(),
                    route.getRouteName(),
                    null,
                    newValue,
                    getIpAddress(httpRequest)
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "路由创建成功");
            result.put("routeId", entity.getRouteId());
            result.put("routeName", entity.getRouteName());
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.warn("Route apply failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Route apply failed", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "路由创建失败: " + e.getMessage()
            ));
        }
    }

    // ============== Request DTOs ==============

    public static class ChatRequest {
        private String sessionId;
        private String message;
        private String context;  // route, service, strategy, debug, performance
        private String instanceId;
        private String provider;
        private String model;

        // Getters and setters
        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class RouteGenerationRequest {
        private String description;
        private String instanceId;
        private String provider;
        private String model;

        // Getters and setters
        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class ErrorAnalysisRequest {
        private String errorMessage;
        private String instanceId;
        private String provider;
        private String model;

        // Getters and setters
        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class ValidateRequest {
        private String provider;
        private String apiKey;
        private String baseUrl;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class ConfigRequest {
        private String provider;
        private String model;
        private String apiKey;
        private String baseUrl;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class ValidateRouteRequest {
        private String routeJson;

        public String getRouteJson() {
            return routeJson;
        }

        public void setRouteJson(String routeJson) {
            this.routeJson = routeJson;
        }
    }

    public static class ApplyRouteRequest {
        private String routeJson;
        private String instanceId;

        public String getRouteJson() {
            return routeJson;
        }

        public void setRouteJson(String routeJson) {
            this.routeJson = routeJson;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }
    }

    public static class ExecuteActionRequest {
        /** chat 返回的 pendingAction.ticket，一次性、服务端校验后据此取出真实动作。 */
        private String ticket;
        /** 仅为兼容/日志保留，执行时以服务端保存的动作快照为准，不信任此字段。 */
        private String toolName;
        private Map<String, Object> arguments;

        public String getTicket() {
            return ticket;
        }

        public void setTicket(String ticket) {
            this.ticket = ticket;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }

        public void setArguments(Map<String, Object> arguments) {
            this.arguments = arguments;
        }
    }

    public static class FeedbackRequest {
        private String sessionId;
        private String question;
        private String answer;
        /** UP / DOWN */
        private String rating;
        private String comment;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public String getRating() { return rating; }
        public void setRating(String rating) { this.rating = rating; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }
}