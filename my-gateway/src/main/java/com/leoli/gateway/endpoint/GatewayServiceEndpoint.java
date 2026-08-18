package com.leoli.gateway.endpoint;

import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.health.InstanceHealth;
import com.leoli.gateway.manager.ServiceManager;
import com.leoli.gateway.manager.ServiceManager.ServiceEndpoint;
import com.leoli.gateway.manager.ServiceManager.ServiceInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Actuator endpoint for querying gateway service cache.
 * <p>
 * Useful for troubleshooting service routing issues:
 * - GET /actuator/gatewayServices            → list all configured services
 * - GET /actuator/gatewayServices/{serviceId} → get service instances and load balancer config
 * <p>
 * Shows the gateway's internal service cache (from Nacos/Consul config),
 * not the SCG discovery client registry. Includes real-time health status
 * from HybridHealthChecker (passive + active checks).
 *
 * @author leoli
 */
@Component
@Endpoint(id = "gatewayServices")
public class GatewayServiceEndpoint {

    private final ServiceManager serviceManager;
    private final HybridHealthChecker healthChecker;

    @Value("${gateway.instance-id:gateway-unknown}")
    private String instanceId;

    public GatewayServiceEndpoint(ServiceManager serviceManager, HybridHealthChecker healthChecker) {
        this.serviceManager = serviceManager;
        this.healthChecker = healthChecker;
    }

    /**
     * List all configured services with summary info.
     */
    @ReadOperation
    public Map<String, Object> services() {
        Set<String> serviceIds = serviceManager.getAllConfiguredServiceIds();

        List<Map<String, Object>> services = new ArrayList<>();
        for (String serviceId : serviceIds) {
            List<ServiceInstance> instances = serviceManager.getServiceInstances(serviceId);
            int healthyCount = 0;
            for (ServiceInstance inst : instances) {
                InstanceHealth health = healthChecker.getHealth(serviceId, inst.getIp(), inst.getPort());
                if (health == null || health.isHealthy()) {
                    healthyCount++;
                }
            }

            Map<String, Object> service = new LinkedHashMap<>();
            service.put("serviceId", serviceId);
            service.put("loadBalancer", serviceManager.getLoadBalancerStrategy(serviceId));
            service.put("instanceCount", instances.size());
            service.put("healthyCount", healthyCount);
            services.add(service);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("count", serviceIds.size());
        result.put("services", services);
        return result;
    }

    /**
     * Get details for a specific service, including all instances with real health status.
     */
    @ReadOperation
    public Map<String, Object> serviceById(@Selector String serviceId) {
        List<ServiceInstance> instances = serviceManager.getServiceInstances(serviceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("serviceId", serviceId);

        if (instances.isEmpty()) {
            result.put("found", false);
            result.put("message", "Service not found in cache. Route may fail with 503 'No available instances'.");
        } else {
            result.put("found", true);
            result.put("loadBalancer", serviceManager.getLoadBalancerStrategy(serviceId));

            // Build instance list with real health status from HybridHealthChecker
            List<Map<String, Object>> instanceDetails = new ArrayList<>();
            for (ServiceInstance inst : instances) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("address", inst.getAddress());
                detail.put("ip", inst.getIp());
                detail.put("port", inst.getPort());
                detail.put("weight", inst.getWeight());
                detail.put("enabled", inst.isEnabled());

                // Real health status from HybridHealthChecker
                InstanceHealth health = healthChecker.getHealth(serviceId, inst.getIp(), inst.getPort());
                if (health == null) {
                    detail.put("healthy", true);
                    detail.put("healthCheckType", "INIT");
                    detail.put("healthChecked", false);
                    detail.put("message", "No health check record yet. Instance treated as healthy (will be checked on first request).");
                } else {
                    detail.put("healthy", health.isHealthy());
                    detail.put("healthCheckType", health.getCheckType());
                    detail.put("healthChecked", true);
                    detail.put("consecutiveFailures", health.getConsecutiveFailures());
                    if (!health.isHealthy() && health.getUnhealthyReason() != null) {
                        detail.put("unhealthyReason", health.getUnhealthyReason());
                    }
                }

                instanceDetails.add(detail);
            }
            result.put("instances", instanceDetails);

            ServiceEndpoint endpoint = serviceManager.getServiceEndpoint(serviceId);
            if (endpoint != null) {
                result.put("primaryEndpoint", endpoint);
            }
        }

        return result;
    }
}
