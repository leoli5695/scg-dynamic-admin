package com.leoli.gateway.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.manager.ServiceManager;
import com.leoli.gateway.discovery.staticdiscovery.StaticDiscoveryService;
import com.leoli.gateway.filter.loadbalancer.InstanceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceHealthConfigTest {
    @Test
    void disablingReenablingAndRemovingNodesPreservesObservedHealthWithoutServingDisabledNodes() throws Exception {
        var manager = new ServiceManager();
        var health = new HybridHealthChecker();
        ReflectionTestUtils.setField(manager, "hybridHealthChecker", health);
        ReflectionTestUtils.setField(health, "serviceManager", manager);
        ReflectionTestUtils.setField(health, "networkFlapThreshold", 100);
        ReflectionTestUtils.setField(health, "stableCheckThreshold", 10);
        var mapper = new ObjectMapper();
        var discovery = new StaticDiscoveryService(manager);
        var filter = new InstanceFilter(health, mock(ActiveHealthChecker.class));
        String config = """
                {"name":"svc","instances":[{"ip":"127.0.0.1","port":8080,"enabled":%s}]}
                """;
        manager.parseAndCacheService("svc", mapper.readTree(config.formatted(true)));
        health.markHealthy("svc", "127.0.0.1", 8080, "ACTIVE");
        var observation = health.getHealth("svc", "127.0.0.1", 8080);
        manager.parseAndCacheService("svc", mapper.readTree(config.formatted(false)));
        assertSame(observation, health.getHealth("svc", "127.0.0.1", 8080));
        assertTrue(filter.filter(discovery.getInstances("svc")).isEmpty());
        health.markHealthy("svc", "127.0.0.1", 8080, "ACTIVE");
        assertTrue(filter.filter(discovery.getInstances("svc")).isEmpty(), "successful probes must not enable an administratively disabled node");
        manager.parseAndCacheService("svc", mapper.readTree(config.formatted(true)));
        assertEquals(1, filter.filter(discovery.getInstances("svc")).size());
        manager.parseAndCacheService("svc", mapper.readTree("{\"name\":\"svc\",\"instances\":[]}"));
        assertTrue(discovery.getInstances("svc").isEmpty());
        assertNull(health.getHealth("svc", "127.0.0.1", 8080));
    }
}
