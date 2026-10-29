package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.AiUsageResult;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recording half of AI observability, extracted from {@link AiStatisticsFilter} so that a filter
 * which short-circuits the chain can use it.
 * <p>
 * This is a plain component rather than a filter on purpose. {@code GlobalFilterTrackingPostProcessor}
 * replaces <em>every</em> {@code GlobalFilter} bean with a {@code TrackedGlobalFilter} decorator whose
 * delegate has no accessor, so injecting {@code AiStatisticsFilter} by its concrete type fails at
 * startup with {@code BeanNotOfRequiredTypeException}. A component that is not a {@code GlobalFilter}
 * is left alone by that post-processor and can be injected normally.
 * <p>
 * The reason a short-circuit needs this: {@link AiSemanticCacheFilter} serves a hit at order -242 and
 * never calls {@code chain.filter}, so {@link AiStatisticsFilter} at -43 does not run. Before this
 * extraction a cache hit emitted no {@code ai.*} metrics and no {@code ai_log} line, the
 * {@code cacheHit} branch below was unreachable, and the hit rate could not be measured anywhere -
 * the only trace was the {@code X-AI-Cache: HIT} header and one INFO log line.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AiStatisticsRecorder {

    /** Request start (nanoTime). Stamped by whichever filter sees the request first. */
    public static final String ATTR_START_NS = "ai.statistics.start.ns";

    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    @Autowired
    public AiStatisticsRecorder(StrategyManager strategyManager,
                                ObjectMapper objectMapper,
                                @Autowired(required = false) MeterRegistry meterRegistry,
                                @Autowired(required = false) Tracer tracer) {
        this.strategyManager = strategyManager;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    /** The active span, or null when micrometer-tracing is not on the classpath. */
    public Span currentSpan() {
        return tracer != null ? tracer.currentSpan() : null;
    }

    /** Route-bound config first, then the first enabled global (instance-level) one. */
    public AiStatisticsConfig resolveConfig(String routeId) {
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

    /**
     * Record a request that was served without {@link AiStatisticsFilter} ever running, i.e. a
     * semantic-cache hit.
     * <p>
     * The caller must have stamped {@link #ATTR_START_NS}; {@link #record} returns early without it.
     * {@code firstChunkNs} is passed as 0 because no upstream was called, which leaves
     * {@code llm_first_byte_duration} null exactly as it already is for a non-streaming response.
     */
    public void recordShortCircuited(ServerWebExchange exchange) {
        try {
            String routeId = RouteUtils.getRouteId(exchange);
            AiStatisticsConfig config = resolveConfig(routeId);
            if (config == null || !config.isEnabled()) {
                return;
            }
            record(exchange, config, routeId, 0L, System.nanoTime(),
                    tracer != null ? tracer.currentSpan() : null);
        } catch (Exception e) {
            // Recording runs on the response path; it must never fail the request.
            log.debug("AI statistics recording failed for a short-circuited request: {}", e.getMessage());
        }
    }

    public void record(ServerWebExchange exchange, AiStatisticsConfig config, String routeId, long firstChunkNs,
                       long finishedNs, Span requestSpan) {
        try {
            Long start = exchange.getAttribute(ATTR_START_NS);
            if (start == null) {
                return;
            }
            long serviceMs = (finishedNs - start) / 1_000_000;
            Long firstByteMs = firstChunkNs > 0 ? (firstChunkNs - start) / 1_000_000 : null;

            String model = orNone(exchange.getAttribute(AiProviderRoutingFilter.AI_MODEL_ATTR));
            String provider = orNone(exchange.getAttribute(AiProviderRoutingFilter.AI_PROVIDER_ATTR));
            String service = orNone(exchange.getAttribute(AiProviderRoutingFilter.AI_SERVICE_ATTR));
            String tenant = orNone(exchange.getAttribute("token_quota_tenant_id"));
            boolean cacheHit = Boolean.TRUE.equals(exchange.getAttribute(AiSemanticCacheFilter.AI_CACHE_HIT_ATTR));
            long[] usage = exchange.getAttribute(TokenUsageCollectorFilter.AI_USAGE_ATTR);
            AiUsageResult result = exchange.getAttribute(AiUsageResult.RESULT_ATTR);
            String usageStatus = cacheHit ? "NOT_BILLED" : result != null ? result.status().name()
                    : usage != null ? "CONFIRMED" : "UNKNOWN";
            Long inputTokens = null;
            Long outputTokens = null;
            if (cacheHit) {
                inputTokens = 0L;
                outputTokens = 0L;
            } else if (result != null) {
                inputTokens = result.inputTokens();
                outputTokens = result.outputTokens();
            } else if (usage != null) {
                inputTokens = usage[0];
                outputTokens = usage[1];
            }

            org.springframework.http.server.reactive.ServerHttpResponse response = exchange.getResponse();
            String contentType = response.getHeaders().getFirst("Content-Type");
            boolean stream = contentType != null && contentType.contains("text/event-stream");

            if (config.isEnableMetrics() && meterRegistry != null) {
                if (inputTokens != null) Counter.builder("ai.tokens.input")
                        .tag("route", orNone(routeId)).tag("model", model)
                        .tag("provider", provider).tag("tenant", tenant)
                        .register(meterRegistry).increment(inputTokens);
                if (outputTokens != null) Counter.builder("ai.tokens.output")
                        .tag("route", orNone(routeId)).tag("model", model)
                        .tag("provider", provider).tag("tenant", tenant)
                        .register(meterRegistry).increment(outputTokens);
                Counter.builder("ai.usage.requests").tag("route", orNone(routeId))
                        .tag("provider", provider).tag("status", usageStatus)
                        .register(meterRegistry).increment();
                Timer.builder("ai.llm.service.duration")
                        .tag("route", orNone(routeId)).tag("model", model).tag("provider", provider)
                        .register(meterRegistry).record(Duration.ofMillis(serviceMs));
                if (stream && firstByteMs != null) {
                    Timer.builder("ai.llm.first.byte.duration")
                            .tag("route", orNone(routeId)).tag("model", model).tag("provider", provider)
                            .register(meterRegistry).record(Duration.ofMillis(firstByteMs));
                }
            }

            Map<String, Object> aiLog = new LinkedHashMap<>();
            aiLog.put("model", model);
            aiLog.put("input_token", inputTokens);
            aiLog.put("output_token", outputTokens);
            aiLog.put("llm_first_byte_duration", firstByteMs);
            aiLog.put("usage_status", usageStatus);
            aiLog.put("llm_service_duration", serviceMs);
            aiLog.put("response_type", stream ? "stream" : "json");
            aiLog.put("cache_hit", cacheHit);
            aiLog.put("route", orNone(routeId));
            aiLog.put("provider", provider);
            aiLog.put("service", service);
            aiLog.put("tenant", tenant);
            if (config.getSessionIdHeader() != null && !config.getSessionIdHeader().isBlank()) {
                String session = exchange.getRequest().getHeaders().getFirst(config.getSessionIdHeader());
                if (session != null) {
                    aiLog.put("session_id", session);
                }
            }

            if (config.isEnableSpanTags() && tracer != null) {
                Span span = requestSpan;
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
}
