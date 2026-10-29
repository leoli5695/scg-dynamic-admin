package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.token.TokenUsageCollectorFilter;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiStatisticsConfig;
import com.leoli.gateway.model.StrategyDefinition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AiStatisticsFilter}: metric dimensions for stream and
 * non-stream responses, cache-hit zeroing, pass-through body integrity and the
 * disabled no-op.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiStatisticsFilterTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private GatewayFilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SimpleMeterRegistry meterRegistry;
    private AiStatisticsFilter filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
        meterRegistry = new SimpleMeterRegistry();
        filter = new AiStatisticsFilter(strategyManager, objectMapper, meterRegistry, null);
    }

    private MockServerWebExchange exchange() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
        return exchange;
    }

    private void stubConfig(AiStatisticsConfig config) {
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_STATISTICS),
                eq(AiStatisticsConfig.class))).thenReturn(config);
    }

    private AiStatisticsConfig enabled() {
        AiStatisticsConfig c = new AiStatisticsConfig();
        c.setEnabled(true);
        return c;
    }

    /** Chain writes a two-chunk SSE response through the decorated response. */
    private void stubStreamChain(long[] usage) {
        when(chain.filter(any())).thenAnswer(inv -> {
            ServerWebExchange ex = inv.getArgument(0);
            ex.getAttributes().put(AiProviderRoutingFilter.AI_MODEL_ATTR, "qwen-plus");
            ex.getAttributes().put(AiProviderRoutingFilter.AI_PROVIDER_ATTR, "qwen");
            ex.getAttributes().put("token_quota_tenant_id", "acme");
            if (usage != null) {
                ex.getAttributes().put(TokenUsageCollectorFilter.AI_USAGE_ATTR, usage);
            }
            ex.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            DataBuffer c1 = bufferFactory.wrap("data: {\"x\":1}\n\n".getBytes(StandardCharsets.UTF_8));
            DataBuffer c2 = bufferFactory.wrap("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            return ex.getResponse().writeAndFlushWith(Flux.just(Flux.just(c1), Flux.just(c2)));
        });
    }

    @Test
    @DisplayName("Streaming request records token counters and both duration timers")
    void streamRecordsMetrics() {
        stubConfig(enabled());
        stubStreamChain(new long[]{10, 20, 30});
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals(10.0, meterRegistry.get("ai.tokens.input")
                .tag("model", "qwen-plus").tag("tenant", "acme").counter().count());
        assertEquals(20.0, meterRegistry.get("ai.tokens.output")
                .tag("model", "qwen-plus").counter().count());
        assertEquals(1, meterRegistry.get("ai.llm.service.duration")
                .tag("model", "qwen-plus").timer().count());
        assertEquals(1, meterRegistry.get("ai.llm.first.byte.duration")
                .tag("model", "qwen-plus").timer().count());
    }

    @Test
    @DisplayName("Cache hit zeroes token counters and is flagged")
    void cacheHitZeroesTokens() {
        stubConfig(enabled());
        when(chain.filter(any())).thenAnswer(inv -> {
            ServerWebExchange ex = inv.getArgument(0);
            ex.getAttributes().put(AiSemanticCacheFilter.AI_CACHE_HIT_ATTR, Boolean.TRUE);
            ex.getAttributes().put(AiProviderRoutingFilter.AI_MODEL_ATTR, "qwen-plus");
            ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            DataBuffer body = bufferFactory.wrap("{\"cached\":true}".getBytes(StandardCharsets.UTF_8));
            return ex.getResponse().writeWith(Mono.just(body));
        });
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals(0.0, meterRegistry.get("ai.tokens.input").counter().count());
        assertEquals(0.0, meterRegistry.get("ai.tokens.output").counter().count());
        // Non-stream: no first-token timer
        assertEquals(0, meterRegistry.find("ai.llm.first.byte.duration").timers().size());
    }

    /**
     * The test above drives a hit through {@code filter()}, which is not what happens in production:
     * a real hit short-circuits at -242 so this filter never runs, and the cache filter calls
     * {@link AiStatisticsRecorder#recordShortCircuited} instead. These three cover that real path.
     */
    @Test
    @DisplayName("A short-circuited cache hit is recorded as NOT_BILLED with zero tokens")
    void shortCircuitedCacheHitIsRecorded() {
        stubConfig(enabled());
        AiStatisticsRecorder recorder =
                new AiStatisticsRecorder(strategyManager, objectMapper, meterRegistry, null);
        MockServerWebExchange exchange = exchange();
        exchange.getAttributes().put(AiStatisticsRecorder.ATTR_START_NS, System.nanoTime() - 5_000_000L);
        exchange.getAttributes().put(AiSemanticCacheFilter.AI_CACHE_HIT_ATTR, Boolean.TRUE);
        exchange.getAttributes().put(AiProviderRoutingFilter.AI_MODEL_ATTR, "qwen-plus");

        recorder.recordShortCircuited(exchange);

        // status=NOT_BILLED is what makes the hit rate measurable: it is set only when cacheHit is
        // true, so hits / sum(ai.usage.requests) is the hit ratio.
        assertEquals(1.0, meterRegistry.get("ai.usage.requests")
                .tag("status", "NOT_BILLED").counter().count());
        assertEquals(0.0, meterRegistry.get("ai.tokens.input").tag("model", "qwen-plus").counter().count(),
                "a cached answer consumed no provider tokens");
        assertEquals(0.0, meterRegistry.get("ai.tokens.output").tag("model", "qwen-plus").counter().count());
        assertEquals(1, meterRegistry.get("ai.llm.service.duration").timer().count());
        assertEquals(0, meterRegistry.find("ai.llm.first.byte.duration").timers().size(),
                "no upstream was called, so there is no first-byte instant");
    }

    @Test
    @DisplayName("Short-circuit recording respects a disabled AI_STATISTICS strategy")
    void shortCircuitIsNoOpWhenStatisticsDisabled() {
        AiStatisticsConfig disabled = new AiStatisticsConfig();
        disabled.setEnabled(false);
        stubConfig(disabled);
        AiStatisticsRecorder recorder =
                new AiStatisticsRecorder(strategyManager, objectMapper, meterRegistry, null);
        MockServerWebExchange exchange = exchange();
        exchange.getAttributes().put(AiStatisticsRecorder.ATTR_START_NS, System.nanoTime());
        exchange.getAttributes().put(AiSemanticCacheFilter.AI_CACHE_HIT_ATTR, Boolean.TRUE);

        recorder.recordShortCircuited(exchange);

        assertEquals(0, meterRegistry.find("ai.usage.requests").counters().size());
    }

    @Test
    @DisplayName("Short-circuit recording without a start time records nothing")
    void shortCircuitWithoutStartTimeIsNoOp() {
        stubConfig(enabled());
        AiStatisticsRecorder recorder =
                new AiStatisticsRecorder(strategyManager, objectMapper, meterRegistry, null);
        MockServerWebExchange exchange = exchange();
        exchange.getAttributes().put(AiSemanticCacheFilter.AI_CACHE_HIT_ATTR, Boolean.TRUE);

        recorder.recordShortCircuited(exchange);

        // Documents the precondition: record() returns early without ATTR_START_NS, which is why
        // AiSemanticCacheFilter stamps it before doing any cache work.
        assertEquals(0, meterRegistry.find("ai.usage.requests").counters().size());
    }

    @Test
    @DisplayName("Response body passes through untouched (no buffering, no rewrite)")
    void bodyPassesThrough() {
        stubConfig(enabled());
        stubStreamChain(new long[]{1, 1, 2});
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        String written = ((org.springframework.mock.http.server.reactive.MockServerHttpResponse) exchange.getResponse())
                .getBodyAsString().block();
        // writeAndFlushWith chunks are recorded by the mock response as-is
        assertNotNull(written);
        assertTrue(written.contains("\"x\":1"), written);
        assertTrue(written.contains("[DONE]"), written);
    }

    @Test
    @DisplayName("Disabled config registers no meters and leaves the chain untouched")
    void disabledNoOp() {
        AiStatisticsConfig c = new AiStatisticsConfig();
        c.setEnabled(false);
        stubConfig(c);
        stubStreamChain(new long[]{5, 5, 10});
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals(0, meterRegistry.getMeters().size());
    }

    @Test
    @DisplayName("Order sits after the token collector and before NettyWriteResponseFilter")
    void order() {
        assertEquals(FilterOrderConstants.AI_STATISTICS, filter.getOrder());
        assertTrue(filter.getOrder() > FilterOrderConstants.TOKEN_USAGE_COLLECTOR);
        assertTrue(filter.getOrder() < -1);
    }
}
