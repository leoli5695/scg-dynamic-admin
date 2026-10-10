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
        assertEquals(1, meterRegistry.get("ai.llm.first.token.duration")
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
        assertEquals(0, meterRegistry.find("ai.llm.first.token.duration").timers().size());
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
