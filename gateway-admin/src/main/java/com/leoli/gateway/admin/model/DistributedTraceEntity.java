package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 分布式链路追踪实体
 * 
 * 存储下游服务上报的Trace数据。
 * 与网关FilterChain数据关联，形成完整链路。
 * 
 * @author leoli
 */
@Data
@Entity
@Table(name = "distributed_trace", indexes = {
    @Index(name = "idx_trace_id", columnList = "trace_id"),
    @Index(name = "idx_service_name", columnList = "service_name"),
    @Index(name = "idx_trace_time", columnList = "trace_time"),
    @Index(name = "idx_service_time", columnList = "service_name, trace_time")
})
public class DistributedTraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * TraceId（与网关生成的X-Trace-Id对应）
     */
    @Column(name = "trace_id", nullable = false, length = 36)
    private String traceId;

    /**
     * 服务名称
     */
    @Column(name = "service_name", nullable = false, length = 255)
    private String serviceName;

    /**
     * 请求路径
     */
    @Column(name = "path", length = 500)
    private String path;

    /**
     * HTTP方法
     */
    @Column(name = "method", length = 10)
    private String method;

    /**
     * 总耗时（毫秒）
     */
    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    /**
     * HTTP状态码
     */
    @Column(name = "status_code")
    private Integer statusCode;

    /**
     * 是否成功
     */
    @Column(name = "success")
    private Boolean success;

    /**
     * 错误信息
     */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * 客户端IP
     */
    @Column(name = "client_ip", length = 50)
    private String clientIp;

    /**
     * Span列表（JSON格式）
     * 包含每个操作的名称、耗时、状态
     */
    @Column(name = "spans", columnDefinition = "TEXT")
    private String spans;

    /**
     * 追踪时间
     */
    @Column(name = "trace_time")
    private LocalDateTime traceTime;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 是否慢请求
     */
    @Column(name = "is_slow")
    private Boolean isSlow;

    /**
     * 网关实例ID（可选，用于多实例场景）
     */
    @Column(name = "gateway_instance_id", length = 36)
    private String gatewayInstanceId;
}