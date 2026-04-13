package com.leoli.gateway.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StrategyManager.
 * Tests strategy management, route binding, and config extraction.
 *
 * @author leoli
 */
class StrategyManagerTest {

    private StrategyManager manager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        manager = new StrategyManager(objectMapper);
    }

    @Nested
    @DisplayName("Strategy Management Tests")
    class StrategyManagementTests {

        @Test
        @DisplayName("Should add and retrieve strategy")
        void shouldAddAndRetrieveStrategy() {
            // Given
            String strategyId = "strategy-1";
            StrategyDefinition strategy = createGlobalStrategy(strategyId, StrategyDefinition.TYPE_RATE_LIMITER);

            // When
            manager.putStrategy(strategyId, strategy);
            StrategyDefinition retrieved = manager.getStrategy(strategyId);

            // Then
            assertNotNull(retrieved);
            assertEquals(StrategyDefinition.TYPE_RATE_LIMITER, retrieved.getStrategyType());
        }

        @Test
        @DisplayName("Should return null for non-existent strategy")
        void shouldReturnNullForNonExistentStrategy() {
            // When
            StrategyDefinition retrieved = manager.getStrategy("non-existent");

            // Then
            assertNull(retrieved);
        }

        @Test
        @DisplayName("Should remove strategy")
        void shouldRemoveStrategy() {
            // Given
            String strategyId = "strategy-1";
            StrategyDefinition strategy = createGlobalStrategy(strategyId, StrategyDefinition.TYPE_RATE_LIMITER);
            manager.putStrategy(strategyId, strategy);

            // When
            manager.removeStrategy(strategyId);
            StrategyDefinition retrieved = manager.getStrategy(strategyId);

            // Then
            assertNull(retrieved);
        }

        @Test
        @DisplayName("Should handle null strategyId in putStrategy")
        void shouldHandleNullStrategyId() {
            // Given
            StrategyDefinition strategy = createGlobalStrategy("test", StrategyDefinition.TYPE_RATE_LIMITER);

            // When
            manager.putStrategy(null, strategy);

            // Then - Should not throw exception
            assertEquals(0, manager.getStrategyCount());
        }

        @Test
        @DisplayName("Should handle null strategy in putStrategy")
        void shouldHandleNullStrategy() {
            // When
            manager.putStrategy("strategy-1", null);

            // Then - Should not throw exception
            assertEquals(0, manager.getStrategyCount());
        }

        @Test
        @DisplayName("Should get all strategies")
        void shouldGetAllStrategies() {
            // Given
            manager.putStrategy("strategy-1", createGlobalStrategy("strategy-1", StrategyDefinition.TYPE_RATE_LIMITER));
            manager.putStrategy("strategy-2", createGlobalStrategy("strategy-2", StrategyDefinition.TYPE_IP_FILTER));

            // When
            List<StrategyDefinition> allStrategies = manager.getAllStrategies();

            // Then
            assertEquals(2, allStrategies.size());
        }

        @Test
        @DisplayName("Should update existing strategy")
        void shouldUpdateExistingStrategy() {
            // Given
            String strategyId = "strategy-1";
            StrategyDefinition original = createGlobalStrategy(strategyId, StrategyDefinition.TYPE_RATE_LIMITER);
            manager.putStrategy(strategyId, original);

            // When - Update with new type
            StrategyDefinition updated = createGlobalStrategy(strategyId, StrategyDefinition.TYPE_IP_FILTER);
            manager.putStrategy(strategyId, updated);

            // Then
            StrategyDefinition retrieved = manager.getStrategy(strategyId);
            assertEquals(StrategyDefinition.TYPE_IP_FILTER, retrieved.getStrategyType());
            
            // Old type index should be updated
            List<StrategyDefinition> rateLimiterStrategies = manager.getGlobalStrategiesByType(StrategyDefinition.TYPE_RATE_LIMITER);
            assertTrue(rateLimiterStrategies.isEmpty());
        }
    }

    @Nested
    @DisplayName("Route Strategy Tests")
    class RouteStrategyTests {

        @Test
        @DisplayName("Should add route-bound strategy")
        void shouldAddRouteBoundStrategy() {
            // Given
            String strategyId = "route-strategy-1";
            String routeId = "route-1";
            StrategyDefinition strategy = createRouteStrategy(strategyId, StrategyDefinition.TYPE_RATE_LIMITER, routeId);

            // When
            manager.putStrategy(strategyId, strategy);

            // Then
            List<StrategyDefinition> routeStrategies = manager.getStrategiesForRoute(routeId);
            assertEquals(1, routeStrategies.size());
        }

        @Test
        @DisplayName("Should get strategies for route including global")
        void shouldGetStrategiesForRouteWithGlobal() {
            // Given
            manager.putStrategy("global-1", createGlobalStrategy("global-1", StrategyDefinition.TYPE_RATE_LIMITER));
            manager.putStrategy("global-2", createGlobalStrategy("global-2", StrategyDefinition.TYPE_IP_FILTER));
            manager.putStrategy("route-1", createRouteStrategy("route-1", StrategyDefinition.TYPE_TIMEOUT, "route-1"));

            // When
            List<StrategyDefinition> strategies = manager.getStrategiesForRoute("route-1");

            // Then - Should include global + route-bound
            assertEquals(3, strategies.size());
        }

        @Test
        @DisplayName("Should get strategy for route by type")
        void shouldGetStrategyForRouteByType() {
            // Given
            manager.putStrategy("global-rate", createGlobalStrategy("global-rate", StrategyDefinition.TYPE_RATE_LIMITER));
            manager.putStrategy("route-rate", createRouteStrategy("route-rate", StrategyDefinition.TYPE_RATE_LIMITER, "route-1"));

            // When - Route-bound takes precedence
            StrategyDefinition strategy = manager.getStrategyForRoute("route-1", StrategyDefinition.TYPE_RATE_LIMITER);

            // Then - Should return route-bound strategy
            assertNotNull(strategy);
            assertEquals("route-rate", strategy.getStrategyId());
        }

        @Test
        @DisplayName("Should return global strategy when no route-bound")
        void shouldReturnGlobalWhenNoRouteBound() {
            // Given
            manager.putStrategy("global-rate", createGlobalStrategy("global-rate", StrategyDefinition.TYPE_RATE_LIMITER));

            // When
            StrategyDefinition strategy = manager.getStrategyForRoute("route-1", StrategyDefinition.TYPE_RATE_LIMITER);

            // Then - Should return global strategy
            assertNotNull(strategy);
            assertEquals("global-rate", strategy.getStrategyId());
        }

        @Test
        @DisplayName("Should return null when no strategy for type")
        void shouldReturnNullWhenNoStrategyForType() {
            // Given
            manager.putStrategy("global-rate", createGlobalStrategy("global-rate", StrategyDefinition.TYPE_RATE_LIMITER));

            // When
            StrategyDefinition strategy = manager.getStrategyForRoute("route-1", StrategyDefinition.TYPE_IP_FILTER);

            // Then
            assertNull(strategy);
        }

        @Test
        @DisplayName("Should not return disabled strategies")
        void shouldNotReturnDisabledStrategies() {
            // Given
            StrategyDefinition disabledStrategy = createGlobalStrategy("disabled", StrategyDefinition.TYPE_RATE_LIMITER);
            disabledStrategy.setEnabled(false);
            manager.putStrategy("disabled", disabledStrategy);

            // When
            StrategyDefinition strategy = manager.getStrategyForRoute("route-1", StrategyDefinition.TYPE_RATE_LIMITER);

            // Then
            assertNull(strategy);
        }

        @Test
        @DisplayName("Should remove all strategies for route")
        void shouldRemoveStrategiesForRoute() {
            // Given
            manager.putStrategy("route-1", createRouteStrategy("route-1", StrategyDefinition.TYPE_RATE_LIMITER, "route-1"));
            manager.putStrategy("route-2", createRouteStrategy("route-2", StrategyDefinition.TYPE_IP_FILTER, "route-1"));

            // When
            manager.removeStrategiesForRoute("route-1");

            // Then
            assertNull(manager.getStrategy("route-1"));
            assertNull(manager.getStrategy("route-2"));
            assertTrue(manager.getStrategiesForRoute("route-1").isEmpty());
        }

        @Test
        @DisplayName("Should sort strategies by priority")
        void shouldSortStrategiesByPriority() {
            // Given
            StrategyDefinition lowPriority = createGlobalStrategy("low", StrategyDefinition.TYPE_RATE_LIMITER);
            lowPriority.setPriority(10);
            StrategyDefinition highPriority = createGlobalStrategy("high", StrategyDefinition.TYPE_IP_FILTER);
            highPriority.setPriority(100);
            manager.putStrategy("low", lowPriority);
            manager.putStrategy("high", highPriority);

            // When
            List<StrategyDefinition> strategies = manager.getStrategiesForRoute("any-route");

            // Then - Higher priority first
            assertEquals(2, strategies.size());
            assertEquals("high", strategies.get(0).getStrategyId());
            assertEquals("low", strategies.get(1).getStrategyId());
        }
    }

    @Nested
    @DisplayName("Global Strategy Tests")
    class GlobalStrategyTests {

        @Test
        @DisplayName("Should get global strategies by type")
        void shouldGetGlobalStrategiesByType() {
            // Given
            manager.putStrategy("rate-1", createGlobalStrategy("rate-1", StrategyDefinition.TYPE_RATE_LIMITER));
            manager.putStrategy("rate-2", createGlobalStrategy("rate-2", StrategyDefinition.TYPE_RATE_LIMITER));
            manager.putStrategy("ip-1", createGlobalStrategy("ip-1", StrategyDefinition.TYPE_IP_FILTER));

            // When
            List<StrategyDefinition> rateStrategies = manager.getGlobalStrategiesByType(StrategyDefinition.TYPE_RATE_LIMITER);

            // Then
            assertEquals(2, rateStrategies.size());
        }

        @Test
        @DisplayName("Should return empty list when no global strategies for type")
        void shouldReturnEmptyWhenNoGlobalStrategies() {
            // When
            List<StrategyDefinition> strategies = manager.getGlobalStrategiesByType(StrategyDefinition.TYPE_RATE_LIMITER);

            // Then
            assertTrue(strategies.isEmpty());
        }
    }

    @Nested
    @DisplayName("Config Extraction Tests")
    class ConfigExtractionTests {

        @Test
        @DisplayName("Should get rate limiter config for route")
        void shouldGetRateLimiterConfig() {
            // Given
            StrategyDefinition strategy = createRouteStrategy("rate-1", StrategyDefinition.TYPE_RATE_LIMITER, "route-1");
            Map<String, Object> config = new HashMap<>();
            config.put("maxRequests", "100");
            config.put("interval", "60");
            strategy.setConfig(config);
            manager.putStrategy("rate-1", strategy);

            // When
            Map<String, Object> retrievedConfig = manager.getRateLimiterConfig("route-1");

            // Then
            assertNotNull(retrievedConfig);
            assertEquals("100", retrievedConfig.get("maxRequests"));
        }

        @Test
        @DisplayName("Should get IP filter config for route")
        void shouldGetIPFilterConfig() {
            // Given
            StrategyDefinition strategy = createRouteStrategy("ip-1", StrategyDefinition.TYPE_IP_FILTER, "route-1");
            Map<String, Object> config = new HashMap<>();
            config.put("whitelist", "192.168.1.0/24");
            strategy.setConfig(config);
            manager.putStrategy("ip-1", strategy);

            // When
            Map<String, Object> retrievedConfig = manager.getIPFilterConfig("route-1");

            // Then
            assertNotNull(retrievedConfig);
            assertEquals("192.168.1.0/24", retrievedConfig.get("whitelist"));
        }

        @Test
        @DisplayName("Should get circuit breaker config for route")
        void shouldGetCircuitBreakerConfig() {
            // Given
            StrategyDefinition strategy = createRouteStrategy("cb-1", StrategyDefinition.TYPE_CIRCUIT_BREAKER, "route-1");
            Map<String, Object> config = new HashMap<>();
            config.put("failureThreshold", "50");
            config.put("waitDuration", "30");
            strategy.setConfig(config);
            manager.putStrategy("cb-1", strategy);

            // When
            Map<String, Object> retrievedConfig = manager.getCircuitBreakerConfig("route-1");

            // Then
            assertNotNull(retrievedConfig);
            assertEquals("50", retrievedConfig.get("failureThreshold"));
        }

        @Test
        @DisplayName("Should get timeout config for route")
        void shouldGetTimeoutConfig() {
            // Given
            StrategyDefinition strategy = createRouteStrategy("timeout-1", StrategyDefinition.TYPE_TIMEOUT, "route-1");
            Map<String, Object> config = new HashMap<>();
            config.put("timeoutMs", "5000");
            strategy.setConfig(config);
            manager.putStrategy("timeout-1", strategy);

            // When
            Map<String, Object> retrievedConfig = manager.getTimeoutConfig("route-1");

            // Then
            assertNotNull(retrievedConfig);
            assertEquals("5000", retrievedConfig.get("timeoutMs"));
        }

        @Test
        @DisplayName("Should get auth config for route")
        void shouldGetAuthConfig() {
            // Given
            StrategyDefinition strategy = createRouteStrategy("auth-1", StrategyDefinition.TYPE_AUTH, "route-1");
            Map<String, Object> config = new HashMap<>();
            config.put("authType", "JWT");
            strategy.setConfig(config);
            manager.putStrategy("auth-1", strategy);

            // When
            Map<String, Object> retrievedConfig = manager.getAuthConfig("route-1");

            // Then
            assertNotNull(retrievedConfig);
            assertEquals("JWT", retrievedConfig.get("authType"));
        }

        @Test
        @DisplayName("Should get retry config for route")
        void shouldGetRetryConfig() {
            // Given
            StrategyDefinition strategy = createRouteStrategy("retry-1", StrategyDefinition.TYPE_RETRY, "route-1");
            Map<String, Object> config = new HashMap<>();
            config.put("maxRetries", "3");
            strategy.setConfig(config);
            manager.putStrategy("retry-1", strategy);

            // When
            Map<String, Object> retrievedConfig = manager.getRetryConfig("route-1");

            // Then
            assertNotNull(retrievedConfig);
            assertEquals("3", retrievedConfig.get("maxRetries"));
        }

        @Test
        @DisplayName("Should get CORS config for route")
        void shouldGetCorsConfig() {
            // Given
            StrategyDefinition strategy = createRouteStrategy("cors-1", StrategyDefinition.TYPE_CORS, "route-1");
            Map<String, Object> config = new HashMap<>();
            config.put("allowedOrigins", "*");
            strategy.setConfig(config);
            manager.putStrategy("cors-1", strategy);

            // When
            Map<String, Object> retrievedConfig = manager.getCorsConfig("route-1");

            // Then
            assertNotNull(retrievedConfig);
            assertEquals("*", retrievedConfig.get("allowedOrigins"));
        }

        @Test
        @DisplayName("Should return null when config not found")
        void shouldReturnNullWhenConfigNotFound() {
            // When
            Map<String, Object> config = manager.getRateLimiterConfig("non-existent-route");

            // Then
            assertNull(config);
        }
    }

    @Nested
    @DisplayName("Cache Management Tests")
    class CacheManagementTests {

        @Test
        @DisplayName("Should clear all strategies")
        void shouldClearAllStrategies() {
            // Given
            manager.putStrategy("strategy-1", createGlobalStrategy("strategy-1", StrategyDefinition.TYPE_RATE_LIMITER));
            manager.putStrategy("strategy-2", createRouteStrategy("strategy-2", StrategyDefinition.TYPE_IP_FILTER, "route-1"));

            // When
            manager.clear();

            // Then
            assertEquals(0, manager.getStrategyCount());
            assertTrue(manager.getAllStrategies().isEmpty());
        }

        @Test
        @DisplayName("Should check if has strategies")
        void shouldCheckIfHasStrategies() {
            // Given - Empty manager
            assertFalse(manager.hasStrategies());

            // When
            manager.putStrategy("strategy-1", createGlobalStrategy("strategy-1", StrategyDefinition.TYPE_RATE_LIMITER));

            // Then
            assertTrue(manager.hasStrategies());
        }

        @Test
        @DisplayName("Should get strategy count")
        void shouldGetStrategyCount() {
            // Given
            manager.putStrategy("strategy-1", createGlobalStrategy("strategy-1", StrategyDefinition.TYPE_RATE_LIMITER));
            manager.putStrategy("strategy-2", createGlobalStrategy("strategy-2", StrategyDefinition.TYPE_IP_FILTER));

            // When
            int count = manager.getStrategyCount();

            // Then
            assertEquals(2, count);
        }
    }

    @Nested
    @DisplayName("Strategy Enabled Check Tests")
    class StrategyEnabledTests {

        @Test
        @DisplayName("Should check if strategy type is enabled for route")
        void shouldCheckIfStrategyEnabled() {
            // Given
            manager.putStrategy("rate-1", createRouteStrategy("rate-1", StrategyDefinition.TYPE_RATE_LIMITER, "route-1"));

            // When & Then
            assertTrue(manager.isStrategyEnabled("route-1", StrategyDefinition.TYPE_RATE_LIMITER));
            assertFalse(manager.isStrategyEnabled("route-1", StrategyDefinition.TYPE_IP_FILTER));
        }
    }

    // Helper methods to create test strategies
    private StrategyDefinition createGlobalStrategy(String strategyId, String type) {
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setStrategyId(strategyId);
        strategy.setStrategyType(type);
        strategy.setScope(StrategyDefinition.SCOPE_GLOBAL);
        strategy.setEnabled(true);
        strategy.setPriority(100);
        return strategy;
    }

    private StrategyDefinition createRouteStrategy(String strategyId, String type, String routeId) {
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setStrategyId(strategyId);
        strategy.setStrategyType(type);
        strategy.setScope(StrategyDefinition.SCOPE_ROUTE);
        strategy.setRouteId(routeId);
        strategy.setEnabled(true);
        strategy.setPriority(100);
        return strategy;
    }
}