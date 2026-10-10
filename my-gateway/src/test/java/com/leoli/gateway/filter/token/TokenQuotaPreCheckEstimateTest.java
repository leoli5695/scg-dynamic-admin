package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.filter.ai.AiContextLimitFilter;
import com.leoli.gateway.limiter.TokenQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.model.TokenQuotaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets
        ;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * On-demand body reading for the pre-deduct estimate: ESTIMATE joins the body and
 * pre-deducts proportionally (the old code silently degraded to the constant
 * hybridThreshold because nobody published cachedRequestBody), the attribute is
 * reused when present, NONE never touches the body, oversized bodies get 413.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenQuotaPreCheckEstimateTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private TokenQuotaManager tokenQuotaManager;
    @Mock
    private GatewayFilterChain chain;

    private TokenQuotaPreCheckFilter filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        filter = new TokenQuotaPreCheckFilter();
        ReflectionTestUtils.setField(filter, "strategyManager", strategyManager);
        ReflectionTestUtils.setField(filter, "tokenQuotaManager", tokenQuotaManager);
        ReflectionTestUtils.setField(filter, "objectMapper", new ObjectMapper());
        bufferFactory = new DefaultDataBufferFactory();
        when(tokenQuotaManager.currentPeriodStamp())
                .thenReturn(new TokenQuotaManager.QuotaPeriodStamp("202610", "20261010"));
        when(tokenQuotaManager.checkQuota(anyString(), any()))
                .thenReturn(TokenQuotaManager.QuotaCheckResult.allowed(0, 0));
        when(chain.filter(any())).thenAnswer(inv -> Mono.empty());
    }

    private MockServerWebExchange post(String body) {
        DataBuffer buffer = bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8));
        MockServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .header("X-Tenant-Id", "acme")
                .body(Flux.just(buffer));
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
        return exchange;
    }

    private void stubConfig(TokenQuotaConfig config) {
        StrategyDefinition def = new StrategyDefinition();
        def.setEnabled(true);
        def.setStrategyId("s1");
        def.setConfig(new java.util.HashMap<>(java.util.Map.of("enabled", true)));
        when(strategyManager.getStrategyForRoute(anyString(), eq("TOKEN_RATE_LIMITER"))).thenReturn(def);
        when(strategyManager.convertStrategyConfig(anyString(), eq("TOKEN_RATE_LIMITER"),
                eq(def), eq(TokenQuotaConfig.class))).thenReturn(config);
    }

    /** 300 chars of content + 16 overhead => 316/3 = 105 estimated tokens. */
    private static String chatBody() {
        return "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + "x".repeat(300) + "\"}]}";
    }

    @Test
    @DisplayName("ESTIMATE joins the body and pre-deducts proportionally (estimate * multiplier)")
    void estimateJoinsBodyAndPreDeductsProportionally() {
        TokenQuotaConfig config = new TokenQuotaConfig();
        config.setEnabled(true);
        config.setPreDeductStrategy("ESTIMATE");
        config.setEstimateMultiplier(2);
        stubConfig(config);
        when(tokenQuotaManager.preDeductTokens(anyString(), anyLong(), any(), any())).thenReturn(210L);

        MockServerWebExchange exchange = post(chatBody());
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        verify(tokenQuotaManager).preDeductTokens(eq("acme"), amount.capture(), any(), any());
        assertEquals(210L, amount.getValue(), "105 estimated * multiplier 2, not the hybridThreshold constant");
        verify(chain).filter(any());
    }

    @Test
    @DisplayName("cachedRequestBody attribute is reused without re-joining the body")
    void reusesPublishedAttribute() {
        TokenQuotaConfig config = new TokenQuotaConfig();
        config.setEnabled(true);
        config.setPreDeductStrategy("ESTIMATE");
        config.setEstimateMultiplier(1);
        stubConfig(config);
        when(tokenQuotaManager.preDeductTokens(anyString(), anyLong(), any(), any())).thenReturn(204L);

        MockServerWebExchange exchange = post("{\"model\":\"qwen-plus\",\"messages\":[]}");
        // 600 chars + 16 => 616/3 = 205? (600+16)/3 = 205.33 -> 205; use exact math below
        String published = "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + "y".repeat(584) + "\"}]}";
        exchange.getAttributes().put(AiContextLimitFilter.CACHED_REQUEST_BODY_ATTR, published);

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        verify(tokenQuotaManager).preDeductTokens(eq("acme"), amount.capture(), any(), any());
        assertEquals((584 + 16) / 3, amount.getValue(), "estimate must come from the published attribute body");
    }

    @Test
    @DisplayName("NONE strategy never touches the request body")
    void noneStrategyLeavesBodyUntouched() {
        TokenQuotaConfig config = new TokenQuotaConfig();
        config.setEnabled(true);
        config.setPreDeductStrategy("NONE");
        stubConfig(config);

        String original = chatBody();
        MockServerWebExchange exchange = post(original);
        ServerWebExchange[] forwarded = new ServerWebExchange[1];
        when(chain.filter(any())).thenAnswer(inv -> {
            forwarded[0] = inv.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(tokenQuotaManager, never()).preDeductTokens(anyString(), anyLong(), any(), any());
        assertNotNull(forwarded[0]);
        String body = DataBufferUtils.join(forwarded[0].getRequest().getBody())
                .map(b -> {
                    byte[] bytes = new byte[b.readableByteCount()];
                    b.read(bytes);
                    DataBufferUtils.release(b);
                    return new String(bytes, StandardCharsets.UTF_8);
                }).block();
        assertEquals(original, body);
    }

    @Test
    @DisplayName("Body above estimateMaxBodySize is rejected with 413 (stream partially consumed)")
    void oversizedBodyRejected() {
        TokenQuotaConfig config = new TokenQuotaConfig();
        config.setEnabled(true);
        config.setPreDeductStrategy("ESTIMATE");
        config.setEstimateMaxBodySize(1024);
        stubConfig(config);

        String big = "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + "x".repeat(2000) + "\"}]}";
        MockServerWebExchange exchange = post(big);
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
    }
}
