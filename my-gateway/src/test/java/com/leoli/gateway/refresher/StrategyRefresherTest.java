package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static com.leoli.gateway.constants.GatewayConfigConstants.GROUP;
import static com.leoli.gateway.constants.GatewayConfigConstants.STRATEGIES_INDEX;
import static com.leoli.gateway.constants.GatewayConfigConstants.STRATEGY_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the periodic reconcile that stands in for config-center push, which
 * nacos-client 3.0.3 never receives from nacos-server 2.5.4.
 * <p>
 * The behaviour under test was measured broken on a live deployment: a new strategy ID
 * was picked up by the index-driven safety net, but editing an already-indexed strategy
 * produced no callback and no reconcile, because comparing ID sets cannot see an edit.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyRefresherTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private ConfigCenterService configService;

    private StrategyRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new StrategyRefresher(strategyManager, configService, new ObjectMapper());
    }

    private static String strategyJson(String id, int threshold) {
        return "{\"strategyId\":\"" + id + "\",\"strategyName\":\"n\","
                + "\"strategyType\":\"RATE_LIMITER\",\"scope\":\"GLOBAL\",\"enabled\":true,"
                + "\"config\":{\"threshold\":" + threshold + "}}";
    }

    private void stubIndex(String... ids) {
        when(configService.getConfig(STRATEGIES_INDEX, GROUP))
                .thenReturn("[" + String.join(",", java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"").toList()) + "]");
    }

    private void stubStrategy(String id, int threshold) {
        when(configService.getConfig(STRATEGY_PREFIX + id, GROUP)).thenReturn(strategyJson(id, threshold));
    }

    private int appliedThreshold() {
        ArgumentCaptor<StrategyDefinition> captor = ArgumentCaptor.forClass(StrategyDefinition.class);
        verify(strategyManager).putStrategy(eq("s1"), captor.capture());
        return ((Number) captor.getValue().getConfig().get("threshold")).intValue();
    }

    @Test
    @DisplayName("an edit to an already-indexed strategy is applied - the case ID-set sync misses")
    void appliesEditToKnownStrategy() {
        stubIndex("s1");
        stubStrategy("s1", 100);
        refresher.periodicSyncStrategies();
        assertEquals(100, appliedThreshold());

        stubStrategy("s1", 200);
        refresher.periodicSyncStrategies();

        ArgumentCaptor<StrategyDefinition> captor = ArgumentCaptor.forClass(StrategyDefinition.class);
        verify(strategyManager, times(2)).putStrategy(eq("s1"), captor.capture());
        List<StrategyDefinition> applied = captor.getAllValues();
        assertEquals(100, ((Number) applied.get(0).getConfig().get("threshold")).intValue());
        assertEquals(200, ((Number) applied.get(1).getConfig().get("threshold")).intValue(),
                "the edited threshold must reach StrategyManager without a restart");
    }

    @Test
    @DisplayName("unchanged content is not reapplied, so a steady-state cycle is silent")
    void doesNotReapplyUnchangedContent() {
        stubIndex("s1");
        stubStrategy("s1", 100);
        refresher.periodicSyncStrategies();

        refresher.periodicSyncStrategies();
        refresher.periodicSyncStrategies();

        verify(strategyManager, times(1)).putStrategy(eq("s1"), any());
    }

    @Test
    @DisplayName("a strategy dropped from the index is removed, not left serving")
    void removesStrategyMissingFromIndex() {
        stubIndex("s1");
        stubStrategy("s1", 100);
        refresher.periodicSyncStrategies();

        when(configService.getConfig(STRATEGIES_INDEX, GROUP)).thenReturn("[]");
        refresher.periodicSyncStrategies();

        verify(strategyManager).removeStrategy("s1");
    }

    @Test
    @DisplayName("an unreachable (null) index must not wipe live strategies")
    void nullIndexDoesNotWipe() {
        stubIndex("s1");
        stubStrategy("s1", 100);
        refresher.periodicSyncStrategies();

        when(configService.getConfig(STRATEGIES_INDEX, GROUP)).thenReturn(null);
        refresher.periodicSyncStrategies();

        verify(strategyManager, never()).clear();
        verify(strategyManager, never()).removeStrategy(any());
    }

    @Test
    @DisplayName("a corrupt index must not be read as 'everything was deleted'")
    void corruptIndexDoesNotWipe() {
        stubIndex("s1");
        stubStrategy("s1", 100);
        refresher.periodicSyncStrategies();

        when(configService.getConfig(STRATEGIES_INDEX, GROUP)).thenReturn("{not a list");
        refresher.periodicSyncStrategies();

        verify(strategyManager, never()).clear();
        verify(strategyManager, never()).removeStrategy(any());
    }

    @Test
    @DisplayName("a disabled strategy is not put into the manager")
    void disabledStrategyIsNotApplied() {
        stubIndex("s1");
        when(configService.getConfig(STRATEGY_PREFIX + "s1", GROUP))
                .thenReturn(strategyJson("s1", 100).replace("\"enabled\":true", "\"enabled\":false"));

        refresher.periodicSyncStrategies();

        verify(strategyManager, never()).putStrategy(any(), any());
        verify(strategyManager).removeStrategy("s1");
    }
}
