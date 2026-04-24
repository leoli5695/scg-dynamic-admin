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
 * Strategy API Integration Tests with Nacos Namespace Isolation
 * Tests rate limiting, circuit breaker, retry, and other strategies.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StrategyNamespaceTest extends NamespaceIntegrationTest {

    private static String createdStrategyId;
    private static String strategyName = "test-strategy-namespace";

    @BeforeAll
    static void setupInstance() throws Exception {
        NamespaceIntegrationTest test = new NamespaceIntegrationTest() {};
        testInstanceId = test.createTestInstance("test-strategy-instance");
        System.out.println("[SETUP] Created test instance: " + testInstanceId + " with namespace: " + TEST_NAMESPACE);
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
    }

    @Test
    @Order(1)
    @DisplayName("Create rate limit strategy - should isolate to namespace")
    void test01_CreateRateLimitStrategy() throws Exception {
        ObjectNode strategy = objectMapper.createObjectNode();
        strategy.put("strategyName", strategyName);
        strategy.put("type", "RATE_LIMIT");
        strategy.put("scope", "GLOBAL");
        strategy.put("enabled", true);

        ObjectNode config = strategy.putObject("config");
        config.put("rate", 100);
        config.put("burstSize", 150);
        config.put("timeWindow", 60);
        config.put("unit", "SECONDS");
        config.put("limitBy", "IP");

        MvcResult result = mockMvc.perform(post("/api/strategies")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(strategy.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        createdStrategyId = data.get("strategyId").asText();
        assertNotNull(createdStrategyId, "Strategy ID should be generated");
        assertEquals(strategyName, data.get("strategyName").asText());

        System.out.println("[PASS] Rate limit strategy created: " + createdStrategyId);
    }

    @Test
    @Order(2)
    @DisplayName("Get strategies filtered by instanceId - should only return strategies in namespace")
    void test02_GetStrategies_ByInstanceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/strategies")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Response should be an array");

        boolean found = false;
        for (JsonNode s : data) {
            if (strategyName.equals(s.get("strategyName").asText())) {
                found = true;
                assertEquals("RATE_LIMIT", s.get("type").asText());
                assertEquals("GLOBAL", s.get("scope").asText());
                break;
            }
        }
        assertTrue(found, "Created strategy should be in list for this instance");

        System.out.println("[PASS] Strategies filtered correctly by instanceId");
    }

    @Test
    @Order(3)
    @DisplayName("Get strategy by ID - should return strategy details")
    void test03_GetStrategyById() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/strategies/" + createdStrategyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(strategyName, data.get("strategyName").asText());
        assertEquals("RATE_LIMIT", data.get("type").asText());

        System.out.println("[PASS] Get strategy by ID works");
    }

    @Test
    @Order(4)
    @DisplayName("Get strategies by type - should filter by strategy type")
    void test04_GetStrategies_ByType() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/strategies/type/RATE_LIMIT")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Response should be an array");
        assertTrue(data.size() > 0, "Should have at least one rate limit strategy");

        System.out.println("[PASS] Get strategies by type works");
    }

    @Test
    @Order(5)
    @DisplayName("Get global strategies - should return GLOBAL scope strategies")
    void test05_GetGlobalStrategies() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/strategies/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Response should be an array");

        System.out.println("[PASS] Get global strategies works");
    }

    @Test
    @Order(6)
    @DisplayName("Disable strategy - should update strategy status")
    void test06_DisableStrategy() throws Exception {
        mockMvc.perform(post("/api/strategies/" + createdStrategyId + "/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/strategies/" + createdStrategyId))
                .andReturn();
        JsonNode data = extractData(result);
        assertFalse(data.get("enabled").asBoolean(), "Strategy should be disabled");

        System.out.println("[PASS] Strategy disabled successfully");
    }

    @Test
    @Order(7)
    @DisplayName("Enable strategy - should update strategy status")
    void test07_EnableStrategy() throws Exception {
        mockMvc.perform(post("/api/strategies/" + createdStrategyId + "/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/strategies/" + createdStrategyId))
                .andReturn();
        JsonNode data = extractData(result);
        assertTrue(data.get("enabled").asBoolean(), "Strategy should be enabled");

        System.out.println("[PASS] Strategy enabled successfully");
    }

    @Test
    @Order(8)
    @DisplayName("Update strategy - should persist changes")
    void test08_UpdateStrategy() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/api/strategies/" + createdStrategyId))
                .andReturn();
        JsonNode currentStrategy = extractData(getResult);

        ObjectNode updatedStrategy = currentStrategy.deepCopy();
        ObjectNode config = updatedStrategy.get("config").deepCopy();
        config.put("rate", 200);
        updatedStrategy.set("config", config);

        mockMvc.perform(put("/api/strategies/" + createdStrategyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedStrategy.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult verifyResult = mockMvc.perform(get("/api/strategies/" + createdStrategyId))
                .andReturn();
        JsonNode verified = extractData(verifyResult);
        assertEquals(200, verified.get("config").get("rate").asInt());

        System.out.println("[PASS] Strategy updated successfully");
    }

    @Test
    @Order(9)
    @DisplayName("Create circuit breaker strategy - should support different strategy types")
    void test09_CreateCircuitBreakerStrategy() throws Exception {
        ObjectNode strategy = objectMapper.createObjectNode();
        strategy.put("strategyName", "test-circuit-breaker");
        strategy.put("type", "CIRCUIT_BREAKER");
        strategy.put("scope", "ROUTE");
        strategy.put("enabled", true);

        ObjectNode config = strategy.putObject("config");
        config.put("failureRateThreshold", 50);
        config.put("slowCallRateThreshold", 80);
        config.put("waitDurationInOpenState", 60);
        config.put("slidingWindowSize", 100);
        config.put("minimumNumberOfCalls", 10);

        MvcResult result = mockMvc.perform(post("/api/strategies")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(strategy.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        String cbStrategyId = data.get("strategyId").asText();
        assertEquals("CIRCUIT_BREAKER", data.get("type").asText());

        // Cleanup
        mockMvc.perform(delete("/api/strategies/" + cbStrategyId));

        System.out.println("[PASS] Circuit breaker strategy created");
    }

    @Test
    @Order(10)
    @DisplayName("Create retry strategy - should support retry configuration")
    void test10_CreateRetryStrategy() throws Exception {
        ObjectNode strategy = objectMapper.createObjectNode();
        strategy.put("strategyName", "test-retry");
        strategy.put("type", "RETRY");
        strategy.put("scope", "ROUTE");
        strategy.put("enabled", true);

        ObjectNode config = strategy.putObject("config");
        config.put("maxRetries", 3);
        config.put("retryOnException", true);
        config.put("retryOnResponseStatus", "5xx");
        config.put("backoffDelay", 1000);
        config.put("maxBackoffDelay", 10000);

        MvcResult result = mockMvc.perform(post("/api/strategies")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(strategy.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        String retryStrategyId = data.get("strategyId").asText();
        assertEquals("RETRY", data.get("type").asText());

        // Cleanup
        mockMvc.perform(delete("/api/strategies/" + retryStrategyId));

        System.out.println("[PASS] Retry strategy created");
    }

    @Test
    @Order(11)
    @DisplayName("Delete strategy - should remove from database and config center")
    void test11_DeleteStrategy() throws Exception {
        mockMvc.perform(delete("/api/strategies/" + createdStrategyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/strategies/" + createdStrategyId))
                .andExpect(status().isNotFound());

        System.out.println("[PASS] Strategy deleted successfully");
    }

    @Test
    @Order(12)
    @DisplayName("Verify namespace isolation - strategies from different instances are separate")
    void test12_NamespaceIsolation() throws Exception {
        NamespaceIntegrationTest test = new NamespaceIntegrationTest() {};
        String otherInstanceId = test.createTestInstance("other-strategy-instance");

        try {
            // Create strategy in other instance
            ObjectNode strategy = objectMapper.createObjectNode();
            strategy.put("strategyName", "other-strategy");
            strategy.put("type", "RATE_LIMIT");
            strategy.put("scope", "GLOBAL");
            strategy.put("enabled", true);
            strategy.putObject("config").put("rate", 50);

            mockMvc.perform(post("/api/strategies")
                            .param("instanceId", otherInstanceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(strategy.toString()))
                    .andExpect(status().isOk());

            // Verify our instance doesn't see the other strategy
            MvcResult result = mockMvc.perform(get("/api/strategies")
                            .param("instanceId", testInstanceId))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode data = extractData(result);
            boolean foundOtherStrategy = false;
            for (JsonNode s : data) {
                if ("other-strategy".equals(s.get("strategyName").asText())) {
                    foundOtherStrategy = true;
                    break;
                }
            }
            assertFalse(foundOtherStrategy, "Should not see strategies from other namespace");

            System.out.println("[PASS] Namespace isolation verified - instances have separate strategies");
        } finally {
            test.deleteTestInstance(otherInstanceId);
        }
    }
}
