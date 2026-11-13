package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.StrategyTypeEntity;
import com.leoli.gateway.admin.repository.StrategyTypeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context) for the additive English-label enrichment in
 * {@link StrategyTypeService}: labelEn / sectionLabelEn / sectionDescriptionEn / ruleLabelEn /
 * dimensionLabelEn / placeholderEn injection for both the static SUB_SCHEMAS and the
 * DB-sourced schema exits (getFullConfigSchema / toDto).
 *
 * <p>Follows the module's existing plain JUnit 5 + Mockito + ReflectionTestUtils style
 * (see AiSecurityStrategyValidatorTest / AccessLogPathSafetyTest).
 */
class StrategyTypeServiceLabelEnTest {

    private static final Pattern HAN_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");

    // ------------------------------------------------------------------
    // (a) Dictionary coverage against the seed SQL on the classpath
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every Chinese label in sql/seed/strategy_types.sql has an English dictionary entry")
    void seedChineseLabelsAllHaveEnglishTranslation() throws IOException {
        String sql = readClasspathResource("/sql/seed/strategy_types.sql");
        List<String> missing = new ArrayList<>();
        int chineseLabelCount = 0;
        for (String[] pair : ENRICHED_KEYS) {
            // Labels must always resolve (English ones via identity entries); prose, placeholders
            // and units only need a translation when they are actually Chinese.
            boolean alwaysRequired = pair[0].equals("label") || pair[0].equals("sectionLabel");
            Matcher matcher = Pattern.compile("\"" + pair[0] + "\"\\s*:\\s*\"([^\"]+)\"").matcher(sql);
            while (matcher.find()) {
                String label = matcher.group(1);
                if (alwaysRequired || HAN_PATTERN.matcher(label).find()) {
                    chineseLabelCount++;
                    String english = StrategyTypeService.LABEL_EN_DICTIONARY.get(label);
                    if (english == null || english.isBlank()) {
                        missing.add(pair[0] + "=" + label);
                    }
                }
            }
        }
        assertTrue(chineseLabelCount > 0, "regex should have found Chinese labels in the seed SQL");
        assertTrue(missing.isEmpty(),
                "seed labels missing an English translation (add them to LABEL_EN_DICTIONARY): " + missing);
    }

    @Test
    @DisplayName("static sub-schemas carry English variants for ruleLabel, sectionDescription and placeholder")
    void staticSubSchemasEnrichBeyondLabels() {
        Map<String, Object> transform = StrategyTypeService.REQUEST_TRANSFORM_SUB_SCHEMAS;
        Map<String, Object> token = StrategyTypeService.TOKEN_RATE_LIMITER_SUB_SCHEMAS;

        assertEquals("Mapping Rule", section(transform, "fieldMapping").get("ruleLabelEn"));
        assertEquals("Masking Rule", section(transform, "dataMasking").get("ruleLabelEn"));
        assertEquals("Tenant", section(token, "tenantQuotas").get("ruleLabelEn"));

        Object tenantDesc = section(token, "tenantQuotas").get("sectionDescriptionEn");
        assertTrue(tenantDesc instanceof String s && !s.isBlank(), "tenantQuotas.sectionDescriptionEn missing");
        assertFalse(HAN_PATTERN.matcher((String) tenantDesc).find(), "sectionDescriptionEn still contains Chinese");

        // Every Chinese placeholder in the static schemas must resolve too.
        List<String> missing = new ArrayList<>();
        collectMissingPlaceholders(token, missing);
        collectMissingPlaceholders(transform, missing);
        collectMissingPlaceholders(StrategyTypeService.AUTH_SUB_SCHEMAS, missing);
        collectMissingPlaceholders(StrategyTypeService.RESPONSE_TRANSFORM_SUB_SCHEMAS, missing);
        collectMissingPlaceholders(StrategyTypeService.MOCK_RESPONSE_SUB_SCHEMAS, missing);
        assertTrue(missing.isEmpty(), "static placeholders without placeholderEn: " + missing);
    }

    private void collectMissingPlaceholders(Object node, List<String> missing) {
        if (node instanceof Map<?, ?> map) {
            Object zh = map.get("placeholder");
            if (zh instanceof String s && HAN_PATTERN.matcher(s).find()) {
                Object en = map.get("placeholderEn");
                if (!(en instanceof String es) || es.isBlank()) {
                    missing.add(s);
                }
            }
            for (Object value : map.values()) {
                collectMissingPlaceholders(value, missing);
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                collectMissingPlaceholders(value, missing);
            }
        }
    }

    // ------------------------------------------------------------------
    // (b) Static SUB_SCHEMAS fully enriched by the last static block
    // ------------------------------------------------------------------

    @Test
    @DisplayName("all five static SUB_SCHEMAS have labelEn on every field/option and sectionLabelEn on every section")
    void staticSubSchemasFullyEnriched() {
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        byType.put("AUTH", StrategyTypeService.AUTH_SUB_SCHEMAS);
        byType.put("REQUEST_TRANSFORM", StrategyTypeService.REQUEST_TRANSFORM_SUB_SCHEMAS);
        byType.put("RESPONSE_TRANSFORM", StrategyTypeService.RESPONSE_TRANSFORM_SUB_SCHEMAS);
        byType.put("MOCK_RESPONSE", StrategyTypeService.MOCK_RESPONSE_SUB_SCHEMAS);
        byType.put("TOKEN_RATE_LIMITER", StrategyTypeService.TOKEN_RATE_LIMITER_SUB_SCHEMAS);

        for (Map.Entry<String, Map<String, Object>> entry : byType.entrySet()) {
            String typeCode = entry.getKey();
            Map<String, Object> subSchemas = entry.getValue();
            assertFalse(subSchemas.isEmpty(), typeCode + " subSchemas should not be empty");
            assertNodeFullyEnriched(subSchemas, typeCode);
        }

        // Sanity: expected sections exist per type.
        assertTrue(StrategyTypeService.AUTH_SUB_SCHEMAS.keySet()
                .containsAll(List.of("JWT", "API_KEY", "OAUTH2", "BASIC", "HMAC")));
        assertTrue(StrategyTypeService.TOKEN_RATE_LIMITER_SUB_SCHEMAS.keySet()
                .containsAll(List.of("tenantQuotas", "defaultQuotas", "responseFormat", "sseHandling",
                        "tenantIdExtraction", "alert", "preDeduct", "windowLimit")));

        // Spot checks: Chinese label kept, English added, option labels translated.
        Map<String, Object> jwt = section(StrategyTypeService.AUTH_SUB_SCHEMAS, "JWT");
        Map<String, Object> secretKey = firstField(jwt);
        assertEquals("密钥", secretKey.get("label"));
        assertEquals("Secret Key", secretKey.get("labelEn"));

        Map<String, Object> dataMasking = section(StrategyTypeService.REQUEST_TRANSFORM_SUB_SCHEMAS, "dataMasking");
        assertEquals("数据脱敏配置", dataMasking.get("sectionLabel"));
        assertEquals("Data Masking Configuration", dataMasking.get("sectionLabelEn"));
        Map<String, Object> maskType = fieldByName(ruleFields(dataMasking), "maskType");
        Map<String, Object> fullOption = optionByValue(maskType, "FULL");
        assertEquals("完全脱敏", fullOption.get("label"));
        assertEquals("Full Masking", fullOption.get("labelEn"));
        assertEquals("FULL", fullOption.get("value"));

        Map<String, Object> preDeduct = section(StrategyTypeService.TOKEN_RATE_LIMITER_SUB_SCHEMAS, "preDeduct");
        assertEquals("Pre-deduction & Refund Policy", preDeduct.get("sectionLabelEn"));
        Map<String, Object> strategyField = fieldByName(fields(preDeduct), "preDeductStrategy");
        assertEquals("Estimated Pre-deduction", optionByValue(strategyField, "ESTIMATE").get("labelEn"));
    }

    // ------------------------------------------------------------------
    // (c) Immutable Map.of / List.of path (createSelectFieldWithLabels)
    // ------------------------------------------------------------------

    /**
     * Chinese strings observed in the running deployment's strategy_types.config_schema that the
     * seed SQL does not contain (rows drift: "租户ID" became "租户 ID", and new strategy types were
     * added). Asserting only against the seed gave a false green while 37 of 77 live labels had no
     * translation. Regenerate from GET /api/strategy-types/enabled when the catalog changes.
     */
    private static final String[] LIVE_DRIFTED_LABELS = {
            "租户 ID", "IP 列表", "Header 名", "SQL 注入防护", "XSS 防护", "版本 Header", "最大 Body",
            "Mock 模式", "PII 脱敏", "全局", "客户端", "首次拒绝", "全部拒绝", "月度配额", "日度配额",
            "月度", "日度", "两者", "模式", "熔断等待", "最小调用数", "签发者", "预检缓存", "最大条目",
            "处置模式", "仅记录", "精简", "标准", "新增请求 Header (JSON)", "新增响应 Header (JSON)",
            "注入 TraceId", "提供方选择", "按模型", "固定", "按 Header", "扫描请求", "扫描响应",
    };
    private static final String[] LIVE_DRIFTED_UNITS = {"次/秒", "字节"};
    private static final String[] LIVE_DRIFTED_SECTION_LABELS = {"JWT 配置", "API Key 配置"};
    private static final String[] LIVE_DRIFTED_PLACEHOLDERS = {"逗号分隔，支持 CIDR"};

    @Test
    @DisplayName("every Chinese string observed in the live strategy_types table resolves to English")
    void liveCatalogStringsAllResolve() {
        Map<String, Object> schema = new LinkedHashMap<>();
        List<Object> fields = new ArrayList<>();
        for (String label : LIVE_DRIFTED_LABELS) {
            fields.add(new LinkedHashMap<>(Map.of("name", "f", "type", "text", "label", label)));
        }
        for (String unit : LIVE_DRIFTED_UNITS) {
            fields.add(new LinkedHashMap<>(Map.of("name", "u", "type", "number", "label", "QPS", "unit", unit)));
        }
        for (String placeholder : LIVE_DRIFTED_PLACEHOLDERS) {
            fields.add(new LinkedHashMap<>(Map.of("name", "p", "type", "text", "label", "IP 列表",
                    "placeholder", placeholder)));
        }
        schema.put("fields", fields);
        Map<String, Object> sections = new LinkedHashMap<>();
        for (String sectionLabel : LIVE_DRIFTED_SECTION_LABELS) {
            sections.put(sectionLabel, new LinkedHashMap<>(Map.of("sectionLabel", sectionLabel)));
        }
        schema.put("subSchemas", sections);

        Map<String, Object> enriched = StrategyTypeService.enrichSchemaWithEnglishLabels(schema);
        assertNodeFullyEnriched(enriched, "live");
    }

    @Test
    @DisplayName("whitespace drift between seed and live rows still resolves via the normalized fallback")
    void whitespaceDriftResolvesThroughNormalizedDictionary() {
        // The seed writes "租户ID"; the live row writes "租户 ID". Both must translate.
        assertEquals("Tenant ID", StrategyTypeService.LABEL_EN_DICTIONARY.get("租户ID"));
        Map<String, Object> schema = new LinkedHashMap<>(Map.of(
                "fields", List.of(new LinkedHashMap<>(Map.of("label", "租户 ID")))));
        Map<String, Object> enriched = StrategyTypeService.enrichSchemaWithEnglishLabels(schema);
        assertEquals("Tenant ID", asMap(asList(enriched.get("fields")).get(0)).get("labelEn"));
    }

    @Test
    @DisplayName("Chinese units get unitEn without disturbing the identical option label")
    void unitsAreEnrichedWithoutCorruptingOptionLabels() {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("value", "second");
        option.put("label", "秒");
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("label", "QPS");
        field.put("unit", "次/秒");
        field.put("options", List.of(option));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("fields", List.of(field));

        Map<String, Object> enriched = StrategyTypeService.enrichSchemaWithEnglishLabels(schema);
        Map<String, Object> enrichedField = asMap(asList(enriched.get("fields")).get(0));
        assertEquals("req/s", enrichedField.get("unitEn"));
        assertEquals("次/秒", enrichedField.get("unit"), "the Chinese unit must stay untouched");
        // "秒" is a time-unit option ("Second"), not the unit suffix "s" — one text, one meaning.
        assertEquals("Second", asMap(asList(enrichedField.get("options")).get(0)).get("labelEn"));
    }

    @Test
    @DisplayName("immutable option maps/lists are replaced with mutable copies without throwing or altering label/value")
    void immutableMapOfOptionsAreEnrichedViaCopies() {
        Map<String, String> immutableOption = Map.of("value", "FULL", "label", "完全脱敏");
        Map<String, Object> field = new HashMap<>();
        field.put("name", "maskType");
        field.put("options", List.of(immutableOption));
        Map<String, Object> schema = new HashMap<>();
        schema.put("fields", List.of(field));

        Map<String, Object> enriched =
                assertDoesNotThrow(() -> StrategyTypeService.enrichSchemaWithEnglishLabels(schema));

        Map<String, Object> enrichedField = asMap(asList(enriched.get("fields")).get(0));
        Map<String, Object> enrichedOption = asMap(asList(enrichedField.get("options")).get(0));
        assertEquals("Full Masking", enrichedOption.get("labelEn"));
        assertEquals("完全脱敏", enrichedOption.get("label"));
        assertEquals("FULL", enrichedOption.get("value"));
        // The original immutable option must be untouched.
        assertEquals(2, immutableOption.size());
        assertFalse(immutableOption.containsKey("labelEn"));
    }

    @Test
    @DisplayName("fully immutable nested schema (Map.of all the way down) is copied, enriched, and the original stays unchanged")
    void fullyImmutableNestedSchemaIsCopied() {
        Map<String, Object> immutableSchema = Map.of(
                "label", "突发容量",
                "sectionLabel", "告警配置",
                "subSchemas", Map.of("X", Map.of("fields", List.of(Map.of("label", "滑动窗口")))));

        Map<String, Object> enriched =
                assertDoesNotThrow(() -> StrategyTypeService.enrichSchemaWithEnglishLabels(immutableSchema));

        assertNotSame(immutableSchema, enriched);
        assertEquals("Burst Capacity", enriched.get("labelEn"));
        assertEquals("Alert Configuration", enriched.get("sectionLabelEn"));
        assertEquals("突发容量", enriched.get("label"));
        assertEquals("告警配置", enriched.get("sectionLabel"));
        Map<String, Object> innerField = asMap(asList(asMap(asMap(enriched.get("subSchemas")).get("X")).get("fields")).get(0));
        assertEquals("Sliding Window Size", innerField.get("labelEn"));
        assertEquals("滑动窗口", innerField.get("label"));
        // Original immutable structure untouched.
        assertEquals(3, immutableSchema.size());
        assertFalse(immutableSchema.containsKey("labelEn"));
    }

    // ------------------------------------------------------------------
    // (d) Unknown labels: no labelEn, no exception, no value changes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unknown Chinese labels get no labelEn and cause no exception")
    void unknownLabelsAreLeftUntouched() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("label", "一个字典里没有的标签");
        schema.put("sectionLabel", "另一个未知章节");
        schema.put("value", "KEEP_ME");

        Map<String, Object> enriched =
                assertDoesNotThrow(() -> StrategyTypeService.enrichSchemaWithEnglishLabels(schema));

        assertFalse(enriched.containsKey("labelEn"));
        assertFalse(enriched.containsKey("sectionLabelEn"));
        assertEquals("一个字典里没有的标签", enriched.get("label"));
        assertEquals("另一个未知章节", enriched.get("sectionLabel"));
        assertEquals("KEEP_ME", enriched.get("value"));
    }

    @Test
    @DisplayName("non-String labels are skipped, existing labelEn is never overwritten, null schema returns null")
    void nonStringLabelsSkippedAndExistingEnglishPreserved() {
        Map<String, Object> nonString = new HashMap<>();
        nonString.put("label", 42);
        Map<String, Object> enrichedNonString =
                assertDoesNotThrow(() -> StrategyTypeService.enrichSchemaWithEnglishLabels(nonString));
        assertFalse(enrichedNonString.containsKey("labelEn"));
        assertEquals(42, enrichedNonString.get("label"));

        Map<String, Object> preExisting = new HashMap<>();
        preExisting.put("label", "突发容量");
        preExisting.put("labelEn", "Custom Existing Translation");
        Map<String, Object> enrichedPreExisting = StrategyTypeService.enrichSchemaWithEnglishLabels(preExisting);
        assertEquals("Custom Existing Translation", enrichedPreExisting.get("labelEn"));

        assertNull(StrategyTypeService.enrichSchemaWithEnglishLabels(null));
    }

    // ------------------------------------------------------------------
    // DB-sourced exits: getFullConfigSchema / toDto (mocked repository)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getFullConfigSchema enriches DB schema and attaches already-enriched static subSchemas")
    void getFullConfigSchemaEnrichesDbSchemaAndStaticSubSchemas() {
        StrategyTypeService service = newServiceWithMockedRepository();
        StrategyTypeEntity entity = authEntity();
        StrategyTypeRepository repository =
                (StrategyTypeRepository) ReflectionTestUtils.getField(service, "strategyTypeRepository");
        when(repository.findById("AUTH")).thenReturn(Optional.of(entity));

        Map<String, Object> schema = service.getFullConfigSchema("AUTH");
        assertNotNull(schema);
        assertFalse(schema.containsKey("hasSubSchemas"));

        Map<String, Object> authTypeField = asMap(asList(schema.get("fields")).get(0));
        assertEquals("认证类型", authTypeField.get("label"));
        assertEquals("Auth Type", authTypeField.get("labelEn"));
        List<Object> options = asList(authTypeField.get("options"));
        // Language-neutral option label: identity entry, so it still gets an labelEn.
        Map<String, Object> jwtOption = asMap(options.get(0));
        assertEquals("JWT", jwtOption.get("labelEn"));
        assertEquals("JWT", jwtOption.get("value"));
        // Chinese option label: translated.
        Map<String, Object> metadataOption = asMap(options.get(1));
        assertEquals("API Key Metadata", metadataOption.get("labelEn"));
        assertEquals("API Key元数据", metadataOption.get("label"));

        Map<String, Object> jwtSub = asMap(asMap(schema.get("subSchemas")).get("JWT"));
        Map<String, Object> secretKey = firstField(jwtSub);
        assertEquals("密钥", secretKey.get("label"));
        assertEquals("Secret Key", secretKey.get("labelEn"));
    }

    @Test
    @DisplayName("DB-provided nested subSchemas/dimensionOptions are enriched recursively")
    void dbProvidedNestedSubSchemasAreEnriched() {
        StrategyTypeService service = newServiceWithMockedRepository();
        StrategyTypeEntity entity = new StrategyTypeEntity();
        entity.setTypeCode("MULTI_DIM_RATE_LIMITER");
        entity.setConfigSchema("{\"hasSubSchemas\":true,"
                + "\"dimensionOptions\":[{\"value\":\"GLOBAL\",\"label\":\"全局配额\"}],"
                + "\"subSchemas\":{\"GLOBAL\":{\"fields\":[{\"name\":\"qps\",\"label\":\"全局QPS\",\"default\":10000}]}}}");
        StrategyTypeRepository repository =
                (StrategyTypeRepository) ReflectionTestUtils.getField(service, "strategyTypeRepository");
        when(repository.findById("MULTI_DIM_RATE_LIMITER")).thenReturn(Optional.of(entity));

        Map<String, Object> schema = service.getFullConfigSchema("MULTI_DIM_RATE_LIMITER");
        assertNotNull(schema);
        Map<String, Object> dimensionOption = asMap(asList(schema.get("dimensionOptions")).get(0));
        assertEquals("Global Quota", dimensionOption.get("labelEn"));
        assertEquals("全局配额", dimensionOption.get("label"));
        Map<String, Object> globalSub = asMap(asMap(schema.get("subSchemas")).get("GLOBAL"));
        Map<String, Object> qpsField = asMap(asList(globalSub.get("fields")).get(0));
        assertEquals("Global QPS", qpsField.get("labelEn"));
        assertEquals(10000, qpsField.get("default"));
    }

    @Test
    @DisplayName("toDto carries the enriched configSchema and leaves existing DTO fields unchanged")
    void toDtoCarriesEnrichedSchema() {
        StrategyTypeService service = newServiceWithMockedRepository();
        StrategyTypeEntity entity = authEntity();
        StrategyTypeRepository repository =
                (StrategyTypeRepository) ReflectionTestUtils.getField(service, "strategyTypeRepository");
        when(repository.findById("AUTH")).thenReturn(Optional.of(entity));

        Map<String, Object> dto = service.toDto(entity);
        assertEquals("AUTH", dto.get("typeCode"));
        assertEquals("Authentication", dto.get("typeNameEn"));
        Map<String, Object> schema = asMap(dto.get("configSchema"));
        Map<String, Object> authTypeField = asMap(asList(schema.get("fields")).get(0));
        assertEquals("Auth Type", authTypeField.get("labelEn"));
        assertEquals("认证类型", authTypeField.get("label"));
    }

    @Test
    @DisplayName("each call parses a fresh schema: mutating one result never pollutes later results or static subSchemas")
    void enrichmentDoesNotPolluteSharedState() {
        StrategyTypeService service = newServiceWithMockedRepository();
        StrategyTypeEntity entity = authEntity();
        StrategyTypeRepository repository =
                (StrategyTypeRepository) ReflectionTestUtils.getField(service, "strategyTypeRepository");
        when(repository.findById("AUTH")).thenReturn(Optional.of(entity));

        Map<String, Object> first = service.getFullConfigSchema("AUTH");
        first.put("junk", true);
        asMap(asList(first.get("fields")).get(0)).put("labelEn", "POLLUTED");

        Map<String, Object> second = service.getFullConfigSchema("AUTH");
        assertFalse(second.containsKey("junk"));
        assertEquals("Auth Type", asMap(asList(second.get("fields")).get(0)).get("labelEn"));
        // Static subSchemas remain correctly enriched, unaffected by DB-schema mutation.
        assertEquals("Secret Key", firstField(asMap(StrategyTypeService.AUTH_SUB_SCHEMAS.get("JWT"))).get("labelEn"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private StrategyTypeService newServiceWithMockedRepository() {
        StrategyTypeService service = new StrategyTypeService();
        ReflectionTestUtils.setField(service, "strategyTypeRepository", mock(StrategyTypeRepository.class));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return service;
    }

    private StrategyTypeEntity authEntity() {
        StrategyTypeEntity entity = new StrategyTypeEntity();
        entity.setTypeCode("AUTH");
        entity.setTypeName("认证策略");
        entity.setTypeNameEn("Authentication");
        entity.setConfigSchema("{\"fields\":[{\"name\":\"authType\",\"type\":\"select\",\"label\":\"认证类型\","
                + "\"options\":[{\"value\":\"JWT\",\"label\":\"JWT\"},"
                + "{\"value\":\"api_key_metadata\",\"label\":\"API Key元数据\"}]}],\"hasSubSchemas\":true}");
        return entity;
    }

    private String readClasspathResource(String path) throws IOException {
        try (InputStream in = StrategyTypeServiceLabelEnTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Localized schema keys and the English variant the enricher must add for each. */
    private static final String[][] ENRICHED_KEYS = {
            {"label", "labelEn"},
            {"sectionLabel", "sectionLabelEn"},
            {"sectionDescription", "sectionDescriptionEn"},
            {"ruleLabel", "ruleLabelEn"},
            {"dimensionLabel", "dimensionLabelEn"},
            {"placeholder", "placeholderEn"},
    };

    /**
     * Recursively asserts every localized key has a usable English variant. Labels and section
     * labels must always have one (the dictionary carries identity entries for the English ones);
     * prose and placeholders only need one when the source is Chinese — technical values such as
     * the {@code $.user.name} JSONPath sample are language-neutral and stay untouched.
     */
    private void assertNodeFullyEnriched(Object node, String path) {
        if (node instanceof Map<?, ?> map) {
            for (String[] pair : ENRICHED_KEYS) {
                if (!(map.get(pair[0]) instanceof String source) || source.isBlank()) {
                    continue;
                }
                boolean required = pair[0].equals("label") || pair[0].equals("sectionLabel")
                        || HAN_PATTERN.matcher(source).find();
                Object en = map.get(pair[1]);
                if (required || en != null) {
                    assertTrue(en instanceof String s && !s.isBlank(),
                            path + ": missing " + pair[1] + " for " + pair[0] + "=" + source);
                }
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                assertNodeFullyEnriched(entry.getValue(), path + "." + entry.getKey());
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                assertNodeFullyEnriched(list.get(i), path + "[" + i + "]");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object node) {
        return (List<Object>) node;
    }

    private static Map<String, Object> section(Map<String, Object> subSchemas, String name) {
        Map<String, Object> section = asMap(subSchemas.get(name));
        assertNotNull(section, "section not found: " + name);
        return section;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> section) {
        return (List<Map<String, Object>>) (List<?>) section.get("fields");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ruleFields(Map<String, Object> section) {
        return (List<Map<String, Object>>) (List<?>) section.get("ruleFields");
    }

    private static Map<String, Object> firstField(Map<String, Object> section) {
        return asMap(asList(section.get("fields")).get(0));
    }

    private static Map<String, Object> fieldByName(List<Map<String, Object>> fields, String name) {
        return fields.stream().filter(f -> name.equals(f.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionByValue(Map<String, Object> field, String value) {
        List<Map<String, Object>> options = (List<Map<String, Object>>) (List<?>) field.get("options");
        return options.stream().filter(o -> value.equals(o.get("value"))).findFirst().orElseThrow();
    }
}