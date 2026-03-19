package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plugin configuration model.
 * Note: Custom Header feature removed, use SCG native AddRequestHeader filter instead.
 *
 * @author leoli
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StrategyConfig {

    /**
     * Rate limiter plugin configurations.
     */
    private List<RateLimiterConfig> rateLimiters = new ArrayList<>();

    /**
     * IP filter plugin configurations (whitelist/blacklist).
     */
    private List<IPFilterConfig> ipFilters = new ArrayList<>();

    /**
     * Timeout filter configurations.
     */
    private List<TimeoutConfig> timeouts = new ArrayList<>();

    /**
     * Circuit breaker configurations.
     */
    private List<CircuitBreakerConfig> circuitBreakers = new ArrayList<>();

    /**
     * Authentication configurations.
     */
    private List<AuthConfig> authConfigs = new ArrayList<>();

    /**
     * Retry configurations.
     */
    private List<RetryConfig> retries = new ArrayList<>();

    /**
     * CORS configurations.
     */
    private List<CorsConfig> corsConfigs = new ArrayList<>();

    /**
     * Access log configurations.
     */
    private List<AccessLogConfig> accessLogs = new ArrayList<>();

    /**
     * Header operation configurations.
     */
    private List<HeaderOpConfig> headerOps = new ArrayList<>();

    /**
     * Cache configurations.
     */
    private List<CacheConfig> caches = new ArrayList<>();

    /**
     * Security (SQL injection/XSS) configurations.
     */
    private List<SecurityConfig> securities = new ArrayList<>();

    /**
     * API version configurations.
     */
    private List<ApiVersionConfig> apiVersions = new ArrayList<>();

    // ============================================================
    // Retry Strategy
    // ============================================================

    /**
     * Retry configuration.
     */
    @Data
    public static class RetryConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Maximum retry attempts.
         */
        private int maxAttempts = 3;

        /**
         * Retry interval in milliseconds.
         */
        private long retryIntervalMs = 1000;

        /**
         * HTTP status codes that trigger retry (e.g., 500, 502, 503).
         */
        private List<Integer> retryOnStatusCodes = List.of(500, 502, 503, 504);

        /**
         * Exception types that trigger retry.
         */
        private List<String> retryOnExceptions = List.of(
                "java.net.ConnectException",
                "java.net.SocketTimeoutException",
                "java.io.IOException"
        );

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
    }

    // ============================================================
    // CORS Strategy
    // ============================================================

    /**
     * CORS (Cross-Origin Resource Sharing) configuration.
     */
    @Data
    public static class CorsConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Allowed origins (e.g., "http://localhost:3000", "*").
         */
        private List<String> allowedOrigins = List.of("*");

        /**
         * Allowed HTTP methods.
         */
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");

        /**
         * Allowed headers.
         */
        private List<String> allowedHeaders = List.of("*");

        /**
         * Exposed headers that client can access.
         */
        private List<String> exposedHeaders = new ArrayList<>();

        /**
         * Whether credentials (cookies) are allowed.
         */
        private boolean allowCredentials = false;

        /**
         * Preflight cache duration in seconds.
         */
        private long maxAge = 3600;

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
    }

    // ============================================================
    // Access Log Strategy
    // ============================================================

    /**
     * Access log configuration.
     */
    @Data
    public static class AccessLogConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Log level: MINIMAL, NORMAL, VERBOSE.
         */
        private String logLevel = "NORMAL";

        /**
         * Log request headers.
         */
        private boolean logRequestHeaders = true;

        /**
         * Log response headers.
         */
        private boolean logResponseHeaders = true;

        /**
         * Log request body.
         */
        private boolean logRequestBody = false;

        /**
         * Log response body.
         */
        private boolean logResponseBody = false;

        /**
         * Maximum body length to log (characters).
         */
        private int maxBodyLength = 1000;

        /**
         * Sampling rate (1-100, 100 means log all requests).
         */
        private int samplingRate = 100;

        /**
         * Fields to mask (e.g., password, token, authorization).
         */
        private List<String> sensitiveFields = List.of("password", "token", "authorization", "secret");

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
    }

    // ============================================================
    // Header Operation Strategy
    // ============================================================

    /**
     * Header operation configuration.
     */
    @Data
    public static class HeaderOpConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Request headers to add.
         * Key: header name, Value: header value (supports expressions like ${requestId}).
         */
        private Map<String, String> addRequestHeaders;

        /**
         * Request headers to remove.
         */
        private List<String> removeRequestHeaders = new ArrayList<>();

        /**
         * Response headers to add.
         */
        private Map<String, String> addResponseHeaders;

        /**
         * Response headers to remove.
         */
        private List<String> removeResponseHeaders = new ArrayList<>();

        /**
         * Enable trace ID injection.
         */
        private boolean enableTraceId = true;

        /**
         * Trace ID header name.
         */
        private String traceIdHeader = "X-Trace-Id";

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
    }

    // ============================================================
    // Cache Strategy
    // ============================================================

    /**
     * Cache configuration.
     */
    @Data
    public static class CacheConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Cache time-to-live in seconds.
         */
        private long ttlSeconds = 60;

        /**
         * Maximum cache size (number of entries).
         */
        private int maxSize = 10000;

        /**
         * HTTP methods to cache (usually GET, HEAD).
         */
        private List<String> cacheMethods = List.of("GET", "HEAD");

        /**
         * Cache key expression (supports SpEL).
         * Default: "${method}:${path}:${query}".
         */
        private String cacheKeyExpression = "${method}:${path}";

        /**
         * Paths to exclude from caching.
         */
        private List<String> excludePaths = new ArrayList<>();

        /**
         * Whether to vary cache by request headers.
         */
        private List<String> varyHeaders = List.of("Accept", "Accept-Encoding");

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
    }

    // ============================================================
    // Security Strategy (SQL Injection / XSS)
    // ============================================================

    /**
     * Security configuration for SQL injection and XSS protection.
     */
    @Data
    public static class SecurityConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Enable SQL injection protection.
         */
        private boolean enableSqlInjectionProtection = true;

        /**
         * Enable XSS protection.
         */
        private boolean enableXssProtection = true;

        /**
         * Protection mode: DETECT (log only) or BLOCK (reject request).
         */
        private String mode = "BLOCK";

        /**
         * Paths to exclude from security check.
         */
        private List<String> excludePaths = new ArrayList<>();

        /**
         * Check request parameters.
         */
        private boolean checkParameters = true;

        /**
         * Check request body (JSON).
         */
        private boolean checkBody = true;

        /**
         * Check request headers.
         */
        private boolean checkHeaders = false;

        /**
         * Custom SQL injection patterns (regex).
         */
        private List<String> customSqlPatterns = new ArrayList<>();

        /**
         * Custom XSS patterns (regex).
         */
        private List<String> customXssPatterns = new ArrayList<>();

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
    }

    // ============================================================
    // API Version Strategy
    // ============================================================

    /**
     * API version configuration.
     */
    @Data
    public static class ApiVersionConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Version mode: PATH or SERVICE.
         * PATH: /v1/users vs /v2/users (different routes).
         * SERVICE: same route points to v1/v2 services.
         */
        private String versionMode = "PATH";

        /**
         * Default version when not specified.
         */
        private String defaultVersion = "v1";

        /**
         * Version header name (for SERVICE mode).
         */
        private String versionHeader = "X-API-Version";

        /**
         * Version parameter name (for SERVICE mode).
         */
        private String versionParam = "version";

        /**
         * Version location: HEADER, QUERY, PATH.
         */
        private String versionLocation = "HEADER";

        /**
         * Supported versions and their targets.
         * Key: version (e.g., "v1"), Value: service ID or route ID.
         */
        private Map<String, String> versionMappings;

        /**
         * Whether to include version in response header.
         */
        private boolean includeVersionInResponse = true;

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
    }

    // Note: customHeaders removed - use SCG native AddRequestHeader filter instead

    /**
     * Rate limiter plugin configuration.
     */
    @Data
    public static class RateLimiterConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Rate limit (QPS).
         */
        private int qps = 100;

        /**
         * Time unit: second / minute / hour.
         */
        private String timeUnit = "second";

        /**
         * Burst capacity.
         */
        private int burstCapacity = 200;

        /**
         * Key resolver dimension: ip / user / header / global.
         */
        private String keyResolver = "ip";

        /**
         * Header name when keyResolver is 'header'.
         */
        private String headerName;

        /**
         * Key type: route / ip / combined.
         */
        private String keyType = "combined";

        /**
         * Key prefix.
         */
        private String keyPrefix = "rate_limit:";

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;

        public RateLimiterConfig() {
        }

        public RateLimiterConfig(String routeId, int qps, String timeUnit, int burstCapacity) {
            this.routeId = routeId;
            this.qps = qps;
            this.timeUnit = timeUnit;
            this.burstCapacity = burstCapacity;
        }
    }

    /**
     * IP filter configuration (whitelist/blacklist).
     */
    @Data
    public static class IPFilterConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Filter mode: blacklist / whitelist.
         */
        private String mode = "blacklist";

        /**
         * IP address list (supports CIDR notation).
         */
        private List<String> ipList = new ArrayList<>();

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;

        public IPFilterConfig() {
        }

        public IPFilterConfig(String routeId, String mode, List<String> ipList) {
            this.routeId = routeId;
            this.mode = mode;
            this.ipList = ipList;
        }
    }

    /**
     * Timeout filter configuration.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimeoutConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Connect timeout in milliseconds (TCP connection phase).
         */
        private int connectTimeout = 5000;

        /**
         * Response timeout in milliseconds (total time from request to complete response).
         */
        private int responseTimeout = 30000;

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;

        public TimeoutConfig() {
        }

        public TimeoutConfig(String routeId, int connectTimeout, int responseTimeout) {
            this.routeId = routeId;
            this.connectTimeout = connectTimeout;
            this.responseTimeout = responseTimeout;
        }
    }

    /**
     * Circuit breaker configuration.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CircuitBreakerConfig {
        /**
         * Route ID.
         */
        private String routeId;

        /**
         * Failure rate threshold (percentage, e.g., 50 = 50%).
         */
        private float failureRateThreshold = 50.0f;

        /**
         * Slow call duration threshold in milliseconds.
         */
        private long slowCallDurationThreshold = 60000L;

        /**
         * Slow call rate threshold (percentage).
         */
        private float slowCallRateThreshold = 80.0f;

        /**
         * Wait duration in open state (milliseconds).
         */
        private long waitDurationInOpenState = 30000L;

        /**
         * Sliding window size (number of calls).
         */
        private int slidingWindowSize = 10;

        /**
         * Minimum number of calls before calculating metrics.
         */
        private int minimumNumberOfCalls = 5;

        /**
         * Whether automatic transition from half-open to closed is enabled.
         */
        private boolean automaticTransitionFromOpenToHalfOpenEnabled = true;

        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;

        public CircuitBreakerConfig() {
        }

        public CircuitBreakerConfig(String routeId, float failureRateThreshold, long waitDurationInOpenState) {
            this.routeId = routeId;
            this.failureRateThreshold = failureRateThreshold;
            this.waitDurationInOpenState = waitDurationInOpenState;
        }
    }

}
