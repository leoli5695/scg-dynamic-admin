package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.service.RequestReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for RequestReplayController.
 */
@WebMvcTest(RequestReplayController.class)
class RequestReplayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RequestReplayService replayService;

    private RequestReplayService.ReplayableRequest testRequest;
    private RequestReplayService.ReplayResult testResult;

    @BeforeEach
    void setUp() {
        testRequest = new RequestReplayService.ReplayableRequest();
        testRequest.setTraceId(1L);
        testRequest.setTraceUuid("trace-uuid-123");
        testRequest.setMethod("GET");
        testRequest.setPath("/api/users");
        testRequest.setQueryString("page=1");
        testRequest.setHeaders(new HashMap<>());
        testRequest.setOriginalHeaders(new HashMap<>());
        testRequest.setRequestBody("");
        testRequest.setOriginalRequestBody("");
        testRequest.setOriginalStatusCode(200);
        testRequest.setOriginalResponseBody("{\"users\":[]}");
        testRequest.setOriginalLatencyMs(50);

        testResult = new RequestReplayService.ReplayResult();
        testResult.setSuccess(true);
        testResult.setTraceId(1L);
        testResult.setMethod("GET");
        testResult.setRequestUrl("http://localhost:8080/api/users?page=1");
        testResult.setStatusCode(200);
        testResult.setResponseBody("{\"users\":[]}");
        testResult.setResponseHeaders(new HashMap<>());
        testResult.setLatencyMs(45);
    }

    @Test
    @DisplayName("GET /api/replay/prepare/{traceId} - should return replayable request")
    void prepareReplay_shouldReturnRequest() throws Exception {
        when(replayService.prepareReplay(1L)).thenReturn(testRequest);

        mockMvc.perform(get("/api/replay/prepare/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.traceId").value(1))
                .andExpect(jsonPath("$.data.method").value("GET"))
                .andExpect(jsonPath("$.data.path").value("/api/users"));

        verify(replayService).prepareReplay(1L);
    }

    @Test
    @DisplayName("GET /api/replay/prepare/{traceId} - should return 404 when not found")
    void prepareReplay_shouldReturn404_whenNotFound() throws Exception {
        when(replayService.prepareReplay(999L)).thenReturn(null);

        mockMvc.perform(get("/api/replay/prepare/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Trace not found or not replayable"));

        verify(replayService).prepareReplay(999L);
    }

    @Test
    @DisplayName("POST /api/replay/execute/{traceId} - should execute replay")
    void executeReplay_shouldExecuteReplay() throws Exception {
        when(replayService.executeReplay(eq(1L), eq("test-instance"), any())).thenReturn(testResult);

        mockMvc.perform(post("/api/replay/execute/1")
                        .param("instanceId", "test-instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.latencyMs").value(45));

        verify(replayService).executeReplay(eq(1L), eq("test-instance"), any());
    }

    @Test
    @DisplayName("POST /api/replay/execute/{traceId} - should handle replay with options")
    void executeReplay_shouldHandleWithOptions() throws Exception {
        RequestReplayService.ReplayOptions options = new RequestReplayService.ReplayOptions();
        options.setModifiedPath("/api/v2/users");
        options.setCompareWithOriginal(true);

        when(replayService.executeReplay(eq(1L), eq("test-instance"), any())).thenReturn(testResult);

        mockMvc.perform(post("/api/replay/execute/1")
                        .param("instanceId", "test-instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(options)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/replay/quick/{traceId} - should execute quick replay")
    void quickReplay_shouldExecuteQuickReplay() throws Exception {
        when(replayService.executeReplay(eq(1L), eq("test-instance"), isNull())).thenReturn(testResult);

        mockMvc.perform(post("/api/replay/quick/1")
                        .param("instanceId", "test-instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.traceId").value(1));

        verify(replayService).executeReplay(eq(1L), eq("test-instance"), isNull());
    }

    @Test
    @DisplayName("POST /api/replay/batch/start - should start batch replay")
    void startBatchReplay_shouldStartBatch() throws Exception {
        when(replayService.startBatchReplay(anyList(), eq("test-instance"), any()))
                .thenReturn("batch-session-123");

        List<Long> traceIds = Arrays.asList(1L, 2L, 3L);

        mockMvc.perform(post("/api/replay/batch/start")
                        .param("instanceId", "test-instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(traceIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.sessionId").value("batch-session-123"));

        verify(replayService).startBatchReplay(anyList(), eq("test-instance"), any());
    }

    @Test
    @DisplayName("GET /api/replay/batch/status/{sessionId} - should return batch status")
    void getBatchStatus_shouldReturnStatus() throws Exception {
        RequestReplayService.ReplaySession session = new RequestReplayService.ReplaySession();
        session.setSessionId("batch-session-123");
        session.setTotal(3);
        session.setCompleted(2);
        session.setFailed(0);
        session.setRunning(true);

        when(replayService.getBatchStatus("batch-session-123")).thenReturn(session);

        mockMvc.perform(get("/api/replay/batch/status/batch-session-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("batch-session-123"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.completed").value(2));

        verify(replayService).getBatchStatus("batch-session-123");
    }

    @Test
    @DisplayName("GET /api/replay/batch/status/{sessionId} - should return 404 when session not found")
    void getBatchStatus_shouldReturn404_whenNotFound() throws Exception {
        when(replayService.getBatchStatus("non-existent")).thenReturn(null);

        mockMvc.perform(get("/api/replay/batch/status/non-existent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Session not found"));

        verify(replayService).getBatchStatus("non-existent");
    }

    @Test
    @DisplayName("DELETE /api/replay/batch/cancel/{sessionId} - should cancel batch")
    void cancelBatch_shouldCancelBatch() throws Exception {
        when(replayService.cancelBatch("batch-session-123")).thenReturn(true);

        mockMvc.perform(delete("/api/replay/batch/cancel/batch-session-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Batch replay cancelled"));

        verify(replayService).cancelBatch("batch-session-123");
    }

    @Test
    @DisplayName("DELETE /api/replay/batch/cancel/{sessionId} - should return error when cannot cancel")
    void cancelBatch_shouldReturnError_whenCannotCancel() throws Exception {
        when(replayService.cancelBatch("batch-session-123")).thenReturn(false);

        mockMvc.perform(delete("/api/replay/batch/cancel/batch-session-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Cannot cancel session"));

        verify(replayService).cancelBatch("batch-session-123");
    }

    @Test
    @DisplayName("POST /api/replay/execute/{traceId} - should return result with comparison")
    void executeReplay_shouldReturnWithComparison() throws Exception {
        RequestReplayService.ReplayResult result = new RequestReplayService.ReplayResult();
        result.setSuccess(true);
        result.setTraceId(1L);
        result.setMethod("GET");
        result.setRequestUrl("http://localhost:8080/api/users");
        result.setStatusCode(200);
        result.setResponseBody("{\"users\":[{\"id\":1}]}");
        result.setLatencyMs(45);

        RequestReplayService.ReplayComparison comparison = new RequestReplayService.ReplayComparison();
        comparison.setOriginalStatus(200);
        comparison.setReplayedStatus(200);
        comparison.setStatusMatch(true);
        comparison.setOriginalLatencyMs(50);
        comparison.setReplayedLatencyMs(45);
        comparison.setLatencyDiffMs(-5);
        result.setComparison(comparison);

        when(replayService.executeReplay(eq(1L), eq("test-instance"), any())).thenReturn(result);

        mockMvc.perform(post("/api/replay/execute/1")
                        .param("instanceId", "test-instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.comparison.statusMatch").value(true))
                .andExpect(jsonPath("$.comparison.latencyDiffMs").value(-5));

        verify(replayService).executeReplay(eq(1L), eq("test-instance"), any());
    }
}