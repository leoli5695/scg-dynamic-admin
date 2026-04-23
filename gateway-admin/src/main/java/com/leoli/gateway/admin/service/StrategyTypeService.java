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

    // REQUEST_TRANSFORM subSchemas (multi-section configuration)
    private static final Map<String, Object> REQUEST_TRANSFORM_SUB_SCHEMAS = new HashMap<>();

    // RESPONSE_TRANSFORM subSchemas (multi-section configuration)
    private static final Map<String, Object> RESPONSE_TRANSFORM_SUB_SCHEMAS = new HashMap<>();

    // MOCK_RESPONSE subSchemas (multi-section configuration)
    private static final Map<String, Object> MOCK_RESPONSE_SUB_SCHEMAS = new HashMap<>();

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

    // REQUEST_TRANSFORM subSchemas initialization
    static {
        // Protocol Transform Section
        Map<String, Object> protocolTransformSchema = new HashMap<>();
        protocolTransformSchema.put("sectionLabel", "协议转换配置");
        protocolTransformSchema.put("fields", List.of(
                createSwitchField("enabled", "启用协议转换", false),
                createSelectFieldWithLabels("sourceFormat", "源格式", "JSON",
                        List.of(Map.of("value", "JSON", "label", "JSON"),
                                Map.of("value", "XML", "label", "XML"),
                                Map.of("value", "FORM", "label", "表单(FORM)"))),
                createSelectFieldWithLabels("targetFormat", "目标格式", "JSON",
                        List.of(Map.of("value", "JSON", "label", "JSON"),
                                Map.of("value", "XML", "label", "XML"))),
                createSwitchField("preserveOriginalContentType", "保留原Content-Type", false),
                createField("customContentType", "text", "自定义Content-Type", null)
        ));
        REQUEST_TRANSFORM_SUB_SCHEMAS.put("protocolTransform", protocolTransformSchema);

        // Field Mapping Section (multiRule pattern)
        Map<String, Object> fieldMappingSchema = new HashMap<>();
        fieldMappingSchema.put("sectionLabel", "字段映射配置");
        fieldMappingSchema.put("multiRule", true);
        fieldMappingSchema.put("ruleLabel", "映射规则");
        fieldMappingSchema.put("ruleFields", List.of(
                createFieldWithPlaceholder("sourcePath", "text", "源字段路径", null, "$.user.name"),
                createFieldWithPlaceholder("targetPath", "text", "目标字段路径", null, "$.userData.fullName"),
                createSelectFieldWithLabels("transform", "转换类型", "COPY",
                        List.of(Map.of("value", "COPY", "label", "复制"),
                                Map.of("value", "RENAME", "label", "重命名"),
                                Map.of("value", "REMOVE", "label", "删除"),
                                Map.of("value", "DEFAULT", "label", "默认值"))),
                createFieldWithPlaceholder("defaultValue", "text", "默认值", null, "当源字段不存在时使用"),
                createFieldWithPlaceholder("valueTransform", "text", "值转换表达式", null, "${value.toUpperCase()}")
        ));
        REQUEST_TRANSFORM_SUB_SCHEMAS.put("fieldMapping", fieldMappingSchema);

        // Data Masking Section (multiRule pattern)
        Map<String, Object> dataMaskingSchema = new HashMap<>();
        dataMaskingSchema.put("sectionLabel", "数据脱敏配置");
        dataMaskingSchema.put("multiRule", true);
        dataMaskingSchema.put("ruleLabel", "脱敏规则");
        dataMaskingSchema.put("ruleFields", List.of(
                createFieldWithPlaceholder("fieldPath", "text", "字段路径", null, "$.password"),
                createSelectFieldWithLabels("maskType", "脱敏类型", "FULL",
                        List.of(Map.of("value", "FULL", "label", "完全脱敏"),
                                Map.of("value", "PARTIAL", "label", "部分脱敏"),
                                Map.of("value", "CUSTOM", "label", "自定义正则"))),
                createFieldWithPlaceholder("pattern", "text", "正则表达式", null, "CUSTOM类型时使用"),
                createField("replacement", "text", "替换字符串", "***"),
                createNumberField("keepLength", "保留字符数", 0, 0, 100, null),
                createSelectFieldWithLabels("keepPosition", "保留位置", "START",
                        List.of(Map.of("value", "START", "label", "开头"),
                                Map.of("value", "END", "label", "结尾")))
        ));
        REQUEST_TRANSFORM_SUB_SCHEMAS.put("dataMasking", dataMaskingSchema);
    }

    // RESPONSE_TRANSFORM subSchemas initialization
    static {
        // Protocol Transform Section (for response)
        Map<String, Object> protocolTransformSchema = new HashMap<>();
        protocolTransformSchema.put("sectionLabel", "协议转换配置");
        protocolTransformSchema.put("fields", List.of(
                createSwitchField("enabled", "启用协议转换", false),
                createSelectFieldWithLabels("sourceFormat", "源格式", "AUTO",
                        List.of(Map.of("value", "AUTO", "label", "自动检测"),
                                Map.of("value", "JSON", "label", "JSON"),
                                Map.of("value", "XML", "label", "XML"))),
                createSelectFieldWithLabels("targetFormat", "目标格式", "JSON",
                        List.of(Map.of("value", "JSON", "label", "JSON"),
                                Map.of("value", "XML", "label", "XML"))),
                createField("customContentType", "text", "自定义Content-Type", null)
        ));
        RESPONSE_TRANSFORM_SUB_SCHEMAS.put("protocolTransform", protocolTransformSchema);

        // Field Mapping Section (multiRule pattern)
        Map<String, Object> fieldMappingSchema = new HashMap<>();
        fieldMappingSchema.put("sectionLabel", "字段映射配置");
        fieldMappingSchema.put("multiRule", true);
        fieldMappingSchema.put("ruleLabel", "映射规则");
        fieldMappingSchema.put("ruleFields", List.of(
                createFieldWithPlaceholder("sourcePath", "text", "源字段路径", null, "$.data.result"),
                createFieldWithPlaceholder("targetPath", "text", "目标字段路径", null, "$.result"),
                createSelectFieldWithLabels("transform", "转换类型", "COPY",
                        List.of(Map.of("value", "COPY", "label", "复制"),
                                Map.of("value", "RENAME", "label", "重命名"),
                                Map.of("value", "REMOVE", "label", "删除"),
                                Map.of("value", "DEFAULT", "label", "默认值"))),
                createFieldWithPlaceholder("defaultValue", "text", "默认值", null, "当源字段不存在时使用"),
                createFieldWithPlaceholder("valueTransform", "text", "值转换表达式", null, "${value.toUpperCase()}")
        ));
        RESPONSE_TRANSFORM_SUB_SCHEMAS.put("fieldMapping", fieldMappingSchema);

        // Data Masking Section (multiRule pattern)
        Map<String, Object> dataMaskingSchema = new HashMap<>();
        dataMaskingSchema.put("sectionLabel", "数据脱敏配置");
        dataMaskingSchema.put("multiRule", true);
        dataMaskingSchema.put("ruleLabel", "脱敏规则");
        dataMaskingSchema.put("ruleFields", List.of(
                createFieldWithPlaceholder("fieldPath", "text", "字段路径", null, "$.internalId"),
                createSelectFieldWithLabels("maskType", "脱敏类型", "FULL",
                        List.of(Map.of("value", "FULL", "label", "完全脱敏"),
                                Map.of("value", "PARTIAL", "label", "部分脱敏"),
                                Map.of("value", "CUSTOM", "label", "自定义正则"))),
                createFieldWithPlaceholder("pattern", "text", "正则表达式", null, "CUSTOM类型时使用"),
                createField("replacement", "text", "替换字符串", "***"),
                createNumberField("keepLength", "保留字符数", 0, 0, 100, null),
                createSelectFieldWithLabels("keepPosition", "保留位置", "START",
                        List.of(Map.of("value", "START", "label", "开头"),
                                Map.of("value", "END", "label", "结尾")))
        ));
        RESPONSE_TRANSFORM_SUB_SCHEMAS.put("dataMasking", dataMaskingSchema);
    }

    // MOCK_RESPONSE subSchemas initialization
    static {
        // Static Mock Section
        Map<String, Object> staticMockSchema = new HashMap<>();
        staticMockSchema.put("sectionLabel", "静态Mock配置");
        staticMockSchema.put("fields", List.of(
                createNumberField("statusCode", "HTTP状态码", 200, 100, 599, null),
                createSelectFieldWithLabels("contentType", "Content-Type", "application/json",
                        List.of(Map.of("value", "application/json", "label", "JSON"),
                                Map.of("value", "application/xml", "label", "XML"),
                                Map.of("value", "text/plain", "label", "纯文本"),
                                Map.of("value", "text/html", "label", "HTML"))),
                createField("body", "textarea", "响应Body", null),
                createField("bodyFile", "text", "Body文件路径", null)
        ));
        MOCK_RESPONSE_SUB_SCHEMAS.put("staticMock", staticMockSchema);

        // Dynamic Mock Section
        Map<String, Object> dynamicMockSchema = new HashMap<>();
        dynamicMockSchema.put("sectionLabel", "动态Mock配置");
        dynamicMockSchema.put("multiRule", true);
        dynamicMockSchema.put("ruleLabel", "条件规则");
        dynamicMockSchema.put("ruleFields", List.of(
                createSelectFieldWithLabels("matchType", "匹配类型", "PATH",
                        List.of(Map.of("value", "PATH", "label", "路径匹配"),
                                Map.of("value", "HEADER", "label", "Header匹配"),
                                Map.of("value", "QUERY", "label", "Query参数匹配"),
                                Map.of("value", "BODY", "label", "Body匹配"))),
                createFieldWithPlaceholder("pathPattern", "text", "路径模式", null, "/api/users/{id}"),
                createNumberField("statusCode", "状态码", 200, 100, 599, null),
                createField("body", "textarea", "响应Body", null)
        ));
        MOCK_RESPONSE_SUB_SCHEMAS.put("dynamicMock", dynamicMockSchema);

        // Template Mock Section
        Map<String, Object> templateMockSchema = new HashMap<>();
        templateMockSchema.put("sectionLabel", "模板Mock配置");
        templateMockSchema.put("fields", List.of(
                createSelectFieldWithLabels("templateEngine", "模板引擎", "HANDLEBARS",
                        List.of(Map.of("value", "HANDLEBARS", "label", "Handlebars"),
                                Map.of("value", "JSON_TEMPLATE", "label", "JSON模板"),
                                Map.of("value", "MUSTACHE", "label", "Mustache"))),
                createField("template", "textarea", "模板内容", null),
                createField("templateFile", "text", "模板文件路径", null)
        ));
        MOCK_RESPONSE_SUB_SCHEMAS.put("templateMock", templateMockSchema);

        // Delay Simulation Section
        Map<String, Object> delaySchema = new HashMap<>();
        delaySchema.put("sectionLabel", "延迟模拟配置");
        delaySchema.put("fields", List.of(
                createSwitchField("enabled", "启用延迟", false),
                createNumberField("fixedDelayMs", "固定延迟(ms)", 0, 0, 60000, "ms"),
                createSelectFieldWithLabels("networkConditions", "网络条件", "FAST",
                        List.of(Map.of("value", "FAST", "label", "快速"),
                                Map.of("value", "4G", "label", "4G网络"),
                                Map.of("value", "3G", "label", "3G网络"),
                                Map.of("value", "SLOW_3G", "label", "慢速3G")))
        ));
        MOCK_RESPONSE_SUB_SCHEMAS.put("delay", delaySchema);

        // Error Simulation Section
        Map<String, Object> errorSimulationSchema = new HashMap<>();
        errorSimulationSchema.put("sectionLabel", "错误模拟配置");
        errorSimulationSchema.put("fields", List.of(
                createSwitchField("enabled", "启用错误模拟", false),
                createNumberField("errorRate", "错误率(%)", 0, 0, 100, "%"),
                createField("errorStatusCodes", "text", "错误状态码", "500,503,504")
        ));
        MOCK_RESPONSE_SUB_SCHEMAS.put("errorSimulation", errorSimulationSchema);
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

    private static Map<String, Object> createFieldWithPlaceholder(String name, String type, String label, Object defaultValue, String placeholder) {
        Map<String, Object> field = createField(name, type, label, defaultValue);
        if (placeholder != null) {
            field.put("placeholder", placeholder);
        }
        return field;
    }

    private static Map<String, Object> createSelectFieldWithLabels(String name, String label, Object defaultValue, List<Map<String, String>> options) {
        Map<String, Object> field = createField(name, "select", label, defaultValue);
        field.put("options", options);
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

        // For REQUEST_TRANSFORM type, add subSchemas
        if ("REQUEST_TRANSFORM".equals(typeCode) && Boolean.TRUE.equals(schema.get("hasSubSchemas"))) {
            schema.put("subSchemas", REQUEST_TRANSFORM_SUB_SCHEMAS);
            schema.remove("hasSubSchemas");
        }

        // For RESPONSE_TRANSFORM type, add subSchemas
        if ("RESPONSE_TRANSFORM".equals(typeCode) && Boolean.TRUE.equals(schema.get("hasSubSchemas"))) {
            schema.put("subSchemas", RESPONSE_TRANSFORM_SUB_SCHEMAS);
            schema.remove("hasSubSchemas");
        }

        // For MOCK_RESPONSE type, add subSchemas
        if ("MOCK_RESPONSE".equals(typeCode) && Boolean.TRUE.equals(schema.get("hasSubSchemas"))) {
            schema.put("subSchemas", MOCK_RESPONSE_SUB_SCHEMAS);
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