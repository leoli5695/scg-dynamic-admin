package com.leoli.gateway.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the per-record bound in {@link FilterChainTracker}.
 * <p>
 * The traceId a record is keyed by is CLIENT-SUPPLIED: {@code TraceIdGlobalFilter} takes an inbound
 * {@code traceparent} trace-id or {@code X-Trace-Id} before falling back to generating one. A caller
 * that sends a constant value therefore lands every request in the SAME {@code FilterChainRecord},
 * and {@code MAX_RECORDS} bounds the number of records - not the size of one. Before the cap, that
 * record was never evicted and grew by ~40 executions per request forever, while
 * {@code getTotalDurationMs()} copied the whole list on every filter end (O(N^2)).
 */
class FilterChainTrackerTest {

    /** Roughly the number of GlobalFilters a request passes through. */
    private static final int FILTERS_PER_REQUEST = 40;

    /** Must stay in sync with FilterChainRecord.MAX_EXECUTIONS. */
    private static final int MAX_EXECUTIONS_PER_RECORD = 256;

    private static final int MAX_RECORDS = 1000;

    private void simulateRequest(FilterChainTracker tracker, String traceId, boolean success) {
        long chainStart = System.nanoTime();
        for (int i = 0; i < FILTERS_PER_REQUEST; i++) {
            FilterChainTracker.FilterExecution execution =
                    tracker.startFilter(traceId, "Filter" + i, i, chainStart);
            tracker.markPreEnd(execution);
            tracker.markPostStart(execution);
            tracker.endFilter(execution, success, null);
        }
    }

    @Test
    @DisplayName("a constant client-supplied traceId cannot grow one record without bound")
    void reusedTraceIdDoesNotGrowOneRecordForever() {
        FilterChainTracker tracker = new FilterChainTracker();
        String constantTraceId = "0af7651916cd43dd8448eb211c80319c";

        // 2000 requests x 40 filters = 80_000 executions, all against one traceId.
        for (int request = 0; request < 2000; request++) {
            simulateRequest(tracker, constantTraceId, true);
        }

        FilterChainTracker.FilterChainRecord indexed = tracker.getRecordForTrace(constantTraceId);
        assertNotNull(indexed, "the traceId must still resolve to a record");
        int held = (Integer) indexed.toMap().get("filterCount");
        assertTrue(held <= MAX_EXECUTIONS_PER_RECORD,
                "one record held " + held + " executions; the cap is " + MAX_EXECUTIONS_PER_RECORD);

        assertTrue(tracker.getRecentRecords().size() <= MAX_RECORDS,
                "the rolling window must still bound the number of retained records");

        long retained = tracker.getRecentRecords().stream()
                .mapToLong(record -> (Integer) record.toMap().get("filterCount"))
                .sum();
        assertTrue(retained <= (long) MAX_RECORDS * MAX_EXECUTIONS_PER_RECORD,
                "retained " + retained + " executions in total - heap is not bounded");
    }

    @Test
    @DisplayName("distinct traceIds are unaffected: one record per request, exact counts")
    void distinctTraceIdsKeepOneRecordEach() {
        FilterChainTracker tracker = new FilterChainTracker();

        simulateRequest(tracker, "trace-a", true);
        simulateRequest(tracker, "trace-b", false);

        assertEquals(FILTERS_PER_REQUEST,
                (Integer) tracker.getRecordForTrace("trace-a").toMap().get("filterCount"));
        assertEquals(FILTERS_PER_REQUEST, tracker.getRecordForTrace("trace-a").getSuccessCount());
        assertEquals(0, tracker.getRecordForTrace("trace-a").getFailureCount());

        assertEquals(0, tracker.getRecordForTrace("trace-b").getSuccessCount());
        assertEquals(FILTERS_PER_REQUEST, tracker.getRecordForTrace("trace-b").getFailureCount());
        assertEquals(2, tracker.getRecentRecords().size());
    }

    @Test
    @DisplayName("the incremental aggregates reproduce the old copy-and-scan duration")
    void totalDurationUsesChainStartAndMaxEnd() {
        FilterChainTracker tracker = new FilterChainTracker();
        long chainStart = System.nanoTime();

        FilterChainTracker.FilterExecution first = tracker.startFilter("t", "First", 1, chainStart);
        tracker.markPreEnd(first);
        tracker.markPostStart(first);
        tracker.endFilter(first, true, null);

        FilterChainTracker.FilterExecution last = tracker.startFilter("t", "Last", 2, chainStart);
        tracker.markPreEnd(last);
        tracker.markPostStart(last);
        tracker.endFilter(last, true, null);

        long duration = tracker.getRecordForTrace("t").getTotalDurationMs();
        long expectedFromRawValues = (last.getEndTime() - chainStart) / 1_000_000;
        assertEquals(expectedFromRawValues, duration,
                "duration must span chainStart to the latest filter end");
    }

    @Test
    @DisplayName("the deprecated overload (chainStart 0) falls back to min(startTime)")
    @SuppressWarnings("deprecation")
    void legacyStartFilterFallsBackToMinStartTime() throws Exception {
        FilterChainTracker tracker = new FilterChainTracker();

        FilterChainTracker.FilterExecution first = tracker.startFilter("legacy", "First", 1);
        tracker.endFilter(first, true, null);
        Thread.sleep(10);
        FilterChainTracker.FilterExecution last = tracker.startFilter("legacy", "Last", 2);
        tracker.endFilter(last, true, null);

        long duration = tracker.getRecordForTrace("legacy").getTotalDurationMs();
        assertTrue(duration >= 9 && duration < 60_000,
                "fallback duration should reflect the gap between the two filters, got " + duration + "ms");
    }

    @Test
    @DisplayName("a slow trace increments slowRequestCount once, not once per filter")
    void slowTraceCountedOnce() throws Exception {
        FilterChainTracker tracker = new FilterChainTracker();
        tracker.setSlowThresholdMs(1);
        long chainStart = System.nanoTime();

        for (int i = 0; i < 10; i++) {
            FilterChainTracker.FilterExecution execution =
                    tracker.startFilter("slow", "Filter" + i, i, chainStart);
            tracker.markPreEnd(execution);
            Thread.sleep(2);
            tracker.markPostStart(execution);
            tracker.endFilter(execution, true, null);
        }

        assertEquals(1, tracker.getSlowRequestCount());
    }

    @Test
    @DisplayName("a fresh record reports zeroed aggregates")
    void emptyRecordIsZeroed() {
        FilterChainTracker.FilterChainRecord record =
                new FilterChainTracker.FilterChainRecord("empty");

        assertEquals(0, record.getTotalDurationMs());
        assertEquals(0, record.getSuccessCount());
        assertEquals(0, record.getFailureCount());
        assertFalse(record.isFull());
    }
}
