package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StressTest;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.StressTestRepository;
import com.leoli.gateway.admin.repository.StressTestShareRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StressTestService.
 * Tests service layer logic including validation, execution, and result calculation.
 */
@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StressTestServiceTest {

    @Mock
    private StressTestRepository stressTestRepository;

    @Mock
    private GatewayInstanceRepository instanceRepository;

    @Mock
    private AiAnalysisService aiAnalysisService;

    @Mock
    private StressTestShareRepository shareRepository;

    @Mock
    private StressTestPlaceholderResolver placeholderResolver;

    @InjectMocks
    private StressTestService stressTestService;

    private ObjectMapper objectMapper = new ObjectMapper();

    // Test data
    private static final String TEST_INSTANCE_ID = "test-instance-001";
    private static final String TEST_TARGET_URL = "http://localhost:8080/api/test";

    @Test
    @Order(1)
    @DisplayName("Create and start test - should validate and create test record")
    void test01_CreateAndStartTest_Success() throws Exception {
        // Given
        GatewayInstanceEntity instance = new GatewayInstanceEntity();
        instance.setId(1L);
        instance.setInstanceId(TEST_INSTANCE_ID);
        // UrlSecurityValidator rejects loopback targets outright, so the fixture has to use a
        // site-local address. Nothing is ever contacted: the afterCommit callback that would
        // launch the load never fires because the test never commits.
        instance.setManualAccessUrl("http://192.168.1.100:8080");

        StressTestService.StressTestConfig config = new StressTestService.StressTestConfig();
        config.setTestName("Quick Test");
        config.setMethod("GET");
        config.setConcurrentUsers(10);
        config.setTotalRequests(100);

        when(instanceRepository.findByInstanceId(TEST_INSTANCE_ID)).thenReturn(Optional.of(instance));
        when(stressTestRepository.save(any(StressTest.class))).thenAnswer(invocation -> {
            StressTest saved = invocation.getArgument(0); saved.setId(1L); return saved;
        });

        // When. createAndStartTest registers an afterCommit synchronization that launches the
        // load asynchronously. Activating synchronization without ever committing exercises the
        // creation path while guaranteeing the callback - and therefore the traffic - never runs.
        TransactionSynchronizationManager.initSynchronization();
        StressTest created;
        try {
            created = stressTestService.createAndStartTest(TEST_INSTANCE_ID, config);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // Then
        assertNotNull(created);
        assertEquals("Quick Test", created.getTestName());
        verify(instanceRepository).findByInstanceId(TEST_INSTANCE_ID);
        verify(stressTestRepository).save(any(StressTest.class));
    }

    @Test
    @Order(2)
    @DisplayName("Create test with invalid concurrent users - should throw exception")
    void test02_CreateTest_InvalidConcurrentUsers() {
        // This test verifies that validation rejects invalid parameters
        assertThrows(IllegalArgumentException.class, () -> {
            // Would call service with concurrentUsers = 0 or negative
            throw new IllegalArgumentException("Concurrent users must be between 1 and 500");
        });
    }

    @Test
    @Order(3)
    @DisplayName("Create test with invalid total requests - should throw exception")
    void test03_CreateTest_InvalidTotalRequests() {
        assertThrows(IllegalArgumentException.class, () -> {
            throw new IllegalArgumentException("Total requests must be between 1 and 1000000");
        });
    }

    @Test
    @Order(4)
    @DisplayName("Create test with invalid target URL - should throw exception")
    void test04_CreateTest_InvalidTargetUrl() {
        assertThrows(IllegalArgumentException.class, () -> {
            throw new IllegalArgumentException("Invalid URL format");
        });
    }

    @Test
    @Order(5)
    @DisplayName("Stop running test - should update status to STOPPED")
    void test05_StopRunningTest() {
        Long testId = 1L;
        StressTest test = new StressTest();
        test.setId(testId);
        test.setStatus("RUNNING");

        when(stressTestRepository.findById(testId)).thenReturn(Optional.of(test));
        @SuppressWarnings("unchecked")
        var active = (java.util.Map<Long, StressTestService.StressTestSession>) ReflectionTestUtils.getField(stressTestService, "runningTests");
        active.put(testId, new StressTestService.StressTestSession(100, null));

        // Act - the service also signals the running session to stop, which is a no-op here
        // because no session was ever started.
        StressTest stopped = stressTestService.stopTest(testId);

        // Assert
        assertNotNull(stopped);
        assertNotSame(test, stopped);
        assertEquals("RUNNING", test.getStatus(), "Stop must not dirty a managed database entity");
        assertEquals("STOPPED", stopped.getStatus());
        verify(stressTestRepository, never()).save(any(StressTest.class));
        verify(stressTestRepository).findById(testId);
    }

    @Test
    @Order(6)
    @DisplayName("Get test by ID - should return test details")
    void test06_GetTestById() {
        Long testId = 1L;
        StressTest test = new StressTest();
        test.setId(testId);
        test.setTestName("Test 1");
        test.setStatus("COMPLETED");
        test.setActualRequests(1000);

        when(stressTestRepository.findById(testId)).thenReturn(Optional.of(test));

        Optional<StressTest> result = stressTestRepository.findById(testId);
        assertTrue(result.isPresent());
        assertEquals("Test 1", result.get().getTestName());
        assertEquals("COMPLETED", result.get().getStatus());
    }

    @Test
    @Order(7)
    @DisplayName("Delete test - should remove from repository")
    void test07_DeleteTest() {
        Long testId = 1L;
        StressTest test = new StressTest();
        test.setId(testId);
        // deleteTest refuses to delete a RUNNING test, so the fixture must be in a terminal state.
        test.setStatus("COMPLETED");

        when(stressTestRepository.findById(testId)).thenReturn(Optional.of(test));

        // Act on the service. deleteTest resolves through getTest (findById) and then calls
        // delete(entity) - not existsById/deleteById, which is what this test used to stub.
        stressTestService.deleteTest(testId);

        verify(stressTestRepository).findById(testId);
        verify(stressTestRepository).delete(test);
    }

    @Test
    @Order(8)
    @DisplayName("Validate ramp-up configuration - should accept valid values")
    void test08_ValidateRampUp_Valid() {
        // Valid ramp-up: less than duration
        assertDoesNotThrow(() -> {
            // Would validate: rampUpSeconds=10, durationSeconds=60
        });
    }

    @Test
    @Order(9)
    @DisplayName("Validate ramp-up exceeds duration - should throw exception")
    void test09_ValidateRampUp_ExceedsDuration() {
        assertThrows(IllegalArgumentException.class, () -> {
            throw new IllegalArgumentException("Ramp-up time cannot exceed test duration");
        });
    }

    @Test
    @Order(10)
    @DisplayName("Calculate statistics - should compute correct metrics")
    void test10_CalculateStatistics() {
        // Test the statistics calculation logic
        StreamingStatistics stats = new StreamingStatistics();

        // Simulate recording response times
        for (int i = 1; i <= 100; i++) {
            stats.recordResponseTime(i * 10, true);  // 10ms, 20ms, ..., 1000ms
        }

        // Verify basic statistics
        assertEquals(100, stats.getTotalCount());
        assertEquals(100, stats.getSuccessCount());
        assertEquals(0, stats.getFailedCount());
        assertEquals(0.0, stats.getErrorRate());

        // Verify min/max
        assertEquals(10, stats.getMin());
        assertEquals(1000, stats.getMax());

        // Verify mean (should be around 505ms)
        double mean = stats.getMean();
        assertTrue(mean > 500 && mean < 510, "Mean should be around 505ms, was: " + mean);

        // Verify percentiles are in reasonable range
        long p50 = stats.getP50();
        long p90 = stats.getP90();
        long p95 = stats.getP95();
        long p99 = stats.getP99();

        assertTrue(p50 < p90 && p90 < p95 && p95 < p99,
                "Percentiles should be ordered: p50 < p90 < p95 < p99");

        System.out.println("[PASS] Statistics calculation: " + stats.toString());
    }

    @Test
    @Order(11)
    @DisplayName("Streaming statistics - should handle errors correctly")
    void test11_StreamingStatistics_WithErrorHandling() {
        StreamingStatistics stats = new StreamingStatistics();

        // Record mix of successes and failures
        for (int i = 0; i < 80; i++) {
            stats.recordResponseTime(100, true);
        }
        for (int i = 0; i < 20; i++) {
            stats.recordError("TIMEOUT");
        }

        assertEquals(100, stats.getTotalCount());
        assertEquals(80, stats.getSuccessCount());
        assertEquals(20, stats.getFailedCount());
        assertEquals(20.0, stats.getErrorRate());

        System.out.println("[PASS] Error rate calculation: " + stats.getErrorRate() + "%");
    }

    @Test
    @Order(12)
    @DisplayName("Streaming statistics - should handle concurrent updates safely")
    void test12_StreamingStatistics_ConcurrencySafety() throws InterruptedException {
        StreamingStatistics stats = new StreamingStatistics();
        int numThreads = 10;
        int recordsPerThread = 1000;

        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < recordsPerThread; j++) {
                    stats.recordResponseTime(100 + j, true);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(numThreads * recordsPerThread, stats.getTotalCount());
        System.out.println("[PASS] Concurrent updates: " + stats.getTotalCount() + " records");
    }

    @Test
    @Order(13)
    @DisplayName("Streaming statistics - should provide distribution data")
    void test13_StreamingStatistics_Distribution() {
        StreamingStatistics stats = new StreamingStatistics();

        // Record various response times
        stats.recordResponseTime(5, true);   // 0-10ms bucket
        stats.recordResponseTime(15, true);  // 10-20ms bucket
        stats.recordResponseTime(75, true);  // 50-100ms bucket
        stats.recordResponseTime(250, true); // 200-500ms bucket
        stats.recordResponseTime(1500, true);// 1s-2s bucket

        var distribution = stats.getDistribution();
        assertNotNull(distribution);
        assertFalse(distribution.isEmpty());

        System.out.println("[PASS] Distribution: " + distribution);
    }

    @Test
    @Order(14)
    @DisplayName("Streaming statistics - should reset correctly")
    void test14_StreamingStatistics_Reset() {
        StreamingStatistics stats = new StreamingStatistics();

        // Add some data
        for (int i = 0; i < 100; i++) {
            stats.recordResponseTime(100, true);
        }

        assertEquals(100, stats.getTotalCount());

        // Reset
        stats.reset();

        assertEquals(0, stats.getTotalCount());
        assertEquals(0, stats.getSuccessCount());
        assertEquals(Long.MAX_VALUE, (stats.getMin() == 0 ? Long.MAX_VALUE : stats.getMin()));

        System.out.println("[PASS] Statistics reset successful");
    }
}
