package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiContextLimitConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.AiTokenEstimator;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI context-window limit filter (Higress ai-context-limit counterpart).
 * <p>
 * Estimates the text input tokens of an OpenAI/Anthropic compatible request and,
 * when estimate * bufferRatio exceeds the configured window, rejects it with an
 * OpenAI-style {@code context_length_exceeded} error BEFORE the request spends
 * tenant quota or upstream capacity. Non-chat and non-JSON bodies pass through
 * untouched, matching Higress semantics.
 * <p>
 * Runs at {@link FilterOrderConstants#AI_CONTEXT_LIMIT}, i.e. after authentication
 * but before {@link FilterOrderConstants#TOKEN_QUOTA_PRE_CHECK}, so an oversized
 * request never triggers a pre-deduction. The joined body is replayed through a
 * request decorator and published as the {@code cachedRequestBody} attribute so
 * downstream consumers (quota pre-deduction) see the real body instead of their
 * constant fallback estimate.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AiContextLimitFilter implements GlobalFilter, Ordered {

    /** Exchange attribute read by TokenQuotaPreCheckFilter for input estimation. */
    public static final String CACHED_REQUEST_BODY_ATTR = "cachedRequestBody";

    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiContextLimitFilter(StrategyManager strategyManager, ObjectMapper objectMapper) {
        this.strategyManager = strategyManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        AiContextLimitConfig config = resolveConfig(routeId);

        if (config == null || !config.isEnabled() || config.getMaxContextTokens() <= 0) {
            return chain.filter(exchange);
        }
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }

        long maxBodySize = Math.max(1024, config.getMaxBodySize());
        return com.leoli.gateway.util.RequestBodyCache.read(exchange, (int) Math.min(maxBodySize, Integer.MAX_VALUE))
                .flatMap(bytes -> {
                    String body = new String(bytes, StandardCharsets.UTF_8);

                    long estimated = AiTokenEstimator.estimateChat(body, objectMapper).orElse(-1L);
                    if (estimated < 0) {
                        // Non-chat / non-JSON body: Higress passes these through.
                        return chain.filter(withReplayedBody(exchange, bytes));
                    }

                    long buffered = (long) Math.ceil(estimated * config.getBufferRatio());
                    if (buffered > config.getMaxContextTokens()) {
                        log.info("AI context limit: route={} rejected, estimated={} buffered={} > max={}",
                                routeId, estimated, buffered, config.getMaxContextTokens());
                        return writeContextExceeded(exchange, config, buffered);
                    }

                    // Publish the real body for downstream estimation (quota pre-deduction).
                    exchange.getAttributes().put(CACHED_REQUEST_BODY_ATTR, body);
                    return chain.filter(withReplayedBody(exchange, bytes));
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    // Same precedent as AccessLog/AI proxy: the body stream is already
                    // partially consumed and cannot be forwarded safely.
                    log.warn("AI context limit: request body exceeds maxBodySize {} on route {}, rejecting with 413",
                            maxBodySize, routeId);
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                    return response.setComplete();
                });
    }

    private ServerWebExchange withReplayedBody(ServerWebExchange exchange, byte[] bytes) {
        return exchange.mutate().request(com.leoli.gateway.util.RequestBodyCache.wrap(exchange, bytes)).build();
    }

    /**
     * OpenAI-style rejection so SDKs surface a familiar error
     * (Higress ai-context-limit response parity).
     */
    private Mono<Void> writeContextExceeded(ServerWebExchange exchange, AiContextLimitConfig config, long buffered) {
        ServerHttpResponse response = exchange.getResponse();
        HttpStatus status = HttpStatus.resolve(config.getErrorStatusCode());
        response.setStatusCode(status != null ? status : HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", "This model's maximum context length is " + config.getMaxContextTokens()
                + " tokens. Your request had approximately " + buffered + " tokens.");
        error.put("type", "invalid_request_error");
        error.put("code", "context_length_exceeded");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"error\":{\"message\":\"context length exceeded\",\"type\":\"invalid_request_error\","
                    + "\"code\":\"context_length_exceeded\"}}").getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /** Route-bound config first, then the first enabled global (instance-level) one. */
    private AiContextLimitConfig resolveConfig(String routeId) {
        AiContextLimitConfig config = strategyManager.getConfig(routeId,
                StrategyDefinition.TYPE_AI_CONTEXT_LIMIT, AiContextLimitConfig.class);
        if (config != null) {
            return config;
        }
        try {
            List<StrategyDefinition> globals =
                    strategyManager.getGlobalStrategiesByType(StrategyDefinition.TYPE_AI_CONTEXT_LIMIT);
            if (globals != null) {
                for (StrategyDefinition global : globals) {
                    if (global != null && global.isEnabled()) {
                        return strategyManager.convertStrategyConfig("global:" + global.getStrategyId(),
                                StrategyDefinition.TYPE_AI_CONTEXT_LIMIT, global, AiContextLimitConfig.class);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("No global AI context limit config: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.AI_CONTEXT_LIMIT;
    }
}
