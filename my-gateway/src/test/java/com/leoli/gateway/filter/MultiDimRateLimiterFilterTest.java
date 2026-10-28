package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.ratelimit.MultiDimRateLimiterFilter;
import com.leoli.gateway.limiter.DistributedRateLimiter;
import com.leoli.gateway.limiter.RateLimitResult;
import com.leoli.gateway.limiter.RedisHealthChecker;
import com.leoli.gateway.limiter.ShadowQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.MultiDimRateLimiterConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MultiDimRateLimiterFilter.
 * <p>
 * Regression coverage for the reactive refactor (FIX: blocking Redis on EventLoop):
 * dimensions are checked through tryAcquireReactive and the reactive pipeline must
 * keep the original FIRST_HIT short-circuit / ALL_HIT evaluate-all semantics.
 */
@ExtendWith(MockitoExtension.class)
class MultiDimRateLimiterFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private RedisHealthChecker redisHealthChecker;

    @Mock
    private DistributedRateLimiter distributedRateLimiter;

    @Mock
    private ShadowQuotaManager shadowQuotaManager;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private GatewayFilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MultiDimRateLimiterFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MultiDimRateLimiterFilter();
        ReflectionTestUtils.setField(filter, "strategyManager", strategyManager);
        ReflectionTestUtils.setField(filter, "redisHealthChecker", redisHealthChecker);
        ReflectionTestUtils.setField(filter, "distributedRateLimiter", distributedRateLimiter);
        ReflectionTestUtils.setField(filter, "shadowQuotaManager", shadowQuotaManager);
        ReflectionTestUtils.setField(filter, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(filter, "clientIpResolver", clientIpResolver);
        ReflectionTestUtils.setField(filter, "redisLimitEnabled", true);
        ReflectionTestUtils.setField(filter, "shadowQuotaEnabled", true);
        // Trusted-proxy-aware resolution replaces the old spoofable XFF read; the mock
        // exchanges carry X-Forwarded-For: 192.168.1.100, so keep the IP-dimension key stable.
        lenient().when(clientIpResolver.resolve(any())).thenReturn("192.168.1.100");
    }

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Filter should pass through when config is null")
        void testFilter_nullConfig() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_MULTI_DIM_RATE_LIMITER),
                    eq(MultiDimRateLimiterConfig.class))).thenReturn(null);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Filter should pass through when disabled")
        void testFilter_disabledConfig() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            MultiDimRateLimiterConfig config = createMultiDimConfig(true, 100, false, 0, false, 0, false, 0);
            config.setEnabled(false);
            when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_MULTI_DIM_RATE_LIMITER),
                    eq(MultiDimRateLimiterConfig.class))).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(exchange);
        }
    }

    @Nested
    @DisplayName("Multi-Dimension Rate Limiting Tests (reactive Redis path)")
    class MultiDimensionTests {

        @Test
        @DisplayName("Should allow request when all dimensions pass")
        void testFilter_allDimensionsPass() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            MultiDimRateLimiterConfig config = createMultiDimConfig(true, 100, false, 0, false, 0, true, 5);
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Mono.just(RateLimitResult.allowed(99)));
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should reject request when global limit exceeded")
        void testFilter_globalLimitExceeded() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            MultiDimRateLimiterConfig config = createMultiDimConfig(true, 100, false, 0, false, 0, false, 0);
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Mono.just(RateLimitResult.denied(0)));

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should fallback to local limiter when Redis result signals fallback")
        void testFilter_fallbackToLocalOnRedisFailure() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            MultiDimRateLimiterConfig config = createMultiDimConfig(true, 100, false, 0, false, 0, false, 0);
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Mono.just(RateLimitResult.fallback("Redis down")));
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should use local limiter when Redis health check reports unavailable")
        void testFilter_localLimiterWhenRedisUnavailable() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            MultiDimRateLimiterConfig config = createMultiDimConfig(true, 100, false, 0, false, 0, false, 0);
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(false);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(exchange);
            verifyNoInteractions(distributedRateLimiter);
        }
    }

    @Nested
    @DisplayName("Reject Strategy Semantics (reactive pipeline regression)")
    class RejectStrategyTests {

        @Test
        @DisplayName("FIRST_HIT should short-circuit after the first denied dimension")
        void testFirstHit_shortCircuits() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            // Global (denied) + IP (would allow): FIRST_HIT must not evaluate IP
            MultiDimRateLimiterConfig config = createMultiDimConfig(true, 100, false, 0, false, 0, true, 5);
            config.setRejectStrategy("FIRST_HIT");
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Mono.just(RateLimitResult.denied(0)));

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
            // Only the GLOBAL dimension was evaluated before short-circuiting
            verify(distributedRateLimiter, times(1))
                    .tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong());
            assertEquals("GLOBAL", exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit-Level"));
        }

        @Test
        @DisplayName("ALL_HIT should evaluate every dimension even after a denial")
        void testAllHit_evaluatesAllDimensions() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            // Global (denied) + IP: ALL_HIT must evaluate both dimensions
            MultiDimRateLimiterConfig config = createMultiDimConfig(true, 100, false, 0, false, 0, true, 5);
            config.setRejectStrategy("ALL_HIT");
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Mono.just(RateLimitResult.denied(0)));

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
            // Both GLOBAL and IP dimensions were evaluated
            verify(distributedRateLimiter, times(2))
                    .tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong());
        }
    }

    @Nested
    @DisplayName("Local Limiter Behavior")
    class LocalLimiterTests {

        @Test
        @DisplayName("Local window should reject after quota exhausted")
        void testLocalWindow_quotaExhausted() {
            MultiDimRateLimiterConfig config = createMultiDimConfig(true, 1, false, 0, false, 0, false, 0);
            config.setGlobalQuota(quota(true, 1, 1, 1000));
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(false);

            // Window(qps=1, burst=1) allows steady + burst = 2 requests
            when(chain.filter(any())).thenReturn(Mono.empty());
            for (int i = 0; i < 2; i++) {
                MockServerWebExchange allowed = createMockExchange("/api/test", "test-route");
                StepVerifier.create(filter.filter(allowed, chain))
                        .expectComplete()
                        .verify();
            }
            verify(chain, times(2)).filter(any());

            // Third request within the same window must be rejected
            MockServerWebExchange third = createMockExchange("/api/test", "test-route");
            StepVerifier.create(filter.filter(third, chain))
                    .expectComplete()
                    .verify();
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
            verify(chain, times(2)).filter(any());
        }
    }

    @Nested
    @DisplayName("Dimension Key Extraction Tests")
    class KeyExtractionTests {

        @Test
        @DisplayName("Should extract tenant from API key metadata")
        void testExtractTenantFromApiKey() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            exchange.getAttributes().put("api_key_metadata", java.util.Map.of("tenantId", "tenant-001"));

            MultiDimRateLimiterConfig config = createMultiDimConfig(false, 0, true, 50, false, 0, false, 0);
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Mono.just(RateLimitResult.allowed(49)));
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(distributedRateLimiter).tryAcquireReactive(contains("tenant-001"), eq(50), anyInt(), anyLong());
        }

        @Test
        @DisplayName("Should extract user from JWT subject")
        void testExtractUserFromJwt() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            exchange.getAttributes().put("jwt_subject", "user-123");

            MultiDimRateLimiterConfig config = createMultiDimConfig(false, 0, false, 0, true, 10, false, 0);
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Mono.just(RateLimitResult.allowed(9)));
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(distributedRateLimiter).tryAcquireReactive(contains("user-123"), eq(10), anyInt(), anyLong());
        }

        @Test
        @DisplayName("A client-supplied X-Tenant-Id alone must not create a tenant dimension")
        void testTenantHeaderAloneIsNotTrusted() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-Id", "forged-tenant")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(
                    "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId",
                    "test-route");

            MultiDimRateLimiterConfig config = createMultiDimConfig(false, 0, true, 50, false, 0, false, 0);
            stubConfig(config);
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireReactive(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Mono.just(RateLimitResult.allowed(49)));
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // No verified tenant -> charge the shared anonymous bucket, never the
            // client-selected tenant and never skip the configured dimension.
            verify(distributedRateLimiter)
                    .tryAcquireReactive(endsWith(":anonymous"), eq(50), anyInt(), anyLong());
        }
    }

    @Nested
    @DisplayName("Filter Order Test")
    class OrderTest {

        @Test
        @DisplayName("Filter order should match FilterOrderConstants.MULTI_DIM_RATE_LIMITER")
        void testFilterOrder() {
            assertEquals(FilterOrderConstants.MULTI_DIM_RATE_LIMITER, filter.getOrder());
        }
    }

    // ============== Helper Methods ==============

    private void stubConfig(MultiDimRateLimiterConfig config) {
        lenient().when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_MULTI_DIM_RATE_LIMITER),
                eq(MultiDimRateLimiterConfig.class))).thenReturn(config);
    }

    private MockServerWebExchange createMockExchange(String path, String routeId) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path)
                .header("X-Forwarded-For", "192.168.1.100")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId", routeId);
        return exchange;
    }

    private MultiDimRateLimiterConfig.QuotaConfig quota(boolean enabled, int qps, int burstCapacity, long windowSizeMs) {
        MultiDimRateLimiterConfig.QuotaConfig quota = new MultiDimRateLimiterConfig.QuotaConfig();
        quota.setEnabled(enabled);
        quota.setQps(qps);
        quota.setBurstCapacity(burstCapacity);
        quota.setWindowSizeMs(windowSizeMs);
        return quota;
    }

    private MultiDimRateLimiterConfig createMultiDimConfig(boolean globalEnabled, int globalQps,
                                                            boolean tenantEnabled, int tenantQps,
                                                            boolean userEnabled, int userQps,
                                                            boolean ipEnabled, int ipQps) {
        MultiDimRateLimiterConfig config = new MultiDimRateLimiterConfig();
        config.setEnabled(true);
        config.setRejectStrategy("FIRST_HIT");
        config.setKeySource("COMBINED");
        config.setGlobalQuota(quota(globalEnabled, globalQps, Math.max(globalQps * 2, 1), 1000));
        config.setTenantQuota(quota(tenantEnabled, tenantQps, Math.max(tenantQps * 2, 1), 1000));
        config.setUserQuota(quota(userEnabled, userQps, Math.max(userQps * 2, 1), 1000));
        config.setIpQuota(quota(ipEnabled, ipQps, Math.max(ipQps * 2, 1), 1000));
        return config;
    }
}
