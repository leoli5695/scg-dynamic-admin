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
 * Instance Management API Integration Tests with Nacos Namespace Isolation
 * Tests instance CRUD, lifecycle management, and namespace configuration.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class InstanceManagementTest extends NamespaceIntegrationTest {

    private static String createdInstanceId;
    private static String instanceName = "test-instance-mgmt";

    @BeforeEach
    void setUp() throws Exception {
        // Clean up any existing test instances
        cleanAllData();
    }

    @Test
    @Order(1)
    @DisplayName("Create gateway instance - should create with dedicated namespace")
    void test01_CreateInstance() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("instanceName", instanceName);
        request.put("specType", "small");
        request.put("replicas", 1);
        request.put("namespace", TEST_NAMESPACE);
        request.put("description", "Test instance for management tests");

        MvcResult result = mockMvc.perform(post("/api/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        createdInstanceId = data.get("id").asText();
        assertNotNull(createdInstanceId, "Instance ID should be generated");
        assertEquals(instanceName, data.get("instanceName").asText());
        assertEquals("small", data.get("specType").asText());
        assertEquals(1, data.get("replicas").asInt());

        System.out.println("[PASS] Instance created: " + createdInstanceId + " with namespace: " + TEST_NAMESPACE);
    }

    @Test
    @Order(2)
    @DisplayName("Get all instances - should return list of instances")
    void test02_GetAllInstances() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Response should be an array");
        assertTrue(data.size() > 0, "Should have at least one instance");

        System.out.println("[PASS] Retrieved " + data.size() + " instances");
    }

    @Test
    @Order(3)
    @DisplayName("Get instance by ID - should return instance details")
    void test03_GetInstanceById() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/instances/" + createdInstanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(instanceName, data.get("instanceName").asText());
        assertEquals("small", data.get("specType").asText());

        System.out.println("[PASS] Get instance by ID works");
    }

    @Test
    @Order(4)
    @DisplayName("Get instance by UUID - should return instance by instanceId")
    void test04_GetInstanceByUuid() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/api/instances/" + createdInstanceId))
                .andReturn();
        JsonNode instanceData = extractData(getResult);
        String uuid = instanceData.get("instanceId").asText();

        MvcResult result = mockMvc.perform(get("/api/instances/by-instance-id/" + uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(uuid, data.get("instanceId").asText());

        System.out.println("[PASS] Get instance by UUID works");
    }

    @Test
    @Order(5)
    @DisplayName("Get available specs - should return spec options")
    void test05_GetAvailableSpecs() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/instances/specs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Response should be an array");
        assertTrue(data.size() > 0, "Should have at least one spec");

        System.out.println("[PASS] Available specs retrieved: " + data.size());
    }

    @Test
    @Order(6)
    @DisplayName("Update instance replicas - should scale instance")
    void test06_UpdateInstanceReplicas() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("replicas", 2);

        MvcResult result = mockMvc.perform(put("/api/instances/" + createdInstanceId + "/replicas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(2, data.get("replicas").asInt());

        System.out.println("[PASS] Instance replicas updated to 2");
    }

    @Test
    @Order(7)
    @DisplayName("Update instance spec - should change resource allocation")
    void test07_UpdateInstanceSpec() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("specType", "medium");
        request.put("cpuCores", 2);
        request.put("memoryMB", 2048);

        MvcResult result = mockMvc.perform(put("/api/instances/" + createdInstanceId + "/spec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals("medium", data.get("specType").asText());

        System.out.println("[PASS] Instance spec updated to medium");
    }

    @Test
    @Order(8)
    @DisplayName("Update instance image - should change container image")
    void test08_UpdateInstanceImage() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("image", "gateway:latest");

        MvcResult result = mockMvc.perform(put("/api/instances/" + createdInstanceId + "/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals("gateway:latest", data.get("image").asText());

        System.out.println("[PASS] Instance image updated");
    }

    @Test
    @Order(9)
    @DisplayName("Refresh instance status - should sync with actual state")
    void test09_RefreshInstanceStatus() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/instances/" + createdInstanceId + "/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Refreshed status should not be null");

        System.out.println("[PASS] Instance status refreshed");
    }

    @Test
    @Order(10)
    @DisplayName("Get instance pods - should return pod information")
    void test10_GetInstancePods() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/instances/" + createdInstanceId + "/pods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Pods information should not be null");

        System.out.println("[PASS] Instance pods retrieved");
    }

    @Test
    @Order(11)
    @DisplayName("Start instance - should start the gateway instance")
    void test11_StartInstance() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/instances/" + createdInstanceId + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Start response should not be null");

        System.out.println("[PASS] Instance started");
    }

    @Test
    @Order(12)
    @DisplayName("Stop instance - should stop the gateway instance")
    void test12_StopInstance() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/instances/" + createdInstanceId + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Stop response should not be null");

        System.out.println("[PASS] Instance stopped");
    }

    @Test
    @Order(13)
    @DisplayName("Delete instance - should remove instance and cleanup resources")
    void test13_DeleteInstance() throws Exception {
        mockMvc.perform(delete("/api/instances/" + createdInstanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/instances/" + createdInstanceId))
                .andExpect(status().isNotFound());

        System.out.println("[PASS] Instance deleted successfully");
    }

    @Test
    @Order(14)
    @DisplayName("Create multiple instances - should support multi-tenancy")
    void test14_CreateMultipleInstances() throws Exception {
        String[] instanceNames = {"tenant-a", "tenant-b", "tenant-c"};
        String[] namespaces = {"ns-tenant-a", "ns-tenant-b", "ns-tenant-c"};
        String[] instanceIds = new String[3];

        try {
            // Create multiple instances
            for (int i = 0; i < instanceNames.length; i++) {
                ObjectNode request = objectMapper.createObjectNode();
                request.put("instanceName", instanceNames[i]);
                request.put("specType", "small");
                request.put("replicas", 1);
                request.put("namespace", namespaces[i]);

                MvcResult result = mockMvc.perform(post("/api/instances")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request.toString()))
                        .andExpect(status().isOk())
                        .andReturn();

                instanceIds[i] = extractData(result).get("id").asText();
            }

            // Verify all instances exist
            MvcResult result = mockMvc.perform(get("/api/instances"))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode data = extractData(result);
            int initialCount = data.size();
            assertTrue(initialCount >= 3, "Should have at least 3 instances");

            System.out.println("[PASS] Created " + instanceNames.length + " tenant instances");

        } finally {
            // Cleanup
            for (String id : instanceIds) {
                if (id != null) {
                    try {
                        mockMvc.perform(delete("/api/instances/" + id));
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                }
            }
        }
    }
}
