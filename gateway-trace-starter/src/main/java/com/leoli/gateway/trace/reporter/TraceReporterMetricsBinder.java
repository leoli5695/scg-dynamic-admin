package com.leoli.gateway.trace.reporter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 将 AsyncTraceReporter 的运行统计暴露为 Micrometer 指标，
 * 使队列积压、丢弃、降级落盘对 Prometheus/监控面板可见
 * （此前只有 getter，没有接入任何监控）。
 * <p>
 * 仅当消费方 classpath 存在 micrometer-core（如引入了 actuator）时装配。
 *
 * @author leoli
 */
public class TraceReporterMetricsBinder {

    public TraceReporterMetricsBinder(AsyncTraceReporter reporter, MeterRegistry registry) {
        if (registry == null) {
            // No MeterRegistry in context — metrics simply not exposed
            return;
        }
        Gauge.builder("gateway.trace.reporter.queue.size", reporter, AsyncTraceReporter::getQueueSize)
                .description("Pending traces in the async report queue")
                .register(registry);
        Gauge.builder("gateway.trace.reporter.reported", reporter, r -> (double) r.getReportedCount())
                .description("Total trace records acknowledged as enqueued by admin; not durable storage")
                .register(registry);
        Gauge.builder("gateway.trace.reporter.retained.bytes", reporter, AsyncTraceReporter::getRetainedBytes).register(registry);
        Gauge.builder("gateway.trace.reporter.unconfirmed", reporter, AsyncTraceReporter::getUnconfirmedCount).register(registry);
        Gauge.builder("gateway.trace.reporter.sent", reporter, AsyncTraceReporter::getSentCount).register(registry);
        Gauge.builder("gateway.trace.reporter.rejected", reporter, AsyncTraceReporter::getRejectedCount).register(registry);
        Gauge.builder("gateway.trace.reporter.failed.attempts", reporter, AsyncTraceReporter::getFailedAttemptCount).register(registry);
        Gauge.builder("gateway.trace.reporter.fallback.dropped", reporter, AsyncTraceReporter::getFallbackDroppedCount).register(registry);
        Gauge.builder("gateway.trace.reporter.dropped", reporter, r -> (double) r.getDroppedCount())
                .description("Total traces dropped due to queue overflow")
                .register(registry);
        Gauge.builder("gateway.trace.reporter.fallback", reporter, r -> (double) r.getFallbackCount())
                .description("Total traces written to local fallback log")
                .register(registry);
    }
}
