package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RouteDebugControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RouteRepository routes = mock(RouteRepository.class);
    private final GatewayInstanceService instances = mock(GatewayInstanceService.class);
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> receivedDebugSignature = new AtomicReference<>();
    private RouteDebugController controller;
    private HttpServer gateway;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/hello", exchange -> {
            receivedDebugSignature.set(exchange.getRequestHeaders().getFirst("X-Gateway-Debug-Trace"));
            received.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("X-Trace-Id", "debug-trace");
            byte[] body = "debug response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        gateway.createContext("/unauthorized", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
        gateway.createContext("/large", exchange -> {
            byte[] body = "x".repeat(300000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try { exchange.getResponseBody().write(body); } catch (java.io.IOException ignored) { }
            exchange.close();
        });
        gateway.start();
        var route = new RouteEntity(); route.setInstanceId("instance-a");
        when(routes.findById("route-a")).thenReturn(Optional.of(route));
        when(instances.getAccessUrl("instance-a")).thenReturn("http://127.0.0.1:" + gateway.getAddress().getPort());
        controller = new RouteDebugController(routes, instances, mock(com.leoli.gateway.admin.service.RouteDebugAuthService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() { controller.close(); gateway.stop(0); }

    private String execute(RouteDebugController.DebugRequest request) throws Exception {
        var pending = mvc.perform(post("/api/routes/route-a/debug").param("instanceId", "instance-a")
                        .header("Authorization", "Bearer console-token-must-not-be-forwarded")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void forwardsExplicitCredentialsBodyAndQueryThroughGatewayAndStreamsResponseMetadata() throws Exception {
        String stream = execute(new RouteDebugController.DebugRequest("POST", "/hello?a=1",
                Map.of("Authorization", "Bearer gateway-token", "Content-Type", "application/json"), "{\"hello\":true}", 3));
        assertEquals("POST /hello?a=1 Bearer gateway-token {\"hello\":true}", received.get());
        assertTrue(stream.contains("event:response"));
        assertTrue(stream.contains("debug-trace"));
        assertTrue(stream.contains("debug response"));
        assertTrue(stream.contains("event:complete"));
        assertFalse(stream.contains("console-token"));
    }

    @Test
    void exposesGateway401AsDebugResultRatherThanConsoleAuthenticationFailure() throws Exception {
        String stream = execute(new RouteDebugController.DebugRequest("GET", "/unauthorized", Map.of(), "", 3));
        assertTrue(stream.contains("\"status\":401"));
        assertTrue(stream.contains("event:complete"));
    }

    @Test
    void signsDebugRequestsForOnlyTheSelectedInstanceAndPath() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"internalToken","test-shared-secret");
        execute(new RouteDebugController.DebugRequest("GET","/hello?a=1",Map.of("X-Gateway-Debug-Trace","forged"),"",3));
        String[] parts = receivedDebugSignature.get().split(":");
        assertEquals(3,parts.length);
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test-shared-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        assertEquals(parts[2],java.util.HexFormat.of().formatHex(mac.doFinal(
                ("instance-a\nGET\n/hello?a=1\n"+parts[0]+"\n"+parts[1]).getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void selectedPolicyIsResolvedAndItsCredentialsAreAddedInsideAdmin() throws Exception {
        var policies = mock(com.leoli.gateway.admin.service.AuthPolicyService.class);
        var policy = new com.leoli.gateway.admin.model.AuthPolicyDefinition();
        policy.setAuthType("BASIC"); policy.setBasicUsername("demo"); policy.setBasicPassword("test-password");
        when(policies.getPolicy("instance-a", "basic-policy")).thenReturn(policy);
        controller.close();
        controller = new RouteDebugController(routes, instances, new com.leoli.gateway.admin.service.RouteDebugAuthService(policies));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String stream = execute(new RouteDebugController.DebugRequest("POST", "/hello", Map.of(), "test-body", 3,
                "basic-policy", null, null));
        String expected = Base64.getEncoder().encodeToString("demo:test-password".getBytes(StandardCharsets.UTF_8));
        assertEquals("POST /hello Basic " + expected + " test-body", received.get());
        assertTrue(stream.contains("event:complete"));
        verify(policies).getPolicy("instance-a", "basic-policy");
    }

    @Test
    void forwardsStreamChunksBeforeUpstreamCompletes() throws Exception {
        var finish = new java.util.concurrent.CountDownLatch(1);
        gateway.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { finish.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var pending = mvc.perform(post("/api/routes/route-a/debug").param("instanceId", "instance-a")
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"GET\",\"path\":\"/stream\",\"timeoutSeconds\":3}"))
                .andExpect(request().asyncStarted()).andReturn();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!pending.getResponse().getContentAsString().contains("data: first") && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(pending.getResponse().getContentAsString().contains("data: first"));
            assertFalse(pending.getResponse().getContentAsString().contains("event:complete"));
        } finally { finish.countDown(); }
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
    }

    @Test
    void boundsOutputAndReportsTruncation() throws Exception {
        String stream = execute(new RouteDebugController.DebugRequest("GET", "/large", Map.of(), "", 3));
        assertTrue(stream.contains("\"truncated\":true"));
        assertTrue(stream.length() < 270000);
    }

    @Test
    void rejectsRouteFromAnotherInstanceBeforeResolvingAnyTarget() throws Exception {
        mvc.perform(post("/api/routes/route-a/debug").param("instanceId", "instance-b")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"GET\",\"path\":\"/hello\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(instances);
    }

    @Test
    void rejectsTargetOverridesAndTransportHeadersButSupportsVirtualHostRouting() {
        for (String path : new String[]{"http://metadata/", "//metadata/", "/hello#fragment", "/\\metadata"}) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                    RouteDebugController.buildRequest("http://gateway:8080", new RouteDebugController.DebugRequest("GET", path, Map.of(), "", 3)));
        }
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> RouteDebugController.buildRequest(
                "http://gateway:8080", new RouteDebugController.DebugRequest("GET", "/", Map.of("Content-Length", "2"), "", 3)));
        var request = RouteDebugController.buildRequest("http://gateway:8080", new RouteDebugController.DebugRequest("GET", "/", Map.of("Host", "api.example.com"), "", 3));
        assertEquals("api.example.com", request.header("Host"));
    }
}
