package com.leoli.gateway.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.auth.JwtValidationCache;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.leoli.gateway.constants.GatewayConfigConstants.*;

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

    // Policy cache: policyId -> AuthConfig
    private final Map<String, AuthConfig> policyCache = new ConcurrentHashMap<>();

    // Policy routes cache: policyId -> Set<routeId>
    private final Map<String, Set<String>> policyRoutesCache = new ConcurrentHashMap<>();

    // Index by auth type for quick lookup
    private final Map<String, List<String>> policiesByType = new ConcurrentHashMap<>();

    // Reverse index: routeId -> Set<policyId> (for route-triggered auth)
    private final Map<String, Set<String>> routePoliciesCache = new ConcurrentHashMap<>();

    // Credential indices for O(1) lookup
    private final Map<String, String> apiKeyIndex = new ConcurrentHashMap<>();        // apiKey -> policyId
    private final Map<String, String> basicAuthIndex = new ConcurrentHashMap<>();     // username -> policyId
    private final Map<String, String> accessKeyIndex = new ConcurrentHashMap<>();     // accessKey -> policyId
    private final Map<String, String> clientIdIndex = new ConcurrentHashMap<>();      // clientId -> policyId

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private JwtValidationCache jwtValidationCache;

    private final ObjectMapper objectMapper;

    @Autowired
    public AuthBindingManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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

        // Remove old credential indices if updating
        AuthConfig oldConfig = policyCache.get(policyId);
        if (oldConfig != null) {
            removeCredentialIndices(oldConfig);
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

        // Build credential indices for O(1) lookup
        buildCredentialIndices(policyId, config);

        log.debug("Auth policy cached: {} (type={})", policyId, config.getAuthType());
    }

    /**
     * Build credential indices for a policy.
     */
    private void buildCredentialIndices(String policyId, AuthConfig config) {
        if (config == null) return;

        String authType = config.getAuthType();
        if (authType == null) return;

        switch (authType) {
            case "API_KEY":
                if (config.getApiKey() != null) {
                    apiKeyIndex.put(config.getApiKey(), policyId);
                    log.debug("API Key index added: {} -> {}", config.getApiKey(), policyId);
                }
                break;
            case "BASIC":
                if (config.getBasicUsername() != null) {
                    basicAuthIndex.put(config.getBasicUsername(), policyId);
                    log.debug("Basic Auth index added: {} -> {}", config.getBasicUsername(), policyId);
                }
                break;
            case "HMAC":
                if (config.getAccessKey() != null) {
                    accessKeyIndex.put(config.getAccessKey(), policyId);
                    log.debug("Access Key index added: {} -> {}", config.getAccessKey(), policyId);
                }
                // Also index accessKeySecrets keys
                if (config.getAccessKeySecrets() != null) {
                    for (String key : config.getAccessKeySecrets().keySet()) {
                        accessKeyIndex.put(key, policyId);
                        log.debug("Access Key (secrets) index added: {} -> {}", key, policyId);
                    }
                }
                break;
            case "OAUTH2":
                if (config.getClientId() != null) {
                    clientIdIndex.put(config.getClientId(), policyId);
                    log.debug("Client ID index added: {} -> {}", config.getClientId(), policyId);
                }
                break;
        }
    }

    /**
     * Remove credential indices for a policy.
     */
    private void removeCredentialIndices(AuthConfig config) {
        if (config == null) return;

        String authType = config.getAuthType();
        if (authType == null) return;

        switch (authType) {
            case "API_KEY":
                if (config.getApiKey() != null) {
                    apiKeyIndex.remove(config.getApiKey());
                }
                break;
            case "BASIC":
                if (config.getBasicUsername() != null) {
                    basicAuthIndex.remove(config.getBasicUsername());
                }
                break;
            case "HMAC":
                if (config.getAccessKey() != null) {
                    accessKeyIndex.remove(config.getAccessKey());
                }
                if (config.getAccessKeySecrets() != null) {
                    for (String key : config.getAccessKeySecrets().keySet()) {
                        accessKeyIndex.remove(key);
                    }
                }
                break;
            case "OAUTH2":
                if (config.getClientId() != null) {
                    clientIdIndex.remove(config.getClientId());
                }
                break;
        }
    }

    /**
     * Remove a policy.
     */
    public void removePolicy(String policyId) {
        AuthConfig config = policyCache.remove(policyId);

        // Remove credential indices
        if (config != null) {
            removeCredentialIndices(config);
        }

        // Invalidate JWT cache for this policy
        jwtValidationCache.invalidatePolicy(policyId);

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
    // Authentication Logic - Find Policy by Credentials (O(1) lookup)
    // ============================================================

    /**
     * Find matching policy by Basic Auth credentials.
     * Uses credential index for O(1) lookup.
     */
    public String findPolicyByBasicAuth(String username, String password) {
        if (username == null || password == null) {
            return null;
        }

        // O(1) lookup via index
        String policyId = basicAuthIndex.get(username);
        if (policyId == null) {
            return null;
        }

        // Verify password matches
        AuthConfig config = policyCache.get(policyId);
        if (config != null && config.isEnabled() && password.equals(config.getBasicPassword())) {
            return policyId;
        }

        return null;
    }

    /**
     * Find matching policy by API Key.
     * Uses credential index for O(1) lookup.
     */
    public String findPolicyByApiKey(String apiKey) {
        if (apiKey == null) {
            return null;
        }

        // O(1) lookup via index
        String policyId = apiKeyIndex.get(apiKey);
        if (policyId != null) {
            AuthConfig config = policyCache.get(policyId);
            if (config != null && config.isEnabled()) {
                return policyId;
            }
        }

        // Handle API Key with prefix
        for (Map.Entry<String, String> entry : apiKeyIndex.entrySet()) {
            String storedKey = entry.getKey();
            String pid = entry.getValue();
            AuthConfig config = policyCache.get(pid);

            if (config != null && config.isEnabled()) {
                String prefix = config.getApiKeyPrefix();
                if (prefix != null && !prefix.isEmpty()) {
                    if (apiKey.startsWith(prefix)) {
                        String keyWithoutPrefix = apiKey.substring(prefix.length());
                        if (keyWithoutPrefix.equals(storedKey)) {
                            return pid;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Find matching policy by HMAC Access Key.
     * Uses credential index for O(1) lookup.
     */
    public String findPolicyByAccessKey(String accessKey) {
        if (accessKey == null) {
            return null;
        }

        // O(1) lookup via index
        String policyId = accessKeyIndex.get(accessKey);
        if (policyId != null) {
            AuthConfig config = policyCache.get(policyId);
            if (config != null && config.isEnabled()) {
                return policyId;
            }
        }

        return null;
    }

    /**
     * Find matching policy by OAuth2 Client ID.
     * Uses credential index for O(1) lookup.
     */
    public String findPolicyByClientId(String clientId) {
        if (clientId == null) {
            return null;
        }

        // O(1) lookup via index
        String policyId = clientIdIndex.get(clientId);
        if (policyId != null) {
            AuthConfig config = policyCache.get(policyId);
            if (config != null && config.isEnabled()) {
                return policyId;
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
            String configJson = configCenterService.getConfig(dataId, GROUP);
            if (configJson != null && !configJson.isEmpty()) {
                AuthConfig config = objectMapper.readValue(configJson, AuthConfig.class);
                if (config != null) {
                    // Ensure policyId is set
                    if (config.getPolicyId() == null || config.getPolicyId().isEmpty()) {
                        config.setPolicyId(policyId);
                    }
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
            String routesJson = configCenterService.getConfig(dataId, GROUP);
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
        // Clear credential indices
        apiKeyIndex.clear();
        basicAuthIndex.clear();
        accessKeyIndex.clear();
        clientIdIndex.clear();
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
        // Credential index stats
        stats.put("apiKeyIndexSize", apiKeyIndex.size());
        stats.put("basicAuthIndexSize", basicAuthIndex.size());
        stats.put("accessKeyIndexSize", accessKeyIndex.size());
        stats.put("clientIdIndexSize", clientIdIndex.size());
        return stats;
    }
}