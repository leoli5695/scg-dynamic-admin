package com.leoli.gateway.filter.transform;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.RequestTransformConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.PatternCache;
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
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request Transform Filter.
 * <p>
 * Supports:
 * - Protocol transformation (JSON ↔ XML)
 * - Field mapping (rename, remove, add fields)
 * - Data masking (sensitive field obfuscation)
 *
 * @author leoli
 */
@Component
@Slf4j
public class RequestTransformFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Autowired
    private ObjectMapper objectMapper;  // Use injected singleton

    @Autowired
    private XmlMapper xmlMapper;  // Use injected singleton

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        RequestTransformConfig config = getConfig(routeId);

        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }

        // Check skip conditions
        if (shouldSkip(exchange, config)) {
            return chain.filter(exchange);
        }

        // Only process requests with body
        HttpMethod method = exchange.getRequest().getMethod();
        if (method != HttpMethod.POST && method != HttpMethod.PUT && method != HttpMethod.PATCH) {
            return chain.filter(exchange);
        }

        // FIX (OOM): pre-check Content-Length against maxBodySize BEFORE consuming the body.
        // Previously the limit was only checked AFTER an unbounded join had already allocated
        // the entire body in heap, making the maxBodySize protection ineffective.
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentLength > config.getMaxBodySize()) {
            log.debug("Request body ({} bytes) exceeds transform maxBodySize ({}), passing through unchanged",
                    contentLength, config.getMaxBodySize());
            return chain.filter(exchange);
        }

        log.debug("RequestTransform filter - routeId: {}, transforming request body", routeId);

        // Cache and transform request body
        return cacheAndTransformBody(exchange, chain, config);
    }

    /**
     * Get configuration from StrategyManager.
     * <p>
     * FIX (performance): uses the typed getter whose conversion result is
     * cached per strategy version instead of running ObjectMapper.convertValue
     * on every request.
     */
    private RequestTransformConfig getConfig(String routeId) {
        return strategyManager.getConfig(routeId,
                StrategyDefinition.TYPE_REQUEST_TRANSFORM, RequestTransformConfig.class);
    }

    /**
     * Check if transformation should be skipped.
     */
    private boolean shouldSkip(ServerWebExchange exchange, RequestTransformConfig config) {
        // Check skip methods
        List<String> skipMethods = config.getSkipOnMethods();
        if (skipMethods != null && !skipMethods.isEmpty()) {
            String method = exchange.getRequest().getMethod().name();
            if (skipMethods.contains(method)) {
                return true;
            }
        }

        // Check content type
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null) {
            return true; // No content type, skip
        }

        List<String> transformOnTypes = config.getTransformOnContentTypes();
        if (transformOnTypes != null && !transformOnTypes.isEmpty()) {
            String mimeType = contentType.getType() + "/" + contentType.getSubtype();
            boolean matches = transformOnTypes.stream()
                    .anyMatch(type -> {
                        if (type.endsWith("/*")) {
                            return mimeType.startsWith(type.substring(0, type.length() - 1));
                        }
                        return type.equalsIgnoreCase(mimeType);
                    });
            if (!matches) {
                return true;
            }
        }

        return false;
    }

    /**
     * Cache request body, transform, and continue.
     */
    private Mono<Void> cacheAndTransformBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                              RequestTransformConfig config) {
        ServerHttpRequest request = exchange.getRequest();

        // FIX (OOM): bounded join by maxBodySize; the Content-Length pre-check in filter() covers
        // known sizes, this limit also guards chunked requests.
        return com.leoli.gateway.util.RequestBodyCache.read(exchange, config.getMaxBodySize())
                .flatMap(bytes -> {

                    try {
                        // Check size limit
                        if (bytes.length > config.getMaxBodySize()) {
                            log.debug("Request body size {} exceeds limit {}, passing through unchanged",
                                    bytes.length, config.getMaxBodySize());
                            // FIX: body was already consumed by join - must rebuild it before forwarding
                            return chain.filter(rebuildWithCachedBody(exchange, request, bytes));
                        }

                        String bodyStr = new String(bytes, StandardCharsets.UTF_8);
                        if (bodyStr.isEmpty()) {
                            return chain.filter(exchange);
                        }

                        // Transform the body
                        String transformedBody = transformBody(bodyStr, exchange, config);

                        // Create new request with transformed body
                        byte[] newBytes = transformedBody.getBytes(StandardCharsets.UTF_8);

                        ServerHttpRequest newRequest = new ServerHttpRequestDecorator(request) {
                            @Override
                            public Flux<DataBuffer> getBody() {
                                return Flux.just(exchange.getResponse().bufferFactory().wrap(newBytes));
                            }

                            @Override
                            public org.springframework.http.HttpHeaders getHeaders() {
                                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                                headers.putAll(super.getHeaders());

                                // Update Content-Type if protocol transform changed format
                                if (config.getProtocolTransform().isEnabled()
                                        && !config.getProtocolTransform().isPreserveOriginalContentType()) {
                                    String newContentType = config.getProtocolTransform().getTargetFormat().equals("XML")
                                            ? "application/xml"
                                            : "application/json";
                                    headers.setContentType(MediaType.parseMediaType(newContentType));
                                }

                                headers.setContentLength(newBytes.length);
                                return headers;
                            }
                        };

                        ServerWebExchange transformed = exchange.mutate().request(newRequest).build();
                        return chain.filter(transformed.mutate().request(com.leoli.gateway.util.RequestBodyCache.wrap(transformed, newBytes)).build());

                    } catch (Exception e) {
                        log.error("Error transforming request body: {}", e.getMessage(), e);
                        // FIX: body was already consumed by join - rebuild it before pass-through
                        return chain.filter(rebuildWithCachedBody(exchange, request, bytes));
                    }
                })
                .onErrorResume(org.springframework.core.io.buffer.DataBufferLimitException.class, e -> {
                    // Chunked body exceeded maxBodySize mid-stream - the body is already partially
                    // consumed and cannot be forwarded safely; reject with 413.
                    log.warn("Request body exceeds transform maxBodySize ({} bytes), rejecting with 413",
                            config.getMaxBodySize());
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
                    return response.setComplete();
                });
    }

    /**
     * Rebuild the exchange request with already-consumed body bytes.
     * After DataBufferUtils.join the original body flux is empty, so any path that
     * continues the chain must use the rebuilt request to avoid losing the body.
     */
    private ServerWebExchange rebuildWithCachedBody(ServerWebExchange exchange, ServerHttpRequest request, byte[] bytes) {
        ServerHttpRequest newRequest = com.leoli.gateway.util.RequestBodyCache.wrap(exchange, bytes);
        return exchange.mutate().request(newRequest).build();
    }

    /**
     * Transform request body through the transformation pipeline.
     */
    private String transformBody(String body, ServerWebExchange exchange, RequestTransformConfig config) {
        try {
            // Step 1: Parse body based on source format
            String sourceFormat = detectFormat(body, config.getProtocolTransform().getSourceFormat());
            JsonNode jsonNode = parseToJsonNode(body, sourceFormat);

            if (jsonNode == null) {
                log.warn("Failed to parse request body as {}", sourceFormat);
                return body;
            }

            // Step 2: Apply protocol transformation (if needed)
            String targetFormat = config.getProtocolTransform().getTargetFormat();

            // Step 3: Apply field mapping
            if (config.getFieldMapping().isEnabled()) {
                jsonNode = applyFieldMapping(jsonNode, config.getFieldMapping());
            }

            // Step 4: Apply data masking
            if (config.getDataMasking().isEnabled()) {
                jsonNode = applyDataMasking(jsonNode, config.getDataMasking());
            }

            // Step 5: Serialize to target format
            return serializeToString(jsonNode, targetFormat);

        } catch (Exception e) {
            log.error("Transform error: {}", e.getMessage(), e);
            return body; // Return original on error
        }
    }

    /**
     * Detect body format.
     */
    private String detectFormat(String body, String configuredFormat) {
        if (!"AUTO".equalsIgnoreCase(configuredFormat)) {
            return configuredFormat;
        }

        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "JSON";
        } else if (trimmed.startsWith("<")) {
            return "XML";
        }
        return "JSON"; // Default
    }

    /**
     * Parse body to JsonNode.
     */
    private JsonNode parseToJsonNode(String body, String format) {
        try {
            switch (format.toUpperCase()) {
                case "XML":
                    return xmlMapper.readTree(body);
                case "JSON":
                default:
                    return objectMapper.readTree(body);
            }
        } catch (Exception e) {
            log.warn("Failed to parse body as {}: {}", format, e.getMessage());
            return null;
        }
    }

    /**
     * Serialize JsonNode to string.
     */
    private String serializeToString(JsonNode node, String format) {
        try {
            switch (format.toUpperCase()) {
                case "XML":
                    return xmlMapper.writeValueAsString(node);
                case "JSON":
                default:
                    return objectMapper.writeValueAsString(node);
            }
        } catch (Exception e) {
            log.error("Failed to serialize to {}: {}", format, e.getMessage());
            return "{}";
        }
    }

    /**
     * Apply field mapping transformations.
     */
    private JsonNode applyFieldMapping(JsonNode node, RequestTransformConfig.FieldMappingConfig config) {
        if (!node.isObject()) {
            return node;
        }

        ObjectNode objectNode = (ObjectNode) node.deepCopy();

        // Remove fields
        for (String removePath : config.getRemoveFields()) {
            removeFieldByPath(objectNode, removePath);
        }

        // Apply field mappings
        for (RequestTransformConfig.FieldMapping mapping : config.getMappings()) {
            applyFieldMapping(objectNode, mapping);
        }

        // Add new fields
        for (Map.Entry<String, String> entry : config.getAddFields().entrySet()) {
            setFieldByPath(objectNode, entry.getKey(), entry.getValue());
        }

        return objectNode;
    }

    /**
     * Apply a single field mapping.
     */
    private void applyFieldMapping(ObjectNode node, RequestTransformConfig.FieldMapping mapping) {
        JsonNode sourceValue = getFieldByPath(node, mapping.getSourcePath());
        if (sourceValue == null && mapping.getDefaultValue() != null) {
            sourceValue = objectMapper.getNodeFactory().textNode(mapping.getDefaultValue());
        }

        if (sourceValue != null) {
            String transform = mapping.getTransform();
            if ("RENAME".equals(transform) || "COPY".equals(transform)) {
                setFieldByPath(node, mapping.getTargetPath(), sourceValue);
                if ("RENAME".equals(transform)) {
                    removeFieldByPath(node, mapping.getSourcePath());
                }
            }
        }
    }

    /**
     * Get field value by path (simple dot notation).
     */
    private JsonNode getFieldByPath(JsonNode node, String path) {
        if (path == null || path.isEmpty()) {
            return null;
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
     * Set field value by path.
     */
    private void setFieldByPath(ObjectNode node, String path, Object value) {
        if (path == null || path.isEmpty()) {
            return;
        }

        String[] parts = path.replace("$.", "").split("\\.");
        ObjectNode current = node;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!current.has(part) || !current.get(part).isObject()) {
                current.set(part, objectMapper.getNodeFactory().objectNode());
            }
            current = (ObjectNode) current.get(part);
        }

        String lastPart = parts[parts.length - 1];
        if (value instanceof JsonNode) {
            current.set(lastPart, (JsonNode) value);
        } else if (value instanceof String) {
            current.put(lastPart, (String) value);
        }
    }

    /**
     * Remove field by path.
     */
    private void removeFieldByPath(ObjectNode node, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }

        String[] parts = path.replace("$.", "").split("\\.");
        JsonNode current = node;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!current.has(part)) {
                return;
            }
            current = current.get(part);
        }

        if (current.isObject() && current.has(parts[parts.length - 1])) {
            ((ObjectNode) current).remove(parts[parts.length - 1]);
        }
    }

    /**
     * Apply data masking.
     */
    private JsonNode applyDataMasking(JsonNode node, RequestTransformConfig.DataMaskingConfig config) {
        if (!node.isObject()) {
            return node;
        }

        ObjectNode objectNode = (ObjectNode) node.deepCopy();

        for (RequestTransformConfig.MaskingRule rule : config.getRules()) {
            applyMaskingRule(objectNode, rule);
        }

        return objectNode;
    }

    /**
     * Apply a single masking rule.
     */
    private void applyMaskingRule(ObjectNode node, RequestTransformConfig.MaskingRule rule) {
        JsonNode fieldValue = getFieldByPath(node, rule.getFieldPath());
        if (fieldValue == null || !fieldValue.isTextual()) {
            return;
        }

        String value = fieldValue.asText();
        String maskedValue = maskValue(value, rule);

        setFieldByPath(node, rule.getFieldPath(), maskedValue);
    }

    /**
     * Mask a value based on the rule.
     */
    private String maskValue(String value, RequestTransformConfig.MaskingRule rule) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        String maskType = rule.getMaskType();
        String replacement = rule.getReplacement() != null ? rule.getReplacement() : "***";

        switch (maskType) {
            case "FULL":
                return replacement;

            case "PARTIAL":
                int keepLength = rule.getKeepLength();
                String keepPosition = rule.getKeepPosition();

                if ("END".equals(keepPosition)) {
                    if (value.length() <= keepLength) {
                        return value;
                    }
                    return replacement + value.substring(value.length() - keepLength);
                } else {
                    // START
                    if (value.length() <= keepLength) {
                        return value;
                    }
                    return value.substring(0, keepLength) + replacement;
                }

            case "CUSTOM":
                if (rule.getPattern() != null) {
                    // FIX (performance): patterns were compiled on every request;
                    // use the shared compiled-pattern cache instead.
                    return PatternCache.compile(rule.getPattern())
                            .map(pattern -> pattern.matcher(value).replaceAll(replacement))
                            .orElseGet(() -> {
                                log.warn("Invalid masking pattern: {}", rule.getPattern());
                                return replacement;
                            });
                }
                return replacement;

            default:
                return replacement;
        }
    }

    @Override
    public int getOrder() {
        // After authentication (-250), before rate limiting
        return FilterOrderConstants.REQUEST_TRANSFORM;
    }
}