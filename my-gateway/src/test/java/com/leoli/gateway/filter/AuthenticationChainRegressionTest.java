package com.leoli.gateway.filter;

import com.leoli.gateway.auth.AuthProcessManager;
import com.leoli.gateway.filter.security.AuthenticationGlobalFilter;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationChainRegressionTest {
    private final StrategyManager strategies = mock(StrategyManager.class);
    private final AuthBindingManager bindings = mock(AuthBindingManager.class);
    private final AuthProcessManager processors = mock(AuthProcessManager.class);

    private AuthenticationGlobalFilter filter() {
        var strategy = new StrategyDefinition(); strategy.setEnabled(true); strategy.setConfig(Map.of("authType", "API_KEY"));
        when(strategies.getStrategyForRoute("chain", StrategyDefinition.TYPE_AUTH)).thenReturn(strategy);
        when(bindings.getPoliciesForRoute("chain")).thenReturn(Set.of("policy"));
        when(bindings.findPolicyByApiKey("credential")).thenReturn("policy");
        return new AuthenticationGlobalFilter(strategies, bindings, processors);
    }

    private MockServerWebExchange exchange() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/private").header("X-API-Key", "credential").build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("chain").uri("http://backend").predicate(e -> true).build());
        return exchange;
    }

    @Test
    void disablingAnAlreadyMatchedCredentialCannotTurnAuthenticationIntoAnAllowedNoOp() {
        var filter = filter();
        var disabled = new AuthConfig(); disabled.setAuthType("API_KEY"); disabled.setEnabled(false);
        when(bindings.getAuthConfig("policy")).thenReturn(disabled);
        var exchange = exchange();
        filter.filter(exchange, forwarded -> { fail("Disabled credential must not reach the backend"); return Mono.empty(); }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(processors);
    }

    @Test
    void loadingAnEvictedPolicyRunsOutsideTheEventLoopAndStillAuthenticatesBeforeForwarding() {
        var filter = filter();
        var loaded = new AuthConfig(); loaded.setAuthType("API_KEY"); loaded.setEnabled(true);
        when(bindings.getAuthConfig("policy")).thenReturn(null, loaded);
        doAnswer(call -> { assertFalse(Schedulers.isInNonBlockingThread(), "synchronous config lookup must not block a reactive worker"); return null; })
                .when(bindings).loadPolicyWithRoutes("policy");
        when(processors.authenticate(any(), eq(loaded))).thenReturn(Mono.empty());
        var forwarded = new AtomicBoolean();
        Mono.defer(() -> filter.filter(exchange(), e -> { forwarded.set(true); return Mono.empty(); }))
                .subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5));
        assertTrue(forwarded.get());
        verify(processors).authenticate(any(), eq(loaded));
    }

    @Test
    void hmacVerifiesOriginalBytesBeforeTransformationAndTheBackendReceivesTheTransformedBody() throws Exception {
        var strategy = new StrategyDefinition(); strategy.setEnabled(true); strategy.setConfig(Map.of("authType", "HMAC"));
        when(strategies.getStrategyForRoute("chain", StrategyDefinition.TYPE_AUTH)).thenReturn(strategy);
        when(bindings.getPoliciesForRoute("chain")).thenReturn(Set.of("policy"));
        when(bindings.findPolicyByAccessKey("access")).thenReturn("policy");
        var credential = new AuthConfig(); credential.setEnabled(true); credential.setAuthType("HMAC");
        credential.setAccessKey("access"); credential.setSecretKey("test-secret"); credential.setSignatureAlgorithm("HMAC-SHA256");
        credential.setRequireNonce(true);
        when(bindings.getAuthConfig("policy")).thenReturn(credential);
        var hmac = new com.leoli.gateway.auth.HmacSignatureAuthProcessor();
        org.springframework.test.util.ReflectionTestUtils.setField(hmac, "maxBodyBytes", 1024 * 1024);
        var auth = new AuthenticationGlobalFilter(strategies, bindings, new AuthProcessManager(java.util.List.of(hmac)));
        var transformConfig = new com.leoli.gateway.model.RequestTransformConfig(); transformConfig.setEnabled(true);
        transformConfig.getFieldMapping().setEnabled(true); transformConfig.getFieldMapping().getAddFields().put("injected", "yes");
        when(strategies.getConfig("chain", StrategyDefinition.TYPE_REQUEST_TRANSFORM, com.leoli.gateway.model.RequestTransformConfig.class))
                .thenReturn(transformConfig);
        var transform = new com.leoli.gateway.filter.transform.RequestTransformFilter();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        org.springframework.test.util.ReflectionTestUtils.setField(transform, "strategyManager", strategies);
        org.springframework.test.util.ReflectionTestUtils.setField(transform, "objectMapper", mapper);
        String original = "{\"message\":\"hello\"}";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = java.util.UUID.randomUUID().toString();
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String canonical = "POST\n/private\n\n" + timestamp + "\n" + nonce + "\n" + digest;
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = java.util.Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/private")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("X-Access-Key", "access")
                .header("X-Timestamp", timestamp).header("X-Nonce", nonce).header("X-Signature", signature).body(original));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("chain").uri("http://backend").predicate(e -> true).build());
        var reached = new AtomicBoolean();
        var ordered = new java.util.ArrayList<org.springframework.cloud.gateway.filter.GlobalFilter>(java.util.List.of(auth, transform));
        ordered.sort(java.util.Comparator.comparingInt(f -> ((org.springframework.core.Ordered) f).getOrder()));
        try {
            org.springframework.cloud.gateway.filter.GatewayFilterChain terminal = forwarded ->
                    org.springframework.core.io.buffer.DataBufferUtils.join(forwarded.getRequest().getBody()).flatMap(buffer -> {
                        try {
                            byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes);
                            var json = mapper.readTree(bytes);
                            assertEquals("hello", json.path("message").asText());
                            assertEquals("yes", json.path("injected").asText());
                            reached.set(true); return Mono.empty();
                        } catch (Exception error) { return Mono.error(error); }
                        finally { org.springframework.core.io.buffer.DataBufferUtils.release(buffer); }
                    });
            ordered.get(0).filter(exchange, e -> ordered.get(1).filter(e, terminal)).block(Duration.ofSeconds(5));
            assertTrue(reached.get(), "A valid wire signature must authenticate before the body is transformed");
        } finally {
            transform.closeWorkPool();
        }
    }
}
