package com.leoli.gateway.refresher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.util.ConfigContentTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.leoli.gateway.constants.GatewayConfigConstants.*;

/**
 * Authentication Policy configuration refresher.
 * Listens to policies index and individual policy changes in Nacos.
 * <p>
 * Nacos Config Keys:
 * - config.gateway.metadata.auth-policies-index -> ["policyId1", "policyId2", ...]
 * - config.gateway.auth-policy-{policyId} -> AuthConfig JSON
 * - config.gateway.auth-routes-{policyId} -> ["routeId1", "routeId2", ...]
 *
 * @author leoli
 */
@Slf4j
@Component
public class AuthPolicyRefresher {

    private final ObjectMapper objectMapper;
    private final ConfigCenterService configService;
    private final AuthBindingManager authBindingManager;

    private ConfigCenterService.ConfigListener indexListener;
    // Currently listening policy IDs
    private final Set<String> listeningPolicyIds = ConcurrentHashMap.newKeySet();
    // Policy listeners cache: policyId -> listener
    private final ConcurrentHashMap<String, ConfigCenterService.ConfigListener> policyListeners = new ConcurrentHashMap<>();
    // Routes listeners cache: policyId -> listener
    private final ConcurrentHashMap<String, ConfigCenterService.ConfigListener> routesListeners = new ConcurrentHashMap<>();
    // Last applied content per data ID (policy config AND its route bindings), so the
    // periodic reconcile can tell "unchanged" from "edited".
    private final ConfigContentTracker appliedContent = new ConfigContentTracker();

    @Autowired
    public AuthPolicyRefresher(AuthBindingManager authBindingManager,
                               ConfigCenterService configService,
                               ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.authBindingManager = authBindingManager;
        log.info("AuthPolicyRefresher initialized");
    }

    @PostConstruct
    public void init() {
        // 1. Listen to policies index changes
        indexListener = this::onPoliciesIndexChanged;
        configService.addListener(AUTH_POLICIES_INDEX, GROUP, indexListener);
        log.info("✅ Registered listener for auth policies index: {}", AUTH_POLICIES_INDEX);

        // 2. Load all policies initially
        loadAllPolicies();

        log.info("✅ AuthPolicyRefresher initialization completed");
    }

    @PreDestroy
    public void destroy() {
        // Remove index listener
        if (indexListener != null) {
            configService.removeListener(AUTH_POLICIES_INDEX, GROUP, indexListener);
        }

        // Remove all policy listeners
        for (String policyId : listeningPolicyIds) {
            removePolicyListener(policyId);
        }

        log.info("AuthPolicyRefresher destroyed, all listeners removed");
    }

    /**
     * Handle policies index changes.
     */
    private void onPoliciesIndexChanged(String dataId, String group, String content) {
        log.info("🔥 Auth policies index changed, refreshing...");
        loadAllPolicies();
    }

    /**
     * Load all policies from Nacos.
     */
    private void loadAllPolicies() {
        try {
            // Get policies index
            String indexContent = configService.getConfig(AUTH_POLICIES_INDEX, GROUP);

            List<String> policyIds;
            if (indexContent == null || indexContent.isBlank()) {
                // Index absent/empty. If we still hold policies in memory, this means the
                // index was deleted -> we MUST revoke them (diff against empty set below),
                // otherwise stale credentials keep working after being removed in admin.
                if (listeningPolicyIds.isEmpty()) {
                    log.info("No auth policies index found");
                    return;
                }
                log.warn("Auth policies index removed/empty - revoking {} previously loaded policies",
                        listeningPolicyIds.size());
                policyIds = java.util.Collections.emptyList();
            } else {
                policyIds = objectMapper.readValue(indexContent, new TypeReference<List<String>>() {
                });
            }
            log.info("📋 Found {} policies in index", policyIds.size());

            // Sync listeners
            Set<String> currentIds = Set.copyOf(policyIds);
            Set<String> existingIds = Set.copyOf(listeningPolicyIds);

            // Calculate differences
            Set<String> addedPolicies = getDifference(currentIds, existingIds);
            Set<String> removedPolicies = getDifference(existingIds, currentIds);

            log.info("📊 Auth policy changes: +{} added, -{} removed", addedPolicies.size(), removedPolicies.size());

            // Remove listeners for deleted policies
            for (String policyId : removedPolicies) {
                removePolicyListener(policyId);
                authBindingManager.removePolicy(policyId);
                log.info("➖ Removed policy: {}", policyId);
            }

            // Add listeners for new policies
            for (String policyId : addedPolicies) {
                loadPolicyWithRoutes(policyId);
                addPolicyListeners(policyId);
            }

            if (!addedPolicies.isEmpty() || !removedPolicies.isEmpty()) {
                log.info("✅ Auth policies index refresh completed");
            }

        } catch (Exception e) {
            log.error("Failed to load auth policies: {}", e.getMessage());
        }
    }

    /**
     * Load a policy and its routes from Nacos.
     */
    private void loadPolicyWithRoutes(String policyId) {
        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        String policyContent = configService.getConfig(policyDataId, GROUP);
        if (policyContent != null && !policyContent.isEmpty()) {
            appliedContent.recordIfChanged(policyDataId, policyContent);
            applyPolicyContent(policyId, policyContent);
        }

        String routesDataId = AUTH_ROUTES_PREFIX + policyId;
        String routesContent = configService.getConfig(routesDataId, GROUP);
        if (routesContent != null && !routesContent.isEmpty()) {
            appliedContent.recordIfChanged(routesDataId, routesContent);
            applyRoutesContent(policyId, routesContent);
        }
    }

    /**
     * Apply a policy config payload. Blank means "gone" and revokes the policy.
     */
    private void applyPolicyContent(String policyId, String content) {
        try {
            if (content != null && !content.isEmpty()) {
                AuthConfig config = objectMapper.readValue(content, AuthConfig.class);
                authBindingManager.putPolicy(policyId, config);
                log.info("✅ Updated auth policy: {} (type={})", policyId, config.getAuthType());
            } else {
                authBindingManager.removePolicy(policyId);
                log.info("➖ Removed auth policy: {}", policyId);
            }
        } catch (Exception e) {
            log.warn("Failed to update policy {}: {}", policyId, e.getMessage());
        }
    }

    /**
     * Apply a policy's route-binding payload. Blank clears the bindings.
     */
    private void applyRoutesContent(String policyId, String content) {
        try {
            if (content != null && !content.isEmpty()) {
                List<String> routeIds = objectMapper.readValue(content, new TypeReference<List<String>>() {
                });
                authBindingManager.setPolicyRoutes(policyId, routeIds);
                log.info("✅ Updated routes for policy {}: {} routes", policyId, routeIds.size());
            } else {
                authBindingManager.setPolicyRoutes(policyId, List.of());
                log.info("➖ Cleared routes for policy: {}", policyId);
            }
        } catch (Exception e) {
            log.warn("Failed to update routes for policy {}: {}", policyId, e.getMessage());
        }
    }

    /**
     * Add listeners for a policy and its routes.
     */
    private void addPolicyListeners(String policyId) {
        // Policy config listener
        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        ConfigCenterService.ConfigListener policyListener = (dataId, group, content) -> {
            log.info("🔥 Auth policy changed: {}", policyId);
            if (content == null || content.isEmpty()) {
                appliedContent.forget(policyDataId);
            } else {
                appliedContent.recordIfChanged(policyDataId, content);
            }
            applyPolicyContent(policyId, content);
        };
        configService.addListener(policyDataId, GROUP, policyListener);
        policyListeners.put(policyId, policyListener);

        // Routes listener
        String routesDataId = AUTH_ROUTES_PREFIX + policyId;
        ConfigCenterService.ConfigListener routesListener = (dataId, group, content) -> {
            log.info("🔥 Auth routes changed for policy: {}", policyId);
            if (content == null || content.isEmpty()) {
                appliedContent.forget(routesDataId);
            } else {
                appliedContent.recordIfChanged(routesDataId, content);
            }
            applyRoutesContent(policyId, content);
        };
        configService.addListener(routesDataId, GROUP, routesListener);
        routesListeners.put(policyId, routesListener);

        listeningPolicyIds.add(policyId);
        log.debug("✅ Added listeners for policy: {}", policyId);
    }

    /**
     * Remove listeners for a policy.
     */
    private void removePolicyListener(String policyId) {
        // Remove policy listener
        ConfigCenterService.ConfigListener policyListener = policyListeners.remove(policyId);
        if (policyListener != null) {
            String policyDataId = AUTH_POLICY_PREFIX + policyId;
            configService.removeListener(policyDataId, GROUP, policyListener);
            log.debug("Removed policy config listener: {}", policyId);
        }
        appliedContent.forget(AUTH_POLICY_PREFIX + policyId);

        // Remove routes listener
        ConfigCenterService.ConfigListener routesListener = routesListeners.remove(policyId);
        if (routesListener != null) {
            String routesDataId = AUTH_ROUTES_PREFIX + policyId;
            configService.removeListener(routesDataId, GROUP, routesListener);
            log.debug("Removed routes listener: {}", policyId);
        }
        appliedContent.forget(AUTH_ROUTES_PREFIX + policyId);

        listeningPolicyIds.remove(policyId);
        log.info("🗑️ Removed all listeners for policy: {}", policyId);
    }

    /**
     * Get difference between two sets (elements in set1 but not in set2).
     */
    private Set<String> getDifference(Set<String> set1, Set<String> set2) {
        Set<String> result = new HashSet<>(set1);
        result.removeAll(set2);
        return result;
    }

    /**
     * Periodic reconcile against the config center.
     *
     * <p>Stands in for change pushes, which this deployment never receives (nacos-client
     * 3.0.3 against nacos-server 2.5.4 - measured live: the server's remote-push.log stays
     * empty while the publish succeeds). Comparing only the index ID set, as this method
     * used to, recovers additions but leaves console edits and deletions invisible:
     * a revoked policy or a rotated credential would keep being honoured until restart.
     *
     * <p>Unlike {@link #loadAllPolicies()}, a null/blank index returns early instead of
     * revoking everything. {@code getConfig} returns null both when the config is really
     * gone and when the center is unreachable, and a poll cannot tell the two apart;
     * failing closed there would drop every credential on a transient Nacos blip. The
     * fail-closed revoke stays in {@code loadAllPolicies()}, which runs at startup and
     * from the index listener where that reading is unambiguous.
     */
    @Scheduled(fixedRateString = "${gateway.config-sync.interval-ms:60000}")
    public void periodicSyncPolicies() {
        try {
            String indexContent = configService.getConfig(AUTH_POLICIES_INDEX, GROUP);
            if (indexContent == null || indexContent.isBlank()) {
                return;
            }

            List<String> policyIds = objectMapper.readValue(indexContent, new TypeReference<List<String>>() {
            });
            Set<String> indexed = new HashSet<>(policyIds);

            int removed = 0;
            for (String policyId : new HashSet<>(listeningPolicyIds)) {
                if (!indexed.contains(policyId)) {
                    log.warn("🔍 Periodic sync found a deleted auth policy: {}", policyId);
                    removePolicyListener(policyId);
                    authBindingManager.removePolicy(policyId);
                    removed++;
                }
            }

            int applied = 0;
            for (String policyId : policyIds) {
                if (!listeningPolicyIds.contains(policyId)) {
                    log.warn("🔍 Found missing policy during periodic sync: {}", policyId);
                    loadPolicyWithRoutes(policyId);
                    addPolicyListeners(policyId);
                    continue;
                }
                if (syncPolicyContent(policyId)) {
                    log.warn("🔍 Periodic sync found an auth policy change: {}", policyId);
                    applied++;
                }
            }

            if (applied > 0 || removed > 0) {
                log.info("📊 Periodic auth policy sync: {} applied, {} removed", applied, removed);
            }

        } catch (Exception e) {
            log.warn("Periodic auth policy sync failed - policy changes stay unapplied until it succeeds", e);
        }
    }

    /**
     * Re-read both data IDs of an already-listening policy and apply whichever changed.
     * A data ID that reads back empty is skipped rather than revoked: the index just told
     * us the policy exists, so keeping the last known good rules beats tearing them down
     * on an ambiguous read.
     */
    private boolean syncPolicyContent(String policyId) {
        boolean changed = false;

        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        String policyContent = configService.getConfig(policyDataId, GROUP);
        if (policyContent != null && !policyContent.isBlank()
                && appliedContent.recordIfChanged(policyDataId, policyContent)) {
            applyPolicyContent(policyId, policyContent);
            changed = true;
        }

        String routesDataId = AUTH_ROUTES_PREFIX + policyId;
        String routesContent = configService.getConfig(routesDataId, GROUP);
        if (routesContent != null && !routesContent.isBlank()
                && appliedContent.recordIfChanged(routesDataId, routesContent)) {
            applyRoutesContent(policyId, routesContent);
            changed = true;
        }

        return changed;
    }
}