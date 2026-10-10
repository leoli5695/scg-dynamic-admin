package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.LlmProvider;
import com.leoli.gateway.ai.ProviderRegistry;
import com.leoli.gateway.ai.cache.GatewayEmbeddingClient;
import com.leoli.gateway.ai.cache.SemanticCacheStore;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiSemanticCacheConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * AI semantic cache filter (mirrors Higress ai-cache). Vectorizes the prompt via an
 * OpenAI-compatible {@code /embeddings} call and, on a sufficiently similar cached
 * prompt within the same scope (model + tenant), returns the stored response without
 * calling the LLM - saving tokens, cost and latency. On a miss it forwards and
 * captures the upstream answer to populate the cache.
 * <p>
 * Order {@link FilterOrderConstants#AI_SEMANTIC_CACHE} (-242): after auth/token
 * pre-check, and &lt; NettyWriteResponseFilter (-1) so the response-capture decorator
 * is in the client-write path. A HIT short-circuits (writes the cached body, never
 * calls the chain) so it consumes no LLM tokens. MVP scope: non-streaming only,
 * in-memory per-instance store; embedding/store failures fail OPEN (pass through).
 *
 * @author leoli
 */
@Slf4j
@Component
public class AiSemanticCacheFilter implements GlobalFilter, Ordered {

    public static final String CACHE_HEADER = "X-AI-Cache";
    private static final String TENANT_HEADER = "X-Tenant-Id";

    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;
    private final GatewayEmbeddingClient embeddingClient;
    private final SemanticCacheStore cacheStore;

    @Autowired
    public AiSemanticCacheFilter(StrategyManager strategyManager,
                                 ObjectMapper objectMapper,
                                 GatewayEmbeddingClient embeddingClient,
                                 SemanticCacheStore cacheStore) {
        this.strategyManager = strategyManager;
        this.objectMapper = objectMapper;
        this.embeddingClient = embeddingClient;
        this.cacheStore = cacheStore;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        AiSemanticCacheConfig config = strategyManager.getConfig(routeId,
                StrategyDefinition.TYPE_AI_SEMANTIC_CACHE, AiSemanticCacheConfig.class);

        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }

        long maxBodySize = Math.max(1024, config.getMaxBodySize());
        ServerHttpResponse response = exchange.getResponse();

        return DataBufferUtils.join(exchange.getRequest().getBody(), (int) Math.min(maxBodySize, Integer.MAX_VALUE))
                .defaultIfEmpty(response.bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    try {
                        dataBuffer.read(bytes);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                    if (bytes.length == 0) {
                        return chain.filter(exchange);
                    }

                    final String body;
                    final JsonNode root;
                    try {
                        body = new String(bytes, StandardCharsets.UTF_8);
                        root = objectMapper.readTree(body);
                    } catch (Exception e) {
                        return chain.filter(rebuild(exchange, bytes)); // unparseable: pass through
                    }
                    if (!root.isObject()) {
                        return chain.filter(rebuild(exchange, bytes));
                    }

                    boolean streaming = root.path("stream").asBoolean(false);
                    if (streaming && !config.isCacheStreaming()) {
                        return chain.filter(rebuild(exchange, bytes)); // MVP: non-streaming only
                    }

                    String model = root.path("model").isTextual() ? root.path("model").asText() : null;
                    String prompt = extractPromptText(root);
                    String tenant = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);
                    String scope = scope(config, model, tenant);

                    LlmProvider embeddingProvider = ProviderRegistry.byId(config.getEmbeddingProvider()).orElse(null);
                    if (embeddingProvider == null || isBlank(config.getEmbeddingCredentialRef()) || isBlank(prompt)) {
                        return chain.filter(rebuild(exchange, bytes)); // cannot embed: fail open
                    }

                    // Wrap in Optional so an empty embedding is distinguishable from the
                    // empty completion of chain.filter (avoids the switchIfEmpty-on-Void trap).
                    return embeddingClient.embed(prompt, embeddingProvider, config.getEmbeddingBaseUrlOverride(),
                                    config.getEmbeddingCredentialRef(), config.getEmbeddingModel())
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(vecOpt -> {
                                if (vecOpt.isEmpty() || vecOpt.get().length == 0) {
                                    return chain.filter(rebuild(exchange, bytes)); // embedding failed: fail open
                                }
                                float[] vector = vecOpt.get();
                                Optional<String> hit = cacheStore.search(scope, vector, config.getSimilarityThreshold());
                                if (hit.isPresent()) {
                                    log.info("AI cache HIT on route {} scope={}", routeId, scope);
                                    return writeCached(exchange, hit.get());
                                }
                                log.debug("AI cache MISS on route {} scope={}", routeId, scope);
                                ServerWebExchange decorated = exchange.mutate()
                                        .request(rebuildRequest(exchange, bytes))
                                        .response(captureDecorator(exchange, scope, vector, config))
                                        .build();
                                decorated.getResponse().getHeaders().add(CACHE_HEADER, "MISS");
                                return chain.filter(decorated);
                            });
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    log.warn("AI cache: body exceeds maxBodySize {} on route {}", maxBodySize, routeId);
                    response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                    return response.setComplete();
                });
    }

    /** Concatenate all textual message contents as the cache-key text. */
    private String extractPromptText(JsonNode root) {
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : messages) {
            JsonNode content = m.path("content");
            if (content.isTextual()) {
                sb.append(m.path("role").asText("")).append(':').append(content.asText()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String scope(AiSemanticCacheConfig config, String model, String tenant) {
        String m = config.isScopeByModel() ? (model != null ? model : "*") : "*";
        String t = config.isScopeByTenant() ? (tenant != null ? tenant : "*") : "*";
        return m + "|" + t;
    }

    /** Decorator that captures a successful upstream JSON body and stores it in the cache. */
    private ServerHttpResponse captureDecorator(ServerWebExchange exchange, String scope, float[] vector,
                                                AiSemanticCacheConfig config) {
        ServerHttpResponse original = exchange.getResponse();
        return new ServerHttpResponseDecorator(original) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                HttpStatusCode status = getStatusCode();
                if (status == null || !status.is2xxSuccessful()) {
                    return super.writeWith(body); // never cache errors
                }
                int limit = (int) Math.min(config.getMaxBodySize(), Integer.MAX_VALUE);
                return DataBufferUtils.join(Flux.from(body), limit)
                        .flatMap(buf -> {
                            byte[] b = new byte[buf.readableByteCount()];
                            buf.read(b);
                            DataBufferUtils.release(buf);
                            try {
                                cacheStore.put(scope, vector, new String(b, StandardCharsets.UTF_8), config.getTtlSeconds());
                            } catch (Exception e) {
                                log.warn("AI cache: failed to store entry for scope {}: {}", scope, e.getMessage());
                            }
                            return super.writeWith(Mono.just(original.bufferFactory().wrap(b)));
                        });
            }
        };
    }

    private Mono<Void> writeCached(ServerWebExchange exchange, String cachedResponse) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add(CACHE_HEADER, "HIT");
        byte[] bytes = cachedResponse.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().setContentLength(bytes.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private ServerWebExchange rebuild(ServerWebExchange exchange, byte[] bytes) {
        return exchange.mutate().request(rebuildRequest(exchange, bytes)).build();
    }

    private ServerHttpRequest rebuildRequest(ServerWebExchange exchange, byte[] bytes) {
        ServerHttpRequest request = exchange.getRequest();
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
            }

            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.setContentLength(bytes.length);
                return headers;
            }
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.AI_SEMANTIC_CACHE;
    }
}
