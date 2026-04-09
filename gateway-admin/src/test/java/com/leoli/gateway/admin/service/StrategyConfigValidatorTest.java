package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.StrategyConfig;
import com.leoli.gateway.admin.service.StrategyConfigValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StrategyConfigValidator.
 * Tests validation of rate limiter, IP filter, circuit breaker, and timeout configs.
 *
 * @author leoli
 */
class StrategyConfigValidatorTest {

    private StrategyConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new StrategyConfigValidator();
    }

    @Nested
    @DisplayName("Rate Limiter Validation Tests")
    class RateLimiterValidationTests {

        @Test
        @DisplayName("Should validate valid rate limiter config")
        void shouldValidateValidConfig() {
            // Given
            StrategyConfig.RateLimiterConfig config = new StrategyConfig.RateLimiterConfig();
            config.setRouteId("route-1");
            config.setQps(100);
            config.setBurstCapacity(200);
            config.setTimeUnit("second");
            config.setKeyResolver("ip");

            // When
            ValidationResult result = validator.validateRateLimiter(config);

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should reject null config")
        void shouldRejectNullConfig() {
            // When
            ValidationResult result = validator.validateRateLimiter(null);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().contains("Rate limiter config is null"));
        }

        @Test
        @DisplayName("Should reject negative QPS")
        void shouldRejectNegativeQps() {
            // Given
            StrategyConfig.RateLimiterConfig config = new StrategyConfig.RateLimiterConfig();
            config.setQps(-10);

            // When
            ValidationResult result = validator.validateRateLimiter(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("qps must be positive")));
        }

        @Test
        @DisplayName("Should reject QPS exceeding maximum")
        void shouldRejectExcessiveQps() {
            // Given
            StrategyConfig.RateLimiterConfig config = new StrategyConfig.RateLimiterConfig();
            config.setQps(200000);

            // When
            ValidationResult result = validator.validateRateLimiter(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("qps exceeds maximum")));
        }

        @Test
        @DisplayName("Should reject negative burst capacity")
        void shouldRejectNegativeBurstCapacity() {
            // Given
            StrategyConfig.RateLimiterConfig config = new StrategyConfig.RateLimiterConfig();
            config.setQps(100);
            config.setBurstCapacity(-50);

            // When
            ValidationResult result = validator.validateRateLimiter(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("burstCapacity cannot be negative")));
        }

        @Test
        @DisplayName("Should reject invalid time unit")
        void shouldRejectInvalidTimeUnit() {
            // Given
            StrategyConfig.RateLimiterConfig config = new StrategyConfig.RateLimiterConfig();
            config.setQps(100);
            config.setBurstCapacity(200);
            config.setTimeUnit("invalid-unit");

            // When
            ValidationResult result = validator.validateRateLimiter(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Invalid timeUnit")));
        }

        @Test
        @DisplayName("Should reject invalid key resolver")
        void shouldRejectInvalidKeyResolver() {
            // Given
            StrategyConfig.RateLimiterConfig config = new StrategyConfig.RateLimiterConfig();
            config.setQps(100);
            config.setBurstCapacity(200);
            config.setKeyResolver("invalid-resolver");

            // When
            ValidationResult result = validator.validateRateLimiter(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Invalid keyResolver")));
        }

        @Test
        @DisplayName("Should reject invalid key type")
        void shouldRejectInvalidKeyType() {
            // Given
            StrategyConfig.RateLimiterConfig config = new StrategyConfig.RateLimiterConfig();
            config.setQps(100);
            config.setBurstCapacity(200);
            config.setKeyType("invalid-type");

            // When
            ValidationResult result = validator.validateRateLimiter(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Invalid keyType")));
        }
    }

    @Nested
    @DisplayName("IP Filter Validation Tests")
    class IPFilterValidationTests {

        @Test
        @DisplayName("Should validate valid IP filter config")
        void shouldValidateValidConfig() {
            // Given
            StrategyConfig.IPFilterConfig config = new StrategyConfig.IPFilterConfig();
            config.setRouteId("route-1");
            config.setMode("whitelist");
            config.setIpList(Arrays.asList("192.168.1.0/24", "10.0.0.1"));

            // When
            ValidationResult result = validator.validateIpFilter(config);

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should reject null config")
        void shouldRejectNullConfig() {
            // When
            ValidationResult result = validator.validateIpFilter(null);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().contains("IP filter config is null"));
        }

        @Test
        @DisplayName("Should reject invalid IPv4 address")
        void shouldRejectInvalidIPv4() {
            // Given
            StrategyConfig.IPFilterConfig config = new StrategyConfig.IPFilterConfig();
            config.setIpList(Arrays.asList("999.999.999.999"));

            // When
            ValidationResult result = validator.validateIpFilter(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Invalid IP/CIDR")));
        }

        @Test
        @DisplayName("Should reject invalid CIDR notation")
        void shouldRejectInvalidCidr() {
            // Given
            StrategyConfig.IPFilterConfig config = new StrategyConfig.IPFilterConfig();
            config.setIpList(Arrays.asList("192.168.1.0/35")); // Invalid prefix > 32

            // When
            ValidationResult result = validator.validateIpFilter(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Invalid IP/CIDR")));
        }

        @Test
        @DisplayName("Should validate IPv6 address")
        void shouldValidateIPv6Address() {
            // Given
            StrategyConfig.IPFilterConfig config = new StrategyConfig.IPFilterConfig();
            config.setIpList(Arrays.asList("::1", "2001:db8::1"));

            // When
            ValidationResult result = validator.validateIpFilter(config);

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should validate IPv6 CIDR notation")
        void shouldValidateIPv6Cidr() {
            // Given
            StrategyConfig.IPFilterConfig config = new StrategyConfig.IPFilterConfig();
            config.setIpList(Arrays.asList("2001:db8::/32"));

            // When
            ValidationResult result = validator.validateIpFilter(config);

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should reject invalid mode")
        void shouldRejectInvalidMode() {
            // Given
            StrategyConfig.IPFilterConfig config = new StrategyConfig.IPFilterConfig();
            config.setMode("invalid-mode");

            // When
            ValidationResult result = validator.validateIpFilter(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Invalid mode")));
        }

        @Test
        @DisplayName("Should validate empty IP list")
        void shouldValidateEmptyIpList() {
            // Given
            StrategyConfig.IPFilterConfig config = new StrategyConfig.IPFilterConfig();
            config.setIpList(List.of());

            // When
            ValidationResult result = validator.validateIpFilter(config);

            // Then
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Validation Tests")
    class CircuitBreakerValidationTests {

        @Test
        @DisplayName("Should validate valid circuit breaker config")
        void shouldValidateValidConfig() {
            // Given
            StrategyConfig.CircuitBreakerConfig config = new StrategyConfig.CircuitBreakerConfig();
            config.setRouteId("route-1");
            config.setFailureRateThreshold(50.0f);
            config.setSlowCallRateThreshold(80.0f);
            config.setSlowCallDurationThreshold(60000L);
            config.setWaitDurationInOpenState(30000L);
            config.setSlidingWindowSize(10);
            config.setMinimumNumberOfCalls(5);

            // When
            ValidationResult result = validator.validateCircuitBreaker(config);

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should reject null config")
        void shouldRejectNullConfig() {
            // When
            ValidationResult result = validator.validateCircuitBreaker(null);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().contains("Circuit breaker config is null"));
        }

        @Test
        @DisplayName("Should reject failure rate threshold out of range")
        void shouldRejectInvalidFailureRateThreshold() {
            // Given
            StrategyConfig.CircuitBreakerConfig config = new StrategyConfig.CircuitBreakerConfig();
            config.setFailureRateThreshold(150.0f); // > 100

            // When
            ValidationResult result = validator.validateCircuitBreaker(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("failureRateThreshold must be between 0 and 100")));
        }

        @Test
        @DisplayName("Should reject negative failure rate threshold")
        void shouldRejectNegativeFailureRateThreshold() {
            // Given
            StrategyConfig.CircuitBreakerConfig config = new StrategyConfig.CircuitBreakerConfig();
            config.setFailureRateThreshold(-10.0f);

            // When
            ValidationResult result = validator.validateCircuitBreaker(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("failureRateThreshold must be between 0 and 100")));
        }

        @Test
        @DisplayName("Should reject slow call duration threshold out of range")
        void shouldRejectInvalidSlowCallDurationThreshold() {
            // Given
            StrategyConfig.CircuitBreakerConfig config = new StrategyConfig.CircuitBreakerConfig();
            config.setSlowCallDurationThreshold(70000L); // > 60000

            // When
            ValidationResult result = validator.validateCircuitBreaker(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("slowCallDurationThreshold exceeds maximum")));
        }

        @Test
        @DisplayName("Should reject wait duration exceeding maximum")
        void shouldRejectExcessiveWaitDuration() {
            // Given
            StrategyConfig.CircuitBreakerConfig config = new StrategyConfig.CircuitBreakerConfig();
            config.setWaitDurationInOpenState(400000L); // > 300000

            // When
            ValidationResult result = validator.validateCircuitBreaker(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("waitDurationInOpenState exceeds maximum")));
        }

        @Test
        @DisplayName("Should reject minimum calls exceeding sliding window")
        void shouldRejectMinimumCallsExceedingSlidingWindow() {
            // Given
            StrategyConfig.CircuitBreakerConfig config = new StrategyConfig.CircuitBreakerConfig();
            config.setSlidingWindowSize(10);
            config.setMinimumNumberOfCalls(15); // > slidingWindowSize

            // When
            ValidationResult result = validator.validateCircuitBreaker(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("minimumNumberOfCalls") && e.contains("should not exceed slidingWindowSize")));
        }

        @Test
        @DisplayName("Should reject negative sliding window size")
        void shouldRejectNegativeSlidingWindowSize() {
            // Given
            StrategyConfig.CircuitBreakerConfig config = new StrategyConfig.CircuitBreakerConfig();
            config.setSlidingWindowSize(-10);

            // When
            ValidationResult result = validator.validateCircuitBreaker(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("slidingWindowSize must be positive")));
        }

        @Test
        @DisplayName("Should reject excessive sliding window size")
        void shouldRejectExcessiveSlidingWindowSize() {
            // Given
            StrategyConfig.CircuitBreakerConfig config = new StrategyConfig.CircuitBreakerConfig();
            config.setSlidingWindowSize(2000); // > 1000

            // When
            ValidationResult result = validator.validateCircuitBreaker(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("slidingWindowSize exceeds maximum")));
        }
    }

    @Nested
    @DisplayName("Timeout Validation Tests")
    class TimeoutValidationTests {

        @Test
        @DisplayName("Should validate valid timeout config")
        void shouldValidateValidConfig() {
            // Given
            StrategyConfig.TimeoutConfig config = new StrategyConfig.TimeoutConfig();
            config.setRouteId("route-1");
            config.setConnectTimeout(5000);
            config.setResponseTimeout(30000);

            // When
            ValidationResult result = validator.validateTimeout(config);

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should reject null config")
        void shouldRejectNullConfig() {
            // When
            ValidationResult result = validator.validateTimeout(null);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().contains("Timeout config is null"));
        }

        @Test
        @DisplayName("Should reject negative connect timeout")
        void shouldRejectNegativeConnectTimeout() {
            // Given
            StrategyConfig.TimeoutConfig config = new StrategyConfig.TimeoutConfig();
            config.setConnectTimeout(-5000);

            // When
            ValidationResult result = validator.validateTimeout(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("connectTimeout must be positive")));
        }

        @Test
        @DisplayName("Should reject connect timeout exceeding maximum")
        void shouldRejectExcessiveConnectTimeout() {
            // Given
            StrategyConfig.TimeoutConfig config = new StrategyConfig.TimeoutConfig();
            config.setConnectTimeout(70000); // > 60000

            // When
            ValidationResult result = validator.validateTimeout(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("connectTimeout exceeds maximum")));
        }

        @Test
        @DisplayName("Should reject negative response timeout")
        void shouldRejectNegativeResponseTimeout() {
            // Given
            StrategyConfig.TimeoutConfig config = new StrategyConfig.TimeoutConfig();
            config.setConnectTimeout(5000);
            config.setResponseTimeout(-30000);

            // When
            ValidationResult result = validator.validateTimeout(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("responseTimeout must be positive")));
        }

        @Test
        @DisplayName("Should reject response timeout exceeding maximum")
        void shouldRejectExcessiveResponseTimeout() {
            // Given
            StrategyConfig.TimeoutConfig config = new StrategyConfig.TimeoutConfig();
            config.setConnectTimeout(5000);
            config.setResponseTimeout(400000); // > 300000

            // When
            ValidationResult result = validator.validateTimeout(config);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("responseTimeout exceeds maximum")));
        }
    }

    @Nested
    @DisplayName("Validation Result Tests")
    class ValidationResultTests {

        @Test
        @DisplayName("Should create success result")
        void shouldCreateSuccessResult() {
            // When
            ValidationResult result = ValidationResult.success();

            // Then
            assertTrue(result.isValid());
            assertTrue(result.getErrors().isEmpty());
            assertEquals("", result.getErrorMessage());
        }

        @Test
        @DisplayName("Should create failure result with errors")
        void shouldCreateFailureResult() {
            // When
            ValidationResult result = ValidationResult.failure(Arrays.asList("Error 1", "Error 2"));

            // Then
            assertFalse(result.isValid());
            assertEquals(2, result.getErrors().size());
            assertEquals("Error 1; Error 2", result.getErrorMessage());
        }

        @Test
        @DisplayName("Should handle null errors list in failure")
        void shouldHandleNullErrorsList() {
            // When
            ValidationResult result = ValidationResult.failure(null);

            // Then
            assertFalse(result.isValid());
            assertTrue(result.getErrors().isEmpty());
        }
    }
}