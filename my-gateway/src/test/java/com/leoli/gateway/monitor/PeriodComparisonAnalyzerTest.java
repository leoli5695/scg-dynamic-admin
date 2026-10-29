package com.leoli.gateway.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PeriodComparisonAnalyzerTest {
    private FilterChainTracker.FilterExecution execution(String filter, long ms) {
        var e = new FilterChainTracker.FilterExecution("trace", filter, 1);
        e.setStartTime(1_000_000); e.setEndTime((ms + 1) * 1_000_000);
        e.setPreEndTime(1_000_000); e.setPostStartTime(1_000_000);
        e.setSuccess(true);
        return e;
    }
    private FilterChainTracker.FilterChainRecord record(LocalDateTime time, long ms) {
        var r = new FilterChainTracker.FilterChainRecord("trace");
        ReflectionTestUtils.setField(r, "createdAt", time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        r.addExecution(execution("proxy", ms));
        return r;
    }
    @Test void usesActualWindowAndCountsSlowRequestOncePerFilter() {
        var tracker = mock(FilterChainTracker.class);
        var now = LocalDateTime.now();
        var slow = record(now.minusMinutes(5), 1500);
        slow.addExecution(execution("proxy", 1200));
        var fast = record(now.minusMinutes(2), 100);
        var historical = record(now.minusMinutes(90), 1200);
        var boundary = record(now, 1500);
        when(tracker.getRecentRecords()).thenReturn(List.of(slow, fast, historical, boundary));
        when(tracker.getSlowThresholdMs()).thenReturn(1000L);
        var analyzer = new PeriodComparisonAnalyzer(mock(HistoricalDataTracker.class), tracker);
        var current = analyzer.getMetricsForPeriod(now.minusHours(1), now).get("proxy");
        assertEquals(1, current.getSlowRequestCount());
        assertEquals(3, current.getTotalExecutions());
        var prior = analyzer.getMetricsForPeriod(now.minusHours(2), now.minusHours(1)).get("proxy");
        assertEquals(1, prior.getSlowRequestCount());
        assertEquals(1, prior.getTotalExecutions());
        verify(tracker, never()).getFilterStats();
    }
    @Test void missingHistoryIsExplicitlyInsufficientRatherThanZero() {
        var tracker = mock(FilterChainTracker.class);
        when(tracker.getRecentRecords()).thenReturn(List.of());
        var analyzer = new PeriodComparisonAnalyzer(mock(HistoricalDataTracker.class), tracker);
        var result = analyzer.compare(new PeriodComparisonAnalyzer.ComparisonRequest());
        assertEquals("insufficient_data", result.getStatus());
        assertEquals(false, result.getDataScope().get("completeHistory"));
        assertNotNull(result.toMap().get("dataScope"));
        assertTrue(result.getFilterComparisons().isEmpty());
    }
    @Test void invalidTimeRangeSerializesAsError() {
        var analyzer = new PeriodComparisonAnalyzer(mock(HistoricalDataTracker.class), mock(FilterChainTracker.class));
        var request = new PeriodComparisonAnalyzer.ComparisonRequest();
        request.setComparisonType(PeriodComparisonAnalyzer.ComparisonType.CUSTOM);
        var now = LocalDateTime.now();
        request.setCustomRanges(new PeriodComparisonAnalyzer.TimeRanges(now, now.minusHours(1), now.minusHours(2), now.minusHours(1)));
        assertEquals("error", analyzer.compare(request).toMap().get("status"));
    }
}
