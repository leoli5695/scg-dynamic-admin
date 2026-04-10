package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.service.AiAnalysisService;
import com.leoli.gateway.admin.service.AiCopilotService;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.RouteService;
import com.leoli.gateway.admin.validation.RouteValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
public class AiCopilotController {

    private final AiCopilotService aiCopilotService;
    private final AiAnalysisService aiAnalysisService;
    private final RouteService routeService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Chat with AI Copilot.
     *
     * @param request Chat request with message, context, and optional sessionId
     * @return Chat response
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        log.info("AI Copilot chat request: context={}", request.getContext());

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        AiCopilotService.ChatResponse response = aiCopilotService.chat(
                sessionId,
                request.getMessage(),
                request.getContext(),
                request.getInstanceId(),
                request.getProvider(),
                request.getModel()
        );

        Map<String, Object> result = response.toMap();
        result.put("sessionId", sessionId);

        return ResponseEntity.ok(result);
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
     * Generate route configuration from natural language.
     *
     * @param request Route generation request with description
     * @return Generated route configuration
     */
    @PostMapping("/generate-route")
    public ResponseEntity<Map<String, Object>> generateRoute(@RequestBody RouteGenerationRequest request) {
        log.info("Generating route from description: {}", request.getDescription());

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

        AiCopilotService.DebugAnalysis analysis = aiCopilotService.analyzeError(
                request.getErrorMessage(),
                request.getInstanceId(),
                request.getProvider(),
                request.getModel()
        );

        return ResponseEntity.ok(analysis.toMap());
    }

    /**
     * Get optimization suggestions.
     *
     * @param instanceId Gateway instance ID
     * @param provider Optional AI provider
     * @param model Optional AI model
     * @return Optimization suggestions
     */
    @GetMapping("/optimizations/{instanceId}")
    public ResponseEntity<Map<String, Object>> suggestOptimizations(
            @PathVariable String instanceId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model) {
        log.info("Getting optimization suggestions for instance: {}, provider={}, model={}", 
                instanceId, provider, model);

        AiCopilotService.OptimizationResult result = aiCopilotService.suggestOptimizations(
                instanceId, provider, model);

        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Explain a configuration concept.
     *
     * @param concept Concept name to explain
     * @param instanceId Optional gateway instance ID (for system context)
     * @param provider Optional AI provider
     * @param model Optional AI model
     * @return Concept explanation
     */
    @GetMapping("/explain")
    public ResponseEntity<Map<String, Object>> explainConcept(
            @RequestParam String concept,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model) {
        log.info("Explaining concept: {}, instanceId={}, provider={}, model={}", concept, instanceId, provider, model);

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
                    "AI_COPILOT",
                    "CREATE",
                    "ROUTE",
                    entity.getRouteId(),
                    route.getRouteName(),
                    null,
                    newValue,
                    getClientIpAddress(httpRequest)
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

    /**
     * Get client IP address from request.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // Handle multiple IPs in X-Forwarded-For
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
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
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getContext() { return context; }
        public void setContext(String context) { this.context = context; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class RouteGenerationRequest {
        private String description;
        private String instanceId;
        private String provider;
        private String model;

        // Getters and setters
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class ErrorAnalysisRequest {
        private String errorMessage;
        private String instanceId;
        private String provider;
        private String model;

        // Getters and setters
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class ValidateRequest {
        private String provider;
        private String apiKey;
        private String baseUrl;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class ConfigRequest {
        private String provider;
        private String model;
        private String apiKey;
        private String baseUrl;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class ValidateRouteRequest {
        private String routeJson;

        public String getRouteJson() { return routeJson; }
        public void setRouteJson(String routeJson) { this.routeJson = routeJson; }
    }

    public static class ApplyRouteRequest {
        private String routeJson;
        private String instanceId;

        public String getRouteJson() { return routeJson; }
        public void setRouteJson(String routeJson) { this.routeJson = routeJson; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    }
}