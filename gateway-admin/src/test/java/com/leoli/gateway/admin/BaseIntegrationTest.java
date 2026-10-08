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

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Base integration test class with common utilities.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// SecurityConfig requires hasAuthority("ADMIN") for mutating /api/** calls and
// authenticated() for reads, so an unauthenticated MockMvc request gets 401/403.
// authorities (not roles) because the matchers test the bare "ADMIN" authority.
@WithMockUser(username = "test-admin", authorities = {"ADMIN"})
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ConfigCenterService configCenterService;

    @Autowired
    protected KubernetesClusterRepository clusterRepository;

    protected static final String TEST_CLUSTER_NAME = "test-cluster";
    protected static Long testClusterId;

    /**
     * Seed the cluster row that instance creation requires.
     * <p>
     * GatewayInstanceService.createInstance rejects a missing or unknown clusterId. The import
     * API is not usable from tests: it is gated behind the K8S_INTEGRATION license feature,
     * which the test profile's BASIC fallback license does not grant. A real cluster is not
     * needed either - deployToKubernetes failures are caught and recorded on the instance.
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
     * Create a gateway instance in its own namespace and return its business instanceId
     * (the 12-char string, not the numeric primary key).
     * <p>
     * Service/route/strategy APIs resolve their Nacos namespace via
     * {@code findByInstanceId(...)}, so they need the business id; passing the numeric id
     * silently resolves to a null namespace and the request is rejected.
     */
    protected String createTestInstance(String name) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("instanceName", name);
        request.put("specType", "small");
        request.put("replicas", 1);
        request.put("namespace", "test-ns-" + UUID.randomUUID().toString().substring(0, 8));
        request.put("clusterId", ensureTestCluster());

        MvcResult result = mockMvc.perform(post("/api/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andReturn();

        return extractData(result).get("instanceId").asText();
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
     * Get config from ConfigCenter (Nacos/Consul).
     * Uses ConfigCenterService instead of direct HTTP call.
     */
    protected String getConfigFromCenter(String dataId) {
        try {
            return configCenterService.getConfig(dataId, String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verify config exists in ConfigCenter (Nacos/Consul).
     * Uses ConfigCenterService instead of direct HTTP call.
     */
    protected boolean configCenterExists(String dataId) {
        try {
            return configCenterService.configExists(dataId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Clean all test data
     */
    protected void cleanAllData() throws Exception {
        // Delete all routes
        MvcResult routesResult = mockMvc.perform(get("/api/routes"))
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
        MvcResult servicesResult = mockMvc.perform(get("/api/services"))
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
        MvcResult strategiesResult = mockMvc.perform(get("/api/strategies"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode strategies = extractData(strategiesResult);
        if (strategies.isArray()) {
            for (JsonNode strategy : strategies) {
                String id = strategy.get("strategyId").asText();
                mockMvc.perform(delete("/api/strategies/" + id));
            }
        }
    }
}