package com.leoli.gateway.trace.decorator;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.DistributedTrace;
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
 * - Captures TraceContext when creating task in main thread
 * - Restores TraceContext when executing task in child thread
 * - Clears TraceContext after task execution completes
 */
public class TraceTaskDecorator implements TaskDecorator {

    /**
     * Decorate Runnable task
     * <p>
     * Spring ThreadPoolTaskExecutor automatically calls this method
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        // 在主线程捕获 TraceContext
        TraceContextSnapshot snapshot = captureSnapshot();

        // Return wrapped Runnable
        return () -> {
            try {
                // Restore TraceContext in child thread
                restoreSnapshot(snapshot);
                // Execute original task
                runnable.run();
            } finally {
                // Clear child thread's TraceContext
                TraceContextHolder.clear();
            }
        };
    }

    /**
     * 静态方法：包装 Runnable（供 CompletableFuture 使用）
     * <p>
     * 使用示例：
     * CompletableFuture.runAsync(TraceTaskDecorator.wrap(() -> doSomething()));
     */
    public static Runnable wrap(Runnable runnable) {
        TraceContextSnapshot snapshot = captureSnapshot();
        return () -> {
            try {
                restoreSnapshot(snapshot);
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
        TraceContextSnapshot snapshot = captureSnapshot();
        return () -> {
            try {
                restoreSnapshot(snapshot);
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
        TraceContextSnapshot snapshot = captureSnapshot();
        return () -> {
            try {
                restoreSnapshot(snapshot);
                return supplier.get();
            } finally {
                TraceContextHolder.clear();
            }
        };
    }

    // ==================== Internal Methods ====================

    /**
     * 捕获当前线程的 TraceContext 快照
     */
    private static TraceContextSnapshot captureSnapshot() {
        String traceId = TraceContextHolder.getTraceId();
        boolean sampled = TraceContextHolder.isSampled();
        DistributedTrace trace = TraceContextHolder.getTrace();

        // Deep copy Trace object (prevent multiple threads modifying the same object)
        DistributedTrace traceCopy = null;
        if (trace != null) {
            traceCopy = copyTrace(trace);
        }

        return new TraceContextSnapshot(traceId, sampled, traceCopy);
    }

    /**
     * 在子线程恢复 TraceContext
     */
    private static void restoreSnapshot(TraceContextSnapshot snapshot) {
        if (snapshot.traceId != null) {
            TraceContextHolder.setTraceId(snapshot.traceId);
        }
        TraceContextHolder.setSampled(snapshot.sampled);
        if (snapshot.trace != null) {
            TraceContextHolder.restoreTrace(snapshot.trace);
        }
    }

    /**
     * Deep copy DistributedTrace (simplified version)
     * <p>
     * Note: Only copies basic fields, not spans list
     * Spans added in child thread won't affect main thread's Trace
     */
    private static DistributedTrace copyTrace(DistributedTrace original) {
        DistributedTrace copy = new DistributedTrace();
        copy.setTraceId(original.getTraceId());
        copy.setServiceName(original.getServiceName());
        copy.setPath(original.getPath());
        copy.setMethod(original.getMethod());
        copy.setStartTime(original.getStartTime());
        copy.setClientIp(original.getClientIp());
        // 不拷贝 spans，子线程独立记录
        return copy;
    }

    /**
     * TraceContext snapshot
     * <p>
     * For cross-thread propagation
     */
    private static class TraceContextSnapshot {
        final String traceId;
        final boolean sampled;
        final DistributedTrace trace;

        TraceContextSnapshot(String traceId, boolean sampled, DistributedTrace trace) {
            this.traceId = traceId;
            this.sampled = sampled;
            this.trace = trace;
        }
    }
}