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
 * Strategy API Integration Tests
 * Tests: Create, Read, Update, Delete, Enable/Disable
 * Tests all strategy types: RATE_LIMITER, CIRCUIT_BREAKER, RETRY, TIMEOUT, etc.
 * Verifies: Database and Nacos consistency
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StrategyApiTest extends BaseIntegrationTest {

    private static String rateLimiterStrategyId;
    private static String circuitBreakerStrategyId;
    private static String retryStrategyId;

    @BeforeAll
    static void setup() {
        // Static setup
    }

    @Test
    @Order(0)
    void test00_CleanData() throws Exception {
        cleanAllData();
        System.out.println("[PASS] Data cleaned before tests");
    }
    @Order(1)
    void test01_CreateRateLimiterStrategy_Success() throws Exception {
        ObjectNode strategy = objectMapper.createObjectNode();
        strategy.put("strategyName", "global-rate-limiter");
        strategy.put("strategyType", "RATE_LIMITER");
        strategy.put("scope", "GLOBAL");
        strategy.put("priority", 100);
        strategy.put("enabled", true);
        strategy.put("description", "Global rate limiter");

        ObjectNode config = strategy.putObject("config");
        config.put("qps", 100);
        config.put("burstCapacity", 200);
        config.put("keyResolver", "ip");

        MvcResult result = mockMvc.perform(post("/api/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(strategy.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        rateLimiterStrategyId = data.get("strategyId").asText();
        assertNotNull(rateLimiterStrategyId);

        System.out.println("[PASS] Rate limiter strategy created: " + rateLimiterStrategyId);
    }

    @Test
    @Order(2)
    void test02_CreateCircuitBreakerStrategy_Success() throws Exception {
        ObjectNode strategy = objectMapper.createObjectNode();
        strategy.put("strategyName", "global-circuit-breaker");
        strategy.put("strategyType", "CIRCUIT_BREAKER");
        strategy.put("scope", "GLOBAL");
        strategy.put("priority", 90);
        strategy.put("enabled", true);

        ObjectNode config = strategy.putObject("config");
        config.put("failureRateThreshold", 50);
        config.put("slowCallRateThreshold", 80);
        config.put("waitDurationInOpenState", 10000);
        config.put("minimumNumberOfCalls", 5);

        MvcResult result = mockMvc.perform(post("/api/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(strategy.toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        circuitBreakerStrategyId = data.get("strategyId").asText();
        assertNotNull(circuitBreakerStrategyId);

        System.out.println("[PASS] Circuit breaker strategy created: " + circuitBreakerStrategyId);
    }

    @Test
    @Order(3)
    void test03_CreateRetryStrategy_Success() throws Exception {
        ObjectNode strategy = objectMapper.createObjectNode();
        strategy.put("strategyName", "global-retry");
        strategy.put("strategyType", "RETRY");
        strategy.put("scope", "GLOBAL");
        strategy.put("priority", 80);
        strategy.put("enabled", true);

        ObjectNode config = strategy.putObject("config");
        config.put("maxAttempts", 3);
        config.put("waitDuration", 500);

        MvcResult result = mockMvc.perform(post("/api/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(strategy.toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        retryStrategyId = data.get("strategyId").asText();
        assertNotNull(retryStrategyId);

        System.out.println("[PASS] Retry strategy created: " + retryStrategyId);
    }

    @Test
    @Order(4)
    void test04_VerifyStrategiesInDatabase() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/strategies"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(3, data.size(), "Should have 3 strategies");

        System.out.println("[PASS] All strategies verified in database");
    }

    @Test
    @Order(5)
    void test05_VerifyStrategiesInNacos() throws Exception {
        // Verify strategies index
        String indexConfig = getNacosConfig("config.gateway.metadata.strategies-index");
        JsonNode index = objectMapper.readTree(indexConfig);
        assertEquals(3, index.size(), "Should have 3 strategies in index");

        // Verify each strategy config
        assertTrue(nacosConfigExists("config.gateway.strategy-" + rateLimiterStrategyId));
        assertTrue(nacosConfigExists("config.gateway.strategy-" + circuitBreakerStrategyId));
        assertTrue(nacosConfigExists("config.gateway.strategy-" + retryStrategyId));

        System.out.println("[PASS] All strategies verified in Nacos");
    }

    @Test
    @Order(6)
    void test06_GetStrategyByType() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/strategies/type/RATE_LIMITER"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray());
        assertEquals(1, data.size());
        assertEquals("RATE_LIMITER", data.get(0).get("strategyType").asText());

        System.out.println("[PASS] Get strategy by type works");
    }

    @Test
    @Order(7)
    void test07_GetGlobalStrategies() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/strategies/global"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(3, data.size(), "Should have 3 global strategies");

        System.out.println("[PASS] Get global strategies works");
    }

    @Test
    @Order(8)
    void test08_DisableStrategy() throws Exception {
        mockMvc.perform(post("/api/strategies/" + rateLimiterStrategyId + "/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/strategies/" + rateLimiterStrategyId))
                .andReturn();
        JsonNode data = extractData(result);
        assertFalse(data.get("enabled").asBoolean());

        System.out.println("[PASS] Strategy disabled successfully");
    }

    @Test
    @Order(9)
    void test09_EnableStrategy() throws Exception {
        mockMvc.perform(post("/api/strategies/" + rateLimiterStrategyId + "/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/strategies/" + rateLimiterStrategyId))
                .andReturn();
        JsonNode data = extractData(result);
        assertTrue(data.get("enabled").asBoolean());

        System.out.println("[PASS] Strategy enabled successfully");
    }

    @Test
    @Order(10)
    void test10_UpdateStrategy() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/api/strategies/" + rateLimiterStrategyId))
                .andReturn();
        JsonNode current = extractData(getResult);

        ObjectNode updated = current.deepCopy();
        updated.put("priority", 200);
        updated.get("config").get("qps").asInt();
        ((ObjectNode) updated.get("config")).put("qps", 200);

        mockMvc.perform(put("/api/strategies/" + rateLimiterStrategyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updated.toString()))
                .andExpect(status().isOk());

        MvcResult verifyResult = mockMvc.perform(get("/api/strategies/" + rateLimiterStrategyId))
                .andReturn();
        JsonNode verified = extractData(verifyResult);
        assertEquals(200, verified.get("priority").asInt());
        assertEquals(200, verified.get("config").get("qps").asInt());

        System.out.println("[PASS] Strategy updated successfully");
    }

    @Test
    @Order(11)
    void test11_DeleteStrategy() throws Exception {
        // Delete one strategy
        mockMvc.perform(delete("/api/strategies/" + retryStrategyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // Verify deleted from database
        MvcResult result = mockMvc.perform(get("/api/strategies"))
                .andReturn();
        JsonNode data = extractData(result);
        assertEquals(2, data.size());

        // Verify deleted from Nacos
        assertFalse(nacosConfigExists("config.gateway.strategy-" + retryStrategyId));

        // Verify removed from index
        String indexConfig = getNacosConfig("config.gateway.metadata.strategies-index");
        JsonNode index = objectMapper.readTree(indexConfig);
        assertEquals(2, index.size());

        System.out.println("[PASS] Strategy deleted successfully");
    }

    @Test
    @Order(12)
    void test12_CreateAllStrategyTypes() throws Exception {
        String[] types = {"TIMEOUT", "CORS", "IP_FILTER", "AUTH", "ACCESS_LOG", "HEADER_OP"};

        for (String type : types) {
            ObjectNode strategy = objectMapper.createObjectNode();
            strategy.put("strategyName", "test-" + type.toLowerCase());
            strategy.put("strategyType", type);
            strategy.put("scope", "GLOBAL");
            strategy.put("priority", 50);
            strategy.put("enabled", true);
            strategy.putObject("config");

            mockMvc.perform(post("/api/strategies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(strategy.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        // Verify all created
        MvcResult result = mockMvc.perform(get("/api/strategies"))
                .andReturn();
        JsonNode data = extractData(result);
        assertEquals(8, data.size(), "Should have 8 strategies (2 original + 6 new)");

        System.out.println("[PASS] All strategy types created successfully");

        // Cleanup
        for (String type : types) {
            result = mockMvc.perform(get("/api/strategies/type/" + type)).andReturn();
            JsonNode strategies = extractData(result);
            if (strategies.isArray() && strategies.size() > 0) {
                String id = strategies.get(0).get("strategyId").asText();
                mockMvc.perform(delete("/api/strategies/" + id));
            }
        }
    }

    @Test
    @Order(13)
    void test13_CreateRouteBoundStrategy() throws Exception {
        // First create a route
        ObjectNode route = objectMapper.createObjectNode();
        route.put("id", "strategy-test-route");
        route.put("uri", "static://test-service");
        route.put("mode", "SINGLE");
        route.put("serviceId", "test-service");
        route.put("order", 0);
        route.putArray("predicates").addObject().put("name", "Path").putObject("args").put("pattern", "/test/**");

        MvcResult routeResult = mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(route.toString()))
                .andReturn();
        String routeId = extractData(routeResult).get("id").asText();

        // Create route-bound strategy
        ObjectNode strategy = objectMapper.createObjectNode();
        strategy.put("strategyName", "route-rate-limiter");
        strategy.put("strategyType", "RATE_LIMITER");
        strategy.put("scope", "ROUTE");
        strategy.put("routeId", routeId);
        strategy.put("priority", 100);
        strategy.put("enabled", true);
        strategy.putObject("config").put("qps", 50);

        MvcResult result = mockMvc.perform(post("/api/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(strategy.toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals("ROUTE", data.get("scope").asText());
        assertEquals(routeId, data.get("routeId").asText());

        // Verify get strategies for route
        MvcResult routeStrategies = mockMvc.perform(get("/api/strategies/route/" + routeId))
                .andReturn();
        JsonNode routeStrategiesData = extractData(routeStrategies);
        assertEquals(1, routeStrategiesData.size());

        System.out.println("[PASS] Route-bound strategy created successfully");

        // Cleanup
        mockMvc.perform(delete("/api/strategies/" + data.get("strategyId").asText()));
        mockMvc.perform(delete("/api/routes/" + routeId));
    }

    @Test
    @Order(14)
    void test14_Cleanup() throws Exception {
        // Clean remaining strategies
        mockMvc.perform(delete("/api/strategies/" + rateLimiterStrategyId));
        mockMvc.perform(delete("/api/strategies/" + circuitBreakerStrategyId));

        // Verify all cleaned
        MvcResult result = mockMvc.perform(get("/api/strategies")).andReturn();
        JsonNode data = extractData(result);
        assertEquals(0, data.size(), "All strategies should be deleted");

        System.out.println("[PASS] Cleanup completed");
    }
}