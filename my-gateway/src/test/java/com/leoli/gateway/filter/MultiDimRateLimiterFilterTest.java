package com.leoli.gateway.filter;

import com.leoli.gateway.limiter.DistributedRateLimiter;
import com.leoli.gateway.limiter.RateLimitResult;
import com.leoli.gateway.limiter.RedisHealthChecker;
import com.leoli.gateway.limiter.ShadowQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MultiDimRateLimiterFilter.
 * Tests multi-dimensional rate limiting with Global, Tenant, User, and IP levels.
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
    private GatewayFilterChain chain;

    @InjectMocks
    private MultiDimRateLimiterFilter filter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(filter, "redisLimitEnabled", true);
        ReflectionTestUtils.setField(filter, "shadowQuotaEnabled", true);
    }

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Filter should pass through when config is null")
        void testFilter_nullConfig() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.getMultiDimRateLimiterConfig(anyString())).thenReturn(null);
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
            Map<String, Object> config = new HashMap<>();
            config.put("enabled", false);
            when(strategyManager.getMultiDimRateLimiterConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(exchange);
        }
    }

    @Nested
    @DisplayName("Multi-Dimension Rate Limiting Tests")
    class MultiDimensionTests {

        @Test
        @DisplayName("Should allow request when all dimensions pass")
        void testFilter_allDimensionsPass() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createMultiDimConfig(true, 100, true, 50, true, 10, true, 5);
            when(strategyManager.getMultiDimRateLimiterConfig(anyString())).thenReturn(config);
            when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            when(distributedRateLimiter.tryAcquireWithFallback(anyString(), anyInt(), anyLong()))
                    .thenReturn(RateLimitResult.allowed(99));
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

            Map<String, Object> config = createMultiDimConfig(true, 100, false, 0, false, 0, false, 0);
            when(strategyManager.getMultiDimRateLimiterConfig(anyString())).thenReturn(config);
            when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            when(distributedRateLimiter.tryAcquireWithFallback(anyString(), anyInt(), anyLong()))
                    .thenReturn(RateLimitResult.denied(0));

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should fallback to local limiter when Redis unavailable")
        void testFilter_fallbackToLocal() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createMultiDimConfig(true, 100, false, 0, false, 0, false, 0);
            when(strategyManager.getMultiDimRateLimiterConfig(anyString())).thenReturn(config);
            when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(false);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(exchange);
        }
    }

    @Nested
    @DisplayName("Dimension Key Extraction Tests")
    class KeyExtractionTests {

        @Test
        @DisplayName("Should extract tenant from API key metadata")
        void testExtractTenantFromApiKey() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            exchange.getAttributes().put("api_key_metadata", Map.of("tenantId", "tenant-001"));

            Map<String, Object> config = createMultiDimConfig(false, 0, true, 50, false, 0, false, 0);
            when(strategyManager.getMultiDimRateLimiterConfig(anyString())).thenReturn(config);
            when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            when(distributedRateLimiter.tryAcquireWithFallback(anyString(), anyInt(), anyLong()))
                    .thenReturn(RateLimitResult.allowed(49));
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(distributedRateLimiter).tryAcquireWithFallback(contains("tenant-001"), eq(50), anyLong());
        }

        @Test
        @DisplayName("Should extract user from JWT subject")
        void testExtractUserFromJwt() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            exchange.getAttributes().put("jwt_subject", "user-123");

            Map<String, Object> config = createMultiDimConfig(false, 0, false, 0, true, 10, false, 0);
            when(strategyManager.getMultiDimRateLimiterConfig(anyString())).thenReturn(config);
            when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            when(distributedRateLimiter.tryAcquireWithFallback(anyString(), anyInt(), anyLong()))
                    .thenReturn(RateLimitResult.allowed(9));
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(distributedRateLimiter).tryAcquireWithFallback(contains("user-123"), eq(10), anyLong());
        }
    }

    @Nested
    @DisplayName("Filter Order Test")
    class OrderTest {

        @Test
        @DisplayName("Filter order should be HIGHEST_PRECEDENCE + 21")
        void testFilterOrder() {
            assertTrue(filter.getOrder() < 0, "Filter should have high priority (negative order)");
            assertEquals(-2147483627, filter.getOrder());
        }
    }

    // ============== Helper Methods ==============

    private MockServerWebExchange createMockExchange(String path, String routeId) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path)
                .header("X-Forwarded-For", "192.168.1.100")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId", routeId);
        return exchange;
    }

    private Map<String, Object> createMultiDimConfig(boolean globalEnabled, int globalQps,
                                                      boolean tenantEnabled, int tenantQps,
                                                      boolean userEnabled, int userQps,
                                                      boolean ipEnabled, int ipQps) {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        config.put("rejectStrategy", "FIRST_HIT");
        config.put("keySource", "COMBINED");

        // Global quota
        Map<String, Object> globalQuota = new HashMap<>();
        globalQuota.put("enabled", globalEnabled);
        globalQuota.put("qps", globalQps);
        globalQuota.put("burstCapacity", globalQps * 2);
        globalQuota.put("windowSizeMs", 1000);
        config.put("globalQuota", globalQuota);

        // Tenant quota
        Map<String, Object> tenantQuota = new HashMap<>();
        tenantQuota.put("enabled", tenantEnabled);
        tenantQuota.put("qps", tenantQps);
        tenantQuota.put("burstCapacity", tenantQps * 2);
        tenantQuota.put("windowSizeMs", 1000);
        config.put("tenantQuota", tenantQuota);

        // User quota
        Map<String, Object> userQuota = new HashMap<>();
        userQuota.put("enabled", userEnabled);
        userQuota.put("qps", userQps);
        userQuota.put("burstCapacity", userQps * 2);
        userQuota.put("windowSizeMs", 1000);
        config.put("userQuota", userQuota);

        // IP quota
        Map<String, Object> ipQuota = new HashMap<>();
        ipQuota.put("enabled", ipEnabled);
        ipQuota.put("qps", ipQps);
        ipQuota.put("burstCapacity", ipQps * 2);
        ipQuota.put("windowSizeMs", 1000);
        config.put("ipQuota", ipQuota);

        return config;
    }
}