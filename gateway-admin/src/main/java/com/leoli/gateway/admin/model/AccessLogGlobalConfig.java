package com.leoli.gateway.admin.model;

import lombok.Data;
import java.util.List;

/**
 * Global Access Log Configuration.
 * Controls access logging for all routes globally.
 *
 * Deployment Mode Configuration:
 * - LOCAL: Logs to local file system path
 * - DOCKER: Logs to mounted volume path
 * - K8S: Logs to PVC mount path or stdout for log collection
 *
 * @author leoli
 */
@Data
public class AccessLogGlobalConfig {

    /**
     * Whether global access logging is enabled.
     */
    private boolean enabled = false;

    /**
     * Deployment mode: LOCAL, DOCKER, K8S, CUSTOM.
     */
    private DeployMode deployMode = DeployMode.LOCAL;

    /**
     * Log file directory path.
     * Different for each deployment mode:
     * - LOCAL: ./logs/access
     * - DOCKER: /app/logs/access
     * - K8S: /var/log/gateway/access (PVC mount)
     * - CUSTOM: user-defined path
     */
    private String logDirectory = "./logs/access";

    /**
     * Log file name pattern.
     * Supports date placeholders: {yyyy}, {MM}, {dd}
     * Default: access-{yyyy-MM-dd}.log
     */
    private String fileNamePattern = "access-{yyyy-MM-dd}.log";

    /**
     * Log format: JSON or TEXT.
     */
    private LogFormat logFormat = LogFormat.JSON;

    /**
     * Log level: MINIMAL, NORMAL, VERBOSE.
     * - MINIMAL: Only request line, status, duration
     * - NORMAL: + headers, basic info
     * - VERBOSE: + request/response body
     */
    private LogLevel logLevel = LogLevel.NORMAL;

    /**
     * Log request headers.
     */
    private boolean logRequestHeaders = true;

    /**
     * Log response headers.
     */
    private boolean logResponseHeaders = true;

    /**
     * Log request body (for POST/PUT/PATCH).
     */
    private boolean logRequestBody = false;

    /**
     * Log response body.
     */
    private boolean logResponseBody = false;

    /**
     * Maximum body length to log (characters).
     */
    private int maxBodyLength = 2048;

    /**
     * Sampling rate (1-100, 100 means log all requests).
     */
    private int samplingRate = 100;

    /**
     * Fields to mask (e.g., password, token, authorization).
     */
    private List<String> sensitiveFields = List.of(
            "password", "token", "authorization", "secret",
            "apiKey", "api_key", "accessKey", "access_key",
            "clientSecret", "client_secret", "cookie"
    );

    /**
     * Maximum file size before rotation (MB).
     */
    private int maxFileSizeMb = 100;

    /**
     * Maximum number of backup files to keep.
     */
    private int maxBackupFiles = 30;

    /**
     * Whether to log to console as well (useful for K8S log collection).
     */
    private boolean logToConsole = true;

    /**
     * Whether to include auth info in logs.
     */
    private boolean includeAuthInfo = true;

    /**
     * Deployment mode enum.
     */
    public enum DeployMode {
        LOCAL("Local file system"),
        DOCKER("Docker container with mounted volume"),
        K8S("Kubernetes with PVC or stdout"),
        CUSTOM("Custom path configuration");

        private final String description;

        DeployMode(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Log format enum.
     */
    public enum LogFormat {
        JSON("JSON format (recommended for log aggregation)"),
        TEXT("Human-readable text format");

        private final String description;

        LogFormat(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Log level enum.
     */
    public enum LogLevel {
        MINIMAL("Minimal: request line, status, duration"),
        NORMAL("Normal: + headers, auth info"),
        VERBOSE("Verbose: + request/response body");

        private final String description;

        LogLevel(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Get default log directory for deployment mode.
     */
    public static String getDefaultLogDirectory(DeployMode mode) {
        switch (mode) {
            case LOCAL:
                return "./logs/access";
            case DOCKER:
                return "/app/logs/access";
            case K8S:
                return "/var/log/gateway/access";
            case CUSTOM:
            default:
                return "./logs/access";
        }
    }
}