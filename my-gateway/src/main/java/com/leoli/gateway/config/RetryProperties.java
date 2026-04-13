package com.leoli.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Retry default configuration properties.
 * <p>
 * Allows external configuration of retry default values via application.yml:
 * <pre>
 * gateway:
 *   retry:
 *     default-max-attempts: 3
 *     default-retry-interval-ms: 1000
 *     default-retry-on-status-codes: [500, 502, 503, 504]
 *     default-retry-on-exceptions:
 *       - java.net.ConnectException
 *       - java.net.SocketTimeoutException
 * </pre>
 *
 * @author leoli
 */
@ConfigurationProperties(prefix = "gateway.retry")
public class RetryProperties {

    /**
     * Default maximum retry attempts (including first request).
     */
    private int defaultMaxAttempts = 3;

    /**
     * Default retry interval in milliseconds.
     */
    private long defaultRetryIntervalMs = 1000;

    /**
     * Default HTTP status codes that trigger retry.
     */
    private Set<Integer> defaultRetryOnStatusCodes = Set.of(500, 502, 503, 504);

    /**
     * Default exception class names that trigger retry.
     */
    private Set<String> defaultRetryOnExceptions = Set.of(
            "java.net.ConnectException",
            "java.net.SocketTimeoutException",
            "java.io.IOException",
            "org.springframework.cloud.gateway.support.NotFoundException"
    );

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public long getDefaultRetryIntervalMs() {
        return defaultRetryIntervalMs;
    }

    public void setDefaultRetryIntervalMs(long defaultRetryIntervalMs) {
        this.defaultRetryIntervalMs = defaultRetryIntervalMs;
    }

    public Set<Integer> getDefaultRetryOnStatusCodes() {
        return defaultRetryOnStatusCodes;
    }

    public void setDefaultRetryOnStatusCodes(Set<Integer> defaultRetryOnStatusCodes) {
        this.defaultRetryOnStatusCodes = defaultRetryOnStatusCodes;
    }

    public Set<String> getDefaultRetryOnExceptions() {
        return defaultRetryOnExceptions;
    }

    public void setDefaultRetryOnExceptions(Set<String> defaultRetryOnExceptions) {
        this.defaultRetryOnExceptions = defaultRetryOnExceptions;
    }
}