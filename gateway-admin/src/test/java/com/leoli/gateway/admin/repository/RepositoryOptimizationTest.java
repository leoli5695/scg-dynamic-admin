package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.BaseIntegrationTest;
import com.leoli.gateway.admin.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Repository optimization methods.
 */
@Transactional
class RepositoryOptimizationTest extends BaseIntegrationTest {

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private StrategyRepository strategyRepository;

    @Autowired
    private GatewayInstanceRepository gatewayInstanceRepository;

    private String testInstanceId;

    @BeforeEach
    void setUp() {
        // Create a test gateway instance
        GatewayInstanceEntity instance = new GatewayInstanceEntity();
        instance.setInstanceId("test-instance-" + System.currentTimeMillis());
        instance.setInstanceName("Test Instance");
        instance.setNamespace("test-namespace");
        instance.setNacosNamespace("test-nacos-ns");
        instance.setEnabled(true);
        instance.setStatusCode(1);
        instance.setStatus("running");
        instance = gatewayInstanceRepository.save(instance);
        testInstanceId = instance.getInstanceId();

        // Create test routes
        createTestRoute("route-1", testInstanceId, true);
        createTestRoute("route-2", testInstanceId, true);
        createTestRoute("route-3", testInstanceId, false);

        // Create test services
        createTestService("service-1", testInstanceId, true);
        createTestService("service-2", testInstanceId, false);

        // Create test strategies
        createTestStrategy("strategy-1", testInstanceId, "RATE_LIMITER", true);
        createTestStrategy("strategy-2", testInstanceId, "IP_FILTER", false);
    }

    // ==================== RouteRepository Tests ====================

    @Test
    void countByEnabled_shouldReturnCorrectCount() {
        long enabledCount = routeRepository.countByEnabled(true);
        long disabledCount = routeRepository.countByEnabled(false);

        assertTrue(enabledCount >= 2);
        assertTrue(disabledCount >= 1);
    }

    @Test
    void countByEnabledTrue_shouldReturnEnabledRoutes() {
        long count = routeRepository.countByEnabledTrue();
        assertTrue(count >= 2);
    }

    @Test
    void findEnabledRouteIdsByInstanceId_shouldReturnOnlyEnabledIds() {
        List<String> ids = routeRepository.findEnabledRouteIdsByInstanceId(testInstanceId);

        assertNotNull(ids);
        assertTrue(ids.size() >= 2);
        // Should only return enabled routes
        for (String id : ids) {
            RouteEntity route = routeRepository.findById(id).orElse(null);
            assertNotNull(route);
            assertTrue(route.getEnabled());
        }
    }

    @Test
    void findByInstanceIdAndEnabledTrue_shouldReturnEnabledRoutesForInstance() {
        List<RouteEntity> routes = routeRepository.findByInstanceIdAndEnabledTrue(testInstanceId);

        assertNotNull(routes);
        assertTrue(routes.size() >= 2);
        for (RouteEntity route : routes) {
            assertEquals(testInstanceId, route.getInstanceId());
            assertTrue(route.getEnabled());
        }
    }

    // ==================== ServiceRepository Tests ====================

    @Test
    void serviceCountByEnabled_shouldReturnCorrectCount() {
        long enabledCount = serviceRepository.countByEnabled(true);
        long disabledCount = serviceRepository.countByEnabled(false);

        assertTrue(enabledCount >= 1);
        assertTrue(disabledCount >= 1);
    }

    @Test
    void serviceCountByEnabledTrue_shouldReturnEnabledServices() {
        long count = serviceRepository.countByEnabledTrue();
        assertTrue(count >= 1);
    }

    @Test
    void findEnabledServiceIdsByInstanceId_shouldReturnOnlyEnabledIds() {
        List<String> ids = serviceRepository.findEnabledServiceIdsByInstanceId(testInstanceId);

        assertNotNull(ids);
        assertTrue(ids.size() >= 1);
    }

    @Test
    void findByInstanceIdAndEnabledTrue_shouldReturnEnabledServicesForInstance() {
        List<ServiceEntity> services = serviceRepository.findByInstanceIdAndEnabledTrue(testInstanceId);

        assertNotNull(services);
        assertTrue(services.size() >= 1);
        for (ServiceEntity service : services) {
            assertEquals(testInstanceId, service.getInstanceId());
            assertTrue(service.getEnabled());
        }
    }

    // ==================== StrategyRepository Tests ====================

    @Test
    void strategyCountByEnabled_shouldReturnCorrectCount() {
        long enabledCount = strategyRepository.countByEnabled(true);
        long disabledCount = strategyRepository.countByEnabled(false);

        assertTrue(enabledCount >= 1);
        assertTrue(disabledCount >= 1);
    }

    @Test
    void strategyCountByEnabledTrue_shouldReturnEnabledStrategies() {
        long count = strategyRepository.countByEnabledTrue();
        assertTrue(count >= 1);
    }

    @Test
    void countByStrategyTypeAndInstanceId_shouldReturnCorrectCount() {
        long count = strategyRepository.countByStrategyTypeAndInstanceId("RATE_LIMITER", testInstanceId);
        assertTrue(count >= 1);
    }

    @Test
    void findByStrategyTypeAndEnabledTrue_shouldReturnEnabledStrategiesByType() {
        List<StrategyEntity> strategies = strategyRepository.findByStrategyTypeAndEnabledTrue("RATE_LIMITER");

        assertNotNull(strategies);
        for (StrategyEntity strategy : strategies) {
            assertEquals("RATE_LIMITER", strategy.getStrategyType());
            assertTrue(strategy.getEnabled());
        }
    }

    @Test
    void findByInstanceIdAndEnabledTrue_shouldReturnEnabledStrategiesForInstance() {
        List<StrategyEntity> strategies = strategyRepository.findByInstanceIdAndEnabledTrue(testInstanceId);

        assertNotNull(strategies);
        assertTrue(strategies.size() >= 1);
        for (StrategyEntity strategy : strategies) {
            assertEquals(testInstanceId, strategy.getInstanceId());
            assertTrue(strategy.getEnabled());
        }
    }

    // ==================== GatewayInstanceRepository Tests ====================

    @Test
    void countByEnabledTrue_shouldReturnEnabledInstances() {
        long count = gatewayInstanceRepository.countByEnabledTrue();
        assertTrue(count >= 1);
    }

    @Test
    void countByStatusCode_shouldReturnCorrectCount() {
        long count = gatewayInstanceRepository.countByStatusCode(1);
        assertTrue(count >= 1);
    }

    @Test
    void findByStatusCode_shouldReturnInstancesWithStatusCode() {
        List<GatewayInstanceEntity> instances = gatewayInstanceRepository.findByStatusCode(1);

        assertNotNull(instances);
        assertTrue(instances.size() >= 1);
        for (GatewayInstanceEntity instance : instances) {
            assertEquals(1, instance.getStatusCode());
        }
    }

    // ==================== Helper Methods ====================

    private void createTestRoute(String name, String instanceId, boolean enabled) {
        RouteEntity route = new RouteEntity();
        route.setRouteId("test-route-" + name + "-" + System.nanoTime());
        route.setRouteName(name);
        route.setInstanceId(instanceId);
        route.setEnabled(enabled);
        route.setMetadata("{\"uri\":\"static://test\"}");
        routeRepository.save(route);
    }

    private void createTestService(String name, String instanceId, boolean enabled) {
        ServiceEntity service = new ServiceEntity();
        service.setServiceId("test-service-" + name + "-" + System.nanoTime());
        service.setServiceName(name);
        service.setInstanceId(instanceId);
        service.setEnabled(enabled);
        service.setMetadata("{\"name\":\"" + name + "\"}");
        serviceRepository.save(service);
    }

    private void createTestStrategy(String name, String instanceId, String type, boolean enabled) {
        StrategyEntity strategy = new StrategyEntity();
        strategy.setStrategyId("test-strategy-" + name + "-" + System.nanoTime());
        strategy.setStrategyName(name);
        strategy.setInstanceId(instanceId);
        strategy.setStrategyType(type);
        strategy.setScope("GLOBAL");
        strategy.setEnabled(enabled);
        strategy.setMetadata("{\"type\":\"" + type + "\"}");
        strategyRepository.save(strategy);
    }
}