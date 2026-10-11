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
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tenant identity for token quota must come from a server-verified auth result. The
 * {@code X-Tenant-Id} header is client-supplied: honoring it in the default "combined" mode
 * let a caller spend another tenant's quota, or mint a fresh tenant per request and walk past
 * the quota altogether. {@code tenantIdSource=header} remains an explicit opt-in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenQuotaTenantSourceTest {

    private static final String ROUTE_ATTR =
            "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";
    private static final String TENANT_ATTR = "token_quota_tenant_id";

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
        when(tokenQuotaManager.checkQuota(anyString(), any()))
                .thenReturn(TokenQuotaManager.QuotaCheckResult.allowed(0, 0));
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private TokenQuotaConfig config() {
        TokenQuotaConfig c = new TokenQuotaConfig();
        c.setEnabled(true);
        c.setWindowLimitEnabled(false);
        c.setPreDeductStrategy("NONE");
        return c;
    }

    private void stubConfig(TokenQuotaConfig config) {
        StrategyDefinition def = new StrategyDefinition();
        def.setEnabled(true);
        def.setStrategyId("s1");
        def.setConfig(new HashMap<>(Map.of("enabled", true)));
        when(strategyManager.getStrategyForRoute(anyString(), eq("TOKEN_RATE_LIMITER"))).thenReturn(def);
        when(strategyManager.convertStrategyConfig(anyString(), eq("TOKEN_RATE_LIMITER"),
                eq(def), eq(TokenQuotaConfig.class))).thenReturn(config);
    }

    private MockServerWebExchange post(String tenantHeader) {
        MockServerHttpRequest.BodyBuilder builder = MockServerHttpRequest.post("/v1/chat/completions");
        if (tenantHeader != null) {
            builder.header("X-Tenant-Id", tenantHeader);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(
                builder.body("{\"model\":\"qwen-plus\",\"messages\":[]}"));
        exchange.getAttributes().put(ROUTE_ATTR, "ai-route");
        return exchange;
    }

    @Test
    @DisplayName("A client-supplied X-Tenant-Id alone selects no quota bucket")
    void headerAloneYieldsNoTenant() {
        stubConfig(config());

        MockServerWebExchange exchange = post("victim");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(chain).filter(any());
        verify(tokenQuotaManager, never()).checkQuota(anyString(), any());
        assertNull(exchange.getAttribute(TENANT_ATTR),
                "a forged header must not become the accounting tenant");
    }

    @Test
    @DisplayName("API key metadata tenant drives the quota check, not the forged header")
    void apiKeyMetadataTenantIsUsed() {
        stubConfig(config());

        MockServerWebExchange exchange = post("victim");
        exchange.getAttributes().put("api_key_metadata", Map.of("tenantId", "acme"));
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(tokenQuotaManager).checkQuota(eq("acme"), any());
        verify(tokenQuotaManager, never()).checkQuota(eq("victim"), any());
        assertEquals("acme", exchange.getAttribute(TENANT_ATTR));
    }

    @Test
    @DisplayName("JWT claim tenant is used when there is no API key metadata")
    void jwtClaimTenantIsUsed() {
        stubConfig(config());

        MockServerWebExchange exchange = post(null);
        exchange.getAttributes().put("jwt_claims", Map.of("tenant_id", "jwt-tenant"));
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(tokenQuotaManager).checkQuota(eq("jwt-tenant"), any());
    }

    @Test
    @DisplayName("API key metadata takes precedence over the JWT claim")
    void apiKeyWinsOverJwtClaim() {
        stubConfig(config());

        MockServerWebExchange exchange = post(null);
        exchange.getAttributes().put("api_key_metadata", Map.of("tenantId", "acme"));
        exchange.getAttributes().put("jwt_claims", Map.of("tenant_id", "jwt-tenant"));
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(tokenQuotaManager).checkQuota(eq("acme"), any());
        verify(tokenQuotaManager, never()).checkQuota(eq("jwt-tenant"), any());
    }

    @Test
    @DisplayName("Explicit tenantIdSource=header still honors the header (documented opt-in)")
    void explicitHeaderModeStillWorks() {
        TokenQuotaConfig c = config();
        c.setTenantIdSource("header");
        stubConfig(c);

        MockServerWebExchange exchange = post("legacy-tenant");
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify();

        verify(tokenQuotaManager).checkQuota(eq("legacy-tenant"), any());
    }
}
