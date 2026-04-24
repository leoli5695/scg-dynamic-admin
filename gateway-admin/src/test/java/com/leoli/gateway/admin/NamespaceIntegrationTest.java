package com.leoli.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.admin.center.ConfigCenterService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Base integration test class with Nacos namespace support for tenant isolation.
 * All tests use instanceId to ensure proper namespace isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class NamespaceIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ConfigCenterService configCenterService;

    // Test namespace and instance - each test class gets its own namespace for isolation
    protected static final String TEST_NAMESPACE = "test-namespace-" + UUID.randomUUID().toString().substring(0, 8);
    protected static String testInstanceId;

    /**
     * Create a test gateway instance with dedicated namespace
     */
    protected String createTestInstance(String name) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("instanceName", name);
        request.put("specType", "small");
        request.put("replicas", 1);
        request.put("namespace", TEST_NAMESPACE);

        MvcResult result = mockMvc.perform(post("/api/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        return data.get("id").asText();
    }

    /**
     * Delete a test instance by ID
     */
    protected void deleteTestInstance(String id) throws Exception {
        try {
            mockMvc.perform(delete("/api/instances/" + id))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    /**
     * Extract data from API response
     */
    protected JsonNode extractData(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        return root.get("data");
    }

    /**
     * Extract code from API response
     */
    protected int extractCode(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        return root.get("code").asInt();
    }

    /**
     * Extract message from API response
     */
    protected String extractMessage(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        return root.get("message").asText();
    }

    /**
     * Get config from ConfigCenter with namespace
     */
    protected String getConfigFromCenter(String dataId, String namespace) {
        try {
            return configCenterService.getConfig(dataId, String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verify config exists in ConfigCenter
     */
    protected boolean configCenterExists(String dataId) {
        try {
            return configCenterService.configExists(dataId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Clean all test data for current instance
     */
    protected void cleanAllData() throws Exception {
        if (testInstanceId == null) return;

        // Delete all routes
        MvcResult routesResult = mockMvc.perform(get("/api/routes")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode routes = extractData(routesResult);
        if (routes.isArray()) {
            for (JsonNode route : routes) {
                String id = route.get("id").asText();
                mockMvc.perform(delete("/api/routes/" + id));
            }
        }

        // Delete all services
        MvcResult servicesResult = mockMvc.perform(get("/api/services")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode services = extractData(servicesResult);
        if (services.isArray()) {
            for (JsonNode service : services) {
                String name = service.get("name").asText();
                mockMvc.perform(delete("/api/services/" + name));
            }
        }

        // Delete all strategies
        MvcResult strategiesResult = mockMvc.perform(get("/api/strategies")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode strategies = extractData(strategiesResult);
        if (strategies.isArray()) {
            for (JsonNode strategy : strategies) {
                String id = strategy.get("strategyId").asText();
                mockMvc.perform(delete("/api/strategies/" + id));
            }
        }

        // Delete all auth policies
        MvcResult authResult = mockMvc.perform(get("/api/auth/policies")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode policies = extractData(authResult);
        if (policies.isArray()) {
            for (JsonNode policy : policies) {
                String id = policy.get("policyId").asText();
                mockMvc.perform(delete("/api/auth/policies/" + id));
            }
        }
    }
}
