package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.ProviderKeyPoolSelector;
import com.leoli.gateway.ai.ProviderKeyResolver;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiProxyConfig;
import com.leoli.gateway.model.StrategyDefinition;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Routing-level key pool behaviour: the provider router draws credentials from
 * the configured pool (round-robin across requests) and releases least-inflight
 * slots once the downstream exchange terminates.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiProviderKeyPoolRoutingTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private ProviderKeyResolver keyResolver;
    @Mock
    private GatewayFilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ProviderKeyPoolSelector selector;
    private AiProviderRoutingFilter filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
        selector = new ProviderKeyPoolSelector();
        filter = new AiProviderRoutingFilter(strategyManager, keyResolver, selector, objectMapper);
        when(keyResolver.resolveKey(anyString())).thenReturn(Mono.just("sk-test"));
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange post() {
        String body = "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        DataBuffer buffer = bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8));
        MockServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.just(buffer));
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
        return exchange;
    }

    private AiProxyConfig poolConfig(String strategy) {
        AiProxyConfig config = new AiProxyConfig();
        config.setEnabled(true);
        AiProxyConfig.KeyPool pool = new AiProxyConfig.KeyPool();
        pool.setStrategy(strategy);
        pool.setCredentialRefs(List.of("ref-a", "ref-b"));
        config.setProviderKeyPools(Map.of("qwen", pool));
        return config;
    }

    @Test
    @DisplayName("Consecutive requests rotate across the pool's credentials")
    void rotatesAcrossRequests() {
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_PROXY),
                eq(AiProxyConfig.class))).thenReturn(poolConfig("ROUND_ROBIN"));

        StepVerifier.create(filter.filter(post(), chain)).expectComplete().verify();
        StepVerifier.create(filter.filter(post(), chain)).expectComplete().verify();

        verify(keyResolver).resolveKey("ref-a");
        verify(keyResolver).resolveKey("ref-b");
    }

    @Test
    @DisplayName("Least-inflight pool releases the slot after the exchange terminates")
    void leastInflightReleasedAfterCompletion() {
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_PROXY),
                eq(AiProxyConfig.class))).thenReturn(poolConfig("LEAST_INFLIGHT"));

        StepVerifier.create(filter.filter(post(), chain)).expectComplete().verify();

        // First request took ref-a (tie -> pool order); slot must be released on completion.
        verify(keyResolver).resolveKey("ref-a");
        assertEqualsInflight(0, "ref-a");
        assertEqualsInflight(0, "ref-b");
    }

    private void assertEqualsInflight(int expected, String ref) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, selector.inflight(ref));
    }
}
