package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiPromptTemplateConfig;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiPromptTemplateFilter}: system-prompt injection
 * (PREPEND/OVERRIDE), {{var}} substitution, model override, named-template
 * selection and control-field stripping.
 */
@ExtendWith(MockitoExtension.class)
class AiPromptTemplateFilterTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private GatewayFilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AiPromptTemplateFilter filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
        filter = new AiPromptTemplateFilter(strategyManager, objectMapper);
    }

    private MockServerWebExchange post(String body, String... headers) {
        DataBuffer buffer = bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8));
        MockServerHttpRequest.BodyBuilder b = MockServerHttpRequest.post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(b.body(Flux.just(buffer)));
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
        return exchange;
    }

    private void stub(AiPromptTemplateConfig config) {
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_PROMPT_TEMPLATE),
                eq(AiPromptTemplateConfig.class))).thenReturn(config);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private JsonNode forwardedBody() {
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        DataBuffer db = captor.getValue().getRequest().getBody().blockFirst();
        assertNotNull(db);
        byte[] b = new byte[db.readableByteCount()];
        db.read(b);
        try {
            return objectMapper.readTree(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AiPromptTemplateConfig enabled() {
        AiPromptTemplateConfig c = new AiPromptTemplateConfig();
        c.setEnabled(true);
        return c;
    }

    @Test
    @DisplayName("Injects rendered system prompt (PREPEND) and strips variables")
    void injectsSystemPromptAndStripsVariables() {
        AiPromptTemplateConfig config = enabled();
        config.setSystemPrompt("You are {{name}}'s assistant");
        stub(config);

        MockServerWebExchange exchange = post(
                "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"variables\":{\"name\":\"Bob\"}}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        JsonNode body = forwardedBody();
        JsonNode msgs = body.get("messages");
        assertEquals(2, msgs.size());
        assertEquals("system", msgs.get(0).get("role").asText());
        assertEquals("You are Bob's assistant", msgs.get(0).get("content").asText());
        assertEquals("user", msgs.get(1).get("role").asText());
        assertFalse(body.has("variables"), "variables control field must be stripped");
    }

    @Test
    @DisplayName("OVERRIDE replaces existing system messages")
    void overrideReplacesExistingSystem() {
        AiPromptTemplateConfig config = enabled();
        config.setSystemPrompt("new system");
        config.setSystemPromptMode("OVERRIDE");
        stub(config);

        MockServerWebExchange exchange = post(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"old\"},{\"role\":\"user\",\"content\":\"hi\"}]}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        JsonNode msgs = forwardedBody().get("messages");
        assertEquals(2, msgs.size());
        assertEquals("new system", msgs.get(0).get("content").asText());
        assertEquals("user", msgs.get(1).get("role").asText());
    }

    @Test
    @DisplayName("Model override rewrites the request model")
    void modelOverride() {
        AiPromptTemplateConfig config = enabled();
        config.setModel("qwen-max");
        stub(config);

        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals("qwen-max", forwardedBody().get("model").asText());
    }

    @Test
    @DisplayName("Named template selected by header wins over default")
    void namedTemplateByHeader() {
        AiPromptTemplateConfig config = enabled();
        config.setSystemPrompt("default system");
        AiPromptTemplateConfig.PromptTemplate pirate = new AiPromptTemplateConfig.PromptTemplate();
        pirate.setName("pirate");
        pirate.setSystemPrompt("Talk like a pirate");
        config.setTemplates(List.of(pirate));
        stub(config);

        MockServerWebExchange exchange = post(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "X-Prompt-Template", "pirate");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        JsonNode msgs = forwardedBody().get("messages");
        assertEquals("Talk like a pirate", msgs.get(0).get("content").asText());
    }

    @Test
    @DisplayName("Unknown placeholder is left intact")
    void unknownPlaceholderLeftIntact() {
        AiPromptTemplateConfig config = enabled();
        config.setSystemPrompt("Hello {{missing}}");
        stub(config);

        MockServerWebExchange exchange = post("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals("Hello {{missing}}", forwardedBody().get("messages").get(0).get("content").asText());
    }

    @Test
    @DisplayName("Disabled config passes through untouched")
    void disabledPassesThrough() {
        AiPromptTemplateConfig config = new AiPromptTemplateConfig();
        config.setEnabled(false);
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_PROMPT_TEMPLATE),
                eq(AiPromptTemplateConfig.class))).thenReturn(config);
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = post("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(exchange); // same exchange, no decoration
    }

    @Test
    @DisplayName("Filter order is just before AI provider routing")
    void orderIsBeforeProviderRouting() {
        assertEquals(10004, filter.getOrder());
        assertTrue(filter.getOrder() < 10005);
    }
}
