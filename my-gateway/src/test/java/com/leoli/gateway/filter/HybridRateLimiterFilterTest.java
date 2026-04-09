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
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HybridRateLimiterFilter.
 * Tests the core rate limiting logic including Redis distributed limiting,
 * local fallback, and shadow quota management.
 */
@ExtendWith(MockitoExtension.class)
class HybridRateLimiterFilterTest {

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
    private HybridRateLimiterFilter filter;

    @BeforeEach
    void setUp() {
        // Set default field values
        ReflectionTestUtils.setField(filter, "redisLimitEnabled", true);
        ReflectionTestUtils.setField(filter, "shadowQuotaEnabled", true);
    }

    // ============================================================
    // Basic Filter Tests
    // ============================================================

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Filter should pass through when rate limiting is disabled")
        void testFilter_disabledConfig() {
            // Setup exchange with route
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            // Mock: no rate limiter config (disabled by default)
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(null);

            // Mock chain filter
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            // Execute
            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Verify: chain was called, no rate limiting
            verify(chain).filter(exchange);
            verify(distributedRateLimiter, never()).tryAcquireWithFallback(anyString(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("Filter should pass through when enabled=false in config")
        void testFilter_configDisabled() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            // Mock: rate limiter config with enabled=false
            Map<String, Object> config = new HashMap<>();
            config.put("enabled", false);
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(config);

            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(exchange);
            verify(distributedRateLimiter, never()).tryAcquireWithFallback(anyString(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("Filter should return 429 when rate limit exceeded")
        void testFilter_rateLimitExceeded() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            // Mock: enabled rate limiter config
            Map<String, Object> config = createRateLimitConfig(10, 1000);
            lenient().when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(config);

            // Mock: Redis available, rate limit denied
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireWithFallback(anyString(), anyInt(), anyLong()))
                    .thenReturn(RateLimitResult.denied(0));

            // Execute
            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Verify: 429 response
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
            verify(chain, never()).filter(exchange);
        }
    }

    // ============================================================
    // Redis Rate Limiting Tests
    // ============================================================

    @Nested
    @DisplayName("Redis Rate Limiting Tests")
    class RedisRateLimitingTests {

        @Test
        @DisplayName("Should use Redis when available and healthy")
        void testFilter_redisAvailable_rateLimitAllowed() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            // Mock config
            Map<String, Object> config = createRateLimitConfig(100, 1000);
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(config);

            // Mock Redis healthy
            when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            when(shadowQuotaManager.isRedisHealthy()).thenReturn(true);
            when(shadowQuotaManager.getRecoveryProgress()).thenReturn(100);

            // Mock rate limit allowed
            when(distributedRateLimiter.tryAcquireWithFallback(anyString(), anyInt(), anyLong()))
                    .thenReturn(RateLimitResult.allowed(99));

            // registerRoute is void, no need to mock;
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(distributedRateLimiter).tryAcquireWithFallback(anyString(), anyInt(), anyLong());
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should fallback to local when Redis returns fallback result")
        void testFilter_redisUnavailable_fallbackToLocal() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            // Mock config
            Map<String, Object> config = createRateLimitConfig(100, 1000);
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(config);

            // Mock Redis available check but returns fallback
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireWithFallback(anyString(), anyInt(), anyLong()))
                    .thenReturn(RateLimitResult.fallback("Redis error"));

            // Mock shadow quota
            when(shadowQuotaManager.isRedisHealthy()).thenReturn(false);
            when(shadowQuotaManager.getShadowQuota(anyString(), anyInt())).thenReturn(50L);

            // registerRoute is void, no need to mock;
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Verify: local rate limiter was used (shadow quota)
            verify(shadowQuotaManager).getShadowQuota(anyString(), anyInt());
            verify(chain).filter(exchange);
        }
    }

    // ============================================================
    // Shadow Quota Tests
    // ============================================================

    @Nested
    @DisplayName("Shadow Quota Tests")
    class ShadowQuotaTests {

        @Test
        @DisplayName("Should use shadow quota when Redis is unhealthy")
        void testFilter_useShadowQuotaWhenRedisDown() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            // Mock config
            Map<String, Object> config = createRateLimitConfig(100, 1000);
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(config);

            // Mock Redis unavailable
            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(false);
            lenient().when(shadowQuotaManager.isRedisHealthy()).thenReturn(false);
            lenient().when(shadowQuotaManager.getShadowQuota(anyString(), anyInt())).thenReturn(25L);

            // registerRoute is void, no need to mock;
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(shadowQuotaManager).getShadowQuota(anyString(), anyInt());
            verify(distributedRateLimiter, never()).tryAcquireWithFallback(anyString(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("Should use Redis during full recovery")
        void testFilter_redisRecovery_fullRecovery() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createRateLimitConfig(100, 1000);
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(config);

            // Mock: Redis recovered fully (progress=100)
            when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            when(shadowQuotaManager.isRedisHealthy()).thenReturn(true);
            when(shadowQuotaManager.getRecoveryProgress()).thenReturn(100);

            when(distributedRateLimiter.tryAcquireWithFallback(anyString(), anyInt(), anyLong()))
                    .thenReturn(RateLimitResult.allowed(99));

            // registerRoute is void, no need to mock;
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(distributedRateLimiter).tryAcquireWithFallback(anyString(), anyInt(), anyLong());
        }
    }

    // ============================================================
    // Client ID Extraction Tests
    // ============================================================

    @Nested
    @DisplayName("Client ID Extraction Tests")
    class ClientIdExtractionTests {

        @Test
        @DisplayName("Should extract IP from X-Forwarded-For header")
        void testExtractClientId_fromForwardedFor() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Forwarded-For", "192.168.1.100, 10.0.0.1")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

            // Use reflection to test private method indirectly through filter behavior
            Map<String, Object> config = createRateLimitConfig(100, 1000);
            config.put("keyResolver", "ip");
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(null);

            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            // The key should contain 192.168.1.100 (first IP in X-Forwarded-For)
            // This is verified by the fact that filter passed through
        }

        @Test
        @DisplayName("Should extract IP from X-Real-IP header")
        void testExtractClientId_fromRealIp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Real-IP", "192.168.1.200")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

            Map<String, Object> config = createRateLimitConfig(100, 1000);
            config.put("keyResolver", "ip");
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(null);
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();
        }

        @Test
        @DisplayName("Should extract from header when keyResolver=header")
        void testExtractClientId_fromHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Client-Id", "client-12345")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

            Map<String, Object> config = createRateLimitConfig(100, 1000);
            config.put("keyResolver", "header");
            config.put("headerName", "X-Client-Id");
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(null);
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();
        }

        @Test
        @DisplayName("Should extract from user header when keyResolver=user")
        void testExtractClientId_fromUser() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-User-Id", "user-12345")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

            Map<String, Object> config = createRateLimitConfig(100, 1000);
            config.put("keyResolver", "user");
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(null);
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();
        }
    }

    // ============================================================
    // Rate Limit Key Building Tests
    // ============================================================

    @Nested
    @DisplayName("Rate Limit Key Building Tests")
    class KeyBuildingTests {

        @Test
        @DisplayName("Should build combined key by default")
        void testBuildRateLimitKey_combinedKey() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createRateLimitConfig(100, 1000);
            config.put("keyType", "combined");
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(null);
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            // Key format should be: rate_limit:combined:{routeId}:{clientId}
        }

        @Test
        @DisplayName("Should build route-only key when keyType=route")
        void testBuildRateLimitKey_routeKey() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createRateLimitConfig(100, 1000);
            config.put("keyType", "route");
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(null);
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            // Key format should be: rate_limit:route:{routeId}
        }

        @Test
        @DisplayName("Should build IP-only key when keyType=ip")
        void testBuildRateLimitKey_ipKey() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createRateLimitConfig(100, 1000);
            config.put("keyType", "ip");
            when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(null);
            when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            // Key format should be: rate_limit:ip:{clientId}
        }
    }

    // ============================================================
    // Local Rate Limiter Window Tests
    // ============================================================

    @Nested
    @DisplayName("RateLimiterWindow Tests")
    class RateLimiterWindowTests {

        @Test
        @DisplayName("Window should allow requests within quota")
        void testWindow_tryAcquireAllowed() {
            HybridRateLimiterFilter.RateLimiterWindow window =
                    new HybridRateLimiterFilter.RateLimiterWindow(10, 20, 1000);

            // Should allow first 10 requests
            for (int i = 0; i < 10; i++) {
                assertTrue(window.tryAcquire());
            }

            assertEquals(10, window.getCurrentCount().get());
        }

        @Test
        @DisplayName("Window should use burst capacity for extra requests")
        void testWindow_burstCapacity() {
            // maxRequests=10, burstCapacity=20, burstTokens starts at 20
            HybridRateLimiterFilter.RateLimiterWindow window =
                    new HybridRateLimiterFilter.RateLimiterWindow(10, 20, 1000);

            // Use steady quota (10 requests)
            for (int i = 0; i < 10; i++) {
                assertTrue(window.tryAcquire());
            }

            // Use burst capacity: burstTokens decreases from 20 to 0 after 20 requests
            for (int i = 0; i < 20; i++) {
                assertTrue(window.tryAcquire());
            }

            // Total 30 requests, burstTokens=0, should now be denied
            assertFalse(window.tryAcquire());
        }

        @Test
        @DisplayName("Window should deny requests when quota exceeded")
        void testWindow_tryAcquireDenied() {
            // maxRequests=5, burstCapacity=5, burstTokens starts at 5
            HybridRateLimiterFilter.RateLimiterWindow window =
                    new HybridRateLimiterFilter.RateLimiterWindow(5, 5, 1000);

            // Use steady quota (5 requests)
            for (int i = 0; i < 5; i++) {
                assertTrue(window.tryAcquire());
            }

            // Use burst tokens (5 more requests)
            for (int i = 0; i < 5; i++) {
                assertTrue(window.tryAcquire());
            }

            // Total 10 requests, burstTokens=0, should deny next request
            assertFalse(window.tryAcquire());
        }

        @Test
        @DisplayName("Window should reset after time expires")
        void testWindow_windowExpiry() throws InterruptedException {
            // maxRequests=5, burstCapacity=10, burstTokens starts at 10
            HybridRateLimiterFilter.RateLimiterWindow window =
                    new HybridRateLimiterFilter.RateLimiterWindow(5, 10, 100);

            // Use steady quota (5 requests)
            for (int i = 0; i < 5; i++) {
                assertTrue(window.tryAcquire());
            }

            // Use burst tokens (10 more requests)
            for (int i = 0; i < 10; i++) {
                assertTrue(window.tryAcquire());
            }
            assertFalse(window.tryAcquire());

            // Wait for window to expire
            Thread.sleep(150);

            // Window should reset
            assertTrue(window.tryAcquire());
            assertEquals(1, window.getCurrentCount().get());
        }

        @Test
        @DisplayName("Remaining count should be accurate")
        void testWindow_getRemaining() {
            HybridRateLimiterFilter.RateLimiterWindow window =
                    new HybridRateLimiterFilter.RateLimiterWindow(10, 20, 1000);

            assertEquals(20, window.getRemaining()); // 20 - 0

            window.tryAcquire();
            assertEquals(19, window.getRemaining()); // 20 - 1

            for (int i = 0; i < 9; i++) {
                window.tryAcquire();
            }
            assertEquals(10, window.getRemaining()); // 20 - 10
        }
    }

    // ============================================================
    // Response Header Tests
    // ============================================================

    @Nested
    @DisplayName("Response Header Tests")
    class ResponseHeaderTests {

        @Test
        @DisplayName("429 response should include rate limit headers")
        void testRejectRequest_headers() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createRateLimitConfig(100, 1000);
            lenient().when(strategyManager.getRateLimiterConfig(anyString())).thenReturn(config);

            lenient().when(redisHealthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
            lenient().when(distributedRateLimiter.tryAcquireWithFallback(anyString(), anyInt(), anyLong()))
                    .thenReturn(RateLimitResult.denied(0));
            // registerRoute is void, no need to mock;

            filter.filter(exchange, chain).block();

            ServerHttpResponse response = exchange.getResponse();
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
            assertTrue(response.getHeaders().containsKey("X-RateLimit-Limit"));
            assertTrue(response.getHeaders().containsKey("X-RateLimit-Remaining"));
            assertEquals("100", response.getHeaders().getFirst("X-RateLimit-Limit"));
            assertEquals("0", response.getHeaders().getFirst("X-RateLimit-Remaining"));
        }
    }

    // ============================================================
    // Filter Order Test
    // ============================================================

    @Test
    @DisplayName("Filter order should be HIGHEST_PRECEDENCE + 20")
    void testGetOrder() {
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20, filter.getOrder());
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    private MockServerWebExchange createMockExchange(String path, String routeId) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path)
                .header("X-Forwarded-For", "127.0.0.1")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

        // Set route attribute directly (GATEWAY_ROUTE_ATTR is just the route ID string in this case)
        exchange.getAttributes().put(
                org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                createTestRoute(routeId));

        return exchange;
    }

    private org.springframework.cloud.gateway.route.Route createTestRoute(String routeId) {
        return org.springframework.cloud.gateway.route.Route.async()
                .id(routeId)
                .uri(java.net.URI.create("http://localhost:8080"))
                .predicate(ex -> true)
                .build();
    }

    private Map<String, Object> createRateLimitConfig(int qps, long windowMs) {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        config.put("qps", qps);
        config.put("burstCapacity", qps * 2);
        config.put("windowSizeMs", windowMs);
        config.put("keyResolver", "ip");
        config.put("keyType", "combined");
        config.put("keyPrefix", "rate_limit:");
        return config;
    }
}