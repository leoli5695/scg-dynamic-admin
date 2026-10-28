package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.config.RetryProperties;
import com.leoli.gateway.filter.resilience.RetryGlobalFilter;
import com.leoli.gateway.filter.security.SecurityGlobalFilter;
import com.leoli.gateway.manager.StrategyManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BodyAdmissionRegressionTest {
    @Test void unknownLengthUploadStreamsWithoutRetryAndWithoutReadingAhead() {
        StrategyManager manager = mock(StrategyManager.class);
        when(manager.isStrategyEnabled(any(), any())).thenReturn(true);
        RetryGlobalFilter filter = new RetryGlobalFilter(manager, new RetryProperties());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/upload").body("upload"));
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> filter.filter(exchange, request -> {
            attempts.incrementAndGet();
            return org.springframework.core.io.buffer.DataBufferUtils.join(request.getRequest().getBody())
                    .flatMap(buffer -> {
                        assertEquals("upload", buffer.toString(java.nio.charset.StandardCharsets.UTF_8));
                        org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                        return Mono.error(new java.net.ConnectException("failed"));
                    });
        }).block(Duration.ofSeconds(2)));
        assertEquals(1, attempts.get());
    }

    @Test void wafSaturationRejectsWithoutConsumingBodyOrSkippingSecurity() {
        StrategyManager manager = mock(StrategyManager.class);
        when(manager.getSecurityConfig(any())).thenReturn(Map.of("enabled", true, "mode", "BLOCK", "checkBody", true));
        SecurityGlobalFilter filter = new SecurityGlobalFilter(manager, new ObjectMapper());
        ReflectionTestUtils.setField(filter, "maxInflightBodyBytes", 0L);
        AtomicInteger calls = new AtomicInteger();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/upload")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN).body("unsafe"));
        try {
            filter.filter(exchange, request -> { calls.incrementAndGet(); return Mono.empty(); }).block(Duration.ofSeconds(2));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
            assertEquals(0, calls.get());
        } finally { filter.closeScanPool(); }
    }

    @Test void retryBudgetIsReleasedOnCancellation() {
        StrategyManager manager = mock(StrategyManager.class);
        when(manager.isStrategyEnabled(any(), any())).thenReturn(true);
        RetryGlobalFilter filter = new RetryGlobalFilter(manager, new RetryProperties());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/upload").contentLength(1).body("a"));
        var budget = (com.leoli.gateway.util.ByteBudget) ReflectionTestUtils.getField(filter, "bodyBudget");
        var subscription = filter.filter(exchange, request -> Mono.never()).subscribe();
        assertTrue(budget.used() > 0); subscription.dispose(); assertEquals(0, budget.used());
    }

    @Test void wafChargesActualChunksAndReleasesCapacityOnCancellation() {
        StrategyManager manager = mock(StrategyManager.class);
        when(manager.getSecurityConfig(any())).thenReturn(Map.of("enabled", true, "mode", "BLOCK", "checkBody", true));
        SecurityGlobalFilter filter = new SecurityGlobalFilter(manager, new ObjectMapper());
        ReflectionTestUtils.setField(filter, "maxInflightBodyBytes", 8L);
        var budget = (com.leoli.gateway.util.ByteBudget) ReflectionTestUtils.getField(filter, "scanBudget");
        java.util.List<reactor.core.Disposable> active = new java.util.ArrayList<>();
        java.util.concurrent.CountDownLatch forwarded = new java.util.concurrent.CountDownLatch(2);
        try {
            for (int i = 0; i < 2; i++) active.add(filter.filter(smallBody(), request -> {
                forwarded.countDown(); return Mono.never();
            }).subscribe());
            assertTrue(forwarded.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(8, budget.used());
            var rejected = smallBody(); filter.filter(rejected, request -> Mono.error(new AssertionError("must not forward"))).block();
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getResponse().getStatusCode());
            active.get(0).dispose();
            var accepted = smallBody(); filter.filter(accepted, request -> Mono.empty()).block(Duration.ofSeconds(2));
            assertNull(accepted.getResponse().getStatusCode());
        } catch (InterruptedException error) { throw new AssertionError(error); }
        finally { active.forEach(reactor.core.Disposable::dispose); filter.closeScanPool(); }
        assertEquals(0, budget.used());
    }
    private MockServerWebExchange smallBody() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN).body("a"));
    }
}
