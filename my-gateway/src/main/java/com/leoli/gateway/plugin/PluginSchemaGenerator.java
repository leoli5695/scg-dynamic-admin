package com.leoli.gateway.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plugin Schema Generator.
 * Generates JSON Schema from Config class fields using reflection.
 *
 * Output schema format (compatible with frontend dynamic form):
 * {
 *   "fields": [
 *     {"name": "fieldName", "type": "number", "label": "Field Label", "default": 100},
 *     ...
 *   ]
 * }
 *
 * @author leoli
 */
@Component
@Slf4j
public class PluginSchemaGenerator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Generate JSON Schema from a Config class.
     *
     * @param configClass the Config inner class
     * @return JSON string representing the schema
     */
    public String generateSchema(Class<?> configClass) {
        if (configClass == null) {
            return "{\"fields\": []}";
        }

        try {
            Map<String, Object> schema = new java.util.LinkedHashMap<>();
            List<Map<String, Object>> fields = new ArrayList<>();

            // Get all declared fields (excluding static and synthetic)
            for (Field field : configClass.getDeclaredFields()) {
                // Skip static and synthetic fields
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }

                Map<String, Object> fieldSchema = createFieldSchema(field);
                fields.add(fieldSchema);
            }

            schema.put("fields", fields);
            schema.put("hasSubSchemas", false);

            return objectMapper.writeValueAsString(schema);

        } catch (Exception e) {
            log.error("Failed to generate schema for config class: {}", configClass.getName(), e);
            return "{\"fields\": [], \"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Generate schema and return as Map (for easy manipulation).
     */
    public Map<String, Object> generateSchemaMap(Class<?> configClass) {
        if (configClass == null) {
            return Map.of("fields", List.of());
        }

        try {
            Map<String, Object> schema = new java.util.LinkedHashMap<>();
            List<Map<String, Object>> fields = new ArrayList<>();

            for (Field field : configClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }

                Map<String, Object> fieldSchema = createFieldSchema(field);
                fields.add(fieldSchema);
            }

            schema.put("fields", fields);
            schema.put("hasSubSchemas", false);

            return schema;

        } catch (Exception e) {
            log.error("Failed to generate schema map for config class: {}", configClass.getName(), e);
            return Map.of("fields", List.of(), "error", e.getMessage());
        }
    }

    /**
     * Create field schema entry from a Field.
     */
    private Map<String, Object> createFieldSchema(Field field) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();

        String fieldName = field.getName();
        Class<?> fieldType = field.getType();

        // Basic field info
        schema.put("name", fieldName);
        schema.put("label", generateLabel(fieldName));
        schema.put("type", mapFieldType(fieldType));

        // Default value (try to get from field)
        Object defaultValue = getDefaultValue(field);
        if (defaultValue != null) {
            schema.put("default", defaultValue);
        }

        // Type-specific constraints
        addTypeConstraints(schema, fieldType);

        // Description (could be enhanced with annotations)
        schema.put("description", "Configuration for " + fieldName);

        return schema;
    }

    /**
     * Map Java type to JSON Schema type.
     */
    private String mapFieldType(Class<?> type) {
        if (type == int.class || type == Integer.class ||
            type == long.class || type == Long.class ||
            type == double.class || type == Double.class ||
            type == float.class || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "switch";
        }
        if (type == String.class) {
            return "text";
        }
        if (type.isEnum()) {
            return "select";
        }
        if (type == List.class || type.isArray()) {
            return "array";
        }
        if (type == Map.class) {
            return "textarea"; // JSON config
        }
        // Complex object
        return "textarea";
    }

    /**
     * Generate human-readable label from field name.
     */
    private String generateLabel(String fieldName) {
        // Convert camelCase to Title Case
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (i == 0) {
                label.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                label.append(' ');
                label.append(c);
            } else {
                label.append(c);
            }
        }
        return label.toString();
    }

    /**
     * Get default value from field.
     */
    private Object getDefaultValue(Field field) {
        try {
            // Try to access the field
            field.setAccessible(true);

            // Create a dummy instance to get default values
            // This works for classes with default constructor
            Class<?> declaringClass = field.getDeclaringClass();
            try {
                Object instance = declaringClass.getDeclaredConstructor().newInstance();
                return field.get(instance);
            } catch (Exception e) {
                // Can't instantiate, return null
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Add type-specific constraints to schema.
     */
    private void addTypeConstraints(Map<String, Object> schema, Class<?> type) {
        if (type == int.class || type == Integer.class) {
            schema.put("min", Integer.MIN_VALUE);
            schema.put("max", Integer.MAX_VALUE);
        }
        if (type == long.class || type == Long.class) {
            schema.put("min", Long.MIN_VALUE);
            schema.put("max", Long.MAX_VALUE);
        }
        if (type.isEnum()) {
            // Add enum options
            List<Map<String, String>> options = new ArrayList<>();
            for (Object enumConstant : type.getEnumConstants()) {
                Map<String, String> option = new java.util.LinkedHashMap<>();
                option.put("label", enumConstant.toString());
                option.put("value", enumConstant.toString());
                options.add(option);
            }
            schema.put("options", options);
        }
    }

    /**
     * Merge custom schema from META-INF/plugin-schema.json with generated schema.
     * Custom schema takes precedence.
     */
    public String mergeWithCustomSchema(String generatedSchema, String customSchema) {
        if (customSchema == null || customSchema.isEmpty()) {
            return generatedSchema;
        }

        try {
            Map<String, Object> generated = objectMapper.readValue(generatedSchema, Map.class);
            Map<String, Object> custom = objectMapper.readValue(customSchema, Map.class);

            // Custom schema overrides generated
            Map<String, Object> merged = new java.util.LinkedHashMap<>(generated);
            if (custom.containsKey("fields")) {
                merged.put("fields", custom.get("fields"));
            }
            if (custom.containsKey("description")) {
                merged.put("description", custom.get("description"));
            }

            return objectMapper.writeValueAsString(merged);

        } catch (Exception e) {
            log.warn("Failed to merge schemas, using custom schema: {}", e.getMessage());
            return customSchema;
        }
    }
}