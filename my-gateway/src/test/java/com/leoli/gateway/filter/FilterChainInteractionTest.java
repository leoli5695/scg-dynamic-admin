package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.config.RetryProperties;
import com.leoli.gateway.filter.loadbalancer.MultiServiceLoadBalancerFilter;
import com.leoli.gateway.filter.resilience.RetryGlobalFilter;
import com.leoli.gateway.filter.resilience.CircuitBreakerGlobalFilter;
import com.leoli.gateway.license.LicenseFeatureHelper;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.MultiServiceConfig;
import com.leoli.gateway.model.ServiceBindingType;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FilterChainInteractionTest {
    private final StrategyManager strategies = mock(StrategyManager.class);

    private MockServerWebExchange exchange(String uri) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(uri)).header("X-API-Version", "v2").build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("chain").uri("static://stable").predicate(e -> true).build());
        return exchange;
    }

    @ParameterizedTest
    @ValueSource(strings = {"lb://canary", "static://canary"})
    void versionTargetsSurviveScgUrlConstructionAndMultiServiceSelection(String target) {
        when(strategies.getApiVersionConfig("chain")).thenReturn(Map.of("versionMode", "HEADER", "versionLocation", "HEADER",
                "versionMappings", Map.of("v2", target)));
        var exchange = exchange("http://gateway/api/a%2Fb?value=a%26b");
        var config = new MultiServiceConfig(); config.setServiceId("stable"); config.setServiceType(ServiceBindingType.STATIC);
        Route route = exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id(route.getId()).uri(route.getUri()).predicate(e -> true)
                        .metadata(MultiServiceConfig.METADATA_KEY, config).build());
        var strip = new StripPrefixGatewayFilterFactory.Config(); strip.setParts(1);
        var api = new ApiVersionGlobalFilter(strategies);
        var routing = new ApiVersionRoutingFilter();
        api.filter(exchange, e -> new StripPrefixGatewayFilterFactory().apply(strip).filter(e,
                stripped -> new RouteToRequestUrlFilter().filter(stripped,
                        built -> new MultiServiceLoadBalancerFilter(new ObjectMapper()).filter(built,
                                selected -> routing.filter(selected, forwarded -> {
                                    assertEquals(target + "/a%2Fb?value=a%26b",
                                            forwarded.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR).toString());
                                    assertEquals("canary", forwarded.getAttribute(MultiServiceLoadBalancerFilter.TARGET_SERVICE_ID_ATTR));
                                    assertEquals(target.startsWith("static"), forwarded.getAttributes().containsKey(MultiServiceLoadBalancerFilter.ORIGINAL_STATIC_URI_ATTR));
                                    return Mono.empty();
                                }))))).block();
        assertTrue(api.getOrder() < new RouteToRequestUrlFilter().getOrder());
        assertTrue(routing.getOrder() > new MultiServiceLoadBalancerFilter(new ObjectMapper()).getOrder());
    }

    @Test
    void pathVersionRewritePreservesEncodedSeparatorsAndQueries() {
        when(strategies.getApiVersionConfig("chain")).thenReturn(Map.of("versionMode", "PATH", "versionLocation", "HEADER",
                "versionMappings", Map.of("v2", "unused")));
        var exchange = exchange("http://gateway/a%2Fb?value=a%26b");
        new ApiVersionGlobalFilter(strategies).filter(exchange, e -> {
            assertEquals("/v2/a%2Fb", e.getRequest().getURI().getRawPath());
            assertEquals("value=a%26b", e.getRequest().getURI().getRawQuery()); return Mono.empty();
        }).block();
    }

    private RetryGlobalFilter retry() {
        var props = new RetryProperties();
        props.setDefaultMaxAttempts(3); props.setDefaultRetryIntervalMs(0);
        when(strategies.isStrategyEnabled("chain", StrategyDefinition.TYPE_RETRY)).thenReturn(true);
        when(strategies.getRetryConfig("chain")).thenReturn(Map.of("maxAttempts", 3, "retryIntervalMs", 0));
        return new RetryGlobalFilter(strategies, props);
    }

    @Test
    void retryPreservesEarlierFilterHeadersAndClearsPreviousAttemptState() {
        var retry = retry();
        var exchange = exchange("http://gateway/test");
        exchange.getResponse().getHeaders().set("Access-Control-Allow-Origin", "https://client.example");
        exchange.getResponse().getHeaders().set("X-API-Version", "v2");
        exchange.getResponse().getHeaders().set("X-Trace-Id", "current-trace");
        var calls = new AtomicInteger();
        retry.filter(exchange, e -> {
            int attempt = calls.incrementAndGet();
            if (attempt == 1) {
                e.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                e.getResponse().getHeaders().set("X-Upstream-Old", "discard");
                e.getResponse().getHeaders().setContentLength(4);
            } else {
                assertNull(e.getResponse().getStatusCode());
                assertFalse(e.getResponse().getHeaders().containsKey("X-Upstream-Old"));
                assertEquals(-1, e.getResponse().getHeaders().getContentLength());
                e.getResponse().setStatusCode(HttpStatus.OK);
            }
            return e.getResponse().writeWith(Mono.just(e.getResponse().bufferFactory().wrap("body".getBytes(StandardCharsets.UTF_8))));
        }).block(Duration.ofSeconds(5));
        assertEquals(2, calls.get());
        assertEquals("https://client.example", exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
        assertEquals("v2", exchange.getResponse().getHeaders().getFirst("X-API-Version"));
        assertEquals("current-trace", exchange.getResponse().getHeaders().getFirst("X-Trace-Id"));
        assertNull(exchange.getAttribute(TraceCaptureGlobalFilter.IS_RETRY_REQUEST_ATTR));
    }

    @Test
    void committedResponseCannotBeRetriedAfterAnError() {
        var retry = retry(); var exchange = exchange("http://gateway/test"); var calls = new AtomicInteger();
        assertThrows(java.io.UncheckedIOException.class, () -> retry.filter(exchange, e -> {
            calls.incrementAndGet();
            return e.getResponse().writeWith(Mono.just(e.getResponse().bufferFactory().wrap(new byte[]{1})))
                    .then(Mono.error(new java.io.UncheckedIOException(new java.io.IOException("stream disconnected"))));
        }).block(Duration.ofSeconds(5)));
        assertEquals(1, calls.get());
    }

    @Test
    void deleteBodyIsCachedAndReplayedForRetries() {
        var retry = retry(); var sourceReads = new AtomicInteger(); var bodies = new ArrayList<String>();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.DELETE, URI.create("http://gateway/test"))
                .header("Content-Length", "5").body(Flux.defer(() -> sourceReads.incrementAndGet() == 1
                        ? Flux.just(org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap("hello".getBytes(StandardCharsets.UTF_8)))
                        : Flux.error(new IllegalStateException("body already consumed")))));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("chain").uri("http://backend").predicate(e -> true).build());
        retry.filter(exchange, e -> DataBufferUtils.join(e.getRequest().getBody()).flatMap(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); DataBufferUtils.release(buffer);
            bodies.add(new String(bytes, StandardCharsets.UTF_8));
            return bodies.size() == 1 ? Mono.error(new java.io.IOException("first attempt failed")) : Mono.empty();
        })).block(Duration.ofSeconds(5));
        assertEquals(List.of("hello", "hello"), bodies); assertEquals(1, sourceReads.get());
    }

    @Test
    void headerOperationsKeepTheCanonicalTraceInsteadOfReplacingItWithAnAliasHeader() {
        String canonical = "0123456789abcdef0123456789abcdef";
        when(strategies.getHeaderOpConfig("chain")).thenReturn(Map.of("enableTraceId", true, "traceIdHeader", "X-Custom-Trace"));
        var exchange = exchange("http://gateway/test"); exchange.getAttributes().put("traceId", canonical);
        new HeaderOpGlobalFilter(strategies).filter(exchange, e -> {
            assertEquals(canonical, e.getAttribute("traceId"));
            assertEquals(canonical, e.getRequest().getHeaders().getFirst("X-Custom-Trace"));
            return e.getResponse().setComplete();
        }).block();
        assertEquals(canonical, exchange.getResponse().getHeaders().getFirst("X-Custom-Trace"));
    }

    @Test
    void circuitBreakerWithAutomaticTransitionDisabledCanRecoverThroughAnIncomingRequest() throws Exception {
        var license = mock(LicenseFeatureHelper.class); when(license.hasFeature(any())).thenReturn(true);
        when(strategies.isStrategyEnabled("chain", StrategyDefinition.TYPE_CIRCUIT_BREAKER)).thenReturn(true);
        when(strategies.getCircuitBreakerConfig("chain")).thenReturn(Map.of("minimumNumberOfCalls", 1, "slidingWindowSize", 2,
                "waitDurationInOpenState", 1, "automaticTransitionFromOpenToHalfOpenEnabled", false));
        var filter = new CircuitBreakerGlobalFilter(strategies, license);
        filter.filter(exchange("http://gateway/test"), e -> { e.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR); return Mono.empty(); }).block();
        Thread.sleep(20);
        var recovered = exchange("http://gateway/test"); var calls = new AtomicInteger();
        filter.filter(recovered, e -> { calls.incrementAndGet(); e.getResponse().setStatusCode(HttpStatus.OK); return Mono.empty(); }).block();
        assertEquals(1, calls.get()); assertEquals(HttpStatus.OK, recovered.getResponse().getStatusCode());
    }
}
