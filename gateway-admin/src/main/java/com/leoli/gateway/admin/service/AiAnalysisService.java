package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiConfig;

import com.leoli.gateway.admin.repository.AiConfigRepository;
import com.leoli.gateway.admin.util.ApiKeyEncryptor;
import com.leoli.gateway.admin.util.UrlSecurityValidator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

@Slf4j
@Service
public class AiAnalysisService {

    private final AiConfigRepository aiConfigRepository;
    private final GatewayInstanceService gatewayInstanceService;  // Pod维度分析需要
    private final StressTestService stressTestService;  // Pod维度压测分析需要，使用@Lazy打破循环依赖
    private final AiMetricsCollector aiMetricsCollector;  // 指标数据收集
    private final AiPromptBuilder aiPromptBuilder;  // 提示词构建

    public AiAnalysisService(AiConfigRepository aiConfigRepository,
                              GatewayInstanceService gatewayInstanceService,
                              @Lazy StressTestService stressTestService,
                              AiMetricsCollector aiMetricsCollector,
                              AiPromptBuilder aiPromptBuilder,
                              ApiKeyEncryptor apiKeyEncryptor) {
        this.aiConfigRepository = aiConfigRepository;
        this.gatewayInstanceService = gatewayInstanceService;
        this.stressTestService = stressTestService;
        this.aiMetricsCollector = aiMetricsCollector;
        this.aiPromptBuilder = aiPromptBuilder;
        this.apiKeyEncryptor = apiKeyEncryptor;
    }

    // FIX: encryption moved from the JPA entity (where @Autowired never worked and keys
    // were stored in plaintext) to the service layer.
    private final ApiKeyEncryptor apiKeyEncryptor;

    /**
     * Resolve the usable (decrypted) API key for a stored config.
     * Legacy plaintext values pass through unchanged because decrypt() falls back
     * to the original value when GCM decryption fails.
     */
    public String resolveApiKey(AiConfig config) {
        return config.getApiKey() != null ? apiKeyEncryptor.decrypt(config.getApiKey()) : null;
    }
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        // 应用启动时执行迁移和初始化
        migrateQwenToBailian();
        initializeProviders();
    }

    // 提供商显示名称
    private static final Map<String, String> PROVIDER_NAMES = Map.of(
            "OPENAI", "OpenAI",
            "GEMINI", "Google Gemini",
            "CLAUDE", "Anthropic Claude",
            "BAILIAN", "阿里云百炼"
    );

    // 提供商区域
    private static final Map<String, String> PROVIDER_REGIONS = Map.of(
            "OPENAI", "OVERSEAS",
            "GEMINI", "OVERSEAS",
            "CLAUDE", "OVERSEAS",
            "BAILIAN", "DOMESTIC"
    );

    // RestTemplate with connection pooling and 5 minute timeout for AI API calls
    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMinutes(5));
        return new RestTemplate(factory);
    }

    // 各提供商的API地址
    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
            "OPENAI", "https://api.openai.com/v1",
            "GEMINI", "https://generativelanguage.googleapis.com/v1beta",
            "CLAUDE", "https://api.anthropic.com/v1",
            "BAILIAN", "https://dashscope.aliyuncs.com/compatible-mode/v1"
    );

    // 各提供商支持的模型 - 只保留最新版本
    private static final Map<String, List<String>> PROVIDER_MODELS = Map.of(
            "OPENAI", List.of("gpt-4o", "gpt-4-turbo", "gpt-4"),
            "GEMINI", List.of("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash"),
            "CLAUDE", List.of("claude-3.5-sonnet", "claude-3-opus", "claude-3-haiku"),
            // 百炼平台 - 只保留最新版本模型（2026-10-10 按 DashScope /compatible-mode/v1/models
            // 活目录核对：旧名单 qwen-max/deepseek-v3/kimi-k2/moonshot-v1-128k/glm-4.x 均已落后一代以上，
            // moonshot-v1-128k 甚至已下架）
            "BAILIAN", List.of(
                    // Qwen 系列 (最新)
                    "qwen3.8-max", "qwen3.7-plus", "qwen3.8-flash", "qwen3-coder-next",
                    // DeepSeek 系列 (最新)
                    "deepseek-v4-pro", "deepseek-v4.1-flash",
                    // Kimi 系列 (最新)
                    "kimi-k3", "kimi-k2.7-code",
                    // GLM 系列 (最新)
                    "glm-5.3", "glm-5.2"
            )
    );

    /**
     * 获取所有提供商配置
     */
    public List<AiConfig> getAllProviders() {
        List<AiConfig> providers = aiConfigRepository.findAll();

        // 如果数据库为空，初始化默认提供商
        if (providers.isEmpty()) {
            initializeProviders();
            providers = aiConfigRepository.findAll();
        }

        return providers;
    }

    /**
     * 初始化AI提供商配置
     */
    public void initializeProviders() {
        log.info("Initializing AI providers...");

        for (String provider : DEFAULT_BASE_URLS.keySet()) {
            if (aiConfigRepository.findByProvider(provider).isEmpty()) {
                AiConfig config = new AiConfig();
                config.setProvider(provider);
                config.setProviderName(PROVIDER_NAMES.getOrDefault(provider, provider));
                config.setRegion(PROVIDER_REGIONS.getOrDefault(provider, "OVERSEAS"));
                config.setIsValid(false);
                aiConfigRepository.save(config);
                log.info("Created AI provider: {}", provider);
            }
        }
        log.info("AI providers initialization completed");
    }

    /**
     * 迁移旧的 QWEN 配置到 BAILIAN
     * 百炼平台包含通义千问等多种模型，统一使用 BAILIAN 作为提供商标识
     */
    private void migrateQwenToBailian() {
        Optional<AiConfig> oldQwen = aiConfigRepository.findByProvider("QWEN");
        if (oldQwen.isPresent()) {
            AiConfig qwenConfig = oldQwen.get();
            log.info("Found old QWEN config, migrating to BAILIAN...");

            // 检查是否已存在 BAILIAN 配置
            Optional<AiConfig> existingBailian = aiConfigRepository.findByProvider("BAILIAN");
            if (existingBailian.isPresent()) {
                // 如果 BAILIAN 已存在且未配置，更新其配置
                AiConfig bailianConfig = existingBailian.get();
                if (bailianConfig.getApiKey() == null && qwenConfig.getApiKey() != null) {
                    // Copy the stored (possibly encrypted) value as-is between entities
                    bailianConfig.setApiKey(qwenConfig.getApiKey());
                    bailianConfig.setModel(qwenConfig.getModel());
                    bailianConfig.setBaseUrl(qwenConfig.getBaseUrl() != null ?
                            qwenConfig.getBaseUrl().replace("/api/v1", "/compatible-mode/v1") :
                            DEFAULT_BASE_URLS.get("BAILIAN"));
                    bailianConfig.setIsValid(qwenConfig.getIsValid());
                    bailianConfig.setLastValidatedAt(qwenConfig.getLastValidatedAt());
                    aiConfigRepository.save(bailianConfig);
                    log.info("Migrated QWEN config to existing BAILIAN config");
                }
            } else {
                // 如果 BAILIAN 不存在，直接修改 QWEN 为 BAILIAN
                qwenConfig.setProvider("BAILIAN");
                qwenConfig.setProviderName("阿里云百炼");
                qwenConfig.setRegion("DOMESTIC");
                // 更新 baseUrl 为兼容模式地址
                if (qwenConfig.getBaseUrl() != null) {
                    qwenConfig.setBaseUrl(qwenConfig.getBaseUrl().replace("/api/v1", "/compatible-mode/v1"));
                }
                aiConfigRepository.save(qwenConfig);
                log.info("Renamed QWEN provider to BAILIAN");
            }

            // 删除可能残留的 QWEN 记录（如果创建了新的 BAILIAN）
            aiConfigRepository.deleteByProvider("QWEN");
            log.info("QWEN to BAILIAN migration completed");
        }
    }

    /**
     * 获取指定区域的提供商
     */
    public List<AiConfig> getProvidersByRegion(String region) {
        return aiConfigRepository.findByRegion(region);
    }

    /**
     * 获取提供商支持的模型列表
     */
    public List<String> getSupportedModels(String provider) {
        return PROVIDER_MODELS.getOrDefault(provider, List.of());
    }

    /**
     * 验证API Key
     */
    public boolean validateApiKey(String provider, String apiKey, String baseUrl) {
        try {
            String url = baseUrl != null ? baseUrl : DEFAULT_BASE_URLS.get(provider);

            // FIX: SSRF guard - never send credentials to user-supplied internal addresses
            UrlSecurityValidator.validateExternalUrl(url);

            switch (provider) {
                case "BAILIAN":
                    // 百炼平台使用 qwen-turbo 模型验证
                    return validateBailianKey(url, apiKey);
                case "OPENAI":
                    return validateOpenAIKey(url, apiKey);
                case "GEMINI":
                    return validateGeminiKey(url, apiKey);
                case "CLAUDE":
                    return validateClaudeKey(url, apiKey);
                default:
                    return false;
            }
        } catch (Exception e) {
            log.error("Validate API key failed for provider: {}", provider, e);
            return false;
        }
    }

    /**
     * 验证百炼平台 API Key
     */
    private boolean validateBailianKey(String baseUrl, String apiKey) {
        try {
            String url = baseUrl + "/chat/completions";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 使用百炼平台支持的模型
            Map<String, Object> body = Map.of(
                    "model", "qwen-turbo",
                    "messages", List.of(Map.of("role", "user", "content", "hi")),
                    "max_tokens", 5
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("Bailian API key validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 保存配置
     */
    public AiConfig saveConfig(String provider, String model, String apiKey, String baseUrl) {
        AiConfig config = aiConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));

        config.setModel(model);
        // FIX (P1 数据丢失): 空 apiKey 表示"本次不改密钥"（前端切换 provider 时会清空
        // 输入框、或只改模型/baseUrl），必须保留已存的加密密钥，绝不能置 null 把用户
        // 之前配好的 Key 抹掉。同理空 baseUrl 也视为"不改"，避免误清自定义地址。
        if (apiKey != null && !apiKey.isEmpty()) {
            config.setApiKey(apiKeyEncryptor.encrypt(apiKey));
        }
        // FIX: SSRF guard - reject user-supplied baseUrl pointing to internal/metadata addresses
        if (baseUrl != null && !baseUrl.isEmpty()) {
            UrlSecurityValidator.validateExternalUrl(baseUrl);
            config.setBaseUrl(baseUrl);
        }
        // isValid 只有在确实持有密钥时才为真，避免"标记有效但 Key 为空"的误导状态
        boolean hasKey = config.getApiKey() != null && !config.getApiKey().isEmpty();
        config.setIsValid(hasKey);
        if (hasKey) {
            config.setLastValidatedAt(LocalDateTime.now());
        }

        return aiConfigRepository.save(config);
    }

    /**
     * 调用大模型API（公开方法，供其他服务调用）
     *
     * @param provider AI提供商
     * @param model    模型名称
     * @param prompt   提示词
     * @return AI响应内容
     */
    public String callAiApi(String provider, String model, String prompt) {
        AiConfig config = aiConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));

        if (config.getApiKey() == null) {
            throw new RuntimeException("API Key not configured for provider: " + provider);
        }

        // 使用传入的model或配置中的model
        if (model != null && !model.isEmpty()) {
            config.setModel(model);
        }

        if (config.getModel() == null) {
            throw new RuntimeException("Model not configured for provider: " + provider);
        }

        return callModelApi(config, prompt);
    }

    /**
     * 调用大模型API
     */
    private String callModelApi(AiConfig config, String prompt) {
        String provider = config.getProvider();
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URLS.get(provider);

        try {
            String apiKey = resolveApiKey(config);
            return switch (provider) {
                case "BAILIAN" -> callOpenAIApi(baseUrl, apiKey, config.getModel(), prompt);
                case "OPENAI" -> callOpenAIApi(baseUrl, apiKey, config.getModel(), prompt);
                case "GEMINI" -> callGeminiApi(baseUrl, apiKey, config.getModel(), prompt);
                case "CLAUDE" -> callClaudeApi(baseUrl, apiKey, config.getModel(), prompt);
                default -> throw new RuntimeException("Unsupported provider: " + provider);
            };
        } catch (Exception e) {
            log.error("Failed to call model API for provider: {}", provider, e);
            throw new RuntimeException("AI分析失败: " + e.getMessage());
        }
    }

    // ========== 各模型API调用实现 ==========

    private String callOpenAIApi(String baseUrl, String apiKey, String model, String prompt) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是一个专业的Java应用运维专家。"),
                        Map.of("role", "user", "content", prompt)
                )
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        return extractOpenAIResponse(response.getBody());
    }

    private String callGeminiApi(String baseUrl, String apiKey, String model, String prompt) {
        String url = baseUrl + "/models/" + model + ":generateContent";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                )
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        return extractGeminiResponse(response.getBody());
    }

    private String callClaudeApi(String baseUrl, String apiKey, String model, String prompt) {
        String url = baseUrl + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 4096,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                )
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        return extractClaudeResponse(response.getBody());
    }

    // ========== 响应解析 ==========

    private String extractQwenResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            // Qwen API响应格式: output.choices[0].message.content
            JsonNode output = root.path("output");
            if (output.has("choices")) {
                return output.path("choices").get(0).path("message").path("content").asText();
            }
            // 兼容旧格式: output.text
            if (output.has("text")) {
                return output.path("text").asText();
            }
            log.error("Unexpected Qwen response format: {}", body);
            return "解析响应失败: 未知格式";
        } catch (Exception e) {
            log.error("Failed to parse Qwen response: {}", body, e);
            return "解析响应失败: " + e.getMessage();
        }
    }

    private String extractOpenAIResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response", e);
            return "解析响应失败";
        }
    }

    private String extractGeminiResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        } catch (Exception e) {
            log.error("Failed to parse Gemini response", e);
            return "解析响应失败";
        }
    }

    private String extractClaudeResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("content").get(0).path("text").asText();
        } catch (Exception e) {
            log.error("Failed to parse Claude response", e);
            return "解析响应失败";
        }
    }

    // ========== API Key验证 ==========

    private boolean validateQwenKey(String baseUrl, String apiKey) {
        try {
            String url = baseUrl + "/services/aigc/text-generation/generation";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> body = Map.of(
                    "model", "qwen-turbo",
                    "input", Map.of("messages", List.of(Map.of("role", "user", "content", "hi")))
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateOpenAIKey(String baseUrl, String apiKey) {
        try {
            String url = baseUrl + "/chat/completions";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = Map.of(
                    "model", "gpt-3.5-turbo",
                    "messages", List.of(Map.of("role", "user", "content", "hi")),
                    "max_tokens", 5
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateDeepSeekKey(String baseUrl, String apiKey) {
        return validateOpenAIKey(baseUrl, apiKey);
    }

    private boolean validateKimiKey(String baseUrl, String apiKey) {
        return validateOpenAIKey(baseUrl, apiKey);
    }

    private boolean validateGlmeKey(String baseUrl, String apiKey) {
        try {
            String url = baseUrl + "/chat/completions";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = Map.of(
                    "model", "glm-3-turbo",
                    "messages", List.of(Map.of("role", "user", "content", "hi"))
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateGeminiKey(String baseUrl, String apiKey) {
        try {
            String url = baseUrl + "/models/gemini-pro:generateContent";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);

            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", "hi"))))
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateClaudeKey(String baseUrl, String apiKey) {
        try {
            String url = baseUrl + "/messages";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> body = Map.of(
                    "model", "claude-3-haiku",
                    "max_tokens", 10,
                    "messages", List.of(Map.of("role", "user", "content", "hi"))
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 分析指定时间段的指标数据（用于压测分析）
     *
     * @param provider  AI提供商
     * @param startTime 开始时间（时间戳，秒）
     * @param endTime   结束时间（时间戳，秒）
     * @param language  语言
     * @return AI分析结果
     */
    public TimeRangeAnalysis analyzeTimeRange(String provider, long startTime, long endTime, String language) {
        AiConfig config = aiConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));

        if (config.getApiKey() == null || config.getModel() == null) {
            throw new RuntimeException("API Key or Model not configured for provider: " + provider);
        }

        // 收集时间段内的监控数据与覆盖情况（缺失指标会被明确标注，而不是当作 0）
        var snapshot = aiMetricsCollector.collectTimeRangeMetrics(startTime, endTime);
        String metricsData = snapshot.metricsData();
        Map<String, Object> evidence = snapshot.evidence();

        // 构建分析prompt
        String prompt = aiPromptBuilder.buildTimeRangeAnalysisPrompt(metricsData, evidence, startTime, endTime, language);

        // 调用AI分析；返回报告自身、采集覆盖与原始指标，便于逐条核对依据
        return new TimeRangeAnalysis(callModelApi(config, prompt), evidence, metricsData);
    }

    /** Report text plus the exact metrics and coverage it was generated from. */
    public record TimeRangeAnalysis(String result, Map<String, Object> evidence, String metricsData) { }

    /**
     * 分析压力测试结果，结合压测数据和 Prometheus 监控数据。
     *
     * @param provider       AI提供商
     * @param stressTestData 压测结果摘要（由 StressTestService.buildAnalysisData 构建）
     * @param startTime      测试开始时间（时间戳，秒）
     * @param endTime        测试结束时间（时间戳，秒）
     * @param language       语言
     * @return AI分析结果
     */
    public String analyzeStressTest(String provider, String stressTestData, long startTime, long endTime, String language) {
        AiConfig config = aiConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));

        if (config.getApiKey() == null || config.getModel() == null) {
            throw new RuntimeException("API Key or Model not configured for provider: " + provider);
        }

        // 收集 Prometheus 监控数据
        String metricsData = aiMetricsCollector.collectTimeRangeMetricsData(startTime, endTime);

        // 构建压测专用分析 prompt
        String prompt = aiPromptBuilder.buildStressTestAnalysisPrompt(stressTestData, metricsData, language);

        return callModelApi(config, prompt);
    }

    // ===================== Function Calling / Tool Calling 支持 =====================

    /**
     * 带工具调用的完整对话方法.
     *
     * @param provider AI 提供商
     * @param model    模型名称
     * @param messages 消息数组（包含历史）
     * @param tools    工具定义列表
     * @return AI 响应（可能包含 tool_calls）
     */
    public AiResponse chatWithTools(String provider, String model,
                                    List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools) {
        AiConfig config = aiConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));

        if (config.getApiKey() == null) {
            throw new RuntimeException("API Key not configured for provider: " + provider);
        }

        String effectiveModel = (model != null && !model.isEmpty()) ? model : config.getModel();
        if (effectiveModel == null) {
            throw new RuntimeException("Model not configured for provider: " + provider);
        }

        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URLS.get(provider);

        try {
            String apiKey = resolveApiKey(config);
            return switch (provider) {
                case "BAILIAN" -> callOpenAIWithTools(baseUrl, apiKey, effectiveModel, messages, tools);
                case "OPENAI" -> callOpenAIWithTools(baseUrl, apiKey, effectiveModel, messages, tools);
                case "CLAUDE" -> callClaudeWithTools(baseUrl, apiKey, effectiveModel, messages, tools);
                case "GEMINI" -> callGeminiWithTools(baseUrl, apiKey, effectiveModel, messages, tools);
                default -> throw new RuntimeException("Unsupported provider: " + provider);
            };
        } catch (Exception e) {
            log.error("Failed to call model API with tools for provider: {}", provider, e);
            throw new RuntimeException("AI调用失败: " + e.getMessage());
        }
    }

    /**
     * 带工具的<b>流式</b>调用（token 级）。
     * <p>
     * 目前仅 OpenAI / 百炼（OpenAI 兼容）走真流式：解析 SSE 增量，把文本片段通过
     * {@code onDelta} 实时回调出去，同时累积 tool_calls 分片与 usage。Claude / Gemini
     * 各家流式格式不同，暂回退到非流式 {@link #chatWithTools}（不产生 delta，但结果一致）。
     *
     * @param onDelta 文本增量回调（可为 null）；仅最终答案的 content 会触发
     * @return 与非流式一致的 AiResponse（content 为完整累积文本）
     */
    public AiResponse chatWithToolsStream(String provider, String model,
                                          List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          Consumer<String> onDelta) {
        AiConfig config = aiConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));
        if (config.getApiKey() == null) {
            throw new RuntimeException("API Key not configured for provider: " + provider);
        }
        String effectiveModel = (model != null && !model.isEmpty()) ? model : config.getModel();
        if (effectiveModel == null) {
            throw new RuntimeException("Model not configured for provider: " + provider);
        }
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URLS.get(provider);

        // 只有 OpenAI 兼容路径支持真流式；其余回退非流式
        if (!"OPENAI".equals(provider) && !"BAILIAN".equals(provider)) {
            return chatWithTools(provider, model, messages, tools);
        }

        try {
            String apiKey = resolveApiKey(config);
            return callOpenAIWithToolsStream(baseUrl, apiKey, effectiveModel, messages, tools, onDelta);
        } catch (Exception e) {
            log.error("Failed to stream model API with tools for provider: {}", provider, e);
            throw new RuntimeException("AI流式调用失败: " + e.getMessage());
        }
    }

    private AiResponse callOpenAIWithToolsStream(String baseUrl, String apiKey, String model,
                                                 List<Map<String, Object>> messages,
                                                 List<Map<String, Object>> tools,
                                                 Consumer<String> onDelta) {
        String url = baseUrl + "/chat/completions";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        body.put("stream", true);
        // 让最后一帧带上 usage，便于成本闸门核算
        body.put("stream_options", Map.of("include_usage", true));

        return restTemplate.execute(url, HttpMethod.POST,
                request -> {
                    request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    request.getHeaders().setBearerAuth(apiKey);
                    request.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                    objectMapper.writeValue(request.getBody(), body);
                },
                response -> parseOpenAIStream(response.getBody(), onDelta, objectMapper));
    }

    /**
     * 解析 OpenAI 兼容的流式响应：逐行读 SSE，累积 content（并回调 onDelta）、
     * 按 index 拼接 tool_calls 分片、捕获 finish_reason 与 usage.total_tokens。
     * 提为包级静态方法便于单测（无需构造整个 service）。
     */
    static AiResponse parseOpenAIStream(InputStream in, Consumer<String> onDelta, ObjectMapper mapper)
            throws java.io.IOException {
        StringBuilder contentBuf = new StringBuilder();
        Map<Integer, String> tcId = new HashMap<>();
        Map<Integer, String> tcName = new HashMap<>();
        Map<Integer, StringBuilder> tcArgs = new HashMap<>();
        String finishReason = null;
        long usageTotal = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = mapper.readTree(data);
                } catch (Exception e) {
                    continue; // 跳过无法解析的心跳/注释帧
                }

                JsonNode usage = chunk.path("usage");
                if (usage.isObject()) {
                    long t = usage.path("total_tokens").asLong(0);
                    if (t > 0) {
                        usageTotal = t;
                    }
                }

                JsonNode choice0 = chunk.path("choices").path(0);
                if (choice0.isMissingNode()) {
                    continue;
                }
                JsonNode fr = choice0.path("finish_reason");
                if (fr.isTextual()) {
                    finishReason = fr.asText();
                }
                JsonNode delta = choice0.path("delta");

                JsonNode contentNode = delta.path("content");
                if (contentNode.isTextual()) {
                    String piece = contentNode.asText();
                    if (!piece.isEmpty()) {
                        contentBuf.append(piece);
                        if (onDelta != null) {
                            onDelta.accept(piece);
                        }
                    }
                }

                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        int idx = tc.path("index").asInt(0);
                        String id = tc.path("id").asText(null);
                        if (id != null && !id.isEmpty()) {
                            tcId.put(idx, id);
                        }
                        JsonNode fn = tc.path("function");
                        String name = fn.path("name").asText(null);
                        if (name != null && !name.isEmpty()) {
                            tcName.put(idx, name);
                        }
                        String argPiece = fn.path("arguments").asText("");
                        if (!argPiece.isEmpty()) {
                            tcArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(argPiece);
                        }
                    }
                }
            }
        }

        List<ToolCall> calls = null;
        if (!tcId.isEmpty() || !tcName.isEmpty() || !tcArgs.isEmpty()) {
            TreeSet<Integer> indices = new TreeSet<>();
            indices.addAll(tcId.keySet());
            indices.addAll(tcName.keySet());
            indices.addAll(tcArgs.keySet());
            calls = new ArrayList<>();
            for (Integer idx : indices) {
                String id = tcId.getOrDefault(idx, "call_" + idx);
                String name = tcName.getOrDefault(idx, "");
                String argsStr = tcArgs.containsKey(idx) ? tcArgs.get(idx).toString() : "{}";
                Map<String, Object> args = new LinkedHashMap<>();
                try {
                    args = mapper.readValue(argsStr.isEmpty() ? "{}" : argsStr, Map.class);
                } catch (Exception e) {
                    log.warn("Failed to parse streamed tool arguments: {}", argsStr);
                    args.put("_raw", argsStr);
                }
                calls.add(new ToolCall(id, name, args));
            }
        }

        String content = contentBuf.length() > 0 ? contentBuf.toString() : null;
        AiResponse resp = new AiResponse(content, calls, finishReason != null ? finishReason : "stop");
        resp.setTotalTokens(usageTotal);
        return resp;
    }

    // ========== OpenAI/Bailian 工具调用 ==========

    private AiResponse callOpenAIWithTools(String baseUrl, String apiKey, String model,
                                           List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // 构建请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");  // 让 AI 自动决定是否调用工具
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        return extractOpenAIResponseWithTools(response.getBody());
    }

    private AiResponse extractOpenAIResponseWithTools(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                // 提供商返回了错误体（限流/内容审核/鉴权失败等），透出原始信息便于排查，
                // 而不是在 .get(0) 上抛 NPE 后降级成含糊的"解析响应失败"。
                String err = root.path("error").path("message").asText(null);
                String detail = err != null ? err
                        : (body != null && body.length() > 500 ? body.substring(0, 500) : body);
                log.error("Model API returned no choices: {}", detail);
                return new AiResponse("AI 服务未返回有效结果：" + (detail != null ? detail : "空响应"), null, "error");
            }
            JsonNode first = choices.get(0);
            JsonNode message = first.path("message");

            String content = message.path("content").isNull() ? null : message.path("content").asText();
            String finishReason = first.path("finish_reason").asText();

            // 提取 tool_calls
            List<ToolCall> toolCalls = null;
            if (message.has("tool_calls") && !message.path("tool_calls").isNull()) {
                toolCalls = new ArrayList<>();
                JsonNode toolCallsNode = message.path("tool_calls");
                for (JsonNode tc : toolCallsNode) {
                    String id = tc.path("id").asText();
                    String name = tc.path("function").path("name").asText();
                    String argsStr = tc.path("function").path("arguments").asText();

                    // 解析 arguments JSON
                    Map<String, Object> arguments = new LinkedHashMap<>();
                    try {
                        arguments = objectMapper.readValue(argsStr, Map.class);
                    } catch (Exception e) {
                        log.warn("Failed to parse tool arguments: {}", argsStr);
                        arguments.put("_raw", argsStr);
                    }

                    toolCalls.add(new ToolCall(id, name, arguments));
                }
            }

            AiResponse openAiResp = new AiResponse(content, toolCalls, finishReason);
            openAiResp.setTotalTokens(root.path("usage").path("total_tokens").asLong(0));
            return openAiResp;

        } catch (Exception e) {
            log.error("Failed to parse OpenAI response with tools", e);
            return new AiResponse("解析响应失败: " + e.getMessage(), null, "error");
        }
    }

    // ========== Claude 工具调用 ==========

    private AiResponse callClaudeWithTools(String baseUrl, String apiKey, String model,
                                           List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools) {
        String url = baseUrl + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        // Claude 格式转换
        // Claude 的 messages 不包含 system，需要单独处理
        List<Map<String, Object>> claudeMessages = new ArrayList<>();
        String systemPrompt = null;

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            if ("system".equals(role)) {
                systemPrompt = (String) msg.get("content");
            } else {
                claudeMessages.add(msg);
            }
        }

        // Claude 工具格式转换（使用 input_schema）
        List<Map<String, Object>> claudeTools = null;
        if (tools != null && !tools.isEmpty()) {
            claudeTools = new ArrayList<>();
            for (Map<String, Object> tool : tools) {
                Map<String, Object> f = (Map<String, Object>) tool.get("function");
                Map<String, Object> claudeTool = new LinkedHashMap<>();
                claudeTool.put("name", f.get("name"));
                claudeTool.put("description", f.get("description"));
                claudeTool.put("input_schema", f.get("parameters"));
                claudeTools.add(claudeTool);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 4096);
        if (systemPrompt != null) {
            body.put("system", systemPrompt);
        }
        body.put("messages", claudeMessages);
        if (claudeTools != null) {
            body.put("tools", claudeTools);
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        return extractClaudeResponseWithTools(response.getBody());
    }

    private AiResponse extractClaudeResponseWithTools(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode contentArray = root.path("content");

            String textContent = null;
            List<ToolCall> toolCalls = null;
            String stopReason = root.path("stop_reason").asText();

            for (JsonNode block : contentArray) {
                String type = block.path("type").asText();

                if ("text".equals(type)) {
                    textContent = block.path("text").asText();
                } else if ("tool_use".equals(type)) {
                    if (toolCalls == null) {
                        toolCalls = new ArrayList<>();
                    }
                    String id = block.path("id").asText();
                    String name = block.path("name").asText();
                    Map<String, Object> input = new LinkedHashMap<>();
                    JsonNode inputNode = block.path("input");
                    if (inputNode.isObject()) {
                        input = objectMapper.convertValue(inputNode, Map.class);
                    }

                    toolCalls.add(new ToolCall(id, name, input));
                }
            }

            String finishReason = "tool_use".equals(stopReason) ? "tool_calls" : stopReason;
            AiResponse claudeResp = new AiResponse(textContent, toolCalls, finishReason);
            JsonNode usage = root.path("usage");
            claudeResp.setTotalTokens(usage.path("input_tokens").asLong(0) + usage.path("output_tokens").asLong(0));
            return claudeResp;

        } catch (Exception e) {
            log.error("Failed to parse Claude response with tools", e);
            return new AiResponse("解析响应失败: " + e.getMessage(), null, "error");
        }
    }

    // ========== Gemini 工具调用 ==========

    private AiResponse callGeminiWithTools(String baseUrl, String apiKey, String model,
                                           List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools) {
        String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Gemini 格式转换
        // Gemini 使用 contents + systemInstruction + functionDeclarations
        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> systemInstruction = null;

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");

            if ("system".equals(role)) {
                systemInstruction = Map.of("parts", List.of(Map.of("text", content)));
            } else if ("tool".equals(role)) {
                // Gemini 的工具结果格式不同
                // role: "function", parts: [{functionResponse: {name, response}}]
                String toolCallId = (String) msg.get("tool_call_id");
                // Gemini 不使用 tool_call_id，需要从内容中提取工具名
                continue;  // 工具结果由 AiCopilotService 单独处理
            } else {
                // user 或 assistant
                String geminiRole = "assistant".equals(role) ? "model" : "user";
                contents.add(Map.of(
                        "role", geminiRole,
                        "parts", List.of(Map.of("text", content))
                ));
            }
        }

        // Gemini 工具格式（functionDeclarations）
        List<Map<String, Object>> functionDeclarations = null;
        if (tools != null && !tools.isEmpty()) {
            functionDeclarations = new ArrayList<>();
            for (Map<String, Object> tool : tools) {
                Map<String, Object> f = (Map<String, Object>) tool.get("function");
                functionDeclarations.add(Map.of(
                        "name", f.get("name"),
                        "description", f.get("description"),
                        "parameters", f.get("parameters")
                ));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (systemInstruction != null) {
            body.put("systemInstruction", systemInstruction);
        }
        body.put("contents", contents);
        if (functionDeclarations != null) {
            body.put("functionDeclarations", functionDeclarations);
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        return extractGeminiResponseWithTools(response.getBody());
    }

    private AiResponse extractGeminiResponseWithTools(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode candidate = root.path("candidates").get(0);
            JsonNode contentParts = candidate.path("content").path("parts");

            String textContent = null;
            List<ToolCall> toolCalls = null;

            for (JsonNode part : contentParts) {
                if (part.has("text")) {
                    textContent = part.path("text").asText();
                } else if (part.has("functionCall")) {
                    if (toolCalls == null) {
                        toolCalls = new ArrayList<>();
                    }
                    JsonNode fc = part.path("functionCall");
                    String name = fc.path("name").asText();
                    Map<String, Object> args = new LinkedHashMap<>();
                    JsonNode argsNode = fc.path("args");
                    if (argsNode.isObject()) {
                        args = objectMapper.convertValue(argsNode, Map.class);
                    }

                    // Gemini 没有 tool_call_id，生成一个
                    String id = "gemini_" + UUID.randomUUID().toString().substring(0, 8);
                    toolCalls.add(new ToolCall(id, name, args));
                }
            }

            String finishReason = toolCalls != null ? "tool_calls" : "stop";
            AiResponse geminiResp = new AiResponse(textContent, toolCalls, finishReason);
            geminiResp.setTotalTokens(root.path("usageMetadata").path("totalTokenCount").asLong(0));
            return geminiResp;

        } catch (Exception e) {
            log.error("Failed to parse Gemini response with tools", e);
            return new AiResponse("解析响应失败: " + e.getMessage(), null, "error");
        }
    }

    // ========== 工具调用结果格式化 ==========

    /**
     * 构建 OpenAI 格式的工具结果消息
     */
    public Map<String, Object> buildOpenAIToolResultMessage(String toolCallId, String result) {
        return Map.of(
                "role", "tool",
                "tool_call_id", toolCallId,
                "content", result
        );
    }

    /**
     * 构建 Claude 格式的工具结果消息
     */
    public Map<String, Object> buildClaudeToolResultMessage(String toolUseId, String result) {
        return Map.of(
                "role", "user",
                "content", List.of(Map.of(
                        "type", "tool_result",
                        "tool_use_id", toolUseId,
                        "content", result
                ))
        );
    }

    /**
     * 构建 Gemini 格式的工具结果消息
     */
    public Map<String, Object> buildGeminiToolResultMessage(String toolName, String result) {
        try {
            Map<String, Object> response = objectMapper.readValue(result, Map.class);
            return Map.of(
                    "role", "function",
                    "parts", List.of(Map.of(
                            "functionResponse", Map.of(
                                    "name", toolName,
                                    "response", response
                            )
                    ))
            );
        } catch (Exception e) {
            return Map.of(
                    "role", "function",
                    "parts", List.of(Map.of(
                            "functionResponse", Map.of(
                                    "name", toolName,
                                    "response", Map.of("result", result)
                            )
                    ))
            );
        }
    }

    // ===================== AI 响应数据类 =====================

    /**
     * AI 响应结果（支持工具调用）
     */
    public static class AiResponse {
        private final String content;
        private final List<ToolCall> toolCalls;
        private final String finishReason;
        /** 本次调用的 token 用量（各家 usage 字段归一化后的总数），用于成本闸门核算。 */
        private long totalTokens;

        public AiResponse(String content, List<ToolCall> toolCalls, String finishReason) {
            this.content = content;
            this.toolCalls = toolCalls;
            this.finishReason = finishReason;
        }

        public String getContent() {
            return content;
        }

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public String getFinishReason() {
            return finishReason;
        }

        public long getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(long totalTokens) {
            this.totalTokens = totalTokens;
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        public boolean isFinished() {
            return "stop".equals(finishReason) || "end_turn".equals(finishReason);
        }
    }

    /**
     * 工具调用
     */
    public static class ToolCall {
        private final String id;
        private final String name;
        private final Map<String, Object> arguments;

        public ToolCall(String id, String name, Map<String, Object> arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }
    }

    // ===================== Pod 维度分析方法 =====================

    /**
     * 按 Pod 维度分析压力测试结果.
     * 分别分析每个 Pod 在压测期间的性能表现并对比.
     *
     * @param instanceId 网关实例ID
     * @param testId     压测ID（可选）
     * @param startTime  压测开始时间（时间戳，秒）
     * @param endTime    压测结束时间（时间戳，秒）
     * @param language   语言
     * @return AI分析结果
     */
    public String analyzePodStressTest(String instanceId, Long testId, long startTime, long endTime, String language) {
        AiConfig config = aiConfigRepository.findAll().stream()
                .filter(c -> c.getApiKey() != null && c.getModel() != null && c.getIsValid())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No valid AI provider configured"));

        // 获取实例信息
        var instance = gatewayInstanceService.getInstanceByInstanceId(instanceId);
        if (instance == null) {
            throw new RuntimeException("Gateway instance not found: " + instanceId);
        }

        // 获取 Pod 列表
        List<Map<String, Object>> pods = gatewayInstanceService.getInstancePods(instance.getId());
        if (pods == null || pods.isEmpty()) {
            throw new RuntimeException("No running pods found for instance: " + instanceId);
        }

        // 收集各 Pod 的监控数据
        Map<String, String> podMetricsMap = new LinkedHashMap<>();
        for (Map<String, Object> pod : pods) {
            String podName = (String) pod.get("name");
            String podIP = (String) pod.get("podIP");
            Object managementPortObj = pod.get("managementPort");
            int managementPort = managementPortObj instanceof Number ? ((Number) managementPortObj).intValue() : 9091;

            if (podIP == null) {
                log.warn("Pod {} has no IP, skipping", podName);
                continue;
            }

            String podInstance = podIP + ":" + managementPort;
            String podMetrics = aiMetricsCollector.collectPodMetricsData(startTime, endTime, podInstance, podName);
            podMetricsMap.put(podName, podMetrics);
        }

        // 构建对比分析 Prompt
        String prompt = aiPromptBuilder.buildPodComparisonPrompt(podMetricsMap, startTime, endTime, language);

        // 调用 AI 分析
        return callModelApi(config, prompt);
    }
}
