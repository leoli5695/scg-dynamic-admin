package com.leoli.gateway.filter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.filter.CacheGlobalFilter;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class CorsPolicyRegressionTest {
    private final StrategyManager manager = new StrategyManager(new ObjectMapper());
    private CorsGlobalFilter filter(Map<String, Object> config) {
        install("cors", "CORS", config);
        var filter = new CorsGlobalFilter(); ReflectionTestUtils.setField(filter, "strategyManager", manager);
        return filter;
    }

    private void install(String id, String type, Map<String, Object> config) {
        var policy = new StrategyDefinition(); policy.setStrategyId(id); policy.setEnabled(true);
        policy.setStrategyType(type); policy.setScope("GLOBAL"); policy.setConfig(config);
        manager.putStrategy(id, policy);
    }

    private MockServerWebExchange get() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/data").header("Origin", "https://a.example"));
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id("r").uri("http://localhost").predicate(e -> true).build());
        return exchange;
    }

    @Test void credentialsWithWildcardReflectsTheAllowedOriginAndAddsVary() {
        var filter = filter(Map.of("allowedOrigins", List.of("*"), "allowCredentials", true));
        var exchange = get(); filter.filter(exchange, ex -> Mono.empty()).block();
        assertEquals("https://a.example", exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
        assertTrue(exchange.getResponse().getHeaders().getAccessControlAllowCredentials());
        assertTrue(exchange.getResponse().getHeaders().getVary().contains("Origin"));
    }

    @Test void preflightRejectsMethodsOutsideAllowlist() {
        var filter = filter(Map.of("allowedOrigins", List.of("*"), "allowedMethods", List.of("GET")));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.options("/data")
                .header("Origin", "https://a.example").header("Access-Control-Request-Method", "DELETE"));
        filter.filter(exchange, ex -> Mono.error(new AssertionError("must not reach upstream"))).block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("DELETE"));
    }

    @Test void allowedHeadersAreCaseInsensitiveAndPreflightVariesByAllRequestInputs() {
        var filter = filter(Map.of("allowedOrigins", List.of("*"), "allowedMethods", List.of("POST"),
                "allowedHeaders", List.of("Authorization")));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.options("/data")
                .header("Origin", "https://a.example").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization"));
        filter.filter(exchange, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertEquals("authorization", exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Headers"));
        assertTrue(exchange.getResponse().getHeaders().getVary().containsAll(
                List.of("Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers")));
    }

    @Test void wildcardMethodsAndHeadersAreReturnedAsConcreteRequestedValues() {
        var filter = filter(Map.of("allowedOrigins", List.of("*"), "allowCredentials", true,
                "allowedMethods", List.of("*"), "allowedHeaders", List.of("*")));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.options("/data")
                .header("Origin", "https://a.example").header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "X-Custom"));
        filter.filter(exchange, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertEquals("PATCH", exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Methods"));
        assertEquals("X-Custom", exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Headers"));
    }

    @Test void corsPolicyChangesCannotReplayPreviouslyCachedPermissionHeaders() {
        var filter = filter(Map.of("allowedOrigins", List.of("https://a.example")));
        install("cache", "CACHE", Map.of("enabled", true, "ttlSeconds", 60));
        var cache = new CacheGlobalFilter(manager); cache.initCacheInvalidation();
        AtomicInteger calls = new AtomicInteger();
        GatewayFilterChain backend = ex -> {
            calls.incrementAndGet(); ex.getResponse().setStatusCode(HttpStatus.OK);
            return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap("ok".getBytes(StandardCharsets.UTF_8))));
        };
        var first = get(); filter.filter(first, ex -> cache.filter(ex, backend)).block();
        var hit = get(); filter.filter(hit, ex -> cache.filter(ex, backend)).block();
        assertEquals(1, calls.get()); assertEquals("HIT", hit.getResponse().getHeaders().getFirst("X-Cache"));
        install("cors", "CORS", Map.of("allowedOrigins", List.of("https://b.example")));
        var after = get(); filter.filter(after, ex -> cache.filter(ex, backend)).block();
        assertEquals(2, calls.get());
        assertNull(after.getResponse().getHeaders().getAccessControlAllowOrigin());
    }
}
