package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.model.AuthPolicyDefinition;
import com.leoli.gateway.admin.model.AuthPolicyEntity;
import com.leoli.gateway.admin.repository.AuthPolicyRepository;
import com.leoli.gateway.admin.service.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for auth policy configurations.
 * Ensures consistency between DB and Nacos for auth policies and route bindings.
 */
@Slf4j
@Component
public class AuthPolicyReconcileTask implements ReconcileTask<AuthPolicyEntity> {

    private static final String AUTH_POLICY_PREFIX = "config.gateway.auth-policy-";
    private static final String AUTH_POLICIES_INDEX = "config.gateway.metadata.auth-policies-index";
    private static final String AUTH_ROUTES_PREFIX = "config.gateway.auth-routes-";
    private static final String GROUP = "DEFAULT_GROUP";

    @Autowired
    private AuthPolicyRepository authPolicyRepository;

    @Autowired
    private InstanceNamespaceCache namespaceCache;

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AlertService alertService;

    @Override
    public String getType() {
        return "AUTH_POLICY";
    }

    @Override
    public List<AuthPolicyEntity> loadFromDB() {
        // Only load ENABLED policies - disabled policies should not be in Nacos
        return authPolicyRepository.findByEnabledTrue();
    }

    @Override
    public Set<String> loadFromNacos() {
        // Note: This loads from default namespace (public), which is legacy behavior
        // The actual reconciliation now uses per-instance namespace
        try {
            // Read as List<String> since index is stored as JSON array
            List<String> policyIds = configCenterService.getConfig(AUTH_POLICIES_INDEX,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (policyIds == null || policyIds.isEmpty()) {
                return Set.of();
            }
            return policyIds.stream().collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to load auth policies index from Nacos", e);
            return Set.of();
        }
    }

    @Override
    public String extractId(AuthPolicyEntity entity) {
        return entity.getPolicyId();  // Use policyId (UUID) as business identifier
    }

    @Override
    public void repairMissingInNacos(AuthPolicyEntity entity) throws Exception {
        // Skip disabled policies - they should NOT be in Nacos
        if (!entity.getEnabled()) {
            log.debug("Skipping disabled auth policy: {}", entity.getPolicyId());
            return;
        }

        // Skip policies without valid instanceId - should NOT publish to public namespace
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());
        if (nacosNamespace == null) {
            log.warn("Skipping auth policy {} without valid instanceId (instanceId={}), will not publish to public namespace",
                     entity.getPolicyId(), entity.getInstanceId());
            return;
        }

        log.info("Repairing missing auth policy in Nacos: {}", entity.getPolicyId());

        // Convert entity to definition
        AuthPolicyDefinition policy = toDefinition(entity);

        // Push to Nacos using policyId and instance namespace
        String policyDataId = AUTH_POLICY_PREFIX + entity.getPolicyId();
        configCenterService.publishConfig(policyDataId, nacosNamespace, policy);

        log.info("Repaired auth policy: {} in namespace: {}", entity.getPolicyId(), nacosNamespace);

        // Rebuild policies index to ensure consistency
        rebuildPoliciesIndex(entity.getInstanceId());
    }

    @Override
    public void removeOrphanFromNacos(String policyId) throws Exception {
        log.info("Removing orphaned auth policy from Nacos: {}", policyId);

        // Find the policy to get instanceId
        AuthPolicyEntity policy = authPolicyRepository.findByPolicyId(policyId).orElse(null);
        String nacosNamespace = policy != null ? getNacosNamespace(policy.getInstanceId()) : null;

        // Skip if no valid namespace - do NOT remove from public namespace
        if (nacosNamespace == null) {
            log.warn("Skipping orphan auth policy {} without valid instanceId, will not remove from public namespace", policyId);
            return;
        }

        // Delete policy config from Nacos
        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        configCenterService.removeConfig(policyDataId, nacosNamespace);

        // Delete routes list from Nacos
        String routesDataId = AUTH_ROUTES_PREFIX + policyId;
        configCenterService.removeConfig(routesDataId, nacosNamespace);

        log.info("Removed orphan auth policy: {} and its routes from namespace: {}", policyId, nacosNamespace);

        // Rebuild policies index after removal
        if (policy != null) {
            rebuildPoliciesIndex(policy.getInstanceId());
        }
    }

    /**
     * Get nacosNamespace from instanceId (uses cache).
     */
    private String getNacosNamespace(String instanceId) {
        return namespaceCache.getNamespace(instanceId);
    }

    /**
     * Rebuild policies index from database for a specific instance.
     * Only includes ENABLED policies - disabled policies should not be in Nacos.
     */
    private void rebuildPoliciesIndex(String instanceId) throws Exception {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }

        String nacosNamespace = getNacosNamespace(instanceId);
        if (nacosNamespace == null) {
            return;
        }

        // Only include ENABLED policies for this instance
        List<String> policyIds = authPolicyRepository.findByInstanceIdAndEnabledTrue(instanceId).stream()
            .map(AuthPolicyEntity::getPolicyId)
            .collect(Collectors.toList());

        // Publish as JSON array to instance namespace
        configCenterService.publishConfig(AUTH_POLICIES_INDEX, nacosNamespace, policyIds);
        log.debug("Auth policies index rebuilt with {} enabled policies in namespace {}", policyIds.size(), nacosNamespace);
    }

    /**
     * Convert entity to definition.
     */
    private AuthPolicyDefinition toDefinition(AuthPolicyEntity entity) {
        if (entity == null) {
            return null;
        }

        // Try to parse from config JSON first
        if (entity.getConfig() != null && !entity.getConfig().isEmpty()) {
            try {
                AuthPolicyDefinition policy = objectMapper.readValue(entity.getConfig(), AuthPolicyDefinition.class);
                policy.setPolicyId(entity.getPolicyId());
                policy.setPolicyName(entity.getPolicyName());
                policy.setAuthTypeEnum(entity.getAuthType());
                policy.setEnabled(entity.getEnabled());
                return policy;
            } catch (Exception e) {
                log.warn("Failed to parse policy config JSON: {}", e.getMessage());
            }
        }

        // Fallback: create from entity fields
        AuthPolicyDefinition policy = new AuthPolicyDefinition();
        policy.setPolicyId(entity.getPolicyId());
        policy.setPolicyName(entity.getPolicyName());
        policy.setAuthTypeEnum(entity.getAuthType());
        policy.setEnabled(entity.getEnabled());
        return policy;
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}