package com.leoli.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Service API Integration Tests with Nacos Namespace Isolation
 * All operations use instanceId to ensure tenant isolation via Nacos namespace.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ServiceNamespaceTest extends NamespaceIntegrationTest {

    private static String testServiceName = "test-service-namespace";

    @BeforeAll
    static void setupInstance() throws Exception {
        NamespaceIntegrationTest test = new NamespaceIntegrationTest() {};
        testInstanceId = test.createTestInstance("test-service-instance");
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
    @DisplayName("Create service with instanceId - should isolate to namespace")
    void test01_CreateService_WithNamespace() throws Exception {
        ObjectNode service = objectMapper.createObjectNode();
        service.put("name", testServiceName);
        service.put("description", "Test service with namespace isolation");
        service.put("loadBalancer", "round-robin");

        ArrayNode instances = service.putArray("instances");
        ObjectNode instance = instances.addObject();
        instance.put("ip", "127.0.0.1");
        instance.put("port", 9200);
        instance.put("weight", 1);
        instance.put("enabled", true);
        ObjectNode metadata = instance.putObject("metadata");
        metadata.put("v1", "1.0");

        MvcResult result = mockMvc.perform(post("/api/services")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(service.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(testServiceName, data.get("name").asText());
        assertNotNull(data.get("serviceId"), "Service ID should be generated");

        System.out.println("[PASS] Service created with namespace: " + TEST_NAMESPACE);
    }

    @Test
    @Order(2)
    @DisplayName("Get services filtered by instanceId - should only return services in namespace")
    void test02_GetServices_ByInstanceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/services")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Response should be an array");

        boolean found = false;
        for (JsonNode svc : data) {
            if (testServiceName.equals(svc.get("name").asText())) {
                found = true;
                assertEquals("round-robin", svc.get("loadBalancer").asText());
                assertTrue(svc.get("instances").isArray());
                break;
            }
        }
        assertTrue(found, "Created service should be in list for this instance");

        System.out.println("[PASS] Services filtered correctly by instanceId");
    }

    @Test
    @Order(3)
    @DisplayName("Get service by name - should return service details")
    void test03_GetServiceByName() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/services/" + testServiceName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(testServiceName, data.get("name").asText());
        assertEquals("Test service with namespace isolation", data.get("description").asText());
        assertTrue(data.get("instances").isArray());

        System.out.println("[PASS] Get service by name works");
    }

    @Test
    @Order(4)
    @DisplayName("Update service - should persist changes")
    void test04_UpdateService() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/api/services/" + testServiceName))
                .andReturn();
        JsonNode currentService = extractData(getResult);

        ObjectNode updatedService = currentService.deepCopy();
        updatedService.put("description", "Updated description");
        updatedService.put("loadBalancer", "weighted");

        mockMvc.perform(put("/api/services/" + testServiceName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedService.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult verifyResult = mockMvc.perform(get("/api/services/" + testServiceName))
                .andReturn();
        JsonNode verified = extractData(verifyResult);
        assertEquals("Updated description", verified.get("description").asText());
        assertEquals("weighted", verified.get("loadBalancer").asText());

        System.out.println("[PASS] Service updated successfully");
    }

    @Test
    @Order(5)
    @DisplayName("Add instance to service - should update service instances")
    void test05_AddInstanceToService() throws Exception {
        ObjectNode newInstance = objectMapper.createObjectNode();
        newInstance.put("ip", "192.168.1.100");
        newInstance.put("port", 9201);
        newInstance.put("weight", 2);
        newInstance.put("enabled", true);

        mockMvc.perform(post("/api/services/" + testServiceName + "/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newInstance.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/services/" + testServiceName))
                .andReturn();
        JsonNode data = extractData(result);
        assertTrue(data.get("instances").size() >= 2, "Should have at least 2 instances");

        System.out.println("[PASS] Instance added to service");
    }

    @Test
    @Order(6)
    @DisplayName("Update instance status - should enable/disable instance")
    void test06_UpdateInstanceStatus() throws Exception {
        // Get first instance
        MvcResult getResult = mockMvc.perform(get("/api/services/" + testServiceName))
                .andReturn();
        JsonNode service = extractData(getResult);
        String instanceId = service.get("instances").get(0).get("id").asText();

        // Disable instance
        ObjectNode status = objectMapper.createObjectNode();
        status.put("enabled", false);
        status.put("healthy", false);

        mockMvc.perform(put("/api/services/" + testServiceName + "/instances/" + instanceId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(status.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult verifyResult = mockMvc.perform(get("/api/services/" + testServiceName))
                .andReturn();
        JsonNode verified = extractData(verifyResult);
        JsonNode updatedInstance = verified.get("instances").get(0);
        assertFalse(updatedInstance.get("enabled").asBoolean(), "Instance should be disabled");

        System.out.println("[PASS] Instance status updated");
    }

    @Test
    @Order(7)
    @DisplayName("Remove instance from service - should delete instance")
    void test07_RemoveInstanceFromService() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/api/services/" + testServiceName))
                .andReturn();
        JsonNode service = extractData(getResult);
        String instanceId = service.get("instances").get(0).get("id").asText();
        int initialSize = service.get("instances").size();

        mockMvc.perform(delete("/api/services/" + testServiceName + "/instances/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult verifyResult = mockMvc.perform(get("/api/services/" + testServiceName))
                .andReturn();
        JsonNode verified = extractData(verifyResult);
        assertEquals(initialSize - 1, verified.get("instances").size(), "Should have one less instance");

        System.out.println("[PASS] Instance removed from service");
    }

    @Test
    @Order(8)
    @DisplayName("Check service usage - should return route associations")
    void test08_CheckServiceUsage() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/services/" + testServiceName + "/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Usage data should not be null");

        System.out.println("[PASS] Service usage check works");
    }

    @Test
    @Order(9)
    @DisplayName("Delete service - should remove from database and config center")
    void test09_DeleteService() throws Exception {
        mockMvc.perform(delete("/api/services/" + testServiceName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/services/" + testServiceName))
                .andExpect(status().isNotFound());

        System.out.println("[PASS] Service deleted successfully");
    }

    @Test
    @Order(10)
    @DisplayName("Create service without instances - should handle empty instances")
    void test10_CreateServiceWithoutInstances() throws Exception {
        ObjectNode service = objectMapper.createObjectNode();
        service.put("name", "test-no-instances");
        service.put("description", "Service without instances");
        service.put("loadBalancer", "round-robin");
        service.putArray("instances"); // Empty array

        MvcResult result = mockMvc.perform(post("/api/services")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(service.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals("test-no-instances", data.get("name").asText());

        // Cleanup
        mockMvc.perform(delete("/api/services/test-no-instances"));

        System.out.println("[PASS] Service created without instances");
    }

    @Test
    @Order(11)
    @DisplayName("Verify namespace isolation - services from different instances are separate")
    void test11_NamespaceIsolation() throws Exception {
        NamespaceIntegrationTest test = new NamespaceIntegrationTest() {};
        String otherInstanceId = test.createTestInstance("other-service-instance");

        try {
            // Create service in other instance
            ObjectNode service = objectMapper.createObjectNode();
            service.put("name", "other-service");
            service.put("description", "Other service");
            service.put("loadBalancer", "round-robin");
            service.putArray("instances");

            mockMvc.perform(post("/api/services")
                            .param("instanceId", otherInstanceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(service.toString()))
                    .andExpect(status().isOk());

            // Verify our instance doesn't see the other service
            MvcResult result = mockMvc.perform(get("/api/services")
                            .param("instanceId", testInstanceId))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode data = extractData(result);
            boolean foundOtherService = false;
            for (JsonNode svc : data) {
                if ("other-service".equals(svc.get("name").asText())) {
                    foundOtherService = true;
                    break;
                }
            }
            assertFalse(foundOtherService, "Should not see services from other namespace");

            System.out.println("[PASS] Namespace isolation verified - instances have separate services");
        } finally {
            test.deleteTestInstance(otherInstanceId);
        }
    }

    @Test
    @Order(12)
    @DisplayName("Get Nacos discovery services - should return discovered services")
    void test12_GetNacosDiscoveryServices() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/services/nacos-discovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertNotNull(data, "Nacos discovery should return data");

        System.out.println("[PASS] Nacos discovery services retrieved");
    }
}
