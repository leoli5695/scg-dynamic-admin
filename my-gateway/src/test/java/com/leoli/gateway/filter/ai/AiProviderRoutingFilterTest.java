package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
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

    private AiProxyConfig.Upstream service(String id, int weight, String model) {
        AiProxyConfig.Upstream up = new AiProxyConfig.Upstream();
        up.setServiceId(id);
        up.setProvider("openai");
        up.setCredentialRef("key-" + id);
        up.setBaseUrlOverride("https://" + id + ".example/v1");
        up.setWeight(weight);
        up.setModelMapping(Map.of("support-chat", model));
        return up;
    }

    @Test
    void balancesSameProviderServicesWithoutMixingCredentialsOrMappings() {
        AiProxyConfig config = enabledConfig();
        config.setUpstreams(List.of(service("a", 1, "gpt-a"), service("b", 3, "gpt-b")));
        // A stale legacy entry must not override service-level configuration.
        config.setProviderApiKeyRefs(Map.of("openai", "wrong-key"));
        stubConfig(config);
        when(keyResolver.resolveKey(anyString())).thenAnswer(inv -> Mono.just("secret-" + inv.getArgument(0)));
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        when(chain.filter(any())).thenAnswer(inv -> {
            org.springframework.web.server.ServerWebExchange ex = inv.getArgument(0);
            String id = ex.getAttribute(AiProviderRoutingFilter.AI_SERVICE_ATTR);
            counts.merge(id, 1, Integer::sum);
            assertEquals(URI.create("https://" + id + ".example/v1/chat/completions"),
                    ex.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR));
            assertEquals("Bearer secret-key-" + id, ex.getRequest().getHeaders().getFirst("Authorization"));
            assertEquals("gpt-" + id, objectMapper.readTree(bodyOf(ex)).path("model").asText());
            return Mono.empty();
        });
        for (int i = 0; i < 40; i++) {
            StepVerifier.create(filter.filter(post("{\"model\":\"support-chat\",\"messages\":[]}"), chain))
                    .verifyComplete();
        }
        assertEquals(Map.of("a", 10, "b", 30), counts);
        verify(keyResolver, never()).resolveKey("wrong-key");
    }

    @Test
    void pinsServiceOrderAcrossRetriesAndPreservesFallbackMapping() {
        AiProxyConfig config = enabledConfig();
        config.setUpstreams(List.of(service("a", 1, "gpt-a"), service("b", 1, "gpt-b")));
        AiProxyConfig.FallbackTarget fb = new AiProxyConfig.FallbackTarget();
        fb.setProvider("qwen");
        fb.setServiceId("backup");
        fb.setCredentialRef("key-backup");
        fb.setModelMapping(Map.of("support-chat", "qwen-plus"));
        config.setFallbacks(List.of(fb));
        stubConfig(config);
        when(keyResolver.resolveKey(anyString())).thenReturn(Mono.just("secret"));
        java.util.List<String> ids = new java.util.ArrayList<>();
        when(chain.filter(any())).thenAnswer(inv -> {
            org.springframework.web.server.ServerWebExchange ex = inv.getArgument(0);
            ids.add(ex.getAttribute(AiProviderRoutingFilter.AI_SERVICE_ATTR));
            return Mono.empty();
        });
        MockServerWebExchange ex = post("{\"model\":\"support-chat\"}");
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        // Another request advances the balancer; retries must retain the first request's plan.
        StepVerifier.create(filter.filter(post("{\"model\":\"support-chat\"}"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        assertEquals(List.of("a", "b", "b", "backup"), ids);
        assertEquals("qwen-plus", ex.getAttribute(AiProviderRoutingFilter.AI_MODEL_ATTR));
    }

    @Test
    void skipsUnresolvableServiceWithoutLosingOtherSameProviderService() {
        AiProxyConfig config = enabledConfig();
        config.setUpstreams(List.of(service("a", 1, "gpt-a"), service("b", 1, "gpt-b")));
        stubConfig(config);
        when(keyResolver.resolveKey("key-a")).thenReturn(Mono.empty());
        when(keyResolver.resolveKey("key-b")).thenReturn(Mono.just("secret"));
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = post("{\"model\":\"support-chat\"}");
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        assertEquals("b", ex.getAttribute(AiProviderRoutingFilter.AI_SERVICE_ATTR));
        assertEquals("gpt-b", ex.getAttribute(AiProviderRoutingFilter.AI_MODEL_ATTR));
    }

    @Test
    void unknownAliasCannotFallBackToLegacyProviderMaps() {
        AiProxyConfig config = enabledConfig();
        config.setUpstreams(List.of(service("a", 1, "gpt-a")));
        config.setProviderApiKeyRefs(Map.of("qwen", "legacy"));
        stubConfig(config);
        MockServerWebExchange ex = post("{\"model\":\"qwen-plus\"}");
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        verifyNoInteractions(keyResolver, chain);
        assertTrue(ex.getResponse().getStatusCode().isError());
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
    @DisplayName("Unresolvable key with no fallback left returns 502")
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
    @DisplayName("Unresolvable primary credential fails over to the fallback in the SAME invocation (no RETRY needed)")
    void credentialFailureFailsOverToFallbackInFilter() {
        MockServerWebExchange exchange = post("{\"model\":\"gpt-4o\",\"messages\":[]}");
        AiProxyConfig config = enabledConfig();
        config.setProviderApiKeyRefs(Map.of("openai", "ref-openai"));
        AiProxyConfig.FallbackTarget fb = new AiProxyConfig.FallbackTarget();
        fb.setProvider("deepseek");
        fb.setCredentialRef("ref-deepseek");
        config.setFallbacks(List.of(fb));
        stubConfig(config);
        // Primary credential is dead (revoked/expired); the fallback's is usable.
        when(keyResolver.resolveKey("ref-openai")).thenReturn(Mono.empty());
        when(keyResolver.resolveKey("ref-deepseek")).thenReturn(Mono.just("sk-fallback"));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        // A config-level credential failure must skip to the next target immediately, without
        // depending on a RETRY strategy to re-invoke the filter.
        verify(chain).filter(any());
        assertEquals("deepseek", exchange.getAttribute(AiProviderRoutingFilter.AI_PROVIDER_ATTR),
                "dead primary credential must fail over to the fallback provider in-filter");
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

    // ============== stream_options.include_usage injection ==============
    // Without usage in the stream the collector settles 0 tokens and the pre-deducted
    // estimate is refunded in full, i.e. streaming traffic would be free.

    private org.springframework.web.server.ServerWebExchange runStreaming(
            String body, AiProxyConfig config, String providerId, String ref) {
        MockServerWebExchange exchange = post(body);
        config.setProviderApiKeyRefs(Map.of(providerId, ref));
        stubConfig(config);
        when(keyResolver.resolveKey(ref)).thenReturn(Mono.just("sk-test"));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        ArgumentCaptor<org.springframework.web.server.ServerWebExchange> captor =
                ArgumentCaptor.forClass(org.springframework.web.server.ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Injects stream_options.include_usage on streaming requests")
    void injectsIncludeUsageOnStreamingRequest() throws Exception {
        String out = bodyOf(runStreaming(
                "{\"model\":\"qwen-plus\",\"stream\":true,\"messages\":[]}",
                enabledConfig(), "qwen", "ref-qwen"));

        JsonNode sent = objectMapper.readTree(out);
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean(false),
                "gateway must ask upstream for usage, otherwise streaming settles 0 tokens");
        assertEquals("qwen-plus", sent.path("model").asText());
    }

    @Test
    @DisplayName("Content-Length matches the rewritten body")
    void contentLengthMatchesRewrittenBody() {
        org.springframework.web.server.ServerWebExchange sent = runStreaming(
                "{\"model\":\"qwen-plus\",\"stream\":true,\"messages\":[]}",
                enabledConfig(), "qwen", "ref-qwen");

        String out = bodyOf(sent);
        assertEquals(out.getBytes(StandardCharsets.UTF_8).length,
                sent.getRequest().getHeaders().getContentLength(),
                "a stale Content-Length would truncate or hang the upstream call");
    }

    @Test
    @DisplayName("Non-streaming body is forwarded byte-identical (no re-serialization)")
    void nonStreamingBodyUntouched() {
        String original = "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String out = bodyOf(runStreaming(original, enabledConfig(), "qwen", "ref-qwen"));

        assertEquals(original, out);
    }

    @Test
    @DisplayName("stream:false is not treated as streaming")
    void explicitStreamFalseNotInjected() throws Exception {
        String out = bodyOf(runStreaming(
                "{\"model\":\"qwen-plus\",\"stream\":false,\"messages\":[]}",
                enabledConfig(), "qwen", "ref-qwen"));

        assertTrue(objectMapper.readTree(out).path("stream_options").isMissingNode());
    }

    @Test
    @DisplayName("response_format passes through untouched (native structured output needs no gateway plugin)")
    void responseFormatPassesThrough() throws Exception {
        String out = bodyOf(runStreaming(
                "{\"model\":\"qwen-plus\",\"stream\":true,\"response_format\":"
                        + "{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"t\",\"schema\":{\"type\":\"object\"}}},"
                        + "\"messages\":[]}",
                enabledConfig(), "qwen", "ref-qwen"));

        JsonNode sent = objectMapper.readTree(out);
        assertEquals("json_schema", sent.path("response_format").path("type").asText());
        assertEquals("t", sent.path("response_format").path("json_schema").path("name").asText());
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean(false));
    }

    @Test
    @DisplayName("Preserves other client stream_options keys and overrides include_usage:false")
    void preservesClientStreamOptions() throws Exception {
        String out = bodyOf(runStreaming(
                "{\"model\":\"qwen-plus\",\"stream\":true,"
                        + "\"stream_options\":{\"continuous_usage_stats\":true,\"include_usage\":false},"
                        + "\"messages\":[]}",
                enabledConfig(), "qwen", "ref-qwen"));

        JsonNode options = objectMapper.readTree(out).path("stream_options");
        assertTrue(options.path("include_usage").asBoolean(false));
        assertTrue(options.path("continuous_usage_stats").asBoolean(false),
                "client-supplied stream_options keys must survive");
    }

    @Test
    @DisplayName("Already-requested usage is left as-is (idempotent)")
    void idempotentWhenAlreadyRequested() throws Exception {
        String out = bodyOf(runStreaming(
                "{\"model\":\"qwen-plus\",\"stream\":true,"
                        + "\"stream_options\":{\"include_usage\":true},\"messages\":[]}",
                enabledConfig(), "qwen", "ref-qwen"));

        JsonNode sent = objectMapper.readTree(out);
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean(false));
        assertEquals(1, sent.path("stream_options").size());
    }

    @Test
    @DisplayName("Model mapping and usage injection happen in one rewrite pass")
    void modelMappingAndUsageInjectionTogether() throws Exception {
        AiProxyConfig config = enabledConfig();
        config.setModelRouting(Map.of("gpt-4o", "qwen"));
        config.setModelMapping(Map.of("gpt-4o", "qwen-max"));

        String out = bodyOf(runStreaming(
                "{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[]}",
                config, "qwen", "ref-qwen"));

        JsonNode sent = objectMapper.readTree(out);
        assertEquals("qwen-max", sent.path("model").asText());
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean(false));
    }

    @Test
    @DisplayName("Malformed JSON body is forwarded rather than rejected by the rewrite")
    void malformedBodyForwarded() {
        AiProxyConfig config = enabledConfig();
        config.setDefaultModel("qwen-plus");
        String original = "{\"model\":\"qwen-plus\",\"stream\":true,,,}";
        String out = bodyOf(runStreaming(original, config, "qwen", "ref-qwen"));

        assertEquals(original, out);
    }
}
