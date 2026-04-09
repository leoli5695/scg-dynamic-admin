package com.leoli.gateway.admin.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Business metrics for Gateway Admin.
 * Exposes custom metrics to Prometheus for monitoring.
 *
 * @author leoli
 */
@Component
public class BusinessMetrics {

    private final MeterRegistry meterRegistry;

    // Counters for operations
    private final Counter routeCreateCounter;
    private final Counter routeUpdateCounter;
    private final Counter routeDeleteCounter;
    private final Counter routeEnableCounter;
    private final Counter routeDisableCounter;

    private final Counter serviceCreateCounter;
    private final Counter serviceUpdateCounter;
    private final Counter serviceDeleteCounter;

    private final Counter strategyCreateCounter;
    private final Counter strategyUpdateCounter;
    private final Counter strategyDeleteCounter;

    private final Counter configPublishCounter;
    private final Counter configPublishErrorCounter;

    // Gauges for current state (updated via suppliers)
    private final AtomicLong totalRoutes = new AtomicLong(0);
    private final AtomicLong enabledRoutes = new AtomicLong(0);
    private final AtomicLong totalServices = new AtomicLong(0);
    private final AtomicLong enabledServices = new AtomicLong(0);
    private final AtomicLong totalStrategies = new AtomicLong(0);
    private final AtomicLong enabledStrategies = new AtomicLong(0);
    private final AtomicLong healthyInstances = new AtomicLong(0);
    private final AtomicLong totalInstances = new AtomicLong(0);

    // Timers for operations
    private final Timer configPublishTimer;
    private final Timer nacosQueryTimer;

    public BusinessMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Route operation counters
        this.routeCreateCounter = Counter.builder("gateway_route_operations_total")
                .description("Total number of route operations")
                .tag("operation", "create")
                .register(meterRegistry);

        this.routeUpdateCounter = Counter.builder("gateway_route_operations_total")
                .description("Total number of route operations")
                .tag("operation", "update")
                .register(meterRegistry);

        this.routeDeleteCounter = Counter.builder("gateway_route_operations_total")
                .description("Total number of route operations")
                .tag("operation", "delete")
                .register(meterRegistry);

        this.routeEnableCounter = Counter.builder("gateway_route_operations_total")
                .description("Total number of route operations")
                .tag("operation", "enable")
                .register(meterRegistry);

        this.routeDisableCounter = Counter.builder("gateway_route_operations_total")
                .description("Total number of route operations")
                .tag("operation", "disable")
                .register(meterRegistry);

        // Service operation counters
        this.serviceCreateCounter = Counter.builder("gateway_service_operations_total")
                .description("Total number of service operations")
                .tag("operation", "create")
                .register(meterRegistry);

        this.serviceUpdateCounter = Counter.builder("gateway_service_operations_total")
                .description("Total number of service operations")
                .tag("operation", "update")
                .register(meterRegistry);

        this.serviceDeleteCounter = Counter.builder("gateway_service_operations_total")
                .description("Total number of service operations")
                .tag("operation", "delete")
                .register(meterRegistry);

        // Strategy operation counters
        this.strategyCreateCounter = Counter.builder("gateway_strategy_operations_total")
                .description("Total number of strategy operations")
                .tag("operation", "create")
                .register(meterRegistry);

        this.strategyUpdateCounter = Counter.builder("gateway_strategy_operations_total")
                .description("Total number of strategy operations")
                .tag("operation", "update")
                .register(meterRegistry);

        this.strategyDeleteCounter = Counter.builder("gateway_strategy_operations_total")
                .description("Total number of strategy operations")
                .tag("operation", "delete")
                .register(meterRegistry);

        // Config publish counters
        this.configPublishCounter = Counter.builder("gateway_config_publish_total")
                .description("Total number of config publish operations")
                .register(meterRegistry);

        this.configPublishErrorCounter = Counter.builder("gateway_config_publish_errors_total")
                .description("Total number of config publish errors")
                .register(meterRegistry);

        // Gauges - register with suppliers for automatic updates
        Gauge.builder("gateway_routes_total", totalRoutes, AtomicLong::get)
                .description("Total number of routes")
                .register(meterRegistry);

        Gauge.builder("gateway_routes_enabled", enabledRoutes, AtomicLong::get)
                .description("Number of enabled routes")
                .register(meterRegistry);

        Gauge.builder("gateway_services_total", totalServices, AtomicLong::get)
                .description("Total number of services")
                .register(meterRegistry);

        Gauge.builder("gateway_services_enabled", enabledServices, AtomicLong::get)
                .description("Number of enabled services")
                .register(meterRegistry);

        Gauge.builder("gateway_strategies_total", totalStrategies, AtomicLong::get)
                .description("Total number of strategies")
                .register(meterRegistry);

        Gauge.builder("gateway_strategies_enabled", enabledStrategies, AtomicLong::get)
                .description("Number of enabled strategies")
                .register(meterRegistry);

        Gauge.builder("gateway_instances_total", totalInstances, AtomicLong::get)
                .description("Total number of gateway instances")
                .register(meterRegistry);

        Gauge.builder("gateway_instances_healthy", healthyInstances, AtomicLong::get)
                .description("Number of healthy gateway instances")
                .register(meterRegistry);

        // Timers
        this.configPublishTimer = Timer.builder("gateway_config_publish_duration")
                .description("Time taken to publish config to Nacos")
                .register(meterRegistry);

        this.nacosQueryTimer = Timer.builder("gateway_nacos_query_duration")
                .description("Time taken to query Nacos")
                .register(meterRegistry);
    }

    // Route operation methods
    public void recordRouteCreate() {
        routeCreateCounter.increment();
    }

    public void recordRouteUpdate() {
        routeUpdateCounter.increment();
    }

    public void recordRouteDelete() {
        routeDeleteCounter.increment();
    }

    public void recordRouteEnable() {
        routeEnableCounter.increment();
    }

    public void recordRouteDisable() {
        routeDisableCounter.increment();
    }

    // Service operation methods
    public void recordServiceCreate() {
        serviceCreateCounter.increment();
    }

    public void recordServiceUpdate() {
        serviceUpdateCounter.increment();
    }

    public void recordServiceDelete() {
        serviceDeleteCounter.increment();
    }

    // Strategy operation methods
    public void recordStrategyCreate() {
        strategyCreateCounter.increment();
    }

    public void recordStrategyUpdate() {
        strategyUpdateCounter.increment();
    }

    public void recordStrategyDelete() {
        strategyDeleteCounter.increment();
    }

    // Config publish methods
    public void recordConfigPublish() {
        configPublishCounter.increment();
    }

    public void recordConfigPublishError() {
        configPublishErrorCounter.increment();
    }

    public Timer.Sample startConfigPublishTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordConfigPublishDuration(Timer.Sample sample) {
        sample.stop(configPublishTimer);
    }

    public void recordConfigPublishDuration(long durationMillis) {
        configPublishTimer.record(durationMillis, TimeUnit.MILLISECONDS);
    }

    // Nacos query methods
    public Timer.Sample startNacosQueryTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordNacosQueryDuration(Timer.Sample sample) {
        sample.stop(nacosQueryTimer);
    }

    public void recordNacosQueryDuration(long durationMillis) {
        nacosQueryTimer.record(durationMillis, TimeUnit.MILLISECONDS);
    }

    // Gauge update methods
    public void updateRouteCounts(long total, long enabled) {
        totalRoutes.set(total);
        enabledRoutes.set(enabled);
    }

    public void updateServiceCounts(long total, long enabled) {
        totalServices.set(total);
        enabledServices.set(enabled);
    }

    public void updateStrategyCounts(long total, long enabled) {
        totalStrategies.set(total);
        enabledStrategies.set(enabled);
    }

    public void updateInstanceCounts(long total, long healthy) {
        totalInstances.set(total);
        healthyInstances.set(healthy);
    }
}