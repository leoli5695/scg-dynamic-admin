package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Audit log entity for tracking configuration changes.
 *
 * @author leoli
 */
@Data
@Entity(name = "audit_logs")
@Table(name = "audit_logs", indexes = {
    // 1. 分页查询主索引：时间优先（ORDER BY created_at DESC）
    // 注意：JPA @Index 不支持 DESC，数据库默认 ASC，但 MySQL 可用降序索引
    @Index(name = "idx_audit_time_instance", columnList = "created_at, instance_id"),

    // 2. 按实例 + 时间（带条件筛选的分页）
    @Index(name = "idx_audit_instance_time", columnList = "instance_id, created_at"),

    // 3. 按目标类型 + 时间
    @Index(name = "idx_audit_target_type_time", columnList = "target_type, created_at"),

    // 4. 按操作类型 + 时间
    @Index(name = "idx_audit_operation_time", columnList = "operation_type, created_at"),

    // 5. 按目标查询（查看特定路由/服务的变更历史）
    @Index(name = "idx_audit_target", columnList = "target_type, target_id, created_at"),

    // 6. 复合索引：实例 + 目标类型 + 操作类型（高级筛选）
    @Index(name = "idx_audit_instance_target_op", columnList = "instance_id, target_type, operation_type, created_at"),

    // 7. 单字段时间索引（时间范围查询、清理过期日志）
    @Index(name = "idx_audit_created_at", columnList = "created_at")
})
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;

    @Column(length = 100)
    private String operator;

    /**
     * Operator type: MANUAL (user operation) or AI_COPILOT (AI assistant operation).
     */
    @Column(name = "operator_type", length = 20)
    private String operatorType;

    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType;

    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_id", length = 255)
    private String targetId;

    @Column(name = "target_name", length = 255)
    private String targetName;

    @Column(columnDefinition = "TEXT")
    private String oldValue;

    @Column(columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
