package com.leoli.gateway.admin.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BusinessMetrics.
 */
class BusinessMetricsTest {

    private MeterRegistry meterRegistry;
    private BusinessMetrics businessMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        businessMetrics = new BusinessMetrics(meterRegistry);
    }

    // ==================== Route Operation Tests ====================

    @Test
    void recordRouteCreate_shouldIncrementCounter() {
        double initial = getRouteCounterValue("create");
        businessMetrics.recordRouteCreate();
        double after = getRouteCounterValue("create");
        assertEquals(1.0, after - initial, 0.001);
    }

    @Test
    void recordRouteUpdate_shouldIncrementCounter() {
        double initial = getRouteCounterValue("update");
        businessMetrics.recordRouteUpdate();
        double after = getRouteCounterValue("update");
        assertEquals(1.0, after - initial, 0.001);
    }

    @Test
    void recordRouteDelete_shouldIncrementCounter() {
        double initial = getRouteCounterValue("delete");
        businessMetrics.recordRouteDelete();
        double after = getRouteCounterValue("delete");
        assertEquals(1.0, after - initial, 0.001);
    }

    @Test
    void recordRouteEnable_shouldIncrementCounter() {
        double initial = getRouteCounterValue("enable");
        businessMetrics.recordRouteEnable();
        double after = getRouteCounterValue("enable");
        assertEquals(1.0, after - initial, 0.001);
    }

    @Test
    void recordRouteDisable_shouldIncrementCounter() {
        double initial = getRouteCounterValue("disable");
        businessMetrics.recordRouteDisable();
        double after = getRouteCounterValue("disable");
        assertEquals(1.0, after - initial, 0.001);
    }

    @Test
    void recordRouteCreate_multipleIncrements_shouldAccumulate() {
        businessMetrics.recordRouteCreate();
        businessMetrics.recordRouteCreate();
        businessMetrics.recordRouteCreate();
        double value = getRouteCounterValue("create");
        assertEquals(3.0, value, 0.001);
    }

    // ==================== Service Operation Tests ====================

    @Test
    void recordServiceCreate_shouldIncrementCounter() {
        businessMetrics.recordServiceCreate();
        Counter counter = meterRegistry.find("gateway_service_operations_total").tag("operation", "create").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.001);
    }

    @Test
    void recordServiceUpdate_shouldIncrementCounter() {
        businessMetrics.recordServiceUpdate();
        Counter counter = meterRegistry.find("gateway_service_operations_total").tag("operation", "update").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.001);
    }

    @Test
    void recordServiceDelete_shouldIncrementCounter() {
        businessMetrics.recordServiceDelete();
        Counter counter = meterRegistry.find("gateway_service_operations_total").tag("operation", "delete").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.001);
    }

    // ==================== Strategy Operation Tests ====================

    @Test
    void recordStrategyCreate_shouldIncrementCounter() {
        businessMetrics.recordStrategyCreate();
        Counter counter = meterRegistry.find("gateway_strategy_operations_total").tag("operation", "create").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.001);
    }

    @Test
    void recordStrategyUpdate_shouldIncrementCounter() {
        businessMetrics.recordStrategyUpdate();
        Counter counter = meterRegistry.find("gateway_strategy_operations_total").tag("operation", "update").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.001);
    }

    @Test
    void recordStrategyDelete_shouldIncrementCounter() {
        businessMetrics.recordStrategyDelete();
        Counter counter = meterRegistry.find("gateway_strategy_operations_total").tag("operation", "delete").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.001);
    }

    // ==================== Config Publish Tests ====================

    @Test
    void recordConfigPublish_shouldIncrementCounter() {
        businessMetrics.recordConfigPublish();
        Counter counter = meterRegistry.find("gateway_config_publish_total").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.001);
    }

    @Test
    void recordConfigPublishError_shouldIncrementCounter() {
        businessMetrics.recordConfigPublishError();
        Counter counter = meterRegistry.find("gateway_config_publish_errors_total").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.001);
    }

    @Test
    void recordConfigPublishDuration_shouldRecordTimer() {
        businessMetrics.recordConfigPublishDuration(100);
        Timer timer = meterRegistry.find("gateway_config_publish_duration").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 100);
    }

    // ==================== Gauge Tests ====================

    @Test
    void updateRouteCounts_shouldUpdateGauges() {
        businessMetrics.updateRouteCounts(10, 8);

        assertEquals(10.0, getGaugeValue("gateway_routes_total"), 0.001);
        assertEquals(8.0, getGaugeValue("gateway_routes_enabled"), 0.001);
    }

    @Test
    void updateServiceCounts_shouldUpdateGauges() {
        businessMetrics.updateServiceCounts(5, 4);

        assertEquals(5.0, getGaugeValue("gateway_services_total"), 0.001);
        assertEquals(4.0, getGaugeValue("gateway_services_enabled"), 0.001);
    }

    @Test
    void updateStrategyCounts_shouldUpdateGauges() {
        businessMetrics.updateStrategyCounts(20, 15);

        assertEquals(20.0, getGaugeValue("gateway_strategies_total"), 0.001);
        assertEquals(15.0, getGaugeValue("gateway_strategies_enabled"), 0.001);
    }

    @Test
    void updateInstanceCounts_shouldUpdateGauges() {
        businessMetrics.updateInstanceCounts(3, 2);

        assertEquals(3.0, getGaugeValue("gateway_instances_total"), 0.001);
        assertEquals(2.0, getGaugeValue("gateway_instances_healthy"), 0.001);
    }

    @Test
    void gaugeUpdates_shouldReplacePreviousValue() {
        businessMetrics.updateRouteCounts(10, 8);
        assertEquals(10.0, getGaugeValue("gateway_routes_total"), 0.001);

        businessMetrics.updateRouteCounts(15, 12);
        assertEquals(15.0, getGaugeValue("gateway_routes_total"), 0.001);
    }

    // ==================== Timer Sample Tests ====================

    @Test
    void startConfigPublishTimer_shouldReturnSample() {
        Timer.Sample sample = businessMetrics.startConfigPublishTimer();
        assertNotNull(sample);
    }

    @Test
    void recordConfigPublishDuration_withSample_shouldRecord() {
        Timer.Sample sample = businessMetrics.startConfigPublishTimer();
        // Simulate some work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        businessMetrics.recordConfigPublishDuration(sample);

        Timer timer = meterRegistry.find("gateway_config_publish_duration").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 10);
    }

    @Test
    void startNacosQueryTimer_shouldReturnSample() {
        Timer.Sample sample = businessMetrics.startNacosQueryTimer();
        assertNotNull(sample);
    }

    @Test
    void recordNacosQueryDuration_withSample_shouldRecord() {
        Timer.Sample sample = businessMetrics.startNacosQueryTimer();
        businessMetrics.recordNacosQueryDuration(sample);

        Timer timer = meterRegistry.find("gateway_nacos_query_duration").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    // ==================== Helper Methods ====================

    private double getRouteCounterValue(String operation) {
        return meterRegistry.find("gateway_route_operations_total")
                .tag("operation", operation)
                .counter()
                .count();
    }

    private double getGaugeValue(String name) {
        return meterRegistry.find(name).gauge().value();
    }
}