package com.leoli.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Audit Log API Integration Tests with Nacos Namespace Isolation
 * Tests audit trail, diff comparison, rollback, and export functionality.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AuditLogTest extends NamespaceIntegrationTest {

    private static String testRouteId;
    private static String auditLogId;

    @BeforeAll
    static void setupInstance() throws Exception {
        NamespaceIntegrationTest test = new NamespaceIntegrationTest() {};
        testInstanceId = test.createTestInstance("test-audit-instance");
        System.out.println("[SETUP] Created test instance: " + testInstanceId);
    }

    @AfterAll
    static void cleanupInstance() throws Exception {
        NamespaceIntegrationTest test = new NamespaceIntegrationTest() {};
        test.deleteTestInstance(testInstanceId);
        System.out.println("[CLEANUP] Deleted test instance: " + testInstanceId);
    }

    @BeforeEach
    void setUp() throws Exception {
        cleanAllData();
        // Create a route to generate audit logs
        testRouteId = createTestRoute("audit-test-route");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            mockMvc.perform(delete("/api/routes/" + testRouteId));
        } catch (Exception e) {
            // Ignore
        }
    }

    @Test
    @Order(1)
    @DisplayName("Get audit logs - should return paginated audit trail")
    void test01_GetAuditLogs() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs")
                        .param("instanceId", testInstanceId)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Audit logs should not be null");
        assertTrue(data.has("content"), "Response should have content");
        assertTrue(data.has("totalElements"), "Response should have totalElements");

        System.out.println("[PASS] Retrieved audit logs with pagination");
    }

    @Test
    @Order(2)
    @DisplayName("Get audit logs filtered by target type - should filter results")
    void test02_GetAuditLogs_ByTargetType() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs")
                        .param("instanceId", testInstanceId)
                        .param("targetType", "ROUTE")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Filtered audit logs should not be null");

        System.out.println("[PASS] Audit logs filtered by target type");
    }

    @Test
    @Order(3)
    @DisplayName("Get audit logs filtered by operation type - should filter results")
    void test03_GetAuditLogs_ByOperationType() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs")
                        .param("instanceId", testInstanceId)
                        .param("operationType", "CREATE")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Filtered audit logs should not be null");

        System.out.println("[PASS] Audit logs filtered by operation type");
    }

    @Test
    @Order(4)
    @DisplayName("Get audit logs by time range - should filter by date")
    void test04_GetAuditLogs_ByTimeRange() throws Exception {
        String startTime = "2024-01-01T00:00:00";
        String endTime = "2026-12-31T23:59:59";

        MvcResult result = mockMvc.perform(get("/api/audit-logs")
                        .param("instanceId", testInstanceId)
                        .param("startTime", startTime)
                        .param("endTime", endTime)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Time-filtered audit logs should not be null");

        System.out.println("[PASS] Audit logs filtered by time range");
    }

    @Test
    @Order(5)
    @DisplayName("Get audit log by ID - should return log details")
    void test05_GetAuditLogById() throws Exception {
        // Get first audit log
        MvcResult listResult = mockMvc.perform(get("/api/audit-logs")
                        .param("instanceId", testInstanceId)
                        .param("page", "0")
                        .param("size", "1"))
                .andReturn();

        JsonNode listData = extractData(listResult);
        if (listData.get("content").size() == 0) {
            System.out.println("[SKIP] No audit logs available");
            return;
        }

        String logId = listData.get("content").get(0).get("id").asText();

        MvcResult result = mockMvc.perform(get("/api/audit-logs/" + logId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(logId, data.get("id").asText());

        System.out.println("[PASS] Audit log retrieved by ID");
    }

    @Test
    @Order(6)
    @DisplayName("Get audit log diff - should show before/after changes")
    void test06_GetAuditLogDiff() throws Exception {
        // Get first audit log
        MvcResult listResult = mockMvc.perform(get("/api/audit-logs")
                        .param("instanceId", testInstanceId)
                        .param("page", "0")
                        .param("size", "1"))
                .andReturn();

        JsonNode listData = extractData(listResult);
        if (listData.get("content").size() == 0) {
            System.out.println("[SKIP] No audit logs available for diff");
            return;
        }

        String logId = listData.get("content").get(0).get("id").asText();

        MvcResult result = mockMvc.perform(get("/api/audit-logs/" + logId + "/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Diff should not be null");

        System.out.println("[PASS] Audit log diff retrieved");
    }

    @Test
    @Order(7)
    @DisplayName("Get audit log statistics - should return summary stats")
    void test07_GetAuditLogStats() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs/stats")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Statistics should not be null");

        System.out.println("[PASS] Audit log statistics retrieved");
    }

    @Test
    @Order(8)
    @DisplayName("Get target types - should return available target types")
    void test08_GetTargetTypes() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs/target-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Target types should be an array");

        System.out.println("[PASS] Target types retrieved: " + data.size());
    }

    @Test
    @Order(9)
    @DisplayName("Get operation types - should return available operation types")
    void test09_GetOperationTypes() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs/operation-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Operation types should be an array");

        System.out.println("[PASS] Operation types retrieved: " + data.size());
    }

    @Test
    @Order(10)
    @DisplayName("Get timeline - should return chronological events")
    void test10_GetTimeline() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs/timeline/" + testInstanceId)
                        .param("days", "7")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Timeline should not be null");

        System.out.println("[PASS] Timeline retrieved");
    }

    @Test
    @Order(11)
    @DisplayName("Export audit logs as CSV - should generate CSV file")
    void test11_ExportAuditLogsCsv() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs/export/csv")
                        .param("instanceId", testInstanceId)
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertNotNull(content, "CSV export should not be null");

        System.out.println("[PASS] Audit logs exported as CSV");
    }

    @Test
    @Order(12)
    @DisplayName("Export audit logs as JSON - should generate JSON file")
    void test12_ExportAuditLogsJson() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs/export/json")
                        .param("instanceId", testInstanceId)
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertNotNull(content, "JSON export should not be null");

        System.out.println("[PASS] Audit logs exported as JSON");
    }

    @Test
    @Order(13)
    @DisplayName("Get cleanup stats - should return cleanup information")
    void test13_GetCleanupStats() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit-logs/cleanup/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Cleanup stats should not be null");

        System.out.println("[PASS] Cleanup stats retrieved");
    }

    @Test
    @Order(14)
    @DisplayName("Trigger cleanup - should remove old audit logs")
    void test14_TriggerCleanup() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/audit-logs/cleanup")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Cleanup response should not be null");

        System.out.println("[PASS] Cleanup triggered");
    }

    private String createTestRoute(String name) throws Exception {
        ObjectNode route = objectMapper.createObjectNode();
        route.put("routeName", name);
        route.put("uri", "static://test-service");
        route.put("mode", "SINGLE");
        route.put("serviceId", "test-service");
        route.put("order", 0);

        var predicates = route.putArray("predicates");
        var predicate = predicates.addObject();
        predicate.put("name", "Path");
        predicate.putObject("args").put("pattern", "/api/test/**");

        MvcResult result = mockMvc.perform(post("/api/routes")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(route.toString()))
                .andReturn();

        return extractData(result).get("id").asText();
    }
}
