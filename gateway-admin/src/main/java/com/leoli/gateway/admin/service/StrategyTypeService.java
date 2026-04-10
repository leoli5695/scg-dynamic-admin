package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.StrategyTypeEntity;
import com.leoli.gateway.admin.repository.StrategyTypeRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy type service for managing strategy type metadata.
 *
 * @author leoli
 */
@Slf4j
@Service
public class StrategyTypeService {

    @Autowired
    private StrategyTypeRepository strategyTypeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // AUTH subSchemas (loaded dynamically when needed)
    private static final Map<String, Object> AUTH_SUB_SCHEMAS = new HashMap<>();

    static {
        // JWT subSchema
        Map<String, Object> jwtSchema = new HashMap<>();
        jwtSchema.put("fields", List.of(
                createField("secretKey", "password", "密钥", null),
                createSelectField("jwtAlgorithm", "算法", "HS256", List.of("HS256", "HS512", "RS256")),
                createField("jwtIssuer", "text", "Issuer", null),
                createField("jwtAudience", "text", "Audience", null),
                createNumberField("jwtClockSkewSeconds", "时钟偏移", 60, null, null, "s")
        ));
        AUTH_SUB_SCHEMAS.put("JWT", jwtSchema);

        // API_KEY subSchema
        Map<String, Object> apiKeySchema = new HashMap<>();
        apiKeySchema.put("fields", List.of(
                createField("apiKey", "password", "API Key", null),
                createField("apiKeyHeader", "text", "Header名", "X-API-Key"),
                createField("apiKeyPrefix", "text", "前缀", null),
                createField("apiKeyQueryParam", "text", "Query参数", null)
        ));
        AUTH_SUB_SCHEMAS.put("API_KEY", apiKeySchema);

        // OAUTH2 subSchema
        Map<String, Object> oauth2Schema = new HashMap<>();
        oauth2Schema.put("fields", List.of(
                createField("clientId", "text", "Client ID", null),
                createField("clientSecret", "password", "Client Secret", null),
                createField("tokenEndpoint", "text", "Token端点", null),
                createField("requiredScopes", "text", "Required Scopes", null)
        ));
        AUTH_SUB_SCHEMAS.put("OAUTH2", oauth2Schema);

        // BASIC subSchema
        Map<String, Object> basicSchema = new HashMap<>();
        basicSchema.put("fields", List.of(
                createField("basicUsername", "text", "用户名", null),
                createField("basicPassword", "password", "密码", null),
                createField("realm", "text", "Realm", null),
                createSelectField("passwordHashAlgorithm", "密码哈希", "PLAIN", List.of("PLAIN", "MD5", "SHA256", "BCRYPT")),
                createField("basicUsersJson", "textarea", "用户JSON", null)
        ));
        AUTH_SUB_SCHEMAS.put("BASIC", basicSchema);

        // HMAC subSchema
        Map<String, Object> hmacSchema = new HashMap<>();
        hmacSchema.put("fields", List.of(
                createField("accessKey", "text", "Access Key", null),
                createField("secretKey", "password", "Secret Key", null),
                createSelectField("signatureAlgorithm", "签名算法", "HMAC-SHA256", List.of("HMAC-SHA256", "HMAC-SHA512", "HMAC-SHA1")),
                createNumberField("clockSkewMinutes", "时钟偏移", 5, null, null, "min"),
                createSwitchField("requireNonce", "要求Nonce", true),
                createSwitchField("validateContentMd5", "验证MD5", false),
                createField("accessKeySecretsJson", "textarea", "AK/SK JSON", null)
        ));
        AUTH_SUB_SCHEMAS.put("HMAC", hmacSchema);
    }

    private static Map<String, Object> createField(String name, String type, String label, Object defaultValue) {
        Map<String, Object> field = new HashMap<>();
        field.put("name", name);
        field.put("type", type);
        field.put("label", label);
        if (defaultValue != null) {
            field.put("default", defaultValue);
        }
        return field;
    }

    private static Map<String, Object> createSelectField(String name, String label, Object defaultValue, List<String> options) {
        Map<String, Object> field = createField(name, "select", label, defaultValue);
        field.put("options", options.stream().map(opt -> {
            Map<String, Object> optMap = new HashMap<>();
            optMap.put("value", opt);
            optMap.put("label", opt);
            return optMap;
        }).toList());
        return field;
    }

    private static Map<String, Object> createNumberField(String name, String label, Object defaultValue, Integer min, Integer max, String unit) {
        Map<String, Object> field = createField(name, "number", label, defaultValue);
        if (min != null) field.put("min", min);
        if (max != null) field.put("max", max);
        if (unit != null) field.put("unit", unit);
        return field;
    }

    private static Map<String, Object> createSwitchField(String name, String label, Object defaultValue) {
        Map<String, Object> field = createField(name, "switch", label, defaultValue);
        return field;
    }

    @PostConstruct
    public void init() {
        long count = strategyTypeRepository.countByEnabledTrue();
        log.info("StrategyTypeService initialized with {} enabled strategy types", count);
    }

    /**
     * Get all strategy types.
     */
    public List<StrategyTypeEntity> getAllStrategyTypes() {
        return strategyTypeRepository.findAllOrderBySortOrder();
    }

    /**
     * Get all enabled strategy types.
     */
    public List<StrategyTypeEntity> getAllEnabledStrategyTypes() {
        return strategyTypeRepository.findAllEnabledOrderBySortOrder();
    }

    /**
     * Get strategy type by code.
     */
    public StrategyTypeEntity getStrategyType(String typeCode) {
        return strategyTypeRepository.findById(typeCode).orElse(null);
    }

    /**
     * Get strategy types by category.
     */
    public List<StrategyTypeEntity> getStrategyTypesByCategory(String category) {
        return strategyTypeRepository.findByCategoryAndEnabledTrueOrderBySortOrder(category);
    }

    /**
     * Get all categories.
     */
    public List<String> getAllCategories() {
        return strategyTypeRepository.findAllCategories();
    }

    /**
     * Get full config schema for a strategy type (includes subSchemas for AUTH).
     */
    public Map<String, Object> getFullConfigSchema(String typeCode) {
        StrategyTypeEntity entity = getStrategyType(typeCode);
        if (entity == null) {
            return null;
        }

        Map<String, Object> schema = parseConfigSchema(entity.getConfigSchema());
        if (schema == null) {
            return null;
        }

        // For AUTH type, add subSchemas
        if ("AUTH".equals(typeCode) && Boolean.TRUE.equals(schema.get("hasSubSchemas"))) {
            schema.put("subSchemas", AUTH_SUB_SCHEMAS);
            schema.remove("hasSubSchemas");
        }

        return schema;
    }

    /**
     * Convert entity to DTO with parsed configSchema.
     */
    public Map<String, Object> toDto(StrategyTypeEntity entity) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("typeCode", entity.getTypeCode());
        dto.put("typeName", entity.getTypeName());
        dto.put("typeNameEn", entity.getTypeNameEn());
        dto.put("icon", entity.getIcon());
        dto.put("color", entity.getColor());
        dto.put("category", entity.getCategory());
        dto.put("description", entity.getDescription());
        dto.put("filterClass", entity.getFilterClass());
        dto.put("enabled", entity.getEnabled());
        dto.put("sortOrder", entity.getSortOrder());

        // Parse configSchema to include subSchemas for AUTH
        Map<String, Object> schema = getFullConfigSchema(entity.getTypeCode());
        if (schema != null) {
            dto.put("configSchema", schema);
        }

        return dto;
    }

    /**
     * Parse config schema JSON string to Map.
     */
    private Map<String, Object> parseConfigSchema(String configSchema) {
        if (configSchema == null || configSchema.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(configSchema, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse config schema: {}", e.getMessage());
            return null;
        }
    }
}