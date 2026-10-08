package com.leoli.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Monitoring, Tracing, and Health API Integration Tests
 * Tests request tracing, health checks, diagnostics, and monitoring endpoints.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MonitoringTest extends NamespaceIntegrationTest {

    @BeforeEach
    @Order(0)
    void setupInstance() throws Exception {
        if (testInstanceId == null) {
            testInstanceId = createTestInstance("test-monitoring-instance");
        }
        System.out.println("[SETUP] Created test instance: " + testInstanceId);
    }

    @AfterAll
    void cleanupInstance() throws Exception {
        deleteTestInstance(testInstanceId);
        System.out.println("[CLEANUP] Deleted test instance: " + testInstanceId);
    }

    @Value("${gateway.admin.security.internal-token:}")
    private String internalToken;

    private final List<String> runningInstanceIds = new ArrayList<>();

    /**
     * The multi-instance endpoints only consider RUNNING instances, and an instance only
     * reaches RUNNING after a heartbeat - a freshly created one is STARTING, so compare/all
     * and ranking answer 404 and outliers answers 400 ("Need at least 3 instances").
     * Drive the real state machine through the heartbeat endpoint rather than poking the
     * status column, so the transition itself stays covered.
     */
    private void ensureRunningInstances(int count) throws Exception {
        while (runningInstanceIds.size() < count) {
            String id = createTestInstance("monitoring-running-" + runningInstanceIds.size());
            mockMvc.perform(post("/api/instances/heartbeat")
                            .param("instanceId", id)
                            .header("X-Internal-Token", internalToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
            runningInstanceIds.add(id);
        }
    }

    // ==================== Health Check Tests ====================

    @Test
    @Order(1)
    @DisplayName("Health check - should return comprehensive health status")
    void test01_HealthCheck() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Health check should return data");
        assertTrue(data.has("status"), "Should have status field");

        System.out.println("[PASS] Health check successful: " + data.get("status").asText());
    }

    @Test
    @Order(2)
    @DisplayName("Liveness probe - should return liveness status")
    void test02_LivenessProbe() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Liveness check should return data");

        System.out.println("[PASS] Liveness probe successful");
    }

    @Test
    @Order(3)
    @DisplayName("Readiness probe - should return readiness status")
    void test03_ReadinessProbe() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Readiness check should return data");

        System.out.println("[PASS] Readiness probe successful");
    }

    @Test
    @Order(4)
    @DisplayName("Component health - should return detailed component status")
    void test04_ComponentHealth() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health/components"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Component health should return data");

        System.out.println("[PASS] Component health retrieved");
    }

    // ==================== Diagnostic Tests ====================

    @Test
    @Order(5)
    @DisplayName("Full diagnostic - should return comprehensive diagnostics")
    void test05_FullDiagnostic() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/full"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Full diagnostic should return data");
        assertTrue(data.has("overallScore"), "Should have health score");

        System.out.println("[PASS] Full diagnostic completed, score: " + data.get("overallScore").asInt());
    }

    @Test
    @Order(6)
    @DisplayName("Quick diagnostic - should return quick health check")
    void test06_QuickDiagnostic() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/quick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Quick diagnostic should return data");

        System.out.println("[PASS] Quick diagnostic completed");
    }

    @Test
    @Order(7)
    @DisplayName("Database diagnostic - should check database connectivity")
    void test07_DatabaseDiagnostic() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/database"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Database diagnostic should return data");

        System.out.println("[PASS] Database diagnostic completed");
    }

    @Test
    @Order(8)
    @DisplayName("Redis diagnostic - should check Redis connectivity")
    void test08_RedisDiagnostic() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/redis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Redis diagnostic should return data");

        System.out.println("[PASS] Redis diagnostic completed");
    }

    @Test
    @Order(9)
    @DisplayName("Config center diagnostic - should check Nacos/Consul connectivity")
    void test09_ConfigCenterDiagnostic() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/config-center"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Config center diagnostic should return data");

        System.out.println("[PASS] Config center diagnostic completed");
    }

    @Test
    @Order(10)
    @DisplayName("Routes diagnostic - should check route configuration")
    void test10_RoutesDiagnostic() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Routes diagnostic should return data");

        System.out.println("[PASS] Routes diagnostic completed");
    }

    @Test
    @Order(11)
    @DisplayName("Auth diagnostic - should check authentication configuration")
    void test11_AuthDiagnostic() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Auth diagnostic should return data");

        System.out.println("[PASS] Auth diagnostic completed");
    }

    @Test
    @Order(12)
    @DisplayName("Performance diagnostic - should check system performance")
    void test12_PerformanceDiagnostic() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Performance diagnostic should return data");

        System.out.println("[PASS] Performance diagnostic completed");
    }

    @Test
    @Order(13)
    @DisplayName("Health score - should return overall health score")
    void test13_HealthScore() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Health score should return data");
        assertTrue(data.has("score"), "Should have score field");

        System.out.println("[PASS] Health score: " + data.get("score").asInt());
    }

    @Test
    @Order(14)
    @DisplayName("Diagnostic history - should return historical diagnostics")
    void test14_DiagnosticHistory() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/history")
                        .param("hours", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Diagnostic history should return data");

        System.out.println("[PASS] Diagnostic history retrieved");
    }

    @Test
    @Order(15)
    @DisplayName("Score trend - should return health score trend")
    void test15_ScoreTrend() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostic/trend")
                        .param("hours", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Score trend should return data");

        System.out.println("[PASS] Score trend retrieved");
    }

    // ==================== Request Trace Tests ====================

    @Test
    @Order(16)
    @DisplayName("Get trace statistics - should return trace stats")
    void test16_GetTraceStats() throws Exception {
        // The trace endpoints return their payload directly - they are NOT wrapped in
        // ApiResponse, so there is no $.code / $.data envelope to assert on.
        mockMvc.perform(get("/api/traces/stats")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.errorsToday").isNumber())
                .andExpect(jsonPath("$.errorsLastHour").isNumber())
                .andExpect(jsonPath("$.recentErrors").isArray());

        System.out.println("[PASS] Trace statistics retrieved");
    }

    @Test
    @Order(17)
    @DisplayName("Get traces - should return paginated traces")
    void test17_GetTraces() throws Exception {
        // Returns a raw Spring Data Page, not an ApiResponse envelope.
        mockMvc.perform(get("/api/traces")
                        .param("page", "0")
                        .param("size", "20")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20));

        System.out.println("[PASS] Traces retrieved with pagination");
    }

    @Test
    @Order(18)
    @DisplayName("Get recent errors - should return error traces")
    void test18_GetRecentErrors() throws Exception {
        // Returns a bare JSON array, not an envelope.
        String body = mockMvc.perform(get("/api/traces/errors/recent")
                        .param("limit", "50")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(objectMapper.readTree(body).isArray(), "Recent errors should return a JSON array");

        System.out.println("[PASS] Recent errors retrieved");
    }

    @Test
    @Order(19)
    @DisplayName("Get slow traces - should return slow requests")
    void test19_GetSlowTraces() throws Exception {
        // Returns a raw Spring Data Page, not an ApiResponse envelope.
        mockMvc.perform(get("/api/traces/slow")
                        .param("page", "0")
                        .param("size", "20")
                        .param("thresholdMs", "1000")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());

        System.out.println("[PASS] Slow traces retrieved");
    }

    @Test
    @Order(20)
    @DisplayName("Delete old traces - should cleanup old trace data")
    void test20_DeleteOldTraces() throws Exception {
        // Returns the delete count directly: {"deleted": N} - no envelope.
        mockMvc.perform(delete("/api/traces/old")
                        .param("daysToKeep", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").isNumber());

        System.out.println("[PASS] Old traces deleted");
    }

    // ==================== Multi-Instance Comparison Tests ====================

    @Test
    @Order(21)
    @DisplayName("Compare all instances - should return comparison data")
    void test21_CompareAllInstances() throws Exception {
        ensureRunningInstances(3);

        MvcResult result = mockMvc.perform(get("/api/multi-instance/compare/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Instance comparison should return data");

        System.out.println("[PASS] All instances compared");
    }

    @Test
    @Order(22)
    @DisplayName("Get instance ranking - should return performance ranking")
    void test22_GetInstanceRanking() throws Exception {
        ensureRunningInstances(3);

        MvcResult result = mockMvc.perform(get("/api/multi-instance/ranking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Instance ranking should return data");

        System.out.println("[PASS] Instance ranking retrieved");
    }

    @Test
    @Order(23)
    @DisplayName("Get performance outliers - should identify outlier instances")
    void test23_GetPerformanceOutliers() throws Exception {
        ensureRunningInstances(3);

        MvcResult result = mockMvc.perform(get("/api/multi-instance/outliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Performance outliers should return data");

        System.out.println("[PASS] Performance outliers identified");
    }
}
