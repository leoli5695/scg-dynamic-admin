package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Access log entry entity for storing historical access logs.
 * Supports real-time log collection from Fluent Bit and historical queries.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "access_log_entries", indexes = {
    @Index(name = "idx_log_time_instance", columnList = "log_timestamp, instance_id"),
    @Index(name = "idx_log_instance_time", columnList = "instance_id, log_timestamp"),
    @Index(name = "idx_log_trace_id", columnList = "trace_id"),
    @Index(name = "idx_log_route_id", columnList = "route_id"),
    @Index(name = "idx_log_service_id", columnList = "service_id"),
    @Index(name = "idx_log_status_code", columnList = "status_code, log_timestamp"),
    @Index(name = "idx_log_method", columnList = "method, log_timestamp"),
    @Index(name = "idx_log_created_at", columnList = "created_at")
})
public class AccessLogEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", length = 36, nullable = false)
    private String instanceId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "route_id", length = 100)
    private String routeId;

    @Column(name = "service_id", length = 100)
    private String serviceId;

    @Column(name = "method", length = 10, nullable = false)
    private String method;

    @Column(name = "path", length = 500, nullable = false)
    private String path;

    @Column(name = "query_string", length = 1000)
    private String queryString;

    @Column(name = "client_ip", length = 50)
    private String clientIp;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "status_code", nullable = false)
    private Integer statusCode;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    @Column(name = "auth_type", length = 50)
    private String authType;

    @Column(name = "auth_policy", length = 100)
    private String authPolicy;

    @Column(name = "auth_user", length = 200)
    private String authUser;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "log_timestamp", nullable = false)
    private LocalDateTime logTimestamp;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}