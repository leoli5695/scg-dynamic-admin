package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.MockResponseConfig;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock Response Filter.
 * <p>
 * Supports:
 * - Static mock responses (fixed response body)
 * - Dynamic mock responses (based on request conditions)
 * - Template mock responses (using Handlebars templates)
 * - Delay simulation
 * - Error simulation
 *
 * @author leoli
 */
@Component
@Slf4j
public class MockResponseFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Handlebars handlebars = new Handlebars();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        MockResponseConfig config = getConfig(routeId);

        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }

        log.debug("MockResponse filter - routeId: {}", routeId);

        // Check pass-through conditions
        if (config.getPassThrough().isEnabled() && shouldPassThrough(exchange, config.getPassThrough())) {
            log.debug("Pass-through condition matched, forwarding to real backend");
            return chain.filter(exchange);
        }

        // Determine mock response
        MockResponse result = determineMockResponse(exchange, config);

        if (result == null) {
            // No mock matched, pass through
            return chain.filter(exchange);
        }

        // Error simulation
        if (config.shouldSimulateError()) {
            int errorStatus = config.getRandomErrorStatusCode();
            return sendMockResponse(exchange, errorStatus, "application/json",
                    buildErrorBody(errorStatus, config), 0);
        }

        // Delay simulation
        int delayMs = config.calculateDelay();

        log.info("Returning mock response for routeId: {}, statusCode: {}, delay: {}ms",
                routeId, result.getStatusCode(), delayMs);

        return sendMockResponse(exchange, result.getStatusCode(), result.getContentType(),
                result.getBody(), delayMs);
    }

    /**
     * Get configuration from StrategyManager.
     */
    private MockResponseConfig getConfig(String routeId) {
        Map<String, Object> configMap = strategyManager.getMockResponseConfig(routeId);
        if (configMap == null || configMap.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.convertValue(configMap, MockResponseConfig.class);
        } catch (Exception e) {
            log.warn("Failed to parse MockResponseConfig for route {}: {}", routeId, e.getMessage());
            return null;
        }
    }

    /**
     * Check if request should pass through to real backend.
     */
    private boolean shouldPassThrough(ServerWebExchange exchange, MockResponseConfig.PassThroughConfig config) {
        List<MockResponseConfig.PassThroughCondition> conditions = config.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return false;
        }

        ServerHttpRequest request = exchange.getRequest();

        for (MockResponseConfig.PassThroughCondition condition : conditions) {
            // Check header condition
            if (condition.getHeaderCondition() != null && !condition.getHeaderCondition().isEmpty()) {
                String[] parts = condition.getHeaderCondition().split("=");
                if (parts.length == 2) {
                    String headerName = parts[0].trim();
                    String expectedValue = parts[1].trim();
                    String actualValue = request.getHeaders().getFirst(headerName);
                    if (expectedValue.equals(actualValue)) {
                        return true;
                    }
                }
            }

            // Check query condition
            if (condition.getQueryCondition() != null && !condition.getQueryCondition().isEmpty()) {
                String[] parts = condition.getQueryCondition().split("=");
                if (parts.length == 2) {
                    String paramName = parts[0].trim();
                    String expectedValue = parts[1].trim();
                    String actualValue = request.getQueryParams().getFirst(paramName);
                    if (expectedValue.equals(actualValue)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Determine mock response based on configuration and request.
     */
    private MockResponse determineMockResponse(ServerWebExchange exchange, MockResponseConfig config) {
        String mode = config.getMockMode();

        switch (mode) {
            case "STATIC":
                return buildStaticMockResponse(config.getStaticMock());

            case "DYNAMIC":
                return buildDynamicMockResponse(exchange, config.getDynamicMock());

            case "TEMPLATE":
                return buildTemplateMockResponse(exchange, config.getTemplateMock());

            default:
                return buildStaticMockResponse(config.getStaticMock());
        }
    }

    /**
     * Build static mock response.
     */
    private MockResponse buildStaticMockResponse(MockResponseConfig.StaticMockConfig config) {
        MockResponse response = new MockResponse();
        response.setStatusCode(config.getStatusCode());
        response.setContentType(config.getContentType());
        response.setHeaders(config.getHeaders());

        if (config.getBody() != null) {
            response.setBody(config.getBody());
        } else if (config.getBodyFile() != null) {
            // In production, load from file
            response.setBody("{\"message\": \"Mock response from file: " + config.getBodyFile() + "\"}");
        } else {
            response.setBody("{}");
        }

        return response;
    }

    /**
     * Build dynamic mock response based on request conditions.
     */
    private MockResponse buildDynamicMockResponse(ServerWebExchange exchange,
                                                   MockResponseConfig.DynamicMockConfig config) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        for (MockResponseConfig.MockCondition condition : config.getConditions()) {
            if (matchesCondition(exchange, condition)) {
                return buildMockResponseFromRef(condition.getResponse());
            }
        }

        // Return default response
        return buildMockResponseFromRef(config.getDefaultResponse());
    }

    /**
     * Check if request matches a mock condition.
     */
    private boolean matchesCondition(ServerWebExchange exchange, MockResponseConfig.MockCondition condition) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // Check path pattern
        if (condition.getPathPattern() != null && !condition.getPathPattern().isEmpty()) {
            if (!pathMatcher.match(condition.getPathPattern(), path)) {
                return false;
            }
        }

        // Check header conditions
        if (condition.getHeaderConditions() != null && !condition.getHeaderConditions().isEmpty()) {
            for (Map.Entry<String, String> entry : condition.getHeaderConditions().entrySet()) {
                String actualValue = request.getHeaders().getFirst(entry.getKey());
                if (!entry.getValue().equals(actualValue)) {
                    return false;
                }
            }
        }

        // Check query conditions
        if (condition.getQueryConditions() != null && !condition.getQueryConditions().isEmpty()) {
            for (Map.Entry<String, String> entry : condition.getQueryConditions().entrySet()) {
                String actualValue = request.getQueryParams().getFirst(entry.getKey());
                if (!entry.getValue().equals(actualValue)) {
                    return false;
                }
            }
        }

        // Body conditions would require body caching, simplified here
        return true;
    }

    /**
     * Build mock response from reference.
     */
    private MockResponse buildMockResponseFromRef(MockResponseConfig.MockResponseRef ref) {
        MockResponse response = new MockResponse();
        response.setStatusCode(ref.getStatusCode());
        response.setContentType(ref.getContentType());
        response.setHeaders(ref.getHeaders());
        response.setBody(ref.getBody() != null ? ref.getBody() : "{}");
        return response;
    }

    /**
     * Build template-based mock response.
     */
    private MockResponse buildTemplateMockResponse(ServerWebExchange exchange,
                                                    MockResponseConfig.TemplateMockConfig config) {
        MockResponse response = new MockResponse();
        response.setStatusCode(200);
        response.setContentType(config.getTemplateEngine().contains("JSON")
                ? "application/json" : "application/json");

        try {
            // Extract variables from request
            Map<String, Object> variables = new HashMap<>();

            // Add static variables
            if (config.getVariables() != null) {
                variables.putAll(config.getVariables());
            }

            // Extract from request
            for (MockResponseConfig.RequestExtractConfig extract : config.getExtractFromRequest()) {
                String value = extractFromRequest(exchange, extract);
                if (value != null) {
                    variables.put(extract.getName(), value);
                } else if (extract.getDefaultValue() != null) {
                    variables.put(extract.getName(), extract.getDefaultValue());
                }
            }

            // Render template
            String template = config.getTemplate();
            if (template != null && !template.isEmpty()) {
                response.setBody(renderTemplate(template, variables, config.getTemplateEngine()));
            } else {
                response.setBody("{}");
            }

        } catch (Exception e) {
            log.error("Error rendering mock template: {}", e.getMessage(), e);
            response.setBody("{\"error\": \"Template rendering failed\"}");
        }

        return response;
    }

    /**
     * Extract value from request.
     */
    private String extractFromRequest(ServerWebExchange exchange,
                                       MockResponseConfig.RequestExtractConfig extract) {
        ServerHttpRequest request = exchange.getRequest();
        String source = extract.getSource();
        String expression = extract.getExpression();

        switch (source) {
            case "PATH":
                // Extract from path like /users/{id}
                String path = request.getPath().value();
                if (expression != null && expression.contains("{") && expression.contains("}")) {
                    // Simple path variable extraction
                    String varName = expression.replaceAll(".*\\{(.+)\\}.*", "$1");
                    String pattern = expression.replace("{" + varName + "}", "(.+)");
                    if (pathMatcher.match(pattern, path)) {
                        Map<String, String> vars = pathMatcher.extractUriTemplateVariables(pattern, path);
                        return vars.get(varName);
                    }
                }
                return null;

            case "HEADER":
                return request.getHeaders().getFirst(expression);

            case "QUERY":
                return request.getQueryParams().getFirst(expression);

            case "BODY":
                // Would require body caching, simplified here
                return null;

            default:
                return null;
        }
    }

    /**
     * Render template with variables.
     */
    private String renderTemplate(String template, Map<String, Object> variables, String engine) {
        try {
            switch (engine) {
                case "HANDLEBARS":
                    Template handlebarsTemplate = handlebars.compileInline(template);
                    return handlebarsTemplate.apply(variables);

                case "MUSTACHE":
                    // Simplified mustache-like rendering
                    return simpleTemplateRender(template, variables);

                case "JSON_TEMPLATE":
                default:
                    return simpleTemplateRender(template, variables);
            }
        } catch (Exception e) {
            log.error("Template rendering error: {}", e.getMessage());
            return template;
        }
    }

    /**
     * Simple template rendering ({{variable}} replacement).
     */
    private String simpleTemplateRender(String template, Map<String, Object> variables) {
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /**
     * Build error response body.
     */
    private String buildErrorBody(int statusCode, MockResponseConfig config) {
        String template = config.getErrorSimulation().getErrorBodyTemplate();
        if (template != null) {
            return template.replace("${statusCode}", String.valueOf(statusCode));
        }
        return String.format("{\"error\": \"Simulated error\", \"code\": %d}", statusCode);
    }

    /**
     * Send mock response.
     */
    private Mono<Void> sendMockResponse(ServerWebExchange exchange, int statusCode,
                                         String contentType, String body, int delayMs) {
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(statusCode));
        exchange.getResponse().getHeaders().setContentType(MediaType.parseMediaType(contentType));

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().getHeaders().setContentLength(bytes.length);

        // Add mock indicator header
        exchange.getResponse().getHeaders().add("X-Mock-Response", "true");

        Mono<Void> responseMono = exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))
        );

        // Apply delay if configured
        if (delayMs > 0) {
            return Mono.delay(java.time.Duration.ofMillis(delayMs))
                    .then(responseMono);
        }

        return responseMono;
    }

    @Override
    public int getOrder() {
        // Right after authentication (-250)
        return -249;
    }

    // ============== Inner Classes ==============

    private static class MockResponse {
        private int statusCode = 200;
        private String contentType = "application/json";
        private Map<String, String> headers;
        private String body;

        public int getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(int statusCode) {
            this.statusCode = statusCode;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }
    }
}