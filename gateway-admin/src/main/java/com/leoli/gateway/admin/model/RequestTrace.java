package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Request trace entity.
 * Stores detailed request information for debugging and replay.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "request_trace", indexes = {
    @Index(name = "idx_trace_time", columnList = "trace_time"),
    @Index(name = "idx_route_id", columnList = "route_id"),
    @Index(name = "idx_status_code", columnList = "status_code"),
    @Index(name = "idx_trace_instance", columnList = "instance_id"),
    @Index(name = "idx_trace_instance_route", columnList = "instance_id, route_id"),
    @Index(name = "idx_trace_instance_time", columnList = "instance_id, trace_time")
})
public class RequestTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;

    /**
     * Unique trace ID (for correlation)
     */
    @Column(name = "trace_id", length = 36, unique = true)
    private String traceId;

    /**
     * Route ID this request matched
     */
    @Column(name = "route_id", length = 100)
    private String routeId;

    /**
     * Request method: GET, POST, PUT, DELETE, etc.
     */
    @Column(name = "method", length = 10)
    private String method;

    /**
     * Request URI (path + query string)
     */
    @Column(name = "uri", length = 2000)
    private String uri;

    /**
     * Request path (without query string)
     */
    @Column(name = "path", length = 500)
    private String path;

    /**
     * Query string
     */
    @Column(name = "query_string", length = 1000)
    private String queryString;

    /**
     * Request headers (JSON format)
     */
    @Column(name = "request_headers", columnDefinition = "TEXT")
    private String requestHeaders;

    /**
     * Request body (for POST/PUT)
     */
    @Column(name = "request_body", columnDefinition = "MEDIUMTEXT")
    private String requestBody;

    /**
     * Response status code
     */
    @Column(name = "status_code")
    private Integer statusCode;

    /**
     * Response headers (JSON format)
     */
    @Column(name = "response_headers", columnDefinition = "TEXT")
    private String responseHeaders;

    /**
     * Response body (truncated if too large)
     */
    @Column(name = "response_body", columnDefinition = "MEDIUMTEXT")
    private String responseBody;

    /**
     * Target service instance
     */
    @Column(name = "target_instance", length = 255)
    private String targetInstance;

    /**
     * Request latency in milliseconds
     */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /**
     * Error message if failed
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Error type: TIMEOUT, CONNECTION_ERROR, UPSTREAM_ERROR, etc.
     */
    @Column(name = "error_type", length = 50)
    private String errorType;

    /**
     * Client IP address
     */
    @Column(name = "client_ip", length = 50)
    private String clientIp;

    /**
     * User agent
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /**
     * Trace type: ERROR, SLOW, ALL
     */
    @Column(name = "trace_type", length = 20)
    private String traceType = "ERROR";

    /**
     * Whether this trace can be replayed
     */
    @Column(name = "replayable")
    private Boolean replayable = true;

    /**
     * Replay count
     */
    @Column(name = "replay_count")
    private Integer replayCount = 0;

    /**
     * Last replay result
     */
    @Column(name = "last_replay_result", columnDefinition = "TEXT")
    private String lastReplayResult;

    /**
     * Time when this trace was recorded
     */
    @Column(name = "trace_time")
    private LocalDateTime traceTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (traceTime == null) {
            traceTime = LocalDateTime.now();
        }
        createdAt = LocalDateTime.now();
    }
}