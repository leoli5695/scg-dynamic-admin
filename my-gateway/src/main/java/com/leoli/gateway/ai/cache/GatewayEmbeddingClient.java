package com.leoli.gateway.ai.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.LlmProvider;
import com.leoli.gateway.ai.ProviderKeyResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reactive embeddings client for the data plane. Calls an OpenAI-compatible
 * {@code /embeddings} endpoint via the shared WebClient (non-blocking), reusing the
 * AI proxy credential store for the API key. The control plane's EmbeddingClient is
 * blocking (RestTemplate) and bound to admin config, so it is not reused here.
 *
 * @author leoli
 */
@Slf4j
@Component
public class GatewayEmbeddingClient {

    private final WebClient webClient;
    private final ProviderKeyResolver keyResolver;
    private final ObjectMapper objectMapper;

    public GatewayEmbeddingClient(WebClient webClient, ProviderKeyResolver keyResolver, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.keyResolver = keyResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Embed a single text. Returns an empty Mono when inputs are missing, the key
     * cannot be resolved, the call fails, or the response has no vector - callers
     * treat "no embedding" as a cache miss / no-store (fail-open).
     */
    public Mono<float[]> embed(String text, LlmProvider provider, String baseUrlOverride,
                               String credentialRef, String model) {
        if (text == null || text.isBlank() || provider == null
                || credentialRef == null || credentialRef.isBlank()) {
            return Mono.empty();
        }
        return keyResolver.resolveKey(credentialRef)
                .flatMap(key -> {
                    String url = provider.resolveEmbeddingsUrl(baseUrlOverride);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("model", model);
                    body.put("input", List.of(text));
                    body.put("encoding_format", "float");
                    return webClient.post()
                            .uri(url)
                            .header("Authorization", "Bearer " + key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(this::parseFirstEmbedding)
                            .filter(v -> v.length > 0)
                            .onErrorResume(e -> {
                                log.warn("AI cache: embedding call to {} failed: {}", url, e.getMessage());
                                return Mono.empty();
                            });
                });
    }

    private float[] parseFirstEmbedding(String response) {
        try {
            JsonNode data = objectMapper.readTree(response).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return new float[0];
            }
            JsonNode emb = data.get(0).path("embedding");
            float[] vector = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vector[i] = (float) emb.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            log.warn("AI cache: failed to parse embeddings response: {}", e.getMessage());
            return new float[0];
        }
    }
}
