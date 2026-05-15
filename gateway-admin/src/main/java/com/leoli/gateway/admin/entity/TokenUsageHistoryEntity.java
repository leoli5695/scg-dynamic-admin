package com.leoli.gateway.admin.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Token Usage History Entity.
 * Records individual token usage events for auditing and analysis.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "token_usage_history", indexes = {
    @Index(name = "idx_tenant_time", columnList = "tenant_id,request_time"),
    @Index(name = "idx_trace_id", columnList = "trace_id"),
    @Index(name = "idx_route_id", columnList = "route_id")
})
public class TokenUsageHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenant identifier.
     */
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    /**
     * Trace ID for linking to request trace.
     */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /**
     * Route ID that processed the request.
     */
    @Column(name = "route_id", length = 64)
    private String routeId;

    /**
     * AI model used (e.g., gpt-4, claude-3).
     */
    @Column(name = "model", length = 64)
    private String model;

    /**
     * Prompt/input tokens consumed.
     */
    @Column(name = "prompt_tokens")
    private Integer promptTokens = 0;

    /**
     * Completion/output tokens consumed.
     */
    @Column(name = "completion_tokens")
    private Integer completionTokens = 0;

    /**
     * Total tokens consumed.
     */
    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens = 0;

    /**
     * Request timestamp.
     */
    @Column(name = "request_time", nullable = false)
    private LocalDateTime requestTime;

    /**
     * Gateway instance ID.
     */
    @Column(name = "instance_id", length = 64)
    private String instanceId;

    /**
     * Response format detected.
     */
    @Column(name = "response_format", length = 20)
    private String responseFormat;

    /**
     * Request latency in milliseconds.
     */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /**
     * Created time.
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.requestTime == null) {
            this.requestTime = LocalDateTime.now();
        }
    }
}