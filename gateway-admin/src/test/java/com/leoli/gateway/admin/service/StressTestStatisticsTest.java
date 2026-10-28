package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.StressTest;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class StressTestStatisticsTest {
    @Test void oneSampleAndFailedRequestsUseRealLatenciesAndByteCounts() {
        AtomicLong now = new AtomicLong(); var stats = new StressTestStatistics(now::get);
        stats.record(199, false, 6, "HTTP 429"); now.set(1_000_000_000L);
        var one = stats.getSummary();
        assertEquals(199, one.getP99ResponseTime()); assertEquals(199, one.getAvgResponseTime());
        assertEquals(100, one.getErrorRate()); assertEquals(6.0 / 1024, one.getThroughputKbps());
        one.setTotalRequests(900);
        assertEquals(1, stats.getSummary().getTotalRequests());
        stats.record(1, true, 3, null); now.set(2_000_000_000L);
        var test = new StressTest(); stats.applyTo(test, new ObjectMapper());
        assertEquals(2, test.getActualRequests()); assertEquals(1, test.getSuccessfulRequests());
        assertEquals(1, test.getFailedRequests()); assertEquals(100, test.getAvgResponseTimeMs());
        assertEquals(1.0, test.getRequestsPerSecond());
    }

    @Test void timelineAndRpsDoNotResetTheCumulativeDenominatorOnPolling() {
        AtomicLong now = new AtomicLong(); var stats = new StressTestStatistics(now::get);
        stats.record(10, true, 0, null); now.set(1_000_000_000L); stats.getTimeline();
        now.set(3_000_000_000L); var timeline = stats.getTimeline();
        assertEquals(0, timeline.getLast().getRps()); assertEquals(1.0 / 3, stats.getSummary().getRequestsPerSecond());
        assertEquals(0, stats.getLiveRps());
    }

    @Test void concurrentCompletionsKeepSummaryConsistentAndTimelineBounded() throws Exception {
        AtomicLong now = new AtomicLong(); var stats = new StressTestStatistics(now::get);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> {
                for (int request = 0; request < 10000; request++) stats.record(10, request % 2 == 0, 6, "HTTP 500");
            }));
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        assertEquals(80000, stats.getSummary().getTotalRequests());
        assertEquals(40000, stats.getSummary().getFailedRequests());
        assertEquals(10, stats.getSummary().getAvgResponseTime());
        for (int i = 0; i < 400; i++) { now.addAndGet(2_000_000_000L); stats.getTimeline(); }
        assertEquals(300, stats.getTimeline().size());
    }
}
