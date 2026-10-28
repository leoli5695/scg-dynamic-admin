package com.leoli.gateway.util;

import reactor.core.publisher.Mono;
import java.util.concurrent.*;

/** Dedicated CPU workers. A cancelled running task retains its memory lease until it exits. */
public final class BodyWorkPool implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    public BodyWorkPool(String name, int threads, int queueSize) {
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), task -> {
                    Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public <T> Mono<T> submit(ByteBudget.Lease lease, Callable<T> task) {
        return Mono.create(sink -> {
            lease.retain();
            try {
                executor.execute(() -> {
                    try { sink.success(task.call()); }
                    catch (Throwable error) { sink.error(error); }
                    finally { lease.close(); }
                });
            } catch (RejectedExecutionException error) {
                lease.close(); sink.error(error);
            }
        });
    }
    @Override public void close() { executor.shutdown(); }
}
