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
 * Route API Integration Tests
 * Tests: Create, Read, Update, Delete, Enable/Disable
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RouteApiTest extends BaseIntegrationTest {

    private static String createdRouteId;  // UUID
    private static String createdRouteName = "test-route-api";

    @Test
    @Order(1)
    void test01_CreateRoute_Success() throws Exception {
        ObjectNode route = objectMapper.createObjectNode();
        route.put("id", createdRouteName);
        route.put("uri", "static://test-service-id");
        route.put("mode", "SINGLE");
        route.put("serviceId", "test-service-id");
        route.put("order", 0);

        ArrayNode predicates = route.putArray("predicates");
        ObjectNode predicate = predicates.addObject();
        predicate.put("name", "Path");
        predicate.putObject("args").put("pattern", "/api/test/**");

        MvcResult result = mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(route.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        createdRouteId = data.get("id").asText();
        assertNotNull(createdRouteId, "Route ID should not be null");
        assertEquals(createdRouteName, data.get("routeName").asText());

        System.out.println("[PASS] Route created with UUID: " + createdRouteId);
    }

    @Test
    @Order(2)
    void test02_VerifyRouteInList() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/routes"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        boolean found = false;
        for (JsonNode route : data) {
            if (createdRouteName.equals(route.get("routeName").asText())) {
                found = true;
                assertEquals(createdRouteId, route.get("id").asText());
                assertEquals("static://test-service-id", route.get("uri").asText());
                assertTrue(route.get("enabled").asBoolean());
                break;
            }
        }
        assertTrue(found, "Created route should be in list");

        System.out.println("[PASS] Route verified in list");
    }

    @Test
    @Order(3)
    void test03_GetRouteById() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/routes/" + createdRouteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(createdRouteName, data.get("routeName").asText());
        assertEquals(createdRouteId, data.get("id").asText());

        System.out.println("[PASS] Get route by ID works");
    }

    @Test
    @Order(4)
    void test04_DisableRoute() throws Exception {
        mockMvc.perform(post("/api/routes/" + createdRouteId + "/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/routes/" + createdRouteId))
                .andReturn();
        JsonNode data = extractData(result);
        assertFalse(data.get("enabled").asBoolean(), "Route should be disabled");

        System.out.println("[PASS] Route disabled successfully");
    }

    @Test
    @Order(5)
    void test05_EnableRoute() throws Exception {
        mockMvc.perform(post("/api/routes/" + createdRouteId + "/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(get("/api/routes/" + createdRouteId))
                .andReturn();
        JsonNode data = extractData(result);
        assertTrue(data.get("enabled").asBoolean(), "Route should be enabled");

        System.out.println("[PASS] Route enabled successfully");
    }

    @Test
    @Order(6)
    void test06_UpdateRoute() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/api/routes/" + createdRouteId))
                .andReturn();
        JsonNode currentRoute = extractData(getResult);

        ObjectNode updatedRoute = currentRoute.deepCopy();
        updatedRoute.put("order", 100);

        mockMvc.perform(put("/api/routes/" + createdRouteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedRoute.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult verifyResult = mockMvc.perform(get("/api/routes/" + createdRouteId))
                .andReturn();
        JsonNode verified = extractData(verifyResult);
        assertEquals(100, verified.get("order").asInt());

        System.out.println("[PASS] Route updated successfully");
    }

    @Test
    @Order(7)
    void test07_DeleteRoute() throws Exception {
        mockMvc.perform(delete("/api/routes/" + createdRouteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/routes/" + createdRouteId))
                .andExpect(status().isNotFound());

        System.out.println("[PASS] Route deleted successfully");
    }

    @Test
    @Order(8)
    void test08_CreateMultiServiceRoute() throws Exception {
        // Create services
        String service1Id = createTestService("test-svc-1", 9001);
        String service2Id = createTestService("test-svc-2", 9002);

        ObjectNode route = objectMapper.createObjectNode();
        route.put("id", "multi-service-route");
        route.put("uri", "static://" + service1Id);
        route.put("mode", "MULTI");
        route.put("order", 0);

        ArrayNode predicates = route.putArray("predicates");
        predicates.addObject().put("name", "Path").putObject("args").put("pattern", "/api/multi/**");

        ArrayNode services = route.putArray("services");
        ObjectNode svc1 = services.addObject();
        svc1.put("serviceId", service1Id);
        svc1.put("serviceName", "test-svc-1");
        svc1.put("weight", 30);
        svc1.put("version", "v1");
        svc1.put("enabled", true);

        ObjectNode svc2 = services.addObject();
        svc2.put("serviceId", service2Id);
        svc2.put("serviceName", "test-svc-2");
        svc2.put("weight", 70);
        svc2.put("version", "v2");
        svc2.put("enabled", true);

        MvcResult result = mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(route.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals("MULTI", data.get("mode").asText(), "Should be MULTI mode");

        System.out.println("[PASS] Multi-service route created");

        // Cleanup
        String routeId = data.get("id").asText();
        mockMvc.perform(delete("/api/routes/" + routeId));
        mockMvc.perform(delete("/api/services/test-svc-1"));
        mockMvc.perform(delete("/api/services/test-svc-2"));
    }

    private String createTestService(String name, int port) throws Exception {
        ObjectNode service = objectMapper.createObjectNode();
        service.put("name", name);
        service.put("description", "Test service");
        service.put("loadBalancer", "round-robin");

        ArrayNode instances = service.putArray("instances");
        ObjectNode instance = instances.addObject();
        instance.put("ip", "127.0.0.1");
        instance.put("port", port);
        instance.put("weight", 1);
        instance.put("enabled", true);

        MvcResult result = mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(service.toString()))
                .andReturn();

        return extractData(result).get("serviceId").asText();
    }
}