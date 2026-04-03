package com.leoli.gateway.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication Binding Manager.
 * Manages auth policies and their route bindings.
 *
 * Data Structure:
 * - Policy Config: config.gateway.auth-policy-{policyId} -> AuthConfig JSON
 * - Policy Routes: config.gateway.auth-routes-{policyId} -> ["routeId1", "routeId2", ...]
 *
 * Authentication Flow:
 * 1. Extract credentials from request (username/password, apiKey, etc.)
 * 2. Find matching policy by credentials
 * 3. Check if routeId is in the policy's bound routes list
 *
 * @author leoli
 */
@Slf4j
@Component
public class AuthBindingManager {

    private static final String AUTH_POLICY_PREFIX = "config.gateway.auth-policy-";
    private static final String AUTH_ROUTES_PREFIX = "config.gateway.auth-routes-";
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    // Policy cache: policyId -> AuthConfig
    private final Map<String, AuthConfig> policyCache = new ConcurrentHashMap<>();

    // Policy routes cache: policyId -> Set<routeId>
    private final Map<String, Set<String>> policyRoutesCache = new ConcurrentHashMap<>();

    // Index by auth type for quick lookup
    private final Map<String, List<String>> policiesByType = new ConcurrentHashMap<>();

    // Reverse index: routeId -> Set<policyId> (for route-triggered auth)
    private final Map<String, Set<String>> routePoliciesCache = new ConcurrentHashMap<>();

    @Autowired
    private ConfigCenterService configCenterService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        log.info("AuthBindingManager initialized");
    }

    // ============================================================
    // Policy Management
    // ============================================================

    /**
     * Add or update a policy.
     */
    public void putPolicy(String policyId, AuthConfig config) {
        if (policyId == null || config == null) {
            return;
        }
        policyCache.put(policyId, config);

        // Index by auth type
        String authType = config.getAuthType();
        if (authType != null) {
            policiesByType.computeIfAbsent(authType, k -> new ArrayList<>());
            List<String> policies = policiesByType.get(authType);
            if (!policies.contains(policyId)) {
                policies.add(policyId);
            }
        }

        log.debug("Auth policy cached: {} (type={})", policyId, config.getAuthType());
    }

    /**
     * Remove a policy.
     */
    public void removePolicy(String policyId) {
        AuthConfig config = policyCache.remove(policyId);

        // Remove from route policies reverse index
        Set<String> routes = policyRoutesCache.get(policyId);
        if (routes != null) {
            for (String routeId : routes) {
                Set<String> policies = routePoliciesCache.get(routeId);
                if (policies != null) {
                    policies.remove(policyId);
                    if (policies.isEmpty()) {
                        routePoliciesCache.remove(routeId);
                    }
                }
            }
        }
        policyRoutesCache.remove(policyId);

        // Remove from type index
        if (config != null && config.getAuthType() != null) {
            List<String> policies = policiesByType.get(config.getAuthType());
            if (policies != null) {
                policies.remove(policyId);
            }
        }

        log.debug("Auth policy removed: {}", policyId);
    }

    /**
     * Get a policy by ID.
     */
    public AuthConfig getPolicy(String policyId) {
        return policyCache.get(policyId);
    }

    /**
     * Get all policies of a specific type.
     */
    public List<String> getPoliciesByType(String authType) {
        return policiesByType.getOrDefault(authType, Collections.emptyList());
    }

    /**
     * Get all policies of a specific AuthType enum.
     */
    public List<String> getPoliciesByType(AuthType authType) {
        if (authType == null) {
            return Collections.emptyList();
        }
        return getPoliciesByType(authType.getCode());
    }

    /**
     * Get all policies.
     */
    public Map<String, AuthConfig> getAllPolicies() {
        return new HashMap<>(policyCache);
    }

    // ============================================================
    // Policy Routes Management
    // ============================================================

    /**
     * Set routes for a policy.
     */
    public void setPolicyRoutes(String policyId, List<String> routeIds) {
        if (policyId == null) {
            return;
        }

        // Remove old route associations from reverse index
        Set<String> oldRoutes = policyRoutesCache.get(policyId);
        if (oldRoutes != null) {
            for (String routeId : oldRoutes) {
                Set<String> policies = routePoliciesCache.get(routeId);
                if (policies != null) {
                    policies.remove(policyId);
                    if (policies.isEmpty()) {
                        routePoliciesCache.remove(routeId);
                    }
                }
            }
        }

        if (routeIds == null || routeIds.isEmpty()) {
            policyRoutesCache.remove(policyId);
            log.debug("Policy routes removed for policy: {}", policyId);
        } else {
            policyRoutesCache.put(policyId, new HashSet<>(routeIds));

            // Update reverse index: routeId -> policyId
            for (String routeId : routeIds) {
                routePoliciesCache.computeIfAbsent(routeId, k -> new HashSet<>()).add(policyId);
            }
            log.debug("Policy routes updated for policy {}: {} routes", policyId, routeIds.size());
        }
    }

    /**
     * Get routes for a policy.
     */
    public Set<String> getPolicyRoutes(String policyId) {
        return policyRoutesCache.getOrDefault(policyId, Collections.emptySet());
    }

    /**
     * Check if a route is bound to a policy.
     */
    public boolean isRouteBound(String policyId, String routeId) {
        Set<String> routes = policyRoutesCache.get(policyId);
        return routes != null && routes.contains(routeId);
    }

    /**
     * Get policies bound to a route (reverse lookup).
     */
    public Set<String> getPoliciesForRoute(String routeId) {
        return routePoliciesCache.getOrDefault(routeId, Collections.emptySet());
    }

    /**
     * Check if a route requires authentication.
     */
    public boolean requiresAuth(String routeId) {
        Set<String> policies = routePoliciesCache.get(routeId);
        if (policies == null || policies.isEmpty()) {
            return false;
        }
        // Check if any enabled policy is bound to this route
        for (String policyId : policies) {
            AuthConfig config = policyCache.get(policyId);
            if (config != null && config.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // Authentication Logic - Find Policy by Credentials
    // ============================================================

    /**
     * Find matching policy by Basic Auth credentials.
     */
    public String findPolicyByBasicAuth(String username, String password) {
        List<String> basicPolicies = getPoliciesByType(AuthType.BASIC);
        for (String policyId : basicPolicies) {
            AuthConfig config = policyCache.get(policyId);
            if (config != null && config.isEnabled()) {
                if (username != null && username.equals(config.getBasicUsername()) &&
                    password != null && password.equals(config.getBasicPassword())) {
                    return policyId;
                }
            }
        }
        return null;
    }

    /**
     * Find matching policy by API Key.
     */
    public String findPolicyByApiKey(String apiKey) {
        List<String> apiKeyPolicies = getPoliciesByType(AuthType.API_KEY);
        for (String policyId : apiKeyPolicies) {
            AuthConfig config = policyCache.get(policyId);
            if (config != null && config.isEnabled()) {
                String validKey = config.getApiKey();
                String prefix = config.getApiKeyPrefix();

                // Check with prefix
                if (prefix != null && !prefix.isEmpty()) {
                    if (apiKey != null && apiKey.startsWith(prefix)) {
                        String keyWithoutPrefix = apiKey.substring(prefix.length());
                        if (keyWithoutPrefix.equals(validKey)) {
                            return policyId;
                        }
                    }
                } else if (apiKey != null && apiKey.equals(validKey)) {
                    return policyId;
                }
            }
        }
        return null;
    }

    /**
     * Find matching policy by HMAC Access Key.
     */
    public String findPolicyByAccessKey(String accessKey) {
        List<String> hmacPolicies = getPoliciesByType(AuthType.HMAC);
        for (String policyId : hmacPolicies) {
            AuthConfig config = policyCache.get(policyId);
            if (config != null && config.isEnabled()) {
                if (accessKey != null && accessKey.equals(config.getAccessKey())) {
                    return policyId;
                }
                // Also check in accessKeySecrets map
                Map<String, String> secrets = config.getAccessKeySecrets();
                if (secrets != null && secrets.containsKey(accessKey)) {
                    return policyId;
                }
            }
        }
        return null;
    }

    /**
     * Find matching policy by OAuth2 Client ID.
     */
    public String findPolicyByClientId(String clientId) {
        List<String> oauth2Policies = getPoliciesByType(AuthType.OAUTH2);
        for (String policyId : oauth2Policies) {
            AuthConfig config = policyCache.get(policyId);
            if (config != null && config.isEnabled()) {
                if (clientId != null && clientId.equals(config.getClientId())) {
                    return policyId;
                }
            }
        }
        return null;
    }

    /**
     * Get AuthConfig for a policy.
     */
    public AuthConfig getAuthConfig(String policyId) {
        return policyCache.get(policyId);
    }

    /**
     * Check if route is authorized for a policy.
     */
    public boolean isRouteAuthorized(String policyId, String routeId) {
        Set<String> routes = policyRoutesCache.get(policyId);
        if (routes == null) {
            // Try to load from Nacos
            loadPolicyRoutesFromNacos(policyId);
            routes = policyRoutesCache.get(policyId);
        }
        return routes != null && routes.contains(routeId);
    }

    // ============================================================
    // Nacos Config Loading
    // ============================================================

    /**
     * Load policy from Nacos.
     */
    public void loadPolicyFromNacos(String policyId) {
        String dataId = AUTH_POLICY_PREFIX + policyId;
        try {
            String configJson = configCenterService.getConfig(dataId, DEFAULT_GROUP);
            if (configJson != null && !configJson.isEmpty()) {
                AuthConfig config = objectMapper.readValue(configJson, AuthConfig.class);
                if (config != null) {
                    putPolicy(policyId, config);
                    log.info("Loaded auth policy from Nacos: {}", policyId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load auth policy from Nacos: {} - {}", dataId, e.getMessage());
        }
    }

    /**
     * Load policy routes from Nacos.
     */
    public void loadPolicyRoutesFromNacos(String policyId) {
        String dataId = AUTH_ROUTES_PREFIX + policyId;
        try {
            String routesJson = configCenterService.getConfig(dataId, DEFAULT_GROUP);
            if (routesJson != null && !routesJson.isEmpty()) {
                List<String> routeIds = objectMapper.readValue(routesJson, new TypeReference<List<String>>() {});
                setPolicyRoutes(policyId, routeIds);
                log.info("Loaded policy routes from Nacos: {} -> {} routes", policyId, routeIds.size());
            }
        } catch (Exception e) {
            log.debug("No policy routes found in Nacos for policy: {}", policyId);
        }
    }

    /**
     * Load policy and its routes from Nacos.
     */
    public void loadPolicyWithRoutes(String policyId) {
        loadPolicyFromNacos(policyId);
        loadPolicyRoutesFromNacos(policyId);
    }

    // ============================================================
    // Cache Management
    // ============================================================

    /**
     * Clear all caches.
     */
    public void clear() {
        policyCache.clear();
        policyRoutesCache.clear();
        policiesByType.clear();
        routePoliciesCache.clear();
        log.info("Auth binding cache cleared");
    }

    /**
     * Get cache statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("policyCount", policyCache.size());
        stats.put("policyRoutesCount", policyRoutesCache.size());
        stats.put("routePoliciesCount", routePoliciesCache.size());
        stats.put("policiesByType", new HashMap<>(policiesByType));
        return stats;
    }
}