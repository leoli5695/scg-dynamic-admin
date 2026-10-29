package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.AiUsageResult;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiStatisticsConfig;
import com.leoli.gateway.util.RouteUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI observability filter (Higress ai-statistics counterpart).
 * <p>
 * Emits, per proxied AI request:
 * <ul>
 *   <li>metrics: {@code ai.tokens.input} / {@code ai.tokens.output} counters and
 *       {@code ai.llm.service.duration} / {@code ai.llm.first.byte.duration}
 *       timers, tagged route/model/provider/tenant (Higress' gateway-route-service-
 *       model-consumer dimensions);</li>
 *   <li>log: one structured {@code ai_log} info line with model, input/output
 *       tokens, first-byte and service durations, cache hit and session id;</li>
 *   <li>trace: the same values as tags on the current OTel span when
 *       micrometer-tracing is active.</li>
 * </ul>
 * The response decorator is strictly pass-through (timestamps only, no buffering)
 * so it cannot interfere with SSE streaming; token counts come from the settled
 * usage the collector publishes as an exchange attribute.
 * <p>
 * The recording itself lives in {@link AiStatisticsRecorder}, because a request served from the
 * semantic cache short-circuits the chain at order -242 and never reaches this filter at -43 - so the
 * cache filter has to record the hit itself. It can only do that because the recorder is a plain
 * component: {@code GlobalFilterTrackingPostProcessor} replaces every {@code GlobalFilter} bean with a
 * {@code TrackedGlobalFilter} that does not expose its delegate, so one filter cannot inject another
 * by concrete type (it fails at startup with {@code BeanNotOfRequiredTypeException}).
 *
 * @author leoli
 */
@Slf4j
@Component
public class AiStatisticsFilter implements GlobalFilter, Ordered {

    /**
     * Request start (nanoTime), stamped in the pre-phase. Kept here as an alias because callers and
     * tests reference it by this name; {@link AiStatisticsRecorder} owns the canonical constant.
     */
    public static final String ATTR_START_NS = AiStatisticsRecorder.ATTR_START_NS;

    private final AiStatisticsRecorder recorder;

    @Autowired
    public AiStatisticsFilter(AiStatisticsRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * Convenience constructor for tests that wire the collaborators by hand. Spring uses the
     * {@link AiStatisticsRecorder} constructor above.
     */
    public AiStatisticsFilter(StrategyManager strategyManager,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry,
                              Tracer tracer) {
        this(new AiStatisticsRecorder(strategyManager, objectMapper, meterRegistry, tracer));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        AiStatisticsConfig config = recorder.resolveConfig(routeId);
        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }

        exchange.getAttributes().put(ATTR_START_NS, System.nanoTime());
        Span requestSpan = recorder.currentSpan();
        AtomicLong firstChunkNs = new AtomicLong(0);

        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return super.writeWith(Flux.from(body).doOnNext(buffer -> {
                    if (buffer.readableByteCount() > 0) firstChunkNs.compareAndSet(0, System.nanoTime());
                }));
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return super.writeAndFlushWith(Flux.from(body).map(group -> Flux.from(group).doOnNext(buffer -> {
                    if (buffer.readableByteCount() > 0) firstChunkNs.compareAndSet(0, System.nanoTime());
                })));
            }
        };

        AtomicBoolean recorded = new AtomicBoolean();
        Runnable finish = () -> {
                    if (!recorded.compareAndSet(false, true)) return;
                    long finishedNs = System.nanoTime();
                    var completion = AiUsageResult.completion(exchange);
                    if (completion == null) {
                        recorder.record(exchange, config, routeId, firstChunkNs.get(), finishedNs, requestSpan);
                    } else {
                        completion.thenAccept(result -> recorder.record(exchange, config, routeId,
                                firstChunkNs.get(), finishedNs, requestSpan));
                    }
                };
        return Mono.defer(() -> chain.filter(exchange.mutate().response(decorated).build()))
                .doOnSuccess(ignored -> finish.run()).doOnError(error -> finish.run()).doOnCancel(finish);
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.AI_STATISTICS;
    }
}
