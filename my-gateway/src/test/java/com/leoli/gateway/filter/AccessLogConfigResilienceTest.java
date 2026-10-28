package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.filter.accesslog.AccessLogGlobalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessLogConfigResilienceTest {
    @Test
    void missingOrMalformedOptionalLogConfigPreservesTheLastValidConfig() {
        var center = mock(ConfigCenterService.class);
        var filter = new AccessLogGlobalFilter(center, null, null, null, null, new ObjectMapper(), null);
        try {
            when(center.getConfig(anyString(), anyString())).thenReturn("{\"enabled\":false}");
            ReflectionTestUtils.invokeMethod(filter, "loadConfig");
            Object valid = ReflectionTestUtils.getField(filter, "config");
            when(center.getConfig(anyString(), anyString())).thenThrow(new IllegalStateException("config center unavailable"));
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(filter, "loadConfig"));
            assertSame(valid, ReflectionTestUtils.getField(filter, "config"));
            ReflectionTestUtils.invokeMethod(filter, "applyConfig", "null");
            assertSame(valid, ReflectionTestUtils.getField(filter, "config"));
        } finally {
            filter.destroy();
        }
    }
}
