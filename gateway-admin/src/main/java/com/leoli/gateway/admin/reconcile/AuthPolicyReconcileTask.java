package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.model.AuthPolicyDefinition;
import com.leoli.gateway.admin.model.AuthPolicyEntity;
import com.leoli.gateway.admin.model.RouteAuthBindingEntity;
import com.leoli.gateway.admin.repository.AuthPolicyRepository;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteAuthBindingRepository;
import com.leoli.gateway.admin.service.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for auth policy configurations.
 * Ensures consistency between DB and Nacos for auth policies and route bindings.
 *
 * <p>Auth policies live in their own instance's Nacos namespace, so reconciliation has to be
 * namespace-complete. See {@link NamespacedReconcile} for the algorithm and for the bug this task
 * used to have (public-namespace index compared against per-namespace writes, which republished
 * every policy every cycle and made orphan removal a structural no-op).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthPolicyReconcileTask implements ReconcileTask<AuthPolicyEntity> {

    private static final String AUTH_POLICY_PREFIX = "config.gateway.auth-policy-";
    private static final String AUTH_POLICIES_INDEX = "config.gateway.metadata.auth-policies-index";
    private static final String AUTH_ROUTES_PREFIX = "config.gateway.auth-routes-";
    private static final String GROUP = "DEFAULT_GROUP";

    private final AuthPolicyRepository authPolicyRepository;
    private final RouteAuthBindingRepository routeAuthBindingRepository;
    private final GatewayInstanceRepository gatewayInstanceRepository;
    private final InstanceNamespaceCache namespaceCache;
    private final ConfigCenterService configCenterService;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;

    @Override
    public String getType() {
        return "AUTH_POLICY";
    }

    @Override
    public ReconcileResult reconcile() {
        return NamespacedReconcile.reconcile(new NamespacedReconcile.Spec<>(
                getType(),
                configCenterService,
                log,
                authPolicyRepository.findAll(),
                policy -> Boolean.TRUE.equals(policy.getEnabled()),
                AuthPolicyEntity::getPolicyId,
                // An auth policy belongs to exactly one namespace - unlike strategies there is no
                // GLOBAL broadcast scope. An empty result makes the reconciler skip it and warn.
                policy -> {
                    String ns = getNacosNamespace(policy.getInstanceId());
                    return ns != null ? List.of(ns) : List.of();
                },
                AUTH_POLICY_PREFIX,
                AUTH_POLICIES_INDEX,
                this::toDefinition,
                getAllGatewayNamespaces(),
                alertService,
                // A policy publishes a SECOND dataId holding its bound route list. Without these
                // hooks the reconciler would repair/remove only the primary config: a republished
                // policy would apply to no routes, and an orphan's route list would stay in Nacos
                // forever. Neither the old code nor the first migration handled this - the old
                // removeOrphanFromNacos did delete both dataIds, but it never ran for a real orphan
                // because the namespace lookup for a row that is not in the DB returns null.
                this::repairPolicyRoutes,
                this::removePolicyRoutes));
    }

    /**
     * Publish a policy's bound-route list next to its primary config. Mirrors
     * {@code AuthPolicyService.publishPolicyRoutesToNacos}: an empty binding list means the dataId
     * should not exist at all, so it is removed rather than published as an empty array.
     */
    private void repairPolicyRoutes(String policyId, String namespace) {
        List<String> routeIds = routeAuthBindingRepository.findByPolicyIdAndEnabledTrue(policyId).stream()
                .map(RouteAuthBindingEntity::getRouteId)
                .distinct()
                .collect(Collectors.toList());
        String dataId = AUTH_ROUTES_PREFIX + policyId;
        if (routeIds.isEmpty()) {
            configCenterService.removeConfig(dataId, namespace);
            return;
        }
        if (!configCenterService.publishConfig(dataId, namespace, routeIds)) {
            log.error("Failed to publish auth routes for policy {} in namespace {}", policyId, namespace);
        }
    }

    /**
     * Remove a policy's bound-route list. Deliberately logs instead of throwing when Nacos reports
     * failure: this dataId is OPTIONAL (a policy with no bound routes has none), so "not found" is a
     * normal outcome and must not be treated as an error.
     */
    private void removePolicyRoutes(String policyId, String namespace) {
        if (!configCenterService.removeConfig(AUTH_ROUTES_PREFIX + policyId, namespace)) {
            log.debug("No auth-routes config to remove for policy {} in namespace {} (optional dataId)",
                    policyId, namespace);
        }
    }

    @Override
    public List<AuthPolicyEntity> loadFromDB() {
        // Only load ENABLED policies - disabled policies should not be in Nacos
        return authPolicyRepository.findByEnabledTrue();
    }

    @Override
    public Set<String> loadFromNacos() {
        // Retained for the ReconcileTask contract and for ad-hoc inspection. It reads the PUBLIC
        // namespace index, which per-instance auth policy config is never written to - that mismatch
        // is exactly what reconcile() above works around, so do not use this to decide what is missing.
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

        // Push to Nacos using policyId and instance namespace. A false return used to be discarded
        // here, so the caller counted the policy as repaired and reported success while nothing had
        // actually landed in Nacos.
        String policyDataId = AUTH_POLICY_PREFIX + entity.getPolicyId();
        if (!configCenterService.publishConfig(policyDataId, nacosNamespace, policy)) {
            throw new IllegalStateException("Failed to publish auth policy " + entity.getPolicyId()
                    + " to namespace " + nacosNamespace);
        }

        log.info("Repaired auth policy: {} in namespace: {}", entity.getPolicyId(), nacosNamespace);

        // Rebuild policies index to ensure consistency
        rebuildPoliciesIndex(entity.getInstanceId());
    }

    @Override
    public void removeOrphanFromNacos(String policyId) throws Exception {
        log.info("Removing orphaned auth policy from Nacos: {}", policyId);

        // Find the policy to get instanceId. NOTE: for a true orphan this lookup comes back empty, so
        // the namespace resolves to null and nothing is removed - which is why reconcile() does
        // orphan removal per namespace instead of through this method. Kept for the contract and for
        // the row-still-exists case (a policy that was disabled and should stop being published).
        AuthPolicyEntity policy = authPolicyRepository.findByPolicyId(policyId).orElse(null);
        String nacosNamespace = policy != null ? getNacosNamespace(policy.getInstanceId()) : null;

        // Skip if no valid namespace - do NOT remove from public namespace
        if (nacosNamespace == null) {
            log.warn("Skipping orphan auth policy {} without valid instanceId, will not remove from public namespace", policyId);
            return;
        }

        // Delete policy config from Nacos
        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        if (!configCenterService.removeConfig(policyDataId, nacosNamespace)) {
            throw new IllegalStateException("Failed to remove auth policy " + policyId
                    + " from namespace " + nacosNamespace);
        }

        // Delete routes list from Nacos. This dataId is optional - a policy with no bound routes
        // has none - so a false return is logged, not thrown.
        removePolicyRoutes(policyId, nacosNamespace);

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

    private List<String> getAllGatewayNamespaces() {
        return gatewayInstanceRepository.findAll().stream()
                .map(i -> i.getNacosNamespace())
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .collect(Collectors.toList());
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
        if (!configCenterService.publishConfig(AUTH_POLICIES_INDEX, nacosNamespace, policyIds)) {
            log.error("Failed to rebuild auth policies index in namespace {}", nacosNamespace);
        } else {
            log.debug("Auth policies index rebuilt with {} enabled policies in namespace {}", policyIds.size(), nacosNamespace);
        }
    }

    /**
     * The published shape of an auth policy: its config JSON when that parses, otherwise a minimal
     * definition built from the entity's own columns. Either way the identity fields come from the
     * entity, so a stale config blob cannot rename or re-type the policy.
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