package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.filter.ratelimit.HybridRateLimiterFilter;
import com.leoli.gateway.filter.ratelimit.MultiDimRateLimiterFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the periodic fallback for the rate-limit reset command channel.
 *
 * <p>The hazard under test is replay: unlike the index-driven refreshers this channel
 * carries one-shot commands that stay published in the config center, so a naive poll
 * would clear the local rate-limit windows every cycle - resetting the counters that are
 * supposed to be counting, i.e. silently disabling the limiter. De-duplication is by the
 * timestamp gateway-admin stamps on every publish.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitResetRefresherTest {

    private static final String COMMAND_DATA_ID = "config.gateway.command.rate-limit-reset";
    private static final String GROUP = "DEFAULT_GROUP";

    @Mock
    private ConfigCenterService configService;

    @Mock
    private HybridRateLimiterFilter hybridRateLimiterFilter;

    @Mock
    private MultiDimRateLimiterFilter multiDimRateLimiterFilter;

    private RateLimitResetRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new RateLimitResetRefresher(configService, new ObjectMapper());
        // @Autowired(required = false) fields - no constructor to inject them through
        ReflectionTestUtils.setField(refresher, "hybridRateLimiterFilter", hybridRateLimiterFilter);
        ReflectionTestUtils.setField(refresher, "multiDimRateLimiterFilter", multiDimRateLimiterFilter);
    }

    private static String command(long timestamp) {
        return "{\"strategyId\":\"s1\",\"strategyType\":\"RATE_LIMITER\",\"scope\":\"ROUTE\","
                + "\"routeId\":\"r1\",\"timestamp\":" + timestamp + "}";
    }

    private void stubCommand(String content) {
        when(configService.getConfig(COMMAND_DATA_ID, GROUP)).thenReturn(content);
    }

    @Test
    @DisplayName("the command already published at startup is not replayed - a restart is itself a clean start")
    void doesNotReplayTheCommandPresentAtStartup() {
        stubCommand(command(1000L));
        refresher.init();

        refresher.periodicSyncResetCommand();
        refresher.periodicSyncResetCommand();

        verify(hybridRateLimiterFilter, never()).invalidateCache(any());
        verify(multiDimRateLimiterFilter, never()).invalidateCache(any());
    }

    @Test
    @DisplayName("a newer command is applied exactly once no matter how many cycles see it")
    void appliesANewerCommandExactlyOnce() {
        stubCommand(command(1000L));
        refresher.init();

        stubCommand(command(2000L));
        refresher.periodicSyncResetCommand();
        refresher.periodicSyncResetCommand();
        refresher.periodicSyncResetCommand();

        verify(hybridRateLimiterFilter, times(1)).invalidateCache(any());
    }

    @Test
    @DisplayName("an older command than the last applied one is ignored")
    void ignoresAnOlderCommand() {
        stubCommand(command(2000L));
        refresher.init();

        stubCommand(command(1000L));
        refresher.periodicSyncResetCommand();

        verify(hybridRateLimiterFilter, never()).invalidateCache(any());
    }

    @Test
    @DisplayName("a command with no usable timestamp is skipped rather than replayed every cycle")
    void ignoresACommandWithoutTimestamp() {
        stubCommand(null);
        refresher.init();

        stubCommand("{\"strategyId\":\"s1\",\"scope\":\"ROUTE\"}");
        refresher.periodicSyncResetCommand();
        refresher.periodicSyncResetCommand();

        verify(hybridRateLimiterFilter, never()).invalidateCache(any());
    }

    @Test
    @DisplayName("a MULTI_DIM command goes to the multi-dim filter, not the hybrid one")
    void routesMultiDimCommandToItsOwnFilter() {
        stubCommand(null);
        refresher.init();

        stubCommand("{\"strategyId\":\"s1\",\"strategyType\":\"MULTI_DIM_RATE_LIMITER\","
                + "\"scope\":\"GLOBAL\",\"timestamp\":5000}");
        refresher.periodicSyncResetCommand();

        verify(multiDimRateLimiterFilter, times(1)).invalidateCache(any());
        verify(hybridRateLimiterFilter, never()).invalidateCache(any());
    }

    @Test void customMultiDimPrefixResetsOnlyItsStrategyNamespaceAndLegacyKeys() {
        stubCommand(null);
        refresher.init();
        stubCommand("{\"strategyId\":\"s1\",\"strategyType\":\"MULTI_DIM_RATE_LIMITER\","
                + "\"scope\":\"GLOBAL\",\"keyPrefix\":\"custom:\",\"timestamp\":5000}");
        refresher.periodicSyncResetCommand();
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(multiDimRateLimiterFilter).invalidateCache(captor.capture());
        assertTrue(captor.getValue().contains("custom:s1:*"));
        assertFalse(captor.getValue().contains("multi_rate:*"));
        assertFalse(captor.getValue().contains("custom:*"));
    }

    @Test
    @DisplayName("an absent command is a no-op")
    void absentCommandIsNoOp() {
        stubCommand(null);
        refresher.init();

        refresher.periodicSyncResetCommand();

        verify(hybridRateLimiterFilter, never()).invalidateCache(any());
    }

    @Test
    @DisplayName("the key patterns actually cover the strategy being reset")
    void resetsTheRightKeyPatterns() {
        stubCommand(null);
        refresher.init();

        stubCommand(command(3000L));
        refresher.periodicSyncResetCommand();

        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(hybridRateLimiterFilter).invalidateCache(captor.capture());
        Set<String> captured = captor.getValue();
        assertTrue(captured.contains("rate_limit:s1:*"),
                "expected the strategy-scoped pattern, got " + captured);
        assertTrue(captured.contains("rate_limit:combined:r1:*"),
                "expected the route-scoped pattern, got " + captured);
    }
}
