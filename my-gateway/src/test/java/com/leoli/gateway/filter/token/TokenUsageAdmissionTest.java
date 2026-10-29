package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiProxyConfig;
import com.leoli.gateway.parser.TokenUsageParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TokenUsageAdmissionTest {
    @TempDir Path directory;
    TokenUsageReporter reporter;
    TokenUsageCollectorFilter filter;
    GatewayFilterChain chain;
    @BeforeEach void setup() {
        reporter = spy(new TokenUsageReporter(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(reporter, "internalToken", "secret");
        ReflectionTestUtils.setField(reporter, "outboxDirectory", directory.toString());
        ReflectionTestUtils.setField(reporter, "maxRecords", 1);
        var strategies = mock(StrategyManager.class);
        var proxy = new AiProxyConfig(); proxy.setEnabled(true);
        when(strategies.getConfig(anyString(), eq("AI_PROXY"), eq(AiProxyConfig.class))).thenReturn(proxy);
        filter = new TokenUsageCollectorFilter();
        ReflectionTestUtils.setField(filter, "strategyManager", strategies);
        ReflectionTestUtils.setField(filter, "tokenUsageReporter", reporter);
        ReflectionTestUtils.setField(filter, "tokenUsageParser", new TokenUsageParser());
        ReflectionTestUtils.setField(filter, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(filter, "instanceId", "i1");
        ReflectionTestUtils.setField(filter, "maxBodySize", 65536);
        chain = mock(GatewayFilterChain.class);
    }
    @AfterEach void close() { reporter.close(); }
    MockServerWebExchange exchange() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions").build());
        exchange.getAttributes().put("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId", "r1");
        return exchange;
    }
    @Test void fullQueueReturns503WithoutCallingModel() {
        reporter.begin(Map.of("tenantId", "occupier"));
        var exchange = exchange();
        filter.filter(exchange, chain).block(Duration.ofSeconds(5));
        assertEquals(503, exchange.getResponse().getStatusCode().value());
        verifyNoInteractions(chain);
    }
    @Test void unwritableStoreReturns503WithoutCallingModel() throws Exception {
        Path file = directory.resolve("not-a-directory"); Files.writeString(file, "occupied");
        ReflectionTestUtils.setField(reporter, "outboxDirectory", file.toString());
        var exchange = exchange();
        filter.filter(exchange, chain).block(Duration.ofSeconds(5));
        assertEquals(503, exchange.getResponse().getStatusCode().value());
        verifyNoInteractions(chain);
    }
    @Test void admittedResponseIsPersistedOnceWithExactUsage() throws Exception {
        var done = new CountDownLatch(1);
        doAnswer(call -> { try { return call.callRealMethod(); } finally { done.countDown(); } })
                .when(reporter).complete(any(), anyMap());
        when(chain.filter(any())).thenAnswer(call -> {
            var exchange = (org.springframework.web.server.ServerWebExchange) call.getArgument(0);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            var bytes = "{\"usage\":{\"prompt_tokens\":62,\"completion_tokens\":36,\"total_tokens\":98}}".getBytes();
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        });
        filter.filter(exchange(), chain).block(Duration.ofSeconds(5));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        reporter.close();
        try (var store = new TokenUsageOutbox(directory, 67108864, 4096)) {
            assertEquals(1, store.size());
            var row = new ObjectMapper().readTree(store.batch(2).getFirst().bytes());
            assertEquals(98, row.get("totalTokens").asInt());
            assertEquals("CONFIRMED", row.get("usageStatus").asText());
            assertEquals("NOT_REQUIRED", row.get("settlementStatus").asText());
        }
    }
}
