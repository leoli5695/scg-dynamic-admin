package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.StrategyTypeEntity;
import com.leoli.gateway.admin.repository.StrategyTypeRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    // Package-private (not private) so unit tests can assert English-label enrichment directly.
    static final Map<String, Object> AUTH_SUB_SCHEMAS = new HashMap<>();

    // REQUEST_TRANSFORM subSchemas (multi-section configuration)
    static final Map<String, Object> REQUEST_TRANSFORM_SUB_SCHEMAS = new HashMap<>();

    // RESPONSE_TRANSFORM subSchemas (multi-section configuration)
    static final Map<String, Object> RESPONSE_TRANSFORM_SUB_SCHEMAS = new HashMap<>();

    // MOCK_RESPONSE subSchemas (multi-section configuration)
    static final Map<String, Object> MOCK_RESPONSE_SUB_SCHEMAS = new HashMap<>();

    // TOKEN_RATE_LIMITER subSchemas (AI Token quota configuration)
    static final Map<String, Object> TOKEN_RATE_LIMITER_SUB_SCHEMAS = new HashMap<>();

    // Chinese label -> English label dictionary used to enrich schemas with labelEn / sectionLabelEn.
    // Additive only: existing label / sectionLabel values are never modified.
    // Package-private so unit tests can assert seed-label coverage.
    static final Map<String, String> LABEL_EN_DICTIONARY = buildLabelEnDictionary();

    /**
     * Whitespace-stripped view of {@link #LABEL_EN_DICTIONARY}. Rows in strategy_types drift from
     * the seed SQL over time ("租户ID" becomes "租户 ID"), so an exact miss falls back to this
     * instead of leaving the field untranslated.
     */
    private static final Map<String, String> LABEL_EN_NORMALIZED = buildNormalizedDictionary();

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

    // TOKEN_RATE_LIMITER subSchemas initialization (AI Token Quota)
    static {
        // Tenant Quotas Section (multiRule pattern for multiple tenants)
        Map<String, Object> tenantQuotasSchema = new HashMap<>();
        tenantQuotasSchema.put("sectionLabel", "租户配额配置");
        tenantQuotasSchema.put("sectionDescription", "为不同租户配置Token配额，支持月度和日度双重限制");
        tenantQuotasSchema.put("multiRule", true);
        tenantQuotasSchema.put("ruleLabel", "租户");
        tenantQuotasSchema.put("ruleKeyField", "tenantId");
        tenantQuotasSchema.put("ruleFields", List.of(
                createFieldWithPlaceholder("tenantId", "text", "租户ID", null, "如: tenant-001"),
                createNumberField("monthlyQuota", "月配额", 1000000, 0, null, "tokens"),
                createNumberField("dailyQuota", "日配额", 50000, 0, null, "tokens"),
                createNumberField("burstQuota", "突发配额", 0, 0, null, "tokens"),
                createNumberField("windowTokenPerMinute", "窗口Token/分覆盖", null, 0, null, "tokens"),
                createNumberField("windowTokenPerHour", "窗口Token/时覆盖", null, 0, null, "tokens"),
                createSelectFieldWithLabels("quotaPeriod", "配额周期", "BOTH",
                        List.of(Map.of("value", "MONTHLY", "label", "仅月度"),
                                Map.of("value", "DAILY", "label", "仅日度"),
                                Map.of("value", "BOTH", "label", "月度+日度")))
        ));
        TOKEN_RATE_LIMITER_SUB_SCHEMAS.put("tenantQuotas", tenantQuotasSchema);

        // Default Quotas Section
        Map<String, Object> defaultQuotasSchema = new HashMap<>();
        defaultQuotasSchema.put("sectionLabel", "默认配额设置");
        defaultQuotasSchema.put("sectionDescription", "新租户的默认配额值");
        defaultQuotasSchema.put("fields", List.of(
                createNumberField("defaultMonthlyQuota", "默认月配额", 1000000, 0, null, "tokens"),
                createNumberField("defaultDailyQuota", "默认日配额", 50000, 0, null, "tokens")
        ));
        TOKEN_RATE_LIMITER_SUB_SCHEMAS.put("defaultQuotas", defaultQuotasSchema);

        // Response Format Section
        Map<String, Object> responseFormatSchema = new HashMap<>();
        responseFormatSchema.put("sectionLabel", "响应格式配置");
        responseFormatSchema.put("sectionDescription", "AI服务响应的usage字段解析格式");
        responseFormatSchema.put("fields", List.of(
                createSelectFieldWithLabels("responseFormat", "响应格式", "OPENAI",
                        List.of(Map.of("value", "OPENAI", "label", "OpenAI标准"),
                                Map.of("value", "ANTHROPIC", "label", "Anthropic格式"),
                                Map.of("value", "CUSTOM", "label", "自定义格式"))),
                createSwitchField("sseIncrementalTracking", "SSE增量追踪", true),
                createNumberField("sseTimeoutMs", "SSE超时时间", 60000, 1000, 300000, "ms")
        ));
        TOKEN_RATE_LIMITER_SUB_SCHEMAS.put("responseFormat", responseFormatSchema);

        // SSE Stream Handling Section
        Map<String, Object> sseHandlingSchema = new HashMap<>();
        sseHandlingSchema.put("sectionLabel", "SSE流处理配置");
        sseHandlingSchema.put("sectionDescription", "流式响应的内存控制和中断处理策略");
        sseHandlingSchema.put("fields", List.of(
                createNumberField("sseMaxBufferSize", "最大缓冲大小", 1048576, 65536, 10485760, "bytes"),
                createNumberField("sseMaxChunks", "最大chunk数", 200, 50, 1000, null),
                createSwitchField("ssePartialBillingOnInterrupt", "中断部分计费", true),
                createNumberField("sseMinChargeOnInterrupt", "中断最小计费", 100, 0, 10000, "tokens")
        ));
        TOKEN_RATE_LIMITER_SUB_SCHEMAS.put("sseHandling", sseHandlingSchema);

        // Tenant ID Extraction Section
        Map<String, Object> tenantIdExtractionSchema = new HashMap<>();
        tenantIdExtractionSchema.put("sectionLabel", "租户识别配置");
        tenantIdExtractionSchema.put("sectionDescription", "如何从请求中提取租户ID用于配额检查。组合模式只认服务端已验证的来源（API Key元数据、JWT Claims）；Header 由客户端自带、可伪造，仅在来源显式选择 HTTP Header 时生效");
        tenantIdExtractionSchema.put("fields", List.of(
                createSelectFieldWithLabels("tenantIdSource", "租户ID来源", "combined",
                        List.of(Map.of("value", "api_key_metadata", "label", "API Key元数据"),
                                Map.of("value", "jwt_claim", "label", "JWT Claims"),
                                Map.of("value", "header", "label", "HTTP Header"),
                                Map.of("value", "combined", "label", "组合模式(API Key→JWT，不含Header)"))),
                createField("headerNames.tenantIdHeader", "text", "租户Header名(仅来源=HTTP Header时生效)", "X-Tenant-Id")
        ));
        TOKEN_RATE_LIMITER_SUB_SCHEMAS.put("tenantIdExtraction", tenantIdExtractionSchema);

        // Alert Configuration Section
        Map<String, Object> alertSchema = new HashMap<>();
        alertSchema.put("sectionLabel", "告警配置");
        alertSchema.put("sectionDescription", "配额接近耗尽时的告警设置");
        alertSchema.put("fields", List.of(
                createNumberField("alertThresholdPercent", "告警阈值", 80, 50, 100, "%"),
                createSwitchField("localCacheFallback", "本地缓存降级", true),
                createNumberField("fallbackQuotaPercent", "降级配额比例", 50, 10, 100, "%")
        ));
        TOKEN_RATE_LIMITER_SUB_SCHEMAS.put("alert", alertSchema);

        // Pre-deduct Strategy Section
        Map<String, Object> preDeductSchema = new HashMap<>();
        preDeductSchema.put("sectionLabel", "预扣与退款策略");
        preDeductSchema.put("sectionDescription", "请求开始时预扣Token配额及响应后结算退款策略");
        preDeductSchema.put("fields", List.of(
                createSelectFieldWithLabels("preDeductStrategy", "预扣策略", "NONE",
                        List.of(Map.of("value", "NONE", "label", "不预扣(响应后扣减)"),
                                Map.of("value", "ESTIMATE", "label", "预估预扣"),
                                Map.of("value", "HYBRID", "label", "混合模式"))),
                createNumberField("estimateMultiplier", "预估倍数", 2, 1, 5, "x"),
                createNumberField("hybridThreshold", "混合模式阈值", 1000, 100, 10000, "tokens"),
                createSwitchField("autoRefundEnabled", "自动退款", true),
                createNumberField("refundThresholdPercent", "退款阈值", 10, 1, 50, "%"),
                createNumberField("maxRefundPerRequest", "单次最大退款", 10000, 100, 100000, "tokens"),
                createNumberField("estimateMaxBodySize", "估算Body上限", 1048576, 1024, 10485760, "bytes")
        ));
        TOKEN_RATE_LIMITER_SUB_SCHEMAS.put("preDeduct", preDeductSchema);

        // Fixed-window token rate limit section (Higress ai-token-ratelimit counterpart)
        Map<String, Object> windowLimitSchema = new HashMap<>();
        windowLimitSchema.put("sectionLabel", "窗口Token限流");
        windowLimitSchema.put("sectionDescription", "固定窗口限流:请求阶段检查窗口已用量,响应阶段按实际usage扣减(对标Higress ai-token-ratelimit);0表示不启用该窗口");
        windowLimitSchema.put("fields", List.of(
                createSwitchField("windowLimitEnabled", "启用窗口限流", false),
                createField("windowRuleName", "text", "规则名", "default"),
                createNumberField("tokenPerMinute", "租户Token/分", 0, 0, null, "tokens"),
                createNumberField("tokenPerHour", "租户Token/时", 0, 0, null, "tokens"),
                createNumberField("globalTokenPerMinute", "全局Token/分", 0, 0, null, "tokens"),
                createNumberField("rejectedCode", "拒绝状态码", 429, 400, 599, null),
                createField("rejectedMsg", "text", "拒绝提示语", "Too many requests")
        ));
        TOKEN_RATE_LIMITER_SUB_SCHEMAS.put("windowLimit", windowLimitSchema);
    }

    // Enrich all static sub-schemas with English labels (labelEn / sectionLabelEn).
    // MUST remain the LAST static block: static initializers run in textual order, so every
    // SUB_SCHEMAS map above is fully populated when this runs. The top-level maps are mutable
    // HashMaps, so enrichment happens in place; immutable option maps/lists inside (Map.of /
    // List.of from createSelectFieldWithLabels) are replaced with mutable copies by the walker.
    static {
        enrichSchemaWithEnglishLabels(AUTH_SUB_SCHEMAS);
        enrichSchemaWithEnglishLabels(REQUEST_TRANSFORM_SUB_SCHEMAS);
        enrichSchemaWithEnglishLabels(RESPONSE_TRANSFORM_SUB_SCHEMAS);
        enrichSchemaWithEnglishLabels(MOCK_RESPONSE_SUB_SCHEMAS);
        enrichSchemaWithEnglishLabels(TOKEN_RATE_LIMITER_SUB_SCHEMAS);
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

        // Add labelEn / sectionLabelEn to the DB-sourced schema. parseConfigSchema returns a
        // freshly parsed Map on every call (Jackson readValue, no shared/cached state), so
        // in-place enrichment is safe. The static SUB_SCHEMAS attached below were already
        // enriched once by the last static initializer block.
        schema = enrichSchemaWithEnglishLabels(schema);

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

        // For TOKEN_RATE_LIMITER type, add subSchemas (AI Token quota)
        if ("TOKEN_RATE_LIMITER".equals(typeCode) && Boolean.TRUE.equals(schema.get("hasSubSchemas"))) {
            schema.put("subSchemas", TOKEN_RATE_LIMITER_SUB_SCHEMAS);
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

        // Parse configSchema to include subSchemas for AUTH.
        // getFullConfigSchema already enriches the returned schema with labelEn / sectionLabelEn,
        // so this DTO exit is covered by the same enrichment.
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

    /**
     * Recursively add English labels to a config schema: for every String-valued {@code label},
     * {@code sectionLabel}, {@code sectionDescription}, {@code ruleLabel}, {@code dimensionLabel}
     * and {@code placeholder} key, add the matching {@code …En} key by looking the Chinese text
     * up in {@link #LABEL_EN_DICTIONARY}.
     * Covers nested {@code fields}, {@code ruleFields}, {@code subSchemas} and {@code options}.
     *
     * <p>Additive only: existing keys ({@code label}, {@code sectionLabel}, {@code value},
     * {@code name}, {@code default}, ...) are never modified, and an already present
     * {@code labelEn} is never overwritten. Unknown labels get no {@code labelEn} at all,
     * letting the frontend fall back to the Chinese label.
     *
     * <p>Package-private entry point for unit tests.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> enrichSchemaWithEnglishLabels(Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }
        return (Map<String, Object>) enrichLabelsNode(schema);
    }

    /**
     * Returns the enriched node: the same instance when it was mutable and enriched in place,
     * or a mutable copy when the original node was immutable (e.g. {@code Map.of} / {@code List.of}
     * used by createSelectFieldWithLabels). Callers must write the returned node back into the
     * parent container, which is what the recursion below does.
     */
    @SuppressWarnings("unchecked")
    private static Object enrichLabelsNode(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            // Recurse first; collect child replacements so we never structurally modify the
            // map while iterating its entrySet (would throw ConcurrentModificationException).
            Map<String, Object> replacements = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object value = entry.getValue();
                Object enriched = enrichLabelsNode(value);
                if (enriched != value) {
                    replacements.put(entry.getKey(), enriched);
                }
            }
            Map<String, Object> additions = new LinkedHashMap<>();
            collectEnglishLabel(map, "label", "labelEn", additions);
            collectEnglishLabel(map, "sectionLabel", "sectionLabelEn", additions);
            collectEnglishLabel(map, "sectionDescription", "sectionDescriptionEn", additions);
            collectEnglishLabel(map, "ruleLabel", "ruleLabelEn", additions);
            collectEnglishLabel(map, "dimensionLabel", "dimensionLabelEn", additions);
            collectEnglishLabel(map, "placeholder", "placeholderEn", additions);
            collectEnglishLabel(map, "unit", "unitEn", additions);
            if (replacements.isEmpty() && additions.isEmpty()) {
                return map;
            }
            try {
                map.putAll(replacements);
                map.putAll(additions);
                return map;
            } catch (UnsupportedOperationException immutable) {
                // Immutable map (Map.of / Collections.unmodifiableMap): replace with a mutable copy.
                Map<String, Object> copy = new LinkedHashMap<>(map);
                copy.putAll(replacements);
                copy.putAll(additions);
                return copy;
            }
        }
        if (node instanceof List<?> rawList) {
            List<Object> list = (List<Object>) rawList;
            List<Object> copy = null;
            for (int i = 0; i < list.size(); i++) {
                Object value = list.get(i);
                Object enriched = enrichLabelsNode(value);
                if (enriched != value) {
                    if (copy == null) {
                        // Immutable list (List.of): copy once, then write replacements into the copy.
                        copy = new ArrayList<>(list);
                    }
                    copy.set(i, enriched);
                }
            }
            return copy != null ? copy : list;
        }
        return node;
    }

    private static void collectEnglishLabel(Map<String, Object> map, String sourceKey, String targetKey,
                                            Map<String, Object> additions) {
        if (map.containsKey(targetKey)) {
            return; // never overwrite an existing English label
        }
        Object label = map.get(sourceKey);
        if (label instanceof String labelText) {
            String english = LABEL_EN_DICTIONARY.get(labelText);
            if (english == null) {
                english = LABEL_EN_NORMALIZED.get(labelText.replaceAll("\\s+", ""));
            }
            if (english != null) {
                additions.put(targetKey, english);
            }
        }
    }

    private static Map<String, String> buildNormalizedDictionary() {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : LABEL_EN_DICTIONARY.entrySet()) {
            normalized.putIfAbsent(entry.getKey().replaceAll("\\s+", ""), entry.getValue());
        }
        return Collections.unmodifiableMap(normalized);
    }

    /**
     * Chinese label -> English label dictionary.
     * The 304 Chinese entries mirror the i18n audit extract (artifacts/i18n-audit/backend-labels.txt)
     * taken from sql/seed/strategy_types.sql and the static SUB_SCHEMAS above, in audit order.
     * The trailing identity entries cover labels/options that are already English in the static
     * schemas, so every static field/option ends up with a labelEn.
     */
    private static Map<String, String> buildLabelEnDictionary() {
        Map<String, String> m = new LinkedHashMap<>();
        // --- Chinese labels (audit order) ---
        m.put("3G网络", "3G Network");
        m.put("4G网络", "4G Network");
        m.put("安全缓冲系数", "Safety Buffer Factor");
        m.put("按租户隔离", "Isolate by Tenant");
        m.put("按Header指定", "Specify by Header");
        m.put("按model隔离", "Isolate by Model");
        m.put("按model自动路由", "Auto-route by Model");
        m.put("白名单", "Whitelist");
        m.put("版本模式", "Version Mode");
        m.put("版本Header", "Version Header");
        m.put("保留位置", "Keep Position");
        m.put("保留原Content-Type", "Preserve Original Content-Type");
        m.put("保留字符数", "Characters to Keep");
        m.put("本地缓存降级", "Local Cache Fallback");
        m.put("变量Body字段", "Variable Body Field");
        m.put("表单(FORM)", "Form (FORM)");
        m.put("剥离客户端Auth", "Strip Client Auth");
        m.put("不预扣(响应后扣减)", "No Pre-deduction (Deduct After Response)");
        m.put("部分脱敏", "Partial Masking");
        m.put("采样率", "Sampling Rate");
        m.put("采样率(%)", "Sampling Rate (%)");
        m.put("出站处置", "Outbound Action");
        m.put("窗口Token/分覆盖", "Window Tokens/min Override");
        m.put("窗口Token/时覆盖", "Window Tokens/hour Override");
        m.put("窗口Token限流", "Window Token Rate Limiting");
        m.put("纯文本", "Plain Text");
        m.put("错误处理策略", "Error Handling Strategy");
        m.put("错误率(%)", "Error Rate (%)");
        m.put("错误模拟", "Error Simulation");
        m.put("错误模拟配置", "Error Simulation Configuration");
        m.put("错误状态码", "Error Status Codes");
        m.put("单次最大退款", "Max Refund per Request");
        m.put("当源字段不存在时使用", "Used when the source field is missing");
        m.put("动态", "Dynamic");
        m.put("动态配置", "Dynamic Configuration");
        m.put("动态Mock", "Dynamic Mock");
        m.put("动态Mock配置", "Dynamic Mock Configuration");
        m.put("返回错误", "Return Error");
        m.put("返回原始响应", "Return Original Response");
        m.put("防护模式", "Protection Mode");
        m.put("分钟", "Minute");
        m.put("复制", "Copy");
        m.put("覆盖原system", "Override Original System");
        m.put("告警配置", "Alert Configuration");
        m.put("告警阈值", "Alert Threshold");
        m.put("个人信息处置", "PII Action");
        m.put("个人信息类型", "PII Types");
        m.put("估算Body上限", "Max Body Size for Estimation");
        m.put("固定延迟(ms)", "Fixed Delay (ms)");
        m.put("固定Provider", "Fixed Provider");
        m.put("规则名", "Rule Name");
        m.put("过滤模式", "Filter Mode");
        m.put("黑名单", "Blacklist");
        m.put("滑动窗口", "Sliding Window Size");
        m.put("缓存方法", "Cacheable Methods");
        m.put("缓存时间", "Cache Duration");
        m.put("缓存时间(s)", "Cache Duration (s)");
        m.put("缓存TTL(秒)", "Cache TTL (s)");
        m.put("混合模式", "Hybrid Mode");
        m.put("混合模式阈值", "Hybrid Mode Threshold");
        m.put("记录请求体", "Log Request Body");
        m.put("记录请求头", "Log Request Headers");
        m.put("记录响应体", "Log Response Body");
        m.put("记录响应头", "Log Response Headers");
        m.put("检测", "Detect");
        m.put("检测正则", "Detection Regex");
        m.put("检查参数", "Check Parameters");
        m.put("检查Body", "Check Body");
        m.put("降级", "Fallback");
        m.put("降级配额比例", "Fallback Quota Percentage");
        m.put("结尾", "End");
        m.put("仅检测", "Detect Only");
        m.put("仅日度", "Daily Only");
        m.put("仅月度", "Monthly Only");
        m.put("静态", "Static");
        m.put("静态配置", "Static Configuration");
        m.put("静态Mock", "Static Mock");
        m.put("静态Mock配置", "Static Mock Configuration");
        m.put("拒绝", "Reject");
        m.put("拒绝策略", "Rejection Policy");
        m.put("拒绝流式响应", "Reject Streaming Responses");
        m.put("拒绝提示语", "Rejection Message");
        m.put("拒绝状态码", "Rejection Status Code");
        m.put("开头", "Start");
        m.put("快速", "Fast");
        m.put("快速失败", "Fail Fast");
        m.put("宽松", "Lenient");
        m.put("拦截", "Block");
        m.put("拦截提示语", "Block Message");
        m.put("连接超时", "Connect Timeout");
        m.put("连接超时(ms)", "Connect Timeout (ms)");
        m.put("路径", "Path");
        m.put("路径模式", "Path Pattern");
        m.put("路径匹配", "Path Match");
        m.put("慢调用阈值", "Slow Call Threshold");
        m.put("慢调用阈值(ms)", "Slow Call Threshold (ms)");
        m.put("慢速3G", "Slow 3G");
        m.put("密码", "Password");
        m.put("密码哈希", "Password Hash");
        m.put("密钥", "Secret Key");
        m.put("秒", "Second");
        m.put("敏感词", "Sensitive Words");
        m.put("命名模板选择Header", "Named Template Selection Header");
        m.put("模板", "Template");
        m.put("模板内容", "Template Content");
        m.put("模板配置", "Template Configuration");
        m.put("模板文件路径", "Template File Path");
        m.put("模板引擎", "Template Engine");
        m.put("模板Mock", "Template Mock");
        m.put("模板Mock配置", "Template Mock Configuration");
        m.put("模型覆盖(可选)", "Model Override (Optional)");
        m.put("默认版本", "Default Version");
        m.put("默认模型", "Default Model");
        m.put("默认配额设置", "Default Quota Settings");
        m.put("默认日配额", "Default Daily Quota");
        m.put("默认月配额", "Default Monthly Quota");
        m.put("默认值", "Default Value");
        m.put("目标格式", "Target Format");
        m.put("目标字段路径", "Target Field Path");
        m.put("排队", "Queue");
        m.put("配额周期", "Quota Period");
        m.put("匹配类型", "Match Type");
        m.put("启用", "Enabled");
        m.put("启用窗口限流", "Enable Window Rate Limiting");
        m.put("启用错误模拟", "Enable Error Simulation");
        m.put("启用协议转换", "Enable Protocol Transform");
        m.put("启用延迟", "Enable Delay");
        m.put("启用TraceId", "Enable TraceId");
        m.put("签名算法", "Signature Algorithm");
        m.put("前置(保留原system)", "Prepend (Preserve Original System)");
        m.put("前缀", "Prefix");
        m.put("嵌入模型", "Embedding Model");
        m.put("嵌入凭据Ref", "Embedding Credential Ref");
        m.put("嵌入Provider", "Embedding Provider");
        m.put("全部检查(返回最严格限制)", "Check All (Return Strictest Limit)");
        m.put("全局配额", "Global Quota");
        m.put("全局突发容量", "Global Burst Capacity");
        m.put("全局QPS", "Global QPS");
        m.put("全局Token/分", "Global Tokens/min");
        m.put("认证类型", "Auth Type");
        m.put("日配额", "Daily Quota");
        m.put("日志级别", "Log Level");
        m.put("熔断等待时间", "Circuit Breaker Wait Duration");
        m.put("熔断等待时间(ms)", "Circuit Breaker Wait Duration (ms)");
        m.put("如: tenant-001", "e.g. tenant-001");
        m.put("入站处置", "Inbound Action");
        m.put("删除", "Remove");
        m.put("审核请求", "Moderate Request");
        m.put("审核响应", "Moderate Response");
        m.put("失败率阈值", "Failure Rate Threshold");
        m.put("失败率阈值(%)", "Failure Rate Threshold (%)");
        m.put("时间单位", "Time Unit");
        m.put("时钟偏移", "Clock Skew");
        m.put("首次触发(任一超限即拒绝)", "First Trigger (Reject on Any Limit Exceeded)");
        m.put("数据脱敏", "Data Masking");
        m.put("数据脱敏配置", "Data Masking Configuration");
        m.put("算法", "Algorithm");
        m.put("替换后放行", "Replace and Allow");
        m.put("替换字符串", "Replacement String");
        m.put("添加请求Header", "Add Request Headers");
        m.put("添加响应Header", "Add Response Headers");
        m.put("跳过错误", "Skip Errors");
        m.put("跳过流式审核", "Skip Streaming Moderation");
        m.put("通义千问/百炼", "Tongyi Qianwen / Bailian");
        m.put("突发配额", "Burst Quota");
        m.put("突发容量", "Burst Capacity");
        m.put("退回内置规则", "Fall Back to Built-in Rules");
        m.put("退款阈值", "Refund Threshold");
        m.put("脱敏放行", "Mask and Allow");
        m.put("脱敏类型", "Masking Type");
        m.put("脱敏替换", "Mask and Replace");
        m.put("外部审核超时", "External Moderation Timeout");
        m.put("外部审核地址（留空仅用内置）", "External Moderation URL (Leave Empty for Built-in Only)");
        m.put("外部审核请求头", "External Moderation Headers");
        m.put("外部审核异常处置", "External Moderation Failure Action");
        m.put("完全脱敏", "Full Masking");
        m.put("完整缓冲审核（失去逐字输出）", "Full Buffer Moderation (Loses Streaming Output)");
        m.put("网络条件", "Network Conditions");
        m.put("违禁词", "Prohibited Words");
        m.put("相似度阈值", "Similarity Threshold");
        m.put("详细", "Verbose");
        m.put("响应超时", "Response Timeout");
        m.put("响应超时(ms)", "Response Timeout (ms)");
        m.put("响应等待上限", "Max Response Wait");
        m.put("响应格式", "Response Format");
        m.put("响应格式配置", "Response Format Configuration");
        m.put("响应Body", "Response Body");
        m.put("小时", "Hour");
        m.put("协议转换", "Protocol Transform");
        m.put("协议转换配置", "Protocol Transform Configuration");
        m.put("延迟", "Delay");
        m.put("延迟(ms)", "Delay (ms)");
        m.put("延迟模拟配置", "Delay Simulation Configuration");
        m.put("严格", "Strict");
        m.put("验证模式", "Validation Mode");
        m.put("验证MD5", "Validate MD5");
        m.put("要求Nonce", "Require Nonce");
        m.put("已部署Java适配器标识", "Deployed Java Adapter ID");
        m.put("用户", "User");
        m.put("用户名", "Username");
        m.put("用户配额", "User Quota");
        m.put("用户突发容量", "User Burst Capacity");
        m.put("用户ID Header", "User ID Header");
        m.put("用户ID来源", "User ID Source");
        m.put("用户JSON", "Users JSON");
        m.put("用户QPS", "User QPS");
        m.put("预估倍数", "Estimation Multiplier");
        m.put("预估预扣", "Estimated Pre-deduction");
        m.put("预扣策略", "Pre-deduction Strategy");
        m.put("预扣与退款策略", "Pre-deduction & Refund Policy");
        m.put("源格式", "Source Format");
        m.put("源字段路径", "Source Field Path");
        m.put("月度+日度", "Monthly + Daily");
        m.put("月配额", "Monthly Quota");
        m.put("月之暗面/Kimi", "Moonshot / Kimi");
        m.put("允许方法", "Allowed Methods");
        m.put("允许来源", "Allowed Origins");
        m.put("允许凭证", "Allow Credentials");
        m.put("允许Headers", "Allowed Headers");
        m.put("正常", "Normal");
        m.put("正则表达式", "Regular Expression");
        m.put("值转换表达式", "Value Transform Expression");
        m.put("指标(metrics)", "Metrics");
        m.put("智谱GLM", "Zhipu GLM");
        m.put("中断部分计费", "Partial Billing on Interrupt");
        m.put("中断最小计费", "Minimum Charge on Interrupt");
        m.put("重命名", "Rename");
        m.put("重试间隔", "Retry Interval");
        m.put("重试间隔(ms)", "Retry Interval (ms)");
        m.put("重试状态码", "Retry Status Codes");
        m.put("转发前剥离控制字段", "Strip Control Fields Before Forwarding");
        m.put("转换后验证JSON", "Validate JSON After Transform");
        m.put("转换类型", "Transform Type");
        m.put("状态码", "Status Code");
        m.put("字段路径", "Field Path");
        m.put("字段验证", "Field Validation");
        m.put("字段映射", "Field Mapping");
        m.put("字段映射配置", "Field Mapping Configuration");
        m.put("自定义", "Custom");
        m.put("自定义格式", "Custom Format");
        m.put("自定义验证器", "Custom Validator");
        m.put("自定义正则", "Custom Regex");
        m.put("自定义Content-Type", "Custom Content-Type");
        m.put("自动检测", "Auto Detect");
        m.put("自动退款", "Auto Refund");
        m.put("租户配额", "Tenant Quota");
        m.put("租户配额配置", "Tenant Quota Configuration");
        m.put("租户识别配置", "Tenant Identification Configuration");
        m.put("租户Header名(仅来源=HTTP Header时生效)", "Tenant Header Name (Only When Source = HTTP Header)");
        m.put("租户ID", "Tenant ID");
        m.put("租户ID来源", "Tenant ID Source");
        m.put("租户Token/分", "Tenant Tokens/min");
        m.put("租户Token/时", "Tenant Tokens/hour");
        m.put("阻断", "Block");
        m.put("组合模式(API Key→JWT，不含Header)", "Combined Mode (API Key → JWT, Excluding Header)");
        m.put("最大缓冲大小", "Max Buffer Size");
        m.put("最大缓存数", "Max Cache Size");
        m.put("最大记录长度", "Max Record Length");
        m.put("最大上下文Token", "Max Context Tokens");
        m.put("最大重试次数", "Max Retries");
        m.put("最大Body", "Max Body");
        m.put("最大Body(bytes)", "Max Body (bytes)");
        m.put("最大Body大小", "Max Body Size");
        m.put("最大chunk数", "Max Chunks");
        m.put("最小", "Minimal");
        m.put("最小调用次数", "Minimum Number of Calls");
        m.put("ai_log日志", "ai_log Logs");
        m.put("Anthropic格式", "Anthropic Format");
        m.put("API Key元数据", "API Key Metadata");
        m.put("Body匹配", "Body Match");
        m.put("Body文件路径", "Body File Path");
        m.put("Client ID来源", "Client ID Source");
        m.put("Client/API Key配额", "Client/API Key Quota");
        m.put("Client突发容量", "Client Burst Capacity");
        m.put("CUSTOM类型时使用", "Used when type is CUSTOM");
        m.put("Header名", "Header Name");
        m.put("Header名称", "Header Name");
        m.put("Header配额", "Header Quota");
        m.put("Header匹配", "Header Match");
        m.put("Header突发容量", "Header Burst Capacity");
        m.put("HTTP状态码", "HTTP Status Code");
        m.put("IP列表", "IP List");
        m.put("IP配额", "IP Quota");
        m.put("IP突发容量", "IP Burst Capacity");
        m.put("Java适配器参数", "Java Adapter Parameters");
        m.put("JSON模板", "JSON Template");
        m.put("Key解析器", "Key Resolver");
        m.put("Mock模式", "Mock Mode");
        m.put("OpenAI标准", "OpenAI Standard");
        m.put("PII脱敏", "PII Masking");
        m.put("Provider Header名", "Provider Header Name");
        m.put("Provider选择方式", "Provider Selection Mode");
        m.put("Query参数", "Query Parameter");
        m.put("Query参数匹配", "Query Parameter Match");
        m.put("Schema验证", "Schema Validation");
        m.put("Span标签", "Span Tags");
        m.put("SQL注入防护", "SQL Injection Protection");
        m.put("SSE超时时间", "SSE Timeout");
        m.put("SSE流处理配置", "SSE Stream Handling Configuration");
        m.put("SSE审核方式", "SSE Moderation Mode");
        m.put("SSE增量追踪", "SSE Incremental Tracking");
        m.put("System注入方式", "System Injection Mode");
        m.put("Token端点", "Token Endpoint");
        m.put("XSS防护", "XSS Protection");
        // --- Identity entries: labels/options already English in the static SUB_SCHEMAS ---
        m.put("Issuer", "Issuer");
        m.put("Audience", "Audience");
        m.put("API Key", "API Key");
        m.put("Client ID", "Client ID");
        m.put("Client Secret", "Client Secret");
        m.put("Required Scopes", "Required Scopes");
        m.put("Realm", "Realm");
        m.put("Access Key", "Access Key");
        m.put("Secret Key", "Secret Key");
        m.put("AK/SK JSON", "AK/SK JSON");
        m.put("Content-Type", "Content-Type");
        m.put("JSON", "JSON");
        m.put("XML", "XML");
        m.put("HTML", "HTML");
        m.put("Handlebars", "Handlebars");
        m.put("Mustache", "Mustache");
        m.put("JWT Claims", "JWT Claims");
        m.put("HTTP Header", "HTTP Header");
        m.put("HS256", "HS256");
        m.put("HS512", "HS512");
        m.put("RS256", "RS256");
        m.put("PLAIN", "PLAIN");
        m.put("MD5", "MD5");
        m.put("SHA256", "SHA256");
        m.put("BCRYPT", "BCRYPT");
        m.put("HMAC-SHA256", "HMAC-SHA256");
        m.put("HMAC-SHA512", "HMAC-SHA512");
        m.put("HMAC-SHA1", "HMAC-SHA1");
        // --- ruleLabel / sectionDescription / dimensionLabel / placeholder ---
        m.put("映射规则", "Mapping Rule");
        m.put("脱敏规则", "Masking Rule");
        m.put("条件规则", "Condition Rule");
        m.put("租户", "Tenant");
        m.put("限流维度", "Rate Limit Dimension");
        m.put("为不同租户配置Token配额，支持月度和日度双重限制",
                "Configure token quotas per tenant, with both monthly and daily limits");
        m.put("新租户的默认配额值", "Default quota values for new tenants");
        m.put("AI服务响应的usage字段解析格式", "Parsing format for the usage field in AI service responses");
        m.put("流式响应的内存控制和中断处理策略", "Memory control and interruption handling for streaming responses");
        m.put("如何从请求中提取租户ID用于配额检查。组合模式只认服务端已验证的来源（API Key元数据、JWT Claims）；Header 由客户端自带、可伪造，仅在来源显式选择 HTTP Header 时生效",
                "How to extract the tenant ID from the request for quota checks. Combined mode trusts only server-verified sources (API Key metadata, JWT Claims); a Header is client-supplied and forgeable, so it applies only when the source is explicitly set to HTTP Header");
        m.put("配额接近耗尽时的告警设置", "Alert settings for when the quota is nearly exhausted");
        m.put("请求开始时预扣Token配额及响应后结算退款策略",
                "Pre-deduct token quota at request start and settle/refund after the response");
        m.put("固定窗口限流:请求阶段检查窗口已用量,响应阶段按实际usage扣减(对标Higress ai-token-ratelimit);0表示不启用该窗口",
                "Fixed-window rate limiting: check window usage at request time, deduct actual usage at response time (aligned with Higress ai-token-ratelimit); 0 disables the window");
        m.put("请求未带model时使用", "Used when the request carries no model");
        m.put("支持 {{变量}} 占位符", "Supports {{variable}} placeholders");
        m.put("留空则用请求model", "Leave empty to use the request model");
        m.put("provider_credentials 的 credentialRef", "credentialRef from provider_credentials");
        m.put("留空则不记录session_id", "Leave empty to skip recording session_id");
        m.put("当来源为Header时使用", "Used when the source is Header");
        m.put("限流依据的Header名称", "Header name used for rate limiting");
        // --- Labels observed in the live strategy_types table (drifted from the seed SQL) ---
        m.put("全局", "Global");
        m.put("客户端", "Client");
        m.put("首次拒绝", "Reject on First");
        m.put("全部拒绝", "Reject All");
        m.put("月度配额", "Monthly Quota");
        m.put("日度配额", "Daily Quota");
        m.put("月度", "Monthly");
        m.put("日度", "Daily");
        m.put("两者", "Both");
        m.put("模式", "Mode");
        m.put("熔断等待", "Open-State Wait");
        m.put("最小调用数", "Minimum Calls");
        m.put("签发者", "Issuer");
        m.put("预检缓存", "Preflight Cache");
        m.put("最大条目", "Max Entries");
        m.put("处置模式", "Action Mode");
        m.put("仅记录", "Log Only");
        m.put("精简", "Compact");
        m.put("标准", "Standard");
        m.put("新增请求 Header (JSON)", "Add Request Headers (JSON)");
        m.put("新增响应 Header (JSON)", "Add Response Headers (JSON)");
        m.put("注入 TraceId", "Inject TraceId");
        m.put("提供方选择", "Provider Selection");
        m.put("按模型", "By Model");
        m.put("固定", "Fixed");
        m.put("按 Header", "By Header");
        m.put("扫描请求", "Scan Request");
        m.put("扫描响应", "Scan Response");
        m.put("JWT 配置", "JWT Configuration");
        m.put("API Key 配置", "API Key Configuration");
        m.put("逗号分隔，支持 CIDR", "Comma-separated, CIDR supported");
        m.put("次/秒", "req/s");
        m.put("字节", "bytes");
        // --- Language-neutral labels/units: identity entries so every field has an …En variant ---
        m.put("QPS", "QPS");
        m.put("IP", "IP");
        m.put("Header", "Header");
        m.put("JWT", "JWT");
        m.put("OAuth2", "OAuth2");
        m.put("Basic", "Basic");
        m.put("HMAC", "HMAC");
        m.put("TTL", "TTL");
        m.put("TraceId Header", "TraceId Header");
        m.put("OpenAI", "OpenAI");
        m.put("ms", "ms");
        m.put("%", "%");
        m.put("Query", "Query");
        m.put("DeepSeek", "DeepSeek");
        m.put("Anthropic", "Anthropic");
        m.put("Anthropic Claude", "Anthropic Claude");
        m.put("System Prompt", "System Prompt");
        m.put("Session ID Header", "Session ID Header");
        m.put("IP QPS", "IP QPS");
        m.put("Client QPS", "Client QPS");
        m.put("Header QPS", "Header QPS");
        m.put("JWT Subject", "JWT Subject");
        m.put("JWT Claim", "JWT Claim");
        m.put("API Key Header", "API Key Header");
        return Collections.unmodifiableMap(m);
    }
}