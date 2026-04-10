package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Stress Test Record.
 * Stores stress test configuration and results.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "stress_test")
public class StressTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false, length = 100)
    private String instanceId;

    @Column(name = "test_name", length = 100)
    private String testName;

    @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    @Column(name = "method", length = 10)
    private String method = "GET";

    @Column(name = "headers", columnDefinition = "TEXT")
    private String headers;  // JSON format

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "concurrent_users", nullable = false)
    private Integer concurrentUsers = 10;

    @Column(name = "total_requests", nullable = false)
    private Integer totalRequests = 1000;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;  // Alternative to total_requests

    @Column(name = "ramp_up_seconds")
    private Integer rampUpSeconds = 0;

    @Column(name = "status", length = 20)
    private String status = "CREATED";  // CREATED, RUNNING, COMPLETED, STOPPED, FAILED

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "actual_requests")
    private Integer actualRequests = 0;

    @Column(name = "successful_requests")
    private Integer successfulRequests = 0;

    @Column(name = "failed_requests")
    private Integer failedRequests = 0;

    @Column(name = "min_response_time_ms")
    private Double minResponseTimeMs;

    @Column(name = "max_response_time_ms")
    private Double maxResponseTimeMs;

    @Column(name = "avg_response_time_ms")
    private Double avgResponseTimeMs;

    @Column(name = "p50_response_time_ms")
    private Double p50ResponseTimeMs;

    @Column(name = "p90_response_time_ms")
    private Double p90ResponseTimeMs;

    @Column(name = "p95_response_time_ms")
    private Double p95ResponseTimeMs;

    @Column(name = "p99_response_time_ms")
    private Double p99ResponseTimeMs;

    @Column(name = "requests_per_second")
    private Double requestsPerSecond;

    @Column(name = "error_rate")
    private Double errorRate;

    @Column(name = "throughput_kbps")
    private Double throughputKbps;

    @Column(name = "response_time_distribution", columnDefinition = "TEXT")
    private String responseTimeDistribution;  // JSON histogram data

    @Column(name = "error_distribution", columnDefinition = "TEXT")
    private String errorDistribution;  // JSON error breakdown

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "CREATED";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}