package com.leoli.gateway.e2e;

import com.leoli.gateway.manager.RouteManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.route.DynamicRouteDefinitionLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the gateway critical path:
 * routing -> rate limiting -> circuit breaker -> retry.
 *
 * Boots the FULL application context with the "e2e" profile (no external infra),
 * injects routes/strategies programmatically via RouteManager/StrategyManager,
 * and drives real HTTP traffic through the Netty server to a local stub downstream.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Gateway Critical-Path E2E")
class GatewayE2ETest {

    /** Started before the Spring context boots so routes can point at it. */
    private static final DownstreamStubServer DOWNSTREAM = newDownstream();

    private static DownstreamStubServer newDownstream() {
        try {
            return new DownstreamStubServer();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start downstream stub", e);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RouteManager routeManager;

    @Autowired
    private StrategyManager strategyManager;

    @Autowired
    private DynamicRouteDefinitionLocator routeLocator;

    @BeforeAll
    void setUpRoutesAndStrategies() {
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(15)).build();
        // Licensed features (circuit breaker etc.) are unlocked via the e2e profile property
        // license.client.fallback-edition=ENTERPRISE, honored by LicenseValidator's fallback.

        // --- Routes ---
        registerRoute("e2e-basic", "/e2e/hello", null);
        // Map the limited path onto the /e2e/hello downstream endpoint
        registerRoute("e2e-limited", "/e2e/limited", "/e2e/hello");
        registerRoute("e2e-retry", "/e2e/flaky", null);
        registerRoute("e2e-breaker", "/e2e/always-500", null);
        registerRoute("e2e-cache", "/e2e/cacheable", null);

        // --- Rate limit strategy: window quota 2 per minute (qps=1, burst=0) ---
        // Note: the explicit windowSizeMs key takes precedence over timeUnit; the local
        // window capacity is qps + max(qps, burst) = 2, so the third request within the
        // minute window must be rejected with 429.
        Map<String, Object> rateConfig = new HashMap<>();
        rateConfig.put("enabled", true);
        rateConfig.put("qps", 1);
        rateConfig.put("burstCapacity", 0);
        rateConfig.put("windowSizeMs", 60000);
        rateConfig.put("keyResolver", "ip");
        rateConfig.put("keyType", "combined");
        rateConfig.put("keyPrefix", "rate_limit:");
        registerStrategy("e2e-strategy-ratelimit", StrategyDefinition.TYPE_RATE_LIMITER, "e2e-limited", rateConfig);

        // --- Retry strategy: up to 3 attempts (incl. first) on HTTP 500 ---
        Map<String, Object> retryConfig = new HashMap<>();
        retryConfig.put("enabled", true);
        retryConfig.put("maxAttempts", 3);
        retryConfig.put("retryIntervalMs", 20);
        retryConfig.put("retryOnStatusCodes", List.of(500));
        registerStrategy("e2e-strategy-retry", StrategyDefinition.TYPE_RETRY, "e2e-retry", retryConfig);

        // --- Circuit breaker: opens after 2 consecutive failures ---
        Map<String, Object> cbConfig = new HashMap<>();
        cbConfig.put("failureRateThreshold", 50);
        cbConfig.put("slidingWindowSize", 2);
        cbConfig.put("minimumNumberOfCalls", 2);
        cbConfig.put("waitDurationInOpenState", 60000);
        cbConfig.put("permittedNumberOfCallsInHalfOpenState", 1);
        cbConfig.put("automaticTransitionFromOpenToHalfOpenEnabled", false);
        registerStrategy("e2e-strategy-breaker", StrategyDefinition.TYPE_CIRCUIT_BREAKER, "e2e-breaker", cbConfig);

        // --- Response cache: 60s TTL, GET only ---
        Map<String, Object> cacheConfig = new HashMap<>();
        cacheConfig.put("enabled", true);
        cacheConfig.put("ttlSeconds", 60);
        cacheConfig.put("maxSize", 100);
        cacheConfig.put("cacheMethods", List.of("GET"));
        registerStrategy("e2e-strategy-cache", StrategyDefinition.TYPE_CACHE, "e2e-cache", cacheConfig);

        routeLocator.refresh();
        waitForRoute("/e2e/hello");
    }

    // ============================================================
    // Routing
    // ============================================================

    @Test
    @DisplayName("Routing: request is forwarded to downstream and response passes through")
    void routing_forwardsToDownstream() {
        int before = DOWNSTREAM.helloCount();

        webTestClient.get().uri("/e2e/hello")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertTrue(body.contains("hello-from-downstream"),
                        "unexpected body: " + body));

        assertEquals(before + 1, DOWNSTREAM.helloCount(), "downstream should be hit exactly once");
    }

    // ============================================================
    // Rate limiting
    // ============================================================

    @Test
    @DisplayName("Rate limit: quota exhausted -> 429 with rate limit headers")
    void rateLimit_rejectsWhenQuotaExhausted() {
        // Quota = 2 per 60s window (no burst): first two pass, third is rejected
        webTestClient.get().uri("/e2e/limited").exchange().expectStatus().isOk();
        webTestClient.get().uri("/e2e/limited").exchange().expectStatus().isOk();

        webTestClient.get().uri("/e2e/limited")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("X-RateLimit-Remaining");
    }

    // ============================================================
    // Retry
    // ============================================================

    @Test
    @DisplayName("Retry: transient 500s are retried until success")
    void retry_eventuallySucceedsAfterTransientFailures() {
        int before = DOWNSTREAM.flakyCount();

        webTestClient.get().uri("/e2e/flaky")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertTrue(body.contains("flaky-recovered"),
                        "unexpected body: " + body));

        // Downstream saw the two failures plus the successful attempt
        assertEquals(before + 3, DOWNSTREAM.flakyCount(),
                "retry should reach downstream 3 times (2 failures + 1 success)");
    }

    // ============================================================
    // Circuit breaker
    // ============================================================

    @Test
    @DisplayName("Circuit breaker: opens after failures and fast-fails without hitting downstream")
    void circuitBreaker_opensAndFastFails() {
        int before = DOWNSTREAM.always500Count();

        // Two failures fill the sliding window -> breaker opens
        webTestClient.get().uri("/e2e/always-500").exchange().expectStatus().is5xxServerError();
        webTestClient.get().uri("/e2e/always-500").exchange().expectStatus().is5xxServerError();
        assertEquals(before + 2, DOWNSTREAM.always500Count(), "both calls should reach downstream");

        // Next request must be fast-failed by the breaker WITHOUT reaching downstream
        webTestClient.get().uri("/e2e/always-500")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertEquals(before + 2, DOWNSTREAM.always500Count(),
                "open circuit breaker must not forward the request to downstream");
    }

    // ============================================================
    // Response cache
    // ============================================================

    /**
     * Regression guard for the cache-ordering bug: at order 50 (AFTER SCG's
     * NettyWriteResponseFilter, -1) the filter's response decorator was never on the
     * client-write path, so cache.put never ran and every request went back to origin.
     * Driven over the wire through the real Netty stack, which a unit test cannot cover.
     */
    @Test
    @DisplayName("Response cache: repeated GET is served from cache and replays the captured body")
    void responseCache_servesRepeatedRequestFromCache() throws Exception {
        // Nonce keeps the entry unique to this run; the downstream echoes its call number
        // in the body, so a HIT is distinguishable from a fresh origin call.
        String uri = "/e2e/cacheable?nonce=" + System.nanoTime();
        int before = DOWNSTREAM.cacheableCount();

        String firstBody = webTestClient.get().uri(uri)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("X-Cache")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // The entry is stored from writeWith's doFinally, which Reactor runs only AFTER
        // completing the downstream write - poll briefly rather than assume ordering.
        String lastBody = null;
        boolean hit = false;
        int attempts = 0;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            attempts++;
            var result = webTestClient.get().uri(uri)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith("application/json")
                    .returnResult(String.class);
            hit = "HIT".equals(result.getResponseHeaders().getFirst("X-Cache"));
            lastBody = result.getResponseBody().blockFirst();
            if (hit) {
                break;
            }
            Thread.sleep(50);
        }

        assertTrue(hit, "the repeated GET must eventually be served from cache (X-Cache: HIT)");
        assertEquals(firstBody, lastBody, "a cache hit must replay the body captured on the miss");
        assertEquals(before + attempts, DOWNSTREAM.cacheableCount(),
                "only the non-cached attempts should reach downstream");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void registerRoute(String routeId, String pathPattern, String downstreamPath) {
        RouteDefinition route = new RouteDefinition();
        route.setId(routeId);
        route.setUri(URI.create("http://127.0.0.1:" + DOWNSTREAM.port()));
        route.setPredicates(List.of(new PredicateDefinition("Path=" + pathPattern)));
        if (downstreamPath != null) {
            route.setFilters(List.of(new FilterDefinition(
                    "RewritePath=" + pathPattern + "," + downstreamPath)));
        }
        routeManager.putRoute(routeId, route);
    }

    private void registerStrategy(String strategyId, String type, String routeId, Map<String, Object> config) {
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setStrategyId(strategyId);
        strategy.setStrategyName(strategyId);
        strategy.setStrategyType(type);
        strategy.setScope(StrategyDefinition.SCOPE_ROUTE);
        strategy.setRouteId(routeId);
        strategy.setPriority(100);
        strategy.setEnabled(true);
        strategy.setConfig(config);
        strategyManager.putStrategy(strategyId, strategy);
    }

    /**
     * Route refresh is asynchronous (RefreshRoutesEvent); poll until the route answers.
     */
    private void waitForRoute(String uriPath) {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            try {
                int status = webTestClient.get().uri(uriPath)
                        .exchange()
                        .returnResult(String.class).getStatus().value();
                if (status != 404) {
                    return;
                }
            } catch (Exception ignored) {
                // context may still be wiring routes; keep polling
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("Route for " + uriPath + " was not registered within 15s");
    }
}
