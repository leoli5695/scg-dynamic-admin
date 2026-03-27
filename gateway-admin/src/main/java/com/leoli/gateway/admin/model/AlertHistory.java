package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Alert history entity.
 * Records all sent alerts for auditing and history viewing.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "alert_history", indexes = {
    @Index(name = "idx_alert_type", columnList = "alert_type"),
    @Index(name = "idx_alert_created_at", columnList = "created_at")
})
public class AlertHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Alert type: CPU, MEMORY, HTTP_ERROR, RESPONSE_TIME, INSTANCE, THREAD
     */
    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    /**
     * Alert level: INFO, WARNING, ERROR, CRITICAL
     */
    @Column(name = "alert_level", nullable = false, length = 20)
    private String alertLevel;

    /**
     * Metric name that triggered the alert
     */
    @Column(name = "metric_name", length = 100)
    private String metricName;

    /**
     * Current metric value
     */
    @Column(name = "metric_value", precision = 20, scale = 4)
    private BigDecimal metricValue;

    /**
     * Threshold value that was exceeded
     */
    @Column(name = "threshold_value", precision = 20, scale = 4)
    private BigDecimal thresholdValue;

    /**
     * Alert title
     */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /**
     * Alert content (AI generated)
     */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /**
     * Email recipients who received this alert
     */
    @Column(name = "email_recipients", columnDefinition = "TEXT")
    private String emailRecipients;

    /**
     * Alert status: SENT, FAILED
     */
    @Column(name = "status", length = 20)
    private String status = "SENT";

    /**
     * Error message if sending failed
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}