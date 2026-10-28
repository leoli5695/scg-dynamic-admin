package com.leoli.gateway.util;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Immutable byte snapshot attached to the request that owns it, never to a stale exchange attribute. */
public final class RequestBodyCache {
    private RequestBodyCache() { }

    public static Mono<byte[]> read(ServerWebExchange exchange, int limit) {
        return read(exchange, limit, null, 0, 0);
    }

    public static Mono<byte[]> read(ServerWebExchange exchange, int limit, ByteBudget.Lease lease, int copies, long budgetLimit) {
        if (exchange.getRequest() instanceof BodyRequest cached) {
            if (cached.bytes.length > limit) return Mono.error(new DataBufferLimitException("Cached request body exceeds " + limit + " bytes"));
            return Mono.defer(() -> lease == null || lease.tryGrow((long) copies * cached.bytes.length, budgetLimit)
                    ? Mono.just(cached.bytes) : Mono.error(new java.util.concurrent.RejectedExecutionException("Concurrent body budget exhausted")));
        }
        Flux<DataBuffer> body = lease == null ? exchange.getRequest().getBody()
                : ByteBudget.charge(exchange.getRequest().getBody(), lease, copies, budgetLimit);
        return DataBufferUtils.join(body, limit)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                }).defaultIfEmpty(new byte[0]);
    }

    public static ServerHttpRequest wrap(ServerWebExchange exchange, byte[] bytes) {
        if (exchange.getRequest() instanceof BodyRequest cached && cached.bytes == bytes) {
            return cached;
        }
        return new BodyRequest(exchange, bytes);
    }

    private static final class BodyRequest extends ServerHttpRequestDecorator {
        private final ServerWebExchange exchange;
        private final byte[] bytes;

        private BodyRequest(ServerWebExchange exchange, byte[] bytes) {
            super(exchange.getRequest());
            this.exchange = exchange;
            this.bytes = bytes;
        }

        @Override
        public Flux<DataBuffer> getBody() {
            return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(super.getHeaders());
            headers.remove(HttpHeaders.TRANSFER_ENCODING);
            headers.setContentLength(bytes.length);
            return headers;
        }
    }
}
