package com.leoli.gateway.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock Response Configuration.
 * Supports static, dynamic, and template-based mock responses.
 *
 * @author leoli
 */
@Data
public class MockResponseConfig {

    private boolean enabled = false;

    // ============== Mock Mode ==============
    /**
     * Mock mode: STATIC, DYNAMIC, TEMPLATE.
     * STATIC: Return fixed response.
     * DYNAMIC: Select response based on request conditions.
     * TEMPLATE: Generate response using template engine.
     */
    private String mockMode = "STATIC";

    // ============== Static Mock ==============
    private StaticMockConfig staticMock = new StaticMockConfig();

    // ============== Dynamic Mock ==============
    private DynamicMockConfig dynamicMock = new DynamicMockConfig();

    // ============== Template Mock ==============
    private TemplateMockConfig templateMock = new TemplateMockConfig();

    // ============== Delay Simulation ==============
    private MockDelayConfig delay = new MockDelayConfig();

    // ============== Error Simulation ==============
    private ErrorSimulationConfig errorSimulation = new ErrorSimulationConfig();

    // ============== Pass Through Conditions ==============
    private PassThroughConfig passThrough = new PassThroughConfig();

    // ============== Nested Config Classes ==============

    @Data
    public static class StaticMockConfig {
        /**
         * HTTP status code to return.
         */
        private int statusCode = 200;

        /**
         * Content-Type header.
         */
        private String contentType = "application/json";

        /**
         * Response headers.
         */
        private Map<String, String> headers = new HashMap<>();

        /**
         * Response body string.
         */
        private String body;

        /**
         * Response body file path (alternative to inline body).
         * E.g., "mock/users.json", "mock/products.xml"
         */
        private String bodyFile;
    }

    @Data
    public static class DynamicMockConfig {
        /**
         * Mock conditions for dynamic selection.
         */
        private List<MockCondition> conditions = new ArrayList<>();

        /**
         * Default response when no condition matches.
         */
        private MockResponseRef defaultResponse = new MockResponseRef();
    }

    @Data
    public static class MockCondition {
        /**
         * Match type: PATH, HEADER, QUERY, BODY.
         */
        private String matchType = "PATH";

        /**
         * Path pattern (Ant-style).
         * E.g., "/api/users/{id}", "/api/products/**"
         */
        private String pathPattern;

        /**
         * Header conditions: header name -> expected value.
         */
        private Map<String, String> headerConditions = new HashMap<>();

        /**
         * Query parameter conditions.
         */
        private Map<String, String> queryConditions = new HashMap<>();

        /**
         * Body conditions (JSONPath match).
         */
        private Map<String, String> bodyConditions = new HashMap<>();

        /**
         * Response to return when condition matches.
         */
        private MockResponseRef response = new MockResponseRef();
    }

    @Data
    public static class MockResponseRef {
        private int statusCode = 200;
        private String contentType = "application/json";
        private Map<String, String> headers = new HashMap<>();
        private String body;
        private String bodyFile;
    }

    @Data
    public static class TemplateMockConfig {
        /**
         * Template engine: JSON_TEMPLATE, HANDLEBARS, MUSTACHE.
         */
        private String templateEngine = "HANDLEBARS";

        /**
         * Template content.
         */
        private String template;

        /**
         * Template file path.
         */
        private String templateFile;

        /**
         * Static variables for template rendering.
         */
        private Map<String, Object> variables = new HashMap<>();

        /**
         * Extract variables from request.
         */
        private List<RequestExtractConfig> extractFromRequest = new ArrayList<>();
    }

    @Data
    public static class RequestExtractConfig {
        /**
         * Source: PATH, HEADER, QUERY, BODY.
         */
        private String source = "PATH";

        /**
         * Variable name in template.
         */
        private String name;

        /**
         * Extraction expression.
         * For PATH: "/users/{id}" -> "id"
         * For HEADER: header name
         * For QUERY: query parameter name
         * For BODY: JSONPath expression
         */
        private String expression;

        /**
         * Default value if extraction fails.
         */
        private String defaultValue;
    }

    @Data
    public static class MockDelayConfig {
        private boolean enabled = false;

        /**
         * Fixed delay in milliseconds.
         */
        private int fixedDelayMs = 0;

        /**
         * Random delay configuration.
         */
        private RandomDelayConfig randomDelay = new RandomDelayConfig();

        /**
         * Preset network conditions: FAST, 3G, 4G, SLOW_3G.
         */
        private String networkConditions = "FAST";
    }

    @Data
    public static class RandomDelayConfig {
        private boolean enabled = false;
        private int minMs = 100;
        private int maxMs = 500;
    }

    @Data
    public static class ErrorSimulationConfig {
        private boolean enabled = false;

        /**
         * Error rate percentage (0-100).
         */
        private int errorRate = 0;

        /**
         * Error status codes to simulate.
         */
        private List<Integer> errorStatusCodes = List.of(500, 503, 504);

        /**
         * Error response body template.
         */
        private String errorBodyTemplate = "{\"error\": \"Simulated error\", \"code\": ${statusCode}}";
    }

    @Data
    public static class PassThroughConfig {
        private boolean enabled = false;

        /**
         * Conditions to pass through to real backend.
         */
        private List<PassThroughCondition> conditions = new ArrayList<>();
    }

    @Data
    public static class PassThroughCondition {
        /**
         * Header condition: X-Mock-Bypass=true.
         */
        private String headerCondition;

        /**
         * Query parameter condition: mock=false.
         */
        private String queryCondition;
    }

    // ============== Helper Methods ==============

    /**
     * Calculate delay based on configuration.
     */
    public int calculateDelay() {
        if (!delay.isEnabled()) {
            return 0;
        }

        if (delay.getRandomDelay().isEnabled()) {
            int min = delay.getRandomDelay().getMinMs();
            int max = delay.getRandomDelay().getMaxMs();
            return min + (int) (Math.random() * (max - min));
        }

        // Preset network conditions
        return switch (delay.getNetworkConditions()) {
            case "FAST" -> delay.getFixedDelayMs();
            case "4G" -> delay.getFixedDelayMs() + (int) (Math.random() * 100);
            case "3G" -> delay.getFixedDelayMs() + 300 + (int) (Math.random() * 500);
            case "SLOW_3G" -> delay.getFixedDelayMs() + 1000 + (int) (Math.random() * 2000);
            default -> delay.getFixedDelayMs();
        };
    }

    /**
     * Check if should simulate error.
     */
    public boolean shouldSimulateError() {
        if (!errorSimulation.isEnabled() || errorSimulation.getErrorRate() <= 0) {
            return false;
        }
        return Math.random() * 100 < errorSimulation.getErrorRate();
    }

    /**
     * Get a random error status code.
     */
    public int getRandomErrorStatusCode() {
        List<Integer> codes = errorSimulation.getErrorStatusCodes();
        if (codes.isEmpty()) {
            return 500;
        }
        return codes.get((int) (Math.random() * codes.size()));
    }
}