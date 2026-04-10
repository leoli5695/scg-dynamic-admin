package com.leoli.gateway.refresher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.model.AuthConfig;
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

    private static final String GROUP = "DEFAULT_GROUP";
    private static final String AUTH_POLICIES_INDEX = "config.gateway.metadata.auth-policies-index";
    private static final String AUTH_POLICY_PREFIX = "config.gateway.auth-policy-";
    private static final String AUTH_ROUTES_PREFIX = "config.gateway.auth-routes-";

    private final AuthBindingManager authBindingManager;
    private final ConfigCenterService configService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Currently listening policy IDs
    private final Set<String> listeningPolicyIds = ConcurrentHashMap.newKeySet();

    // Policy listeners cache: policyId -> listener
    private final ConcurrentHashMap<String, ConfigCenterService.ConfigListener> policyListeners = new ConcurrentHashMap<>();
    // Routes listeners cache: policyId -> listener
    private final ConcurrentHashMap<String, ConfigCenterService.ConfigListener> routesListeners = new ConcurrentHashMap<>();

    private ConfigCenterService.ConfigListener indexListener;

    @Autowired
    public AuthPolicyRefresher(AuthBindingManager authBindingManager,
                               ConfigCenterService configService) {
        this.authBindingManager = authBindingManager;
        this.configService = configService;
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
            if (indexContent == null || indexContent.isEmpty()) {
                log.info("No auth policies index found");
                return;
            }

            List<String> policyIds = objectMapper.readValue(indexContent, new TypeReference<List<String>>() {
            });
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
        try {
            // Load policy config
            String policyDataId = AUTH_POLICY_PREFIX + policyId;
            String policyContent = configService.getConfig(policyDataId, GROUP);
            if (policyContent != null && !policyContent.isEmpty()) {
                AuthConfig config = objectMapper.readValue(policyContent, AuthConfig.class);
                authBindingManager.putPolicy(policyId, config);
                log.info("✅ Loaded auth policy: {} (type={})", policyId, config.getAuthType());
            }

            // Load policy routes
            String routesDataId = AUTH_ROUTES_PREFIX + policyId;
            String routesContent = configService.getConfig(routesDataId, GROUP);
            if (routesContent != null && !routesContent.isEmpty()) {
                List<String> routeIds = objectMapper.readValue(routesContent, new TypeReference<List<String>>() {
                });
                authBindingManager.setPolicyRoutes(policyId, routeIds);
                log.info("✅ Loaded routes for policy {}: {} routes", policyId, routeIds.size());
            }

        } catch (Exception e) {
            log.warn("Failed to load policy {}: {}", policyId, e.getMessage());
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
        };
        configService.addListener(policyDataId, GROUP, policyListener);
        policyListeners.put(policyId, policyListener);

        // Routes listener
        String routesDataId = AUTH_ROUTES_PREFIX + policyId;
        ConfigCenterService.ConfigListener routesListener = (dataId, group, content) -> {
            log.info("🔥 Auth routes changed for policy: {}", policyId);
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

        // Remove routes listener
        ConfigCenterService.ConfigListener routesListener = routesListeners.remove(policyId);
        if (routesListener != null) {
            String routesDataId = AUTH_ROUTES_PREFIX + policyId;
            configService.removeListener(routesDataId, GROUP, routesListener);
            log.debug("Removed routes listener: {}", policyId);
        }

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
     * Periodic fallback sync: check for missing policies every 1 minute.
     * This is a safety net in case index listener missed updates.
     */
    @Scheduled(fixedRate = 60000) // 1 minute
    public void periodicSyncMissingPolicies() {
        try {
            // Load current policies index from Nacos
            String indexContent = configService.getConfig(AUTH_POLICIES_INDEX, GROUP);
            if (indexContent == null || indexContent.isEmpty()) {
                return;
            }

            List<String> nacosPolicyIds = objectMapper.readValue(indexContent, new TypeReference<List<String>>() {
            });
            if (nacosPolicyIds.isEmpty()) {
                return;
            }

            // Get currently listening policy IDs
            Set<String> localPolicyIds = new HashSet<>(listeningPolicyIds);

            // Find missing policies (in Nacos but not in local cache)
            int syncedCount = 0;
            for (String policyId : nacosPolicyIds) {
                if (!localPolicyIds.contains(policyId)) {
                    log.warn("🔍 Found missing policy during periodic sync: {}", policyId);

                    // Try to load the missing policy
                    loadPolicyWithRoutes(policyId);
                    addPolicyListeners(policyId);
                    syncedCount++;
                    log.info("✅ Periodic sync recovered missing policy: {}", policyId);
                }
            }

            if (syncedCount > 0) {
                log.info("📊 Periodic sync completed: recovered {} missing policies", syncedCount);
            }

        } catch (Exception e) {
            log.debug("Periodic sync check completed (no action needed)");
        }
    }
}