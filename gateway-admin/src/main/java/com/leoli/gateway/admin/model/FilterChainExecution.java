package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Filter chain execution entity.
 * Stores individual filter execution details for each request trace.
 * Enables deep performance analysis and bottleneck identification.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "filter_chain_execution", indexes = {
    @Index(name = "idx_fce_trace_id", columnList = "trace_id"),
    @Index(name = "idx_fce_filter_name", columnList = "filter_name"),
    @Index(name = "idx_fce_created_at", columnList = "created_at")
})
public class FilterChainExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Trace ID - links to request_trace table
     */
    @Column(name = "trace_id", length = 64, nullable = false)
    private String traceId;

    /**
     * Filter name (e.g., Authentication, RateLimit, CircuitBreaker)
     */
    @Column(name = "filter_name", length = 100, nullable = false)
    private String filterName;

    /**
     * Filter execution order (determines sequence)
     */
    @Column(name = "filter_order", nullable = false)
    private Integer filterOrder;

    /**
     * Execution duration in milliseconds
     */
    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    /**
     * Execution duration in microseconds (for precision)
     */
    @Column(name = "duration_micros", nullable = false)
    private Long durationMicros;

    /**
     * Whether the filter execution succeeded
     */
    @Column(name = "success", nullable = false)
    private Boolean success = true;

    /**
     * Error message if execution failed
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Time percentage of total request duration
     */
    @Column(name = "time_percentage")
    private Double timePercentage;

    /**
     * Instance ID where the filter executed
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;

    /**
     * Creation timestamp
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}