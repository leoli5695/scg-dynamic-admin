package com.leoli.gateway.util;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RequestBodyCacheTest {
    @Test
    void reusesTheSnapshotAndReplaysWithIndependentBufferPositions() {
        var original = MockServerWebExchange.from(MockServerHttpRequest.post("/body").body("original"));
        byte[] bytes = RequestBodyCache.read(original, 100).block();
        var cached = original.mutate().request(RequestBodyCache.wrap(original, bytes)).build();
        assertSame(bytes, RequestBodyCache.read(cached, 100).block());
        assertSame(bytes, RequestBodyCache.read(cached, 100).block());
        for (int i = 0; i < 2; i++) {
            DataBufferUtils.join(cached.getRequest().getBody()).doOnNext(buffer -> {
                byte[] read = new byte[buffer.readableByteCount()];
                buffer.read(read);
                DataBufferUtils.release(buffer);
                assertArrayEquals(bytes, read);
            }).block();
        }
        StepVerifier.create(RequestBodyCache.read(cached, 3))
                .expectError(DataBufferLimitException.class).verify();
    }

    @Test
    void transformedSnapshotReplacesOriginalAndUpdatesFraming() {
        var original = MockServerWebExchange.from(MockServerHttpRequest.post("/body")
                .header("Transfer-Encoding", "chunked").body("original"));
        byte[] transformed = "changed".getBytes(StandardCharsets.UTF_8);
        var cached = original.mutate().request(RequestBodyCache.wrap(original, transformed)).build();
        assertSame(transformed, RequestBodyCache.read(cached, 100).block());
        assertEquals(transformed.length, cached.getRequest().getHeaders().getContentLength());
        assertFalse(cached.getRequest().getHeaders().containsKey("Transfer-Encoding"));
        // An arbitrary body-changing decorator must not inherit a stale snapshot.
        var replaced = cached.mutate().request(MockServerHttpRequest.post("/body").body("replacement")).build();
        assertEquals("replacement", new String(RequestBodyCache.read(replaced, 100).block(), StandardCharsets.UTF_8));
    }
}
