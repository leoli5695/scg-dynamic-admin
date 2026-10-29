package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.AiUsageResult;
import com.leoli.gateway.limiter.TokenQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.TokenQuotaConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.parser.TokenUsageParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The response-write path of {@link TokenUsageCollectorFilter} for bodies larger than
 * {@code gateway.token-usage.max-body-size}.
 * <p>
 * A chunked response carries no Content-Length, and this path used to hand it to
 * {@code DataBufferUtils.join(flux, maxBodySize)}. {@code join} releases the buffers it has already
 * consumed when the limit trips, so the body was unrecoverable and the filter answered 502 —
 * discarding an answer the upstream had already produced in full. Chunked transfer is normal for LLM
 * providers and intermediate proxies, so that was the common case rather than an edge case.
 * <p>
 * These tests pin the replacement behaviour: the client always receives the complete body, and
 * metering degrades to {@code UNKNOWN} — which retains the pre-deduction for manual review — instead
 * of failing the request. Aggregation is now reserved for responses that declare a Content-Length
 * within the bound, which is the only case where the {@code X-Token-Usage-*} headers can still be
 * set before the response is committed.
 */
class TokenUsageOversizedResponseTest {

    private static final TokenQuotaManager.QuotaPeriodStamp STAMP =
            new TokenQuotaManager.QuotaPeriodStamp("202610", "20261028");

    private final StrategyManager strategies = mock(StrategyManager.class);
    private final TokenQuotaManager quota = mock(TokenQuotaManager.class);
    private final TokenUsageReporter reporter = mock(TokenUsageReporter.class);
    private final TokenQuotaConfig config = new TokenQuotaConfig();
    private final TokenUsageCollectorFilter collector = new TokenUsageCollectorFilter();
    private final DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();

    /** Deliberately tiny so an ordinary completion exceeds it, while a bare usage object still fits. */
    private static final int MAX_BODY_SIZE = 128;

    @BeforeEach
    void setUp() {
        config.setEnabled(true);
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setEnabled(true);
        strategy.setConfig(Map.of("enabled", true));
        when(strategies.getStrategyForRoute(anyString(), eq("TOKEN_RATE_LIMITER"))).thenReturn(strategy);
        when(strategies.convertStrategyConfig(anyString(), eq("TOKEN_RATE_LIMITER"), any(), eq(TokenQuotaConfig.class)))
                .thenReturn(config);
        ReflectionTestUtils.setField(collector, "strategyManager", strategies);
        ReflectionTestUtils.setField(collector, "tokenQuotaManager", quota);
        ReflectionTestUtils.setField(collector, "tokenUsageReporter", reporter);
        ReflectionTestUtils.setField(collector, "tokenUsageParser", new TokenUsageParser());
        ReflectionTestUtils.setField(collector, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(collector, "maxBodySize", MAX_BODY_SIZE);
    }

    /**
     * Drives the collector over an upstream response written as several buffers.
     *
     * @param contentLength when non-null the upstream declares it, which is what lets the filter
     *                      aggregate and set the usage headers before committing
     */
    private MockServerWebExchange run(String body, int chunkSize, Integer contentLength) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
        exchange.getAttributes().put("token_quota_tenant_id", "tenant");
        exchange.getAttributes().put("token_quota_pre_deducted", 100L);
        exchange.getAttributes().put(TokenQuotaPreCheckFilter.ATTR_PERIOD_STAMP, STAMP);
        exchange.getAttributes().put("ai.model", "gpt-test");

        StepVerifier.create(collector.filter(exchange, downstream -> {
            downstream.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            if (contentLength != null) {
                downstream.getResponse().getHeaders().setContentLength(contentLength);
            }
            return downstream.getResponse().writeWith(split(body, chunkSize));
        })).verifyComplete();
        return exchange;
    }

    private Flux<DataBuffer> split(String body, int chunkSize) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return Flux.generate(() -> 0, (position, sink) -> {
            if (position >= bytes.length) {
                sink.complete();
                return position;
            }
            int end = Math.min(position + chunkSize, bytes.length);
            sink.next(buffers.wrap(java.util.Arrays.copyOfRange(bytes, position, end)));
            return end;
        });
    }

    private static String bodyOf(int padding) {
        // usage sits at the end, exactly where a truncated buffer loses it
        return "{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(padding)
                + "\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";
    }

    @Test
    @DisplayName("A chunked body over the limit reaches the client complete, with no 502")
    void oversizedChunkedBodyIsStreamedThroughIntact() {
        String body = bodyOf(400);
        assertTrue(body.length() > MAX_BODY_SIZE, "test body must exceed max-body-size");

        MockServerWebExchange exchange = run(body, 37, null);

        // MockServerHttpResponse leaves the status null unless something sets it, so the
        // assertion that matters is that metering did not stamp an error onto a valid answer.
        assertNotEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode(),
                "metering must not turn a valid upstream answer into a 502");
        String delivered = ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block();
        assertEquals(body, delivered, "the client must receive every byte the upstream produced");
    }

    @Test
    @DisplayName("An oversized body settles as UNKNOWN rather than as a confirmed zero")
    void oversizedBodySettlesAsUnknownNotConfirmedZero() throws Exception {
        MockServerWebExchange exchange = run(bodyOf(400), 41, null);

        AiUsageResult result = AiUsageResult.completion(exchange).get(5, TimeUnit.SECONDS);
        assertNotNull(result, "settlement must still publish a result");
        assertEquals(AiUsageResult.Status.UNKNOWN, result.status(),
                "a truncated buffer cannot be parsed; billing it as a confirmed 0 would undercharge silently");
    }

    @Test
    @DisplayName("A small body with a declared length still aggregates and sets the usage headers")
    void smallBodyWithContentLengthStillSetsUsageHeaders() {
        String body = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";
        assertTrue(body.length() <= MAX_BODY_SIZE, "test body must fit within max-body-size");

        MockServerWebExchange exchange = run(body, body.length(), body.getBytes(StandardCharsets.UTF_8).length);

        assertNotEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
        assertEquals(body, ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block());
        assertEquals("30", exchange.getResponse().getHeaders().getFirst("X-Token-Usage-Total"),
                "aggregation is what makes the usage headers reachable before the response commits");
    }

    @Test
    @DisplayName("A declared length above the limit streams through instead of being aggregated")
    void knownOversizedLengthIsNotAggregated() {
        String body = bodyOf(400);

        MockServerWebExchange exchange = run(body, 64, body.getBytes(StandardCharsets.UTF_8).length);

        assertNotEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
        assertEquals(body, ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block());
    }
}
