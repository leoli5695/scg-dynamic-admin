package com.leoli.gateway.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request Validation Configuration.
 * Supports JSON Schema validation, field constraints, and custom validators.
 *
 * @author leoli
 */
@Data
public class RequestValidationConfig {

    private boolean enabled = false;

    // ============== Validation Mode ==============
    /**
     * Validation mode: SCHEMA, CUSTOM, HYBRID.
     * SCHEMA: Use JSON Schema only.
     * CUSTOM: Use custom field validators only.
     * HYBRID: Use both Schema and custom validators.
     */
    private String validationMode = "HYBRID";

    // ============== Schema Validation ==============
    private SchemaValidationConfig schemaValidation = new SchemaValidationConfig();

    // ============== Field Validation ==============
    private FieldValidationConfig fieldValidation = new FieldValidationConfig();

    // ============== Custom Validators ==============
    private List<CustomValidatorConfig> customValidators = new ArrayList<>();

    // ============== Execution Conditions ==============
    /**
     * Only validate for these HTTP methods.
     * Default: POST, PUT, PATCH.
     */
    private List<String> validateOnMethods = List.of("POST", "PUT", "PATCH");

    /**
     * Only validate when Content-Type matches.
     */
    private List<String> validateOnContentTypes = List.of("application/json");

    /**
     * Stop on first validation error.
     */
    private boolean stopOnFirstError = true;

    // ============== Error Response Configuration ==============
    private ErrorResponseConfig errorResponse = new ErrorResponseConfig();

    // ============== Nested Config Classes ==============

    @Data
    public static class SchemaValidationConfig {
        private boolean enabled = true;

        /**
         * Schema source: INLINE, REF, URL.
         * INLINE: Schema is provided in inlineSchema field.
         * REF: Schema is referenced by schemaRef (stored in gateway-admin).
         * URL: Schema is fetched from a remote URL.
         */
        private String schemaSource = "INLINE";

        /**
         * Reference to a stored schema.
         */
        private String schemaRef;

        /**
         * Inline JSON Schema string.
         */
        private String inlineSchema;

        /**
         * Remote schema URL.
         */
        private String schemaUrl;

        /**
         * Cache TTL for remote schemas (in seconds).
         */
        private int schemaCacheTtl = 300;
    }

    @Data
    public static class FieldValidationConfig {
        private boolean enabled = true;

        /**
         * Required field paths.
         * E.g., ["user.name", "user.email", "items[*].productId"]
         */
        private List<String> requiredFields = new ArrayList<>();

        /**
         * Type constraints for fields.
         */
        private List<TypeConstraint> typeConstraints = new ArrayList<>();

        /**
         * Enum constraints for fields.
         */
        private List<EnumConstraint> enumConstraints = new ArrayList<>();
    }

    @Data
    public static class TypeConstraint {
        /**
         * Field path (JSONPath).
         */
        private String fieldPath;

        /**
         * Expected type: string, number, integer, boolean, array, object.
         */
        private String expectedType;

        /**
         * Minimum value (for numbers).
         */
        private Number min;

        /**
         * Maximum value (for numbers).
         */
        private Number max;

        /**
         * Minimum string length.
         */
        private Integer minLength;

        /**
         * Maximum string length.
         */
        private Integer maxLength;

        /**
         * Regex pattern for validation.
         */
        private String pattern;

        /**
         * Custom error message.
         */
        private String errorMessage;
    }

    @Data
    public static class EnumConstraint {
        private String fieldPath;
        private List<Object> allowedValues = new ArrayList<>();
        private String errorMessage;
    }

    @Data
    public static class CustomValidatorConfig {
        /**
         * Validator name.
         */
        private String name;

        /**
         * Field path to validate.
         */
        private String fieldPath;

        /**
         * Validator type: SCRIPT, BEAN, HTTP.
         * SCRIPT: Execute a script expression.
         * BEAN: Call a Spring Bean method.
         * HTTP: Call an external validation service.
         */
        private String validatorType = "SCRIPT";

        /**
         * Script expression (Groovy/SpEL).
         * E.g., "value != null && value.length() > 0"
         */
        private String script;

        /**
         * Spring Bean name for validation.
         */
        private String beanName;

        /**
         * HTTP endpoint for validation.
         */
        private String httpEndpoint;

        /**
         * Custom error message.
         */
        private String errorMessage;
    }

    @Data
    public static class ErrorResponseConfig {
        /**
         * Error response format: STANDARD, DETAILED.
         * STANDARD: Simple error message.
         * DETAILED: Include all validation errors with field paths.
         */
        private String format = "DETAILED";

        /**
         * Include schema validation errors in response.
         */
        private boolean includeSchemaErrors = true;

        /**
         * Custom error message template.
         */
        private String customMessage;

        /**
         * HTTP status code for validation errors.
         */
        private int statusCode = 400;
    }
}