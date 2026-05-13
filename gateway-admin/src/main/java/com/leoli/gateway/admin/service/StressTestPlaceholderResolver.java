package com.leoli.gateway.admin.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================================================
 * Stress Test Placeholder Resolver
 * ============================================================================
 * <p>
 * Resolves placeholders in stress test request bodies dynamically.
 * Each request gets fresh random values, simulating different users.
 * <p>
 * Supported placeholders:
 * - {{randomInt}}           → Random integer (1-1,000,000)
 * - {{randomInt:min-max}}   → Random integer in range
 * - {{randomString}}        → Random 16-char alphanumeric string
 * - {{randomString:length}} → Random string of specified length
 * - {{randomUuid}}          → UUID v4
 * - {{timestamp}}           → Current timestamp in milliseconds
 * <p>
 * Example:
 * {"userId": {{randomInt:10000-100000}}, "token": "{{randomString:32}}"}
 * <p>
 * Each placeholder is resolved independently per request, ensuring
 * unique values for concurrent stress tests.
 *
 * @author leoli
 */
@Slf4j
@Component
public class StressTestPlaceholderResolver {

    /**
     * Pattern to match placeholders: {{placeholderName:params}}
     * Groups:
     * - Group 1: placeholder name (e.g., "randomInt")
     * - Group 3: params (e.g., "10000-100000" or "32")
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{(\\w+)(:([^}]+))?}}");

    /**
     * Resolve all placeholders in the given body string.
     * Unknown placeholders are preserved as-is.
     *
     * @param body The request body containing placeholders
     * @return Resolved body with actual values
     */
    public String resolve(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }

        // Quick check if body contains any placeholders
        if (!body.contains("{{")) {
            return body;
        }

        StringBuffer result = new StringBuffer();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(body);

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String params = matcher.group(3); // min-max or length

            String replacement = resolvePlaceholder(placeholder, params, matcher.group(0));

            // Escape special characters for appendReplacement
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        log.debug("Resolved placeholders: {} -> {}", body, result.toString());
        return result.toString();
    }

    /**
     * Resolve a single placeholder to its actual value.
     *
     * @param placeholder The placeholder name
     * @param params      The parameters (may be null)
     * @param original    The original placeholder text (for fallback)
     * @return The resolved value
     */
    private String resolvePlaceholder(String placeholder, String params, String original) {
        try {
            switch (placeholder) {
                case "randomInt":
                    return resolveRandomInt(params);
                case "randomString":
                    return resolveRandomString(params);
                case "randomUuid":
                    return UUID.randomUUID().toString();
                case "timestamp":
                    return String.valueOf(System.currentTimeMillis());
                default:
                    // Keep original placeholder if unknown
                    log.warn("Unknown placeholder: {}", original);
                    return escapeReplacement(original);
            }
        } catch (Exception e) {
            log.error("Failed to resolve placeholder {}: {}", original, e.getMessage());
            return escapeReplacement(original);
        }
    }

    /**
     * Resolve {{randomInt}} or {{randomInt:min-max}}.
     * Default range is 1 to 1,000,000 if no params specified.
     */
    private String resolveRandomInt(String params) {
        if (params == null || params.isEmpty()) {
            return String.valueOf(ThreadLocalRandom.current().nextInt(1, 1_000_000));
        }

        // Parse "min-max" format
        String[] range = params.split("-");
        if (range.length != 2) {
            throw new IllegalArgumentException("Invalid randomInt params: " + params);
        }

        int min = Integer.parseInt(range[0].trim());
        int max = Integer.parseInt(range[1].trim());

        // ThreadLocalRandom.nextInt(min, max) is exclusive of max, so add 1
        return String.valueOf(ThreadLocalRandom.current().nextInt(min, max + 1));
    }

    /**
     * Resolve {{randomString}} or {{randomString:length}}.
     * Default length is 16 if no params specified.
     */
    private String resolveRandomString(String params) {
        int length = 16; // Default length
        if (params != null && !params.isEmpty()) {
            length = Integer.parseInt(params.trim());
        }

        // Generate alphanumeric string (a-z, A-Z, 0-9)
        return RandomStringUtils.randomAlphanumeric(length);
    }

    /**
     * Escape special characters for Matcher.appendReplacement.
     * $ and \ have special meaning in replacement strings.
     */
    private String escapeReplacement(String str) {
        return str.replace("\\", "\\\\").replace("$", "\\$");
    }
}