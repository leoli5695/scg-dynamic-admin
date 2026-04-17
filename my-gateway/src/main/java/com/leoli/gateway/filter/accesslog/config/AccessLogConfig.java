package com.leoli.gateway.filter.accesslog.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Access log configuration model.
 * <p>
 * Defines all configurable options for access log output including:
 * - Output mode (stdout/file/both)
 * - Log format and level
 * - Body logging options
 * - Sampling rate
 * - Sensitive field masking
 * - Binary content handling
 * <p>
 * Config Key: config.gateway.access-log
 *
 * @author leoli
 */
@Data
@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessLogConfig {

    // ============================================================
    // Basic Configuration
    // ============================================================

    /**
     * Enable/disable access logging.
     */
    private boolean enabled = false;

    /**
     * Deployment mode: LOCAL, DOCKER, K8S, CUSTOM.
     * Determines default log directory path.
     */
    private String deployMode = "LOCAL";

    /**
     * Log directory path (user configurable).
     */
    private String logDirectory = "./logs/access";

    /**
     * Log file name pattern for rolling.
     */
    private String fileNamePattern = "access-{yyyy-MM-dd}.log";

    /**
     * Log format: JSON or TEXT.
     */
    private String logFormat = "JSON";

    /**
     * Log level: MINIMAL, NORMAL, VERBOSE.
     * Controls how much information is logged.
     */
    private String logLevel = "NORMAL";

    // ============================================================
    // Header Logging
    // ============================================================

    /**
     * Include request headers in log.
     */
    private boolean logRequestHeaders = true;

    /**
     * Include response headers in log.
     */
    private boolean logResponseHeaders = true;

    // ============================================================
    // Body Logging
    // ============================================================

    /**
     * Include request body in log.
     */
    private boolean logRequestBody = false;

    /**
     * Include response body in log.
     */
    private boolean logResponseBody = false;

    /**
     * Maximum body content length to log.
     * Prevents logging excessively large bodies.
     */
    private int maxBodyLength = 2048;

    // ============================================================
    // Sampling
    // ============================================================

    /**
     * Sampling rate percentage (0-100).
     * 100 means log all requests.
     */
    private int samplingRate = 100;

    // ============================================================
    // Security & Privacy
    // ============================================================

    /**
     * Field names to mask for privacy.
     */
    private List<String> sensitiveFields = Arrays.asList("password", "token", "secret", "apiKey");

    // ============================================================
    // Output Control
    // ============================================================

    /**
     * Output to console (stdout).
     */
    private boolean logToConsole = true;

    /**
     * Include authentication info in log.
     */
    private boolean includeAuthInfo = true;

    // ============================================================
    // File Rolling
    // ============================================================

    /**
     * Maximum file size in MB before rolling.
     */
    private int maxFileSizeMb = 100;

    /**
     * Maximum number of backup files to keep.
     */
    private int maxBackupFiles = 30;

    // ============================================================
    // Binary Content Handling
    // ============================================================

    /**
     * Custom binary content types to skip body logging.
     * e.g., ["application/x-custom-binary", "application/my-archive"]
     */
    private List<String> customBinaryContentTypes = new ArrayList<>();

    /**
     * Custom binary file extensions to skip body logging.
     * e.g., [".xyz", ".custom"]
     */
    private List<String> customBinaryExtensions = new ArrayList<>();

    // ============================================================
    // Utility Methods
    // ============================================================

    /**
     * Get log directory based on deploy mode and user configuration.
     * Returns null if stdout mode is enabled or no valid directory is configured.
     *
     * @return Log directory path or null for stdout-only mode
     */
    public String getLogDirectory() {
        // If stdout mode (logToConsole=true and no explicit directory), return null
        // This prevents file writes when user only wants stdout output
        if (logToConsole && (logDirectory == null || logDirectory.isEmpty())) {
            return null;  // stdout mode - no file needed
        }

        // If user explicitly set a directory, use it (highest priority)
        if (logDirectory != null && !logDirectory.isEmpty()) {
            return logDirectory;
        }

        // CUSTOM mode: user MUST provide a path, otherwise don't write to file
        if ("CUSTOM".equals(deployMode)) {
            // No path provided for CUSTOM mode, skip file output
            log.warn("CUSTOM deploy mode requires user-defined logDirectory, but none provided. Skipping file output.");
            return null;
        }

        // Otherwise, use default based on deploy mode
        switch (deployMode) {
            case "DOCKER":
                return "/app/logs/access";
            case "K8S":
                // K8S default path, but only if file output is explicitly needed
                return "/var/log/gateway/access";
            case "LOCAL":
            default:
                return "./logs/access";
        }
    }

    /**
     * Check if VERBOSE log level is enabled.
     */
    public boolean isVerbose() {
        return "VERBOSE".equals(logLevel);
    }

    /**
     * Check if MINIMAL log level is enabled.
     */
    public boolean isMinimal() {
        return "MINIMAL".equals(logLevel);
    }

    /**
     * Check if any body logging is enabled.
     */
    public boolean isBodyLoggingEnabled() {
        return logRequestBody || logResponseBody;
    }
}