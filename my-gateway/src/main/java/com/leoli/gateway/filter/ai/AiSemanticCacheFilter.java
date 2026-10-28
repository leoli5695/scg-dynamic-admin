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
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * AI semantic cache filter (mirrors Higress ai-cache). Vectorizes the prompt via an
 * OpenAI-compatible {@code /embeddings} call and, on a sufficiently similar cached
 * prompt within the same scope (model + tenant), returns the stored response without
 * calling the LLM - saving tokens, cost and latency. On a miss it forwards and
 * captures the upstream answer to populate the cache.
 * <p>
 * Order {@link FilterOrderConstants#AI_SEMANTIC_CACHE} (-242): after auth and the rate
 * limiters but BEFORE the token-quota pre-check (-241), and &lt; NettyWriteResponseFilter
 * (-1) so the response-capture decorator is in the client-write path. A HIT short-circuits
 * (writes the cached body, never calls the chain) so it consumes no LLM tokens and
 * pre-deducts no quota. MVP scope: non-streaming only, in-memory per-instance store;
 * embedding/store failures fail OPEN (pass through). The tenant half of the scope comes only
 * from server-verified auth results: a request that asserts {@code X-Tenant-Id} without any
 * authenticated tenant bypasses the cache, since a client-chosen scope would let it read and
 * poison another tenant's entries.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AiSemanticCacheFilter implements GlobalFilter, Ordered {

    public static final String CACHE_HEADER = "X-AI-Cache";

    /** Exchange attribute flagging a cache HIT for AiStatisticsFilter. */
    public static final String AI_CACHE_HIT_ATTR = "ai.cache_hit";
    private static final String TENANT_HEADER = "X-Tenant-Id";

    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;
    private final GatewayEmbeddingClient embeddingClient;
    private final SemanticCacheStore cacheStore;
    private final com.leoli.gateway.util.ByteBudget storeBudget = new com.leoli.gateway.util.ByteBudget();
    private final com.leoli.gateway.util.BodyWorkPool storePool = new com.leoli.gateway.util.BodyWorkPool("gateway-cache-store", 2, 16);
    @jakarta.annotation.PreDestroy
    public void closeStorePool() { storePool.close(); }

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

        return com.leoli.gateway.util.RequestBodyCache.read(exchange, (int) Math.min(maxBodySize, Integer.MAX_VALUE))
                .flatMap(bytes -> {
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
                    String tenant = trustedTenantId(exchange);
                    if (config.isScopeByTenant() && tenant == null
                            && !isBlank(exchange.getRequest().getHeaders().getFirst(TENANT_HEADER))) {
                        // The client asserts a tenant it cannot prove: caching would let it read
                        // and poison that tenant's scope, so bypass the cache for this request.
                        log.debug("AI cache bypassed on route {}: {} sent without an authenticated tenant",
                                routeId, TENANT_HEADER);
                        return chain.filter(rebuild(exchange, bytes));
                    }
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
                                // Search scans its scope shard. Offload the lookup so a slow
                                // scan cannot stall unrelated routes sharing the WebClient loop, then hop to
                                // parallel() so the downstream chain is not run on a blocking-pool thread.
                                return Mono.fromCallable(() -> cacheStore.search(scope, vector,
                                                config.getSimilarityThreshold()))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .publishOn(Schedulers.parallel())
                                        .flatMap(hit -> {
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
                            String bodyText = new String(b, StandardCharsets.UTF_8);
                            // Pending writes have their own byte budget and bounded executor.
                            var lease = storeBudget.tryAcquire(2L * bodyText.length() + 4L * vector.length,
                                    8L * 1024 * 1024);
                            if (lease != null) {
                                storePool.submit(lease, () -> {
                                            cacheStore.put(scope, vector, bodyText, config.getTtlSeconds());
                                            return true;
                                        }).doFinally(signal -> lease.close())
                                        .subscribe(ignored -> { }, error -> log.debug("AI cache store skipped: {}", error.getMessage()));
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
        exchange.getAttributes().put(AI_CACHE_HIT_ATTR, Boolean.TRUE);
        byte[] bytes = cachedResponse.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().setContentLength(bytes.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private ServerWebExchange rebuild(ServerWebExchange exchange, byte[] bytes) {
        return exchange.mutate().request(rebuildRequest(exchange, bytes)).build();
    }

    private ServerHttpRequest rebuildRequest(ServerWebExchange exchange, byte[] bytes) {
        return com.leoli.gateway.util.RequestBodyCache.wrap(exchange, bytes);
    }

    /**
     * Tenant identity from server-verified auth results only, with the same precedence the
     * token-quota filter uses (API key metadata, then JWT claim). Deliberately never the raw
     * {@code X-Tenant-Id} header: the scope is an isolation boundary, so a client-supplied
     * value would let any caller select another tenant's cached answers.
     */
    private static String trustedTenantId(ServerWebExchange exchange) {
        Map<String, Object> attrs = exchange.getAttributes();
        if (attrs.get("api_key_metadata") instanceof Map<?, ?> metadata && metadata.get("tenantId") != null) {
            return String.valueOf(metadata.get("tenantId"));
        }
        if (attrs.get("jwt_claims") instanceof Map<?, ?> claims && claims.get("tenant_id") != null) {
            return String.valueOf(claims.get("tenant_id"));
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.AI_SEMANTIC_CACHE;
    }
}
