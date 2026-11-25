package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.dto.ProviderHealthDTO;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Probe logic exercised against a real local HTTP endpoint (no WireMock dependency
 * in the repo; follows the StressTestExecutionTest HttpServer precedent).
 */
class ProviderHealthProbeServiceTest {

    private static final String KEY = "sk-test-SECRETKEY123";

    private final ServiceService serviceService = mock(ServiceService.class);
    private final ProviderCredentialService credentials = mock(ProviderCredentialService.class);
    private ProviderHealthProbeService probe;
    private HttpServer server;
    private ExecutorService serverThreads;

    @BeforeEach
    void setup() throws Exception {
        when(credentials.resolveKey(anyString())).thenReturn(KEY);
        probe = new ProviderHealthProbeService(serviceService, credentials, 500, 3_600_000, 4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverThreads);
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ServiceDefinition ai(String name) {
        ServiceDefinition d = new ServiceDefinition();
        d.setName(name);
        d.setCategory("AI");
        d.setProviderType("openai");
        d.setBaseUrl(baseUrl());
        d.setCredentialRef("cred-" + name);
        return d;
    }

    private void targets(ServiceDefinition... services) {
        when(serviceService.getAllServices()).thenReturn(List.of(services));
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void openAiCompatibleProbesModelsWithBearer() {
        List<String> seen = new CopyOnWriteArrayList<>();
        List<String> auth = new CopyOnWriteArrayList<>();
        server.createContext("/", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, bytes("{\"data\":[]}"));
        });
        server.start();
        targets(ai("svc"));

        probe.probeAll();

        ProviderHealthDTO h = probe.getHealth("svc");
        assertEquals(ProviderHealthDTO.HEALTHY, h.getStatus());
        assertNotNull(h.getLastProbeAt());
        assertEquals("HTTP 200", h.getMessage());
        assertEquals(List.of("GET /models"), seen);
        assertEquals(List.of("Bearer " + KEY), auth);
    }

    @Test
    void trailingSlashBaseUrlDoesNotDoubleSlashModels() {
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/", ex -> {
            seen.add(ex.getRequestURI().getPath());
            respond(ex, 200, bytes("{}"));
        });
        server.start();
        ServiceDefinition d = ai("svc");
        d.setBaseUrl(baseUrl() + "/");
        targets(d);

        probe.probeAll();

        assertEquals(ProviderHealthDTO.HEALTHY, probe.getHealth("svc").getStatus());
        assertEquals(List.of("/models"), seen);
    }

    @Test
    void authFailureIsUnhealthyAndNeverLeaksKeyEvenIfBodyEchoesIt() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            // Provider echoes the full key in the error body; it must never surface.
            respond(ex, 401, bytes("{\"error\":{\"message\":\"Invalid key " + KEY + "\"}}"));
        });
        server.start();
        targets(ai("svc"));

        probe.probeAll();

        ProviderHealthDTO h = probe.getHealth("svc");
        assertEquals(ProviderHealthDTO.UNHEALTHY, h.getStatus());
        assertTrue(h.getMessage().contains("401"), h.getMessage());
        assertFalse(h.getMessage().contains(KEY), "key leaked: " + h.getMessage());
        assertEquals(2, calls.get(), "expected exactly one retry");
    }

    @Test
    void models404FallsBackToBaseUrl() {
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/models", ex -> {
            seen.add(ex.getRequestURI().getPath());
            respond(ex, 404, bytes("not found"));
        });
        server.createContext("/", ex -> {
            seen.add(ex.getRequestURI().getPath());
            respond(ex, 200, bytes("ok"));
        });
        server.start();
        targets(ai("svc"));

        probe.probeAll();

        assertEquals(ProviderHealthDTO.HEALTHY, probe.getHealth("svc").getStatus());
        assertEquals(List.of("/models", "/"), seen);
    }

    @Test
    void nonCompatibleProtocolUsesHeadOnBaseUrl() {
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            respond(ex, 200, new byte[0]);
        });
        server.start();
        ServiceDefinition d = ai("svc");
        d.setProtocol("ANTHROPIC");
        targets(d);

        probe.probeAll();

        assertEquals(ProviderHealthDTO.HEALTHY, probe.getHealth("svc").getStatus());
        assertEquals(List.of("HEAD /"), seen);
    }

    @Test
    void head405FallsBackToGet() {
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/", ex -> {
            seen.add(ex.getRequestMethod());
            if ("HEAD".equals(ex.getRequestMethod())) {
                respond(ex, 405, new byte[0]);
            } else {
                respond(ex, 200, bytes("ok"));
            }
        });
        server.start();
        ServiceDefinition d = ai("svc");
        d.setProtocol("CUSTOM");
        targets(d);

        probe.probeAll();

        assertEquals(ProviderHealthDTO.HEALTHY, probe.getHealth("svc").getStatus());
        assertEquals(List.of("HEAD", "GET"), seen);
    }

    @Test
    void retriesOnceThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", ex -> {
            if (calls.incrementAndGet() == 1) {
                respond(ex, 500, bytes("boom"));
            } else {
                respond(ex, 200, bytes("{}"));
            }
        });
        server.start();
        targets(ai("svc"));

        probe.probeAll();

        assertEquals(ProviderHealthDTO.HEALTHY, probe.getHealth("svc").getStatus());
        assertEquals(2, calls.get());
    }

    @Test
    void retryExhaustedStaysUnhealthy() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            respond(ex, 503, bytes("down"));
        });
        server.start();
        targets(ai("svc"));

        probe.probeAll();

        ProviderHealthDTO h = probe.getHealth("svc");
        assertEquals(ProviderHealthDTO.UNHEALTHY, h.getStatus());
        assertTrue(h.getMessage().contains("503"), h.getMessage());
        assertEquals(2, calls.get());
    }

    @Test
    void readTimeoutIsUnhealthy() {
        server.createContext("/", ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, bytes("{}"));
        });
        server.start();
        targets(ai("svc"));

        probe.probeAll();

        ProviderHealthDTO h = probe.getHealth("svc");
        assertEquals(ProviderHealthDTO.UNHEALTHY, h.getStatus());
        assertTrue(h.getMessage().contains("超时"), h.getMessage());
    }

    @Test
    void unreachableEndpointIsUnhealthy() throws Exception {
        // Bind then close a plain socket so the port is genuinely unbound. The exact
        // network wording (refused vs timeout) is OS/firewall dependent; the contract
        // is simply that an unreachable provider is reported UNHEALTHY.
        java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        int deadPort = ss.getLocalPort();
        ss.close();
        ServiceDefinition d = ai("svc");
        d.setBaseUrl("http://127.0.0.1:" + deadPort);
        targets(d);

        probe.probeAll();

        ProviderHealthDTO h = probe.getHealth("svc");
        assertEquals(ProviderHealthDTO.UNHEALTHY, h.getStatus());
        assertNotNull(h.getMessage());
        assertFalse(h.getMessage().contains(KEY));
    }

    @Test
    void redirectIsNotFollowedAndReportedUnhealthy() {
        AtomicInteger redirectTargetHits = new AtomicInteger();
        server.createContext("/models", ex -> {
            ex.getResponseHeaders().set("Location", baseUrl() + "/redirect-target");
            respond(ex, 302, new byte[0]);
        });
        server.createContext("/redirect-target", ex -> {
            redirectTargetHits.incrementAndGet();
            respond(ex, 200, bytes("{}"));
        });
        server.start();
        targets(ai("svc"));

        probe.probeAll();

        ProviderHealthDTO h = probe.getHealth("svc");
        assertEquals(ProviderHealthDTO.UNHEALTHY, h.getStatus());
        assertTrue(h.getMessage().contains("302"), h.getMessage());
        assertEquals(0, redirectTargetHits.get(), "redirect must not be followed");
    }

    @Test
    void missingCredentialIsUnhealthyWithoutHttpCall() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            respond(ex, 200, bytes("{}"));
        });
        server.start();
        ServiceDefinition d = ai("svc");
        d.setCredentialRef(null);
        targets(d);

        probe.probeAll();

        ProviderHealthDTO h = probe.getHealth("svc");
        assertEquals(ProviderHealthDTO.UNHEALTHY, h.getStatus());
        assertTrue(h.getMessage().contains("凭证"), h.getMessage());
        assertEquals(0, calls.get());
    }

    @Test
    void disabledCredentialIsUnhealthy() {
        when(credentials.resolveKey("cred-svc"))
                .thenThrow(new IllegalArgumentException("Credential is disabled: cred-svc"));
        targets(ai("svc"));

        probe.probeAll();

        ProviderHealthDTO h = probe.getHealth("svc");
        assertEquals(ProviderHealthDTO.UNHEALTHY, h.getStatus());
        assertTrue(h.getMessage().contains("凭证不可用"), h.getMessage());
        assertFalse(h.getMessage().contains(KEY));
    }

    @Test
    void unknownBeforeFirstProbe() {
        ProviderHealthDTO h = probe.getHealth("never-probed");
        assertEquals(ProviderHealthDTO.UNKNOWN, h.getStatus());
        assertNull(h.getLastProbeAt());
        assertNull(h.getMessage());
    }

    @Test
    void normalServicesAreNotProbed() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            respond(ex, 200, bytes("{}"));
        });
        server.start();
        ServiceDefinition normal = new ServiceDefinition();
        normal.setName("normal");
        normal.setCategory("NORMAL");
        targets(normal, ai("svc"));

        probe.probeAll();

        assertEquals(ProviderHealthDTO.HEALTHY, probe.getHealth("svc").getStatus());
        assertEquals(ProviderHealthDTO.UNKNOWN, probe.getHealth("normal").getStatus());
        assertEquals(1, calls.get());
    }

    @Test
    void concurrencyIsBounded() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        server.createContext("/", ex -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            respond(ex, 200, bytes("{}"));
        });
        server.start();
        ServiceDefinition[] many = new ServiceDefinition[8];
        for (int i = 0; i < many.length; i++) {
            many[i] = ai("svc" + i);
        }
        targets(many);

        probe.probeAll();

        assertTrue(peak.get() <= 4, "peak concurrency " + peak.get() + " exceeded 4");
        for (int i = 0; i < many.length; i++) {
            assertEquals(ProviderHealthDTO.HEALTHY, probe.getHealth("svc" + i).getStatus());
        }
    }

    @Test
    void cacheTtlOutlivesInterval() throws Exception {
        ProviderHealthProbeService shortLived =
                new ProviderHealthProbeService(serviceService, credentials, 500, 100, 4);
        server.createContext("/", ex -> respond(ex, 200, bytes("{}")));
        server.start();
        targets(ai("svc"));

        shortLived.probeAll();
        assertEquals(ProviderHealthDTO.HEALTHY, shortLived.getHealth("svc").getStatus());

        Thread.sleep(300); // > TTL (2 x 100ms)
        assertEquals(ProviderHealthDTO.UNKNOWN, shortLived.getHealth("svc").getStatus());
    }

    @Test
    void resolveBaseUrlPrefersExplicitThenProviderDefault() {
        ServiceDefinition explicit = ai("svc");
        explicit.setBaseUrl("https://relay.example/v1");
        assertEquals("https://relay.example/v1", probe.resolveBaseUrl(explicit));

        ServiceDefinition qwen = ai("svc");
        qwen.setBaseUrl(null);
        qwen.setProviderType("qwen");
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", probe.resolveBaseUrl(qwen));

        ServiceDefinition unknown = ai("svc");
        unknown.setBaseUrl("  ");
        unknown.setProviderType("custom");
        assertNull(probe.resolveBaseUrl(unknown));
    }

    @Test
    void blankBaseUrlUnknownProviderIsUnknownNotProbed() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            respond(ex, 200, bytes("{}"));
        });
        server.start();
        ServiceDefinition d = ai("svc");
        d.setBaseUrl(null);
        d.setProviderType("custom");
        targets(d);

        probe.probeAll();

        ProviderHealthDTO h = probe.getHealth("svc");
        assertEquals(ProviderHealthDTO.UNKNOWN, h.getStatus());
        assertTrue(h.getMessage().contains("baseUrl"), h.getMessage());
        assertEquals(0, calls.get());
    }
}