package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.StrategyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validator for strategy configurations.
 * Provides validation for rate limiter, IP filter, circuit breaker, etc.
 *
 * @author leoli
 */
@Slf4j
@Component
public class StrategyConfigValidator {

    // IPv4 CIDR pattern
    private static final Pattern IPV4_CIDR_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)/(\\d|[1-2]\\d|3[0-2])$"
    );

    // IPv6 pattern (simplified)
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::$|^([0-9a-fA-F]{1,4}:)*:([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4}$"
    );

    /**
     * Validation result.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors != null ? errors : new ArrayList<>();
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }

        public String getErrorMessage() {
            return String.join("; ", errors);
        }

        public static ValidationResult success() {
            return new ValidationResult(true, new ArrayList<>());
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }

    /**
     * Validate rate limiter configuration.
     */
    public ValidationResult validateRateLimiter(StrategyConfig.RateLimiterConfig config) {
        List<String> errors = new ArrayList<>();

        if (config == null) {
            return ValidationResult.failure(List.of("Rate limiter config is null"));
        }

        // QPS validation (int type, always has value)
        int qps = config.getQps();
        if (qps <= 0) {
            errors.add("qps must be positive, got: " + qps);
        } else if (qps > 100000) {
            errors.add("qps exceeds maximum limit (100000), got: " + qps);
        }

        // Burst capacity validation (int type, always has value)
        int burstCapacity = config.getBurstCapacity();
        if (burstCapacity < 0) {
            errors.add("burstCapacity cannot be negative, got: " + burstCapacity);
        }
        if (burstCapacity < qps) {
            log.warn("burstCapacity ({}) is less than qps ({}), this may cause immediate rate limiting",
                    burstCapacity, qps);
        }

        // Time unit validation
        if (config.getTimeUnit() != null) {
            String timeUnit = config.getTimeUnit().toLowerCase();
            if (!List.of("second", "minute", "hour").contains(timeUnit)) {
                errors.add("Invalid timeUnit: " + config.getTimeUnit() + ", must be one of: second, minute, hour");
            }
        }

        // Key resolver validation
        if (config.getKeyResolver() != null) {
            String keyResolver = config.getKeyResolver().toLowerCase();
            if (!List.of("ip", "user", "header", "global").contains(keyResolver)) {
                errors.add("Invalid keyResolver: " + config.getKeyResolver() + ", must be one of: ip, user, header, global");
            }
        }

        // Key type validation
        if (config.getKeyType() != null) {
            String keyType = config.getKeyType().toLowerCase();
            if (!List.of("route", "ip", "combined", "user", "header").contains(keyType)) {
                errors.add("Invalid keyType: " + config.getKeyType() + ", must be one of: route, ip, combined, user, header");
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Validate IP filter configuration.
     */
    public ValidationResult validateIpFilter(StrategyConfig.IPFilterConfig config) {
        List<String> errors = new ArrayList<>();

        if (config == null) {
            return ValidationResult.failure(List.of("IP filter config is null"));
        }

        // Validate IP list
        List<String> ipList = config.getIpList();
        if (ipList != null && !ipList.isEmpty()) {
            for (String ip : ipList) {
                if (!isValidIpOrCidr(ip)) {
                    errors.add("Invalid IP/CIDR: " + ip);
                }
            }
        }

        // Validate mode
        if (config.getMode() != null) {
            String mode = config.getMode().toLowerCase();
            if (!List.of("whitelist", "blacklist").contains(mode)) {
                errors.add("Invalid mode: " + config.getMode() + ", must be one of: whitelist, blacklist");
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Validate circuit breaker configuration.
     */
    public ValidationResult validateCircuitBreaker(StrategyConfig.CircuitBreakerConfig config) {
        List<String> errors = new ArrayList<>();

        if (config == null) {
            return ValidationResult.failure(List.of("Circuit breaker config is null"));
        }

        // Failure rate threshold (0-100, float type always has value)
        float failureRateThreshold = config.getFailureRateThreshold();
        if (failureRateThreshold < 0 || failureRateThreshold > 100) {
            errors.add("failureRateThreshold must be between 0 and 100, got: " + failureRateThreshold);
        }

        // Slow call rate threshold (0-100, float type always has value)
        float slowCallRateThreshold = config.getSlowCallRateThreshold();
        if (slowCallRateThreshold < 0 || slowCallRateThreshold > 100) {
            errors.add("slowCallRateThreshold must be between 0 and 100, got: " + slowCallRateThreshold);
        }

        // Slow call duration threshold (long type always has value)
        long slowCallDurationThreshold = config.getSlowCallDurationThreshold();
        if (slowCallDurationThreshold <= 0) {
            errors.add("slowCallDurationThreshold must be positive, got: " + slowCallDurationThreshold);
        } else if (slowCallDurationThreshold > 60000) {
            errors.add("slowCallDurationThreshold exceeds maximum (60000ms), got: " + slowCallDurationThreshold);
        }

        // Wait duration in open state (long type always has value)
        long waitDurationInOpenState = config.getWaitDurationInOpenState();
        if (waitDurationInOpenState <= 0) {
            errors.add("waitDurationInOpenState must be positive, got: " + waitDurationInOpenState);
        } else if (waitDurationInOpenState > 300000) {
            errors.add("waitDurationInOpenState exceeds maximum (300000ms = 5 min), got: " + waitDurationInOpenState);
        }

        // Sliding window size (int type always has value)
        int slidingWindowSize = config.getSlidingWindowSize();
        if (slidingWindowSize <= 0) {
            errors.add("slidingWindowSize must be positive, got: " + slidingWindowSize);
        } else if (slidingWindowSize > 1000) {
            errors.add("slidingWindowSize exceeds maximum (1000), got: " + slidingWindowSize);
        }

        // Minimum number of calls (int type always has value)
        int minimumNumberOfCalls = config.getMinimumNumberOfCalls();
        if (minimumNumberOfCalls <= 0) {
            errors.add("minimumNumberOfCalls must be positive, got: " + minimumNumberOfCalls);
        } else if (minimumNumberOfCalls > 1000) {
            errors.add("minimumNumberOfCalls exceeds maximum (1000), got: " + minimumNumberOfCalls);
        }

        // Validate relationship: minimumNumberOfCalls should not exceed slidingWindowSize
        if (minimumNumberOfCalls > slidingWindowSize) {
            errors.add("minimumNumberOfCalls (" + minimumNumberOfCalls +
                    ") should not exceed slidingWindowSize (" + slidingWindowSize + ")");
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Validate timeout configuration.
     */
    public ValidationResult validateTimeout(StrategyConfig.TimeoutConfig config) {
        List<String> errors = new ArrayList<>();

        if (config == null) {
            return ValidationResult.failure(List.of("Timeout config is null"));
        }

        // Connect timeout (int type always has value)
        int connectTimeout = config.getConnectTimeout();
        if (connectTimeout <= 0) {
            errors.add("connectTimeout must be positive, got: " + connectTimeout);
        } else if (connectTimeout > 60000) {
            errors.add("connectTimeout exceeds maximum (60000ms), got: " + connectTimeout);
        }

        // Response timeout (int type always has value)
        int responseTimeout = config.getResponseTimeout();
        if (responseTimeout <= 0) {
            errors.add("responseTimeout must be positive, got: " + responseTimeout);
        } else if (responseTimeout > 300000) {
            errors.add("responseTimeout exceeds maximum (300000ms = 5 min), got: " + responseTimeout);
        }

        // Response timeout should be >= connect timeout
        if (responseTimeout < connectTimeout) {
            log.warn("responseTimeout ({}) is less than connectTimeout ({}), this may cause issues",
                    responseTimeout, connectTimeout);
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Check if IP or CIDR is valid.
     */
    private boolean isValidIpOrCidr(String ipOrCidr) {
        if (ipOrCidr == null || ipOrCidr.isEmpty()) {
            return false;
        }

        // Check if it's a CIDR notation
        if (ipOrCidr.contains("/")) {
            // IPv4 CIDR
            if (IPV4_CIDR_PATTERN.matcher(ipOrCidr).matches()) {
                return true;
            }
            // IPv6 CIDR
            if (isValidIPv6Cidr(ipOrCidr)) {
                return true;
            }
            return false;
        }

        // Plain IP address
        return isValidIPv4(ipOrCidr) || isValidIPv6(ipOrCidr);
    }

    private boolean isValidIPv4(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isValidIPv6(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        // Simplified IPv6 validation
        try {
            // Try to parse as IPv6
            if (ip.contains(":")) {
                // Handle :: shorthand
                String expanded = expandIPv6(ip);
                return expanded != null;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isValidIPv6Cidr(String cidr) {
        if (cidr == null || !cidr.contains(":") || !cidr.contains("/")) {
            return false;
        }
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 128) {
                return false;
            }
            return isValidIPv6(parts[0]);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String expandIPv6(String ip) {
        if (ip == null) {
            return null;
        }
        // Handle :: shorthand
        if (ip.equals("::")) {
            return "0000:0000:0000:0000:0000:0000:0000:0000";
        }

        String[] parts;
        int missingCount = 0;

        if (ip.contains("::")) {
            // Expand :: to correct number of zero groups
            String[] halves = ip.split("::", -1);
            int leftCount = halves[0].isEmpty() ? 0 : halves[0].split(":", -1).length;
            int rightCount = halves.length > 1 && !halves[1].isEmpty() ? halves[1].split(":", -1).length : 0;
            missingCount = 8 - leftCount - rightCount;

            StringBuilder expanded = new StringBuilder();
            if (!halves[0].isEmpty()) {
                expanded.append(halves[0]);
            }
            for (int i = 0; i < missingCount; i++) {
                if (expanded.length() > 0) {
                    expanded.append(":");
                }
                expanded.append("0000");
            }
            if (halves.length > 1 && !halves[1].isEmpty()) {
                expanded.append(":").append(halves[1]);
            }
            parts = expanded.toString().split(":");
        } else {
            parts = ip.split(":");
        }

        if (parts.length != 8) {
            return null;
        }

        // Validate each part
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 4) {
                return null;
            }
            try {
                Integer.parseInt(part, 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return String.join(":", parts);
    }
}