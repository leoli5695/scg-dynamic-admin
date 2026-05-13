package com.leoli.gateway.trace.config;

import com.leoli.gateway.trace.decorator.TraceTaskDecorator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Trace thread pool auto-configuration
 * <p>
 * Provides a thread pool configured with TraceTaskDecorator,
 * for automatic TraceContext propagation in @Async async tasks.
 * <p>
 * Usage:
 * 1. Enable configuration: gateway.trace.async-thread-pool-enabled=true
 * 2. Use @Async("traceTaskExecutor") on async methods
 * <p>
 * Example:
 *
 * @author leoli
 * @Async("traceTaskExecutor") public void asyncMethod() {
 * // TraceContextHolder.getTraceId() is available in child thread
 * }
 * <p>
 * Note: If not using this configuration, you need to manually configure the thread pool:
 * @Bean public Executor myTaskExecutor() {
 * ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 * executor.setTaskDecorator(new TraceTaskDecorator());
 * return executor;
 * }
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.trace", name = "async-thread-pool-enabled", havingValue = "true")
public class TraceThreadPoolConfig {

    /**
     * Thread pool configured with TraceTaskDecorator
     * <p>
     * For @Async annotated async methods
     */
    @Bean("traceTaskExecutor")
    public Executor traceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Core configuration
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("trace-async-");

        // 【关键】设置 TraceTaskDecorator
        executor.setTaskDecorator(new TraceTaskDecorator());

        // Wait for tasks to complete before shutting down the thread pool
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        return executor;
    }
}