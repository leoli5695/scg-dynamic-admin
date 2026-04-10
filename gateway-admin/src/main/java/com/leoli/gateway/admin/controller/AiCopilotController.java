package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.AiCopilotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
                request.getInstanceId()
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
                request.getInstanceId()
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
        log.info("Analyzing error for instance: {}", request.getInstanceId());

        AiCopilotService.DebugAnalysis analysis = aiCopilotService.analyzeError(
                request.getErrorMessage(),
                request.getInstanceId()
        );

        return ResponseEntity.ok(analysis.toMap());
    }

    /**
     * Get optimization suggestions.
     *
     * @param instanceId Gateway instance ID
     * @return Optimization suggestions
     */
    @GetMapping("/optimizations/{instanceId}")
    public ResponseEntity<Map<String, Object>> suggestOptimizations(@PathVariable String instanceId) {
        log.info("Getting optimization suggestions for instance: {}", instanceId);

        AiCopilotService.OptimizationResult result = aiCopilotService.suggestOptimizations(instanceId);

        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Explain a configuration concept.
     *
     * @param concept Concept name to explain
     * @return Concept explanation
     */
    @GetMapping("/explain")
    public ResponseEntity<Map<String, Object>> explainConcept(@RequestParam String concept) {
        log.info("Explaining concept: {}", concept);

        String explanation = aiCopilotService.explainConcept(concept);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "concept", concept,
                "explanation", explanation
        ));
    }

    // ============== Request DTOs ==============

    public static class ChatRequest {
        private String sessionId;
        private String message;
        private String context;  // route, service, strategy, debug, performance
        private String instanceId;

        // Getters and setters
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getContext() { return context; }
        public void setContext(String context) { this.context = context; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    }

    public static class RouteGenerationRequest {
        private String description;
        private String instanceId;

        // Getters and setters
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    }

    public static class ErrorAnalysisRequest {
        private String errorMessage;
        private String instanceId;

        // Getters and setters
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    }
}