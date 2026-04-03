package com.leoli.gateway.admin.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Client IP statistics DTO for analytics.
 *
 * @author leoli
 */
@Data
public class ClientStats {
    
    /**
     * Client IP address
     */
    private String clientIp;
    
    /**
     * Total request count
     */
    private Long requestCount;
    
    /**
     * Average latency in milliseconds
     */
    private Double avgLatencyMs;
    
    /**
     * Last request time
     */
    private LocalDateTime lastRequestTime;
    
    public ClientStats() {
    }
    
    public ClientStats(String clientIp, Long requestCount, Double avgLatencyMs, LocalDateTime lastRequestTime) {
        this.clientIp = clientIp;
        this.requestCount = requestCount;
        this.avgLatencyMs = avgLatencyMs;
        this.lastRequestTime = lastRequestTime;
    }
}
