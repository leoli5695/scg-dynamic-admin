package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for HealthCheckController.
 */
class HealthCheckControllerTest extends BaseIntegrationTest {

    @Test
    void health_shouldReturnOverallStatus() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertNotNull(content);
        assertTrue(content.contains("status"));
        assertTrue(content.contains("database"));
        assertTrue(content.contains("redis"));
        assertTrue(content.contains("nacos"));
        assertTrue(content.contains("timestamp"));
    }

    @Test
    void liveness_shouldReturnUpStatus() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health/liveness"))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("\"status\":\"UP\""));
        assertTrue(content.contains("timestamp"));
    }

    @Test
    void readiness_shouldReturnStatus() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health/readiness"))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("status"));
        assertTrue(content.contains("timestamp"));
    }

    @Test
    void components_shouldReturnDetailedStatus() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health/components"))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertNotNull(content);
        assertTrue(content.contains("database"));
        assertTrue(content.contains("redis"));
        assertTrue(content.contains("nacos"));
    }

    @Test
    void health_shouldReturnJsonContentType() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"));
    }

    @Test
    void liveness_shouldReturnJsonContentType() throws Exception {
        mockMvc.perform(get("/api/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"));
    }

    @Test
    void readiness_shouldReturnJsonContentType() throws Exception {
        mockMvc.perform(get("/api/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"));
    }
}