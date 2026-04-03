package com.leoli.gateway.admin.dto;

import lombok.Data;

/**
 * HTTP method statistics DTO for analytics.
 *
 * @author leoli
 */
@Data
public class MethodStats {
    
    /**
     * HTTP method: GET, POST, PUT, DELETE, etc.
     */
    private String method;
    
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
    
    public MethodStats() {
    }
    
    public MethodStats(String method, Long requestCount, Double avgLatencyMs, Long errorCount) {
        this.method = method;
        this.requestCount = requestCount;
        this.avgLatencyMs = avgLatencyMs;
        this.errorCount = errorCount;
        this.errorRate = requestCount != null && requestCount > 0 
            ? (double) errorCount / requestCount : 0.0;
    }
}