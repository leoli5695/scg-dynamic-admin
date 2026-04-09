package com.leoli.gateway.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Response Transform Configuration.
 * Supports protocol conversion, field mapping, and data masking for responses.
 *
 * @author leoli
 */
@Data
public class ResponseTransformConfig {

    private boolean enabled = false;

    // ============== Protocol Transform ==============
    private ProtocolTransformConfig protocolTransform = new ProtocolTransformConfig();

    // ============== Field Mapping ==============
    private FieldMappingConfig fieldMapping = new FieldMappingConfig();

    // ============== Data Masking ==============
    private DataMaskingConfig dataMasking = new DataMaskingConfig();

    // ============== Execution Conditions ==============
    /**
     * Only transform when response status code matches.
     * E.g., [200, 201, 202]
     */
    private List<Integer> transformOnStatusCodes = new ArrayList<>();

    /**
     * Skip transformation for these Content-Types.
     * E.g., ["image/*", "video/*", "application/octet-stream"]
     */
    private List<String> skipOnContentTypes = new ArrayList<>();

    /**
     * Maximum body size to transform (in bytes).
     */
    private int maxBodySize = 10 * 1024 * 1024; // 10MB default for responses

    /**
     * Error handling strategy when transformation fails.
     * SKIP_ON_ERROR: Return original response.
     * RETURN_ERROR: Return 500 error.
     * RETURN_ORIGINAL: Return original response without transformation.
     */
    private String errorHandling = "RETURN_ORIGINAL";

    // ============== Nested Config Classes ==============

    @Data
    public static class ProtocolTransformConfig {
        private boolean enabled = false;

        /**
         * Source format: AUTO (auto-detect), JSON, XML.
         */
        private String sourceFormat = "AUTO";

        /**
         * Target format: JSON, XML.
         */
        private String targetFormat = "JSON";

        /**
         * Custom content type to set after transformation.
         */
        private String customContentType;
    }

    @Data
    public static class FieldMappingConfig {
        private boolean enabled = false;

        /**
         * Field mappings for response.
         */
        private List<FieldMapping> mappings = new ArrayList<>();

        /**
         * Fields to remove from response (internal fields).
         * Common use: remove "_id", "createdAt", "updatedAt", "internalNote".
         */
        private List<String> removeFields = new ArrayList<>();

        /**
         * Fields to add to response.
         */
        private Map<String, String> addFields = new HashMap<>();

        /**
         * Wrap response in a custom envelope.
         * E.g., {"data": {...}}
         */
        private String wrapInEnvelope;

        /**
         * Unwrap response from an envelope.
         * E.g., extract from "response.data"
         */
        private String unwrapFromEnvelope;
    }

    @Data
    public static class FieldMapping {
        private String sourcePath;
        private String targetPath;
        private String transform = "COPY";
        private String defaultValue;
        private String valueTransform;
    }

    @Data
    public static class DataMaskingConfig {
        private boolean enabled = false;
        private List<MaskingRule> rules = new ArrayList<>();
    }

    @Data
    public static class MaskingRule {
        private String fieldPath;
        private String maskType = "FULL";
        private String pattern;
        private String replacement = "***";
        private int keepLength = 0;
        private String keepPosition = "START";
    }
}