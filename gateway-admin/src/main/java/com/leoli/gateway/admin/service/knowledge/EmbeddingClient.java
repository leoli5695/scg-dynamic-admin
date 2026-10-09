package com.leoli.gateway.admin.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiConfig;
import com.leoli.gateway.admin.repository.AiConfigRepository;
import com.leoli.gateway.admin.util.ApiKeyEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * 嵌入向量客户端（RAG）
 * ============================================================================
 * <p>
 * 走 OpenAI 兼容的 {@code /embeddings} 端点，<b>复用现有 AI 配置里的 apiKey/baseUrl</b>
 * （优先百炼，token 不限量），无需新增任何凭据。仅 OPENAI/BAILIAN 这类 OpenAI 兼容
 * provider 支持此格式；若当前配置的是 Claude/Gemini，会自动回退去找一个 BAILIAN/OPENAI
 * 配置用于嵌入。
 * <p>
 * 维度不写死：不向 API 传 {@code dimensions}（部分兼容端点不接受），而是从返回的向量
 * 长度读取实际维度，避免"强塞参数导致整批失败"。
 *
 * @author leoli
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final AiConfigRepository aiConfigRepository;
    private final ApiKeyEncryptor apiKeyEncryptor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = createRestTemplate();

    @Value("${gateway.copilot.rag.embedding-provider:BAILIAN}")
    private String embeddingProvider;

    @Value("${gateway.copilot.rag.embedding-model:text-embedding-v3}")
    private String embeddingModel;

    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
            "OPENAI", "https://api.openai.com/v1",
            "BAILIAN", "https://dashscope.aliyuncs.com/compatible-mode/v1"
    );

    public EmbeddingClient(AiConfigRepository aiConfigRepository, ApiKeyEncryptor apiKeyEncryptor) {
        this.aiConfigRepository = aiConfigRepository;
        this.apiKeyEncryptor = apiKeyEncryptor;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
        return new RestTemplate(f);
    }

    public String getModel() {
        return embeddingModel;
    }

    /** 单条文本嵌入。失败抛异常，由调用方决定降级。 */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /** 批量嵌入，返回顺序与入参一致。 */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        AiConfig config = resolveEmbeddingConfig();
        String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()
                ? config.getBaseUrl() : DEFAULT_BASE_URLS.get(config.getProvider());
        if (baseUrl == null) {
            throw new IllegalStateException("No base URL for embedding provider: " + config.getProvider());
        }
        String apiKey = apiKeyEncryptor.decrypt(config.getApiKey());
        String url = baseUrl.replaceAll("/+$", "") + "/embeddings";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", embeddingModel);
        body.put("input", texts);
        body.put("encoding_format", "float");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        return parseEmbeddings(resp.getBody(), texts.size());
    }

    /**
     * 选一个 OpenAI 兼容的配置用于嵌入：优先指定 provider，其次任意 BAILIAN/OPENAI，
     * 最后退化到第一个有效配置（若它也兼容）。
     */
    private AiConfig resolveEmbeddingConfig() {
        AiConfig cfg = aiConfigRepository.findByProvider(embeddingProvider)
                .filter(c -> c.getApiKey() != null && isCompatible(c.getProvider()))
                .orElse(null);
        if (cfg != null) {
            return cfg;
        }
        for (String p : List.of("BAILIAN", "OPENAI")) {
            cfg = aiConfigRepository.findByProvider(p)
                    .filter(c -> c.getApiKey() != null)
                    .orElse(null);
            if (cfg != null) {
                return cfg;
            }
        }
        throw new IllegalStateException("未找到可用于嵌入的 OpenAI 兼容 AI 配置（BAILIAN/OPENAI）");
    }

    private boolean isCompatible(String provider) {
        return "OPENAI".equals(provider) || "BAILIAN".equals(provider);
    }

    private List<float[]> parseEmbeddings(String body, int expected) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                String err = root.path("error").path("message").asText(null);
                throw new IllegalStateException("embeddings 响应无 data: " + (err != null ? err : body));
            }
            List<float[]> result = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode emb = item.path("embedding");
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                result.add(vec);
            }
            if (result.size() != expected) {
                log.warn("embeddings 返回 {} 条，期望 {} 条", result.size(), expected);
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 embeddings 响应失败: " + e.getMessage(), e);
        }
    }
}
