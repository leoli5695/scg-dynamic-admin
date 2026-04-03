package com.leoli.gateway.admin.dto;

import lombok.Data;

/**
 * Route statistics DTO for analytics.
 *
 * @author leoli
 */
@Data
public class RouteStats {
    
    /**
     * Route ID
     */
    private String routeId;
    
    /**
     * Total request count
     */
    private Long requestCount;
    
    /**
     * Average latency in milliseconds
     */
    private Double avgLatencyMs;
    
    /**
     * Error count (4xx and 5xx)
     */
    private Long errorCount;
    
    /**
     * Error rate (0.0 - 1.0)
     */
    private Double errorRate;
    
    /**
     * P95 latency in milliseconds
     */
    private Long p95LatencyMs;
    
    /**
     * P99 latency in milliseconds
     */
    private Long p99LatencyMs;
    
    public RouteStats() {
    }
    
    public RouteStats(String routeId, Long requestCount, Double avgLatencyMs, Long errorCount) {
        this.routeId = routeId;
        this.requestCount = requestCount;
        this.avgLatencyMs = avgLatencyMs;
        this.errorCount = errorCount;
        this.errorRate = requestCount > 0 ? (double) errorCount / requestCount : 0.0;
    }
}
