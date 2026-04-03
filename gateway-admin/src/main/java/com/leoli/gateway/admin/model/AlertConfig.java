package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Alert configuration entity.
 * Stores email recipients and threshold settings for monitoring alerts.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "alert_config")
public class AlertConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;

    @Column(name = "config_name", nullable = false, length = 100)
    private String configName = "Default Alert Config";

    /**
     * Email recipients in JSON array format.
     * Example: ["admin@example.com", "ops@example.com"]
     */
    @Column(name = "email_recipients", nullable = false, columnDefinition = "TEXT")
    private String emailRecipients;

    /**
     * Email language: zh (Chinese) or en (English)
     */
    @Column(name = "email_language", length = 10)
    private String emailLanguage = "zh";

    /**
     * Threshold configuration in JSON format.
     * Contains thresholds for CPU, memory, HTTP, instance, thread monitoring.
     */
    @Column(name = "threshold_config", columnDefinition = "TEXT")
    private String thresholdConfig;

    /**
     * Whether this alert config is enabled
     */
    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}