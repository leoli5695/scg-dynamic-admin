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
 * Service API Integration Tests
 * Tests: Create, Read, Update, Delete, Instance Management
 * Verifies: Database and Nacos consistency
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ServiceApiTest extends BaseIntegrationTest {

    private static String createdServiceId;
    private static String createdServiceName = "test-service-api";

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
    void test01_CreateService_Success() throws Exception {
        ObjectNode service = objectMapper.createObjectNode();
        service.put("name", createdServiceName);
        service.put("description", "Test service for API testing");
        service.put("loadBalancer", "round-robin");

        ArrayNode instances = service.putArray("instances");
        ObjectNode instance = instances.addObject();
        instance.put("ip", "127.0.0.1");
        instance.put("port", 9001);
        instance.put("weight", 1);
        instance.put("enabled", true);

        MvcResult result = mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(service.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        createdServiceId = data.get("serviceId").asText();
        assertNotNull(createdServiceId, "Service ID should not be null");
        assertEquals(createdServiceName, data.get("name").asText());

        System.out.println("[PASS] Service created with ID: " + createdServiceId);
    }

    @Test
    @Order(2)
    void test02_CreateService_VerifyDatabase() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/services"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        boolean found = false;
        for (JsonNode service : data) {
            if (createdServiceName.equals(service.get("name").asText())) {
                found = true;
                assertEquals(1, service.get("instances").size());
                break;
            }
        }
        assertTrue(found, "Created service should be in database");

        System.out.println("[PASS] Service verified in database");
    }

    @Test
    @Order(3)
    void test03_CreateService_VerifyNacos() throws Exception {
        String serviceConfigId = "config.gateway.service-" + createdServiceId;
        assertTrue(nacosConfigExists(serviceConfigId), "Service config should exist in Nacos");

        String config = getNacosConfig(serviceConfigId);
        JsonNode configJson = objectMapper.readTree(config);

        assertEquals(createdServiceName, configJson.get("name").asText());
        assertEquals("round-robin", configJson.get("loadBalancer").asText());
        assertEquals(1, configJson.get("instances").size());

        System.out.println("[PASS] Service config verified in Nacos");
    }

    @Test
    @Order(4)
    void test04_VerifyServicesIndex() throws Exception {
        String indexConfig = getNacosConfig("config.gateway.metadata.services-index");
        JsonNode index = objectMapper.readTree(indexConfig);

        assertTrue(index.isArray(), "Services index should be an array");
        boolean found = false;
        for (JsonNode id : index) {
            if (createdServiceId.equals(id.asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Service ID should be in services index");

        System.out.println("[PASS] Services index verified in Nacos");
    }

    @Test
    @Order(5)
    void test05_GetServiceByName() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/services/" + createdServiceName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(createdServiceName, data.get("name").asText());
        assertEquals(createdServiceId, data.get("serviceId").asText());

        System.out.println("[PASS] Get service by name works");
    }

    @Test
    @Order(6)
    void test06_AddInstance() throws Exception {
        ObjectNode instance = objectMapper.createObjectNode();
        instance.put("ip", "127.0.0.1");
        instance.put("port", 9002);
        instance.put("weight", 2);
        instance.put("enabled", true);

        MvcResult result = mockMvc.perform(post("/api/services/" + createdServiceName + "/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instance.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(2, data.get("instances").size(), "Should have 2 instances");

        // Verify in Nacos
        String serviceConfigId = "config.gateway.service-" + createdServiceId;
        String config = getNacosConfig(serviceConfigId);
        JsonNode configJson = objectMapper.readTree(config);
        assertEquals(2, configJson.get("instances").size(), "Nacos should have 2 instances");

        System.out.println("[PASS] Instance added successfully");
    }

    @Test
    @Order(7)
    void test07_DisableInstance() throws Exception {
        // Disable instance 127.0.0.1:9002
        mockMvc.perform(put("/api/services/" + createdServiceName + "/instances/127.0.0.1:9002/status")
                        .param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // Verify in database
        MvcResult result = mockMvc.perform(get("/api/services/" + createdServiceName))
                .andReturn();
        JsonNode data = extractData(result);

        boolean foundDisabled = false;
        for (JsonNode inst : data.get("instances")) {
            if (inst.get("port").asInt() == 9002) {
                assertFalse(inst.get("enabled").asBoolean(), "Instance should be disabled");
                foundDisabled = true;
                break;
            }
        }
        assertTrue(foundDisabled, "Instance 9002 should exist");

        // Verify in Nacos
        String serviceConfigId = "config.gateway.service-" + createdServiceId;
        String config = getNacosConfig(serviceConfigId);
        JsonNode configJson = objectMapper.readTree(config);
        for (JsonNode inst : configJson.get("instances")) {
            if (inst.get("port").asInt() == 9002) {
                assertFalse(inst.get("enabled").asBoolean(), "Nacos instance should be disabled");
                break;
            }
        }

        System.out.println("[PASS] Instance disabled successfully");
    }

    @Test
    @Order(8)
    void test08_EnableInstance() throws Exception {
        mockMvc.perform(put("/api/services/" + createdServiceName + "/instances/127.0.0.1:9002/status")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/services/" + createdServiceName))
                .andReturn();
        JsonNode data = extractData(result);

        for (JsonNode inst : data.get("instances")) {
            if (inst.get("port").asInt() == 9002) {
                assertTrue(inst.get("enabled").asBoolean(), "Instance should be enabled");
                break;
            }
        }

        System.out.println("[PASS] Instance enabled successfully");
    }

    @Test
    @Order(9)
    void test09_RemoveInstance() throws Exception {
        mockMvc.perform(delete("/api/services/" + createdServiceName + "/instances/127.0.0.1:9002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/services/" + createdServiceName))
                .andReturn();
        JsonNode data = extractData(result);

        assertEquals(1, data.get("instances").size(), "Should have 1 instance after removal");
        assertEquals(9001, data.get("instances").get(0).get("port").asInt(), "Remaining instance should be 9001");

        // Verify in Nacos
        String serviceConfigId = "config.gateway.service-" + createdServiceId;
        String config = getNacosConfig(serviceConfigId);
        JsonNode configJson = objectMapper.readTree(config);
        assertEquals(1, configJson.get("instances").size());

        System.out.println("[PASS] Instance removed successfully");
    }

    @Test
    @Order(10)
    void test10_UpdateService() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/api/services/" + createdServiceName))
                .andReturn();
        JsonNode currentService = extractData(getResult);

        ObjectNode updatedService = currentService.deepCopy();
        updatedService.put("description", "Updated description");
        updatedService.put("loadBalancer", "weighted");

        mockMvc.perform(put("/api/services/" + createdServiceName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedService.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult verifyResult = mockMvc.perform(get("/api/services/" + createdServiceName))
                .andReturn();
        JsonNode verified = extractData(verifyResult);
        assertEquals("Updated description", verified.get("description").asText());
        assertEquals("weighted", verified.get("loadBalancer").asText());

        System.out.println("[PASS] Service updated successfully");
    }

    @Test
    @Order(11)
    void test11_DeleteService() throws Exception {
        mockMvc.perform(delete("/api/services/" + createdServiceName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // Verify deleted from database
        MvcResult result = mockMvc.perform(get("/api/services"))
                .andReturn();
        JsonNode services = extractData(result);
        for (JsonNode service : services) {
            assertNotEquals(createdServiceName, service.get("name").asText());
        }

        // Verify deleted from Nacos
        String serviceConfigId = "config.gateway.service-" + createdServiceId;
        assertFalse(nacosConfigExists(serviceConfigId), "Service config should be deleted from Nacos");

        // Verify removed from index
        String indexConfig = getNacosConfig("config.gateway.metadata.services-index");
        JsonNode index = objectMapper.readTree(indexConfig);
        for (JsonNode id : index) {
            assertNotEquals(createdServiceId, id.asText());
        }

        System.out.println("[PASS] Service deleted successfully");
    }

    @Test
    @Order(12)
    void test12_CreateServiceWithNoInstances() throws Exception {
        ObjectNode service = objectMapper.createObjectNode();
        service.put("name", "service-no-instances");
        service.put("description", "Service with no instances");
        service.put("loadBalancer", "round-robin");
        service.putArray("instances"); // Empty instances

        MvcResult result = mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(service.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.get("instances").isEmpty(), "Instances should be empty");

        // Cleanup
        mockMvc.perform(delete("/api/services/service-no-instances"));

        System.out.println("[PASS] Service with no instances created");
    }

    @Test
    @Order(13)
    void test13_DuplicateServiceName_ShouldFail() throws Exception {
        // Create first service
        ObjectNode service = objectMapper.createObjectNode();
        service.put("name", "duplicate-test");
        service.put("loadBalancer", "round-robin");
        service.putArray("instances");

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(service.toString()))
                .andExpect(status().isOk());

        // Try to create duplicate
        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(service.toString()))
                .andExpect(status().isBadRequest());

        // Cleanup
        mockMvc.perform(delete("/api/services/duplicate-test"));

        System.out.println("[PASS] Duplicate service name rejected");
    }
}