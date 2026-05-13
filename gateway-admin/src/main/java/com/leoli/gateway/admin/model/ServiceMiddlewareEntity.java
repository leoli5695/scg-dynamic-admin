package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 服务中间件映射实体
 * 
 * 存储服务依赖的中间件Exporter地址映射表。
 * 网关控制台只存映射，不存监控数据。
 * AI分析时通过此映射查询Prometheus。
 * 
 * @author leoli
 */
@Data
@Entity
@Table(name = "service_middleware", indexes = {
    @Index(name = "idx_service_name", columnList = "service_name"),
    @Index(name = "idx_instance_address", columnList = "instance_address"),
    @Index(name = "idx_service_middleware_type", columnList = "service_name, middleware_type"),
    @Index(name = "idx_updated_at", columnList = "updated_at")
}, uniqueConstraints = {
    // 按服务实例隔离：同一服务同一实例同一中间件类型只能有一条记录
    @UniqueConstraint(columnNames = {"service_name", "instance_address", "middleware_type"})
})
public class ServiceMiddlewareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 服务名称
     * 示例：seckill-service, order-service
     */
    @Column(name = "service_name", nullable = false, length = 255)
    private String serviceName;

    /**
     * 服务实例地址
     * 示例：192.168.1.100:8080
     */
    @Column(name = "instance_address", length = 100)
    private String instanceAddress;

    /**
     * 中间件类型
     * 示例：redis, rocketmq, mysql, elasticsearch, kafka
     */
    @Column(name = "middleware_type", nullable = false, length = 50)
    private String middlewareType;

    /**
     * 中间件主机地址
     * 示例：redis-cluster, mysql-master
     */
    @Column(name = "middleware_host", length = 100)
    private String middlewareHost;

    /**
     * 中间件端口
     * 示例：6379, 3306
     */
    @Column(name = "middleware_port")
    private Integer middlewarePort;

    /**
     * Prometheus Exporter地址
     * 示例：redis-exporter:9121, mysql-exporter:9104
     * AI通过此地址查询Prometheus
     */
    @Column(name = "exporter_url", length = 200)
    private String exporterUrl;

    /**
     * 中间件版本（可选）
     */
    @Column(name = "middleware_version", length = 50)
    private String middlewareVersion;

    /**
     * 附加标签（可选）
     * JSON格式，用于AI分析时过滤
     */
    @Column(name = "labels", columnDefinition = "TEXT")
    private String labels;

    /**
     * 是否启用监控
     */
    @Column(name = "monitoring_enabled", columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean monitoringEnabled = true;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间（每次服务上报更新）
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 最后上报时间
     */
    @Column(name = "last_report_time")
    private LocalDateTime lastReportTime;
}