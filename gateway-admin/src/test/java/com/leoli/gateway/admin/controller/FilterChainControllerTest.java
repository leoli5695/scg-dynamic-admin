package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.GatewayInstanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for FilterChainController.
 */
@WebMvcTest(FilterChainController.class)
class FilterChainControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GatewayInstanceService instanceService;

    @MockBean
    private RestTemplate restTemplate;

    private Map<String, Object> mockStats;
    private Map<String, Object> mockRecords;

    @BeforeEach
    void setUp() {
        mockStats = new HashMap<>();
        mockStats.put("totalRequests", 1000L);
        mockStats.put("avgFilterTime", 5.2);
        mockStats.put("filters", new HashMap<String, Object>() {{
            put("RateLimitFilter", Map.of("count", 1000, "avgTime", 1.5));
            put("AuthFilter", Map.of("count", 800, "avgTime", 3.0));
        }});

        mockRecords = new HashMap<>();
        mockRecords.put("records", java.util.Arrays.asList(
                Map.of("traceId", "trace-1", "path", "/api/users", "filters", java.util.Arrays.asList("RateLimitFilter", "AuthFilter")),
                Map.of("traceId", "trace-2", "path", "/api/orders", "filters", java.util.Arrays.asList("RateLimitFilter"))
        ));
    }

    @Test
    @DisplayName("GET /api/filter-chain/{instanceId}/stats - should return filter stats")
    void getFilterStats_shouldReturnStats() throws Exception {
        when(instanceService.getAccessUrl("test-instance")).thenReturn("http://localhost:8080");
        when(restTemplate.getForObject("http://localhost:8080/internal/filter-chain/stats", Map.class))
                .thenReturn(mockStats);

        mockMvc.perform(get("/api/filter-chain/test-instance/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalRequests").value(1000));

        verify(instanceService).getAccessUrl("test-instance");
        verify(restTemplate).getForObject("http://localhost:8080/internal/filter-chain/stats", Map.class);
    }

    @Test
    @DisplayName("GET /api/filter-chain/{instanceId}/stats - should return 404 when instance not found")
    void getFilterStats_shouldReturn404_whenInstanceNotFound() throws Exception {
        when(instanceService.getAccessUrl("non-existent")).thenReturn(null);

        mockMvc.perform(get("/api/filter-chain/non-existent/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Instance not found or not running"));

        verify(instanceService).getAccessUrl("non-existent");
        verify(restTemplate, never()).getForObject(anyString(), eq(Map.class));
    }

    @Test
    @DisplayName("GET /api/filter-chain/{instanceId}/stats - should handle exception")
    void getFilterStats_shouldHandleException() throws Exception {
        when(instanceService.getAccessUrl("test-instance")).thenReturn("http://localhost:8080");
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        mockMvc.perform(get("/api/filter-chain/test-instance/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /api/filter-chain/{instanceId}/records - should return filter records")
    void getFilterRecords_shouldReturnRecords() throws Exception {
        when(instanceService.getAccessUrl("test-instance")).thenReturn("http://localhost:8080");
        when(restTemplate.getForObject("http://localhost:8080/internal/filter-chain/records?limit=20", Map.class))
                .thenReturn(mockRecords);

        mockMvc.perform(get("/api/filter-chain/test-instance/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());

        verify(instanceService).getAccessUrl("test-instance");
    }

    @Test
    @DisplayName("GET /api/filter-chain/{instanceId}/records - should accept limit parameter")
    void getFilterRecords_shouldAcceptLimit() throws Exception {
        when(instanceService.getAccessUrl("test-instance")).thenReturn("http://localhost:8080");
        when(restTemplate.getForObject("http://localhost:8080/internal/filter-chain/records?limit=50", Map.class))
                .thenReturn(mockRecords);

        mockMvc.perform(get("/api/filter-chain/test-instance/records")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(restTemplate).getForObject("http://localhost:8080/internal/filter-chain/records?limit=50", Map.class);
    }

    @Test
    @DisplayName("GET /api/filter-chain/{instanceId}/records - should return 404 when instance not found")
    void getFilterRecords_shouldReturn404_whenInstanceNotFound() throws Exception {
        when(instanceService.getAccessUrl("non-existent")).thenReturn(null);

        mockMvc.perform(get("/api/filter-chain/non-existent/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("GET /api/filter-chain/{instanceId}/trace/{traceId} - should return filter trace")
    void getFilterTrace_shouldReturnTrace() throws Exception {
        Map<String, Object> mockTrace = new HashMap<>();
        mockTrace.put("traceId", "trace-123");
        mockTrace.put("path", "/api/users");
        mockTrace.put("method", "GET");
        mockTrace.put("filterChain", java.util.Arrays.asList(
                Map.of("name", "RateLimitFilter", "duration", 1.5, "result", "PASSED"),
                Map.of("name", "AuthFilter", "duration", 3.0, "result", "PASSED")
        ));

        when(instanceService.getAccessUrl("test-instance")).thenReturn("http://localhost:8080");
        when(restTemplate.getForObject("http://localhost:8080/internal/filter-chain/trace/trace-123", Map.class))
                .thenReturn(mockTrace);

        mockMvc.perform(get("/api/filter-chain/test-instance/trace/trace-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.traceId").value("trace-123"));

        verify(instanceService).getAccessUrl("test-instance");
        verify(restTemplate).getForObject("http://localhost:8080/internal/filter-chain/trace/trace-123", Map.class);
    }

    @Test
    @DisplayName("GET /api/filter-chain/{instanceId}/trace/{traceId} - should return 404 when instance not found")
    void getFilterTrace_shouldReturn404_whenInstanceNotFound() throws Exception {
        when(instanceService.getAccessUrl("non-existent")).thenReturn(null);

        mockMvc.perform(get("/api/filter-chain/non-existent/trace/trace-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("DELETE /api/filter-chain/{instanceId}/stats - should clear filter stats")
    void clearFilterStats_shouldClearStats() throws Exception {
        when(instanceService.getAccessUrl("test-instance")).thenReturn("http://localhost:8080");
        doNothing().when(restTemplate).delete("http://localhost:8080/internal/filter-chain/stats");

        mockMvc.perform(delete("/api/filter-chain/test-instance/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Filter chain stats cleared"));

        verify(instanceService).getAccessUrl("test-instance");
        verify(restTemplate).delete("http://localhost:8080/internal/filter-chain/stats");
    }

    @Test
    @DisplayName("DELETE /api/filter-chain/{instanceId}/stats - should return 404 when instance not found")
    void clearFilterStats_shouldReturn404_whenInstanceNotFound() throws Exception {
        when(instanceService.getAccessUrl("non-existent")).thenReturn(null);

        mockMvc.perform(delete("/api/filter-chain/non-existent/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("DELETE /api/filter-chain/{instanceId}/stats - should handle exception")
    void clearFilterStats_shouldHandleException() throws Exception {
        when(instanceService.getAccessUrl("test-instance")).thenReturn("http://localhost:8080");
        doThrow(new RuntimeException("Connection refused")).when(restTemplate).delete(anyString());

        mockMvc.perform(delete("/api/filter-chain/test-instance/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").exists());
    }
}