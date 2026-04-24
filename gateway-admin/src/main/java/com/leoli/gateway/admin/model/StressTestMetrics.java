package com.leoli.gateway.admin.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Time-series metrics data for stress test visualization.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StressTestMetrics {

    private Long testId;
    private String status;
    private double progress;

    // Time-series data points
    private List<MetricDataPoint> timeline = new ArrayList<>();

    // Current summary
    private SummaryMetrics summary = new SummaryMetrics();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricDataPoint {
        private long timestamp;          // Unix timestamp in milliseconds
        private double rps;              // Requests per second
        private double avgResponseTime;  // Average response time (ms)
        private double p95ResponseTime;  // P95 response time (ms)
        private double p99ResponseTime;  // P99 response time (ms)
        private double errorRate;        // Error rate percentage
        private long totalRequests;      // Cumulative total requests
        private long successRequests;    // Cumulative success requests
        private long failedRequests;     // Cumulative failed requests
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryMetrics {
        private long totalRequests;
        private long successRequests;
        private long failedRequests;
        private double avgResponseTime;
        private double minResponseTime;
        private double maxResponseTime;
        private double p50ResponseTime;
        private double p90ResponseTime;
        private double p95ResponseTime;
        private double p99ResponseTime;
        private double requestsPerSecond;
        private double errorRate;
        private double throughputKbps;
    }
}
