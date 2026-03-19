package com.leoli.gateway.admin.service;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nacos metadata sync service.
 *
 * @author leoli
 */
@Service
@Slf4j
public class NacosMetadataSyncer {

    @Autowired(required = false)
    private NamingService namingService;

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;

    @Value("${gateway.health.nacos-sync-enabled:false}")
    private boolean nacosSyncEnabled;

    /**
     * Sync health status to Nacos.
     */
    public void syncToNacos(String serviceId, String ip, int port, boolean healthy,
                            String unhealthyReason, String gatewayId) {
        if (!nacosSyncEnabled || namingService == null) {
            log.debug("Nacos sync disabled or NamingService not available");
            return;
        }

        try {
            // Build metadata
            Map<String, String> metadata = new HashMap<>();
            metadata.put("healthy", String.valueOf(healthy));
            metadata.put("unhealthy-source", "GATEWAY_HEALTH_CHECK");
            metadata.put("unhealthy-time", String.valueOf(System.currentTimeMillis()));
            metadata.put("unhealthy-gateway", gatewayId);

            if (!healthy && unhealthyReason != null) {
                metadata.put("unhealthy-reason", unhealthyReason);
            }

            // Get current instances
            List<Instance> instances = namingService.selectInstances(serviceId, true);

            // Find matching instance
            for (Instance instance : instances) {
                if (instance.getIp().equals(ip) && instance.getPort() == port) {
                    // Update metadata
                    updateInstanceMetadata(serviceId, instance, metadata);
                    log.info("Synced instance {}:{}:{} to Nacos with health status: {}",
                            ip, port, serviceId, healthy ? "HEALTHY" : "UNHEALTHY");
                    return;
                }
            }

            log.warn("Instance {}:{} not found in Nacos for service {}", ip, port, serviceId);

        } catch (Exception e) {
            log.error("Failed to sync instance {}:{} to Nacos", ip, port, e);
            // Don't throw exception to keep main flow running
        }
    }

    /**
     * Update instance metadata.
     */
    private void updateInstanceMetadata(String serviceName, Instance instance,
                                         Map<String, String> newMetadata) throws Exception {
        // Merge existing metadata
        Map<String, String> mergedMetadata = new HashMap<>(instance.getMetadata());
        mergedMetadata.putAll(newMetadata);

        // Create new instance object with updated metadata
        Instance newInstance = new Instance();
        newInstance.setIp(instance.getIp());
        newInstance.setPort(instance.getPort());
        newInstance.setWeight(instance.getWeight());
        newInstance.setHealthy(instance.isHealthy());
        newInstance.setEphemeral(instance.isEphemeral());
        newInstance.setClusterName(instance.getClusterName());
        newInstance.setServiceName(serviceName);
        newInstance.setMetadata(mergedMetadata);

        // Register instance (will override existing metadata)
        namingService.registerInstance(serviceName, newInstance);
    }

    /**
     * Batch sync multiple instances.
     */
    public void batchSyncToNacos(List<Map<String, Object>> instances, String gatewayId) {
        if (!nacosSyncEnabled || namingService == null) {
            return;
        }

        for (Map<String, Object> instance : instances) {
            try {
                String serviceId = (String) instance.get("serviceId");
                String ip = (String) instance.get("ip");
                Integer port = (Integer) instance.get("port");
                Boolean healthy = (Boolean) instance.get("healthy");
                String reason = (String) instance.get("unhealthyReason");

                syncToNacos(serviceId, ip, port, healthy != null ? healthy : true,
                        reason, gatewayId);
            } catch (Exception e) {
                log.error("Failed to sync instance to Nacos", e);
            }
        }
    }
}