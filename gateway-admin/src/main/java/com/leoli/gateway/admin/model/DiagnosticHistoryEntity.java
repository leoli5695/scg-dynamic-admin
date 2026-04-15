package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Diagnostic History Entity.
 * Stores historical diagnostic results for trend analysis.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "diagnostic_history")
public class DiagnosticHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", length = 64)
    private String instanceId;

    @Column(name = "diagnostic_type", length = 20, nullable = false)
    private String diagnosticType;

    @Column(name = "overall_score", nullable = false)
    private Integer overallScore;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "duration_ms")
    private Long durationMs;

    // Component status snapshots
    @Column(name = "database_status", length = 20)
    private String databaseStatus;

    @Column(name = "redis_status", length = 20)
    private String redisStatus;

    @Column(name = "config_center_status", length = 20)
    private String configCenterStatus;

    @Column(name = "routes_status", length = 20)
    private String routesStatus;

    @Column(name = "auth_status", length = 20)
    private String authStatus;

    @Column(name = "gateway_instances_status", length = 20)
    private String gatewayInstancesStatus;

    @Column(name = "performance_status", length = 20)
    private String performanceStatus;

    // Key metrics snapshots for trend analysis
    @Column(name = "gateway_qps")
    private Double gatewayQps;

    @Column(name = "gateway_error_rate")
    private Double gatewayErrorRate;

    @Column(name = "gateway_avg_latency_ms")
    private Double gatewayAvgLatencyMs;

    @Column(name = "gateway_heap_usage_percent")
    private Double gatewayHeapUsagePercent;

    @Column(name = "gateway_cpu_usage_percent")
    private Double gatewayCpuUsagePercent;

    @Column(name = "admin_heap_usage_percent")
    private Double adminHeapUsagePercent;

    // Recommendations
    @Column(name = "recommendations_count")
    private Integer recommendationsCount;

    @Column(name = "recommendations_summary", columnDefinition = "TEXT")
    private String recommendationsSummary;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}