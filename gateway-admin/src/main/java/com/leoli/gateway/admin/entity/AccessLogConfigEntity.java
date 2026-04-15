package com.leoli.gateway.admin.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Access Log Configuration Entity.
 * Stored per gateway instance.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "access_log_config", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"instance_id"})
})
public class AccessLogConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Gateway instance ID (foreign key to gateway_instances).
     * Null means global default config.
     */
    @Column(name = "instance_id", length = 64)
    private String instanceId;

    /**
     * Whether access logging is enabled.
     */
    @Column(name = "enabled")
    private Boolean enabled = false;

    /**
     * Deployment mode: LOCAL, DOCKER, K8S, CUSTOM.
     */
    @Column(name = "deploy_mode", length = 20)
    private String deployMode = "LOCAL";

    /**
     * Log file directory path.
     */
    @Column(name = "log_directory", length = 256)
    private String logDirectory = "./logs/access";

    /**
     * Log file name pattern.
     */
    @Column(name = "file_name_pattern", length = 128)
    private String fileNamePattern = "access-{yyyy-MM-dd}.log";

    /**
     * Log format: JSON or TEXT.
     */
    @Column(name = "log_format", length = 20)
    private String logFormat = "JSON";

    /**
     * Log level: MINIMAL, NORMAL, VERBOSE.
     */
    @Column(name = "log_level", length = 20)
    private String logLevel = "NORMAL";

    /**
     * Log request headers.
     */
    @Column(name = "log_request_headers")
    private Boolean logRequestHeaders = true;

    /**
     * Log response headers.
     */
    @Column(name = "log_response_headers")
    private Boolean logResponseHeaders = true;

    /**
     * Log request body.
     */
    @Column(name = "log_request_body")
    private Boolean logRequestBody = false;

    /**
     * Log response body.
     */
    @Column(name = "log_response_body")
    private Boolean logResponseBody = false;

    /**
     * Maximum body length to log (characters).
     */
    @Column(name = "max_body_length")
    private Integer maxBodyLength = 2048;

    /**
     * Sampling rate (1-100).
     */
    @Column(name = "sampling_rate")
    private Integer samplingRate = 100;

    /**
     * Maximum file size before rotation (MB).
     */
    @Column(name = "max_file_size_mb")
    private Integer maxFileSizeMb = 100;

    /**
     * Maximum number of backup files to keep.
     */
    @Column(name = "max_backup_files")
    private Integer maxBackupFiles = 30;

    /**
     * Whether to log to console.
     */
    @Column(name = "log_to_console")
    private Boolean logToConsole = true;

    /**
     * Whether to include auth info.
     */
    @Column(name = "include_auth_info")
    private Boolean includeAuthInfo = true;

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