package com.leoli.gateway.admin.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TimeRangeMetricsSnapshotTest {
    private final PrometheusService prometheus = mock(PrometheusService.class);

    @Test
    @SuppressWarnings("unchecked")
    void missingMetricsAndWindowsStayMissingAndEachQueryRunsOnce() {
        var seen = new HashSet<String>();
        when(prometheus.queryRange(anyString(), anyLong(), anyLong(), anyString())).thenAnswer(call -> {
            String identity = call.getArgument(0) + ":" + call.getArgument(1) + ":" + call.getArgument(2) + ":" + call.getArgument(3);
            assertTrue(seen.add(identity), "Evidence must not re-query metrics");
            String query = call.getArgument(0);
            long start = call.getArgument(1);
            return query.contains("process_cpu_usage") && start == 1000L
                    ? List.of(Map.of("value", .9), Map.of("value", .6)) : List.of();
        });
        var result = new AiMetricsCollector(prometheus).collectTimeRangeMetrics(1000, 1600);
        Map<String, Integer> counts = (Map<String, Integer>) result.evidence().get("samples");
        List<String> missing = (List<String>) result.evidence().get("missing");
        assertEquals(2, counts.get("process_cpu_usage.analysis"));
        assertTrue(missing.containsAll(List.of("response_time.analysis", "full_gc_time.analysis", "old_used.analysis", "process_cpu_usage.recovery")));
        assertTrue(result.metricsData().contains("平均=75.0000"));
        assertTrue(result.metricsData().contains("进程CPU峰值=90.0000%"));
        assertTrue(result.metricsData().contains("Full GC平均耗时峰值: 数据缺失"));
        assertFalse(result.metricsData().contains("已恢复"));
        assertFalse(result.metricsData().contains("GC正常"));
        assertFalse(result.metricsData().contains("NaN"));
        List<Map<String, Object>> queries = (List<Map<String, Object>>) result.evidence().get("queries");
        assertEquals(seen.size(), queries.size());
        for (var query : queries) {
            assertEquals(counts.get(query.get("key")), query.get("samples"));
            assertTrue(result.metricsData().contains("query=" + query.get("query")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void zerosAreValidButNonFiniteSamplesAndQueryErrorsAreNot() {
        when(prometheus.queryRange(anyString(), anyLong(), anyLong(), anyString())).thenReturn(
                List.of(Map.of("value", 0), Map.of("value", Double.NaN), Map.of("value", Double.POSITIVE_INFINITY)));
        var result = TimeRangeMetricsSnapshot.collect(prometheus, 1000, 1600);
        assertTrue(((List<?>) result.evidence().get("missing")).isEmpty());
        assertTrue(((Map<String, Integer>) result.evidence().get("samples")).values().stream().allMatch(count -> count == 1));
        assertTrue(result.metricsData().contains("基准必须大于0"));
        assertFalse(result.metricsData().contains("NaN"));
        assertFalse(result.metricsData().contains("Infinity"));

        when(prometheus.queryRange(anyString(), anyLong(), anyLong(), anyString())).thenThrow(new IllegalStateException("unavailable"));
        var failed = TimeRangeMetricsSnapshot.collect(prometheus, 1000, 1600);
        assertTrue(((List<Map<String, Object>>) failed.evidence().get("queries")).stream().allMatch(query -> "ERROR".equals(query.get("status"))));
        assertFalse(failed.metricsData().contains("未超过阈值"));
    }
}
