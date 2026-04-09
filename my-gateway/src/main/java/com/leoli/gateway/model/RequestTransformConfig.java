package com.leoli.gateway.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request Transform Configuration.
 * Supports protocol conversion, field mapping, and data masking.
 *
 * @author leoli
 */
@Data
public class RequestTransformConfig {

    private boolean enabled = false;

    // ============== Protocol Transform ==============
    private ProtocolTransformConfig protocolTransform = new ProtocolTransformConfig();

    // ============== Field Mapping ==============
    private FieldMappingConfig fieldMapping = new FieldMappingConfig();

    // ============== Data Masking ==============
    private DataMaskingConfig dataMasking = new DataMaskingConfig();

    // ============== Execution Conditions ==============
    /**
     * Only transform when Content-Type matches.
     * E.g., ["application/json", "application/xml"]
     */
    private List<String> transformOnContentTypes = new ArrayList<>();

    /**
     * Skip transformation for these HTTP methods.
     * E.g., ["GET", "HEAD", "DELETE"]
     */
    private List<String> skipOnMethods = new ArrayList<>();

    /**
     * Maximum body size to transform (in bytes).
     * Bodies larger than this will be passed through unchanged.
     */
    private int maxBodySize = 1024 * 1024; // 1MB default

    /**
     * Whether to validate JSON after transformation.
     */
    private boolean validateAfterTransform = false;

    // ============== Nested Config Classes ==============

    @Data
    public static class ProtocolTransformConfig {
        private boolean enabled = false;

        /**
         * Source format: JSON, XML, FORM.
         */
        private String sourceFormat = "JSON";

        /**
         * Target format: JSON, XML.
         */
        private String targetFormat = "JSON";

        /**
         * Whether to preserve original Content-Type header.
         */
        private boolean preserveOriginalContentType = false;

        /**
         * Custom content type to set after transformation.
         */
        private String customContentType;
    }

    @Data
    public static class FieldMappingConfig {
        private boolean enabled = false;

        /**
         * Field mappings: rename, move, or transform fields.
         */
        private List<FieldMapping> mappings = new ArrayList<>();

        /**
         * Fields to remove from the request body.
         * JSONPath expressions supported.
         */
        private List<String> removeFields = new ArrayList<>();

        /**
         * Fields to add to the request body.
         * Key: field path, Value: static value or expression.
         */
        private Map<String, String> addFields = new HashMap<>();
    }

    @Data
    public static class FieldMapping {
        /**
         * Source field path (JSONPath expression).
         * E.g., "$.user.name" or "data.items[*].id"
         */
        private String sourcePath;

        /**
         * Target field path.
         */
        private String targetPath;

        /**
         * Transform type: COPY, RENAME, REMOVE, DEFAULT.
         */
        private String transform = "COPY";

        /**
         * Default value when source field doesn't exist.
         */
        private String defaultValue;

        /**
         * Value transformation expression (optional).
         * Supports simple expressions like "${value.toUpperCase()}"
         */
        private String valueTransform;
    }

    @Data
    public static class DataMaskingConfig {
        private boolean enabled = false;

        /**
         * Masking rules for sensitive fields.
         */
        private List<MaskingRule> rules = new ArrayList<>();
    }

    @Data
    public static class MaskingRule {
        /**
         * Field path to mask (JSONPath).
         */
        private String fieldPath;

        /**
         * Mask type: FULL, PARTIAL, CUSTOM.
         * FULL: Replace entire value with mask character.
         * PARTIAL: Keep some characters visible.
         * CUSTOM: Use regex pattern to mask.
         */
        private String maskType = "FULL";

        /**
         * Regex pattern to identify sensitive data.
         * E.g., email, phone, idCard patterns.
         */
        private String pattern;

        /**
         * Replacement string.
         * E.g., "***" or "****@****.com"
         */
        private String replacement = "***";

        /**
         * For PARTIAL mask: number of characters to keep visible.
         */
        private int keepLength = 0;

        /**
         * For PARTIAL mask: which end to keep.
         * START: keep first N characters.
         * END: keep last N characters.
         */
        private String keepPosition = "START";
    }
}