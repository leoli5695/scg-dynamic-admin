package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.limiter.TokenQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.model.TokenQuotaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Window rate limit wiring in {@link TokenQuotaPreCheckFilter}: exceeded window
 * rejects before any pre-deduction, passing windows are published for the
 * collector's response-phase increment, and the global scope works without a tenant.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenQuotaPreCheckWindowTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private TokenQuotaManager tokenQuotaManager;
    @Mock
    private GatewayFilterChain chain;

    private TokenQuotaPreCheckFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TokenQuotaPreCheckFilter();
        ReflectionTestUtils.setField(filter, "strategyManager", strategyManager);
        ReflectionTestUtils.setField(filter, "tokenQuotaManager", tokenQuotaManager);
        ReflectionTestUtils.setField(filter, "objectMapper", new ObjectMapper());
    }

    private MockServerWebExchange postWithTenant(String tenant) {
        MockServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .header("X-Tenant-Id", tenant)
                .body("{\"model\":\"qwen-plus\",\"messages\":[]}");
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

    private TokenQuotaConfig windowConfig() {
        TokenQuotaConfig c = new TokenQuotaConfig();
        c.setEnabled(true);
        c.setWindowLimitEnabled(true);
        c.setTokenPerMinute(100);
        c.setPreDeductStrategy("NONE");
        return c;
    }

    @Test
    @DisplayName("Exceeded window rejects with 429 before quota pre-deduction")
    void exceededWindowRejects() {
        stubConfig(windowConfig());
        when(tokenQuotaManager.windowKey(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("token_quota:win:default:t:acme:60:1");
        when(tokenQuotaManager.windowAllowed("token_quota:win:default:t:acme:60:1", 100)).thenReturn(false);
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = postWithTenant("acme");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        verify(tokenQuotaManager, never()).preDeductTokens(anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("Passing window forwards and publishes the window key for the collector")
    void passingWindowPublishesKey() {
        stubConfig(windowConfig());
        when(tokenQuotaManager.windowKey(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("token_quota:win:default:t:acme:60:1");
        when(tokenQuotaManager.windowAllowed(anyString(), eq(100L))).thenReturn(true);
        when(tokenQuotaManager.checkQuota(anyString(), any()))
                .thenReturn(TokenQuotaManager.QuotaCheckResult.allowed(0, 0));
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = postWithTenant("acme");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        @SuppressWarnings("unchecked")
        List<Object[]> keys = (List<Object[]>) exchange.getAttributes()
                .get(TokenQuotaPreCheckFilter.ATTR_WINDOW_USAGE_KEYS);
        assertNotNull(keys);
        assertEquals(1, keys.size());
        assertEquals("token_quota:win:default:t:acme:60:1", keys.get(0)[0]);
        assertEquals(60L, keys.get(0)[1]);
    }

    @Test
    @DisplayName("Global window applies even without a tenant identity")
    void globalWindowWithoutTenant() {
        TokenQuotaConfig c = windowConfig();
        c.setTokenPerMinute(0);
        c.setGlobalTokenPerMinute(50);
        stubConfig(c);
        when(tokenQuotaManager.windowKey(anyString(), anyString(), eq("global"), eq(60L)))
                .thenReturn("token_quota:win:default:global:60:1");
        when(tokenQuotaManager.windowAllowed("token_quota:win:default:global:60:1", 50)).thenReturn(false);
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .body("{\"model\":\"qwen-plus\",\"messages\":[]}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Window limit disabled keeps the legacy quota-only flow")
    void disabledWindowKeepsLegacyFlow() {
        TokenQuotaConfig c = windowConfig();
        c.setWindowLimitEnabled(false);
        stubConfig(c);
        when(tokenQuotaManager.checkQuota(anyString(), any()))
                .thenReturn(TokenQuotaManager.QuotaCheckResult.allowed(0, 0));
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = postWithTenant("acme");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        verify(tokenQuotaManager, never()).windowAllowed(anyString(), anyLong());
    }
}
