package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class CacheSecurityRegressionTest {
    private CacheGlobalFilter filter() {
        return filter(Map.of("enabled", true));
    }

    private CacheGlobalFilter filter(Map<String, Object> config) {
        StrategyManager manager = new StrategyManager(new ObjectMapper());
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setStrategyId("cache");
        strategy.setStrategyType(StrategyDefinition.TYPE_CACHE);
        strategy.setConfig(config);
        manager.putStrategy("cache", strategy);
        return new CacheGlobalFilter(manager);
    }

    private MockServerWebExchange request(String path, String header, String value) {
        var builder = MockServerHttpRequest.method(HttpMethod.GET, URI.create("http://localhost" + path));
        if (header != null) builder.header(header, value);
        var exchange = MockServerWebExchange.from(builder);
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id("cache-route")
                .uri("http://localhost").predicate(ignored -> true).build());
        return exchange;
    }

    private Mono<Void> respond(ServerWebExchange exchange, String body) {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void publicResponsesStillHitCache() {
        var filter = filter();
        filter.filter(request("/public", null, null), e -> respond(e, "cached")).block();
        var second = request("/public", null, null);
        filter.filter(second, e -> Mono.error(new AssertionError("unexpected upstream call"))).block();
        assertEquals("cached", second.getResponse().getBodyAsString().block());
        assertEquals("HIT", second.getResponse().getHeaders().getFirst("X-Cache"));
    }

    @Test
    void cachedResponsesDoNotOverwriteTheCurrentRequestsTraceIdentity() {
        var filter = filter();
        var first = request("/trace", null, null);
        first.getResponse().getHeaders().set("X-Trace-Id", "old-trace");
        first.getResponse().getHeaders().set("X-Gateway-Span-Id", "old-span");
        filter.filter(first, e -> respond(e, "cached")).block();
        var second = request("/trace", null, null);
        second.getResponse().getHeaders().set("X-Trace-Id", "current-trace");
        second.getResponse().getHeaders().set("X-Gateway-Span-Id", "current-span");
        filter.filter(second, e -> Mono.error(new AssertionError("Expected a cache hit"))).block();
        assertEquals("current-trace", second.getResponse().getHeaders().getFirst("X-Trace-Id"));
        assertEquals("current-span", second.getResponse().getHeaders().getFirst("X-Gateway-Span-Id"));
        assertEquals("cached", second.getResponse().getBodyAsString().block());
    }

    @Test
    void apiVersionTargetsCannotShareOneCachedResponse() {
        var filter = filter();
        var first = request("/versioned", null, null);
        first.getAttributes().put(ApiVersionGlobalFilter.VERSION_ATTR, "v1");
        first.getAttributes().put(ApiVersionGlobalFilter.TARGET_URI_ATTR, URI.create("lb://v1"));
        filter.filter(first, e -> respond(e, "version-one")).block();
        var second = request("/versioned", null, null);
        second.getAttributes().put(ApiVersionGlobalFilter.VERSION_ATTR, "v2");
        second.getAttributes().put(ApiVersionGlobalFilter.TARGET_URI_ATTR, URI.create("lb://v2"));
        filter.filter(second, e -> respond(e, "version-two")).block();
        assertEquals("version-two", second.getResponse().getBodyAsString().block());
    }

    @Test
    void multiServiceRoutesCannotHaveGrayOrWeightedSelectionBypassedByTheResponseCache() {
        var filter = filter(); var upstreamCalls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            var exchange = request("/gray", null, null);
            var config = new com.leoli.gateway.model.MultiServiceConfig();
            config.setMode(com.leoli.gateway.model.MultiServiceConfig.RoutingMode.MULTI);
            exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id("cache-route").uri("lb://stable")
                    .predicate(e -> true).metadata(com.leoli.gateway.model.MultiServiceConfig.METADATA_KEY, config).build());
            filter.filter(exchange, e -> { upstreamCalls.incrementAndGet(); return respond(e, "body"); }).block();
        }
        assertEquals(2, upstreamCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "Cookie", "X-API-Key", "Api-Key", "ApiKey", "X-Access-Key"})
    void credentialedRequestsNeverReadOrPopulateSharedCache(String header) {
        var filter = filter();
        filter.filter(request("/profile", header, "alice"), e -> respond(e, "alice-private")).block();
        var bob = request("/profile", header, "bob");
        filter.filter(bob, e -> respond(e, "bob-private")).block();
        assertEquals("bob-private", bob.getResponse().getBodyAsString().block());
        filter.filter(request("/profile", null, null), e -> respond(e, "public")).block();
        var alice = request("/profile", header, "alice");
        filter.filter(alice, e -> respond(e, "alice-private")).block();
        assertEquals("alice-private", alice.getResponse().getBodyAsString().block());
    }

    @Test
    void verifiedIdentityWithoutCredentialHeaderBypassesCache() {
        var filter = filter();
        filter.filter(request("/profile", null, null), e -> respond(e, "public")).block();
        var exchange = request("/profile", null, null);
        exchange.getAttributes().put("jwt_subject", "alice");
        filter.filter(exchange, e -> respond(e, "alice-private")).block();
        assertEquals("alice-private", exchange.getResponse().getBodyAsString().block());
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-store", "private", "PRIVATE, max-age=60", "no-cache", "private=\"field\""})
    void forbiddenResponseIsNotCached(String control) {
        var filter = filter();
        filter.filter(request("/private", null, null), e -> {
            e.getResponse().getHeaders().add("Cache-Control", control);
            return respond(e, "private");
        }).block();
        var next = request("/private", null, null);
        filter.filter(next, e -> respond(e, "fresh")).block();
        assertEquals("fresh", next.getResponse().getBodyAsString().block());
    }

    @Test
    void requestNoCacheBypassesExistingEntry() {
        var filter = filter();
        filter.filter(request("/public", null, null), e -> respond(e, "stale")).block();
        var next = request("/public", "Cache-Control", "no-cache");
        filter.filter(next, e -> respond(e, "fresh")).block();
        assertEquals("fresh", next.getResponse().getBodyAsString().block());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Set-Cookie", "Vary"})
    void sessionSettingOrUnconfiguredVaryResponsesAreNotShared(String header) {
        var filter = filter();
        filter.filter(request("/vary", null, null), e -> {
            e.getResponse().getHeaders().add(header, header.equals("Vary") ? "Accept-Language" : "session=alice");
            return respond(e, "first");
        }).block();
        var next = request("/vary", null, null);
        filter.filter(next, e -> respond(e, "second")).block();
        assertEquals("second", next.getResponse().getBodyAsString().block());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/query?x=a%26y=b", "/query?x=%41", "/a%2Fb"})
    void distinctEncodedUrlsDoNotShareEntries(String encoded) {
        var filter = filter();
        filter.filter(request(encoded, null, null), e -> respond(e, "encoded")).block();
        var next = request(URI.create(encoded).getPath()
                + (URI.create(encoded).getQuery() == null ? "" : "?" + URI.create(encoded).getQuery()), null, null);
        filter.filter(next, e -> respond(e, "decoded")).block();
        assertEquals("decoded", next.getResponse().getBodyAsString().block());
    }

    @Test
    void failedBodyIsNotCached() {
        var filter = filter();
        StepVerifier.create(filter.filter(request("/broken", null, null), e -> {
            e.getResponse().setStatusCode(HttpStatus.OK);
            return e.getResponse().writeWith(Flux.concat(
                    Mono.just(e.getResponse().bufferFactory().wrap("partial".getBytes(StandardCharsets.UTF_8))),
                    Mono.error(new IllegalStateException("connection reset"))));
        })).expectError(IllegalStateException.class).verify();
        var next = request("/broken", null, null);
        filter.filter(next, e -> respond(e, "complete")).block();
        assertEquals("complete", next.getResponse().getBodyAsString().block());
    }

    @Test
    void cancelledBodyIsNotCached() {
        var filter = filter();
        AtomicInteger chunks = new AtomicInteger();
        StepVerifier.create(filter.filter(request("/cancelled", null, null), e -> {
            e.getResponse().setStatusCode(HttpStatus.OK);
            return e.getResponse().writeWith(Flux.concat(
                    Mono.just(e.getResponse().bufferFactory().wrap("partial".getBytes(StandardCharsets.UTF_8)))
                            .doOnNext(ignored -> chunks.incrementAndGet()), Flux.never()));
        })).then(() -> assertEquals(1, chunks.get())).thenCancel().verify();
        var next = request("/cancelled", null, null);
        filter.filter(next, e -> respond(e, "complete")).block();
        assertEquals("complete", next.getResponse().getBodyAsString().block());
    }

    @Test
    void routeAndInstanceByteBudgetsAreEnforcedAndReleasedOnInvalidation() {
        var filter = filter(Map.of("enabled", true, "maxCacheBytes", 1600));
        filter.initCacheInvalidation();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "maxTotalBytes", 1800L);
        String body = "a".repeat(600);
        filter.filter(request("/first", null, null), e -> respond(e, body)).block();
        var routeLimited = request("/second", null, null);
        filter.filter(routeLimited, e -> respond(e, body)).block();
        var otherRoute = request("/other", null, null);
        otherRoute.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id("other")
                .uri("http://localhost").predicate(ignored -> true).build());
        filter.filter(otherRoute, e -> respond(e, body)).block();
        var budget = (java.util.concurrent.atomic.AtomicLong) org.springframework.test.util.ReflectionTestUtils
                .getField(filter, "cachedBytes");
        assertTrue(budget.get() >= 600 && budget.get() <= 1600);
        var secondAgain = request("/second", null, null);
        filter.filter(secondAgain, e -> respond(e, "fresh")).block();
        assertEquals("fresh", secondAgain.getResponse().getBodyAsString().block());
        var manager = (StrategyManager) org.springframework.test.util.ReflectionTestUtils.getField(filter, "strategyManager");
        manager.clear();
        assertEquals(0, budget.get());
    }

    @Test
    void springCorsVaryHeadersRemainCacheableAndOriginsStayIsolated() {
        var filter = filter();
        filter.filter(request("/cors", "Origin", "https://one.example"), e -> {
            e.getResponse().getHeaders().set("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
            return respond(e, "one");
        }).block();
        var sameOrigin = request("/cors", "Origin", "https://one.example");
        filter.filter(sameOrigin, e -> Mono.error(new AssertionError("expected cache hit"))).block();
        assertEquals("one", sameOrigin.getResponse().getBodyAsString().block());
        var otherOrigin = request("/cors", "Origin", "https://two.example");
        filter.filter(otherOrigin, e -> respond(e, "two")).block();
        assertEquals("two", otherOrigin.getResponse().getBodyAsString().block());
    }
}
