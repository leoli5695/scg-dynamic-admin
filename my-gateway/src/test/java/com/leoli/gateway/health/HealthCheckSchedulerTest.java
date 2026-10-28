package com.leoli.gateway.health;

import com.leoli.gateway.config.HealthCheckProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthCheckSchedulerTest {
    @Test
    void disabledSchedulesDoNotDiscoverOrProbeNodes() {
        var discovery = mock(InstanceDiscoveryService.class);
        var checker = mock(ActiveHealthChecker.class);
        var properties = new HealthCheckProperties(); properties.setEnabled(false);
        var scheduler = scheduler(discovery, checker, properties);
        scheduler.performRegularHealthCheck();
        scheduler.performDegradedHealthCheck();
        scheduler.performStableHealthCheck();
        verifyNoInteractions(discovery, checker);
        scheduler.destroy();
    }

    @Test
    void regularStableAndDegradedSchedulesAllExecuteTheirCandidates() {
        var discovery = mock(InstanceDiscoveryService.class);
        var checker = mock(ActiveHealthChecker.class);
        when(discovery.findInstancesForRegularCheck()).thenReturn(List.of(new InstanceDiscoveryService.InstanceKey("svc", "127.0.0.1", 8001)));
        when(discovery.findInstancesForStableCheck()).thenReturn(List.of(new InstanceDiscoveryService.InstanceKey("svc", "127.0.0.1", 8002)));
        when(discovery.findInstancesForDegradedCheck()).thenReturn(List.of(new InstanceDiscoveryService.InstanceKey("svc", "127.0.0.1", 8003)));
        var scheduler = scheduler(discovery, checker, new HealthCheckProperties());
        scheduler.performRegularHealthCheck(); scheduler.performStableHealthCheck(); scheduler.performDegradedHealthCheck();
        verify(checker).probe("svc", "127.0.0.1", 8001);
        verify(checker).probe("svc", "127.0.0.1", 8002);
        verify(checker).probe("svc", "127.0.0.1", 8003);
        scheduler.destroy();
    }

    @Test
    void sharedEndpointIsProbedOncePerBatchRegardlessOfServiceAliases() {
        var first = new InstanceDiscoveryService.InstanceKey("a", "127.0.0.1", 8080);
        var second = new InstanceDiscoveryService.InstanceKey("b", "127.0.0.1", 8080);
        assertEquals(first, second);
        assertEquals(1, new java.util.HashSet<>(List.of(first, second)).size());
    }

    private HealthCheckScheduler scheduler(InstanceDiscoveryService discovery, ActiveHealthChecker checker, HealthCheckProperties properties) {
        var scheduler = new HealthCheckScheduler();
        ReflectionTestUtils.setField(scheduler, "instanceDiscovery", discovery);
        ReflectionTestUtils.setField(scheduler, "activeChecker", checker);
        ReflectionTestUtils.setField(scheduler, "healthCheckProperties", properties);
        ReflectionTestUtils.setField(scheduler, "checkBatchSize", 10);
        ReflectionTestUtils.setField(scheduler, "maxConcurrentPerBatch", 2);
        scheduler.init(); return scheduler;
    }
}
