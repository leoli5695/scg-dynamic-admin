package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.ai.LlmProvider;
import com.leoli.gateway.ai.ProviderKeyResolver;
import com.leoli.gateway.ai.ProviderRegistry;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiProxyConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AI provider routing filter - the data-plane heart of the AI gateway.
 * <p>
 * Turns the northbound OpenAI-compatible endpoint into a multi-provider proxy:
 * the request's {@code model} selects the upstream provider (built-in registry),
 * the provider's credential is fetched and injected, and the upstream URL is
 * rewritten to that provider. For OpenAI-compatible providers the body passes
 * through untouched (only an optional model-name rewrite).
 * <p>
 * Runs at {@link FilterOrderConstants#AI_PROVIDER_ROUTING} - after SCG's
 * RouteToRequestUrlFilter (10000) so overwriting GATEWAY_REQUEST_URL_ATTR sticks,
 * and before NettyRoutingFilter which performs the actual upstream call.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AiProviderRoutingFilter implements GlobalFilter, Ordered {

    /** Exchange attributes consumed downstream by the token-usage collector. */
    public static final String AI_PROVIDER_ATTR = "ai.provider";
    public static final String AI_MODEL_ATTR = "ai.model";
    public static final String AI_RESPONSE_FORMAT_ATTR = "ai.response_format";

    private final StrategyManager strategyManager;
    private final ProviderKeyResolver keyResolver;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiProviderRoutingFilter(StrategyManager strategyManager,
                                   ProviderKeyResolver keyResolver,
                                   ObjectMapper objectMapper) {
        this.strategyManager = strategyManager;
        this.keyResolver = keyResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        AiProxyConfig config = strategyManager.getConfig(routeId,
                StrategyDefinition.TYPE_AI_PROXY, AiProxyConfig.class);

        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }

        // Only the chat-completions POST is proxied here; GET /v1/models is a native endpoint.
        if (exchange.getRequest().getMethod() != org.springframework.http.HttpMethod.POST) {
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
                    String body = new String(bytes, StandardCharsets.UTF_8);

                    String model = extractModel(body);
                    if ((model == null || model.isBlank())
                            && config.getDefaultModel() != null && !config.getDefaultModel().isBlank()) {
                        model = config.getDefaultModel();
                    }

                    Optional<LlmProvider> resolved = resolveProvider(exchange, config, model);
                    if (resolved.isEmpty()) {
                        log.warn("AI proxy: no provider resolved for model '{}' on route {}", model, routeId);
                        return writeError(exchange, HttpStatus.BAD_REQUEST, 40001,
                                "无法解析模型对应的 Provider: " + model);
                    }
                    LlmProvider provider = resolved.get();

                    String credentialRef = config.getProviderApiKeyRefs() == null ? null
                            : config.getProviderApiKeyRefs().get(provider.getId());
                    if (credentialRef == null || credentialRef.isBlank()) {
                        log.warn("AI proxy: no credential configured for provider {}", provider.getId());
                        return writeError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, 50001,
                                "Provider 未配置凭据: " + provider.getId());
                    }

                    final String requestedModel = model;
                    final String upstreamModel = config.getModelMapping() != null
                            ? config.getModelMapping().getOrDefault(requestedModel, requestedModel)
                            : requestedModel;

                    // switchIfEmpty is on Mono<String> (emits when present) - NOT on the
                    // downstream Mono<Void>, which would falsely trigger on normal completion.
                    return keyResolver.resolveKey(credentialRef)
                            .switchIfEmpty(Mono.error(new KeyResolutionException(provider.getId())))
                            .flatMap(key -> {
                                byte[] outBytes = (upstreamModel != null && !upstreamModel.equals(requestedModel))
                                        ? rewriteModel(bytes, body, upstreamModel)
                                        : bytes;

                                URI upstream = buildUpstreamUri(provider, config, exchange);
                                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, upstream);
                                exchange.getAttributes().put(AI_PROVIDER_ATTR, provider.getId());
                                exchange.getAttributes().put(AI_MODEL_ATTR, upstreamModel);
                                exchange.getAttributes().put(AI_RESPONSE_FORMAT_ATTR,
                                        config.getResponseFormat() != null ? config.getResponseFormat() : "OPENAI");

                                ServerHttpRequest newRequest = decorateRequest(exchange, provider, key, upstream,
                                        outBytes, config);
                                log.info("AI proxy routing: route={}, model={} -> provider={}, upstream={}",
                                        routeId, requestedModel, provider.getId(), upstream);
                                return chain.filter(exchange.mutate().request(newRequest).build());
                            })
                            .onErrorResume(KeyResolutionException.class, e ->
                                    writeError(exchange, HttpStatus.BAD_GATEWAY, 50201,
                                            "无法获取 Provider 密钥: " + e.providerId));
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    log.warn("AI proxy: request body exceeds maxBodySize {} on route {}", maxBodySize, routeId);
                    return writeError(exchange, HttpStatus.PAYLOAD_TOO_LARGE, 41301, "请求体超过 AI 代理上限");
                });
    }

    private Optional<LlmProvider> resolveProvider(ServerWebExchange exchange, AiProxyConfig config, String model) {
        String selection = config.getProviderSelection() == null ? "MODEL"
                : config.getProviderSelection().trim().toUpperCase();
        switch (selection) {
            case "FIXED":
                return ProviderRegistry.byId(config.getFixedProvider());
            case "HEADER":
                String header = config.getProviderHeader() == null ? "X-Provider" : config.getProviderHeader();
                return ProviderRegistry.byId(exchange.getRequest().getHeaders().getFirst(header));
            case "MODEL":
            default:
                if (model == null || model.isBlank()) {
                    return Optional.empty();
                }
                String explicit = config.getModelRouting() == null ? null : config.getModelRouting().get(model);
                if (explicit != null && !explicit.isBlank()) {
                    return ProviderRegistry.byId(explicit);
                }
                return ProviderRegistry.resolveByModel(model);
        }
    }

    private URI buildUpstreamUri(LlmProvider provider, AiProxyConfig config, ServerWebExchange exchange) {
        String override = config.getProviderBaseUrlOverride() == null ? null
                : config.getProviderBaseUrlOverride().get(provider.getId());
        String url = provider.resolveChatUrl(override);
        String query = exchange.getRequest().getURI().getRawQuery();
        if (query != null && !query.isBlank()) {
            url = url + "?" + query;
        }
        return URI.create(url);
    }

    private ServerHttpRequest decorateRequest(ServerWebExchange exchange, LlmProvider provider, String key,
                                              URI upstream, byte[] outBytes, AiProxyConfig config) {
        ServerHttpRequest request = exchange.getRequest();
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(outBytes));
            }

            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                if (config.isStripClientAuth()) {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.remove("x-api-key");
                }
                if (provider.getAuthScheme() == LlmProvider.AuthScheme.X_API_KEY) {
                    headers.set("x-api-key", key);
                } else {
                    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + key);
                }
                // Forward the provider's host so upstream TLS/SNI and vhosts match.
                if (upstream.getAuthority() != null) {
                    headers.set(HttpHeaders.HOST, upstream.getAuthority());
                }
                headers.setContentLength(outBytes.length);
                return headers;
            }
        };
    }

    private String extractModel(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode model = objectMapper.readTree(body).path("model");
            return model.isTextual() ? model.asText() : null;
        } catch (Exception e) {
            log.debug("AI proxy: failed to parse request body for model: {}", e.getMessage());
            return null;
        }
    }

    private byte[] rewriteModel(byte[] original, String body, String upstreamModel) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root instanceof ObjectNode) {
                ((ObjectNode) root).put("model", upstreamModel);
                return objectMapper.writeValueAsBytes(root);
            }
        } catch (Exception e) {
            log.warn("AI proxy: failed to rewrite model field, forwarding original body: {}", e.getMessage());
        }
        return original;
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, int code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("httpStatus", status.value());
        body.put("code", code);
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("data", null);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"httpStatus\":" + status.value() + ",\"code\":" + code
                    + ",\"message\":\"AI proxy error\"}").getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.AI_PROVIDER_ROUTING;
    }

    /** Marker so an absent key is not confused with normal Mono<Void> completion. */
    private static final class KeyResolutionException extends RuntimeException {
        private final String providerId;

        KeyResolutionException(String providerId) {
            super("provider key not resolvable: " + providerId);
            this.providerId = providerId;
        }
    }
}
