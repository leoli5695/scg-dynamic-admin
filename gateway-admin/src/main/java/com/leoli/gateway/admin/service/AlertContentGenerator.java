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

import java.util.*;

/**
 * Alert content generator using AI.
 * Generates intelligent alert email content with analysis and recommendations.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertContentGenerator {

    private final AiConfigRepository aiConfigRepository;
    private final PrometheusService prometheusService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(60000); // 1 minute for alert generation
        return new RestTemplate(factory);
    }

    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
        "OPENAI", "https://api.openai.com/v1",
        "QWEN", "https://dashscope.aliyuncs.com/api/v1",
        "DEEPSEEK", "https://api.deepseek.com/v1",
        "KIMI", "https://api.moonshot.cn/v1",
        "GLM", "https://open.bigmodel.cn/api/paas/v4"
    );

    /**
     * Generate alert content using AI.
     *
     * @param alertType    Alert type (CPU, MEMORY, HTTP_ERROR, etc.)
     * @param metricName   Metric name
     * @param currentValue Current metric value
     * @param threshold    Threshold value
     * @param language     Language (zh or en)
     * @return Generated alert content
     */
    public String generateAlertContent(String alertType, String metricName,
                                        double currentValue, double threshold,
                                        String language) {
        try {
            // Get first valid AI config
            Optional<AiConfig> configOpt = aiConfigRepository.findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsValid()) && c.getApiKey() != null)
                .findFirst();

            if (configOpt.isEmpty()) {
                log.warn("No valid AI config found, using default content");
                return generateDefaultContent(alertType, metricName, currentValue, threshold, language);
            }

            AiConfig config = configOpt.get();

            // Collect current metrics for context
            String metricsContext = collectMetricsContext();

            // Build prompt
            String prompt = buildPrompt(alertType, metricName, currentValue, threshold, metricsContext, language);

            // Call AI API
            return callAiApi(config, prompt);

        } catch (Exception e) {
            log.error("Failed to generate AI content, using default", e);
            return generateDefaultContent(alertType, metricName, currentValue, threshold, language);
        }
    }

    /**
     * Build the prompt for alert analysis.
     */
    private String buildPrompt(String alertType, String metricName,
                               double currentValue, double threshold,
                               String metricsContext, String language) {
        if ("zh".equals(language)) {
            return String.format("""
                你是网关运维专家。以下指标超过阈值，请分析可能原因并给出建议。

                告警类型: %s
                告警指标: %s
                当前值: %.2f
                阈值: %.2f

                系统上下文:
                %s

                请用简洁专业的语言（不超过200字）分析原因并给出处理建议。
                直接输出分析内容，不要添加标题或其他格式。
                """, alertType, metricName, currentValue, threshold, metricsContext);
        } else {
            return String.format("""
                You are a gateway operations expert. The following metric exceeded the threshold.

                Alert Type: %s
                Alert Metric: %s
                Current Value: %.2f
                Threshold: %.2f

                System Context:
                %s

                Please analyze the possible causes and provide recommendations in a concise manner (no more than 200 words).
                Output the analysis directly without titles or other formatting.
                """, alertType, metricName, currentValue, threshold, metricsContext);
        }
    }

    /**
     * Collect current metrics for context.
     */
    private String collectMetricsContext() {
        StringBuilder sb = new StringBuilder();

        try {
            Map<String, Object> metrics = prometheusService.getGatewayMetrics();

            // CPU
            Map<String, Object> cpu = (Map<String, Object>) metrics.get("cpu");
            if (cpu != null) {
                sb.append("- CPU使用率: 进程 ").append(String.format("%.1f%%", getDoubleValue(cpu, "processUsage")))
                  .append(", 系统 ").append(String.format("%.1f%%", getDoubleValue(cpu, "systemUsage"))).append("\n");
            }

            // Memory
            Map<String, Object> memory = (Map<String, Object>) metrics.get("jvmMemory");
            if (memory != null) {
                sb.append("- 堆内存使用率: ").append(String.format("%.1f%%", getDoubleValue(memory, "heapUsagePercent"))).append("\n");
            }

            // HTTP
            Map<String, Object> http = (Map<String, Object>) metrics.get("httpRequests");
            if (http != null) {
                sb.append("- HTTP错误率: ").append(String.format("%.2f%%", getDoubleValue(http, "errorRate"))).append("\n");
                sb.append("- 平均响应时间: ").append(String.format("%.0fms", getDoubleValue(http, "avgResponseTimeMs"))).append("\n");
            }

            // Threads
            Map<String, Object> threads = (Map<String, Object>) metrics.get("threads");
            if (threads != null) {
                sb.append("- 活跃线程数: ").append(getIntValue(threads, "liveThreads")).append("\n");
            }

            // Instances
            List<Map<String, Object>> instances = (List<Map<String, Object>>) metrics.get("instances");
            if (instances != null) {
                long unhealthy = instances.stream().filter(i -> !"UP".equals(i.get("status"))).count();
                sb.append("- 实例状态: ").append(instances.size() - unhealthy).append("/").append(instances.size()).append(" 健康\n");
            }

        } catch (Exception e) {
            log.error("Failed to collect metrics context", e);
        }

        return sb.toString();
    }

    /**
     * Generate default content when AI is not available.
     */
    private String generateDefaultContent(String alertType, String metricName,
                                          double currentValue, double threshold,
                                          String language) {
        if ("zh".equals(language)) {
            return String.format(
                "告警指标 %s 超过阈值。当前值: %.2f, 阈值: %.2f。请检查系统状态并及时处理。",
                metricName, currentValue, threshold
            );
        } else {
            return String.format(
                "Alert metric %s exceeded threshold. Current value: %.2f, Threshold: %.2f. Please check system status and take action.",
                metricName, currentValue, threshold
            );
        }
    }

    /**
     * Call AI API based on provider.
     */
    private String callAiApi(AiConfig config, String prompt) {
        String provider = config.getProvider();
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URLS.getOrDefault(provider, "");

        try {
            return switch (provider) {
                case "QWEN" -> callQwenApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                case "OPENAI", "DEEPSEEK", "KIMI" -> callOpenAICompatibleApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                case "GLM" -> callGlmApi(baseUrl, config.getApiKey(), config.getModel(), prompt);
                default -> throw new RuntimeException("Unsupported provider: " + provider);
            };
        } catch (Exception e) {
            log.error("Failed to call AI API for provider: {}", provider, e);
            throw new RuntimeException("AI API call failed: " + e.getMessage());
        }
    }

    private String callOpenAICompatibleApi(String baseUrl, String apiKey, String model, String prompt) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            ),
            "max_tokens", 500
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        return extractOpenAIResponse(response.getBody());
    }

    private String callQwenApi(String baseUrl, String apiKey, String model, String prompt) {
        String url = baseUrl + "/services/aigc/text-generation/generation";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", Map.of("messages", List.of(
            Map.of("role", "user", "content", prompt)
        )));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        return extractQwenResponse(response.getBody());
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

    private String extractOpenAIResponse(String body) {
        try {
            log.debug("OpenAI response: {}", body);
            JsonNode root = objectMapper.readTree(body);

            // Check for error response
            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText();
                log.error("AI API returned error: {}", errorMsg);
                return "AI分析失败: " + errorMsg;
            }

            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                log.error("No choices in OpenAI response: {}", body);
                // 尝试其他格式 - 某些模型可能返回不同格式
                if (root.has("response")) {
                    return root.path("response").asText();
                }
                if (root.has("text")) {
                    return root.path("text").asText();
                }
                if (root.has("content")) {
                    return root.path("content").asText();
                }
                return "AI分析完成，但响应格式异常";
            }

            JsonNode message = choices.get(0).path("message");
            if (message.isMissingNode()) {
                // 某些模型可能直接在 choices[0] 返回 text
                JsonNode textNode = choices.get(0).path("text");
                if (!textNode.isMissingNode()) {
                    return textNode.asText();
                }
                log.error("No message in choices: {}", body);
                return "AI分析完成，但响应格式异常";
            }

            String content = message.path("content").asText();
            if (content == null || content.isEmpty()) {
                log.error("Empty content in response: {}", body);
                return "AI分析完成，但内容为空";
            }

            return content;
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response: {}", body, e);
            return "AI分析完成，但解析响应失败: " + e.getMessage();
        }
    }

    private String extractQwenResponse(String body) {
        try {
            log.debug("Qwen response: {}", body);
            JsonNode root = objectMapper.readTree(body);

            // Check for error response
            if (root.has("code") && !"Success".equals(root.path("code").asText())) {
                String errorMsg = root.path("message").asText();
                log.error("Qwen API returned error: {}", errorMsg);
                return "AI分析失败: " + errorMsg;
            }

            JsonNode output = root.path("output");
            
            // 标准 Qwen 格式: output.choices[0].message.content
            JsonNode choices = output.path("choices");
            if (!choices.isMissingNode() && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                if (!message.isMissingNode()) {
                    String content = message.path("content").asText();
                    if (content != null && !content.isEmpty()) {
                        return content;
                    }
                }
                // 某些版本可能直接返回 text
                JsonNode textNode = choices.get(0).path("text");
                if (!textNode.isMissingNode()) {
                    return textNode.asText();
                }
            }
            
            // 兼容旧格式: output.text
            if (output.has("text")) {
                return output.path("text").asText();
            }
            
            // 尝试其他可能的格式
            if (root.has("result")) {
                return root.path("result").asText();
            }
            
            log.error("No valid content in Qwen response: {}", body);
            return "AI分析完成，但响应格式异常";
        } catch (Exception e) {
            log.error("Failed to parse Qwen response: {}", body, e);
            return "AI分析完成，但解析响应失败: " + e.getMessage();
        }
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
}