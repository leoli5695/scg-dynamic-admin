package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.model.ServiceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.ServiceRepository;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.service.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for service configurations.
 *
 * <p>Services live in their own instance's Nacos namespace, so reconciliation has to be
 * namespace-complete. See {@link NamespacedReconcile} for the algorithm and for the bug this task
 * used to have (public-namespace index compared against per-namespace writes, which republished
 * every service every cycle and made orphan removal a structural no-op).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceReconcileTask implements ReconcileTask<ServiceEntity> {

    private static final String SERVICE_PREFIX = "config.gateway.service-";
    private static final String SERVICES_INDEX = "config.gateway.metadata.services-index";
    private static final String GROUP = "DEFAULT_GROUP";

    private final ServiceRepository serviceRepository;
    private final GatewayInstanceRepository gatewayInstanceRepository;
    private final InstanceNamespaceCache namespaceCache;
    private final ConfigCenterService configCenterService;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;

    @Override
    public String getType() {
        return "SERVICE";
    }

    @Override
    public ReconcileResult reconcile() {
        return NamespacedReconcile.reconcile(new NamespacedReconcile.Spec<>(
                getType(),
                configCenterService,
                log,
                serviceRepository.findAll(),
                service -> Boolean.TRUE.equals(service.getEnabled()),
                ServiceEntity::getServiceId,
                // A service belongs to exactly one namespace - unlike strategies there is no GLOBAL
                // broadcast scope. An empty result makes the reconciler skip the service and warn.
                service -> {
                    String ns = getNacosNamespace(service.getInstanceId());
                    return ns != null ? List.of(ns) : List.of();
                },
                SERVICE_PREFIX,
                SERVICES_INDEX,
                this::toDefinition,
                getAllGatewayNamespaces(),
                alertService));
    }

    @Override
    public List<ServiceEntity> loadFromDB() {
        // Only load ENABLED services - disabled services should not be synced to Nacos
        return serviceRepository.findByEnabledTrue();
    }

    @Override
    public Set<String> loadFromNacos() {
        // Retained for the ReconcileTask contract and for ad-hoc inspection. It reads the PUBLIC
        // namespace index, which per-instance service config is never written to - that mismatch is
        // exactly what reconcile() above works around, so do not use this to decide what is missing.
        try {
            // Read as List<String> since index is stored as JSON array
            List<String> serviceNames = configCenterService.getConfig(SERVICES_INDEX,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (serviceNames == null || serviceNames.isEmpty()) {
                return Set.of();
            }
            return serviceNames.stream().collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to load services index from Nacos", e);
            return Set.of();
        }
    }

    /**
     * Load all service dataIds from Nacos for reconciliation.
     */
    public Set<String> loadAllServiceDataIdsFromNacos() {
        try {
            // This would require listing all configs in a namespace, which is not supported by Nacos API
            // Instead, we rely on the services-index to know what should exist
            return loadFromNacos();
        } catch (Exception e) {
            log.error("Failed to load service dataIds from Nacos", e);
            return Set.of();
        }
    }

    @Override
    public String extractId(ServiceEntity entity) {
        return entity.getServiceId();  // Use service_id (UUID) as business identifier
    }

    @Override
    public void repairMissingInNacos(ServiceEntity entity) throws Exception {
        // Skip disabled services - they should NOT be in Nacos
        if (!entity.getEnabled()) {
            log.debug("Skipping disabled service: {}", entity.getServiceId());
            return;
        }

        // Skip services without valid instanceId - should NOT publish to public namespace
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());
        if (nacosNamespace == null) {
            log.warn("Skipping service {} without valid instanceId (instanceId={}), will not publish to public namespace",
                     entity.getServiceId(), entity.getInstanceId());
            return;
        }

        log.info("Repairing missing service in Nacos: {}", entity.getServiceId());

        // Parse full ServiceDefinition from entity's metadata JSON
        // This preserves instances, loadBalancer, and other configuration
        ServiceDefinition service = toDefinition(entity);

        // Push to Nacos using service_id and instance namespace. A false return used to be discarded
        // here, so the caller counted the service as repaired and reported success while nothing had
        // actually landed in Nacos.
        String serviceDataId = SERVICE_PREFIX + entity.getServiceId();
        if (!configCenterService.publishConfig(serviceDataId, nacosNamespace, service)) {
            throw new IllegalStateException("Failed to publish service " + entity.getServiceId()
                    + " to namespace " + nacosNamespace);
        }

        log.info("Repaired service: {} with {} instances in namespace: {}",
                 entity.getServiceId(),
                 service.getInstances() != null ? service.getInstances().size() : 0,
                 nacosNamespace);

        // Rebuild services index to ensure consistency
        rebuildServicesIndex(entity.getInstanceId());
    }

    /**
     * The published shape of a service: its metadata JSON when that parses (preserving instances,
     * loadBalancer and the rest), otherwise a minimal definition that at least carries the id and name.
     */
    private ServiceDefinition toDefinition(ServiceEntity entity) {
        // Try to restore from JSON backup in metadata field
        if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
            try {
                ServiceDefinition service = objectMapper.readValue(entity.getMetadata(), ServiceDefinition.class);
                if (service != null) {
                    log.debug("Successfully parsed service definition from metadata for: {}", entity.getServiceName());
                    return service;
                }
            } catch (Exception e) {
                log.warn("Failed to parse service from metadata JSON, falling back to minimal definition: {}", e.getMessage());
            }
        }

        // Fallback: create minimal definition (should rarely happen)
        log.warn("Creating minimal service definition for: {} (no valid metadata)", entity.getServiceName());
        ServiceDefinition service = new ServiceDefinition();
        service.setName(entity.getServiceName());
        service.setServiceId(entity.getServiceId());
        return service;
    }

    @Override
    public void removeOrphanFromNacos(String serviceId) throws Exception {
        log.info("Removing orphaned service from Nacos: {}", serviceId);

        // Find the service to get instanceId. NOTE: for a true orphan this lookup comes back empty, so
        // the namespace resolves to null and nothing is removed - which is why reconcile() does
        // orphan removal per namespace instead of through this method. Kept for the contract and for
        // the row-still-exists case (a service that was disabled and should stop being published).
        ServiceEntity service = serviceRepository.findByServiceId(serviceId).orElse(null);
        String nacosNamespace = service != null ? getNacosNamespace(service.getInstanceId()) : null;

        // Skip if no valid namespace - do NOT remove from public namespace
        if (nacosNamespace == null) {
            log.warn("Skipping orphan service {} without valid instanceId, will not remove from public namespace", serviceId);
            return;
        }

        // Delete from Nacos using service_id
        String serviceDataId = SERVICE_PREFIX + serviceId;
        if (!configCenterService.removeConfig(serviceDataId, nacosNamespace)) {
            throw new IllegalStateException("Failed to remove service " + serviceId
                    + " from namespace " + nacosNamespace);
        }

        log.info("Removed orphan service: {} from namespace: {}", serviceId, nacosNamespace);

        // Rebuild services index after removal to maintain consistency
        if (service != null) {
            rebuildServicesIndex(service.getInstanceId());
        }
    }

    /**
     * Get nacosNamespace from instanceId (uses cache).
     */
    private String getNacosNamespace(String instanceId) {
        return namespaceCache.getNamespace(instanceId);
    }

    private List<String> getAllGatewayNamespaces() {
        return gatewayInstanceRepository.findAll().stream()
                .map(i -> i.getNacosNamespace())
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Rebuild services index from database for a specific instance.
     * Only includes ENABLED services since disabled services have no config in Nacos.
     */
    private void rebuildServicesIndex(String instanceId) throws Exception {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }

        String nacosNamespace = getNacosNamespace(instanceId);
        if (nacosNamespace == null) {
            return;
        }

        // Only include ENABLED services for this instance
        List<String> serviceIds = serviceRepository.findByInstanceIdAndEnabledTrue(instanceId).stream()
            .map(ServiceEntity::getServiceId)
            .collect(Collectors.toList());

        // Publish as JSON array to instance namespace
        if (!configCenterService.publishConfig(SERVICES_INDEX, nacosNamespace, serviceIds)) {
            log.error("Failed to rebuild services index in namespace {}", nacosNamespace);
        } else {
            log.debug("Services index rebuilt with {} enabled services in namespace {}", serviceIds.size(), nacosNamespace);
        }
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}