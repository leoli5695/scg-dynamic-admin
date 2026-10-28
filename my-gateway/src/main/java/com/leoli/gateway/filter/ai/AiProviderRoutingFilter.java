package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.ai.LlmProvider;
import com.leoli.gateway.ai.ProviderKeyPoolSelector;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI provider routing filter - the data-plane heart of the AI gateway.
 * <p>
 * Turns the northbound OpenAI-compatible endpoint into a multi-provider proxy:
 * the request's {@code model} selects the upstream provider (built-in registry),
 * the provider's credential is fetched and injected, and the upstream URL is
 * rewritten to that provider. For OpenAI-compatible providers the body passes
 * through with at most two edits: an optional model-name rewrite, and
 * {@code stream_options.include_usage} on streaming calls so token quota can be
 * settled against real usage instead of a refunded estimate.
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
    public static final String AI_SERVICE_ATTR = "ai.service";

    private static final String TARGETS_ATTR = "ai.routing.targets";
    private record CandidateKey(String service, String provider, String credential, int weight) { }
    private record BalanceKey(String route, List<CandidateKey> candidates) { }
    private final com.github.benmanes.caffeine.cache.Cache<BalanceKey, java.util.concurrent.atomic.AtomicReference<long[]>> selections =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(10000).build();

    private final StrategyManager strategyManager;
    private final ProviderKeyResolver keyResolver;
    private final ProviderKeyPoolSelector keyPoolSelector;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiProviderRoutingFilter(StrategyManager strategyManager,
                                   ProviderKeyResolver keyResolver,
                                   ProviderKeyPoolSelector keyPoolSelector,
                                   ObjectMapper objectMapper) {
        this.strategyManager = strategyManager;
        this.keyResolver = keyResolver;
        this.keyPoolSelector = keyPoolSelector;
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

        return com.leoli.gateway.util.RequestBodyCache.read(exchange, (int) Math.min(maxBodySize, Integer.MAX_VALUE))
                .flatMap(bytes -> {
                    String body = new String(bytes, StandardCharsets.UTF_8);

                    String model = extractModel(body);
                    if ((model == null || model.isBlank())
                            && config.getDefaultModel() != null && !config.getDefaultModel().isBlank()) {
                        model = config.getDefaultModel();
                    }

                    final String requestedModel = model;
                    final String mappedModel = config.getModelMapping() != null
                            ? config.getModelMapping().getOrDefault(requestedModel, requestedModel)
                            : requestedModel;

                    // Ordered target list: primary (model-resolved) then configured fallbacks.
                    List<Target> targets = buildTargets(exchange, config, requestedModel, mappedModel);
                    if (targets.isEmpty()) {
                        boolean providerKnown = resolveProvider(exchange, config, requestedModel).isPresent();
                        log.warn("AI proxy: no routable provider/credential for model '{}' on route {}", model, routeId);
                        return providerKnown
                                ? writeError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, 50001,
                                        "Provider 未配置凭据: " + model)
                                : writeError(exchange, HttpStatus.BAD_REQUEST, 40001,
                                        "无法解析模型对应的 Provider: " + model);
                    }

                    // The attempt index advances each time RetryGlobalFilter re-invokes this
                    // filter, so a transient DOWNSTREAM failure fails over to the next target.
                    // An unresolvable CREDENTIAL, by contrast, is walked past immediately within
                    // this invocation (see routeTarget) - it is a config-level failure, so routing
                    // it through RETRY would only back off and re-resolve the same dead ref.
                    int attempt = nextAttemptIndex(exchange);
                    return routeTarget(exchange, chain, config, routeId, targets, bytes, body,
                            requestedModel, Math.min(attempt, targets.size() - 1));
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    log.warn("AI proxy: request body exceeds maxBodySize {} on route {}", maxBodySize, routeId);
                    return writeError(exchange, HttpStatus.PAYLOAD_TOO_LARGE, 41301, "请求体超过 AI 代理上限");
                });
    }

    /**
     * Route to {@code targets[index]}. If its credential cannot be resolved, fail over to the
     * next target WITHIN this invocation: credential resolution is a local lookup with no upstream
     * round-trip, so a revoked/expired/deleted ref is skipped immediately rather than being turned
     * into a 502 that only fails over when a RETRY strategy happens to re-invoke us (and then only
     * after a backoff that cannot heal a config-level failure). Downstream (post-routing) errors are
     * deliberately NOT caught here - they propagate so RetryGlobalFilter can advance the attempt
     * index and re-enter at the next target. Exhausting the list yields a single 502.
     */
    private Mono<Void> routeTarget(ServerWebExchange exchange, GatewayFilterChain chain, AiProxyConfig config,
                                   String routeId, List<Target> targets, byte[] bytes, String body,
                                   String requestedModel, int index) {
        if (index >= targets.size()) {
            return writeError(exchange, HttpStatus.BAD_GATEWAY, 50201,
                    "无法获取任何 Provider 密钥（已尝试 " + targets.size() + " 个目标）");
        }
        Target target = targets.get(index);
        if (exchange.getAttribute(TARGETS_ATTR) != null) {
            exchange.getAttributes().put(ATTEMPT_INDEX_ATTR, index + 1);
        }

        // switchIfEmpty is on Mono<String> (emits when present) - NOT on the downstream
        // Mono<Void>, which would falsely trigger on normal completion.
        return keyResolver.resolveKey(target.credentialRef)
                .switchIfEmpty(Mono.error(new KeyResolutionException(target.provider.getId())))
                .flatMap(key -> {
                    byte[] outBytes = rewriteUpstreamBody(bytes, body, requestedModel,
                            target.upstreamModel, target.provider);

                    URI upstream = buildUpstreamUri(target.provider, target.baseUrlOverride, exchange);
                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, upstream);
                    exchange.getAttributes().put(AI_PROVIDER_ATTR, target.provider.getId());
                    if (target.serviceId != null) {
                        exchange.getAttributes().put(AI_SERVICE_ATTR, target.serviceId);
                    }
                    exchange.getAttributes().put(AI_MODEL_ATTR, target.upstreamModel);
                    exchange.getAttributes().put(AI_RESPONSE_FORMAT_ATTR,
                            config.getResponseFormat() != null ? config.getResponseFormat() : "OPENAI");

                    ServerHttpRequest newRequest = decorateRequest(exchange, target.provider, key, upstream,
                            outBytes, config);
                    log.info("AI proxy routing: route={}, model={} -> provider={}, service={}, upstream={} (attempt {}/{})",
                            routeId, requestedModel, target.provider.getId(), target.serviceId, upstream, index + 1, targets.size());
                    keyPoolSelector.acquire(target.provider.getId(), target.credentialRef, config);
                    return chain.filter(exchange.mutate().request(newRequest).build())
                            .doFinally(signal -> keyPoolSelector.release(
                                    target.provider.getId(), target.credentialRef, config));
                })
                .onErrorResume(KeyResolutionException.class, e -> {
                    log.warn("AI proxy: credential for provider '{}' unresolvable, failing over to next target",
                            e.providerId);
                    return routeTarget(exchange, chain, config, routeId, targets, bytes, body,
                            requestedModel, index + 1);
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

    private URI buildUpstreamUri(LlmProvider provider, String baseUrlOverride, ServerWebExchange exchange) {
        String url = provider.resolveChatUrl(baseUrlOverride);
        String query = exchange.getRequest().getURI().getRawQuery();
        if (query != null && !query.isBlank()) {
            url = url + "?" + query;
        }
        return URI.create(url);
    }

    /** Exchange attr carrying the failover attempt index (advances per RetryGlobalFilter re-invocation). */
    private static final String ATTEMPT_INDEX_ATTR = "ai.target.attempt";

    private int nextAttemptIndex(ServerWebExchange exchange) {
        Object value = exchange.getAttributes().get(ATTEMPT_INDEX_ATTR);
        int idx = (value instanceof Integer) ? (Integer) value : 0;
        exchange.getAttributes().put(ATTEMPT_INDEX_ATTR, idx + 1);
        return idx;
    }

    /**
     * Build the ordered target list: the primary (model-resolved) provider first, then
     * each configured fallback with a resolvable provider AND credential. Targets missing
     * either are skipped so a partial config still routes to whatever is usable.
     */
    private List<Target> buildTargets(ServerWebExchange exchange, AiProxyConfig config,
                                      String requestedModel, String mappedModel) {
        List<Target> saved = exchange.getAttribute(TARGETS_ATTR);
        if (saved != null) {
            return saved;
        }
        List<Target> targets = new ArrayList<>();

        Optional<LlmProvider> primary = resolveProvider(exchange, config, requestedModel);
        if (config.getUpstreams() != null && !config.getUpstreams().isEmpty()) {
            List<AiProxyConfig.Upstream> eligible = config.getUpstreams().stream()
                    .filter(up -> up != null && up.getWeight() > 0
                            && up.getCredentialRef() != null && !up.getCredentialRef().isBlank()
                            && ProviderRegistry.byId(up.getProvider()).isPresent())
                    .filter(up -> matchesUpstream(up, config, requestedModel, primary))
                    .toList();
            long totalWeight = eligible.stream().mapToLong(AiProxyConfig.Upstream::getWeight).sum();
            if (totalWeight > 0) {
                int selected = selectService(RouteUtils.getRouteId(exchange), eligible, totalWeight);
                // Pin the ordered plan to this request: retries must not re-run load balancing.
                addServiceTarget(targets, eligible.get(selected), requestedModel);
                for (int i = 0; i < eligible.size(); i++) {
                    if (i != selected) addServiceTarget(targets, eligible.get(i), requestedModel);
                }
            }
        } else if (primary.isPresent()) {
            // Pool-aware selection (Higress ai-load-balancer counterpart); falls back
            // to the legacy single providerApiKeyRefs entry when no pool is configured.
            String ref = keyPoolSelector.selectRef(primary.get().getId(), config);
            if (ref != null && !ref.isBlank()) {
                targets.add(new Target(primary.get(), ref, mappedModel,
                        overrideFor(config, primary.get().getId())));
            }
        }

        if (config.getFallbacks() != null) {
            for (AiProxyConfig.FallbackTarget fb : config.getFallbacks()) {
                if (fb == null) {
                    continue;
                }
                Optional<LlmProvider> provider = ProviderRegistry.byId(fb.getProvider());
                if (provider.isEmpty()) {
                    continue;
                }
                String ref = (fb.getCredentialRef() != null && !fb.getCredentialRef().isBlank())
                        ? fb.getCredentialRef()
                        : keyPoolSelector.selectRef(provider.get().getId(), config);
                if (ref == null || ref.isBlank()) {
                    continue;
                }
                String targetModel = (fb.getModel() != null && !fb.getModel().isBlank())
                        ? fb.getModel() : fb.getModelMapping() == null ? requestedModel
                        : fb.getModelMapping().getOrDefault(requestedModel, requestedModel);
                String override = (fb.getBaseUrlOverride() != null && !fb.getBaseUrlOverride().isBlank())
                        ? fb.getBaseUrlOverride() : overrideFor(config, provider.get().getId());
                targets.add(new Target(provider.get(), ref, targetModel, override, fb.getServiceId()));
            }
        }
        if (config.getUpstreams() != null && !config.getUpstreams().isEmpty()) {
            exchange.getAttributes().put(TARGETS_ATTR, targets);
        }
        return targets;
    }

    /** Smooth weighted rotation, local to this gateway; CAS keeps selection off blocking locks. */
    private int selectService(String routeId, List<AiProxyConfig.Upstream> eligible, long totalWeight) {
        BalanceKey key = new BalanceKey(routeId, eligible.stream().map(up ->
                new CandidateKey(up.getServiceId(), up.getProvider(), up.getCredentialRef(), up.getWeight())).toList());
        var state = selections.get(key, ignored -> new java.util.concurrent.atomic.AtomicReference<>(new long[eligible.size()]));
        while (true) {
            long[] previous = state.get();
            long[] next = previous.clone();
            int selected = 0;
            for (int i = 0; i < next.length; i++) {
                next[i] += eligible.get(i).getWeight();
                if (next[i] > next[selected]) selected = i;
            }
            next[selected] -= totalWeight;
            if (state.compareAndSet(previous, next)) return selected;
        }
    }

    private boolean matchesUpstream(AiProxyConfig.Upstream up, AiProxyConfig config,
                                    String model, Optional<LlmProvider> resolved) {
        if (config.getProviderSelection() != null && !"MODEL".equalsIgnoreCase(config.getProviderSelection())) {
            return resolved.map(p -> p.getId().equalsIgnoreCase(up.getProvider())).orElse(false);
        }
        if (model == null || model.isBlank()) return false;
        String explicit = config.getModelRouting() == null ? null : config.getModelRouting().get(model);
        if (explicit != null && !explicit.isBlank()) {
            return resolved.map(p -> p.getId().equalsIgnoreCase(up.getProvider())).orElse(false);
        }
        return (up.getModelMapping() != null && up.getModelMapping().containsKey(model))
                || resolved.map(p -> p.getId().equalsIgnoreCase(up.getProvider())).orElse(false);
    }

    private void addServiceTarget(List<Target> targets, AiProxyConfig.Upstream up, String model) {
        String mapped = up.getModelMapping() == null ? model : up.getModelMapping().getOrDefault(model, model);
        targets.add(new Target(ProviderRegistry.byId(up.getProvider()).orElseThrow(),
                up.getCredentialRef(), mapped, up.getBaseUrlOverride(), up.getServiceId()));
    }

    private String overrideFor(AiProxyConfig config, String providerId) {
        return config.getProviderBaseUrlOverride() == null ? null
                : config.getProviderBaseUrlOverride().get(providerId);
    }

    /** A resolved failover target: provider + credential ref + upstream model + base-url override. */
    private static final class Target {
        final LlmProvider provider;
        final String credentialRef;
        final String upstreamModel;
        final String baseUrlOverride;
        final String serviceId;

        Target(LlmProvider provider, String credentialRef, String upstreamModel, String baseUrlOverride) {
            this(provider, credentialRef, upstreamModel, baseUrlOverride, null);
        }

        Target(LlmProvider provider, String credentialRef, String upstreamModel, String baseUrlOverride, String serviceId) {
            this.provider = provider;
            this.credentialRef = credentialRef;
            this.upstreamModel = upstreamModel;
            this.baseUrlOverride = baseUrlOverride;
            this.serviceId = serviceId;
        }
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

    /**
     * Build the bytes to send upstream in a single parse/serialize pass: apply the
     * model-name mapping and, for streaming calls, make sure usage is reported.
     * Returns the original bytes untouched when neither applies, so non-streaming
     * traffic pays no re-serialization cost.
     */
    private byte[] rewriteUpstreamBody(byte[] original, String body, String requestedModel,
                                       String upstreamModel, LlmProvider provider) {
        boolean modelRewrite = upstreamModel != null && !upstreamModel.equals(requestedModel);
        // Substring pre-check keeps the JSON parse off the non-streaming hot path.
        boolean usageCandidate = provider.getProtocol() == LlmProvider.Protocol.OPENAI_COMPAT
                && body.contains("\"stream\"");
        if (!modelRewrite && !usageCandidate) {
            return original;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!(root instanceof ObjectNode)) {
                return original;
            }
            ObjectNode obj = (ObjectNode) root;
            boolean changed = false;
            if (modelRewrite) {
                obj.put("model", upstreamModel);
                changed = true;
            }
            if (usageCandidate && obj.path("stream").asBoolean(false) && forceIncludeUsage(obj)) {
                changed = true;
            }
            return changed ? objectMapper.writeValueAsBytes(obj) : original;
        } catch (Exception e) {
            log.warn("AI proxy: failed to rewrite request body, forwarding original: {}", e.getMessage());
            return original;
        }
    }

    /**
     * Streaming responses only carry a usage block if the caller asked for one, and
     * without usage the gateway cannot settle token quota - the pre-deducted estimate
     * would be refunded in full and streaming traffic would cost the tenant nothing.
     * So ask on the caller's behalf. Returns false when it was already requested.
     */
    private boolean forceIncludeUsage(ObjectNode body) {
        JsonNode existing = body.get("stream_options");
        ObjectNode options = existing instanceof ObjectNode ? (ObjectNode) existing : null;
        if (options != null && options.path("include_usage").asBoolean(false)) {
            return false;
        }
        if (options == null) {
            options = body.putObject("stream_options");
        }
        options.put("include_usage", true);
        return true;
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
