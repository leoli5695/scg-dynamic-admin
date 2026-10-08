package com.leoli.gateway.trace.decorator;

import com.leoli.gateway.trace.TraceContextHolder;
import org.springframework.core.task.TaskDecorator;

import java.util.function.Supplier;

/**
 * Trace task decorator
 * <p>
 * Solves the issue of child threads losing TraceId when using @Async or CompletableFuture.runAsync.
 * <p>
 * Usage:
 * 1. Set TaskDecorator when configuring thread pool:
 *
 * @author leoli
 * @Bean public Executor taskExecutor() {
 * ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 * executor.setTaskDecorator(new TraceTaskDecorator());
 * return executor;
 * }
 * <p>
 * 2. Manual usage (when not configuring thread pool):
 * CompletableFuture.runAsync(TraceTaskDecorator.wrap(() -> {...}));
 * <p>
 * Principle:
 * - Captures the FULL TraceContext when creating the task in the parent thread
 *   (traceId, shared DistributedTrace reference, sampled flag, current span id,
 *   span stack copy, baggage copy) via TraceContextHolder.captureContext()
 * - Restores it in the child thread, so spans recorded there attach to the
 *   correct parent and land in the shared trace (reported with the main request)
 * - Clears the child thread's ThreadLocals after task completion
 */
public class TraceTaskDecorator implements TaskDecorator {

    /**
     * Decorate Runnable task
     * <p>
     * Spring ThreadPoolTaskExecutor automatically calls this method
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        return wrap(runnable);
    }

    /**
     * 静态方法：包装 Runnable（供 CompletableFuture 使用）
     * <p>
     * 使用示例：
     * CompletableFuture.runAsync(TraceTaskDecorator.wrap(() -> doSomething()));
     */
    public static Runnable wrap(Runnable runnable) {
        TraceContextHolder.TraceContextSnapshot snapshot = TraceContextHolder.captureContext();
        return () -> {
            try {
                TraceContextHolder.restoreContext(snapshot);
                runnable.run();
            } finally {
                TraceContextHolder.clear();
            }
        };
    }

    /**
     * Static method: wrap Callable (for async tasks with return values)
     * <p>
     * Usage example:
     * Future<String> future = executor.submit(TraceTaskDecorator.wrap(() -> getResult()));
     */
    public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> callable) {
        TraceContextHolder.TraceContextSnapshot snapshot = TraceContextHolder.captureContext();
        return () -> {
            try {
                TraceContextHolder.restoreContext(snapshot);
                return callable.call();
            } finally {
                TraceContextHolder.clear();
            }
        };
    }

    /**
     * 静态方法：包装 Supplier（供 CompletableFuture.supplyAsync 使用）
     * <p>
     * 使用示例：
     * CompletableFuture<String> future = CompletableFuture.supplyAsync(
     * TraceTaskDecorator.wrapSupplier(() -> getResult())
     * );
     */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> supplier) {
        TraceContextHolder.TraceContextSnapshot snapshot = TraceContextHolder.captureContext();
        return () -> {
            try {
                TraceContextHolder.restoreContext(snapshot);
                return supplier.get();
            } finally {
                TraceContextHolder.clear();
            }
        };
    }
}
