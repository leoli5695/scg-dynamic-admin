package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Email configuration entity.
 * Stores SMTP settings for sending alert emails.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "email_config")
public class EmailConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Configuration name
     */
    @Column(name = "config_name", length = 100)
    private String configName = "Default SMTP Config";

    /**
     * SMTP server host
     */
    @Column(name = "smtp_host", length = 255, nullable = false)
    private String smtpHost;

    /**
     * SMTP server port
     */
    @Column(name = "smtp_port", nullable = false)
    private Integer smtpPort = 465;

    /**
     * SMTP username (email address)
     */
    @Column(name = "smtp_username", length = 255, nullable = false)
    private String smtpUsername;

    /**
     * SMTP password (authorization code for some providers)
     */
    @Column(name = "smtp_password", length = 255, nullable = false)
    private String smtpPassword;

    /**
     * Use SSL/TLS
     */
    @Column(name = "use_ssl")
    private Boolean useSsl = true;

    /**
     * Use STARTTLS
     */
    @Column(name = "use_starttls")
    private Boolean useStartTls = false;

    /**
     * From email address
     */
    @Column(name = "from_email", length = 255)
    private String fromEmail;

    /**
     * From display name
     */
    @Column(name = "from_name", length = 100)
    private String fromName;

    /**
     * Whether this config is enabled
     */
    @Column(name = "enabled")
    private Boolean enabled = true;

    /**
     * Test email status
     */
    @Column(name = "test_status", length = 20)
    private String testStatus;

    /**
     * Last test time
     */
    @Column(name = "last_test_time")
    private LocalDateTime lastTestTime;

    /**
     * Last test error message
     */
    @Column(name = "last_test_error", length = 500)
    private String lastTestError;

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

    /**
     * Get from address in format: "Name <email@example.com>"
     */
    public String getFromAddress() {
        if (fromName != null && !fromName.isEmpty()) {
            return fromName + " <" + (fromEmail != null ? fromEmail : smtpUsername) + ">";
        }
        return fromEmail != null ? fromEmail : smtpUsername;
    }
}