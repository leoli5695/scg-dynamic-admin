package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.AiUsageResult;
import com.leoli.gateway.filter.ai.AiStatisticsFilter;
import com.leoli.gateway.limiter.TokenQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.*;
import com.leoli.gateway.parser.TokenUsageParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiUsageLifecycleTest {
    private static final TokenQuotaManager.QuotaPeriodStamp STAMP = new TokenQuotaManager.QuotaPeriodStamp("202610", "20261028");
    private final StrategyManager strategies = mock(StrategyManager.class);
    private final TokenQuotaManager quota = mock(TokenQuotaManager.class);
    private final TokenUsageReporter reporter = mock(TokenUsageReporter.class);
    private final TokenQuotaConfig config = new TokenQuotaConfig();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TokenUsageCollectorFilter collector = new TokenUsageCollectorFilter();
    private AiStatisticsFilter statistics;

    private MockServerWebExchange runWithoutQuota(String body, boolean streaming, Map<String, Object> identity) {
        when(strategies.getStrategyForRoute(anyString(), eq("TOKEN_RATE_LIMITER"))).thenReturn(null);
        var proxy = new AiProxyConfig();
        proxy.setEnabled(true);
        when(strategies.getConfig(anyString(), eq("AI_PROXY"), eq(AiProxyConfig.class))).thenReturn(proxy);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions")
                .header("X-Tenant-Id", "spoofed-tenant"));
        exchange.getAttributes().putAll(identity);
        collector.filter(exchange, terminal -> {
            terminal.getAttributes().put("ai.model", "gpt-test");
            terminal.getAttributes().put("ai.provider", "openai");
            terminal.getResponse().getHeaders().setContentType(streaming ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON);
            var bytes = Mono.just(new DefaultDataBufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8)));
            return streaming ? terminal.getResponse().writeAndFlushWith(Mono.just(bytes)) : terminal.getResponse().writeWith(bytes);
        }).block();
        verify(reporter, timeout(3000)).report(anyMap());
        return exchange;
    }

    @Test void aiUsageIsCollectedWithoutQuotaOrTenantAndWithoutChargingRedis() {
        runWithoutQuota("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}", false, Map.of());
        verify(reporter).report(argThat(row -> Long.valueOf(30).equals(row.get("totalTokens"))
                && TokenUsageCollectorFilter.UNASSIGNED_TENANT.equals(row.get("tenantId"))
                && "NOT_REQUIRED".equals(row.get("settlementStatus")) && Long.valueOf(0).equals(row.get("chargedTokens"))));
        verifyNoInteractions(quota);
    }

    @Test void aiMeteringWorksWithoutQuotaManagerAndUsesVerifiedTenant() {
        ReflectionTestUtils.setField(collector, "tokenQuotaManager", null);
        runWithoutQuota("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}", false,
                Map.of("api_key_metadata", Map.of("tenantId", "verified-tenant")));
        verify(reporter).report(argThat(row -> "verified-tenant".equals(row.get("tenantId"))
                && Long.valueOf(30).equals(row.get("totalTokens"))));
        verifyNoInteractions(quota);
    }

    @Test void streamingAiUsageIsCollectedWithoutQuota() {
        runWithoutQuota("data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}\n\ndata: [DONE]\n\n", true, Map.of());
        verify(reporter).report(argThat(row -> Long.valueOf(30).equals(row.get("totalTokens"))
                && "CONFIRMED".equals(row.get("usageStatus"))));
        verifyNoInteractions(quota);
    }

    @Test void missingUsageIsVisibleEvenWithoutQuota() {
        runWithoutQuota("{\"choices\":[]}", false, Map.of());
        verify(reporter).report(argThat(row -> "UNKNOWN".equals(row.get("usageStatus"))
                && row.get("totalTokens") == null));
    }

    @Test void oversizedAiResponsePassesThroughInsteadOfFailingMetering() {
        String body = "{\"content\":\"" + "x".repeat(70000) + "\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}";
        var exchange = runWithoutQuota(body, false, Map.of());
        assertEquals(body, exchange.getResponse().getBodyAsString().block());
        verify(reporter).report(argThat(row -> "UNKNOWN".equals(row.get("usageStatus"))));
    }

    @Test void enabledQuotaWithoutTenantStillCollectsAiUsage() {
        var proxy = new AiProxyConfig(); proxy.setEnabled(true);
        when(strategies.getConfig(anyString(), eq("AI_PROXY"), eq(AiProxyConfig.class))).thenReturn(proxy);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
        collector.filter(exchange, terminal -> terminal.getResponse().writeWith(Mono.just(new DefaultDataBufferFactory()
                .wrap("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}".getBytes(StandardCharsets.UTF_8))))).block();
        verify(reporter, timeout(3000)).report(argThat(row -> Long.valueOf(30).equals(row.get("totalTokens"))));
        verifyNoInteractions(quota);
    }

    @BeforeEach void setup() {
        config.setEnabled(true);
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setEnabled(true);
        strategy.setConfig(Map.of("enabled", true));
        when(strategies.getStrategyForRoute(anyString(), eq("TOKEN_RATE_LIMITER"))).thenReturn(strategy);
        when(strategies.convertStrategyConfig(anyString(), eq("TOKEN_RATE_LIMITER"), any(), eq(TokenQuotaConfig.class)))
                .thenReturn(config);
        AiStatisticsConfig statisticsConfig = new AiStatisticsConfig();
        statisticsConfig.setEnabled(true);
        when(strategies.getConfig(anyString(), eq("AI_STATISTICS"), eq(AiStatisticsConfig.class))).thenReturn(statisticsConfig);
        ReflectionTestUtils.setField(collector, "strategyManager", strategies);
        ReflectionTestUtils.setField(collector, "tokenQuotaManager", quota);
        ReflectionTestUtils.setField(collector, "tokenUsageReporter", reporter);
        ReflectionTestUtils.setField(collector, "tokenUsageParser", new TokenUsageParser());
        ReflectionTestUtils.setField(collector, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(collector, "maxBodySize", 65536);
        statistics = new AiStatisticsFilter(strategies, new ObjectMapper(), meters, null);
    }

    private MockServerWebExchange run(String body) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
        exchange.getAttributes().put("token_quota_tenant_id", "tenant");
        exchange.getAttributes().put("token_quota_pre_deducted", 100L);
        exchange.getAttributes().put(TokenQuotaPreCheckFilter.ATTR_PERIOD_STAMP, STAMP);
        exchange.getAttributes().put("ai.model", "gpt-test");
        exchange.getAttributes().put("ai.provider", "openai");
        exchange.getAttributes().put("ai.service", "channel-a");
        StepVerifier.create(collector.filter(exchange, collected -> statistics.filter(collected, terminal -> {
            terminal.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return terminal.getResponse().writeWith(Mono.just(new DefaultDataBufferFactory()
                    .wrap(body.getBytes(StandardCharsets.UTF_8))));
        }))).verifyComplete();
        verify(reporter, timeout(3000)).report(anyMap());
        return exchange;
    }

    @Test void realFilterOrderRecordsUsageInsteadOfRacingAsyncSettlement() {
        run("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}");
        assertEquals(10, meters.get("ai.tokens.input").counter().count());
        assertEquals(20, meters.get("ai.tokens.output").counter().count());
        verify(quota).settleTokenUsage("tenant", 100L, 30L, config, STAMP);
        verify(reporter).report(argThat(row -> "CONFIRMED".equals(row.get("usageStatus"))
                && "APPLIED".equals(row.get("settlementStatus")) && Long.valueOf(30).equals(row.get("chargedTokens"))
                && "202610".equals(row.get("quotaMonth")) && "20261028".equals(row.get("quotaDay"))));
    }

    @Test void missingUsageRetainsReservationAndNeverProducesConfirmedZeroMetrics() {
        var exchange = run("{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}");
        AiUsageResult result = exchange.getAttribute(AiUsageResult.RESULT_ATTR);
        assertEquals(AiUsageResult.Status.UNKNOWN, result.status());
        assertNull(result.totalTokens());
        assertNull(result.chargedTokens());
        verifyNoInteractions(quota);
        assertNull(meters.find("ai.tokens.input").counter());
        assertEquals(1, meters.get("ai.usage.requests").tag("status", "UNKNOWN").counter().count());
        verify(reporter).report(argThat(row -> "PENDING".equals(row.get("settlementStatus"))
                && row.get("totalTokens") == null && Long.valueOf(100).equals(row.get("reservedTokens"))));
    }

    @Test void explicitZeroIsConfirmedAndCanRefundReservation() {
        var exchange = run("{\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}");
        AiUsageResult result = exchange.getAttribute(AiUsageResult.RESULT_ATTR);
        assertEquals(AiUsageResult.Status.CONFIRMED, result.status());
        assertEquals(0L, result.totalTokens());
        verify(quota).settleTokenUsage("tenant", 100L, 0L, config, STAMP);
    }

    @Test void uncertainRedisFailureDoesNotTriggerASecondQuotaAdjustment() {
        doThrow(new IllegalStateException("Redis connection lost after write"))
                .when(quota).settleTokenUsage("tenant", 100L, 30L, config, STAMP);
        run("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}");
        verify(quota).settleTokenUsage("tenant", 100L, 30L, config, STAMP);
        verifyNoMoreInteractions(quota);
        verify(reporter).report(argThat(row -> "FAILED".equals(row.get("settlementStatus"))
                && Long.valueOf(30).equals(row.get("totalTokens"))));
    }

    @Test void firstPublishedDecisionCannotBeOverwrittenByALaterCallback() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        var completion = new java.util.concurrent.CompletableFuture<AiUsageResult>();
        exchange.getAttributes().put(AiUsageResult.COMPLETION_ATTR, completion);
        var confirmed = new AiUsageResult(AiUsageResult.Status.CONFIRMED, 1L, 2L, 3L, 3L, 10, false);
        confirmed.publish(exchange);
        new AiUsageResult(AiUsageResult.Status.UNKNOWN, null, null, null, null, 10, false).publish(exchange);
        assertSame(confirmed, exchange.getAttribute(AiUsageResult.RESULT_ATTR));
        assertSame(confirmed, completion.join());
    }
    @Test void managedCollectorUsesLedgerRatherThanLegacyQuotaAndReporter() {
        var ledger = mock(com.leoli.gateway.limiter.TokenQuotaLedger.class);
        ReflectionTestUtils.setField(collector,"tokenQuotaLedger",ledger);
        when(ledger.complete(anyString(),eq(30L),anyMap())).thenReturn("PENDING");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
        String id = java.util.UUID.randomUUID().toString();
        exchange.getAttributes().put("token_quota_tenant_id","tenant");
        exchange.getAttributes().put("token_quota_pre_deducted",100L);
        exchange.getAttributes().put(TokenQuotaPreCheckFilter.ATTR_PERIOD_STAMP,STAMP);
        exchange.getAttributes().put(com.leoli.gateway.limiter.TokenQuotaLedger.REQUEST_ATTR,id);
        exchange.getAttributes().put(com.leoli.gateway.limiter.TokenQuotaLedger.CONFIG_ATTR,config);
        // A policy removal during the request must not turn a served request into an early refund.
        when(strategies.getStrategyForRoute(anyString(),eq("TOKEN_RATE_LIMITER"))).thenReturn(null);
        collector.filter(exchange,terminal -> {
            terminal.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return terminal.getResponse().writeWith(Mono.just(new DefaultDataBufferFactory().wrap(
                    "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}".getBytes(StandardCharsets.UTF_8))));
        }).block();
        verify(ledger,timeout(3000)).complete(eq(id),eq(30L),argThat(row -> "CONFIRMED".equals(row.get("usageStatus"))));
        verifyNoInteractions(quota,reporter);
    }
}
