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
 * Auth Policy API Integration Tests with Nacos Namespace Isolation
 * Tests JWT, API Key, Basic Auth, and other authentication policies.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AuthPolicyNamespaceTest extends NamespaceIntegrationTest {

    private static String createdPolicyId;
    private static String policyName = "test-auth-policy-namespace";

    @BeforeAll
    static void setupInstance() throws Exception {
        NamespaceIntegrationTest test = new NamespaceIntegrationTest() {};
        testInstanceId = test.createTestInstance("test-auth-instance");
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
    @DisplayName("Create JWT auth policy - should isolate to namespace")
    void test01_CreateJwtAuthPolicy() throws Exception {
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("policyName", policyName);
        policy.put("authType", "JWT");
        policy.put("enabled", true);

        ObjectNode config = policy.putObject("config");
        config.put("secretKey", "test-secret-key-for-jwt-signing");
        config.put("algorithm", "HS256");
        config.put("tokenPrefix", "Bearer ");
        config.put("headerName", "Authorization");
        config.put("expirationCheck", true);

        MvcResult result = mockMvc.perform(post("/api/auth/policies")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policy.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        createdPolicyId = data.get("policyId").asText();
        assertNotNull(createdPolicyId, "Policy ID should be generated");
        assertEquals(policyName, data.get("policyName").asText());

        System.out.println("[PASS] JWT auth policy created: " + createdPolicyId);
    }

    @Test
    @Order(2)
    @DisplayName("Get auth policies filtered by instanceId - should only return policies in namespace")
    void test02_GetAuthPolicies_ByInstanceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/policies")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Response should be an array");

        boolean found = false;
        for (JsonNode p : data) {
            if (policyName.equals(p.get("policyName").asText())) {
                found = true;
                assertEquals("JWT", p.get("authType").asText());
                break;
            }
        }
        assertTrue(found, "Created policy should be in list for this instance");

        System.out.println("[PASS] Auth policies filtered correctly by instanceId");
    }

    @Test
    @Order(3)
    @DisplayName("Get auth policy by ID - should return policy details")
    void test03_GetAuthPolicyById() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/policies/" + createdPolicyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(policyName, data.get("policyName").asText());
        assertEquals("JWT", data.get("authType").asText());

        System.out.println("[PASS] Get auth policy by ID works");
    }

    @Test
    @Order(4)
    @DisplayName("Get auth policies by type - should filter by auth type")
    void test04_GetAuthPolicies_ByType() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/policies/type/JWT")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Response should be an array");
        assertTrue(data.size() > 0, "Should have at least one JWT policy");

        System.out.println("[PASS] Get auth policies by type works");
    }

    @Test
    @Order(5)
    @DisplayName("Disable auth policy - should update policy status")
    void test05_DisableAuthPolicy() throws Exception {
        mockMvc.perform(post("/api/auth/policies/" + createdPolicyId + "/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/auth/policies/" + createdPolicyId))
                .andReturn();
        JsonNode data = extractData(result);
        assertFalse(data.get("enabled").asBoolean(), "Policy should be disabled");

        System.out.println("[PASS] Auth policy disabled successfully");
    }

    @Test
    @Order(6)
    @DisplayName("Enable auth policy - should update policy status")
    void test06_EnableAuthPolicy() throws Exception {
        mockMvc.perform(post("/api/auth/policies/" + createdPolicyId + "/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/auth/policies/" + createdPolicyId))
                .andReturn();
        JsonNode data = extractData(result);
        assertTrue(data.get("enabled").asBoolean(), "Policy should be enabled");

        System.out.println("[PASS] Auth policy enabled successfully");
    }

    @Test
    @Order(7)
    @DisplayName("Update auth policy - should persist changes")
    void test07_UpdateAuthPolicy() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/api/auth/policies/" + createdPolicyId))
                .andReturn();
        JsonNode currentPolicy = extractData(getResult);

        ObjectNode updatedPolicy = currentPolicy.deepCopy();
        ObjectNode config = updatedPolicy.get("config").deepCopy();
        config.put("tokenPrefix", "Token ");
        updatedPolicy.set("config", config);

        mockMvc.perform(put("/api/auth/policies/" + createdPolicyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedPolicy.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult verifyResult = mockMvc.perform(get("/api/auth/policies/" + createdPolicyId))
                .andReturn();
        JsonNode verified = extractData(verifyResult);
        assertEquals("Token ", verified.get("config").get("tokenPrefix").asText());

        System.out.println("[PASS] Auth policy updated successfully");
    }

    @Test
    @Order(8)
    @DisplayName("Get usage example - should return configuration example")
    void test08_GetUsageExample() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/policies/" + createdPolicyId + "/usage-example"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Usage example should not be null");

        System.out.println("[PASS] Usage example retrieved");
    }

    @Test
    @Order(9)
    @DisplayName("Create API Key auth policy - should support multiple auth types")
    void test09_CreateApiKeyAuthPolicy() throws Exception {
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("policyName", "test-api-key");
        policy.put("authType", "API_KEY");
        policy.put("enabled", true);

        ObjectNode config = policy.putObject("config");
        config.put("headerName", "X-API-Key");
        config.put("queryParamName", "api_key");
        config.put("keyLength", 32);

        MvcResult result = mockMvc.perform(post("/api/auth/policies")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policy.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        String apiKeyPolicyId = data.get("policyId").asText();
        assertEquals("API_KEY", data.get("authType").asText());

        // Cleanup
        mockMvc.perform(delete("/api/auth/policies/" + apiKeyPolicyId));

        System.out.println("[PASS] API Key auth policy created");
    }

    @Test
    @Order(10)
    @DisplayName("Bind auth policy to route - should create binding")
    void test10_BindPolicyToRoute() throws Exception {
        // First create a route
        String routeId = createTestRoute("test-route-for-auth");

        // Create binding
        ObjectNode binding = objectMapper.createObjectNode();
        binding.put("policyId", createdPolicyId);
        binding.put("routeId", routeId);
        binding.put("priority", 1);

        MvcResult result = mockMvc.perform(post("/api/auth/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(binding.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        String bindingId = data.get("bindingId").asText();
        assertNotNull(bindingId, "Binding ID should be generated");

        System.out.println("[PASS] Auth policy bound to route: " + bindingId);

        // Cleanup binding
        mockMvc.perform(delete("/api/auth/bindings/" + bindingId));
        mockMvc.perform(delete("/api/routes/" + routeId));
    }

    @Test
    @Order(11)
    @DisplayName("Get bindings for policy - should return route associations")
    void test11_GetBindingsForPolicy() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/bindings/policy/" + createdPolicyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Bindings should not be null");

        System.out.println("[PASS] Bindings for policy retrieved");
    }

    @Test
    @Order(12)
    @DisplayName("Get binding count for policy - should return count")
    void test12_GetBindingCount() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/policies/" + createdPolicyId + "/bindings/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Binding count should not be null");

        System.out.println("[PASS] Binding count retrieved");
    }

    @Test
    @Order(13)
    @DisplayName("Delete auth policy - should remove from database and config center")
    void test13_DeleteAuthPolicy() throws Exception {
        mockMvc.perform(delete("/api/auth/policies/" + createdPolicyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/auth/policies/" + createdPolicyId))
                .andExpect(status().isNotFound());

        System.out.println("[PASS] Auth policy deleted successfully");
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
