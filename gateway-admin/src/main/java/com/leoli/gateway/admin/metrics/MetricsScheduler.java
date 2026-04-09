package com.leoli.gateway.admin.metrics;

import com.leoli.gateway.admin.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to update business metrics.
 * Periodically queries database and updates gauge metrics.
 *
 * @author leoli
 */
@Slf4j
@Component
public class MetricsScheduler {

    @Autowired
    private BusinessMetrics businessMetrics;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private StrategyRepository strategyRepository;

    @Autowired
    private GatewayInstanceRepository instanceRepository;

    /**
     * Update metrics every 30 seconds.
     */
    @Scheduled(fixedRate = 30000, initialDelay = 10000)
    public void updateMetrics() {
        try {
            updateRouteMetrics();
            updateServiceMetrics();
            updateStrategyMetrics();
            updateInstanceMetrics();
            log.debug("Business metrics updated");
        } catch (Exception e) {
            log.warn("Failed to update business metrics: {}", e.getMessage());
        }
    }

    private void updateRouteMetrics() {
        long total = routeRepository.count();
        long enabled = routeRepository.countByEnabledTrue();
        businessMetrics.updateRouteCounts(total, enabled);
    }

    private void updateServiceMetrics() {
        long total = serviceRepository.count();
        long enabled = serviceRepository.countByEnabledTrue();
        businessMetrics.updateServiceCounts(total, enabled);
    }

    private void updateStrategyMetrics() {
        long total = strategyRepository.count();
        long enabled = strategyRepository.countByEnabledTrue();
        businessMetrics.updateStrategyCounts(total, enabled);
    }

    private void updateInstanceMetrics() {
        long total = instanceRepository.count();
        long healthy = instanceRepository.countByStatus("HEALTHY");
        businessMetrics.updateInstanceCounts(total, healthy);
    }
}