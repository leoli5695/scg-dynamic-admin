package com.leoli.gateway.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridHealthCheckerTest {
    private final HybridHealthChecker checker = new HybridHealthChecker();

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(checker, "failureThreshold", 3);
        ReflectionTestUtils.setField(checker, "networkFlapThreshold", 1000);
        ReflectionTestUtils.setField(checker, "stableCheckThreshold", 2);
        ReflectionTestUtils.setField(checker, "degradedCheckThreshold", 2);
        ReflectionTestUtils.setField(checker, "batchSize", 50);
    }

    @Test
    void initializationIsPendingAndDoesNotOverwriteExistingFailureHistory() {
        checker.initializeInstance("svc", "127.0.0.1", 8080);
        assertFalse(checker.getHealth("svc", "127.0.0.1", 8080).isHealthy());
        assertEquals("INIT", checker.getHealth("svc", "127.0.0.1", 8080).getCheckType());
        checker.markUnhealthy("svc", "127.0.0.1", 8080, "offline", "ACTIVE");
        checker.markUnhealthy("svc", "127.0.0.1", 8080, "offline", "ACTIVE");
        checker.initializeInstance("svc", "127.0.0.1", 8080);
        var observation = checker.getHealth("svc", "127.0.0.1", 8080);
        assertEquals("ACTIVE", observation.getCheckType());
        assertTrue(observation.isDegradedCheckMode());
        assertEquals(2, observation.getTotalUnhealthyChecks());
        assertTrue(checker.getBatchQueueSize() > 0, "first failed probe must be reported even from INIT");
    }

    @Test
    void passiveFailuresRespectThresholdAndLeaveStableModeSoRecoveryChecksContinue() {
        checker.markHealthy("svc", "127.0.0.1", 8080, "ACTIVE");
        checker.markHealthy("svc", "127.0.0.1", 8080, "ACTIVE");
        assertTrue(checker.getHealth("svc", "127.0.0.1", 8080).isStableCheckMode());
        checker.recordFailure("svc", "127.0.0.1", 8080);
        checker.recordFailure("svc", "127.0.0.1", 8080);
        assertTrue(checker.getHealth("svc", "127.0.0.1", 8080).isHealthy());
        checker.recordFailure("svc", "127.0.0.1", 8080);
        var failed = checker.getHealth("svc", "127.0.0.1", 8080);
        assertFalse(failed.isHealthy()); assertFalse(failed.isStableCheckMode());
        assertTrue(checker.getRegularCheckInstances().contains(failed));
        checker.recordSuccess("svc", "127.0.0.1", 8080);
        assertTrue(failed.isHealthy()); assertEquals(0, failed.getConsecutiveFailures());
        assertNull(failed.getUnhealthyReason());
    }

    @Test
    void activeRecoveryClearsStaleFailureReasonAndDegradedMode() {
        checker.markUnhealthy("svc", "127.0.0.1", 8080, "offline", "ACTIVE");
        checker.markUnhealthy("svc", "127.0.0.1", 8080, "offline", "ACTIVE");
        checker.markHealthy("svc", "127.0.0.1", 8080, "ACTIVE");
        var recovered = checker.getHealth("svc", "127.0.0.1", 8080);
        assertTrue(recovered.isHealthy()); assertFalse(recovered.isDegradedCheckMode());
        assertNull(recovered.getUnhealthyReason());
    }

    @Test
    void recentActiveProbeKeepsIdleButHealthyNodeInTheHealthCache() {
        checker.markHealthy("svc", "127.0.0.1", 8080, "ACTIVE");
        var observation = checker.getHealth("svc", "127.0.0.1", 8080);
        observation.setLastRequestTime(System.currentTimeMillis() - 700_000);
        checker.cleanupExpired();
        assertSame(observation, checker.getHealth("svc", "127.0.0.1", 8080));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullSyncReconcilesSuppressedTransitionsAndIncludesDiagnosticDetails() {
        ReflectionTestUtils.setField(checker, "networkFlapThreshold", 1);
        var rest = mock(RestTemplate.class);
        var discovery = mock(AdminServiceDiscovery.class);
        when(discovery.getAdminUrl()).thenReturn("http://admin");
        ReflectionTestUtils.setField(checker, "restTemplate", rest);
        ReflectionTestUtils.setField(checker, "adminServiceDiscovery", discovery);
        ReflectionTestUtils.setField(checker, "instanceId", "gateway-test");
        checker.markUnhealthy("svc", "127.0.0.1", 8080, "TCP_UNREACHABLE", "ACTIVE");
        checker.initializeInstance("svc", "127.0.0.2", 8080);
        assertEquals(0, checker.getBatchQueueSize());
        checker.syncCurrentHealthStatus();
        ArgumentCaptor<HttpEntity> sent = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).postForEntity(eq("http://admin/api/gateway/health/sync"), sent.capture(), eq(Void.class));
        var observations = (List<InstanceHealth>) sent.getValue().getBody();
        assertEquals(1, observations.size());
        assertEquals("TCP_UNREACHABLE", observations.get(0).getUnhealthyReason());
        assertFalse(observations.get(0).isHealthy());
        assertNotNull(observations.get(0).getLastActiveCheckTime());
    }
}
