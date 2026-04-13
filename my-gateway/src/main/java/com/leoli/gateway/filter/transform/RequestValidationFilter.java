package com.leoli.gateway.filter.transform;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.RequestValidationConfig;
import com.leoli.gateway.util.RouteUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Request Validation Filter.
 * <p>
 * Supports:
 * - JSON Schema validation
 * - Required field checking
 * - Type constraints (string, number, integer, boolean, array, object)
 * - Enum constraints
 * - Custom validators (script-based)
 *
 * @author leoli
 */
@Component
@Slf4j
public class RequestValidationFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    private final ObjectMapper objectMapper;

    @Autowired
    public RequestValidationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        RequestValidationConfig config = getConfig(routeId);

        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }

        // Check if method should be validated
        HttpMethod method = exchange.getRequest().getMethod();
        if (!shouldValidateMethod(method, config)) {
            return chain.filter(exchange);
        }

        // Check content type
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (!shouldValidateContentType(contentType, config)) {
            return chain.filter(exchange);
        }

        log.debug("RequestValidation filter - routeId: {}", routeId);

        // Cache and validate request body
        return cacheAndValidateBody(exchange, chain, config);
    }

    /**
     * Get configuration from StrategyManager.
     */
    private RequestValidationConfig getConfig(String routeId) {
        Map<String, Object> configMap = strategyManager.getRequestValidationConfig(routeId);
        if (configMap == null || configMap.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.convertValue(configMap, RequestValidationConfig.class);
        } catch (Exception e) {
            log.warn("Failed to parse RequestValidationConfig for route {}: {}", routeId, e.getMessage());
            return null;
        }
    }

    /**
     * Check if method should be validated.
     */
    private boolean shouldValidateMethod(HttpMethod method, RequestValidationConfig config) {
        List<String> validateMethods = config.getValidateOnMethods();
        if (validateMethods == null || validateMethods.isEmpty()) {
            return true;
        }
        return validateMethods.contains(method.name());
    }

    /**
     * Check if content type should be validated.
     */
    private boolean shouldValidateContentType(MediaType contentType, RequestValidationConfig config) {
        if (contentType == null) {
            return false;
        }

        List<String> validateTypes = config.getValidateOnContentTypes();
        if (validateTypes == null || validateTypes.isEmpty()) {
            return true;
        }

        String mimeType = contentType.getType() + "/" + contentType.getSubtype();
        return validateTypes.stream()
                .anyMatch(type -> type.equalsIgnoreCase(mimeType));
    }

    /**
     * Cache request body and validate.
     */
    private Mono<Void> cacheAndValidateBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                             RequestValidationConfig config) {
        ServerHttpRequest request = exchange.getRequest();

        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(new DefaultDataBufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    try {
                        // Read body bytes
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);

                        String bodyStr = new String(bytes, StandardCharsets.UTF_8);
                        if (bodyStr.isEmpty()) {
                            // Empty body, validate if required
                            ValidationResult result = new ValidationResult();
                            if (config.getFieldValidation().isEnabled()
                                    && !config.getFieldValidation().getRequiredFields().isEmpty()) {
                                result.addError("body", "Request body is required but empty");
                            }

                            if (!result.isValid()) {
                                return sendValidationError(exchange, result, config);
                            }
                            return chain.filter(exchange);
                        }

                        // Parse JSON
                        JsonNode jsonNode;
                        try {
                            jsonNode = objectMapper.readTree(bodyStr);
                        } catch (Exception e) {
                            ValidationResult result = new ValidationResult();
                            result.addError("body", "Invalid JSON format: " + e.getMessage());
                            return sendValidationError(exchange, result, config);
                        }

                        // Validate
                        ValidationResult result = validate(jsonNode, config);

                        if (!result.isValid()) {
                            return sendValidationError(exchange, result, config);
                        }

                        // Rebuild request with cached body
                        ServerHttpRequest newRequest = new ServerHttpRequestDecorator(request) {
                            @Override
                            public Flux<DataBuffer> getBody() {
                                return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                            }
                        };

                        return chain.filter(exchange.mutate().request(newRequest).build());

                    } catch (Exception e) {
                        log.error("Error validating request body: {}", e.getMessage(), e);
                        return chain.filter(exchange);
                    }
                });
    }

    /**
     * Validate JSON against all configured validators.
     */
    private ValidationResult validate(JsonNode jsonNode, RequestValidationConfig config) {
        ValidationResult result = new ValidationResult();
        String mode = config.getValidationMode();

        boolean stopOnFirstError = config.isStopOnFirstError();

        // Schema validation
        if (("SCHEMA".equals(mode) || "HYBRID".equals(mode))
                && config.getSchemaValidation().isEnabled()) {
            validateSchema(jsonNode, config.getSchemaValidation(), result);
            if (stopOnFirstError && !result.isValid()) {
                return result;
            }
        }

        // Field validation
        if (("CUSTOM".equals(mode) || "HYBRID".equals(mode))
                && config.getFieldValidation().isEnabled()) {
            validateFields(jsonNode, config.getFieldValidation(), result);
            if (stopOnFirstError && !result.isValid()) {
                return result;
            }
        }

        // Custom validators
        if (!config.getCustomValidators().isEmpty()) {
            validateCustom(jsonNode, config.getCustomValidators(), result);
        }

        return result;
    }

    /**
     * Validate against JSON Schema.
     */
    private void validateSchema(JsonNode jsonNode, RequestValidationConfig.SchemaValidationConfig config,
                                 ValidationResult result) {
        // Simple schema validation implementation
        // For production, use networknt/json-schema-validator library
        String schemaSource = config.getSchemaSource();

        if ("INLINE".equals(schemaSource) && config.getInlineSchema() != null) {
            try {
                JsonNode schemaNode = objectMapper.readTree(config.getInlineSchema());
                validateAgainstSchema(jsonNode, schemaNode, result);
            } catch (Exception e) {
                log.warn("Failed to parse inline schema: {}", e.getMessage());
            }
        }
        // For REF and URL sources, implement schema loading logic
    }

    /**
     * Simple schema validation (basic implementation).
     */
    private void validateAgainstSchema(JsonNode data, JsonNode schema, ValidationResult result) {
        if (!schema.isObject()) {
            return;
        }

        // Check type
        if (schema.has("type")) {
            String expectedType = schema.get("type").asText();
            if (!checkType(data, expectedType)) {
                result.addError("", "Expected type '" + expectedType + "' but got '"
                        + getNodeType(data) + "'");
                return;
            }
        }

        // Check required fields
        if (schema.has("required") && data.isObject()) {
            JsonNode required = schema.get("required");
            if (required.isArray()) {
                for (JsonNode field : required) {
                    String fieldName = field.asText();
                    if (!data.has(fieldName)) {
                        result.addError(fieldName, "Required field is missing");
                    }
                }
            }
        }

        // Check properties
        if (schema.has("properties") && data.isObject()) {
            JsonNode properties = schema.get("properties");
            for (Map.Entry<String, JsonNode> entry : properties.properties()) {
                String propName = entry.getKey();
                if (data.has(propName)) {
                    validateAgainstSchema(data.get(propName), entry.getValue(), result);
                }
            }
        }

        // Check items (for arrays)
        if (schema.has("items") && data.isArray()) {
            JsonNode itemsSchema = schema.get("items");
            for (int i = 0; i < data.size(); i++) {
                validateAgainstSchema(data.get(i), itemsSchema, result);
            }
        }

        // Check min/max
        if (schema.has("minimum") && data.isNumber()) {
            double min = schema.get("minimum").asDouble();
            if (data.asDouble() < min) {
                result.addError("", "Value must be >= " + min);
            }
        }
        if (schema.has("maximum") && data.isNumber()) {
            double max = schema.get("maximum").asDouble();
            if (data.asDouble() > max) {
                result.addError("", "Value must be <= " + max);
            }
        }

        // Check minLength/maxLength
        if (schema.has("minLength") && data.isTextual()) {
            int minLen = schema.get("minLength").asInt();
            if (data.asText().length() < minLen) {
                result.addError("", "String length must be >= " + minLen);
            }
        }
        if (schema.has("maxLength") && data.isTextual()) {
            int maxLen = schema.get("maxLength").asInt();
            if (data.asText().length() > maxLen) {
                result.addError("", "String length must be <= " + maxLen);
            }
        }

        // Check pattern
        if (schema.has("pattern") && data.isTextual()) {
            String pattern = schema.get("pattern").asText();
            if (!Pattern.matches(pattern, data.asText())) {
                result.addError("", "Value does not match pattern: " + pattern);
            }
        }

        // Check enum
        if (schema.has("enum")) {
            JsonNode enumValues = schema.get("enum");
            boolean found = false;
            for (JsonNode enumVal : enumValues) {
                if (data.equals(enumVal)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.addError("", "Value must be one of: " + enumValues);
            }
        }
    }

    /**
     * Check if data matches expected type.
     */
    private boolean checkType(JsonNode data, String expectedType) {
        switch (expectedType.toLowerCase()) {
            case "string":
                return data.isTextual();
            case "number":
                return data.isNumber();
            case "integer":
                return data.isInt() || data.isLong();
            case "boolean":
                return data.isBoolean();
            case "array":
                return data.isArray();
            case "object":
                return data.isObject();
            case "null":
                return data.isNull();
            default:
                return true;
        }
    }

    /**
     * Get node type name.
     */
    private String getNodeType(JsonNode node) {
        if (node.isTextual()) return "string";
        if (node.isInt() || node.isLong()) return "integer";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isArray()) return "array";
        if (node.isObject()) return "object";
        if (node.isNull()) return "null";
        return "unknown";
    }

    /**
     * Validate fields using field validation config.
     */
    private void validateFields(JsonNode jsonNode, RequestValidationConfig.FieldValidationConfig config,
                                 ValidationResult result) {
        // Check required fields
        for (String requiredField : config.getRequiredFields()) {
            JsonNode fieldValue = getFieldByPath(jsonNode, requiredField);
            if (fieldValue == null || fieldValue.isNull()) {
                result.addError(requiredField, "Required field is missing");
            }
        }

        // Check type constraints
        for (RequestValidationConfig.TypeConstraint constraint : config.getTypeConstraints()) {
            JsonNode fieldValue = getFieldByPath(jsonNode, constraint.getFieldPath());
            if (fieldValue == null || fieldValue.isNull()) {
                continue; // Skip null values
            }

            String error = validateTypeConstraint(fieldValue, constraint);
            if (error != null) {
                result.addError(constraint.getFieldPath(), error);
            }
        }

        // Check enum constraints
        for (RequestValidationConfig.EnumConstraint constraint : config.getEnumConstraints()) {
            JsonNode fieldValue = getFieldByPath(jsonNode, constraint.getFieldPath());
            if (fieldValue == null || fieldValue.isNull()) {
                continue;
            }

            boolean found = constraint.getAllowedValues().stream()
                    .anyMatch(allowed -> {
                        if (fieldValue.isTextual()) {
                            return fieldValue.asText().equals(String.valueOf(allowed));
                        }
                        return fieldValue.equals(objectMapper.valueToTree(allowed));
                    });

            if (!found) {
                String message = constraint.getErrorMessage() != null
                        ? constraint.getErrorMessage()
                        : "Value must be one of: " + constraint.getAllowedValues();
                result.addError(constraint.getFieldPath(), message);
            }
        }
    }

    /**
     * Validate a type constraint.
     */
    private String validateTypeConstraint(JsonNode value, RequestValidationConfig.TypeConstraint constraint) {
        // Check type
        if (constraint.getExpectedType() != null && !checkType(value, constraint.getExpectedType())) {
            return constraint.getErrorMessage() != null
                    ? constraint.getErrorMessage()
                    : "Expected type '" + constraint.getExpectedType() + "' but got '" + getNodeType(value) + "'";
        }

        // Check min/max for numbers
        if (value.isNumber()) {
            if (constraint.getMin() != null && value.asDouble() < constraint.getMin().doubleValue()) {
                return "Value must be >= " + constraint.getMin();
            }
            if (constraint.getMax() != null && value.asDouble() > constraint.getMax().doubleValue()) {
                return "Value must be <= " + constraint.getMax();
            }
        }

        // Check min/max length for strings
        if (value.isTextual()) {
            String text = value.asText();
            if (constraint.getMinLength() != null && text.length() < constraint.getMinLength()) {
                return "String length must be >= " + constraint.getMinLength();
            }
            if (constraint.getMaxLength() != null && text.length() > constraint.getMaxLength()) {
                return "String length must be <= " + constraint.getMaxLength();
            }
            if (constraint.getPattern() != null && !Pattern.matches(constraint.getPattern(), text)) {
                return constraint.getErrorMessage() != null
                        ? constraint.getErrorMessage()
                        : "Value does not match pattern: " + constraint.getPattern();
            }
        }

        return null;
    }

    /**
     * Validate using custom validators.
     */
    private void validateCustom(JsonNode jsonNode, List<RequestValidationConfig.CustomValidatorConfig> validators,
                                 ValidationResult result) {
        for (RequestValidationConfig.CustomValidatorConfig validator : validators) {
            JsonNode fieldValue = getFieldByPath(jsonNode, validator.getFieldPath());
            if (fieldValue == null) {
                continue;
            }

            if ("SCRIPT".equals(validator.getValidatorType()) && validator.getScript() != null) {
                boolean valid = evaluateScript(fieldValue, validator.getScript());
                if (!valid) {
                    String message = validator.getErrorMessage() != null
                            ? validator.getErrorMessage()
                            : "Custom validation failed for field: " + validator.getFieldPath();
                    result.addError(validator.getFieldPath(), message);
                }
            }
            // BEAN and HTTP validators would require additional implementation
        }
    }

    /**
     * Simple script evaluation (basic SpEL-like).
     */
    private boolean evaluateScript(JsonNode value, String script) {
        // Basic script evaluation for common patterns
        // For production, use proper expression language engine

        if (script.contains("!= null")) {
            return !value.isNull();
        }
        if (script.contains("== null")) {
            return value.isNull();
        }
        if (script.contains(".isEmpty()") && value.isTextual()) {
            return !value.asText().isEmpty();
        }
        if (script.contains(".length() >") && value.isTextual()) {
            String text = value.asText();
            try {
                int minLength = Integer.parseInt(script.replaceAll(".*\\.length\\(\\) > (\\d+).*", "$1"));
                return text.length() > minLength;
            } catch (Exception e) {
                return true;
            }
        }

        // Default to true for unrecognized scripts
        return true;
    }

    /**
     * Get field value by path.
     */
    private JsonNode getFieldByPath(JsonNode node, String path) {
        if (path == null || path.isEmpty()) {
            return node;
        }

        String[] parts = path.replace("$.", "").split("\\.");
        JsonNode current = node;

        for (String part : parts) {
            if (current == null || !current.has(part)) {
                return null;
            }
            current = current.get(part);
        }

        return current;
    }

    /**
     * Send validation error response.
     */
    private Mono<Void> sendValidationError(ServerWebExchange exchange, ValidationResult result,
                                            RequestValidationConfig config) {
        int statusCode = config.getErrorResponse().getStatusCode();
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(statusCode));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body;
        if ("DETAILED".equals(config.getErrorResponse().getFormat())) {
            try {
                ObjectNode errorResponse = objectMapper.createObjectNode();
                errorResponse.put("error", "Validation Error");

                if (config.getErrorResponse().getCustomMessage() != null) {
                    errorResponse.put("message", config.getErrorResponse().getCustomMessage());
                } else {
                    errorResponse.put("message", "Request validation failed");
                }

                ArrayNode errors = errorResponse.putArray("errors");
                for (ValidationError error : result.getErrors()) {
                    ObjectNode errorNode = errors.addObject();
                    errorNode.put("field", error.getField());
                    errorNode.put("message", error.getMessage());
                }

                body = objectMapper.writeValueAsString(errorResponse);
            } catch (Exception e) {
                body = "{\"error\":\"Validation Error\",\"message\":\"Request validation failed\"}";
            }
        } else {
            body = "{\"error\":\"Validation Error\",\"message\":\"Request validation failed\"}";
        }

        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8)))
        );
    }

    @Override
    public int getOrder() {
        // After RequestTransform (-255)
        return FilterOrderConstants.REQUEST_VALIDATION;
    }

    // ============== Inner Classes ==============

    @Data
    private static class ValidationResult {
        private boolean valid = true;
        private List<ValidationError> errors = new ArrayList<>();

        public void addError(String field, String message) {
            errors.add(new ValidationError(field, message));
            valid = false;
        }
    }

    @Data
    private static class ValidationError {
        private final String field;
        private final String message;

        public ValidationError(String field, String message) {
            this.field = field;
            this.message = message;
        }
    }
}