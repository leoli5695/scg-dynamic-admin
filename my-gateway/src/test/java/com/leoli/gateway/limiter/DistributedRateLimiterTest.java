package com.leoli.gateway.limiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DistributedRateLimiter.
 * Tests Redis-based distributed rate limiting with Lua scripts.
 */
@ExtendWith(MockitoExtension.class)
class DistributedRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private DefaultRedisScript<Long> rateLimitScript;

    @InjectMocks
    private DistributedRateLimiter rateLimiter;

    // ============================================================
    // tryAcquireWithFallback Tests
    // ============================================================

    @Nested
    @DisplayName("tryAcquireWithFallback Tests")
    class TryAcquireTests {

        @Test
        @DisplayName("Should return allowed when Redis permits request")
        void testTryAcquireWithFallback_allowed() {
            // Mock Redis script execution returns 1 (allowed)
            lenient().when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

            RateLimitResult result = rateLimiter.tryAcquireWithFallback(
                    "rate_limit:test:route:127.0.0.1", 100, 1000);

            assertTrue(result.isAllowed());
            assertTrue(result.isRedisAvailable());
            assertFalse(result.isShouldFallback());
            assertEquals(100, result.getRemainingRequests());
        }

        @Test
        @DisplayName("Should return denied when Redis rejects request")
        void testTryAcquireWithFallback_denied() {
            // Mock Redis script execution returns 0 (denied)
            lenient().when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

            RateLimitResult result = rateLimiter.tryAcquireWithFallback(
                    "rate_limit:test:route:127.0.0.1", 100, 1000);

            assertFalse(result.isAllowed());
            assertTrue(result.isRedisAvailable());
            assertFalse(result.isShouldFallback());
            assertEquals(0, result.getRemainingRequests());
        }

        @Test
        @DisplayName("Should return fallback when Redis template is null")
        void testTryAcquireWithFallback_templateNull() {
            DistributedRateLimiter limiter = new DistributedRateLimiter();
            // redisTemplate is null by default

            RateLimitResult result = limiter.tryAcquireWithFallback(
                    "rate_limit:test:route:127.0.0.1", 100, 1000);

            assertFalse(result.isAllowed());
            assertFalse(result.isRedisAvailable());
            assertTrue(result.isShouldFallback());
            assertNotNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("Should return fallback when Redis script returns null")
        void testTryAcquireWithFallback_nullResult() {
            lenient().when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

            RateLimitResult result = rateLimiter.tryAcquireWithFallback(
                    "rate_limit:test:route:127.0.0.1", 100, 1000);

            assertFalse(result.isAllowed());
            assertFalse(result.isRedisAvailable());
            assertTrue(result.isShouldFallback());
            assertTrue(result.getErrorMessage().contains("null"));
        }

        @Test
        @DisplayName("Should return fallback when Redis throws exception")
        void testTryAcquireWithFallback_redisException() {
            lenient().when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                    .thenThrow(new RuntimeException("Redis connection timeout"));

            RateLimitResult result = rateLimiter.tryAcquireWithFallback(
                    "rate_limit:test:route:127.0.0.1", 100, 1000);

            assertFalse(result.isAllowed());
            assertFalse(result.isRedisAvailable());
            assertTrue(result.isShouldFallback());
            assertTrue(result.getErrorMessage().contains("timeout"));
        }
    }

    // ============================================================
    // isRedisAvailable Tests
    // ============================================================

    @Nested
    @DisplayName("isRedisAvailable Tests")
    class IsRedisAvailableTests {

        @Test
        @DisplayName("Should return false when template is null")
        void testIsRedisAvailable_nullTemplate() {
            DistributedRateLimiter limiter = new DistributedRateLimiter();

            assertFalse(limiter.isRedisAvailable());
        }

        @Test
        @DisplayName("Should return true when Redis is connected")
        void testIsRedisAvailable_connected() {
            when(redisTemplate.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
            when(redisTemplate.opsForValue().get(anyString())).thenReturn("ok");

            assertTrue(rateLimiter.isRedisAvailable());
        }

        @Test
        @DisplayName("Should return false when Redis throws exception")
        void testIsRedisAvailable_disconnected() {
            when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Connection refused"));

            assertFalse(rateLimiter.isRedisAvailable());
        }
    }

    // ============================================================
    // RateLimitResult Tests
    // ============================================================

    @Nested
    @DisplayName("RateLimitResult Tests")
    class RateLimitResultTests {

        @Test
        @DisplayName("allowed() should create correct result")
        void testRateLimitResult_allowed() {
            RateLimitResult result = RateLimitResult.allowed(99);

            assertTrue(result.isAllowed());
            assertTrue(result.isRedisAvailable());
            assertFalse(result.isShouldFallback());
            assertEquals(99, result.getRemainingRequests());
            assertNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("denied() should create correct result")
        void testRateLimitResult_denied() {
            RateLimitResult result = RateLimitResult.denied(0);

            assertFalse(result.isAllowed());
            assertTrue(result.isRedisAvailable());
            assertFalse(result.isShouldFallback());
            assertEquals(0, result.getRemainingRequests());
        }

        @Test
        @DisplayName("fallback(String) should create correct result")
        void testRateLimitResult_fallbackString() {
            RateLimitResult result = RateLimitResult.fallback("Redis unavailable");

            assertFalse(result.isAllowed());
            assertFalse(result.isRedisAvailable());
            assertTrue(result.isShouldFallback());
            assertEquals(-1, result.getRemainingRequests());
            assertEquals("Redis unavailable", result.getErrorMessage());
        }

        @Test
        @DisplayName("fallback(Exception) should create correct result")
        void testRateLimitResult_fallbackException() {
            Exception ex = new RuntimeException("Connection refused");
            RateLimitResult result = RateLimitResult.fallback(ex);

            assertFalse(result.isAllowed());
            assertFalse(result.isRedisAvailable());
            assertTrue(result.isShouldFallback());
            assertEquals(-1, result.getRemainingRequests());
            assertEquals("Connection refused", result.getErrorMessage());
        }

        @Test
        @DisplayName("toString should contain key information")
        void testRateLimitResult_toString() {
            RateLimitResult result = RateLimitResult.allowed(50);
            String str = result.toString();

            assertTrue(str.contains("allowed=true"));
            assertTrue(str.contains("redisAvailable=true"));
            assertTrue(str.contains("remaining=50"));
        }
    }

    // ============================================================
    // Edge Cases Tests
    // ============================================================

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle empty key")
        void testEmptyKey() {
            lenient().when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

            RateLimitResult result = rateLimiter.tryAcquireWithFallback(
                    "", 100, 1000);

            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("Should handle zero maxRequests")
        void testZeroMaxRequests() {
            lenient().when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

            RateLimitResult result = rateLimiter.tryAcquireWithFallback(
                    "rate_limit:test", 0, 1000);

            assertFalse(result.isAllowed());
        }

        @Test
        @DisplayName("Should handle large window size")
        void testLargeWindowSize() {
            lenient().when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

            RateLimitResult result = rateLimiter.tryAcquireWithFallback(
                    "rate_limit:test", 100, 3600000L); // 1 hour

            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("Should handle special characters in key")
        void testSpecialCharactersInKey() {
            lenient().when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

            RateLimitResult result = rateLimiter.tryAcquireWithFallback(
                    "rate_limit:test:route:192.168.1.1:8080", 100, 1000);

            assertTrue(result.isAllowed());
        }
    }
}