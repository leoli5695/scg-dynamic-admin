package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.DiagnosticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for DiagnosticController.
 */
@WebMvcTest(DiagnosticController.class)
class DiagnosticControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DiagnosticService diagnosticService;

    private DiagnosticService.DiagnosticReport testReport;
    private DiagnosticService.ComponentDiagnostic testDiagnostic;

    @BeforeEach
    void setUp() {
        testDiagnostic = new DiagnosticService.ComponentDiagnostic();
        testDiagnostic.setName("database");
        testDiagnostic.setStatus("HEALTHY");
        testDiagnostic.setMessage("Database connection is active");
        testDiagnostic.setScore(100);

        testReport = new DiagnosticService.DiagnosticReport();
        testReport.setOverallScore(85);
        testReport.setDuration(150L);
        testReport.setDatabase(testDiagnostic);
        testReport.setRedis(createDiagnostic("redis", "HEALTHY", 100));
        testReport.setConfigCenter(createDiagnostic("nacos", "HEALTHY", 100));
        testReport.setRoutes(createDiagnostic("routes", "WARNING", 70));
        testReport.setAuth(createDiagnostic("auth", "HEALTHY", 100));
        testReport.setGatewayInstances(createDiagnostic("instances", "WARNING", 60));
        testReport.setPerformance(createDiagnostic("performance", "HEALTHY", 90));
    }

    private DiagnosticService.ComponentDiagnostic createDiagnostic(String name, String status, int score) {
        DiagnosticService.ComponentDiagnostic diagnostic = new DiagnosticService.ComponentDiagnostic();
        diagnostic.setName(name);
        diagnostic.setStatus(status);
        diagnostic.setScore(score);
        return diagnostic;
    }

    @Test
    @DisplayName("GET /api/diagnostic/full - should return full diagnostic report")
    void runFullDiagnostic_shouldReturnReport() throws Exception {
        when(diagnosticService.runFullDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/full"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(85))
                .andExpect(jsonPath("$.duration").value(150))
                .andExpect(jsonPath("$.database.status").value("HEALTHY"))
                .andExpect(jsonPath("$.redis.status").value("HEALTHY"));

        verify(diagnosticService).runFullDiagnostic();
    }

    @Test
    @DisplayName("GET /api/diagnostic/full - should handle exception")
    void runFullDiagnostic_shouldHandleException() throws Exception {
        when(diagnosticService.runFullDiagnostic()).thenThrow(new RuntimeException("Connection failed"));

        mockMvc.perform(get("/api/diagnostic/full"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/diagnostic/quick - should return quick diagnostic report")
    void runQuickDiagnostic_shouldReturnReport() throws Exception {
        when(diagnosticService.runQuickDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/quick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(85));

        verify(diagnosticService).runQuickDiagnostic();
    }

    @Test
    @DisplayName("GET /api/diagnostic/quick - should handle exception")
    void runQuickDiagnostic_shouldHandleException() throws Exception {
        when(diagnosticService.runQuickDiagnostic()).thenThrow(new RuntimeException("Quick check failed"));

        mockMvc.perform(get("/api/diagnostic/quick"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/diagnostic/database - should return database diagnostic")
    void diagnoseDatabase_shouldReturnDiagnostic() throws Exception {
        when(diagnosticService.runFullDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/database"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.score").value(100));
    }

    @Test
    @DisplayName("GET /api/diagnostic/database - should handle null diagnostic")
    void diagnoseDatabase_shouldHandleNull() throws Exception {
        testReport.setDatabase(null);
        when(diagnosticService.runFullDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/database"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/redis - should return redis diagnostic")
    void diagnoseRedis_shouldReturnDiagnostic() throws Exception {
        when(diagnosticService.runFullDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/redis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/config-center - should return config center diagnostic")
    void diagnoseConfigCenter_shouldReturnDiagnostic() throws Exception {
        when(diagnosticService.runFullDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/config-center"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/routes - should return routes diagnostic")
    void diagnoseRoutes_shouldReturnDiagnostic() throws Exception {
        when(diagnosticService.runFullDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WARNING"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/auth - should return auth diagnostic")
    void diagnoseAuth_shouldReturnDiagnostic() throws Exception {
        when(diagnosticService.runFullDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/instances - should return instances diagnostic")
    void diagnoseInstances_shouldReturnDiagnostic() throws Exception {
        when(diagnosticService.runFullDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WARNING"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/performance - should return performance diagnostic")
    void diagnosePerformance_shouldReturnDiagnostic() throws Exception {
        when(diagnosticService.runFullDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/score - should return health score")
    void getHealthScore_shouldReturnScore() throws Exception {
        when(diagnosticService.runQuickDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(85))
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.duration").value("150ms"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/score - should return WARNING status for medium score")
    void getHealthScore_shouldReturnWarning_forMediumScore() throws Exception {
        testReport.setOverallScore(65);
        when(diagnosticService.runQuickDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(65))
                .andExpect(jsonPath("$.status").value("WARNING"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/score - should return CRITICAL status for low score")
    void getHealthScore_shouldReturnCritical_forLowScore() throws Exception {
        testReport.setOverallScore(30);
        when(diagnosticService.runQuickDiagnostic()).thenReturn(testReport);

        mockMvc.perform(get("/api/diagnostic/score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(30))
                .andExpect(jsonPath("$.status").value("CRITICAL"));
    }

    @Test
    @DisplayName("GET /api/diagnostic/score - should handle exception")
    void getHealthScore_shouldHandleException() throws Exception {
        when(diagnosticService.runQuickDiagnostic()).thenThrow(new RuntimeException("Score calculation failed"));

        mockMvc.perform(get("/api/diagnostic/score"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }
}