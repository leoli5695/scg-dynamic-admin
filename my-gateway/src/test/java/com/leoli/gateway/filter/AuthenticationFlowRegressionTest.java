package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.auth.AuthProcessManager;
import com.leoli.gateway.auth.BasicAuthProcessor;
import com.leoli.gateway.filter.security.AuthenticationGlobalFilter;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.crypto.bcrypt.BCrypt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class AuthenticationFlowRegressionTest {
    @Test
    void unavailableRevocationServiceReturns503WithoutCallingTheUpstream() {
        var manager = org.mockito.Mockito.mock(AuthProcessManager.class);
        org.mockito.Mockito.when(manager.authenticate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(new com.leoli.gateway.auth.RevocationUnavailableException(null)));
        var unavailable = new AuthenticationGlobalFilter(strategies, bindings, manager);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/private")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        "alice:correct-password".getBytes(StandardCharsets.UTF_8))));
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id("private-route")
                .uri("http://localhost").predicate(e -> true).build());
        var forwarded = new AtomicBoolean();
        unavailable.filter(exchange, e -> { forwarded.set(true); return Mono.empty(); }).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertFalse(forwarded.get());
    }
    private final AuthBindingManager bindings = new AuthBindingManager(new ObjectMapper());
    private final StrategyManager strategies = new StrategyManager(new ObjectMapper());
    private final AuthConfig config = new AuthConfig();
    private AuthenticationGlobalFilter filter;

    @BeforeEach
    void setUp() {
        config.setPolicyId("basic-policy");
        config.setAuthType("BASIC");
        config.setBasicUsername("alice");
        config.setBasicPassword("correct-password");
        bindings.putPolicy("basic-policy", config);
        bindings.setPolicyRoutes("basic-policy", List.of("private-route"));
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setStrategyId("auth-strategy");
        strategy.setStrategyType(StrategyDefinition.TYPE_AUTH);
        strategy.setScope("ROUTE");
        strategy.setRouteId("private-route");
        strategy.setConfig(Map.of("authType", "BASIC"));
        strategies.putStrategy("auth-strategy", strategy);
        filter = new AuthenticationGlobalFilter(strategies, bindings,
                new AuthProcessManager(List.of(new BasicAuthProcessor())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAIN", "BCRYPT", "SHA256", "SHA-256", "MD5"})
    void acceptsConfiguredPasswordAndRejectsWrongPassword(String algorithm) throws Exception {
        config.setPasswordHashAlgorithm(algorithm);
        String stored = switch (algorithm) {
            case "BCRYPT" -> BCrypt.hashpw("correct-password", BCrypt.gensalt(4));
            case "SHA256", "SHA-256", "MD5" -> HexFormat.of().formatHex(
                    MessageDigest.getInstance(algorithm.equals("MD5") ? "MD5" : "SHA-256")
                            .digest("correct-password".getBytes(StandardCharsets.UTF_8)));
            default -> "correct-password";
        };
        config.setBasicPassword(stored);
        assertForwarded("correct-password");
        assertUnauthorized("wrong-password");
        if (!algorithm.equals("PLAIN")) {
            assertUnauthorized(stored);
        }
    }

    @Test
    void rejectsMalformedHashAndUnknownAlgorithm() {
        config.setPasswordHashAlgorithm("BCRYPT");
        assertUnauthorized("correct-password");
        config.setPasswordHashAlgorithm("UNKNOWN");
        assertUnauthorized("correct-password");
    }

    @Test
    void preservesDownstreamErrorAfterSuccessfulAuthentication() {
        var exchange = exchange("correct-password");
        RuntimeException downstreamError = new RuntimeException("upstream unavailable");
        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(downstreamError)))
                .expectErrorMatches(error -> error == downstreamError).verify();
        assertNull(exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().isCommitted());
    }

    @Test
    void passwordIsCheckedOnceOffTheCallingThread() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var worker = new java.util.concurrent.atomic.AtomicReference<String>();
        BasicAuthProcessor processor = new BasicAuthProcessor() {
            @Override
            protected Mono<Boolean> validateCredentials(String username, String password, AuthConfig auth) {
                calls.incrementAndGet();
                worker.set(Thread.currentThread().getName());
                return super.validateCredentials(username, password, auth);
            }
        };
        filter = new AuthenticationGlobalFilter(strategies, bindings, new AuthProcessManager(List.of(processor)));
        assertForwarded("correct-password");
        assertEquals(1, calls.get());
        assertTrue(worker.get().startsWith("gateway-password"));
        assertNotEquals(Thread.currentThread().getName(), worker.get());
    }

    @Test
    void unboundBasicPolicyRequiresValidPasswordBeforeReturning403() {
        bindings.setPolicyRoutes("basic-policy", List.of());
        assertUnauthorized("wrong-password");
        var exchange = exchange("correct-password");
        filter.filter(exchange, ignored -> Mono.error(new AssertionError("must not forward"))).block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void processorFailureReturns401WithoutCallingDownstream() {
        config.setPasswordHashAlgorithm("PLAIN");
        AuthProcessManager rejectingProcessor = new AuthProcessManager(List.of());
        filter = new AuthenticationGlobalFilter(strategies, bindings, rejectingProcessor);
        assertUnauthorized("correct-password");
    }

    private void assertForwarded(String password) {
        AtomicBoolean called = new AtomicBoolean();
        var exchange = exchange(password);
        GatewayFilterChain chain = ignored -> {
            called.set(true);
            return Mono.empty();
        };
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        assertTrue(called.get());
        assertEquals("alice", exchange.getAttribute("auth_user"));
    }

    private void assertUnauthorized(String password) {
        AtomicBoolean called = new AtomicBoolean();
        var exchange = exchange(password);
        StepVerifier.create(filter.filter(exchange, ignored -> {
            called.set(true);
            return Mono.empty();
        })).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertFalse(called.get());
    }

    private MockServerWebExchange exchange(String password) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/private")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("alice:" + password).getBytes(StandardCharsets.UTF_8))));
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async()
                .id("private-route").uri("http://localhost").predicate(ignored -> true).build());
        return exchange;
    }
}
