package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiConfig;
import com.leoli.gateway.admin.prompt.PromptService;
import com.leoli.gateway.admin.repository.AiConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final AiConfigRepository aiConfigRepository;
    private final PrometheusService prometheusService;
    private final PromptService promptService;  // 新增：用于动态提示词
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

    // RestTemplate with 5 minute timeout for AI API calls
    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000); // 30 seconds
        factory.setReadTimeout(300000);   // 5 minutes
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
            // 百炼平台 - 只保留最新版本模型
            "BAILIAN", List.of(
                    // Qwen 系列 (最新)
                    "qwen-max", "qwen-plus", "qwen-turbo",
                    // DeepSeek 系列 (最新)
                    "deepseek-v3", "deepseek-r1",
                    // Kimi 系列 (最新)
                    "kimi-k2", "moonshot-v1-128k",
                    // GLM 系列 (最新)
                    "glm-5", "glm-4.7", "glm-4.5"
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
        config.setApiKey(apiKey);
        config.setBaseUrl(baseUrl);
        config.setIsValid(true);
        config.setLastValidatedAt(LocalDateTime.now());

        return aiConfigRepository.save(config);
    }

    private String formatBytes(double bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = (int) (Math.log(bytes) / Math.log(1024));
        unitIndex = Math.min(unitIndex, units.length - 1);
        double value = bytes / Math.pow(1024, unitIndex);
        return String.format("%.2f %s", value, units[unitIndex]);
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
            return switch (provider) {
                case "BAILIAN" -> callOpenAIApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                case "OPENAI" -> callOpenAIApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                case "GEMINI" -> callGeminiApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                case "CLAUDE" -> callClaudeApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
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
        String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

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
            String url = baseUrl + "/models/gemini-pro:generateContent?key=" + apiKey;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

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
    public String analyzeTimeRange(String provider, long startTime, long endTime, String language) {
        AiConfig config = aiConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));

        if (config.getApiKey() == null || config.getModel() == null) {
            throw new RuntimeException("API Key or Model not configured for provider: " + provider);
        }

        // 收集时间段内的监控数据
        String metricsData = collectTimeRangeMetricsData(startTime, endTime);

        // 构建分析prompt
        String prompt = buildTimeRangeAnalysisPrompt(metricsData, startTime, endTime, language);

        // 调用AI分析
        return callModelApi(config, prompt);
    }

    /**
     * 收集时间段内的指标数据（增强版：包含压测前/后对比）
     */
    private String collectTimeRangeMetricsData(long startTime, long endTime) {
        StringBuilder sb = new StringBuilder();

        try {
            // 格式化时间
            String startTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startTime * 1000));
            String endTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(endTime * 1000));
            long durationMinutes = (endTime - startTime) / 60;

            // 计算压测前/后时间段（各5分钟）
            long baselineStartTime = startTime - 300; // 压测前5分钟
            long recoveryEndTime = endTime + 300;     // 压测后5分钟

            sb.append("【分析时间段】\n");
            sb.append("压测开始: ").append(startTimeStr).append("\n");
            sb.append("压测结束: ").append(endTimeStr).append("\n");
            sb.append("压测持续时间: ").append(durationMinutes).append(" 分钟\n");
            sb.append("基准数据时段: 压测前5分钟\n");
            sb.append("恢复数据时段: 压测后5分钟\n\n");

            // 查询时间范围内的指标
            String step = durationMinutes <= 5 ? "15s" : durationMinutes <= 30 ? "1m" : "5m";

            // ========== CPU使用率分析（含压测前/后对比）==========
            sb.append("【CPU使用率分析】\n");

            // 进程CPU使用率（process_cpu_usage）- 这是网关进程的CPU使用率
            double baselineProcessCpu = getMetricAverage(
                    "process_cpu_usage{application=\"my-gateway\"}", baselineStartTime, startTime, "15s");
            sb.append("进程CPU压测前基准: ").append(String.format("%.1f%%", baselineProcessCpu * 100)).append("\n");

            // 压测期间进程CPU
            List<Map<String, Object>> processCpuData = prometheusService.queryRange(
                    "process_cpu_usage{application=\"my-gateway\"}", startTime, endTime, step);
            double maxProcessCpu = 0, avgProcessCpu = 0;
            if (processCpuData != null && !processCpuData.isEmpty()) {
                double sumProcessCpu = 0;
                int countProcessCpu = 0;
                for (Map<String, Object> point : processCpuData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue() * 100;
                        maxProcessCpu = Math.max(maxProcessCpu, val);
                        sumProcessCpu += val;
                        countProcessCpu++;
                    }
                }
                avgProcessCpu = countProcessCpu > 0 ? sumProcessCpu / countProcessCpu : 0;
                sb.append("进程CPU压测期间峰值: ").append(String.format("%.1f%%", maxProcessCpu)).append("\n");
                sb.append("进程CPU压测期间平均: ").append(String.format("%.1f%%", avgProcessCpu)).append("\n");
            }

            // 压测后进程CPU恢复
            double recoveryProcessCpu = getMetricAverage(
                    "process_cpu_usage{application=\"my-gateway\"}", endTime, recoveryEndTime, "15s");
            sb.append("进程CPU压测后恢复: ").append(String.format("%.1f%%", recoveryProcessCpu * 100)).append("\n");

            // 系统CPU使用率（system_cpu_usage）- 这是整个系统的CPU使用率
            double baselineSystemCpu = getMetricAverage(
                    "system_cpu_usage{application=\"my-gateway\"}", baselineStartTime, startTime, "15s");
            sb.append("系统CPU压测前基准: ").append(String.format("%.1f%%", baselineSystemCpu * 100)).append("\n");

            // 压测期间系统CPU
            List<Map<String, Object>> systemCpuData = prometheusService.queryRange(
                    "system_cpu_usage{application=\"my-gateway\"}", startTime, endTime, step);
            double maxSystemCpu = 0, avgSystemCpu = 0;
            if (systemCpuData != null && !systemCpuData.isEmpty()) {
                double sumSystemCpu = 0;
                int countSystemCpu = 0;
                for (Map<String, Object> point : systemCpuData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue() * 100;
                        maxSystemCpu = Math.max(maxSystemCpu, val);
                        sumSystemCpu += val;
                        countSystemCpu++;
                    }
                }
                avgSystemCpu = countSystemCpu > 0 ? sumSystemCpu / countSystemCpu : 0;
                sb.append("系统CPU压测期间峰值: ").append(String.format("%.1f%%", maxSystemCpu)).append("\n");
                sb.append("系统CPU压测期间平均: ").append(String.format("%.1f%%", avgSystemCpu)).append("\n");
            }

            // 压测后系统CPU恢复
            double recoverySystemCpu = getMetricAverage(
                    "system_cpu_usage{application=\"my-gateway\"}", endTime, recoveryEndTime, "15s");
            sb.append("系统CPU压测后恢复: ").append(String.format("%.1f%%", recoverySystemCpu * 100)).append("\n");

            // CPU判定（使用进程CPU，因为这才是网关进程的CPU使用率）
            sb.append("\n**CPU判定说明**：\n");
            sb.append("- 进程CPU（process_cpu_usage）：网关进程本身的CPU使用率，压测期间峰值 ").append(String.format("%.1f%%", maxProcessCpu)).append("\n");
            sb.append("- 系统CPU（system_cpu_usage）：整个系统的CPU使用率，包含所有进程\n");
            if (maxProcessCpu > 95) {
                sb.append("- CPU判定: ❌ 进程CPU满载（>95%），网关进程是性能瓶颈\n");
            } else if (maxProcessCpu > 80) {
                sb.append("- CPU判定: ⚠️ 进程CPU高负载（>80%），接近瓶颈\n");
            } else {
                sb.append("- CPU判定: ✅ 进程CPU正常（<80%），无瓶颈\n");
            }
            sb.append("CPU是否恢复: ").append(recoveryProcessCpu <= baselineProcessCpu * 1.5 ? "✅ 已恢复" : "⚠️ 未完全恢复").append("\n\n");

            // ========== JVM堆内存分析（含压测前/后对比）==========
            sb.append("【JVM堆内存分析】\n");

            // 压测前基准
            double baselineHeap = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"})", baselineStartTime, startTime, "15s");
            sb.append("压测前基准: ").append(formatBytes(baselineHeap)).append("\n");

            // 压测期间
            List<Map<String, Object>> heapData = prometheusService.queryRange(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"})", startTime, endTime, step);
            double maxHeap = 0;
            if (heapData != null && !heapData.isEmpty()) {
                double sumHeap = 0;
                int countHeap = 0;
                for (Map<String, Object> point : heapData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue();
                        maxHeap = Math.max(maxHeap, val);
                        sumHeap += val;
                        countHeap++;
                    }
                }
                sb.append("压测期间峰值: ").append(formatBytes(maxHeap)).append("\n");
                sb.append("压测期间平均: ").append(countHeap > 0 ? formatBytes(sumHeap / countHeap) : "N/A").append("\n");
            }

            // 压测后恢复
            double recoveryHeap = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"})", endTime, recoveryEndTime, "15s");
            sb.append("压测后恢复: ").append(formatBytes(recoveryHeap)).append("\n");

            // 内存泄漏判定
            double heapIncreasePercent = (recoveryHeap - baselineHeap) / baselineHeap * 100;
            sb.append("内存变化率: ").append(String.format("%.1f%%", heapIncreasePercent)).append("\n");
            if (heapIncreasePercent > 30) {
                sb.append("内存泄漏判定: ❌ 高度疑似内存泄漏（压测后内存比压测前高出30%+）\n");
            } else if (heapIncreasePercent > 10) {
                sb.append("内存泄漏判定: ⚠️ 可能存在对象未释放（压测后内存比压测前高出10%-30%）\n");
            } else {
                sb.append("内存泄漏判定: ✅ 正常（压测后内存已回落到基准水平）\n");
            }
            sb.append("\n");

            // ========== HTTP请求分析 ==========
            List<Map<String, Object>> reqData = prometheusService.queryRange(
                    "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))", startTime, endTime, step);
            if (reqData != null && !reqData.isEmpty()) {
                sb.append("【HTTP请求分析】\n");
                double maxReq = 0, sumReq = 0;
                int countReq = 0;
                for (Map<String, Object> point : reqData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue();
                        maxReq = Math.max(maxReq, val);
                        sumReq += val;
                        countReq++;
                    }
                }
                sb.append("请求峰值: ").append(String.format("%.2f", maxReq)).append(" req/s\n");
                sb.append("请求平均: ").append(countReq > 0 ? String.format("%.2f", sumReq / countReq) : "N/A").append(" req/s\n\n");
            }

            // ========== 响应时间分析（含压测前/后对比）==========
            sb.append("【响应时间分析】\n");

            // 压测前基准
            double baselineResp = getMetricAverage(
                    "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))",
                    baselineStartTime, startTime, "15s");
            sb.append("压测前基准: ").append(String.format("%.2f ms", baselineResp * 1000)).append("\n");

            // 压测期间
            List<Map<String, Object>> respData = prometheusService.queryRange(
                    "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))",
                    startTime, endTime, step);
            double maxResp = 0;
            if (respData != null && !respData.isEmpty()) {
                double sumResp = 0;
                int countResp = 0;
                for (Map<String, Object> point : respData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue() * 1000;
                        maxResp = Math.max(maxResp, val);
                        sumResp += val;
                        countResp++;
                    }
                }
                sb.append("压测期间峰值: ").append(String.format("%.2f", maxResp)).append(" ms\n");
                sb.append("压测期间平均: ").append(countResp > 0 ? String.format("%.2f", sumResp / countResp) : "N/A").append(" ms\n");
            }

            // 压测后恢复
            double recoveryResp = getMetricAverage(
                    "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))",
                    endTime, recoveryEndTime, "15s");
            sb.append("压测后恢复: ").append(String.format("%.2f ms", recoveryResp * 1000)).append("\n");
            sb.append("响应时间是否恢复: ").append(recoveryResp <= baselineResp * 1.5 ? "✅ 已恢复" : "⚠️ 未完全恢复").append("\n\n");

            // ========== GC统计（增强版）==========
            sb.append("【GC详细统计】\n");

            // Young GC
            double youngGcCountBefore = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of minor GC\"}[5m]))",
                    baselineStartTime, startTime, "1m");
            double youngGcCountDuring = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of minor GC\"}[5m]))",
                    startTime, endTime, step);
            double youngGcCountAfter = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of minor GC\"}[5m]))",
                    endTime, recoveryEndTime, "1m");

            sb.append("Young GC次数（压测前5分钟）: ").append(String.format("%.0f", youngGcCountBefore)).append("\n");
            sb.append("Young GC次数（压测期间）: ").append(String.format("%.0f", youngGcCountDuring)).append("\n");
            sb.append("Young GC次数（压测后5分钟）: ").append(String.format("%.0f", youngGcCountAfter)).append("\n");

            // Full GC
            double fullGcCountBefore = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of major GC\"}[5m]))",
                    baselineStartTime, startTime, "1m");
            double fullGcCountDuring = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of major GC\"}[5m]))",
                    startTime, endTime, step);
            double fullGcCountAfter = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of major GC\"}[5m]))",
                    endTime, recoveryEndTime, "1m");

            sb.append("Full GC次数（压测前5分钟）: ").append(String.format("%.0f", fullGcCountBefore)).append("\n");
            sb.append("Full GC次数（压测期间）: ").append(String.format("%.0f", fullGcCountDuring)).append("\n");
            sb.append("Full GC次数（压测后5分钟）: ").append(String.format("%.0f", fullGcCountAfter)).append("\n");

            // GC总耗时
            List<Map<String, Object>> gcTimeData = prometheusService.queryRange(
                    "sum(increase(jvm_gc_pause_seconds_sum{application=\"my-gateway\"}[5m]))", startTime, endTime, step);
            if (gcTimeData != null && !gcTimeData.isEmpty()) {
                double totalGcTime = 0;
                for (Map<String, Object> point : gcTimeData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        totalGcTime += ((Number) valObj).doubleValue();
                    }
                }
                sb.append("GC总耗时（压测期间）: ").append(String.format("%.2f", totalGcTime)).append(" 秒\n");
                sb.append("GC频率: ").append(durationMinutes > 0 ? String.format("%.2f", totalGcTime / durationMinutes) : "N/A").append(" 秒/分钟\n");
            }

            // GC异常判定
            if (fullGcCountDuring > 3) {
                sb.append("GC判定: ⚠️ Full GC频繁（压测期间发生").append(String.format("%.0f", fullGcCountDuring)).append("次），可能存在内存压力\n");
            } else {
                sb.append("GC判定: ✅ 正常\n");
            }
            sb.append("\n");

            // ========== 线程数分析 ==========
            List<Map<String, Object>> threadData = prometheusService.queryRange(
                    "jvm_threads_live_threads{application=\"my-gateway\"}", startTime, endTime, step);
            if (threadData != null && !threadData.isEmpty()) {
                sb.append("【线程分析】\n");
                int maxThreads = 0, sumThreads = 0, countThreads = 0;
                for (Map<String, Object> point : threadData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        int val = ((Number) valObj).intValue();
                        maxThreads = Math.max(maxThreads, val);
                        sumThreads += val;
                        countThreads++;
                    }
                }
                sb.append("线程数峰值: ").append(maxThreads).append("\n");
                sb.append("线程数平均: ").append(countThreads > 0 ? String.format("%.1f", (double) sumThreads / countThreads) : "N/A").append("\n\n");
            }

            // ========== 连接池状态（新增）==========
            sb.append("【连接池状态】\n");

            // HttpClient 连接池（如果有指标）
            List<Map<String, Object>> httpPoolData = prometheusService.queryRange(
                    "httpclient_pool_connections_active{application=\"my-gateway\"}", startTime, endTime, step);
            if (httpPoolData != null && !httpPoolData.isEmpty()) {
                int maxConn = 0;
                for (Map<String, Object> point : httpPoolData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        maxConn = Math.max(maxConn, ((Number) valObj).intValue());
                    }
                }
                sb.append("HttpClient活跃连接峰值: ").append(maxConn).append("\n");
            } else {
                sb.append("HttpClient连接池: 数据不可用（需启用micrometer-observation）\n");
            }

            // Redis 连接（如果有指标）
            List<Map<String, Object>> redisConnData = prometheusService.queryRange(
                    "lettuce_connections_active{application=\"my-gateway\"}", startTime, endTime, step);
            if (redisConnData != null && !redisConnData.isEmpty()) {
                int maxRedisConn = 0;
                for (Map<String, Object> point : redisConnData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        maxRedisConn = Math.max(maxRedisConn, ((Number) valObj).intValue());
                    }
                }
                sb.append("Redis活跃连接峰值: ").append(maxRedisConn).append("\n");
            } else {
                sb.append("Redis连接池: 数据不可用\n");
            }
            sb.append("\n");

            // ========== 汇总对比表 ==========
            sb.append("【压测前后对比汇总】\n");
            sb.append("| 指标 | 压测前基准 | 压测峰值 | 压测后恢复 | 变化率 |\n");
            sb.append("|------|-----------|---------|-----------|-------|\n");
            sb.append(String.format("| 进程CPU | %.1f%% | %.1f%% | %.1f%% | %.1f%% |\n",
                    baselineProcessCpu * 100, maxProcessCpu, recoveryProcessCpu * 100,
                    baselineProcessCpu > 0 ? (recoveryProcessCpu - baselineProcessCpu) / baselineProcessCpu * 100 : 0));
            sb.append(String.format("| 系统CPU | %.1f%% | %.1f%% | %.1f%% | %.1f%% |\n",
                    baselineSystemCpu * 100, maxSystemCpu, recoverySystemCpu * 100,
                    baselineSystemCpu > 0 ? (recoverySystemCpu - baselineSystemCpu) / baselineSystemCpu * 100 : 0));
            sb.append(String.format("| 内存 | %s | %s | %s | %.1f%% |\n",
                    formatBytes(baselineHeap), formatBytes(maxHeap > 0 ? maxHeap : baselineHeap), formatBytes(recoveryHeap), heapIncreasePercent));
            sb.append(String.format("| 响应时间 | %.2fms | %.2fms | %.2fms | %.1f%% |\n",
                    baselineResp * 1000, maxResp, recoveryResp * 1000,
                    baselineResp > 0 ? (recoveryResp - baselineResp) / baselineResp * 100 : 0));
            sb.append(String.format("| Young GC | %.0f次 | %.0f次 | %.0f次 | - |\n",
                    youngGcCountBefore, youngGcCountDuring, youngGcCountAfter));
            sb.append(String.format("| Full GC | %.0f次 | %.0f次 | %.0f次 | - |\n",
                    fullGcCountBefore, fullGcCountDuring, fullGcCountAfter));

        } catch (Exception e) {
            log.error("Failed to collect time range metrics data", e);
            sb.append("数据收集失败: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 获取指标在时间段内的平均值
     */
    private double getMetricAverage(String query, long startTime, long endTime, String step) {
        try {
            List<Map<String, Object>> data = prometheusService.queryRange(query, startTime, endTime, step);
            if (data == null || data.isEmpty()) return 0;

            double sum = 0;
            int count = 0;
            for (Map<String, Object> point : data) {
                Object valObj = point.get("value");
                if (valObj instanceof Number) {
                    sum += ((Number) valObj).doubleValue();
                    count++;
                }
            }
            return count > 0 ? sum / count : 0;
        } catch (Exception e) {
            log.warn("Failed to get metric average for query: {}", query);
            return 0;
        }
    }

    /**
     * 获取指标在时间段内的累计值
     */
    private double getMetricSum(String query, long startTime, long endTime, String step) {
        try {
            List<Map<String, Object>> data = prometheusService.queryRange(query, startTime, endTime, step);
            if (data == null || data.isEmpty()) return 0;

            double sum = 0;
            for (Map<String, Object> point : data) {
                Object valObj = point.get("value");
                if (valObj instanceof Number) {
                    sum += ((Number) valObj).doubleValue();
                }
            }
            return sum;
        } catch (Exception e) {
            log.warn("Failed to get metric sum for query: {}", query);
            return 0;
        }
    }

    /**
     * 构建时间段分析prompt
     */
    private String buildTimeRangeAnalysisPrompt(String metricsData, long startTime, long endTime, String language) {
        String langName = "zh".equals(language) ? "中文" : "English";
        long durationMinutes = (endTime - startTime) / 60;

        return String.format("""
                你是一个专业的Java应用运维专家和性能分析师。请分析以下Gateway网关在指定时间段内的监控数据。
                            
                这段时间可能进行了压力测试或遇到了性能问题，请仔细分析各项指标的峰值、平均值和变化趋势。
                            
                请用%s回答。
                            
                %s
                            
                请输出详细的分析报告，包含：
                            
                ## 1. 时间段概览
                - 分析时间段的基本情况
                            
                ## 2. 性能分析
                - 各指标（CPU、内存、请求、响应时间）的峰值和平均值分析
                - 是否存在性能瓶颈
                - 指标之间的关联性（如CPU和请求量的关系）
                            
                ## 3. 问题诊断
                - 发现的异常或问题
                - 可能的根本原因分析
                - 对比正常情况，指出异常点
                            
                ## 4. 性能评估
                - 系统在此时间段的整体表现评分（1-10分）
                - 吞吐量评估
                - 资源利用率评估
                            
                ## 5. 优化建议
                - 针对发现的问题提出具体的优化建议
                - 配置调整建议
                - 容量规划建议
                            
                请用Markdown格式输出，要专业、详细、有数据支撑。
                """, langName, metricsData);
    }

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
        String metricsData = collectTimeRangeMetricsData(startTime, endTime);

        // 构建压测专用分析 prompt
        String prompt = buildStressTestAnalysisPrompt(stressTestData, metricsData, language);

        return callModelApi(config, prompt);
    }

    /**
     * 构建压力测试分析 prompt
     * Uses PromptService for dynamic configuration, falls back to inline defaults.
     */
    private String buildStressTestAnalysisPrompt(String stressTestData, String metricsData, String language) {
        // 尝试从 PromptService 获取模板
        String templateKey = "task.stressTestAnalysis." + language;
        String template = promptService.getPrompt(templateKey);

        if (template != null && !template.isEmpty()) {
            // 使用动态模板
            String langName = "zh".equals(language) ? "中文" : "English";
            return template
                    .replace("{langName}", langName)
                    .replace("{stressTestData}", stressTestData)
                    .replace("{metricsData}", metricsData.isEmpty() ? "【服务器监控数据】\n暂无 Prometheus 监控数据" : metricsData);
        }

        // 默认内联提示词（兜底）- 与数据库模板保持完全一致
        String langName = "zh".equals(language) ? "中文" : "English";

        return String.format("""
                                你是一个专业的性能测试工程师和Java应用运维专家。请根据以下压力测试结果和服务器监控数据，给出**严格、深入**的性能分析报告。
                                
                                请用%s回答。
                                
                                %s
                                
                                %s
                                
                                ---
                                
                                ## ⚠️ 分析要求（强制执行）

                                **【依赖项说明 - 重要】**
                                分析时必须区分核心性能和扩展功能性能：
                                - **数据库**：仅用于 gateway-admin 配置存储，**不影响网关运行时性能**。网关从 Nacos/Consul 获取配置，数据库问题只影响管理后台。
                                - **Redis**：仅用于分布式限流，如未配置或不可用，网关自动使用本地限流，**不影响核心路由转发**。
                                - **Nacos/Consul**：服务发现和配置中心，是网关核心依赖，如不可用会影响路由。
                                
                                **分析原则**：
                                - 网关核心性能 = 路由转发 + 过滤器链 + 负载均衡
                                - 数据库/Redis 问题属于"扩展功能性能"，不应作为核心瓶颈判定依据
                                
                                **【CPU 指标说明 - 重要】**
                                监控数据包含两种 CPU 指标，必须正确理解：
                                - **进程CPU（process_cpu_usage）**：网关进程本身的 CPU 使用率，这才是判断网关性能瓶颈的关键指标
                                - **系统CPU（system_cpu_usage）**：整个操作系统的 CPU 使用率，包含所有进程
                                
                                **CPU 满载判定规则**（使用进程CPU）：
                                - 进程CPU > 95% → ❌ 网关进程满载，是性能瓶颈
                                - 进程CPU > 80% → ⚠️ 网关进程高负载，接近瓶颈
                                - 进程CPU < 80% → ✅ 网关进程正常，无瓶颈
                                
                                **注意**：不要混淆进程CPU和系统CPU！如果进程CPU很低但系统CPU很高，说明瓶颈在其他进程而非网关。
                                
                                **【内存泄漏检测 - 最高优先级】**
                                必须对比压测前、压测中、压测后的内存变化：
                                1. 压测前内存基准值（取压测开始前5分钟的平均值）
                                2. 压测期间内存峰值
                                3. **压测结束后内存值**（取压测结束后5分钟的平均值）
                                
                                **判定规则**：
                                - ✅ 正常：压测后内存回落到压测前水平 ±10%
                                - ⚠️ 警告：压测后内存比压测前高出 10%-30%，可能存在对象未及时释放
                                - ❌ 严重：压测后内存比压测前高出 30%+，**高度疑似内存泄漏**
                                
                                如果发现内存未回落，必须：
                                - 明确指出"内存泄漏风险"
                                - 建议进行堆转储分析（jmap -histo:live）
                                - 建议检查连接池、缓存、ThreadLocal 是否正确关闭
                                
                                **【响应时间异常检测】**
                                对比压测前后的响应时间：
                                - 如果压测后响应时间明显高于压测前，说明系统未完全恢复
                                - 分析是否存在请求排队、连接池耗尽等问题
                                
                                ---
                                
                                ## 输出格式（严格遵循）
                                
                                ### 1. 压测核心指标对比表
                                
                                | 指标 | 压测前 | 压测峰值 | 压测后 | 评价 |
                                |------|--------|----------|--------|------|
                                | 进程CPU | X%% | Y%% | Z%% | 是否满载 |
                                | 系统CPU | X%% | Y%% | Z%% | 整体负载 |
                                | 内存 | X GB | Y GB | Z GB | **是否泄漏** |
                                | 响应时间 | X ms | Y ms | Z ms | 是否恢复 |
                                | 错误率 | 0%% | Y%% | Z%% | 是否有错误 |
                                
                                ### 2. 进程CPU专项分析（必填 - 最高优先级）

                **进程CPU变化趋势**：
                - 压测前基准：X%
                - 压测峰值：Y%
                - 压测后恢复：Z%

                **进程CPU判定**（必须明确输出以下结论之一）：
                - [ ] 进程CPU < 80% → ✅ **网关进程正常，无瓶颈**
                - [ ] 进程CPU 80%-95% → ⚠️ **网关进程高负载，接近瓶颈**
                - [ ] 进程CPU > 95% → ❌ **网关进程满载，是性能瓶颈**

                **重要**：如果进程CPU < 80%，必须明确结论"网关不是性能瓶颈"！

                ### 3. 系统CPU分析

                **系统CPU变化趋势**：
                - 压测前基准：X%
                - 压测峰值：Y%
                - 压测后恢复：Z%

                **系统CPU判定**：
                - 如果系统CPU > 95% 但进程CPU < 80% → ⚠️ **系统整体负载高，但瓶颈不在网关，可能在其他进程（如压测客户端、数据库、Redis等）**

                ### 4. 内存专项分析（必填）
                                
                                **内存变化趋势**：
                                - 压测前基准：X GB
                                - 压测峰值：Y GB（上升 Z%%）
                                - 压测后状态：W GB
                                
                                **内存泄漏判定**：
                                - [ ] 压测后内存已回落到基准水平 ✅
                                - [ ] 压测后内存仍高于基准 10%%+ ⚠️ 需关注
                                - [ ] 压测后内存仍高于基准 30%%+ ❌ **疑似内存泄漏**
                                
                                **如果疑似泄漏，列出可能原因**：
                                1. 连接池未正确关闭（HttpClient、Redis、DB）
                                2. 大对象缓存未清理
                                3. ThreadLocal 未 remove
                                4. 响应体未完全消费
                                
                                ### 5. 响应时间分布分析
                                
                                分析 P50/P90/P95/P99 是否合理：
                                - P99 > 3×P50 → 存在长尾延迟问题
                                - 最大响应时间 > 5×P99 → 存在极端慢请求
                                
                                ### 6. 吞吐量瓶颈分析
                                
                                计算系统承载能力：
                                - 当前配置下最大 QPS = 实测QPS × 安全系数(0.8)
                                - CPU 满载时理论 QPS 上限
                                - 是否需要扩容
                                
                                ### 7. 问题诊断（按严重程度排序）
                                
                                | 问题 | 严重程度 | 可能原因 | 建议措施 |
                                |------|----------|----------|----------|
                                | 进程CPU满载 | HIGH/MEDIUM/LOW | ... | ... |
                                | 内存未回落 | ... | ... | ... |
                                | 响应时间长尾 | ... | ... | ... |
                                
                                ### 8. 性能评分（严格扣分）
                                
                                **评分规则**：
                                - 基准分：100
                                - 进程CPU满载（>95%%持续）：-20 分
                                - 内存泄漏（压测后 > 压测前 30%%）：-30 分
                                - 内存未完全回落（10%%-30%%）：-15 分
                                - 错误率 > 0.1%%：-10 分
                                - 响应时间 P99 > 500ms：-10 分
                                - Redis/DB 延迟 WARNING：-5 分
                                
                                **最终评分**：X/100 分
                                
                                **评分解读**：
                                - 90-100：优秀，系统健康
                                - 70-89：良好，有小问题需关注
                                - 50-69：一般，存在明显问题需优化
                                - <50：差，存在严重问题需立即处理
                                
                                ### 7. 优化建议（按优先级）
                                
                                **高优先级（立即处理）**：
                                1. [如果有内存泄漏] 执行堆分析，定位泄漏对象
                                2. [如果CPU满载] 扩容或优化代码
                                
                                **中优先级（一周内）**：
                                1. 调整 JVM 参数（如有 GC 问题）
                                2. 优化连接池配置
                                
                                **低优先级（持续优化）**：
                                1. 监控告警阈值调整
                                2. 性能基线建立
                                
                                ---
                                
                                ## 📋 健康状态总结
                                
                                | 组件 | 状态 | 说明 |
                                |------|------|------|
                                | 内存 | ✅/⚠️/❌ | 是否泄漏/是否回落 |
                                | CPU | ✅/⚠️/❌ | 是否满载 |
                                | 响应时间 | ✅/⚠️/❌ | 是否恢复 |
                                | 错误率 | ✅/⚠️/❌ | 是否有错误 |
                                
                                **总体结论**：系统在压测后 [已完全恢复 / 存在遗留问题需关注 / 存在严重问题需立即处理]
                                
                                ---
                                
                                请用 Markdown 格式输出，分析要**严格、有数据支撑、不回避问题**。如果某些监控数据缺失，请明确标注"数据缺失"并基于已有数据进行分析。
                                """, langName, stressTestData, metricsData.isEmpty() ? "【服务器监控数据】\n暂无 Prometheus 监控数据" : metricsData);
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
            return switch (provider) {
                case "BAILIAN" -> callOpenAIWithTools(baseUrl, config.getApiKey(), effectiveModel, messages, tools);
                case "OPENAI" -> callOpenAIWithTools(baseUrl, config.getApiKey(), effectiveModel, messages, tools);
                case "CLAUDE" -> callClaudeWithTools(baseUrl, config.getApiKey(), effectiveModel, messages, tools);
                case "GEMINI" -> callGeminiWithTools(baseUrl, config.getApiKey(), effectiveModel, messages, tools);
                default -> throw new RuntimeException("Unsupported provider: " + provider);
            };
        } catch (Exception e) {
            log.error("Failed to call model API with tools for provider: {}", provider, e);
            throw new RuntimeException("AI调用失败: " + e.getMessage());
        }
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
            JsonNode message = root.path("choices").get(0).path("message");

            String content = message.path("content").isNull() ? null : message.path("content").asText();
            String finishReason = root.path("choices").get(0).path("finish_reason").asText();

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

            return new AiResponse(content, toolCalls, finishReason);

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
            return new AiResponse(textContent, toolCalls, finishReason);

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
            return new AiResponse(textContent, toolCalls, finishReason);

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
}