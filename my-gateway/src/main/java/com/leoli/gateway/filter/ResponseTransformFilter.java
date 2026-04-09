package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.ResponseTransformConfig;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
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
 * Response Transform Filter.
 * <p>
 * Supports:
 * - Protocol transformation (JSON ↔ XML)
 * - Field mapping (remove internal fields, rename, add)
 * - Data masking (sensitive field obfuscation)
 *
 * @author leoli
 */
@Component
@Slf4j
public class ResponseTransformFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XmlMapper xmlMapper = new XmlMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        ResponseTransformConfig config = getConfig(routeId);

        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }

        log.debug("ResponseTransform filter - routeId: {}", routeId);

        // Wrap response to intercept body
        ServerHttpResponse originalResponse = exchange.getResponse();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                // Check status code filter
                HttpStatus status = HttpStatus.valueOf(getStatusCode().value());
                if (!shouldTransformStatus(config, status)) {
                    return super.writeWith(body);
                }

                // Check content type filter
                if (shouldSkipContentType(config, getHeaders().getContentType())) {
                    return super.writeWith(body);
                }

                return Flux.from(body)
                        .collectList()
                        .flatMap(buffers -> {
                            if (buffers.isEmpty()) {
                                return super.writeWith(Flux.empty());
                            }

                            try {
                                // Merge all buffers
                                int totalSize = buffers.stream().mapToInt(DataBuffer::readableByteCount).sum();
                                byte[] allBytes = new byte[totalSize];
                                int offset = 0;
                                for (DataBuffer buffer : buffers) {
                                    int size = buffer.readableByteCount();
                                    buffer.read(allBytes, offset, size);
                                    offset += size;
                                    DataBufferUtils.release(buffer);
                                }

                                // Check size limit
                                if (allBytes.length > config.getMaxBodySize()) {
                                    log.debug("Response body size {} exceeds limit {}, passing through",
                                            allBytes.length, config.getMaxBodySize());
                                    return super.writeWith(Flux.just(originalResponse.bufferFactory().wrap(allBytes)));
                                }

                                // Transform the body
                                String originalBody = new String(allBytes, StandardCharsets.UTF_8);
                                String transformedBody = transformBody(originalBody, config);

                                // Update Content-Type if needed
                                updateContentType(getHeaders(), config);

                                byte[] newBytes = transformedBody.getBytes(StandardCharsets.UTF_8);
                                return super.writeWith(Flux.just(originalResponse.bufferFactory().wrap(newBytes)));

                            } catch (Exception e) {
                                log.error("Error transforming response body: {}", e.getMessage(), e);
                                // Return original on error based on error handling strategy
                                return super.writeWith(Flux.just(originalResponse.bufferFactory().wrap(allBytes)));
                            }
                        });
            }

            private byte[] allBytes;
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    /**
     * Get configuration from StrategyManager.
     */
    private ResponseTransformConfig getConfig(String routeId) {
        Map<String, Object> configMap = strategyManager.getResponseTransformConfig(routeId);
        if (configMap == null || configMap.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.convertValue(configMap, ResponseTransformConfig.class);
        } catch (Exception e) {
            log.warn("Failed to parse ResponseTransformConfig for route {}: {}", routeId, e.getMessage());
            return null;
        }
    }

    /**
     * Check if status code should be transformed.
     */
    private boolean shouldTransformStatus(ResponseTransformConfig config, HttpStatus status) {
        List<Integer> statusCodes = config.getTransformOnStatusCodes();
        if (statusCodes == null || statusCodes.isEmpty()) {
            return true; // Transform all status codes
        }
        return statusCodes.contains(status.value());
    }

    /**
     * Check if content type should be skipped.
     */
    private boolean shouldSkipContentType(ResponseTransformConfig config, MediaType contentType) {
        if (contentType == null) {
            return true;
        }

        List<String> skipTypes = config.getSkipOnContentTypes();
        if (skipTypes == null || skipTypes.isEmpty()) {
            return false;
        }

        String mimeType = contentType.getType() + "/" + contentType.getSubtype();
        return skipTypes.stream().anyMatch(skipType -> {
            if (skipType.endsWith("/*")) {
                return mimeType.startsWith(skipType.substring(0, skipType.length() - 1));
            }
            return skipType.equalsIgnoreCase(mimeType);
        });
    }

    /**
     * Transform response body through the transformation pipeline.
     */
    private String transformBody(String body, ResponseTransformConfig config) {
        try {
            // Step 1: Parse body
            String sourceFormat = detectFormat(body, config.getProtocolTransform().getSourceFormat());
            JsonNode jsonNode = parseToJsonNode(body, sourceFormat);

            if (jsonNode == null) {
                log.warn("Failed to parse response body as {}", sourceFormat);
                return body;
            }

            // Step 2: Apply field mapping
            if (config.getFieldMapping().isEnabled()) {
                jsonNode = applyFieldMapping(jsonNode, config.getFieldMapping());
            }

            // Step 3: Apply data masking
            if (config.getDataMasking().isEnabled()) {
                jsonNode = applyDataMasking(jsonNode, config.getDataMasking());
            }

            // Step 4: Serialize to target format
            String targetFormat = config.getProtocolTransform().isEnabled()
                    ? config.getProtocolTransform().getTargetFormat()
                    : "JSON";

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
        return "JSON";
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
    private JsonNode applyFieldMapping(JsonNode node, ResponseTransformConfig.FieldMappingConfig config) {
        if (!node.isObject()) {
            return node;
        }

        ObjectNode objectNode = (ObjectNode) node.deepCopy();

        // Remove internal fields
        for (String removePath : config.getRemoveFields()) {
            removeFieldByPath(objectNode, removePath);
        }

        // Apply field mappings
        for (ResponseTransformConfig.FieldMapping mapping : config.getMappings()) {
            applyFieldMapping(objectNode, mapping);
        }

        // Add new fields
        for (Map.Entry<String, String> entry : config.getAddFields().entrySet()) {
            setFieldByPath(objectNode, entry.getKey(), entry.getValue());
        }

        // Wrap in envelope if configured
        if (config.getWrapInEnvelope() != null && !config.getWrapInEnvelope().isEmpty()) {
            ObjectNode wrapped = objectMapper.getNodeFactory().objectNode();
            wrapped.set(config.getWrapInEnvelope(), objectNode);
            return wrapped;
        }

        // Unwrap from envelope if configured
        if (config.getUnwrapFromEnvelope() != null && !config.getUnwrapFromEnvelope().isEmpty()) {
            String[] path = config.getUnwrapFromEnvelope().split("\\.");
            JsonNode current = objectNode;
            for (String part : path) {
                if (current.has(part)) {
                    current = current.get(part);
                } else {
                    break;
                }
            }
            if (current.isObject()) {
                return current;
            }
        }

        return objectNode;
    }

    /**
     * Apply a single field mapping.
     */
    private void applyFieldMapping(ObjectNode node, ResponseTransformConfig.FieldMapping mapping) {
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
     * Get field value by path.
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
    private JsonNode applyDataMasking(JsonNode node, ResponseTransformConfig.DataMaskingConfig config) {
        if (!node.isObject()) {
            return node;
        }

        ObjectNode objectNode = (ObjectNode) node.deepCopy();

        for (ResponseTransformConfig.MaskingRule rule : config.getRules()) {
            applyMaskingRule(objectNode, rule);
        }

        return objectNode;
    }

    /**
     * Apply a single masking rule.
     */
    private void applyMaskingRule(ObjectNode node, ResponseTransformConfig.MaskingRule rule) {
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
    private String maskValue(String value, ResponseTransformConfig.MaskingRule rule) {
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
                    if (value.length() <= keepLength) {
                        return value;
                    }
                    return value.substring(0, keepLength) + replacement;
                }

            case "CUSTOM":
                if (rule.getPattern() != null) {
                    try {
                        Pattern pattern = Pattern.compile(rule.getPattern());
                        Matcher matcher = pattern.matcher(value);
                        return matcher.replaceAll(replacement);
                    } catch (Exception e) {
                        log.warn("Invalid masking pattern: {}", rule.getPattern());
                        return replacement;
                    }
                }
                return replacement;

            default:
                return replacement;
        }
    }

    /**
     * Update Content-Type header.
     */
    private void updateContentType(HttpHeaders headers, ResponseTransformConfig config) {
        if (!config.getProtocolTransform().isEnabled()) {
            return;
        }

        String customContentType = config.getProtocolTransform().getCustomContentType();
        if (customContentType != null && !customContentType.isEmpty()) {
            headers.setContentType(MediaType.parseMediaType(customContentType));
        } else {
            String targetFormat = config.getProtocolTransform().getTargetFormat();
            String newContentType = "XML".equals(targetFormat) ? "application/xml" : "application/json";
            headers.setContentType(MediaType.parseMediaType(newContentType));
        }
    }

    @Override
    public int getOrder() {
        // Before response is committed
        return -45;
    }
}