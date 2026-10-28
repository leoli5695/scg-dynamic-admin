package com.leoli.gateway.center.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.leoli.gateway.center.spi.ConfigCenterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NacosConfigFallbackTest {
    @Mock ConfigService sdk;
    NacosConfigService configs;

    @BeforeEach
    void setup() {
        configs = new NacosConfigService(sdk);
    }

    @Test
    void servesCachedConfigDuringOutageThenUsesRecoveredConfig() throws Exception {
        when(sdk.getConfig("route", "g", 5000)).thenReturn("old")
                .thenThrow(new NacosException(500, "offline")).thenReturn("new");
        assertEquals("old", configs.getConfig("route", "g"));
        assertEquals("old", configs.getConfig("route", "g"));
        assertFalse(configs.isHealthy());
        assertEquals("new", configs.getConfig("route", "g"));
        assertTrue(configs.isHealthy());
    }

    @Test
    void missingFallbackThrowsInsteadOfPretendingTheKeyWasDeleted() throws Exception {
        when(sdk.getConfig(anyString(), anyString(), anyLong())).thenThrow(new NacosException(500, "offline"));
        assertThrows(IllegalStateException.class, () -> configs.getConfig("index", "g"));
    }

    @Test
    void groupsDoNotShareFallbacks() throws Exception {
        when(sdk.getConfig("route", "a", 5000)).thenReturn("a").thenThrow(new NacosException(500, "offline"));
        when(sdk.getConfig("route", "b", 5000)).thenThrow(new NacosException(500, "offline"));
        assertEquals("a", configs.getConfig("route", "a"));
        assertThrows(IllegalStateException.class, () -> configs.getConfig("route", "b"));
        assertEquals("a", configs.getConfig("route", "a"));
    }

    @Test
    void disconnectedSdkNullDoesNotInvalidateCachedConfig() throws Exception {
        when(sdk.getConfig("index", "g", 5000)).thenReturn("[\"r1\"]").thenReturn(null);
        when(sdk.getServerStatus()).thenReturn("UP").thenReturn("DOWN");
        assertEquals("[\"r1\"]", configs.getConfig("index", "g"));
        assertEquals("[\"r1\"]", configs.getConfig("index", "g"));
        assertFalse(configs.isHealthy());
    }

    @Test
    void sdkSnapshotCanBootstrapFallbackButDisconnectedMissCannot() throws Exception {
        when(sdk.getServerStatus()).thenReturn("DOWN");
        when(sdk.getConfig("route", "g", 5000)).thenReturn("disk-snapshot");
        assertEquals("disk-snapshot", configs.getConfig("route", "g"));
        assertFalse(configs.isHealthy());
        assertThrows(IllegalStateException.class, () -> configs.getConfig("unknown", "g"));
    }

    @Test
    void confirmedReadDeletionIsNotResurrectedOnOutage() throws Exception {
        when(sdk.getConfig("route", "g", 5000)).thenReturn("old").thenReturn(null)
                .thenThrow(new NacosException(500, "offline"));
        assertEquals("old", configs.getConfig("route", "g"));
        assertNull(configs.getConfig("route", "g"));
        assertThrows(IllegalStateException.class, () -> configs.getConfig("route", "g"));
    }

    @Test
    void listenerDeletionInvalidatesFallbackAndDisconnectedBlankDoesNot() throws Exception {
        ConfigCenterService.ConfigListener consumer = mock(ConfigCenterService.ConfigListener.class);
        configs.addListener("route", "g", consumer);
        ArgumentCaptor<Listener> listener = ArgumentCaptor.forClass(Listener.class);
        verify(sdk).addListener(eq("route"), eq("g"), listener.capture());
        listener.getValue().receiveConfigInfo("from-push");
        when(sdk.getServerStatus()).thenReturn("DOWN");
        listener.getValue().receiveConfigInfo(null);
        verify(consumer, never()).onConfigChange("route", "g", null);
        when(sdk.getConfig("route", "g", 5000)).thenThrow(new NacosException(500, "offline"));
        assertEquals("from-push", configs.getConfig("route", "g"));
        when(sdk.getServerStatus()).thenReturn("UP");
        listener.getValue().receiveConfigInfo(null);
        verify(consumer).onConfigChange("route", "g", null);
        assertThrows(IllegalStateException.class, () -> configs.getConfig("route", "g"));
    }
}
