package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.ai.security.ContentModerator;
import com.leoli.gateway.ai.security.ContentModerator.ModerationResult;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiSecurityConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI security guard filter (mirrors Higress ai-security-guard). Local, rule-based
 * content moderation of the inbound request: sensitive words, regex patterns and
 * PII, with DETECT / BLOCK / REPLACE actions. The moderator is pluggable so a
 * model-based moderator can be added without touching this filter.
 * <p>
 * Runs at {@link FilterOrderConstants#AI_SECURITY} (-246): after authentication
 * (identity known) but before the rate limiters / token quota, so a blocked request
 * never spends quota; and &lt; NettyWriteResponseFilter (-1) so an outbound response
 * decorator can be added later without re-ordering.
 * <p>
 * Scope: this increment moderates the INBOUND request. Outbound (response) moderation
 * is a follow-up increment (it needs response-body interception + a streaming policy).
 *
 * @author leoli
 */
@Slf4j
@Component
public class AiSecurityGuardFilter implements GlobalFilter, Ordered {

    /** Response header carrying the inbound moderation verdict (DETECT/REPLACE). */
    public static final String REQUEST_VERDICT_HEADER = "X-AI-Security-Request";

    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;
    private final Map<String, ContentModerator> moderators;

    @Autowired
    public AiSecurityGuardFilter(StrategyManager strategyManager,
                                 ObjectMapper objectMapper,
                                 List<ContentModerator> moderatorList) {
        this.strategyManager = strategyManager;
        this.objectMapper = objectMapper;
        this.moderators = moderatorList.stream()
                .collect(Collectors.toMap(m -> m.type().toUpperCase(), m -> m, (a, b) -> a, LinkedHashMap::new));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        AiSecurityConfig config = strategyManager.getConfig(routeId,
                StrategyDefinition.TYPE_AI_SECURITY, AiSecurityConfig.class);

        if (config == null || !config.isEnabled() || !config.isScanRequest()) {
            return chain.filter(exchange);
        }
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }
        ContentModerator moderator = resolveModerator(config);
        if (moderator == null) {
            log.warn("AI security: no moderator '{}' available on route {}, passing through",
                    config.getModerator(), routeId);
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

                    byte[] outBytes = bytes;
                    try {
                        JsonNode root = objectMapper.readTree(bytes);
                        if (root instanceof ObjectNode && root.get("messages") instanceof ArrayNode) {
                            Verdict verdict = moderateMessages((ArrayNode) root.get("messages"), config, moderator);
                            if (verdict.flagged) {
                                String action = normalizeAction(config.getInboundAction(), "BLOCK");
                                if ("BLOCK".equals(action)) {
                                    log.warn("AI security BLOCK on route {}: {}", routeId, verdict.categories);
                                    return writeBlock(exchange, config, verdict.categories);
                                }
                                response.getHeaders().add(REQUEST_VERDICT_HEADER, String.join(",", verdict.categories));
                                if ("REPLACE".equals(action) && verdict.sanitizedAny) {
                                    outBytes = objectMapper.writeValueAsBytes(root);
                                }
                                log.info("AI security {} on route {}: {}", action, routeId, verdict.categories);
                            }
                        }
                    } catch (DataBufferLimitException dble) {
                        throw dble;
                    } catch (Exception e) {
                        log.warn("AI security: moderation failed on route {}, forwarding original: {}",
                                routeId, e.getMessage());
                    }

                    ServerHttpRequest newRequest = decorateRequest(exchange, outBytes);
                    return chain.filter(exchange.mutate().request(newRequest).build());
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    log.warn("AI security: body exceeds maxBodySize {} on route {}", maxBodySize, routeId);
                    response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                    return response.setComplete();
                });
    }

    /** Moderate every textual message content; for REPLACE, rewrite content in place. */
    private Verdict moderateMessages(ArrayNode messages, AiSecurityConfig config, ContentModerator moderator) {
        Verdict verdict = new Verdict();
        for (JsonNode message : messages) {
            if (!(message instanceof ObjectNode)) {
                continue;
            }
            ObjectNode msg = (ObjectNode) message;
            JsonNode content = msg.get("content");
            if (content == null || !content.isTextual()) {
                continue; // multimodal / tool messages: out of MVP scope
            }
            ModerationResult result = moderator.moderate(content.asText(), config);
            if (result.isFlagged()) {
                verdict.flagged = true;
                verdict.categories.addAll(result.getCategories());
                String sanitized = result.getSanitizedText();
                if (sanitized != null && !sanitized.equals(content.asText())) {
                    msg.put("content", sanitized);
                    verdict.sanitizedAny = true;
                }
            }
        }
        return verdict;
    }

    private ContentModerator resolveModerator(AiSecurityConfig config) {
        String type = config.getModerator() == null ? "RULE" : config.getModerator().trim().toUpperCase();
        ContentModerator m = moderators.get(type);
        return m != null ? m : moderators.get("RULE");
    }

    private String normalizeAction(String action, String fallback) {
        if (action == null || action.isBlank()) {
            return fallback;
        }
        String a = action.trim().toUpperCase();
        return ("DETECT".equals(a) || "BLOCK".equals(a) || "REPLACE".equals(a)) ? a : fallback;
    }

    private Mono<Void> writeBlock(ServerWebExchange exchange, AiSecurityConfig config, Set<String> categories) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add(REQUEST_VERDICT_HEADER, String.join(",", categories));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("httpStatus", 403);
        body.put("code", 40301);
        body.put("error", "Content Blocked");
        body.put("message", config.getBlockMessage());
        body.put("categories", categories);
        body.put("data", null);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = "{\"httpStatus\":403,\"code\":40301,\"error\":\"Content Blocked\"}".getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private ServerHttpRequest decorateRequest(ServerWebExchange exchange, byte[] outBytes) {
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
                headers.setContentLength(outBytes.length);
                return headers;
            }
        };
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.AI_SECURITY;
    }

    /** Inbound moderation verdict. */
    private static final class Verdict {
        boolean flagged;
        boolean sanitizedAny;
        final Set<String> categories = new LinkedHashSet<>();
    }
}
