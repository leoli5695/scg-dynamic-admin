package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.service.AiCopilotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for AiCopilotController.
 */
@WebMvcTest(AiCopilotController.class)
class AiCopilotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiCopilotService aiCopilotService;

    private AiCopilotController.ChatRequest chatRequest;
    private AiCopilotService.ChatResponse chatResponse;

    @BeforeEach
    void setUp() {
        chatRequest = new AiCopilotController.ChatRequest();
        chatRequest.setMessage("How to configure rate limiting?");
        chatRequest.setContext("gateway");
        chatRequest.setInstanceId("test-instance");

        chatResponse = new AiCopilotService.ChatResponse(true, "To configure rate limiting, you need to...", null);
    }

    @Test
    @DisplayName("POST /api/copilot/chat - should return chat response")
    void chat_shouldReturnResponse() throws Exception {
        when(aiCopilotService.chat(anyString(), eq("How to configure rate limiting?"), eq("gateway"), eq("test-instance")))
                .thenReturn(chatResponse);

        mockMvc.perform(post("/api/copilot/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.response").value("To configure rate limiting, you need to..."))
                .andExpect(jsonPath("$.sessionId").exists());

        verify(aiCopilotService).chat(anyString(), eq("How to configure rate limiting?"), eq("gateway"), eq("test-instance"));
    }

    @Test
    @DisplayName("POST /api/copilot/chat - should generate session ID if not provided")
    void chat_shouldGenerateSessionId() throws Exception {
        chatRequest.setSessionId(null);
        when(aiCopilotService.chat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(chatResponse);

        mockMvc.perform(post("/api/copilot/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    @DisplayName("POST /api/copilot/chat - should use existing session ID")
    void chat_shouldUseExistingSessionId() throws Exception {
        chatRequest.setSessionId("existing-session-123");
        when(aiCopilotService.chat(eq("existing-session-123"), anyString(), anyString(), anyString()))
                .thenReturn(chatResponse);

        mockMvc.perform(post("/api/copilot/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("existing-session-123"));
    }

    @Test
    @DisplayName("DELETE /api/copilot/chat/{sessionId} - should clear history")
    void clearHistory_shouldClearHistory() throws Exception {
        doNothing().when(aiCopilotService).clearHistory("session-123");

        mockMvc.perform(delete("/api/copilot/chat/session-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Conversation history cleared"));

        verify(aiCopilotService).clearHistory("session-123");
    }

    @Test
    @DisplayName("POST /api/copilot/generate-route - should generate route configuration")
    void generateRoute_shouldGenerateRoute() throws Exception {
        AiCopilotService.RouteGenerationResult result = new AiCopilotService.RouteGenerationResult(true,
                "spring:\n  cloud:\n    gateway:\n      routes:\n        - id: test-route", null);

        when(aiCopilotService.generateRoute(eq("Route all /api/users to user-service"), anyString()))
                .thenReturn(result);

        Map<String, String> request = new HashMap<>();
        request.put("description", "Route all /api/users to user-service");
        request.put("instanceId", "test-instance");

        mockMvc.perform(post("/api/copilot/generate-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.config").exists());

        verify(aiCopilotService).generateRoute(eq("Route all /api/users to user-service"), anyString());
    }

    @Test
    @DisplayName("POST /api/copilot/analyze-error - should analyze error")
    void analyzeError_shouldAnalyzeError() throws Exception {
        AiCopilotService.DebugAnalysis analysis = new AiCopilotService.DebugAnalysis(true,
                "Root Cause: Connection refused to upstream service\nSolution: Check if the upstream service is running", null);

        when(aiCopilotService.analyzeError(eq("Connection refused: localhost:8080"), anyString()))
                .thenReturn(analysis);

        Map<String, String> request = new HashMap<>();
        request.put("errorMessage", "Connection refused: localhost:8080");
        request.put("instanceId", "test-instance");

        mockMvc.perform(post("/api/copilot/analyze-error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.analysis").exists());

        verify(aiCopilotService).analyzeError(eq("Connection refused: localhost:8080"), anyString());
    }

    @Test
    @DisplayName("GET /api/copilot/optimizations/{instanceId} - should return optimization suggestions")
    void suggestOptimizations_shouldReturnSuggestions() throws Exception {
        AiCopilotService.OptimizationResult result = new AiCopilotService.OptimizationResult(true,
                "1. Enable connection pooling\n2. Add caching for frequent requests", null);

        when(aiCopilotService.suggestOptimizations("test-instance"))
                .thenReturn(result);

        mockMvc.perform(get("/api/copilot/optimizations/test-instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.suggestions").exists());

        verify(aiCopilotService).suggestOptimizations("test-instance");
    }

    @Test
    @DisplayName("GET /api/copilot/explain - should explain concept")
    void explainConcept_shouldExplainConcept() throws Exception {
        when(aiCopilotService.explainConcept("Circuit Breaker"))
                .thenReturn("A circuit breaker is a design pattern used to detect failures...");

        mockMvc.perform(get("/api/copilot/explain")
                        .param("concept", "Circuit Breaker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.concept").value("Circuit Breaker"))
                .andExpect(jsonPath("$.explanation").exists());

        verify(aiCopilotService).explainConcept("Circuit Breaker");
    }

    @Test
    @DisplayName("POST /api/copilot/generate-route - should handle generation failure")
    void generateRoute_shouldHandleFailure() throws Exception {
        AiCopilotService.RouteGenerationResult result = new AiCopilotService.RouteGenerationResult(false,
                null, "Unable to understand the route description");

        when(aiCopilotService.generateRoute(anyString(), anyString()))
                .thenReturn(result);

        Map<String, String> request = new HashMap<>();
        request.put("description", "invalid description");
        request.put("instanceId", "test-instance");

        mockMvc.perform(post("/api/copilot/generate-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Unable to understand the route description"));
    }
}