package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.ProviderKeyResolver;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiProxyConfig;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failover target-selection tests for {@link AiProviderRoutingFilter}.
 * The retry loop itself is driven by RetryGlobalFilter (bound RETRY strategy), which
 * re-invokes this filter; each re-invocation advances the attempt index and walks the
 * [primary, ...fallbacks] target list. These tests drive the attempt index directly.
 */
@ExtendWith(MockitoExtension.class)
class AiProviderFailoverTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";
    /** Mirrors AiProviderRoutingFilter.ATTEMPT_INDEX_ATTR (private). */
    private static final String ATTEMPT_ATTR = "ai.target.attempt";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private ProviderKeyResolver keyResolver;
    @Mock
    private GatewayFilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AiProviderRoutingFilter filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
        filter = new AiProviderRoutingFilter(strategyManager, keyResolver, new com.leoli.gateway.ai.ProviderKeyPoolSelector(), objectMapper);
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

    private AiProxyConfig configWithFallback() {
        AiProxyConfig config = new AiProxyConfig();
        config.setEnabled(true);
        config.setModelRouting(Map.of("gpt-4o", "openai"));
        config.setProviderApiKeyRefs(Map.of("openai", "ref-openai"));
        AiProxyConfig.FallbackTarget fb = new AiProxyConfig.FallbackTarget();
        fb.setProvider("deepseek");
        fb.setCredentialRef("ref-deepseek");
        config.setFallbacks(List.of(fb));
        return config;
    }

    private void stub(AiProxyConfig config) {
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_PROXY), eq(AiProxyConfig.class)))
                .thenReturn(config);
        lenient().when(keyResolver.resolveKey("ref-openai")).thenReturn(Mono.just("sk-openai"));
        lenient().when(keyResolver.resolveKey("ref-deepseek")).thenReturn(Mono.just("sk-deepseek"));
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private ServerWebExchange capturedExchange() {
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("First attempt uses the primary (model-resolved) target")
    void firstAttemptUsesPrimary() {
        MockServerWebExchange exchange = post("{\"model\":\"gpt-4o\"}");
        stub(configWithFallback());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        URI upstream = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertEquals("https://api.openai.com/v1/chat/completions", upstream.toString());
        assertEquals("Bearer sk-openai", capturedExchange().getRequest().getHeaders().getFirst("Authorization"));
    }

    @Test
    @DisplayName("Second attempt (retry) walks to the fallback target")
    void secondAttemptUsesFallback() {
        MockServerWebExchange exchange = post("{\"model\":\"gpt-4o\"}");
        exchange.getAttributes().put(ATTEMPT_ATTR, 1); // simulate RetryGlobalFilter re-invocation
        stub(configWithFallback());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        URI upstream = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertEquals("https://api.deepseek.com/chat/completions", upstream.toString());
        assertEquals("deepseek", exchange.getAttribute(AiProviderRoutingFilter.AI_PROVIDER_ATTR));
        assertEquals("Bearer sk-deepseek", capturedExchange().getRequest().getHeaders().getFirst("Authorization"));
    }

    @Test
    @DisplayName("Attempt index beyond the list clamps to the last target")
    void attemptIndexClampsToLastTarget() {
        MockServerWebExchange exchange = post("{\"model\":\"gpt-4o\"}");
        exchange.getAttributes().put(ATTEMPT_ATTR, 9);
        stub(configWithFallback());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        URI upstream = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertEquals("https://api.deepseek.com/chat/completions", upstream.toString());
    }

    @Test
    @DisplayName("Fallback without a resolvable credential is skipped")
    void fallbackWithoutCredentialSkipped() {
        AiProxyConfig config = configWithFallback();
        // deepseek fallback has an explicit credentialRef, so make a second fallback with none
        AiProxyConfig.FallbackTarget noCred = new AiProxyConfig.FallbackTarget();
        noCred.setProvider("zhipu"); // no credentialRef, and no providerApiKeyRefs entry
        config.setFallbacks(List.of(noCred));
        MockServerWebExchange exchange = post("{\"model\":\"gpt-4o\"}");
        exchange.getAttributes().put(ATTEMPT_ATTR, 1); // would be zhipu if not skipped -> clamps to primary
        stub(config);

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        URI upstream = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertEquals("https://api.openai.com/v1/chat/completions", upstream.toString(),
                "credential-less fallback must be skipped, leaving only the primary");
    }

    @Test
    @DisplayName("Primary without credential is skipped so the fallback becomes first")
    void primaryWithoutCredentialFallsToFallback() {
        AiProxyConfig config = new AiProxyConfig();
        config.setEnabled(true);
        config.setModelRouting(Map.of("gpt-4o", "openai"));
        config.setProviderApiKeyRefs(Map.of()); // no openai credential
        AiProxyConfig.FallbackTarget fb = new AiProxyConfig.FallbackTarget();
        fb.setProvider("deepseek");
        fb.setCredentialRef("ref-deepseek");
        config.setFallbacks(List.of(fb));

        MockServerWebExchange exchange = post("{\"model\":\"gpt-4o\"}");
        stub(config);

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        URI upstream = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertEquals("https://api.deepseek.com/chat/completions", upstream.toString(),
                "primary lacking a credential is skipped; fallback is attempted first");
        assertEquals("deepseek", exchange.getAttribute(AiProviderRoutingFilter.AI_PROVIDER_ATTR));
    }
}
