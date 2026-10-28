package com.leoli.gateway.util;

import java.util.concurrent.atomic.AtomicLong;

/** Non-blocking admission for retained body bytes, including buffers and decoded copies. */
public final class ByteBudget {
    private final AtomicLong used = new AtomicLong();
    public Lease tryAcquire(long bytes, long limit) {
        if (bytes < 0 || limit < 0) return null;
        long current;
        do {
            current = used.get();
            if (bytes > limit - current) return null;
        } while (!used.compareAndSet(current, current + bytes));
        return new Lease(bytes);
    }
    public long used() { return used.get(); }
    public final class Lease implements AutoCloseable {
        private long bytes;
        private boolean released;
        private int references = 1;
        private Lease(long bytes) { this.bytes = bytes; }
        public synchronized boolean tryGrow(long additional, long limit) {
            if (released || additional < 0) return false;
            long current;
            do {
                current = used.get();
                if (additional > limit - current) return false;
            } while (!used.compareAndSet(current, current + additional));
            bytes += additional;
            return true;
        }
        public synchronized void retain() {
            if (released) throw new IllegalStateException("Body budget already released");
            references++;
        }
        @Override public synchronized void close() {
            if (!released && --references == 0) {
                released = true;
                used.addAndGet(-bytes);
            }
        }
    }

    /** Charge before retaining a chunk. Rejected buffers are released immediately. */
    public static reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer> charge(
            org.reactivestreams.Publisher<? extends org.springframework.core.io.buffer.DataBuffer> body,
            Lease lease, int copies, long limit) {
        return reactor.core.publisher.Flux.from(body).handle((buffer, sink) -> {
            if (lease.tryGrow((long) copies * buffer.readableByteCount(), limit)) sink.next(buffer);
            else {
                org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                sink.error(new java.util.concurrent.RejectedExecutionException("Concurrent body budget exhausted"));
            }
        });
    }
}
