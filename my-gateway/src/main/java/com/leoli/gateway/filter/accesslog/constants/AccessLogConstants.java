package com.leoli.gateway.filter.accesslog.constants;

/**
 * Access log constants.
 * <p>
 * Defines attribute keys and constants used in access log processing.
 * Interface is used so all fields are implicitly public static final.
 *
 * @author leoli
 */
public interface AccessLogConstants {

    // ============================================================
    // Exchange Attribute Keys
    // ============================================================

    /**
     * Attribute key for request start time.
     */
    String START_TIME_ATTR = "accessLogStartTime";

    /**
     * Attribute key for request ID.
     */
    String REQUEST_ID_ATTR = "requestId";

    /**
     * Attribute key for cached response body.
     */
    String RESPONSE_BODY_ATTR = "accessLogResponseBody";

    /**
     * Attribute key for file upload flag.
     */
    String IS_FILE_UPLOAD_ATTR = "accessLogIsFileUpload";

    /**
     * Attribute key for file download flag.
     */
    String IS_FILE_DOWNLOAD_ATTR = "accessLogIsFileDownload";

    // ============================================================
    // Logger Configuration
    // ============================================================

    /**
     * Logger name for access log output.
     * Should be configured in logback-spring.xml.
     */
    String ACCESS_LOG_LOGGER_NAME = "ACCESS_LOG";

    // ============================================================
    // Log Levels
    // ============================================================

    /**
     * Minimal log level - only essential fields.
     */
    String LOG_LEVEL_MINIMAL = "MINIMAL";

    /**
     * Normal log level - standard fields.
     */
    String LOG_LEVEL_NORMAL = "NORMAL";

    /**
     * Verbose log level - all available fields.
     */
    String LOG_LEVEL_VERBOSE = "VERBOSE";

    // ============================================================
    // Deploy Modes
    // ============================================================

    /**
     * Local deployment mode - logs to ./logs/access.
     */
    String DEPLOY_MODE_LOCAL = "LOCAL";

    /**
     * Docker deployment mode - logs to /app/logs/access.
     */
    String DEPLOY_MODE_DOCKER = "DOCKER";

    /**
     * Kubernetes deployment mode - logs to /var/log/gateway/access.
     */
    String DEPLOY_MODE_K8S = "K8S";

    /**
     * Custom deployment mode - user-defined path.
     */
    String DEPLOY_MODE_CUSTOM = "CUSTOM";

    // ============================================================
    // Log Formats
    // ============================================================

    /**
     * JSON log format.
     */
    String LOG_FORMAT_JSON = "JSON";

    /**
     * Text log format.
     */
    String LOG_FORMAT_TEXT = "TEXT";

    // ============================================================
    // Masking
    // ============================================================

    /**
     * Mask value for sensitive data.
     */
    String MASK_VALUE = "***MASKED***";

    /**
     * Truncation suffix.
     */
    String TRUNCATION_SUFFIX = "...[TRUNCATED]";

    // ============================================================
    // Default Configuration Values
    // ============================================================

    /**
     * Default maximum body length to log.
     */
    int DEFAULT_MAX_BODY_LENGTH = 2048;

    /**
     * Default sampling rate (100%).
     */
    int DEFAULT_SAMPLING_RATE = 100;

    /**
     * Default maximum file size in MB.
     */
    int DEFAULT_MAX_FILE_SIZE_MB = 100;

    /**
     * Default maximum backup files.
     */
    int DEFAULT_MAX_BACKUP_FILES = 30;

    /**
     * Default file name pattern.
     */
    String DEFAULT_FILE_NAME_PATTERN = "access-{yyyy-MM-dd}.log";

    // ============================================================
    // Utility Methods
    // ============================================================

    /**
     * Build truncated content string.
     *
     * @param originalLength Original content length
     * @param maxLength Maximum allowed length
     * @return Truncation info string
     */
    static String buildTruncationInfo(int originalLength, int maxLength) {
        return "...[TRUNCATED " + (originalLength - maxLength) + " chars]";
    }
}