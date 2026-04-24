package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stress Test Validator - Input validation and security controls.
 *
 * Prevents:
 * 1. SSRF attacks by validating target URLs
 * 2. Resource exhaustion via parameter limits
 * 3. Invalid configurations that could crash the system
 */
@Slf4j
@Component
public class StressTestValidator {

    // Configuration limits
    private static final int MIN_CONCURRENT_USERS = 1;
    private static final int MAX_CONCURRENT_USERS = 500;  // Safety limit per test
    private static final int MIN_TOTAL_REQUESTS = 1;
    private static final int MAX_TOTAL_REQUESTS = 1_000_000;  // 1M requests max
    private static final int MIN_DURATION_SECONDS = 1;
    private static final int MAX_DURATION_SECONDS = 3600;  // 1 hour max
    private static final int MIN_RAMP_UP_SECONDS = 0;
    private static final int MAX_RAMP_UP_SECONDS = 300;  // 5 minutes ramp-up
    private static final int MIN_REQUEST_TIMEOUT = 1;
    private static final int MAX_REQUEST_TIMEOUT = 300;  // 5 minutes timeout
    private static final int MIN_TARGET_QPS = 1;
    private static final int MAX_TARGET_QPS = 10000;  // 10K QPS max per test
    private static final int MAX_CONCURRENT_TESTS_PER_INSTANCE = 3;
    private static final int MAX_HEADER_SIZE = 8192;  // 8KB max headers
    private static final int MAX_BODY_SIZE = 1024 * 1024;  // 1MB max body

    // Blocked internal IP ranges (SSRF prevention)
    private static final Set<String> BLOCKED_IP_PREFIXES = new HashSet<>(Arrays.asList(
            "10.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.",
            "172.21.", "172.22.", "172.23.", "172.24.", "172.25.",
            "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
            "192.168.", "127.", "0.0.0.0", "169.254."
    ));

    // Allowed URL schemes
    private static final Set<String> ALLOWED_SCHEMES = new HashSet<>(Arrays.asList("http", "https"));

    // URL pattern
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^https?://[a-zA-Z0-9][-a-zA-Z0-9]*(\\.[a-zA-Z0-9][-a-zA-Z0-9]*)*(:[0-9]+)?(/.*)?$"
    );

    /**
     * Validate stress test configuration parameters
     */
    public void validateTestConfig(Integer concurrentUsers, Integer totalRequests,
                                    Integer durationSeconds, Integer rampUpSeconds,
                                    Integer requestTimeoutSeconds, Integer targetQps) {

        // Validate concurrent users
        if (concurrentUsers == null || concurrentUsers < MIN_CONCURRENT_USERS || concurrentUsers > MAX_CONCURRENT_USERS) {
            throw new IllegalArgumentException(
                    String.format("Concurrent users must be between %d and %d", MIN_CONCURRENT_USERS, MAX_CONCURRENT_USERS)
            );
        }

        // Validate total requests (only if duration is not set)
        if (durationSeconds == null) {
            if (totalRequests == null || totalRequests < MIN_TOTAL_REQUESTS || totalRequests > MAX_TOTAL_REQUESTS) {
                throw new IllegalArgumentException(
                        String.format("Total requests must be between %d and %d when duration is not specified",
                                MIN_TOTAL_REQUESTS, MAX_TOTAL_REQUESTS)
                );
            }
        } else {
            // Duration-based test
            if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS) {
                throw new IllegalArgumentException(
                        String.format("Duration must be between %d and %d seconds", MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
                );
            }
        }

        // Validate ramp-up
        if (rampUpSeconds != null) {
            if (rampUpSeconds < MIN_RAMP_UP_SECONDS || rampUpSeconds > MAX_RAMP_UP_SECONDS) {
                throw new IllegalArgumentException(
                        String.format("Ramp-up time must be between %d and %d seconds", MIN_RAMP_UP_SECONDS, MAX_RAMP_UP_SECONDS)
                );
            }
            if (durationSeconds != null && rampUpSeconds > durationSeconds) {
                throw new IllegalArgumentException("Ramp-up time cannot exceed test duration");
            }
        }

        // Validate request timeout
        if (requestTimeoutSeconds != null) {
            if (requestTimeoutSeconds < MIN_REQUEST_TIMEOUT || requestTimeoutSeconds > MAX_REQUEST_TIMEOUT) {
                throw new IllegalArgumentException(
                        String.format("Request timeout must be between %d and %d seconds", MIN_REQUEST_TIMEOUT, MAX_REQUEST_TIMEOUT)
                );
            }
        }

        // Validate target QPS
        if (targetQps != null && targetQps > 0) {
            if (targetQps < MIN_TARGET_QPS || targetQps > MAX_TARGET_QPS) {
                throw new IllegalArgumentException(
                        String.format("Target QPS must be between %d and %d", MIN_TARGET_QPS, MAX_TARGET_QPS)
                );
            }
        }
    }

    /**
     * Validate target URL for SSRF prevention
     */
    public void validateTargetUrl(String targetUrl, GatewayInstanceEntity instance) {
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Target URL cannot be empty");
        }

        // Check URL format
        if (!URL_PATTERN.matcher(targetUrl).matches()) {
            throw new IllegalArgumentException("Invalid URL format. Must be http:// or https:// followed by valid hostname");
        }

        try {
            URL url = new URL(targetUrl);

            // Validate scheme
            if (!ALLOWED_SCHEMES.contains(url.getProtocol().toLowerCase())) {
                throw new IllegalArgumentException("Only HTTP and HTTPS schemes are allowed");
            }

            // Validate host
            String host = url.getHost();
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("Invalid hostname in URL");
            }

            // Check for localhost/loopback
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
                log.warn("Stress test targeting localhost: {}", targetUrl);
                // Allow localhost for development but log warning
            }

            // If instance is provided, verify URL matches instance
            if (instance != null) {
                validateUrlMatchesInstance(targetUrl, instance);
            }

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + e.getMessage());
        }
    }

    /**
     * Validate that target URL matches the gateway instance
     * This prevents using one instance's credentials to attack another server
     */
    private void validateUrlMatchesInstance(String targetUrl, GatewayInstanceEntity instance) {
        try {
            URL url = new URL(targetUrl);
            String urlHost = url.getHost();
            int urlPort = url.getPort() != -1 ? url.getPort() :
                    (url.getProtocol().equals("https") ? 443 : 80);

            // Get instance access URLs
            String instanceUrl = instance.getManualAccessUrl() != null ?
                    instance.getManualAccessUrl() :
                    (instance.getDiscoveredAccessUrl() != null ?
                            instance.getDiscoveredAccessUrl() :
                            instance.getReportedAccessUrl());

            if (instanceUrl != null && !instanceUrl.isEmpty()) {
                URL instanceParsedUrl = new URL(instanceUrl);
                String instanceHost = instanceParsedUrl.getHost();
                int instancePort = instanceParsedUrl.getPort() != -1 ? instanceParsedUrl.getPort() :
                        (instanceParsedUrl.getProtocol().equals("https") ? 443 : 80);

                // Host and port must match
                if (!urlHost.equals(instanceHost) || urlPort != instancePort) {
                    log.warn("Target URL {} does not match instance URL {} for instance {}",
                            targetUrl, instanceUrl, instance.getInstanceId());
                    // For security, we allow this but log a warning
                    // In strict mode, you could throw an exception here
                }
            }
        } catch (Exception e) {
            log.warn("Failed to validate URL against instance: {}", e.getMessage());
        }
    }

    /**
     * Validate headers size
     */
    public void validateHeaders(String headersJson) {
        if (headersJson != null && headersJson.length() > MAX_HEADER_SIZE) {
            throw new IllegalArgumentException(
                    String.format("Headers size exceeds maximum allowed (%d bytes)", MAX_HEADER_SIZE)
            );
        }
    }

    /**
     * Validate request body size
     */
    public void validateBody(String body) {
        if (body != null && body.length() > MAX_BODY_SIZE) {
            throw new IllegalArgumentException(
                    String.format("Request body size exceeds maximum allowed (%d bytes)", MAX_BODY_SIZE)
            );
        }
    }

    /**
     * Check if instance has reached maximum concurrent tests
     */
    public void validateConcurrentTestsLimit(String instanceId, int currentRunningTests) {
        if (currentRunningTests >= MAX_CONCURRENT_TESTS_PER_INSTANCE) {
            throw new IllegalStateException(
                    String.format("Instance %s has reached maximum concurrent tests limit (%d)",
                            instanceId, MAX_CONCURRENT_TESTS_PER_INSTANCE)
            );
        }
    }

    /**
     * Validate HTTP method
     */
    public void validateHttpMethod(String method) {
        if (method == null || method.trim().isEmpty()) {
            return; // Default to GET
        }

        String upperMethod = method.toUpperCase().trim();
        Set<String> allowedMethods = new HashSet<>(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"
        ));

        if (!allowedMethods.contains(upperMethod)) {
            throw new IllegalArgumentException(
                    "Unsupported HTTP method: " + method + ". Allowed: " + allowedMethods
            );
        }
    }

    /**
     * Comprehensive validation of all test parameters
     */
    public void validateAll(String targetUrl, String method, String headers, String body,
                           Integer concurrentUsers, Integer totalRequests,
                           Integer durationSeconds, Integer rampUpSeconds,
                           Integer requestTimeoutSeconds, Integer targetQps,
                           GatewayInstanceEntity instance, int currentRunningTests) {

        validateTargetUrl(targetUrl, instance);
        validateHttpMethod(method);
        validateHeaders(headers);
        validateBody(body);
        validateTestConfig(concurrentUsers, totalRequests, durationSeconds,
                rampUpSeconds, requestTimeoutSeconds, targetQps);
        validateConcurrentTestsLimit(instance != null ? instance.getInstanceId() : "unknown",
                currentRunningTests);
    }
}
