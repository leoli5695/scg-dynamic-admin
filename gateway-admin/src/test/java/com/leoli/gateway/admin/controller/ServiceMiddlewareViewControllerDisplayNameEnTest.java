package com.leoli.gateway.admin.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests (no Spring context) for the additive displayNameEn output in
 * {@link ServiceMiddlewareViewController}. The private lookups are invoked reflectively,
 * following the module's existing AccessLogPathSafetyTest style.
 */
class ServiceMiddlewareViewControllerDisplayNameEnTest {

    private static final Pattern HAN_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");

    private ServiceMiddlewareViewController controller;

    @BeforeEach
    void setUp() {
        controller = new ServiceMiddlewareViewController(null, null, null, null);
    }

    @Test
    @DisplayName("EN and ZH metric display-name maps have exactly the same key set")
    void englishAndChineseMapsHaveIdenticalKeySets() {
        Map<String, String> zh = ServiceMiddlewareViewController.METRIC_DISPLAY_NAME_MAP;
        Map<String, String> en = ServiceMiddlewareViewController.METRIC_DISPLAY_NAME_EN_MAP;
        assertFalse(zh.isEmpty(), "metric name map should not be empty");
        assertEquals(zh.keySet(), en.keySet(),
                "displayNameEn map keys drifted from displayName map keys");
    }

    @Test
    @DisplayName("every English display name is non-blank and contains no Chinese characters")
    void englishValuesAreActuallyEnglish() {
        for (Map.Entry<String, String> entry : ServiceMiddlewareViewController.METRIC_DISPLAY_NAME_EN_MAP.entrySet()) {
            String value = entry.getValue();
            assertFalse(value == null || value.isBlank(), "blank EN name for " + entry.getKey());
            assertFalse(HAN_PATTERN.matcher(value).find(),
                    "Chinese characters left in EN name for " + entry.getKey() + ": " + value);
        }
    }

    @Test
    @DisplayName("spot checks: EN translations added, ZH display names unchanged")
    void spotCheckTranslations() {
        assertEquals("Memory Used", displayNameEn("redis_memory_used_bytes"));
        assertEquals("Cache Hit Rate", displayNameEn("redis_hit_rate"));
        assertEquals("Cache Hit Rate", displayNameEn("hit_rate"));
        assertEquals("Slow Queries", displayNameEn("mysql_global_status_slow_queries"));
        assertEquals("Buffer Pool Data Pages", displayNameEn("mysql_innodb_buffer_pool_pages_data"));
        assertEquals("Message Lag", displayNameEn("rocketmq_group_diff"));
        assertEquals("Max Lag Consumer Group", displayNameEn("maxLagConsumerGroup"));

        // Existing Chinese behavior must be untouched.
        assertEquals("内存使用量", displayName("redis_memory_used_bytes"));
        assertEquals("缓存命中率", displayName("redis_hit_rate"));
        assertEquals("慢查询数", displayName("mysql_global_status_slow_queries"));
        assertEquals("缓冲池数据页", displayName("mysql_innodb_buffer_pool_pages_data"));
    }

    @Test
    @DisplayName("unknown metric keys fall back to the key itself in both languages")
    void unknownMetricFallsBackToKey() {
        assertEquals("some_unknown_metric", displayNameEn("some_unknown_metric"));
        assertEquals("some_unknown_metric", displayName("some_unknown_metric"));
    }

    @Test
    @DisplayName("transformMetricsForDisplay adds displayNameEn next to the unchanged displayName")
    void transformMetricsForDisplayAddsDisplayNameEn() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("redis_memory_used_bytes", 1024);
        metrics.put("custom_unknown", 7);

        List<Map<String, Object>> display =
                ReflectionTestUtils.invokeMethod(controller, "transformMetricsForDisplay", metrics);

        assertEquals(2, display.size());
        Map<String, Object> redis = byName(display, "redis_memory_used_bytes");
        assertEquals("内存使用量", redis.get("displayName"));
        assertEquals("Memory Used", redis.get("displayNameEn"));
        assertEquals(1024.0, redis.get("value"));

        Map<String, Object> unknown = byName(display, "custom_unknown");
        assertEquals("custom_unknown", unknown.get("displayName"));
        assertEquals("custom_unknown", unknown.get("displayNameEn"));
    }

    private String displayName(String metricKey) {
        return ReflectionTestUtils.invokeMethod(controller, "getMetricDisplayName", metricKey);
    }

    private String displayNameEn(String metricKey) {
        return ReflectionTestUtils.invokeMethod(controller, "getMetricDisplayNameEn", metricKey);
    }

    private static Map<String, Object> byName(List<Map<String, Object>> display, String name) {
        return display.stream().filter(m -> name.equals(m.get("name"))).findFirst().orElseThrow();
    }
}