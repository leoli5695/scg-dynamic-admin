package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.LlmProvider;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiProviderRoutingFilter}: model-based provider selection,
 * upstream URL rewrite, credential injection, model-name mapping and error paths.
 */
@ExtendWith(MockitoExtension.class)
class AiProviderRoutingFilterTest {

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

    private AiProviderRoutingFilter filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
        filter = new AiProviderRoutingFilter(strategyManager, keyResolver, objectMapper);
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

    private void stubConfig(AiProxyConfig config) {
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_PROXY), eq(AiProxyConfig.class)))
                .thenReturn(config);
    }

    private AiProxyConfig enabledConfig() {
        AiProxyConfig config = new AiProxyConfig();
        config.setEnabled(true);
        return config;
    }

    private String bodyOf(org.springframework.web.server.ServerWebExchange ex) {
        DataBuffer db = ex.getRequest().getBody().blockFirst();
        if (db == null) {
            return "";
        }
        byte[] b = new byte[db.readableByteCount()];
        db.read(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Passes through when AI_PROXY config is absent")
    void passthroughWhenNoConfig() {
        MockServerWebExchange exchange = post("{\"model\":\"deepseek-chat\"}");
        stubConfig(null);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertNull(exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR));
        verify(chain).filter(exchange);
        verifyNoInteractions(keyResolver);
    }

    @Test
    @DisplayName("Routes by model, rewrites upstream URL and injects Bearer key")
    void routesByModelAndInjectsKey() {
        MockServerWebExchange exchange = post("{\"model\":\"deepseek-chat\",\"messages\":[]}");
        AiProxyConfig config = enabledConfig();
        config.setProviderApiKeyRefs(Map.of("deepseek", "ref-deepseek"));
        stubConfig(config);
        when(keyResolver.resolveKey("ref-deepseek")).thenReturn(Mono.just("sk-secret"));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        URI upstream = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertNotNull(upstream);
        assertEquals("https://api.deepseek.com/chat/completions", upstream.toString());
        assertEquals("deepseek", exchange.getAttribute(AiProviderRoutingFilter.AI_PROVIDER_ATTR));
        assertEquals("OPENAI", exchange.getAttribute(AiProviderRoutingFilter.AI_RESPONSE_FORMAT_ATTR));

        ArgumentCaptor<org.springframework.web.server.ServerWebExchange> captor =
                ArgumentCaptor.forClass(org.springframework.web.server.ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        String auth = captor.getValue().getRequest().getHeaders().getFirst("Authorization");
        assertEquals("Bearer sk-secret", auth);
    }

    @Test
    @DisplayName("Applies model routing override + model name mapping and rewrites body")
    void appliesModelMappingRewrite() {
        MockServerWebExchange exchange = post("{\"model\":\"gpt-4o\",\"messages\":[]}");
        AiProxyConfig config = enabledConfig();
        config.setModelRouting(Map.of("gpt-4o", "qwen"));
        config.setModelMapping(Map.of("gpt-4o", "qwen-max"));
        config.setProviderApiKeyRefs(Map.of("qwen", "ref-qwen"));
        stubConfig(config);
        when(keyResolver.resolveKey("ref-qwen")).thenReturn(Mono.just("sk-qwen"));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        URI upstream = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", upstream.toString());
        assertEquals("qwen-max", exchange.getAttribute(AiProviderRoutingFilter.AI_MODEL_ATTR));

        ArgumentCaptor<org.springframework.web.server.ServerWebExchange> captor =
                ArgumentCaptor.forClass(org.springframework.web.server.ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        String forwarded = bodyOf(captor.getValue());
        assertTrue(forwarded.contains("\"model\":\"qwen-max\""), "body model should be rewritten: " + forwarded);
    }

    @Test
    @DisplayName("FIXED selection ignores the request model")
    void fixedSelection() {
        MockServerWebExchange exchange = post("{\"model\":\"whatever\"}");
        AiProxyConfig config = enabledConfig();
        config.setProviderSelection("FIXED");
        config.setFixedProvider("zhipu");
        config.setProviderApiKeyRefs(Map.of("zhipu", "ref-zhipu"));
        stubConfig(config);
        when(keyResolver.resolveKey("ref-zhipu")).thenReturn(Mono.just("sk-zhipu"));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        URI upstream = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", upstream.toString());
    }

    @Test
    @DisplayName("Unknown model returns 400 and does not proxy")
    void unknownModelReturns400() {
        MockServerWebExchange exchange = post("{\"model\":\"mystery-model\"}");
        stubConfig(enabledConfig());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Provider without credential returns 500")
    void missingCredentialReturns500() {
        MockServerWebExchange exchange = post("{\"model\":\"deepseek-chat\"}");
        stubConfig(enabledConfig()); // no providerApiKeyRefs

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Unresolvable key returns 502")
    void keyResolutionFailureReturns502() {
        MockServerWebExchange exchange = post("{\"model\":\"kimi-k2\"}");
        AiProxyConfig config = enabledConfig();
        config.setProviderApiKeyRefs(Map.of("moonshot", "ref-moonshot"));
        stubConfig(config);
        when(keyResolver.resolveKey("ref-moonshot")).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Non-POST requests pass through")
    void nonPostPassesThrough() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/models").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
        stubConfig(enabledConfig());
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        verifyNoInteractions(keyResolver);
    }

    @Test
    @DisplayName("Filter order is AI_PROVIDER_ROUTING (after RouteToRequestUrlFilter)")
    void orderIsAfterRouteToRequestUrl() {
        assertEquals(10005, filter.getOrder());
        assertTrue(filter.getOrder() > 10000);
    }

    @Test
    @DisplayName("All six built-in providers are reachable via model prefixes")
    void allProvidersRoutable() {
        assertEquals(LlmProvider.OPENAI, ProviderRegistryResolve("gpt-4o"));
        assertEquals(LlmProvider.DEEPSEEK, ProviderRegistryResolve("deepseek-chat"));
        assertEquals(LlmProvider.QWEN, ProviderRegistryResolve("qwen-max"));
        assertEquals(LlmProvider.ZHIPU, ProviderRegistryResolve("glm-4"));
        assertEquals(LlmProvider.MOONSHOT, ProviderRegistryResolve("kimi-k2"));
        assertEquals(LlmProvider.ANTHROPIC, ProviderRegistryResolve("claude-3-5-sonnet"));
    }

    private LlmProvider ProviderRegistryResolve(String model) {
        return com.leoli.gateway.ai.ProviderRegistry.resolveByModel(model).orElseThrow();
    }
}
