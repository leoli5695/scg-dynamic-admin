package com.leoli.gateway.admin.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关上报的追踪配置登记表测试。
 * 回归重点：只保留最近一次上报、缺配置的心跳不能把已有值抹掉、多实例配置分歧可被识别。
 */
class GatewayTraceConfigRegistryTest {

    private final GatewayTraceConfigRegistry registry = new GatewayTraceConfigRegistry();

    private Map<String, Object> config(boolean captureAll, int samplingRate) {
        Map<String, Object> config = new HashMap<>();
        config.put("captureAll", captureAll);
        config.put("samplingRate", samplingRate);
        return config;
    }

    @Test
    @DisplayName("没有任何上报时 freshest 为空，调用方据此拒绝编造数值")
    void emptyWhenNothingReported() {
        assertTrue(registry.freshest().isEmpty());
        assertFalse(registry.isDivergent());
    }

    @Test
    @DisplayName("记录后能原样读回上报值")
    void recordsAndReturnsConfig() {
        registry.record("gw-1", config(false, 10));

        GatewayTraceConfigRegistry.Report report = registry.freshest().orElseThrow();
        assertEquals(false, report.config().get("captureAll"));
        assertEquals(10, report.config().get("samplingRate"));
        assertFalse(report.isStale(), "刚上报的配置不应被判为过期");
    }

    @Test
    @DisplayName("心跳缺少 traceConfig 时保留上一次的配置，不抹成空")
    void absentConfigDoesNotBlankPreviousValue() {
        registry.record("gw-1", config(true, 100));

        registry.record("gw-1", null);
        registry.record("gw-1", Map.of());

        assertEquals(100, registry.freshest().orElseThrow().config().get("samplingRate"));
    }

    @Test
    @DisplayName("instanceId 缺失时忽略，避免用 null 当键覆盖别的实例")
    void ignoresMissingInstanceId() {
        registry.record(null, config(true, 100));
        registry.record("", config(true, 100));

        assertTrue(registry.freshest().isEmpty());
    }

    @Test
    @DisplayName("freshest 返回最近一次上报，即使它来自另一个实例")
    void freshestPicksMostRecentAcrossInstances() throws InterruptedException {
        registry.record("gw-old", config(true, 100));
        Thread.sleep(5);
        registry.record("gw-new", config(false, 10));

        assertEquals(10, registry.freshest().orElseThrow().config().get("samplingRate"));
    }

    @Test
    @DisplayName("两个实例配置一致时不算分歧，不一致时算")
    void detectsDivergentConfigs() {
        registry.record("gw-1", config(true, 100));
        registry.record("gw-2", config(true, 100));
        assertFalse(registry.isDivergent());

        registry.record("gw-2", config(false, 10));
        assertTrue(registry.isDivergent());
    }

    @Test
    @DisplayName("登记表持有的配置不可被调用方改写")
    void storedConfigIsImmutable() {
        Map<String, Object> reported = config(true, 100);
        registry.record("gw-1", reported);

        // 上报方后续改动自己的 map 不应影响已登记的值
        reported.put("samplingRate", 1);
        assertEquals(100, registry.freshest().orElseThrow().config().get("samplingRate"));

        Map<String, Object> stored = registry.freshest().orElseThrow().config();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> stored.put("samplingRate", 2));
    }
}
