package com.leoli.gateway.admin.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Token Quota Configuration Entity.
 * Stores tenant-level token quota settings for AI Gateway.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "token_quota", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"tenant_id"})
})
public class TokenQuotaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenant identifier (unique).
     */
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    /**
     * Monthly token quota.
     * 0 means no monthly limit.
     */
    @Column(name = "monthly_quota", nullable = false)
    private Long monthlyQuota = 0L;

    /**
     * Daily token quota.
     * 0 means no daily limit.
     */
    @Column(name = "daily_quota", nullable = false)
    private Long dailyQuota = 0L;

    /**
     * Burst quota - allows temporary over-limit usage.
     */
    @Column(name = "burst_quota")
    private Long burstQuota = 0L;

    /**
     * Quota period type: MONTHLY, DAILY, BOTH.
     */
    @Column(name = "quota_period", length = 20)
    private String quotaPeriod = "BOTH";

    /**
     * Response format for usage parsing: OPENAI, ANTHROPIC, CUSTOM.
     */
    @Column(name = "response_format", length = 20)
    private String responseFormat = "OPENAI";

    /**
     * Alert threshold percentage (0-100).
     * Alert triggered when usage exceeds this percentage.
     */
    @Column(name = "alert_threshold")
    private Integer alertThreshold = 80;

    /**
     * Tenant name for display.
     */
    @Column(name = "tenant_name", length = 128)
    private String tenantName;

    /**
     * Tenant contact email for alerts.
     */
    @Column(name = "contact_email", length = 128)
    private String contactEmail;

    /**
     * Whether quota is enabled for this tenant.
     */
    @Column(name = "enabled")
    private Boolean enabled = true;

    /**
     * Gateway instance ID (optional, for instance-specific quotas).
     */
    @Column(name = "instance_id", length = 64)
    private String instanceId;

    /**
     * Notes/comments about this quota.
     */
    @Column(name = "notes", length = 512)
    private String notes;

    /**
     * Created time.
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * Updated time.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}