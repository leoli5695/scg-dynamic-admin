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
 * Route API Integration Tests with Nacos Namespace Isolation
 * All operations use instanceId to ensure tenant isolation via Nacos namespace.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RouteNamespaceTest extends NamespaceIntegrationTest {

    private static String createdRouteId;
    private static String createdRouteName = "test-route-namespace";
    private static String testServiceId;

    @BeforeAll
    static void setupInstance() throws Exception {
        // Create a dedicated test instance with its own namespace
        NamespaceIntegrationTest test = new NamespaceIntegrationTest() {};
        testInstanceId = test.createTestInstance("test-route-instance");
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
    @DisplayName("Create route with instanceId - should isolate to namespace")
    void test01_CreateRoute_WithNamespace() throws Exception {
        // First create a service for the route
        testServiceId = createTestService("test-svc-namespace", 9100);

        ObjectNode route = objectMapper.createObjectNode();
        route.put("routeName", createdRouteName);
        route.put("uri", "static://" + testServiceId);
        route.put("mode", "SINGLE");
        route.put("serviceId", testServiceId);
        route.put("order", 0);

        ArrayNode predicates = route.putArray("predicates");
        ObjectNode predicate = predicates.addObject();
        predicate.put("name", "Path");
        predicate.putObject("args").put("pattern", "/api/namespace-test/**");

        MvcResult result = mockMvc.perform(post("/api/routes")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(route.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        createdRouteId = data.get("id").asText();
        assertNotNull(createdRouteId, "Route ID should not be null");
        assertEquals(createdRouteName, data.get("routeName").asText());

        System.out.println("[PASS] Route created with UUID: " + createdRouteId + " in namespace: " + TEST_NAMESPACE);
    }

    @Test
    @Order(2)
    @DisplayName("Get routes filtered by instanceId - should only return routes in namespace")
    void test02_GetRoutes_ByInstanceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/routes")
                        .param("instanceId", testInstanceId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = extractData(result);
        assertTrue(data.isArray(), "Response should be an array");

        boolean found = false;
        for (JsonNode route : data) {
            if (createdRouteName.equals(route.get("routeName").asText())) {
                found = true;
                assertEquals(createdRouteId, route.get("id").asText());
                assertEquals("static://" + testServiceId, route.get("uri").asText());
                assertTrue(route.get("enabled").asBoolean());
                break;
            }
        }
        assertTrue(found, "Created route should be in list for this instance");

        System.out.println("[PASS] Routes filtered correctly by instanceId");
    }

    @Test
    @Order(3)
    @DisplayName("Get route by ID - should return route details")
    void test03_GetRouteById() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/routes/" + createdRouteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals(createdRouteName, data.get("routeName").asText());
        assertEquals(createdRouteId, data.get("id").asText());
        assertEquals(testServiceId, data.get("serviceId").asText());

        System.out.println("[PASS] Get route by ID works");
    }

    @Test
    @Order(4)
    @DisplayName("Disable route - should update route status")
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
    @DisplayName("Enable route - should update route status")
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
    @DisplayName("Update route - should persist changes")
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
    @DisplayName("Create multi-service route - should support weight-based routing")
    void test07_CreateMultiServiceRoute() throws Exception {
        String service1Id = createTestService("test-svc-multi-1", 9101);
        String service2Id = createTestService("test-svc-multi-2", 9102);

        ObjectNode route = objectMapper.createObjectNode();
        route.put("routeName", "multi-service-namespace");
        route.put("uri", "static://" + service1Id);
        route.put("mode", "MULTI");
        route.put("order", 0);

        ArrayNode predicates = route.putArray("predicates");
        predicates.addObject().put("name", "Path").putObject("args").put("pattern", "/api/multi/**");

        ArrayNode services = route.putArray("services");
        ObjectNode svc1 = services.addObject();
        svc1.put("serviceId", service1Id);
        svc1.put("serviceName", "test-svc-multi-1");
        svc1.put("weight", 30);
        svc1.put("version", "v1");
        svc1.put("enabled", true);

        ObjectNode svc2 = services.addObject();
        svc2.put("serviceId", service2Id);
        svc2.put("serviceName", "test-svc-multi-2");
        svc2.put("weight", 70);
        svc2.put("version", "v2");
        svc2.put("enabled", true);

        MvcResult result = mockMvc.perform(post("/api/routes")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(route.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        assertEquals("MULTI", data.get("mode").asText(), "Should be MULTI mode");

        System.out.println("[PASS] Multi-service route created with namespace isolation");

        // Cleanup
        String routeId = data.get("id").asText();
        mockMvc.perform(delete("/api/routes/" + routeId));
        mockMvc.perform(delete("/api/services/test-svc-multi-1"));
        mockMvc.perform(delete("/api/services/test-svc-multi-2"));
    }

    @Test
    @Order(8)
    @DisplayName("Delete route - should remove from database and config center")
    void test08_DeleteRoute() throws Exception {
        mockMvc.perform(delete("/api/routes/" + createdRouteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/routes/" + createdRouteId))
                .andExpect(status().isNotFound());

        System.out.println("[PASS] Route deleted successfully");
    }

    @Test
    @Order(9)
    @DisplayName("Create route without instanceId - should work with default instance")
    void test09_CreateRoute_WithoutInstanceId() throws Exception {
        ObjectNode route = objectMapper.createObjectNode();
        route.put("routeName", "test-no-instance");
        route.put("uri", "static://default-service");
        route.put("mode", "SINGLE");
        route.put("serviceId", "default-service");
        route.put("order", 0);

        ArrayNode predicates = route.putArray("predicates");
        predicates.addObject().put("name", "Path").putObject("args").put("pattern", "/api/no-instance/**");

        MvcResult result = mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(route.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = extractData(result);
        String routeId = data.get("id").asText();

        // Cleanup
        mockMvc.perform(delete("/api/routes/" + routeId));

        System.out.println("[PASS] Route created without instanceId (uses default)");
    }

    @Test
    @Order(10)
    @DisplayName("Verify namespace isolation - routes from different instances are separate")
    void test10_NamespaceIsolation() throws Exception {
        // Create another instance
        NamespaceIntegrationTest test = new NamespaceIntegrationTest() {};
        String otherInstanceId = test.createTestInstance("other-instance");

        try {
            // Create route in other instance
            ObjectNode route = objectMapper.createObjectNode();
            route.put("routeName", "other-route");
            route.put("uri", "static://other-service");
            route.put("mode", "SINGLE");
            route.put("serviceId", "other-service");
            route.put("order", 0);

            ArrayNode predicates = route.putArray("predicates");
            predicates.addObject().put("name", "Path").putObject("args").put("pattern", "/api/other/**");

            mockMvc.perform(post("/api/routes")
                            .param("instanceId", otherInstanceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(route.toString()))
                    .andExpect(status().isOk());

            // Verify our instance doesn't see the other route
            MvcResult result = mockMvc.perform(get("/api/routes")
                            .param("instanceId", testInstanceId))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode data = extractData(result);
            boolean foundOtherRoute = false;
            for (JsonNode r : data) {
                if ("other-route".equals(r.get("routeName").asText())) {
                    foundOtherRoute = true;
                    break;
                }
            }
            assertFalse(foundOtherRoute, "Should not see routes from other namespace");

            System.out.println("[PASS] Namespace isolation verified - instances have separate routes");
        } finally {
            test.deleteTestInstance(otherInstanceId);
        }
    }

    private String createTestService(String name, int port) throws Exception {
        ObjectNode service = objectMapper.createObjectNode();
        service.put("name", name);
        service.put("description", "Test service for namespace tests");
        service.put("loadBalancer", "round-robin");

        ArrayNode instances = service.putArray("instances");
        ObjectNode instance = instances.addObject();
        instance.put("ip", "127.0.0.1");
        instance.put("port", port);
        instance.put("weight", 1);
        instance.put("enabled", true);

        MvcResult result = mockMvc.perform(post("/api/services")
                        .param("instanceId", testInstanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(service.toString()))
                .andReturn();

        return extractData(result).get("serviceId").asText();
    }
}
