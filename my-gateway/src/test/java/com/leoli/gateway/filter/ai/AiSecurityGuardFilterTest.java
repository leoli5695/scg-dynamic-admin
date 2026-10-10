package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.security.RuleBasedModerator;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiSecurityConfig;
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
import org.springframework.http.HttpStatus;
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
 * Unit tests for {@link AiSecurityGuardFilter} inbound moderation:
 * BLOCK (403), REPLACE (masked body), DETECT (header, body unchanged), PII and passthrough.
 */
@ExtendWith(MockitoExtension.class)
class AiSecurityGuardFilterTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private GatewayFilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AiSecurityGuardFilter filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
        filter = new AiSecurityGuardFilter(strategyManager, objectMapper, List.of(new RuleBasedModerator()));
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

    private AiSecurityConfig config(String action, String... bannedWords) {
        AiSecurityConfig c = new AiSecurityConfig();
        c.setEnabled(true);
        c.setScanRequest(true);
        c.setInboundAction(action);
        c.setBannedWords(List.of(bannedWords));
        return c;
    }

    private void stub(AiSecurityConfig config) {
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_SECURITY),
                eq(AiSecurityConfig.class))).thenReturn(config);
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
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

    @Test
    @DisplayName("BLOCK returns 403 and does not proxy")
    void blockReturns403() {
        stub(config("BLOCK", "bomb"));
        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"make a bomb\"}]}");

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("REPLACE masks the offending content and forwards")
    void replaceMasksBody() {
        stub(config("REPLACE", "bomb"));
        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"make a bomb\"}]}");

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        JsonNode body = forwardedBody();
        String content = body.get("messages").get(0).get("content").asText();
        assertFalse(content.toLowerCase().contains("bomb"), "banned word must be masked: " + content);
        assertTrue(exchange.getResponse().getHeaders().containsKey(AiSecurityGuardFilter.REQUEST_VERDICT_HEADER));
    }

    @Test
    @DisplayName("DETECT forwards unchanged but sets the verdict header")
    void detectPassesThroughWithHeader() {
        stub(config("DETECT", "bomb"));
        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"make a bomb\"}]}");

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        JsonNode body = forwardedBody();
        assertTrue(body.get("messages").get(0).get("content").asText().contains("bomb"), "DETECT must not alter the body");
        assertTrue(exchange.getResponse().getHeaders().containsKey(AiSecurityGuardFilter.REQUEST_VERDICT_HEADER));
    }

    @Test
    @DisplayName("PII is masked under REPLACE")
    void piiMaskedOnReplace() {
        AiSecurityConfig c = config("REPLACE");
        c.setEnablePiiMasking(true);
        stub(c);
        MockServerWebExchange exchange = post("{\"messages\":[{\"role\":\"user\",\"content\":\"mail a@b.com\"}]}");

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        String content = forwardedBody().get("messages").get(0).get("content").asText();
        assertFalse(content.contains("a@b.com"), "email must be masked: " + content);
    }

    @Test
    @DisplayName("Clean request passes through with no verdict header")
    void cleanPassesThrough() {
        stub(config("BLOCK", "bomb"));
        MockServerWebExchange exchange = post("{\"messages\":[{\"role\":\"user\",\"content\":\"hello there\"}]}");

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        assertFalse(exchange.getResponse().getHeaders().containsKey(AiSecurityGuardFilter.REQUEST_VERDICT_HEADER));
    }

    @Test
    @DisplayName("Disabled config passes the same exchange through")
    void disabledPassesThrough() {
        AiSecurityConfig c = new AiSecurityConfig();
        c.setEnabled(false);
        when(strategyManager.getConfig(anyString(), eq(StrategyDefinition.TYPE_AI_SECURITY),
                eq(AiSecurityConfig.class))).thenReturn(c);
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = post("{\"messages\":[{\"role\":\"user\",\"content\":\"bomb\"}]}");

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("Order is AI_SECURITY (-246): before quota, and < -1 for future outbound")
    void orderIsAiSecurity() {
        assertEquals(-246, filter.getOrder());
        assertTrue(filter.getOrder() < -1);
    }
}
