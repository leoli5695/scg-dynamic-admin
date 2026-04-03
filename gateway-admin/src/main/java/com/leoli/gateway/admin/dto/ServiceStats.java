package com.leoli.gateway.admin.dto;

import lombok.Data;

/**
 * Service instance statistics DTO for analytics.
 *
 * @author leoli
 */
@Data
public class ServiceStats {
    
    /**
     * Service instance name/address
     */
    private String serviceInstance;
    
    /**
     * Total request count
     */
    private Long requestCount;
    
    /**
     * Average latency in milliseconds
     */
    private Double avgLatencyMs;
    
    /**
     * Load percentage (0.0 - 1.0)
     */
    private Double loadPercent;
    
    public ServiceStats() {
    }
    
    public ServiceStats(String serviceInstance, Long requestCount, Double avgLatencyMs) {
        this.serviceInstance = serviceInstance;
        this.requestCount = requestCount;
        this.avgLatencyMs = avgLatencyMs;
    }
    
    /**
     * Set load percentage based on total requests
     */
    public void setLoadPercent(Long totalRequests) {
        if (totalRequests != null && totalRequests > 0) {
            this.loadPercent = (double) this.requestCount / totalRequests;
        } else {
            this.loadPercent = 0.0;
        }
    }
}
