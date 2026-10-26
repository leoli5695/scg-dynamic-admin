package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.GatewayTraceConfigRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/traces/config} 契约测试。
 *
 * <p>回归重点：这个端点曾经硬编码返回 captureAll=true / samplingRate=50，而生产实际是
 * capture-all=false、sampling-rate=10 —— 控制台于是长期宣称「正常请求 50% 采样」，而那些请求
 * 一条都没有被记录。修复后配置只能来自网关心跳上报，没上报就明说没上报，绝不编数字。
 */
class RequestTraceControllerConfigTest {

    private final GatewayTraceConfigRegistry registry = new GatewayTraceConfigRegistry();

    private final RequestTraceController controller =
            new RequestTraceController(null, null, null, null, null, registry);

    @SuppressWarnings("unchecked")
    private Map<String, Object> body() {
        Map<String, Object> body = controller.getTraceConfig().getBody();
        assertNotNull(body);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> block(String key) {
        return (Map<String, Object>) body().get(key);
    }

    private void report(boolean captureAll, int samplingRate) {
        Map<String, Object> config = new HashMap<>();
        config.put("captureAll", captureAll);
        config.put("captureErrors", true);
        config.put("captureSlow", true);
        config.put("slowThresholdMs", 3000L);
        config.put("samplingRate", samplingRate);
        config.put("samplingRateForErrors", 100);
        registry.record("gw-under-test", config);
    }

    @Test
    @DisplayName("没有网关上报时不得编造配置：reported=false，且不带任何采样数字")
    void doesNotFabricateConfigWhenNothingReported() {
        Map<String, Object> gateway = block("gateway");

        assertEquals(false, gateway.get("reported"));
        assertFalse(gateway.containsKey("samplingRate"), "不能出现任何看起来像真实值的采样率");
        assertFalse(gateway.containsKey("captureAll"));

        String note = (String) body().get("note");
        assertNotNull(note);
        assertTrue(note.contains("尚未收到"), "note 应说明还没收到上报，而不是给一个默认采样率");
    }

    @Test
    @DisplayName("capture-all 关闭时如实上报，并说明正常请求根本不入库")
    void reportsRealValuesWhenCaptureAllOff() {
        report(false, 10);

        Map<String, Object> gateway = block("gateway");
        assertEquals(true, gateway.get("reported"));
        assertEquals(false, gateway.get("captureAll"));
        assertEquals(10, gateway.get("samplingRate"), "必须是网关上报的真实值，不是旧的硬编码 50");

        String description = (String) gateway.get("description");
        assertTrue(description.contains("capture-all=false"), description);
        assertTrue(description.contains("正常请求不会入库"), description);
    }

    @Test
    @DisplayName("capture-all 打开时按真实采样率描述")
    void reportsRealValuesWhenCaptureAllOn() {
        report(true, 100);

        Map<String, Object> gateway = block("gateway");
        assertEquals(true, gateway.get("captureAll"));
        assertEquals(100, gateway.get("samplingRate"));

        String description = (String) gateway.get("description");
        assertTrue(description.contains("正常请求 100% 采样"), description);
        assertFalse(description.contains("capture-all=false"), description);
    }

    @Test
    @DisplayName("下游服务段不得谎称有服务在上报")
    void serviceBlockDoesNotClaimReporting() {
        report(true, 100);

        Map<String, Object> service = block("service");
        assertEquals(false, service.get("reporting"));
        assertFalse(service.containsKey("sampleRate"), "没有服务上报时不能给出采样率");
        assertTrue(((String) service.get("description")).contains("gateway-trace-starter"));
    }

    @Test
    @DisplayName("多实例配置分歧时在 note 里说明显示的是最近一次上报")
    void noteFlagsDivergentFleet() {
        report(true, 100);
        Map<String, Object> other = new HashMap<>();
        other.put("captureAll", false);
        other.put("samplingRate", 10);
        registry.record("gw-other", other);

        String note = (String) body().get("note");
        assertTrue(note.contains("不一致"), note);
    }
}
