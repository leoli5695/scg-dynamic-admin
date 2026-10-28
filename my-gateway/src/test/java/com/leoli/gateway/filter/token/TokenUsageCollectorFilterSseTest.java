package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.limiter.TokenQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.model.TokenQuotaConfig;
import com.leoli.gateway.parser.TokenUsageParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link TokenUsageCollectorFilter}'s response decorator the way SCG's
 * NettyWriteResponseFilter does: for a streaming media type it calls
 * {@code writeAndFlushWith} with one group per DataBuffer.
 * <p>
 * The wire chunks below are byte-for-byte the shape dashscope compatible-mode
 * emits with {@code stream_options.include_usage} on. Netty's read boundaries do
 * NOT align with SSE event boundaries, so the same event stream is fed both as
 * clean per-event buffers and split mid-event.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenUsageCollectorFilterSseTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";
    private static final String TENANT = "t-sse";

    private static final String CONTENT_CHUNK =
            "data: {\"model\":\"qwen-plus\",\"choices\":[{\"delta\":{\"content\":\"1\"},\"index\":0,"
                    + "\"finish_reason\":null}],\"usage\":null}\n\n";
    private static final String FINISH_CHUNK =
            "data: {\"model\":\"qwen-plus\",\"choices\":[{\"delta\":{\"content\":\"\"},\"index\":0,"
                    + "\"finish_reason\":\"stop\"}],\"usage\":null}\n\n";
    private static final String USAGE_CHUNK =
            "data: {\"model\":\"qwen-plus\",\"choices\":[],\"usage\":{\"prompt_tokens\":24,"
                    + "\"completion_tokens\":50,\"total_tokens\":74}}\n\n";
    private static final String DONE_CHUNK = "data: [DONE]\n\n";

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private TokenQuotaManager tokenQuotaManager;

    @Mock
    private GatewayFilterChain chain;

    private TokenUsageCollectorFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TokenUsageCollectorFilter();
        ReflectionTestUtils.setField(filter, "strategyManager", strategyManager);
        ReflectionTestUtils.setField(filter, "tokenQuotaManager", tokenQuotaManager);
        ReflectionTestUtils.setField(filter, "tokenUsageParser", new TokenUsageParser());
        ReflectionTestUtils.setField(filter, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(filter, "maxBodySize", 65536);

        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setEnabled(true);
        strategy.setConfig(Map.of("enabled", true));
        when(strategyManager.getStrategyForRoute(anyString(), eq("TOKEN_RATE_LIMITER"))).thenReturn(strategy);

        TokenQuotaConfig config = new TokenQuotaConfig();
        config.setEnabled(true);
        config.setTenantIdSource("header");
        config.setPreDeductStrategy("NONE");
        when(strategyManager.convertStrategyConfig(anyString(), eq("TOKEN_RATE_LIMITER"), any(),
                eq(TokenQuotaConfig.class))).thenReturn(config);
    }

    /** Mimics SCG: streaming media type -> writeAndFlushWith, one group per DataBuffer. */
    private MockServerWebExchange runStream(List<String> wireChunks) {
        return runStream(wireChunks, MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private MockServerWebExchange runStream(List<String> wireChunks, String acceptHeader) {
        MockServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .header(HttpHeaders.ACCEPT, acceptHeader)
                .header("X-Tenant-Id", TENANT)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
        // The pre-check filter normally sets this; the collector reads it verbatim.
        exchange.getAttributes().put("token_quota_tenant_id", TENANT);

        when(chain.filter(any())).thenAnswer(invocation -> {
            org.springframework.web.server.ServerWebExchange ex = invocation.getArgument(0);
            return Mono.defer(() -> {
                ex.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
                Flux<Publisher<DataBuffer>> groups = Flux.fromIterable(wireChunks).map(s ->
                        Mono.just(ex.getResponse().bufferFactory().wrap(s.getBytes(StandardCharsets.UTF_8))));
                return ex.getResponse().writeAndFlushWith(groups);
            });
        });

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();
        return exchange;
    }

    private void assertCharged(long expectedTokens) {
        // Settlement is offloaded to boundedElastic, so await it.
        verify(tokenQuotaManager, timeout(5000))
                .consumeTokens(eq(TENANT), eq(expectedTokens), any(TokenQuotaConfig.class));
    }

    @Test
    void observingSseBuffersPreservesTheirOriginalReadPositionInBothWritePaths() {
        for (boolean flush : List.of(false, true)) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions").build());
            exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
            exchange.getAttributes().put("token_quota_tenant_id", TENANT);
            org.mockito.Mockito.doAnswer(invocation -> {
                org.springframework.web.server.ServerWebExchange forwarded = invocation.getArgument(0);
                forwarded.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
                DataBuffer buffer = forwarded.getResponse().bufferFactory().wrap(("skipped" + USAGE_CHUNK + DONE_CHUNK).getBytes(StandardCharsets.UTF_8));
                buffer.readPosition(7);
                return flush ? forwarded.getResponse().writeAndFlushWith(Flux.just(Mono.just(buffer)))
                        : forwarded.getResponse().writeWith(Mono.just(buffer));
            }).when(chain).filter(any());
            filter.filter(exchange, chain).block(java.time.Duration.ofSeconds(5));
            org.junit.jupiter.api.Assertions.assertEquals(USAGE_CHUNK + DONE_CHUNK, exchange.getResponse().getBodyAsString().block());
        }
    }

    @Test
    @DisplayName("Clean per-event framing settles the real 74 tokens")
    void settlesRealUsageWhenEventsArriveIntact() {
        runStream(List.of(CONTENT_CHUNK, CONTENT_CHUNK, FINISH_CHUNK, USAGE_CHUNK, DONE_CHUNK));
        assertCharged(74L);
    }

    @Test
    @DisplayName("[DONE] split across two DataBuffers still settles the real usage")
    void settlesRealUsageWhenDoneMarkerIsSplit() {
        runStream(List.of(CONTENT_CHUNK, FINISH_CHUNK, USAGE_CHUNK, "data: [DO", "NE]\n\n"));
        assertCharged(74L);
    }

    @Test
    @DisplayName("usage JSON split across two DataBuffers still settles the real usage")
    void settlesRealUsageWhenUsageObjectIsSplit() {
        int cut = USAGE_CHUNK.indexOf("\"usage\":") + "\"usage\":".length();
        runStream(List.of(CONTENT_CHUNK, FINISH_CHUNK,
                USAGE_CHUNK.substring(0, cut), USAGE_CHUNK.substring(cut), DONE_CHUNK));
        assertCharged(74L);
    }

    @Test
    @DisplayName("Whole stream coalesced into one DataBuffer still settles the real usage")
    void settlesRealUsageWhenStreamArrivesAsOneBuffer() {
        runStream(List.of(CONTENT_CHUNK + CONTENT_CHUNK + FINISH_CHUNK + USAGE_CHUNK + DONE_CHUNK));
        assertCharged(74L);
    }

    @Test
    @DisplayName("Client sending Accept:*/* still settles real usage - SSE is detected from the response Content-Type")
    void settlesRealUsageWhenClientOmitsEventStreamAcceptHeader() {
        runStream(List.of(CONTENT_CHUNK, FINISH_CHUNK, USAGE_CHUNK, DONE_CHUNK), "*/*");
        assertCharged(74L);
    }

    @Test
    @DisplayName("CRLF-delimited events, split mid-CRLF, are reassembled too")
    void settlesRealUsageWithCrlfDelimiters() {
        String crlfUsage = USAGE_CHUNK.replace("\n", "\r\n");
        int cut = crlfUsage.indexOf("\r\n\r\n") + 1;
        runStream(List.of(
                CONTENT_CHUNK.replace("\n", "\r\n"),
                crlfUsage.substring(0, cut),
                crlfUsage.substring(cut) + DONE_CHUNK.replace("\n", "\r\n")));
        assertCharged(74L);
    }
}
