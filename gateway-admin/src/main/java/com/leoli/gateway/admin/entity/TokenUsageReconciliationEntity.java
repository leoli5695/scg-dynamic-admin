package com.leoli.gateway.admin.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Schema mapping; the reconciliation service uses JDBC row locks for the state machine. */
@Data
@Entity
@Table(name = "token_usage_reconciliation", uniqueConstraints = @UniqueConstraint(name = "uk_usage_reconciliation", columnNames = "usage_id"),
        indexes = @Index(name = "idx_usage_reconciliation_status", columnList = "status,created_at"))
public class TokenUsageReconciliationEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "usage_id", nullable = false) private Long usageId;
    @Column(name = "tenant_id", length = 64, nullable = false) private String tenantId;
    @Column(name = "quota_month", length = 6) private String quotaMonth;
    @Column(name = "quota_day", length = 8) private String quotaDay;
    @Column(name = "prompt_tokens", nullable = false) private Integer promptTokens;
    @Column(name = "completion_tokens", nullable = false) private Integer completionTokens;
    @Column(name = "total_tokens", nullable = false) private Integer totalTokens;
    @Column(name = "charged_tokens", nullable = false) private Long chargedTokens;
    @Column(name = "monthly_delta", nullable = false) private Long monthlyDelta;
    @Column(name = "daily_delta", nullable = false) private Long dailyDelta;
    @Column(length = 20, nullable = false) private String status;
    @Column(length = 128, nullable = false) private String operator;
    @Column(length = 1000, nullable = false) private String reason;
    @Column(name = "original_usage", columnDefinition = "TEXT", nullable = false) private String originalUsage;
    @Column(name = "last_error", length = 1000) private String lastError;
    @Column(nullable = false) private Integer attempts;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "applied_at") private LocalDateTime appliedAt;
}
