package com.leoli.gateway.admin.metrics;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Custom tracing helper for Gateway Admin operations.
 * Provides methods to create and manage tracing spans for key business operations.
 *
 * @author leoli
 */
@Slf4j
@Component
public class TracingHelper {

    private final Tracer tracer;

    public TracingHelper(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Start a new span with the given name.
     */
    public Span startSpan(String name) {
        return tracer.nextSpan().name(name).start();
    }

    /**
     * Start a span for route operations.
     */
    public Span startRouteSpan(String operation, String routeId) {
        return tracer.nextSpan()
                .name("route." + operation)
                .tag("route.id", routeId != null ? routeId : "unknown")
                .start();
    }

    /**
     * Start a span for service operations.
     */
    public Span startServiceSpan(String operation, String serviceId) {
        return tracer.nextSpan()
                .name("service." + operation)
                .tag("service.id", serviceId != null ? serviceId : "unknown")
                .start();
    }

    /**
     * Start a span for strategy operations.
     */
    public Span startStrategySpan(String operation, String strategyId) {
        return tracer.nextSpan()
                .name("strategy." + operation)
                .tag("strategy.id", strategyId != null ? strategyId : "unknown")
                .start();
    }

    /**
     * Start a span for config center operations.
     */
    public Span startConfigSpan(String operation, String dataId) {
        return tracer.nextSpan()
                .name("config." + operation)
                .tag("config.dataId", dataId != null ? dataId : "unknown")
                .start();
    }

    /**
     * Start a span for instance operations.
     */
    public Span startInstanceSpan(String operation, String instanceId) {
        return tracer.nextSpan()
                .name("instance." + operation)
                .tag("instance.id", instanceId != null ? instanceId : "unknown")
                .start();
    }

    /**
     * Add a tag to the current span.
     */
    public void tag(String key, String value) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag(key, value);
        }
    }

    /**
     * Add an event to the current span.
     */
    public void event(String name) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.event(name);
        }
    }

    /**
     * Record an error on the current span.
     */
    public void error(Throwable throwable) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.error(throwable);
        }
    }

    /**
     * End the given span.
     */
    public void endSpan(Span span) {
        if (span != null) {
            span.end();
        }
    }

    /**
     * Execute a Runnable within a span.
     */
    public void withSpan(String name, Runnable runnable) {
        Span span = startSpan(name);
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            runnable.run();
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Execute a java.util.function.Supplier within a span and return the result.
     */
    public <T> T withSpan(String name, java.util.function.Supplier<T> supplier) {
        Span span = startSpan(name);
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return supplier.get();
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}