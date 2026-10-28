package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.filter.ratelimit.HybridRateLimiterFilter;
import com.leoli.gateway.filter.ratelimit.MultiDimRateLimiterFilter;
import com.leoli.gateway.license.LicenseFeatureHelper;
import com.leoli.gateway.limiter.*;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.MultiDimRateLimiterConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class RateLimitingRegressionTest {
    private final StrategyManager strategies = mock(StrategyManager.class);
    private final ShadowQuotaManager shadows = mock(ShadowQuotaManager.class);
    private final RedisHealthChecker health = mock(RedisHealthChecker.class);
    private final DistributedRateLimiter redis = mock(DistributedRateLimiter.class);
    private final LicenseFeatureHelper license = mock(LicenseFeatureHelper.class);
    private final ClientIpResolver ips = mock(ClientIpResolver.class);
    private final GatewayFilterChain backend = exchange -> Mono.empty();
    private HybridRateLimiterFilter hybrid;
    private MultiDimRateLimiterFilter multi;

    @BeforeEach void setup() {
        when(ips.resolve(any())).thenReturn("127.0.0.1");
        when(license.hasFeature(any())).thenReturn(true);
        hybrid = new HybridRateLimiterFilter(strategies, shadows, license, ips, health, redis);
        ReflectionTestUtils.setField(hybrid, "shadowQuotaEnabled", false);
        multi = new MultiDimRateLimiterFilter();
        ReflectionTestUtils.setField(multi, "strategyManager", strategies);
        ReflectionTestUtils.setField(multi, "shadowQuotaManager", shadows);
        ReflectionTestUtils.setField(multi, "clientIpResolver", ips);
        ReflectionTestUtils.setField(multi, "objectMapper", new ObjectMapper());
    }

    private MockServerWebExchange exchange(String route, String userHeader) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test")
                .header("X-User-Id", userHeader));
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR,
                Route.async().id(route).uri("http://localhost").predicate(e -> true).build());
        return exchange;
    }

    private Map<String, Object> hybridConfig(int qps, int burst, long window) {
        Map<String, Object> config = new HashMap<>(Map.of("enabled", true, "qps", qps,
                "burstCapacity", burst, "windowSizeMs", window, "keyType", "route",
                "strategyId", "shared", "scope", "GLOBAL"));
        when(strategies.getRateLimiterConfig(anyString())).thenReturn(config);
        return config;
    }

    private MultiDimRateLimiterConfig multiConfig() {
        var config = new MultiDimRateLimiterConfig();
        config.setEnabled(true);
        when(strategies.getConfig(anyString(), eq(StrategyDefinition.TYPE_MULTI_DIM_RATE_LIMITER),
                eq(MultiDimRateLimiterConfig.class))).thenReturn(config);
        return config;
    }

    @Test void zeroExtraBurstAllowsExactlyTheConfiguredQuota() {
        hybridConfig(1, 0, 60_000);
        var first = exchange("a", "u"); hybrid.filter(first, backend).block();
        var second = exchange("a", "u"); hybrid.filter(second, backend).block();
        assertNull(first.getResponse().getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
        assertEquals("1", second.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));
        assertEquals("60", second.getResponse().getHeaders().getFirst("Retry-After"));
    }

    @Test void changingBurstAndWindowWithUnchangedQpsTakesEffectImmediately() {
        var config = hybridConfig(1, 2, 60_000);
        hybrid.filter(exchange("a", "u"), backend).block();
        config.put("burstCapacity", 0);
        config.put("windowSizeMs", 120_000);
        hybrid.filter(exchange("a", "u"), backend).block();
        var rejected = exchange("a", "u"); hybrid.filter(rejected, backend).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
        assertEquals("120", rejected.getResponse().getHeaders().getFirst("Retry-After"));
    }

    @Test void concurrentColdCacheUsesOneGlobalCounterAcrossRoutes() throws Exception {
        hybridConfig(17, 0, 60_000);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            CountDownLatch start = new CountDownLatch(1);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 96; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    start.await();
                    var exchange = exchange("r" + index % 3, "u");
                    hybrid.filter(exchange, backend).block();
                    return exchange.getResponse().getStatusCode() == null;
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) allowed++;
            assertEquals(17, allowed);
        }
    }

    @Test void globalShadowQuotaIsNotMultipliedByRouteCount() {
        hybridConfig(10, 0, 60_000);
        ReflectionTestUtils.setField(hybrid, "redisLimitEnabled", true);
        ReflectionTestUtils.setField(hybrid, "shadowQuotaEnabled", true);
        when(shadows.getShadowQuota(anyString(), anyInt())).thenReturn(1L);
        hybrid.filter(exchange("a", "u"), backend).block();
        var rejected = exchange("b", "u"); hybrid.filter(rejected, backend).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
    }

    @Test void explicitlyLocalModeDoesNotUseRedisOutageShadowQuota() {
        hybridConfig(2, 0, 60_000);
        ReflectionTestUtils.setField(hybrid, "shadowQuotaEnabled", true);
        hybrid.filter(exchange("a", "u"), backend).block();
        var second = exchange("a", "u"); hybrid.filter(second, backend).block();
        assertNull(second.getResponse().getStatusCode());
        verify(shadows, never()).getShadowQuota(anyString(), anyInt());
    }

    @Test void immediateRedisFailureUsesShadowQuotaBeforeHealthTickCatchesUp() {
        hybridConfig(10, 0, 60_000);
        ReflectionTestUtils.setField(hybrid, "redisLimitEnabled", true);
        ReflectionTestUtils.setField(hybrid, "shadowQuotaEnabled", true);
        when(shadows.isRedisHealthy()).thenReturn(true);
        when(shadows.getRecoveryProgress()).thenReturn(100);
        when(shadows.getShadowQuota(anyString(), anyInt())).thenReturn(1L);
        when(health.isRedisAvailableForRateLimiting()).thenReturn(true);
        when(redis.tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(Mono.just(RateLimitResult.fallback("connection lost")));
        hybrid.filter(exchange("a", "u"), backend).block();
        var rejected = exchange("b", "u"); hybrid.filter(rejected, backend).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
    }

    @Test void missingTenantCannotSkipTheOnlyEnabledDimension() {
        var config = multiConfig();
        config.getTenantQuota().setEnabled(true);
        config.getTenantQuota().setQps(1); config.getTenantQuota().setBurstCapacity(0);
        config.getTenantQuota().setWindowSizeMs(60_000);
        multi.filter(exchange("a", "u"), backend).block();
        var rejected = exchange("a", "v"); multi.filter(rejected, backend).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
        assertEquals("TENANT", rejected.getResponse().getHeaders().getFirst("X-RateLimit-Limit-Level"));
    }

    @Test void rotatingUserHeaderDoesNotBypassCombinedIdentityLimit() {
        var config = multiConfig(); config.setUserIdSource("COMBINED");
        config.getUserQuota().setEnabled(true);
        config.getUserQuota().setQps(1); config.getUserQuota().setBurstCapacity(0);
        config.getUserQuota().setWindowSizeMs(60_000);
        multi.filter(exchange("a", "first"), backend).block();
        var rejected = exchange("a", "second"); multi.filter(rejected, backend).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
    }

    @Test void globalMultiDimensionQuotaIsSharedAcrossRoutesAndStrategiesAreIsolated() {
        var config = multiConfig(); config.setScope("GLOBAL"); config.setStrategyId("one");
        config.getGlobalQuota().setEnabled(true);
        config.getGlobalQuota().setQps(1); config.getGlobalQuota().setBurstCapacity(0);
        config.getGlobalQuota().setWindowSizeMs(60_000);
        multi.filter(exchange("a", "u"), backend).block();
        var rejected = exchange("b", "u"); multi.filter(rejected, backend).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
        config.setStrategyId("two");
        var allowed = exchange("b", "u"); multi.filter(allowed, backend).block();
        assertNull(allowed.getResponse().getStatusCode());
    }

    @Test void switchingQuotaBackDoesNotResurrectAnOldExhaustedWindow() {
        var config = multiConfig(); config.getGlobalQuota().setEnabled(true);
        config.getGlobalQuota().setQps(1); config.getGlobalQuota().setBurstCapacity(0);
        config.getGlobalQuota().setWindowSizeMs(60_000);
        multi.filter(exchange("a", "u"), backend).block();
        config.getGlobalQuota().setQps(2);
        multi.filter(exchange("a", "u"), backend).block();
        config.getGlobalQuota().setQps(1);
        var allowed = exchange("a", "u"); multi.filter(allowed, backend).block();
        assertNull(allowed.getResponse().getStatusCode());
    }

    @Test void responseReportsCapacitySeparatelyFromRemaining() {
        var config = multiConfig(); config.getGlobalQuota().setEnabled(true);
        config.getGlobalQuota().setQps(2); config.getGlobalQuota().setBurstCapacity(1);
        var allowed = exchange("a", "u"); multi.filter(allowed, backend).block();
        assertEquals("3", allowed.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));
        assertEquals("2", allowed.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    @Test void combinedTenantSourceIncludesVerifiedJwtClaims() {
        var config = multiConfig(); config.getTenantQuota().setEnabled(true);
        config.getTenantQuota().setQps(1); config.getTenantQuota().setBurstCapacity(0);
        config.getTenantQuota().setWindowSizeMs(60_000);
        var first = exchange("a", "u");
        first.getAttributes().put("jwt_claims", Map.of("tenant_id", "verified"));
        multi.filter(first, backend).block();
        var other = exchange("a", "u");
        other.getAttributes().put("jwt_claims", Map.of("tenant_id", "other"));
        multi.filter(other, backend).block();
        assertNull(other.getResponse().getStatusCode());
        var rejected = exchange("a", "u");
        rejected.getAttributes().put("jwt_claims", Map.of("tenant_id", "verified"));
        multi.filter(rejected, backend).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
    }

    @Test void redisFailureMultiDimensionQuotaIsDividedAmongGatewayNodes() {
        var config = multiConfig(); config.getGlobalQuota().setEnabled(true);
        config.getGlobalQuota().setQps(4); config.getGlobalQuota().setBurstCapacity(0);
        config.getGlobalQuota().setWindowSizeMs(60_000);
        ReflectionTestUtils.setField(multi, "redisLimitEnabled", true);
        ReflectionTestUtils.setField(multi, "shadowQuotaEnabled", true);
        when(shadows.getCachedNodeCount()).thenReturn(4);
        multi.filter(exchange("a", "u"), backend).block();
        var rejected = exchange("a", "u"); multi.filter(rejected, backend).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
    }
}
