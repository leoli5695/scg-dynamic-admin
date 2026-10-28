package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.cache.GatewayEmbeddingClient;
import com.leoli.gateway.ai.cache.SemanticCacheStore;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiSemanticCacheConfig;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiSemanticCacheFilter}: HIT short-circuit, MISS forwarding,
 * streaming passthrough, embedding-failure fail-open and disabled passthrough.
 */
@ExtendWith(MockitoExtension.class)
class AiSemanticCacheFilterTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private GatewayEmbeddingClient embeddingClient;
    @Mock
    private GatewayFilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SemanticCacheStore store;
    private AiSemanticCacheFilter filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
        store = new SemanticCacheStore();
        ReflectionTestUtils.setField(store, "maxEntries", 100);
        filter = new AiSemanticCacheFilter(strategyManager, objectMapper, embeddingClient, store);
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

    /** As {@link #post(String)} but with a client-supplied X-Tenant-Id on the request. */
    private MockServerWebExchange post(String body, String tenantHeader) {
        DataBuffer buffer = bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8));
        MockServerHttpRequest.BodyBuilder builder = MockServerHttpRequest.post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        if (tenantHeader != null) {
            builder.header("X-Tenant-Id", tenantHeader);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.body(Flux.just(buffer)));
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
        return exchange;
    }

    private AiSemanticCacheConfig config() {
        AiSemanticCacheConfig c = new AiSemanticCacheConfig();
        c.setEnabled(true);
        c.setEmbeddingProvider("qwen");
        c.setEmbeddingCredentialRef("ref-emb");
        c.setEmbeddingModel("text-embedding-v3");
        c.setSimilarityThreshold(0.95);
        c.setTtlSeconds(60);
        return c;
    }

    private void stubConfig(AiSemanticCacheConfig c) {
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_SEMANTIC_CACHE),
                eq(AiSemanticCacheConfig.class))).thenReturn(c);
    }

    @Test
    @DisplayName("HIT short-circuits with the cached response, chain not called")
    void hitShortCircuits() {
        stubConfig(config());
        store.put("qwen-plus|*", new float[]{1f, 0f}, "{\"cached\":true}", 60);
        when(embeddingClient.embed(anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new float[]{1f, 0f}));

        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertEquals("HIT", exchange.getResponse().getHeaders().getFirst(AiSemanticCacheFilter.CACHE_HEADER));
        String written = ((org.springframework.mock.http.server.reactive.MockServerHttpResponse) exchange.getResponse())
                .getBodyAsString().block();
        assertEquals("{\"cached\":true}", written);
    }

    @Test
    @DisplayName("MISS forwards downstream and marks the MISS header")
    void missForwards() {
        stubConfig(config());
        when(embeddingClient.embed(anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new float[]{1f, 0f}));
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        assertEquals("MISS", exchange.getResponse().getHeaders().getFirst(AiSemanticCacheFilter.CACHE_HEADER));
    }

    @Test
    void oversizedResponsesStreamUnchangedInsteadOfFailingTheRequestForCacheCapture() {
        var config = config(); config.setMaxBodySize(2048); stubConfig(config);
        when(embeddingClient.embed(anyString(), any(), any(), any(), any())).thenReturn(Mono.just(new float[]{1f, 0f}));
        var produced = new java.util.concurrent.atomic.AtomicInteger();
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var receivedBytes = new java.util.concurrent.atomic.AtomicInteger();
        var exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        exchange.getResponse().setWriteHandler(body -> body.doOnNext(buffer -> {
            assertEquals(produced.get(), consumed.incrementAndGet(), "forward each chunk before aggregating another");
            receivedBytes.addAndGet(buffer.readableByteCount());
            org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
        }).then());
        when(chain.filter(any())).thenAnswer(invocation -> {
            org.springframework.web.server.ServerWebExchange forwarded = invocation.getArgument(0);
            forwarded.getResponse().setStatusCode(HttpStatus.OK);
            forwarded.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return forwarded.getResponse().writeWith(Flux.range(0, 4).map(i -> {
                produced.incrementAndGet(); return bufferFactory.wrap(new byte[1024]);
            }));
        });
        filter.filter(exchange, chain).block(java.time.Duration.ofSeconds(5));
        assertEquals(4096, receivedBytes.get());
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertTrue(store.search("qwen-plus|*", new float[]{1f, 0f}, 0.95).isEmpty());
    }

    @Test
    @DisplayName("Streaming requests pass through without embedding (MVP non-streaming only)")
    void streamingPassthrough() {
        stubConfig(config());
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    @DisplayName("Embedding failure fails open (forwards, no cache)")
    void embeddingFailureFailsOpen() {
        stubConfig(config());
        when(embeddingClient.embed(anyString(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        assertFalse(exchange.getResponse().getHeaders().containsKey(AiSemanticCacheFilter.CACHE_HEADER));
    }

    @Test
    @DisplayName("Disabled config passes the same exchange through")
    void disabledPassthrough() {
        AiSemanticCacheConfig c = new AiSemanticCacheConfig();
        c.setEnabled(false);
        stubConfig(c);
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(exchange);
        verifyNoInteractions(embeddingClient);
    }

    @Test
    @DisplayName("Order is AI_SEMANTIC_CACHE (-242): before quota pre-check and routing, and < -1 for response capture")
    void orderIsSemanticCache() {
        assertEquals(-242, filter.getOrder());
        assertTrue(filter.getOrder() < -1);
    }

    @Test
    @DisplayName("Semantic cache runs BEFORE token pre-check so a HIT never strands a pre-deduction")
    void orderIsBeforeTokenQuotaPreCheck() {
        // A cache HIT short-circuits the chain, so the collector that settles/refunds the
        // pre-deducted estimate never runs. If the cache sat AFTER the pre-check, every hit
        // would leave the pre-deducted tokens permanently charged for zero LLM usage.
        assertTrue(FilterOrderConstants.AI_SEMANTIC_CACHE < FilterOrderConstants.TOKEN_QUOTA_PRE_CHECK,
                "AI_SEMANTIC_CACHE must run before TOKEN_QUOTA_PRE_CHECK so a hit pre-deducts nothing");
    }

    @Test
    @DisplayName("A client-supplied X-Tenant-Id with no authenticated tenant bypasses the cache")
    void unprovenTenantHeaderBypassesCache() {
        stubConfig(config());
        // An entry sitting in exactly the scope the forged header would select.
        store.put("qwen-plus|victim", new float[]{1f, 0f}, "{\"cached\":true}", 60);
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "victim");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        // Not served and not even embedded: an unprovable tenant claim selects no scope.
        verify(chain).filter(any());
        verifyNoInteractions(embeddingClient);
        assertFalse(exchange.getResponse().getHeaders().containsKey(AiSemanticCacheFilter.CACHE_HEADER));
    }

    @Test
    @DisplayName("Tenant from verified API key metadata scopes the cache and can HIT")
    void trustedTenantFromApiKeyMetadataHits() {
        stubConfig(config());
        store.put("qwen-plus|acme", new float[]{1f, 0f}, "{\"cached\":true}", 60);
        when(embeddingClient.embed(anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new float[]{1f, 0f}));

        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", null);
        exchange.getAttributes().put("api_key_metadata", Map.of("tenantId", "acme"));
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain, never()).filter(any());
        assertEquals("HIT", exchange.getResponse().getHeaders().getFirst(AiSemanticCacheFilter.CACHE_HEADER));
    }

    @Test
    @DisplayName("An authenticated tenant cannot read another tenant entry via a forged header")
    void forgedHeaderCannotCrossAuthenticatedTenant() {
        stubConfig(config());
        store.put("qwen-plus|victim", new float[]{1f, 0f}, "{\"cached\":true}", 60);
        when(embeddingClient.embed(anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new float[]{1f, 0f}));
        when(chain.filter(any())).thenReturn(Mono.empty());

        // Caller claims to be "victim" but authenticates as "acme": the verified identity wins.
        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "victim");
        exchange.getAttributes().put("jwt_claims", Map.of("tenant_id", "acme"));
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        assertEquals("MISS", exchange.getResponse().getHeaders().getFirst(AiSemanticCacheFilter.CACHE_HEADER));
    }
}
