package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.token.TokenUsageCollectorFilter;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiStatisticsConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI observability filter (Higress ai-statistics counterpart).
 * <p>
 * Emits, per proxied AI request:
 * <ul>
 *   <li>metrics: {@code ai.tokens.input} / {@code ai.tokens.output} counters and
 *       {@code ai.llm.service.duration} / {@code ai.llm.first.token.duration}
 *       timers, tagged route/model/provider/tenant (Higress' gateway-route-service-
 *       model-consumer dimensions);</li>
 *   <li>log: one structured {@code ai_log} info line with model, input/output
 *       tokens, first-token and service durations, cache hit and session id;</li>
 *   <li>trace: the same values as tags on the current OTel span when
 *       micrometer-tracing is active.</li>
 * </ul>
 * The response decorator is strictly pass-through (timestamps only, no buffering)
 * so it cannot interfere with SSE streaming; token counts come from the settled
 * usage the collector publishes as an exchange attribute.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AiStatisticsFilter implements GlobalFilter, Ordered {

    /** Request start (nanoTime) recorded in the pre-phase. */
    public static final String ATTR_START_NS = "ai.statistics.start.ns";

    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    @Autowired
    public AiStatisticsFilter(StrategyManager strategyManager,
                              ObjectMapper objectMapper,
                              @Autowired(required = false) MeterRegistry meterRegistry,
                              @Autowired(required = false) Tracer tracer) {
        this.strategyManager = strategyManager;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        AiStatisticsConfig config = resolveConfig(routeId);
        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }

        exchange.getAttributes().put(ATTR_START_NS, System.nanoTime());
        AtomicLong firstChunkNs = new AtomicLong(0);

        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                firstChunkNs.compareAndSet(0, System.nanoTime());
                return super.writeWith(body);
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                firstChunkNs.compareAndSet(0, System.nanoTime());
                return super.writeAndFlushWith(body);
            }
        };

        return chain.filter(exchange.mutate().response(decorated).build())
                .doFinally(signal -> record(exchange, config, routeId, firstChunkNs.get()));
    }

    private void record(ServerWebExchange exchange, AiStatisticsConfig config, String routeId, long firstChunkNs) {
        try {
            Long start = exchange.getAttribute(ATTR_START_NS);
            if (start == null) {
                return;
            }
            long now = System.nanoTime();
            long serviceMs = (now - start) / 1_000_000;
            Long firstTokenMs = firstChunkNs > 0 ? (firstChunkNs - start) / 1_000_000 : null;

            String model = orNone(exchange.getAttribute(AiProviderRoutingFilter.AI_MODEL_ATTR));
            String provider = orNone(exchange.getAttribute(AiProviderRoutingFilter.AI_PROVIDER_ATTR));
            String tenant = orNone(exchange.getAttribute("token_quota_tenant_id"));
            boolean cacheHit = Boolean.TRUE.equals(exchange.getAttribute(AiSemanticCacheFilter.AI_CACHE_HIT_ATTR));
            long[] usage = exchange.getAttribute(TokenUsageCollectorFilter.AI_USAGE_ATTR);
            long inputTokens = cacheHit ? 0 : (usage != null ? usage[0] : 0);
            long outputTokens = cacheHit ? 0 : (usage != null ? usage[1] : 0);

            ServerHttpResponse response = exchange.getResponse();
            String contentType = response.getHeaders().getFirst("Content-Type");
            boolean stream = contentType != null && contentType.contains("text/event-stream");

            if (config.isEnableMetrics() && meterRegistry != null) {
                Counter.builder("ai.tokens.input")
                        .tag("route", orNone(routeId)).tag("model", model)
                        .tag("provider", provider).tag("tenant", tenant)
                        .register(meterRegistry).increment(inputTokens);
                Counter.builder("ai.tokens.output")
                        .tag("route", orNone(routeId)).tag("model", model)
                        .tag("provider", provider).tag("tenant", tenant)
                        .register(meterRegistry).increment(outputTokens);
                Timer.builder("ai.llm.service.duration")
                        .tag("route", orNone(routeId)).tag("model", model).tag("provider", provider)
                        .register(meterRegistry).record(Duration.ofMillis(serviceMs));
                if (stream && firstTokenMs != null) {
                    Timer.builder("ai.llm.first.token.duration")
                            .tag("route", orNone(routeId)).tag("model", model).tag("provider", provider)
                            .register(meterRegistry).record(Duration.ofMillis(firstTokenMs));
                }
            }

            Map<String, Object> aiLog = new LinkedHashMap<>();
            aiLog.put("model", model);
            aiLog.put("input_token", inputTokens);
            aiLog.put("output_token", outputTokens);
            aiLog.put("llm_first_token_duration", firstTokenMs);
            aiLog.put("llm_service_duration", serviceMs);
            aiLog.put("response_type", stream ? "stream" : "json");
            aiLog.put("cache_hit", cacheHit);
            aiLog.put("route", orNone(routeId));
            aiLog.put("provider", provider);
            aiLog.put("tenant", tenant);
            if (config.getSessionIdHeader() != null && !config.getSessionIdHeader().isBlank()) {
                String session = exchange.getRequest().getHeaders().getFirst(config.getSessionIdHeader());
                if (session != null) {
                    aiLog.put("session_id", session);
                }
            }

            if (config.isEnableSpanTags() && tracer != null) {
                Span span = tracer.currentSpan();
                if (span != null) {
                    aiLog.forEach((k, v) -> {
                        if (v != null) {
                            span.tag("ai." + k, String.valueOf(v));
                        }
                    });
                }
            }

            if (config.isEnableLog()) {
                log.info("ai_log: {}", objectMapper.writeValueAsString(aiLog));
            }
        } catch (Exception e) {
            log.debug("AI statistics recording failed on route {}: {}", routeId, e.getMessage());
        }
    }

    private String orNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    /** Route-bound config first, then the first enabled global (instance-level) one. */
    private AiStatisticsConfig resolveConfig(String routeId) {
        AiStatisticsConfig config = strategyManager.getConfig(routeId,
                StrategyDefinition.TYPE_AI_STATISTICS, AiStatisticsConfig.class);
        if (config != null) {
            return config;
        }
        try {
            List<StrategyDefinition> globals =
                    strategyManager.getGlobalStrategiesByType(StrategyDefinition.TYPE_AI_STATISTICS);
            if (globals != null) {
                for (StrategyDefinition global : globals) {
                    if (global != null && global.isEnabled()) {
                        return strategyManager.convertStrategyConfig("global:" + global.getStrategyId(),
                                StrategyDefinition.TYPE_AI_STATISTICS, global, AiStatisticsConfig.class);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("No global AI statistics config: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.AI_STATISTICS;
    }
}
