package com.leoli.gateway.filter.accesslog.sanitizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.filter.accesslog.config.AccessLogConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sensitive data sanitizer for access log privacy protection.
 * <p>
 * Features:
 * - Mask sensitive headers (Authorization, Cookie, etc.)
 * - Mask sensitive fields in JSON body
 * - Configurable sensitive field list
 * - Content truncation for large bodies
 *
 * @author leoli
 */
@Slf4j
@Component
public class SensitiveDataSanitizer {

    private static final String MASK_VALUE = "***MASKED***";
    
    private final ObjectMapper objectMapper;

    /**
     * Default sensitive headers to mask.
     */
    private static final Set<String> SENSITIVE_HEADERS = new HashSet<>(List.of(
            "authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-auth-token",
            "x-access-key",
            "x-secret",
            "proxy-authorization"
    ));

    public SensitiveDataSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Filter sensitive headers from HTTP headers.
     *
     * @param headers HTTP headers
     * @param config Access log configuration
     * @return Filtered headers map
     */
    public HttpHeaders filterHeaders(HttpHeaders headers, AccessLogConfig config) {
        HttpHeaders filtered = new HttpHeaders();
        
        for (String name : headers.keySet()) {
            List<String> values = headers.get(name);
            if (values != null) {
                if (isSensitiveHeader(name)) {
                    filtered.add(name, MASK_VALUE);
                } else {
                    filtered.addAll(name, values);
                }
            }
        }
        
        return filtered;
    }

    /**
     * Check if header name is sensitive.
     *
     * @param headerName Header name
     * @return true if sensitive
     */
    public boolean isSensitiveHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
        return SENSITIVE_HEADERS.contains(headerName.toLowerCase());
    }

    /**
     * Mask sensitive fields in JSON body.
     *
     * @param body JSON body string
     * @param config Access log configuration
     * @return Masked JSON body
     */
    public String maskSensitiveFields(String body, AccessLogConfig config) {
        if (body == null || body.isEmpty()) {
            return body;
        }

        List<String> sensitiveFields = config.getSensitiveFields();
        if (sensitiveFields == null || sensitiveFields.isEmpty()) {
            return body;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.isObject()) {
                maskObjectNode((ObjectNode) root, sensitiveFields);
                return objectMapper.writeValueAsString(root);
            }
            return body;
        } catch (Exception e) {
            log.debug("Failed to mask sensitive fields, returning original: {}", e.getMessage());
            return body;
        }
    }

    /**
     * Mask sensitive fields in ObjectNode.
     *
     * @param node ObjectNode to mask
     * @param sensitiveFields List of sensitive field names
     */
    private void maskObjectNode(ObjectNode node, List<String> sensitiveFields) {
        for (String fieldName : sensitiveFields) {
            if (node.has(fieldName)) {
                node.put(fieldName, MASK_VALUE);
            }
        }
        
        // Also mask nested objects
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                maskObjectNode((ObjectNode) value, sensitiveFields);
            }
        });
    }

    /**
     * Truncate content to maximum length.
     *
     * @param content Content to truncate
     * @param maxLength Maximum length
     * @return Truncated content with ellipsis if needed
     */
    public String truncate(String content, int maxLength) {
        if (content == null) {
            return null;
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...[TRUNCATED " + (content.length() - maxLength) + " chars]";
    }

    /**
     * Sanitize body content for logging.
     * Combines masking and truncation.
     *
     * @param body Body content
     * @param contentType Content type
     * @param config Access log configuration
     * @return Sanitized body
     */
    public String sanitizeBody(String body, String contentType, AccessLogConfig config) {
        if (body == null || body.isEmpty()) {
            return body;
        }

        // Mask sensitive fields for JSON content
        if (contentType != null && contentType.contains("application/json")) {
            body = maskSensitiveFields(body, config);
        }

        // Truncate to max length
        return truncate(body, config.getMaxBodyLength());
    }
}