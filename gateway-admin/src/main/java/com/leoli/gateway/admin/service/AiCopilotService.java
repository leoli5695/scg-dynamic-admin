package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiConfig;
import com.leoli.gateway.admin.repository.AiConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Copilot Service.
 * Provides an intelligent assistant for gateway configuration and troubleshooting.
 * 
 * Features:
 * - Natural language to route configuration
 * - Error analysis and debugging suggestions
 * - Performance optimization recommendations
 * - Configuration best practices guidance
 * - Context-aware responses based on current gateway state
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
    private final ObjectMapper objectMapper;

    // Conversation history per session
    private final ConcurrentHashMap<String, List<ChatMessage>> conversationHistory = new ConcurrentHashMap<>();

    // System prompts for different contexts
    private static final String SYSTEM_PROMPT_GATEWAY = """
            You are an AI Copilot for an API Gateway management system. Your role is to help users:
            
            1. Configure routes, services, and strategies
            2. Debug issues and analyze errors
            3. Optimize gateway performance
            4. Understand best practices for API gateway configuration
            
            You have access to the current gateway state including routes, services, health status, and metrics.
            
            When suggesting configurations:
            - Provide complete, valid JSON configurations
            - Explain the purpose of each field
            - Warn about potential issues
            - Suggest testing approaches
            
            When debugging:
            - Ask clarifying questions if needed
            - Provide step-by-step troubleshooting guides
            - Suggest diagnostic commands or checks
            
            Always be concise, accurate, and helpful. Use markdown formatting for code blocks and lists.
            """;

    private static final Map<String, String> CONTEXT_PROMPTS = Map.of(
            "route", """
                    The user is working on route configuration. Routes define how requests are routed to backend services.
                    Key fields include: id, uri, predicates, filters, metadata.
                    Common predicates: Path, Method, Header, Query
                    Common filters: AddRequestHeader, RewritePath, RateLimit, CircuitBreaker
                    """,
            "service", """
                    The user is working on service configuration. Services define backend services for load balancing.
                    Key fields include: serviceId, type (STATIC/DISCOVERY), instances
                    """,
            "strategy", """
                    The user is working on strategy configuration. Strategies define rate limiting, circuit breaker, timeout settings.
                    Key components: rateLimiter, circuitBreaker, retry
                    """,
            "debug", """
                    The user is debugging an issue. Help analyze error messages, suggest possible causes, and provide step-by-step solutions.
                    """,
            "performance", """
                    The user is optimizing performance. Suggest configuration changes to improve latency, throughput, or resource usage.
                    """
    );

    /**
     * Chat with the AI Copilot.
     */
    public ChatResponse chat(String sessionId, String userMessage, String context, String instanceId) {
        log.info("AI Copilot chat: session={}, context={}, instance={}", sessionId, context, instanceId);

        try {
            // Get conversation history
            List<ChatMessage> history = conversationHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());

            // Build context data
            String contextData = buildContextData(instanceId, context);

            // Build full prompt
            String systemPrompt = buildSystemPrompt(context);
            String fullPrompt = buildFullPrompt(history, userMessage, systemPrompt, contextData);

            // Get AI response
            String aiResponse = callAiApi(fullPrompt);

            // Update history
            history.add(new ChatMessage("user", userMessage));
            history.add(new ChatMessage("assistant", aiResponse));

            // Limit history size
            while (history.size() > 20) {
                history.remove(0);
            }

            return new ChatResponse(true, aiResponse, null);

        } catch (Exception e) {
            log.error("AI Copilot chat failed", e);
            return new ChatResponse(false, null, "AI service error: " + e.getMessage());
        }
    }

    /**
     * Clear conversation history.
     */
    public void clearHistory(String sessionId) {
        conversationHistory.remove(sessionId);
    }

    /**
     * Generate route configuration from natural language.
     */
    public RouteGenerationResult generateRoute(String description, String instanceId) {
        log.info("Generating route from description: {}", description);

        String prompt = String.format("""
                Generate a valid Spring Cloud Gateway route configuration JSON based on this description:
                "%s"
                
                Return ONLY a valid JSON object with these fields:
                - id: route id (string)
                - uri: backend URI (string, use lb:// for service discovery, http:// for direct)
                - predicates: array of predicate objects with name and args
                - filters: array of filter objects with name and args (optional)
                - metadata: object with custom metadata (optional)
                
                Example format:
                {
                    "id": "user-service-route",
                    "uri": "lb://user-service",
                    "predicates": [
                        {"name": "Path", "args": {"pattern": "/api/users/**"}}
                    ],
                    "filters": [
                        {"name": "StripPrefix", "args": {"parts": "1"}}
                    ]
                }
                
                Return only the JSON, no explanation.
                """, description);

        try {
            String response = callAiApi(prompt);
            
            // Extract JSON from response
            String json = extractJson(response);
            
            return new RouteGenerationResult(true, json, null);
        } catch (Exception e) {
            log.error("Route generation failed", e);
            return new RouteGenerationResult(false, null, "Failed to generate route: " + e.getMessage());
        }
    }

    /**
     * Analyze an error and provide debugging suggestions.
     */
    public DebugAnalysis analyzeError(String errorMessage, String instanceId) {
        log.info("Analyzing error for instance: {}", instanceId);

        // Get diagnostic info
        Map<String, Object> diagnostics = getDiagnosticsSummary(instanceId);

        String prompt = String.format("""
                Analyze this gateway error and provide debugging suggestions:
                
                Error: %s
                
                Current Gateway State:
                %s
                
                Provide:
                1. Root cause analysis
                2. Likely causes ranked by probability
                3. Step-by-step debugging instructions
                4. Suggested fixes
                5. Prevention recommendations
                
                Format as markdown with clear sections.
                """, errorMessage, formatDiagnostics(diagnostics));

        try {
            String analysis = callAiApi(prompt);
            return new DebugAnalysis(true, analysis, null);
        } catch (Exception e) {
            log.error("Error analysis failed", e);
            return new DebugAnalysis(false, null, "Analysis failed: " + e.getMessage());
        }
    }

    /**
     * Get performance optimization suggestions.
     */
    public OptimizationResult suggestOptimizations(String instanceId) {
        log.info("Getting optimization suggestions for instance: {}", instanceId);

        Map<String, Object> diagnostics = getDiagnosticsSummary(instanceId);

        String prompt = String.format("""
                Based on this gateway state, suggest performance optimizations:
                
                %s
                
                Provide specific, actionable suggestions for:
                1. Route configuration optimizations
                2. Connection pool tuning
                3. Cache strategies
                4. Rate limiting improvements
                5. JVM tuning suggestions
                
                For each suggestion, explain the problem, solution, and expected impact.
                Format as markdown.
                """, formatDiagnostics(diagnostics));

        try {
            String suggestions = callAiApi(prompt);
            return new OptimizationResult(true, suggestions, null);
        } catch (Exception e) {
            log.error("Optimization suggestion failed", e);
            return new OptimizationResult(false, null, "Suggestion failed: " + e.getMessage());
        }
    }

    /**
     * Explain a configuration concept.
     */
    public String explainConcept(String concept) {
        String prompt = String.format("""
                Explain the Spring Cloud Gateway concept: "%s"
                
                Include:
                - What it is
                - How it works
                - When to use it
                - Example configuration
                - Common pitfalls
                
                Keep it concise and practical. Use markdown formatting.
                """, concept);

        try {
            return callAiApi(prompt);
        } catch (Exception e) {
            log.error("Concept explanation failed", e);
            return "Failed to explain concept: " + e.getMessage();
        }
    }

    // ============== Private Methods ==============

    private String buildSystemPrompt(String context) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT_GATEWAY);
        
        if (context != null && CONTEXT_PROMPTS.containsKey(context)) {
            sb.append("\n\n").append(CONTEXT_PROMPTS.get(context));
        }
        
        return sb.toString();
    }

    private String buildContextData(String instanceId, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current Time: ").append(new Date()).append("\n");
        
        if (instanceId != null) {
            sb.append("Instance ID: ").append(instanceId).append("\n");
            
            try {
                Map<String, Object> diagnostics = getDiagnosticsSummary(instanceId);
                sb.append("\nGateway State:\n");
                sb.append(formatDiagnostics(diagnostics));
            } catch (Exception e) {
                sb.append("Unable to fetch gateway state: ").append(e.getMessage()).append("\n");
            }
        }
        
        return sb.toString();
    }

    private String buildFullPrompt(List<ChatMessage> history, String userMessage, 
                                   String systemPrompt, String contextData) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(systemPrompt).append("\n\n");
        sb.append("Context:\n").append(contextData).append("\n\n");
        
        if (!history.isEmpty()) {
            sb.append("Previous conversation:\n");
            for (ChatMessage msg : history) {
                sb.append(msg.role).append(": ").append(msg.content).append("\n");
            }
            sb.append("\n");
        }
        
        sb.append("user: ").append(userMessage);
        
        return sb.toString();
    }

    private String callAiApi(String prompt) {
        // Get enabled AI config
        AiConfig config = aiConfigRepository.findByEnabledTrue()
                .stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No AI provider configured"));

        return aiAnalysisService.callAiApi(config.getProvider(), config.getModel(), prompt);
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
            summary.put("healthScore", report.getOverallScore());
            summary.put("databaseStatus", report.getDatabase() != null ? report.getDatabase().getStatus() : "unknown");
            summary.put("redisStatus", report.getRedis() != null ? report.getRedis().getStatus() : "unknown");
            summary.put("configCenterStatus", report.getConfigCenter() != null ? report.getConfigCenter().getStatus() : "unknown");
        } catch (Exception e) {
            summary.put("diagnosticError", e.getMessage());
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
        public boolean isSuccess() { return success; }
        public String getResponse() { return response; }
        public String getError() { return error; }
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
        public boolean isSuccess() { return success; }
        public String getConfig() { return config; }
        public String getError() { return error; }
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
        public boolean isSuccess() { return success; }
        public String getAnalysis() { return analysis; }
        public String getError() { return error; }
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
        public boolean isSuccess() { return success; }
        public String getSuggestions() { return suggestions; }
        public String getError() { return error; }
    }
}