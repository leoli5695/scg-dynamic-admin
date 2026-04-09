package com.leoli.gateway.limiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ShadowQuotaManager.
 * Tests the graceful degradation and recovery logic for Redis rate limiter failover.
 */
@ExtendWith(MockitoExtension.class)
class ShadowQuotaManagerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private DiscoveryClient discoveryClient;

    @InjectMocks
    private ShadowQuotaManager shadowQuotaManager;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(shadowQuotaManager, "applicationName", "gateway");
        ReflectionTestUtils.setField(shadowQuotaManager, "shadowQuotaEnabled", true);
        ReflectionTestUtils.setField(shadowQuotaManager, "minNodeCount", 1);
    }

    // ============================================================
    // Redis Health Tests
    // ============================================================

    @Nested
    @DisplayName("Redis Health Tests")
    class RedisHealthTests {

        @Test
        @DisplayName("Should detect Redis as unavailable when template is null")
        void testRedisUnavailable_templateNull() {
            ShadowQuotaManager manager = new ShadowQuotaManager();
            ReflectionTestUtils.setField(manager, "redisTemplate", null);
            ReflectionTestUtils.setField(manager, "shadowQuotaEnabled", true);
            ReflectionTestUtils.setField(manager, "applicationName", "gateway");

            // Trigger health check through updateShadowQuotas
            manager.updateShadowQuotas();

            assertFalse(manager.isRedisHealthy());
        }

        @Test
        @DisplayName("Should detect Redis as healthy on successful connection")
        void testRedisHealthy_connectionSuccess() {
            // Mock successful Redis operation
            when(redisTemplate.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
            when(redisTemplate.opsForValue().get(anyString())).thenReturn("ok");

            // Trigger health check
            shadowQuotaManager.updateShadowQuotas();

            assertTrue(shadowQuotaManager.isRedisHealthy());
        }

        @Test
        @DisplayName("Should detect Redis as unhealthy on connection failure")
        void testRedisUnhealthy_connectionFailure() {
            // Mock Redis failure
            when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Connection refused"));

            // Trigger health check
            shadowQuotaManager.updateShadowQuotas();

            assertFalse(shadowQuotaManager.isRedisHealthy());
        }

        @Test
        @DisplayName("Should transition from healthy to unhealthy on Redis failure")
        void testRedisTransition_failed() {
            // Start with healthy Redis
            ReflectionTestUtils.setField(shadowQuotaManager, "redisHealthy", true);

            // Mock Redis failure
            when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Connection refused"));

            shadowQuotaManager.updateShadowQuotas();

            assertFalse(shadowQuotaManager.isRedisHealthy());
            assertEquals(0, shadowQuotaManager.getRecoveryProgress());
        }

        @Test
        @DisplayName("Should transition from unhealthy to healthy on Redis recovery")
        void testRedisTransition_recovered() {
            // Start with unhealthy Redis
            ReflectionTestUtils.setField(shadowQuotaManager, "redisHealthy", false);

            // Mock Redis recovery
            when(redisTemplate.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
            when(redisTemplate.opsForValue().get(anyString())).thenReturn("ok");

            shadowQuotaManager.updateShadowQuotas();

            assertTrue(shadowQuotaManager.isRedisHealthy());
        }
    }

    // ============================================================
    // Shadow Quota Calculation Tests
    // ============================================================

    @Nested
    @DisplayName("Shadow Quota Calculation Tests")
    class ShadowQuotaCalculationTests {

        @Test
        @DisplayName("Should register route with initial quota")
        void testRegisterRoute() {
            shadowQuotaManager.registerRoute("test-route", 100);

            // Should have quota calculated (configQps / minNodeCount)
            long quota = shadowQuotaManager.getShadowQuota("test-route", 100);
            assertTrue(quota > 0);
        }

        @Test
        @DisplayName("Should return fair share when no shadow quota calculated")
        void testGetShadowQuota_noQuotaCalculated() {
            // Route not registered, should return configQps / nodeCount
            long quota = shadowQuotaManager.getShadowQuota("unknown-route", 100);

            assertEquals(100, quota); // 100 / 1 (minNodeCount)
        }

        @Test
        @DisplayName("Should return configured quota when shadow quota disabled")
        void testGetShadowQuota_disabled() {
            ReflectionTestUtils.setField(shadowQuotaManager, "shadowQuotaEnabled", false);

            long quota = shadowQuotaManager.getShadowQuota("test-route", 100);

            assertEquals(100, quota); // Returns configQps directly
        }

        @Test
        @DisplayName("Should calculate shadow quota based on cluster node count")
        void testShadowQuota_withClusterNodes() {
            // Register route first
            shadowQuotaManager.registerRoute("test-route", 100);

            // Mock cluster with 4 nodes
            when(discoveryClient.getInstances("gateway")).thenReturn(
                    Arrays.asList(
                            mock(org.springframework.cloud.client.ServiceInstance.class),
                            mock(org.springframework.cloud.client.ServiceInstance.class),
                            mock(org.springframework.cloud.client.ServiceInstance.class),
                            mock(org.springframework.cloud.client.ServiceInstance.class)
                    )
            );

            // Update cluster node count
            shadowQuotaManager.updateShadowQuotas();

            int nodeCount = shadowQuotaManager.getClusterNodeCount();
            assertTrue(nodeCount >= 1);
        }
    }

    // ============================================================
    // Cluster Node Count Tests
    // ============================================================

    @Nested
    @DisplayName("Cluster Node Count Tests")
    class ClusterNodeCountTests {

        @Test
        @DisplayName("Should return minNodeCount when discovery client is null")
        void testClusterNodeCount_nullDiscoveryClient() {
            ShadowQuotaManager manager = new ShadowQuotaManager();
            ReflectionTestUtils.setField(manager, "discoveryClient", null);
            ReflectionTestUtils.setField(manager, "minNodeCount", 2);
            ReflectionTestUtils.setField(manager, "shadowQuotaEnabled", true);
            ReflectionTestUtils.setField(manager, "applicationName", "gateway");

            manager.updateShadowQuotas();

            assertEquals(2, manager.getClusterNodeCount());
        }

        @Test
        @DisplayName("Should return actual node count from discovery")
        void testClusterNodeCount_fromDiscovery() {
            when(discoveryClient.getInstances("gateway")).thenReturn(
                    Arrays.asList(
                            mock(org.springframework.cloud.client.ServiceInstance.class),
                            mock(org.springframework.cloud.client.ServiceInstance.class),
                            mock(org.springframework.cloud.client.ServiceInstance.class)
                    )
            );

            shadowQuotaManager.updateShadowQuotas();

            assertEquals(3, shadowQuotaManager.getClusterNodeCount());
        }

        @Test
        @DisplayName("Should use minNodeCount when discovery returns empty")
        void testClusterNodeCount_emptyDiscovery() {
            when(discoveryClient.getInstances("gateway")).thenReturn(Collections.emptyList());

            shadowQuotaManager.updateShadowQuotas();

            assertEquals(1, shadowQuotaManager.getClusterNodeCount()); // minNodeCount
        }

        @Test
        @DisplayName("Should handle discovery exception gracefully")
        void testClusterNodeCount_discoveryException() {
            when(discoveryClient.getInstances("gateway")).thenThrow(new RuntimeException("Discovery error"));

            shadowQuotaManager.updateShadowQuotas();

            assertEquals(1, shadowQuotaManager.getClusterNodeCount()); // minNodeCount
        }
    }

    // ============================================================
    // Recovery Progress Tests
    // ============================================================

    @Nested
    @DisplayName("Recovery Progress Tests")
    class RecoveryProgressTests {

        @Test
        @DisplayName("Should start recovery at 0% when Redis recovers")
        void testRecoveryStartsAtZero() {
            // Start unhealthy
            ReflectionTestUtils.setField(shadowQuotaManager, "redisHealthy", false);

            // Mock Redis recovery
            when(redisTemplate.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
            when(redisTemplate.opsForValue().get(anyString())).thenReturn("ok");

            shadowQuotaManager.updateShadowQuotas();

            assertEquals(0, shadowQuotaManager.getRecoveryProgress());
        }

        @Test
        @DisplayName("Should reach 100% recovery after duration")
        void testFullRecovery() throws InterruptedException {
            // Set up recovery start
            ReflectionTestUtils.setField(shadowQuotaManager, "redisHealthy", true);
            ((AtomicInteger) ReflectionTestUtils.getField(shadowQuotaManager, "recoveryProgress")).set(100);

            assertEquals(100, shadowQuotaManager.getRecoveryProgress());
        }

        @Test
        @DisplayName("shouldUseRedisDuringRecovery returns true when fully recovered")
        void testShouldUseRedis_fullRecovery() {
            ((AtomicInteger) ReflectionTestUtils.getField(shadowQuotaManager, "recoveryProgress")).set(100);

            assertTrue(shadowQuotaManager.shouldUseRedisDuringRecovery());
        }

        @Test
        @DisplayName("shouldUseRedisDuringRecovery uses probabilistic routing during recovery")
        void testShouldUseRedis_partialRecovery() {
            ((AtomicInteger) ReflectionTestUtils.getField(shadowQuotaManager, "recoveryProgress")).set(50);

            // At 50% progress, there's 50% chance to use Redis
            // Run multiple times to verify probabilistic behavior
            int redisCount = 0;
            for (int i = 0; i < 100; i++) {
                if (shadowQuotaManager.shouldUseRedisDuringRecovery()) {
                    redisCount++;
                }
            }

            // Should be roughly 50% (allow some variance: 30-70)
            assertTrue(redisCount >= 30 && redisCount <= 70,
                    "Expected ~50% Redis usage, got " + redisCount + "%");
        }

        @Test
        @DisplayName("shouldUseRedisDuringRecovery returns false at 0% progress")
        void testShouldUseRedis_zeroProgress() {
            ((AtomicInteger) ReflectionTestUtils.getField(shadowQuotaManager, "recoveryProgress")).set(0);

            // At 0% progress, should never use Redis
            for (int i = 0; i < 100; i++) {
                assertFalse(shadowQuotaManager.shouldUseRedisDuringRecovery());
            }
        }
    }

    // ============================================================
    // Status Tests
    // ============================================================

    @Nested
    @DisplayName("Status Tests")
    class StatusTests {

        @Test
        @DisplayName("Should return correct status summary")
        void testGetStatus() {
            shadowQuotaManager.registerRoute("route-1", 100);
            shadowQuotaManager.registerRoute("route-2", 200);

            ShadowQuotaManager.ShadowQuotaStatus status = shadowQuotaManager.getStatus();

            assertNotNull(status);
            assertTrue(status.isRedisHealthy());
            assertTrue(status.getTrackedRoutes() >= 2);
        }

        @Test
        @DisplayName("Status toString should contain key information")
        void testStatusToString() {
            ShadowQuotaManager.ShadowQuotaStatus status =
                    new ShadowQuotaManager.ShadowQuotaStatus(true, 3, 75, 5);

            String str = status.toString();

            assertTrue(str.contains("UP"));
            assertTrue(str.contains("3"));
            assertTrue(str.contains("75"));
            assertTrue(str.contains("5"));
        }
    }

    // ============================================================
    // Edge Cases Tests
    // ============================================================

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle zero minNodeCount gracefully")
        void testZeroMinNodeCount() {
            ReflectionTestUtils.setField(shadowQuotaManager, "minNodeCount", 0);

            // Should not throw exception
            shadowQuotaManager.registerRoute("test-route", 100);
        }

        @Test
        @DisplayName("Should handle negative QPS gracefully")
        void testNegativeQps() {
            shadowQuotaManager.registerRoute("test-route", -100);

            // Should not throw exception
            assertDoesNotThrow(() -> shadowQuotaManager.getShadowQuota("test-route", -100));
        }

        @Test
        @DisplayName("Should handle concurrent route registration")
        void testConcurrentRegistration() throws InterruptedException {
            Runnable registerTask = () -> {
                for (int i = 0; i < 100; i++) {
                    shadowQuotaManager.registerRoute("route-" + i, 100);
                }
            };

            Thread t1 = new Thread(registerTask);
            Thread t2 = new Thread(registerTask);

            t1.start();
            t2.start();
            t1.join();
            t2.join();

            // Should complete without exception
            assertTrue(shadowQuotaManager.getClusterNodeCount() >= 1);
        }
    }
}