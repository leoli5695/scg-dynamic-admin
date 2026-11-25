package com.leoli.gateway.admin.scheduler;

import com.leoli.gateway.admin.service.ProviderHealthProbeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class ProviderHealthProbeSchedulerTest {

    private final ProviderHealthProbeService probeService = mock(ProviderHealthProbeService.class);
    private final ProviderHealthProbeScheduler scheduler = new ProviderHealthProbeScheduler(probeService);

    @Test
    void enabledDelegatesToProbeService() {
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        scheduler.probeProviders();
        verify(probeService).probeAll();
    }

    @Test
    void disabledSkipsProbe() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);
        scheduler.probeProviders();
        verifyNoInteractions(probeService);
    }

    @Test
    void probeFailureIsSwallowed() {
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        doThrow(new RuntimeException("boom")).when(probeService).probeAll();
        scheduler.probeProviders(); // must not throw
        verify(probeService).probeAll();
    }
}