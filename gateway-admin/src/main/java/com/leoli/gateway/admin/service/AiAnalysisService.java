package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiConfig;
import com.leoli.gateway.admin.repository.AiConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 提供商显示名称
    private static final Map<String, String> PROVIDER_NAMES = Map.of(
        "OPENAI", "OpenAI",
        "GEMINI", "Google Gemini",
        "CLAUDE", "Anthropic Claude",
        "QWEN", "通义千问",
        "DEEPSEEK", "DeepSeek",
        "KIMI", "Kimi Moonshot",
        "GLM", "智谱GLM"
    );

    // 提供商区域
    private static final Map<String, String> PROVIDER_REGIONS = Map.of(
        "OPENAI", "OVERSEAS",
        "GEMINI", "OVERSEAS",
        "CLAUDE", "OVERSEAS",
        "QWEN", "DOMESTIC",
        "DEEPSEEK", "DOMESTIC",
        "KIMI", "DOMESTIC",
        "GLM", "DOMESTIC"
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
        "QWEN", "https://dashscope.aliyuncs.com/api/v1",
        "DEEPSEEK", "https://api.deepseek.com/v1",
        "KIMI", "https://api.moonshot.cn/v1",
        "GLM", "https://open.bigmodel.cn/api/paas/v4"
    );
    
    // 各提供商支持的模型
    private static final Map<String, List<String>> PROVIDER_MODELS = Map.of(
        "OPENAI", List.of("gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo"),
        "GEMINI", List.of("gemini-pro", "gemini-1.5-pro", "gemini-1.5-flash"),
        "CLAUDE", List.of("claude-3-opus", "claude-3-sonnet", "claude-3-haiku"),
        "QWEN", List.of("qwen-turbo", "qwen-plus", "qwen-max", "qwen-max-longcontext"),
        "DEEPSEEK", List.of("deepseek-chat", "deepseek-coder"),
        "KIMI", List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
        "GLM", List.of("glm-4", "glm-3-turbo")
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
                case "QWEN":
                    return validateQwenKey(url, apiKey);
                case "OPENAI":
                    return validateOpenAIKey(url, apiKey);
                case "DEEPSEEK":
                    return validateDeepSeekKey(url, apiKey);
                case "KIMI":
                    return validateKimiKey(url, apiKey);
                case "GLM":
                    return validateGlmeKey(url, apiKey);
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
    
    /**
     * 执行AI分析
     */
    public String analyze(String provider, String language) {
        // 获取配置
        AiConfig config = aiConfigRepository.findByProvider(provider)
            .orElseThrow(() -> new RuntimeException("Provider not found: " + provider));
        
        if (config.getApiKey() == null || config.getModel() == null) {
            throw new RuntimeException("API Key or Model not configured for provider: " + provider);
        }
        
        // 收集监控数据
        String metricsData = collectMetricsData();
        
        // 构建prompt
        String prompt = buildPrompt(metricsData, language);
        
        // 调用大模型API
        return callModelApi(config, prompt);
    }
    
    /**
     * 收集监控数据
     */
    private String collectMetricsData() {
        StringBuilder sb = new StringBuilder();
        
        try {
            Map<String, Object> metrics = prometheusService.getGatewayMetrics();
            log.info("Collected metrics keys: {}", metrics.keySet());
            
            // JVM内存
            Map<String, Object> jvmMemory = (Map<String, Object>) metrics.get("jvmMemory");
            if (jvmMemory != null) {
                sb.append("【JVM内存】\n");
                sb.append("堆内存已用: ").append(formatBytes(getDoubleValue(jvmMemory, "heapUsed"))).append("\n");
                sb.append("堆内存最大: ").append(formatBytes(getDoubleValue(jvmMemory, "heapMax"))).append("\n");
                sb.append("堆内存使用率: ").append(String.format("%.1f%%", getDoubleValue(jvmMemory, "heapUsagePercent"))).append("\n");
                sb.append("非堆内存: ").append(formatBytes(getDoubleValue(jvmMemory, "nonHeapUsed"))).append("\n");
            }
            
            // GC
            Map<String, Object> gc = (Map<String, Object>) metrics.get("gc");
            log.info("GC data: {}", gc);
            if (gc != null) {
                sb.append("\n【GC统计】\n");
                sb.append("GC次数: ").append(String.format("%.1f", getDoubleValue(gc, "gcCount"))).append("\n");
                sb.append("GC总耗时: ").append(String.format("%.3f", getDoubleValue(gc, "gcTimeSeconds"))).append(" 秒\n");
                sb.append("GC开销: ").append(String.format("%.2f%%", getDoubleValue(gc, "gcOverheadPercent"))).append("\n");
            }
            
            // CPU
            Map<String, Object> cpu = (Map<String, Object>) metrics.get("cpu");
            if (cpu != null) {
                sb.append("\n【CPU使用率】\n");
                sb.append("进程CPU: ").append(String.format("%.1f%%", getDoubleValue(cpu, "processUsage"))).append("\n");
                sb.append("系统CPU: ").append(String.format("%.1f%%", getDoubleValue(cpu, "systemUsage"))).append("\n");
                sb.append("可用处理器: ").append(cpu.getOrDefault("availableProcessors", "N/A")).append("\n");
            }
            
            // HTTP请求
            Map<String, Object> http = (Map<String, Object>) metrics.get("httpRequests");
            if (http != null) {
                sb.append("\n【HTTP请求】\n");
                sb.append("每秒请求数: ").append(String.format("%.2f", getDoubleValue(http, "requestsPerSecond"))).append("\n");
                sb.append("平均响应时间: ").append(String.format("%.2f", getDoubleValue(http, "avgResponseTimeMs"))).append(" ms\n");
                sb.append("错误率: ").append(String.format("%.2f%%", getDoubleValue(http, "errorRate"))).append("\n");
            }
            
            // 线程
            Map<String, Object> threads = (Map<String, Object>) metrics.get("threads");
            log.info("Threads data: {}", threads);
            if (threads != null) {
                sb.append("\n【线程】\n");
                sb.append("活跃线程: ").append(getIntValue(threads, "liveThreads")).append("\n");
                sb.append("守护线程: ").append(getIntValue(threads, "daemonThreads")).append("\n");
                sb.append("峰值线程: ").append(getIntValue(threads, "peakThreads")).append("\n");
            }
            
        } catch (Exception e) {
            log.error("Failed to collect metrics data", e);
            sb.append("数据收集失败: ").append(e.getMessage());
        }
        
        return sb.toString();
    }
    
    private double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }
    
    private int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
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
     * 构建分析提示词
     */
    private String buildPrompt(String metricsData, String language) {
        boolean isEnglish = !"zh".equals(language);
        
        if (isEnglish) {
            return String.format("""
                You are a professional Java application operations expert. Please analyze the following Gateway Prometheus monitoring data and provide system health assessment and optimization suggestions.
                
                Please respond in English only.
                
                %s
                
                Please output an analysis report containing:
                1. Overall health status (Healthy/Warning/Critical)
                2. Metrics analysis (Memory, GC, CPU, Requests, Threads)
                3. Issues found (if any)
                4. Optimization suggestions (if any issues)
                5. Predicted risks (based on current data)
                
                Please output in Markdown format, be professional, detailed, and data-supported.
                """, metricsData);
        } else {
            return String.format("""
                你是一个专业的Java应用运维专家。请分析以下Gateway网关的Prometheus监控数据，给出系统健康状态评估和优化建议。
                
                请用中文回答。
                
                %s
                
                请输出分析报告，包含：
                1. 整体健康状态（健康/警告/危险）
                2. 各指标分析（内存、GC、CPU、请求、线程）
                3. 发现的问题（如有）
                4. 优化建议（如有问题）
                5. 预测风险（基于当前数据预测可能的风险）
                
                请用Markdown格式输出，要专业、详细、有数据支撑。
                """, metricsData);
        }
    }
    
    /**
     * 调用大模型API
     */
    private String callModelApi(AiConfig config, String prompt) {
        String provider = config.getProvider();
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URLS.get(provider);
        
        try {
            return switch (provider) {
                case "QWEN" -> callQwenApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                case "OPENAI" -> callOpenAIApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                case "DEEPSEEK" -> callDeepSeekApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                case "KIMI" -> callKimiApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                case "GLM" -> callGlmApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
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
    
    private String callQwenApi(String baseUrl, String apiKey, String model, String prompt) {
        String url = baseUrl + "/services/aigc/text-generation/generation";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", Map.of("messages", List.of(
            Map.of("role", "system", "content", "你是一个专业的Java应用运维专家。"),
            Map.of("role", "user", "content", prompt)
        )));
        
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        
        return extractQwenResponse(response.getBody());
    }
    
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
    
    private String callDeepSeekApi(String baseUrl, String apiKey, String model, String prompt) {
        // DeepSeek API兼容OpenAI格式
        return callOpenAIApi(baseUrl, apiKey, model, prompt);
    }
    
    private String callKimiApi(String baseUrl, String apiKey, String model, String prompt) {
        // Kimi API兼容OpenAI格式
        return callOpenAIApi(baseUrl, apiKey, model, prompt);
    }
    
    private String callGlmApi(String baseUrl, String apiKey, String model, String prompt) {
        String url = baseUrl + "/chat/completions";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(
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
     * @param provider AI提供商
     * @param startTime 开始时间（时间戳，秒）
     * @param endTime 结束时间（时间戳，秒）
     * @param language 语言
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
     * 收集时间段内的指标数据
     */
    private String collectTimeRangeMetricsData(long startTime, long endTime) {
        StringBuilder sb = new StringBuilder();
        
        try {
            // 格式化时间
            String startTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startTime * 1000));
            String endTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(endTime * 1000));
            long durationMinutes = (endTime - startTime) / 60;
            
            sb.append("【分析时间段】\n");
            sb.append("开始时间: ").append(startTimeStr).append("\n");
            sb.append("结束时间: ").append(endTimeStr).append("\n");
            sb.append("持续时间: ").append(durationMinutes).append(" 分钟\n\n");
            
            // 查询时间范围内的指标
            String step = durationMinutes <= 5 ? "15s" : durationMinutes <= 30 ? "1m" : "5m";
            
            // CPU使用率峰值和平均值
            List<Map<String, Object>> cpuData = prometheusService.queryRange(
                "system_cpu_usage{application=\"my-gateway\"}", startTime, endTime, step);
            if (cpuData != null && !cpuData.isEmpty()) {
                sb.append("【CPU使用率分析】\n");
                double maxCpu = 0, sumCpu = 0;
                int countCpu = 0;
                for (Map<String, Object> point : cpuData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue() * 100;
                        maxCpu = Math.max(maxCpu, val);
                        sumCpu += val;
                        countCpu++;
                    }
                }
                sb.append("CPU峰值: ").append(String.format("%.1f%%", maxCpu)).append("\n");
                sb.append("CPU平均: ").append(countCpu > 0 ? String.format("%.1f%%", sumCpu / countCpu) : "N/A").append("\n\n");
            }
            
            // JVM堆内存使用
            List<Map<String, Object>> heapData = prometheusService.queryRange(
                "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"})", startTime, endTime, step);
            if (heapData != null && !heapData.isEmpty()) {
                sb.append("【JVM堆内存分析】\n");
                double maxHeap = 0, sumHeap = 0;
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
                sb.append("堆内存峰值: ").append(formatBytes(maxHeap)).append("\n");
                sb.append("堆内存平均: ").append(countHeap > 0 ? formatBytes(sumHeap / countHeap) : "N/A").append("\n\n");
            }
            
            // HTTP请求速率
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

            // 响应时间
            List<Map<String, Object>> respData = prometheusService.queryRange(
                "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))",
                startTime, endTime, step);
            if (respData != null && !respData.isEmpty()) {
                sb.append("【响应时间分析】\n");
                double maxResp = 0, sumResp = 0;
                int countResp = 0;
                for (Map<String, Object> point : respData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue() * 1000; // 转换为ms
                        maxResp = Math.max(maxResp, val);
                        sumResp += val;
                        countResp++;
                    }
                }
                sb.append("响应时间峰值: ").append(String.format("%.2f", maxResp)).append(" ms\n");
                sb.append("响应时间平均: ").append(countResp > 0 ? String.format("%.2f", sumResp / countResp) : "N/A").append(" ms\n\n");
            }

            // GC统计
            List<Map<String, Object>> gcTimeData = prometheusService.queryRange(
                "sum(increase(jvm_gc_pause_seconds_sum{application=\"my-gateway\"}[5m]))", startTime, endTime, step);
            if (gcTimeData != null && !gcTimeData.isEmpty()) {
                sb.append("【GC统计】\n");
                double totalGcTime = 0;
                for (Map<String, Object> point : gcTimeData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        totalGcTime += ((Number) valObj).doubleValue();
                    }
                }
                sb.append("GC总耗时: ").append(String.format("%.2f", totalGcTime)).append(" 秒\n");
                sb.append("GC频率: ").append(durationMinutes > 0 ? String.format("%.2f", totalGcTime / durationMinutes) : "N/A").append(" 秒/分钟\n\n");
            }

            // 线程数
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
                sb.append("线程数平均: ").append(countThreads > 0 ? String.format("%.1f", (double)sumThreads / countThreads) : "N/A").append("\n");
            }
            
        } catch (Exception e) {
            log.error("Failed to collect time range metrics data", e);
            sb.append("数据收集失败: ").append(e.getMessage());
        }
        
        return sb.toString();
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
}