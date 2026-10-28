package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.health.HybridHealthChecker;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstanceRetryExecutorTest {
    private final ServiceInstance one = new DefaultServiceInstance("one", "svc", "localhost", 12345, false);
    private final ServiceInstance two = new DefaultServiceInstance("two", "svc", "localhost", 12346, false);
    private final InstanceRetryExecutor executor = new InstanceRetryExecutor(mock(InstanceFilter.class), mock(HybridHealthChecker.class));

    @Test
    void staticResolutionHonorsInstanceSchemeAndTheAlreadyRewrittenUrl() {
        for (boolean secure : List.of(false, true)) {
            ServiceInstance instance = new DefaultServiceInstance("one", "svc", "localhost", 12345, secure);
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/original").build());
            exchange.getAttributes().put(org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
                    java.net.URI.create("static://svc/rewritten/a%2Fb?q=a%26b"));
            executor.execute(exchange, forwarded -> {
                assertEquals((secure ? "https" : "http") + "://localhost:12345/rewritten/a%2Fb?q=a%26b",
                        forwarded.getAttribute(org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR).toString());
                return Mono.empty();
            }, "svc", instance, List.of(instance), new HashSet<>()).block();
        }
    }

    @Test
    void singleBackendAndLargeBodiesAreForwardedChunkByChunkWithoutReplayCache() {
        for (var instances : List.of(List.of(one), List.of(one, two))) {
            byte[] chunk = new byte[8192];
            AtomicInteger emitted = new AtomicInteger(), consumed = new AtomicInteger();
            Flux<org.springframework.core.io.buffer.DataBuffer> body = Flux.range(0, 128).map(i -> {
                emitted.incrementAndGet(); return DefaultDataBufferFactory.sharedInstance.wrap(chunk);
            });
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/upload")
                    .header("Content-Length", "1048576").body(body));
            executor.execute(exchange, forwarded -> forwarded.getRequest().getBody().doOnNext(buffer -> {
                assertEquals(consumed.incrementAndGet(), emitted.get(), "body must reach downstream before the next chunk is produced");
                org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
            }).then(), "svc", one, instances, new HashSet<>()).block();
            assertEquals(128, consumed.get());
        }
    }

    @Test
    void unknownLengthUploadsStreamWithoutAnArtificial413Limit() {
        var body = Flux.range(0, 200).map(i -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[8192]));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/chunked").body(body));
        AtomicInteger bytes = new AtomicInteger();
        executor.execute(exchange, forwarded -> forwarded.getRequest().getBody().doOnNext(buffer -> {
            bytes.addAndGet(buffer.readableByteCount()); org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
        }).then(), "svc", one, List.of(one, two), new HashSet<>()).block();
        assertEquals(200 * 8192, bytes.get()); assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void smallBodiesCanStillBeReplayedOnAnAlternativeBackend() {
        var choices=mock(InstanceFilter.class);
        when(choices.findAlternative(anyString(),anyList(),anySet())).thenReturn(two);
        var retry=new InstanceRetryExecutor(choices,mock(HybridHealthChecker.class));
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/small")
                .header("Content-Length","5").body("hello"));
        var bodies=new ArrayList<String>();
        retry.execute(exchange,forwarded -> org.springframework.core.io.buffer.DataBufferUtils.join(forwarded.getRequest().getBody())
                .flatMap(buffer -> {
                    byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    bodies.add(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                    return bodies.size()==1?Mono.error(new java.io.IOException("first backend offline")):Mono.empty();
                }),"svc",one,List.of(one,two),new HashSet<>()).block();
        assertEquals(List.of("hello","hello"),bodies);
    }

    @Test
    void cancellationReleasesTheSharedRetryMemoryBudget() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/small")
                .header("Content-Length", "16").body("1234567890123456"));
        var pending = executor.execute(exchange, forwarded -> Mono.never(), "svc", one, List.of(one, two), new HashSet<>()).subscribe();
        var budget = (java.util.concurrent.atomic.AtomicLong) org.springframework.test.util.ReflectionTestUtils.getField(executor, "inflightRetryBytes");
        assertTrue(budget.get() > 0); pending.dispose(); assertEquals(0, budget.get());
    }

    @Test
    void oversizedRetryBodyReturnsAnExplanationWithoutForwardingToTheBackend() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/upload")
                .header("Content-Length", "1").body("x".repeat(65537)));
        executor.execute(exchange, forwarded -> {
            fail("An oversized retry buffer must not reach the backend"); return Mono.empty();
        }, "svc", one, List.of(one, two), new HashSet<>()).block();
        assertEquals(413, exchange.getResponse().getStatusCode().value());
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertEquals(413, body.get("httpStatus").asInt());
        assertTrue(body.get("message").asText().contains("65536-byte"));
        assertTrue(body.get("message").asText().contains("Content-Length"));
    }
}
