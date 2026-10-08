package com.leoli.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
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
// See BaseIntegrationTest: SecurityConfig enforces hasAuthority("ADMIN") on mutating
// /api/** calls, so the mock principal must carry that exact authority.
@WithMockUser(username = "test-admin", authorities = {"ADMIN"})
public abstract class NamespaceIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ConfigCenterService configCenterService;

    @Autowired
    protected KubernetesClusterRepository clusterRepository;

    // Test namespace and instance - each test class gets its own namespace for isolation
    protected static final String TEST_NAMESPACE = "test-namespace-" + UUID.randomUUID().toString().substring(0, 8);
    protected static final String TEST_CLUSTER_NAME = "test-cluster";
    protected static String testInstanceId;
    protected static Long testClusterId;
    /** Namespace actually used by the most recent {@link #createTestInstance(String)} call. */
    protected static String lastUsedNamespace = TEST_NAMESPACE;

    /**
     * Seed the cluster row that instance creation requires.
     * <p>
     * GatewayInstanceService.createInstance rejects a request whose clusterId is missing or
     * unknown. The import API cannot be used here: it is gated behind the K8S_INTEGRATION
     * license feature, which the test profile's BASIC fallback license does not grant.
     * Deployment to a real cluster is not needed either - deployToKubernetes failures are
     * caught and recorded on the instance, so creation still succeeds.
     */
    protected Long ensureTestCluster() {
        if (testClusterId != null) {
            return testClusterId;
        }
        KubernetesCluster cluster = clusterRepository.findByClusterName(TEST_CLUSTER_NAME)
                .orElseGet(() -> {
                    KubernetesCluster c = new KubernetesCluster();
                    c.setClusterName(TEST_CLUSTER_NAME);
                    c.setServerUrl("https://127.0.0.1:6443");
                    c.setConnectionStatus("UNKNOWN");
                    c.setEnabled(true);
                    return clusterRepository.save(c);
                });
        testClusterId = cluster.getId();
        return testClusterId;
    }

    /**
     * Create a test gateway instance with dedicated namespace
     */
    protected String createTestInstance(String name) throws Exception {
        // A fresh namespace per call: the Spring context (and therefore the create-drop
        // database) is shared across every test class in a surefire run, so reusing the
        // JVM-wide TEST_NAMESPACE makes the second class collide on the namespace
        // uniqueness check in GatewayInstanceService.createInstance.
        String namespace = TEST_NAMESPACE + "-" + UUID.randomUUID().toString().substring(0, 8);
        lastUsedNamespace = namespace;

        ObjectNode request = objectMapper.createObjectNode();
        request.put("instanceName", name);
        request.put("specType", "small");
        request.put("replicas", 1);
        request.put("namespace", namespace);
        request.put("clusterId", ensureTestCluster());

        MvcResult result = mockMvc.perform(post("/api/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        return data.get("id").asText();
    }

    /**
     * Delete a test instance by ID. Best effort: cleanup runs from @AfterAll, which is
     * outside the @WithMockUser security context, so the request may well be rejected.
     * Asserting here would turn a harmless cleanup miss into a test failure (AssertionError
     * is an Error, so the catch below would not even contain it).
     */
    protected void deleteTestInstance(String id) throws Exception {
        if (id == null) {
            return;
        }
        try {
            mockMvc.perform(delete("/api/instances/" + id));
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
