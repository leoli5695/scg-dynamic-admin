package com.leoli.gateway.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.model.AiSecurityConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalContentModeratorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void httpContractBuildsRequestAndParsesRealResponse() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/check", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("X-Test"));
            byte[] result = "{\"flagged\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result);
            exchange.close();
        });
        server.start();
        try {
            AiSecurityConfig c = new AiSecurityConfig();
            c.setExternalUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/check");
            c.setExternalHeaders(Map.of("X-Test", "test-key"));
            var executor = new ExternalContentModerator(WebClient.builder(), List.of(new HttpJsonModerationAdapter()));
            assertTrue(executor.moderate("测试内容", "REQUEST", c).block().isFlagged());
            assertEquals("测试内容", mapper.readTree(received.get()).path("text").asText());
            assertEquals("REQUEST", mapper.readTree(received.get()).path("direction").asText());
            assertEquals("test-key", auth.get());
        } finally { server.stop(0); }
    }

    @Test
    void referenceAdapterRejectsMissingVerdictsAndHttpFailures() {
        var adapter = new HttpJsonModerationAdapter();
        for (String body : List.of("{}", "{\"flagged\":\"false\"}", "{\"flagged\":null}", "")) {
            assertThrows(Exception.class, () -> adapter.parse(new ModerationAdapter.Response(200, Map.of(), body.getBytes(StandardCharsets.UTF_8))));
        }
        assertThrows(Exception.class, () -> adapter.parse(new ModerationAdapter.Response(503, Map.of(), "{\"flagged\":false}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void unknownAdapterFailsRatherThanUsingAnotherProtocol() {
        AiSecurityConfig c = new AiSecurityConfig();
        c.setExternalUrl("https://example.invalid");
        c.setExternalAdapter("UNINSTALLED");
        var executor = new ExternalContentModerator(WebClient.builder(), List.of(new HttpJsonModerationAdapter()));
        StepVerifier.create(executor.moderate("text", "REQUEST", c)).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void javaAdapterControlsMethodHeadersRawBodyAndResponseProtocol() {
        ModerationAdapter custom = new ModerationAdapter() {
            public String id() { return "PLAIN"; }
            public Request prepare(Context context) {
                return new Request("PUT", URI.create(context.endpoint() + "/custom"),
                        Map.of("Content-Type", "text/plain", "X-Signature", "signature"), context.text().getBytes(StandardCharsets.UTF_8));
            }
            public Decision parse(Response response) {
                assertEquals(202, response.status());
                assertEquals("provider", response.headers().get("X-Vendor").getFirst());
                assertEquals("approved", new String(response.body(), StandardCharsets.UTF_8));
                return Decision.clean();
            }
        };
        var builder = WebClient.builder().exchangeFunction(request -> {
            assertEquals("PUT", request.method().name());
            assertTrue(request.url().getPath().endsWith("/custom"));
            assertEquals("signature", request.headers().getFirst("X-Signature"));
            return Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).header("X-Vendor", "provider").body("approved").build());
        });
        var executor = new ExternalContentModerator(builder, List.of(custom));
        AiSecurityConfig c = new AiSecurityConfig();
        c.setExternalUrl("https://example.invalid");
        c.setExternalAdapter("PLAIN");
        assertFalse(executor.moderate("text", "REQUEST", c).block().isFlagged());
    }

    @Test
    void externalTimeoutIsBoundedAndDuplicateIdsAreRejected() {
        var builder = WebClient.builder().exchangeFunction(request -> Mono.never());
        var executor = new ExternalContentModerator(builder, List.of(new HttpJsonModerationAdapter()));
        AiSecurityConfig c = new AiSecurityConfig();
        c.setExternalUrl("https://example.invalid");
        c.setExternalTimeoutMs(100);
        StepVerifier.create(executor.moderate("text", "REQUEST", c))
                .expectError(java.util.concurrent.TimeoutException.class).verify(java.time.Duration.ofSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> new ExternalContentModerator(WebClient.builder(),
                List.of(new HttpJsonModerationAdapter(), new HttpJsonModerationAdapter())));
    }

    @Test
    void externalFailuresBlockOrUseLocalRulesExplicitly() throws Exception {
        var external = mock(ExternalContentModerator.class);
        when(external.moderate(anyString(), anyString(), any())).thenReturn(Mono.error(new IllegalStateException()));
        var inspector = new AiContentInspector(new RuleBasedModerator(), external);
        AiSecurityConfig c = new AiSecurityConfig();
        c.setExternalUrl("https://example.invalid");
        assertTrue(inspector.inspect(mapper.readTree("{\"input\":\"safe\"}"), "REQUEST", c, false).block().blocked());
        c.setExternalFailureAction("LOCAL");
        var result = inspector.inspect(mapper.readTree("{\"input\":\"a@b.com\"}"), "REQUEST", c, false).block();
        assertFalse(result.blocked());
        assertTrue(result.externalFailed());
        assertEquals("***", result.body().path("input").asText());
        c.setProhibitedWords(List.of("forbidden"));
        assertTrue(inspector.inspect(mapper.readTree("{\"input\":\"forbidden\"}"), "REQUEST", c, false).block().blocked());
    }

    @Test
    void externalReplacementDoesNotLeakContentAndReceivesMaskedPii() throws Exception {
        var external = mock(ExternalContentModerator.class);
        var flagged = ContentModerator.ModerationResult.clean("");
        flagged.setFlagged(true);
        flagged.getCategories().add("EXTERNAL_CONTENT");
        when(external.moderate(anyString(), anyString(), any())).thenReturn(Mono.just(flagged));
        var inspector = new AiContentInspector(new RuleBasedModerator(), external);
        AiSecurityConfig c = new AiSecurityConfig();
        c.setExternalUrl("https://example.invalid");
        c.setOutboundAction("REPLACE");
        var result = inspector.inspect(mapper.readTree("{\"content\":\"a@b.com secret\"}"), "RESPONSE", c, false).block();
        assertEquals(c.getBlockMessage(), result.body().path("content").asText());
        verify(external).moderate(eq("*** secret"), eq("RESPONSE"), same(c));
    }
}
