package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiContextLimitConfig;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiContextLimitFilter}: oversized rejection with the
 * OpenAI context_length_exceeded shape, pass-through within the window,
 * non-chat fail-open, disabled pass-through, body replay and filter order.
 */
@ExtendWith(MockitoExtension.class)
class AiContextLimitFilterTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private GatewayFilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AiContextLimitFilter filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
        filter = new AiContextLimitFilter(strategyManager, objectMapper);
    }

    private MockServerWebExchange post(String body) {
        DataBuffer buffer = bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8));
        MockServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.just(buffer));
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
        return exchange;
    }

    private AiContextLimitConfig config(long maxTokens) {
        AiContextLimitConfig c = new AiContextLimitConfig();
        c.setEnabled(true);
        c.setMaxContextTokens(maxTokens);
        return c;
    }

    private void stubConfig(AiContextLimitConfig c) {
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_CONTEXT_LIMIT),
                eq(AiContextLimitConfig.class))).thenReturn(c);
    }

    /** ~300 chars of text + 16 chars overhead => 316/3 = 105 estimated tokens. */
    private static String chatBody() {
        return "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + "x".repeat(300) + "\"}]}";
    }

    @Test
    @DisplayName("Oversized input is rejected with OpenAI context_length_exceeded, chain not called")
    void oversizedRejected() {
        stubConfig(config(100));
        MockServerWebExchange exchange = post(chatBody());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        String written = ((org.springframework.mock.http.server.reactive.MockServerHttpResponse) exchange.getResponse())
                .getBodyAsString().block();
        assertNotNull(written);
        assertTrue(written.contains("context_length_exceeded"), written);
        assertTrue(written.contains("invalid_request_error"), written);
    }

    @Test
    @DisplayName("Input within the window forwards with the body replayed intact")
    void withinWindowForwards() {
        stubConfig(config(200));
        MockServerWebExchange exchange = post(chatBody());
        ServerWebExchange[] forwarded = new ServerWebExchange[1];
        when(chain.filter(any())).thenAnswer(inv -> {
            forwarded[0] = inv.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertNotNull(forwarded[0]);
        String replayed = DataBufferUtils.join(forwarded[0].getRequest().getBody())
                .map(b -> {
                    byte[] bytes = new byte[b.readableByteCount()];
                    b.read(bytes);
                    DataBufferUtils.release(b);
                    return new String(bytes, StandardCharsets.UTF_8);
                }).block();
        assertEquals(chatBody(), replayed);
        // Downstream estimation sees the real body instead of its constant fallback.
        assertEquals(chatBody(), forwarded[0].getAttributes().get(AiContextLimitFilter.CACHED_REQUEST_BODY_ATTR));
    }

    @Test
    @DisplayName("buffer_ratio multiplies the estimate before comparison")
    void bufferRatioApplied() {
        // estimate 105 * 1.10 = 116 (ceil) -> over 110, under 120
        stubConfig(config(110));
        MockServerWebExchange exchange = post(chatBody());
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();
        verify(chain, never()).filter(any());

        stubConfig(config(120));
        MockServerWebExchange exchange2 = post(chatBody());
        when(chain.filter(any())).thenReturn(Mono.empty());
        StepVerifier.create(filter.filter(exchange2, chain)).expectComplete().verify();
        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("Non-chat JSON body passes through untouched (Higress parity)")
    void nonChatPassesThrough() {
        stubConfig(config(10));
        MockServerWebExchange exchange = post("{\"foo\":\"bar\"}");
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Disabled or unset maxContextTokens passes through without touching config estimation")
    void disabledPassesThrough() {
        stubConfig(config(0));
        MockServerWebExchange exchange = post(chatBody());
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
    }

    @Test
    @DisplayName("Custom error status code is honoured")
    void customErrorStatus() {
        AiContextLimitConfig c = config(100);
        c.setErrorStatusCode(413);
        stubConfig(c);
        MockServerWebExchange exchange = post(chatBody());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Order sits before the token quota pre-check so oversized requests never pre-deduct")
    void orderBeforeQuotaPreCheck() {
        assertEquals(FilterOrderConstants.AI_CONTEXT_LIMIT, filter.getOrder());
        assertTrue(filter.getOrder() < FilterOrderConstants.TOKEN_QUOTA_PRE_CHECK);
        assertTrue(filter.getOrder() > FilterOrderConstants.MULTI_DIM_RATE_LIMITER);
    }
}
