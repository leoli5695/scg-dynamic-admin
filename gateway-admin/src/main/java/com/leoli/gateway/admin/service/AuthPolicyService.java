package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.enums.AuthType;
import com.leoli.gateway.admin.model.AuthPolicyDefinition;
import com.leoli.gateway.admin.model.AuthPolicyEntity;
import com.leoli.gateway.admin.model.RouteAuthBindingEntity;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.repository.AuthPolicyRepository;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteAuthBindingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Authentication Policy Service.
 * Manages auth policies and route bindings with Nacos synchronization.
 *
 * Nacos Config Structure:
 * - config.gateway.metadata.auth-policies-index -> ["policyId1", "policyId2", ...]
 * - config.gateway.auth-policy-{policyId} -> AuthPolicyDefinition JSON
 * - config.gateway.auth-routes-{policyId} -> ["routeId1", "routeId2", ...]
 *
 * @author leoli
 */
@Slf4j
@Service
public class AuthPolicyService {

    private static final String AUTH_POLICIES_INDEX = "config.gateway.metadata.auth-policies-index";
    private static final String AUTH_POLICY_PREFIX = "config.gateway.auth-policy-";
    // 存储每个凭证绑定的路由列表: policyId -> [routeId1, routeId2, ...]
    private static final String AUTH_ROUTES_PREFIX = "config.gateway.auth-routes-";

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private AuthPolicyRepository authPolicyRepository;

    @Autowired
    private RouteAuthBindingRepository routeAuthBindingRepository;

    @Autowired
    private GatewayInstanceRepository gatewayInstanceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        loadPoliciesFromDatabase();
        log.info("AuthPolicyService initialized");
    }

    /**
     * Get Nacos namespace from instance ID.
     * Returns null for default namespace if instance not found.
     */
    private String getNacosNamespace(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return null; // Use default namespace
        }
        Optional<GatewayInstanceEntity> instance = gatewayInstanceRepository.findByInstanceId(instanceId);
        return instance.map(GatewayInstanceEntity::getNacosNamespace).orElse(null);
    }

    /**
     * Load policies from database and sync enabled ones to Nacos.
     * Note: This method loads all policies across all instances during startup.
     * Instance-specific loading should be done via loadPoliciesForInstance(String instanceId).
     */
    private void loadPoliciesFromDatabase() {
        try {
            List<AuthPolicyEntity> entities = authPolicyRepository.findAll();
            if (entities != null && !entities.isEmpty()) {
                long enabledCount = entities.stream().filter(AuthPolicyEntity::getEnabled).count();
                log.info("Loaded {} auth policies from database ({} enabled)", entities.size(), enabledCount);

                // Group by instanceId and sync each instance's policies
                Map<String, List<AuthPolicyEntity>> byInstance = entities.stream()
                        .filter(AuthPolicyEntity::getEnabled)
                        .collect(Collectors.groupingBy(e -> e.getInstanceId() != null ? e.getInstanceId() : ""));

                for (Map.Entry<String, List<AuthPolicyEntity>> entry : byInstance.entrySet()) {
                    String instanceId = entry.getKey();
                    syncPoliciesForInstance(instanceId, entry.getValue());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load auth policies from database: {}", e.getMessage());
        }
    }

    /**
     * Load and sync policies for a specific instance.
     */
    public void loadPoliciesForInstance(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            log.warn("Cannot load policies for null or empty instanceId");
            return;
        }

        List<AuthPolicyEntity> entities = authPolicyRepository.findByInstanceId(instanceId);
        syncPoliciesForInstance(instanceId, entities);
        log.info("Loaded {} policies for instance: {}", entities.size(), instanceId);
    }

    /**
     * Sync policies for a specific instance to Nacos.
     */
    private void syncPoliciesForInstance(String instanceId, List<AuthPolicyEntity> entities) {
        String namespace = getNacosNamespace(instanceId);

        // Recover enabled policies in Nacos
        for (AuthPolicyEntity entity : entities) {
            if (!entity.getEnabled()) {
                continue;
            }

            String policyDataId = AUTH_POLICY_PREFIX + entity.getPolicyId();
            if (!configCenterService.configExists(policyDataId, namespace)) {
                AuthPolicyDefinition policy = toDefinition(entity);
                configCenterService.publishConfig(policyDataId, namespace, policy);
                log.info("Recovered missing auth policy in Nacos: {} (instance: {})", policyDataId, instanceId);
            }
        }

        // Recover route bindings in Nacos (policyId -> [routeIds])
        List<RouteAuthBindingEntity> bindings = routeAuthBindingRepository.findByInstanceId(instanceId);
        Map<String, List<String>> policyRoutes = new HashMap<>();
        for (RouteAuthBindingEntity binding : bindings) {
            if (binding.getEnabled()) {
                policyRoutes.computeIfAbsent(binding.getPolicyId(), k -> new ArrayList<>())
                        .add(binding.getRouteId());
            }
        }
        for (Map.Entry<String, List<String>> entry : policyRoutes.entrySet()) {
            publishPolicyRoutesToNacos(instanceId, entry.getKey(), entry.getValue());
        }

        // Publish policies index for this instance
        publishPoliciesIndex(instanceId);
    }

    // ==================== Policy CRUD ====================

    /**
     * Get all policies (across all instances).
     */
    public List<AuthPolicyDefinition> getAllPolicies() {
        List<AuthPolicyEntity> entities = authPolicyRepository.findAll();
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get all policies for a specific instance.
     */
    public List<AuthPolicyDefinition> getAllPolicies(String instanceId) {
        List<AuthPolicyEntity> entities = authPolicyRepository.findByInstanceId(instanceId);
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get policy by ID (across all instances).
     */
    public AuthPolicyDefinition getPolicy(String policyId) {
        Optional<AuthPolicyEntity> entity = authPolicyRepository.findByPolicyId(policyId);
        return entity.map(this::toDefinition).orElse(null);
    }

    /**
     * Get policy by ID and instanceId.
     */
    public AuthPolicyDefinition getPolicy(String instanceId, String policyId) {
        Optional<AuthPolicyEntity> entity = authPolicyRepository.findByPolicyIdAndInstanceId(policyId, instanceId);
        return entity.map(this::toDefinition).orElse(null);
    }

    /**
     * Get policies by auth type (across all instances).
     */
    public List<AuthPolicyDefinition> getPoliciesByType(String authType) {
        AuthType type = AuthType.fromCode(authType);
        List<AuthPolicyEntity> entities = authPolicyRepository.findByAuthType(type);
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get policies by auth type for a specific instance.
     */
    public List<AuthPolicyDefinition> getPoliciesByType(String instanceId, String authType) {
        AuthType type = AuthType.fromCode(authType);
        List<AuthPolicyEntity> entities = authPolicyRepository.findByInstanceIdAndAuthType(instanceId, type);
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get policies by auth type enum.
     */
    public List<AuthPolicyDefinition> getPoliciesByType(AuthType authType) {
        List<AuthPolicyEntity> entities = authPolicyRepository.findByAuthType(authType);
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get policies by auth type enum for a specific instance.
     */
    public List<AuthPolicyDefinition> getPoliciesByType(String instanceId, AuthType authType) {
        List<AuthPolicyEntity> entities = authPolicyRepository.findByInstanceIdAndAuthType(instanceId, authType);
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Create a new policy (without instanceId - deprecated, use createPolicy with instanceId).
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthPolicyEntity createPolicy(AuthPolicyDefinition policy) {
        return createPolicy(null, policy);
    }

    /**
     * Create a new policy for a specific instance.
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthPolicyEntity createPolicy(String instanceId, AuthPolicyDefinition policy) {
        if (policy == null || policy.getPolicyName() == null) {
            throw new IllegalArgumentException("Invalid policy definition");
        }

        // Validate auth type
        validateAuthType(policy.getAuthType());

        // Check if name already exists (within same instance if instanceId provided)
        if (instanceId != null && !instanceId.isEmpty()) {
            if (authPolicyRepository.existsByPolicyNameAndInstanceId(policy.getPolicyName(), instanceId)) {
                throw new IllegalArgumentException("Policy name already exists in this instance: " + policy.getPolicyName());
            }
        } else {
            if (authPolicyRepository.existsByPolicyName(policy.getPolicyName())) {
                throw new IllegalArgumentException("Policy name already exists: " + policy.getPolicyName());
            }
        }

        // Generate UUID
        String policyId = UUID.randomUUID().toString();
        policy.setPolicyId(policyId);

        // Save to database
        AuthPolicyEntity entity = toEntity(policy);
        entity.setPolicyId(policyId);
        entity.setInstanceId(instanceId);
        entity = authPolicyRepository.save(entity);
        log.info("Auth policy saved to database: {} (ID: {}, Instance: {})", policy.getPolicyName(), policyId, instanceId);

        // Publish to Nacos if enabled
        if (policy.isEnabled()) {
            String policyDataId = AUTH_POLICY_PREFIX + policyId;
            String namespace = getNacosNamespace(instanceId);
            configCenterService.publishConfig(policyDataId, namespace, policy);
            log.info("Auth policy published to Nacos: {} (namespace: {})", policyDataId, namespace);

            // Publish empty routes list
            publishPolicyRoutesToNacos(instanceId, policyId, new ArrayList<>());
        }

        // Update policies index
        publishPoliciesIndex(instanceId);

        return entity;
    }

    /**
     * Update an existing policy.
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthPolicyEntity updatePolicy(String policyId, AuthPolicyDefinition policy) {
        if (policy == null || policyId == null) {
            throw new IllegalArgumentException("Invalid policy definition or ID");
        }

        // Validate auth type
        validateAuthType(policy.getAuthType());

        Optional<AuthPolicyEntity> optEntity = authPolicyRepository.findByPolicyId(policyId);
        if (optEntity.isEmpty()) {
            throw new IllegalArgumentException("Policy not found: " + policyId);
        }

        AuthPolicyEntity entity = optEntity.get();

        // Check name uniqueness if changed
        if (!entity.getPolicyName().equals(policy.getPolicyName())) {
            if (authPolicyRepository.existsByPolicyName(policy.getPolicyName())) {
                throw new IllegalArgumentException("Policy name already exists: " + policy.getPolicyName());
            }
        }

        policy.setPolicyId(policyId);

        // Update entity
        entity.setPolicyName(policy.getPolicyName());
        entity.setAuthType(policy.getAuthTypeEnum());
        entity.setEnabled(policy.isEnabled());
        entity.setDescription(policy.getDescription());

        // Store config as JSON
        try {
            String configJson = objectMapper.writeValueAsString(policy);
            entity.setConfig(configJson);
            log.info("Policy input apiKey: {}, config JSON stored: {}", policy.getApiKey(), configJson);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize policy config", e);
        }

        entity = authPolicyRepository.save(entity);
        log.info("Auth policy updated in database: {}", policy.getPolicyName());
        log.info("entity.getConfig() = {}", entity.getConfig());

        // Update Nacos with correct namespace - use complete config from entity
        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        String namespace = getNacosNamespace(entity.getInstanceId());
        AuthPolicyDefinition completePolicy = toDefinition(entity); // Get complete config from database
        log.info("Complete policy for Nacos - apiKey: {}, apiKeyHeader: {}", completePolicy.getApiKey(), completePolicy.getApiKeyHeader());

        // Use entity.getConfig() directly to ensure consistency
        if (completePolicy.isEnabled() && entity.getConfig() != null) {
            // Publish the stored config JSON directly to avoid re-serialization issues
            boolean result = publishRawConfig(policyDataId, namespace, entity.getConfig());
            log.info("Auth policy published to Nacos: {} (namespace: {}, result: {})", policyDataId, namespace, result);
        } else if (!completePolicy.isEnabled()) {
            configCenterService.removeConfig(policyDataId, namespace);
            log.info("Disabled policy removed from Nacos: {} (namespace: {})", policyDataId, namespace);
        }

        // Update policies index
        publishPoliciesIndex(entity.getInstanceId());

        return entity;
    }

    /**
     * Delete a policy.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePolicy(String policyId) {
        Optional<AuthPolicyEntity> optEntity = authPolicyRepository.findByPolicyId(policyId);
        if (optEntity.isEmpty()) {
            throw new IllegalArgumentException("Policy not found: " + policyId);
        }

        AuthPolicyEntity entity = optEntity.get();
        log.info("Deleting auth policy: {} (ID: {})", entity.getPolicyName(), policyId);

        // Remove policy config from Nacos
        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        configCenterService.removeConfig(policyDataId);
        log.info("Auth policy removed from Nacos: {}", policyDataId);

        // Remove routes list from Nacos
        String routesDataId = AUTH_ROUTES_PREFIX + policyId;
        configCenterService.removeConfig(routesDataId);
        log.info("Auth routes removed from Nacos: {}", routesDataId);

        // Delete all bindings for this policy
        routeAuthBindingRepository.deleteByPolicyId(policyId);

        // Delete from database
        authPolicyRepository.delete(entity);
        log.info("Auth policy deleted from database: {}", policyId);

        // Update policies index
        publishPoliciesIndex(entity.getInstanceId());
    }

    /**
     * Enable a policy.
     */
    @Transactional(rollbackFor = Exception.class)
    public void enablePolicy(String policyId) {
        Optional<AuthPolicyEntity> optEntity = authPolicyRepository.findByPolicyId(policyId);
        if (optEntity.isEmpty()) {
            throw new IllegalArgumentException("Policy not found: " + policyId);
        }

        AuthPolicyEntity entity = optEntity.get();
        if (entity.getEnabled()) {
            return;
        }

        String instanceId = entity.getInstanceId();
        entity.setEnabled(true);
        authPolicyRepository.save(entity);

        // Publish to Nacos
        AuthPolicyDefinition policy = toDefinition(entity);
        policy.setEnabled(true);
        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        String namespace = getNacosNamespace(instanceId);
        configCenterService.publishConfig(policyDataId, namespace, policy);
        log.info("Auth policy enabled: {} (namespace: {})", policyId, namespace);

        // Update routes list
        updatePolicyRoutesInNacos(instanceId, policyId);

        // Update policies index
        publishPoliciesIndex(instanceId);
    }

    /**
     * Disable a policy.
     */
    @Transactional(rollbackFor = Exception.class)
    public void disablePolicy(String policyId) {
        Optional<AuthPolicyEntity> optEntity = authPolicyRepository.findByPolicyId(policyId);
        if (optEntity.isEmpty()) {
            throw new IllegalArgumentException("Policy not found: " + policyId);
        }

        AuthPolicyEntity entity = optEntity.get();
        if (!entity.getEnabled()) {
            return;
        }

        entity.setEnabled(false);
        authPolicyRepository.save(entity);

        String instanceId = entity.getInstanceId();
        String namespace = getNacosNamespace(instanceId);

        // Remove from Nacos
        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        configCenterService.removeConfig(policyDataId, namespace);
        log.info("Auth policy disabled and removed from Nacos: {} (namespace: {})", policyId, namespace);

        // Remove routes list from Nacos
        String routesDataId = AUTH_ROUTES_PREFIX + policyId;
        configCenterService.removeConfig(routesDataId, namespace);
        log.info("Auth routes removed from Nacos: {} (namespace: {})", routesDataId, namespace);

        // Update policies index
        publishPoliciesIndex(instanceId);
    }

    // ==================== Route Binding Management ====================

    /**
     * Get all bindings.
     */
    public List<RouteAuthBindingEntity> getAllBindings() {
        return routeAuthBindingRepository.findAll();
    }

    /**
     * Get bindings for a policy.
     */
    public List<RouteAuthBindingEntity> getBindingsForPolicy(String policyId) {
        return routeAuthBindingRepository.findByPolicyId(policyId);
    }

    /**
     * Get bindings for a route.
     */
    public List<RouteAuthBindingEntity> getBindingsForRoute(String routeId) {
        return routeAuthBindingRepository.findByRouteIdAndEnabledTrueOrderByPriorityDesc(routeId);
    }

    /**
     * Get policies for a route.
     */
    public List<AuthPolicyDefinition> getPoliciesForRoute(String routeId) {
        List<RouteAuthBindingEntity> bindings = routeAuthBindingRepository.findByRouteIdAndEnabledTrueOrderByPriorityDesc(routeId);
        List<AuthPolicyDefinition> policies = new ArrayList<>();
        for (RouteAuthBindingEntity binding : bindings) {
            AuthPolicyDefinition policy = getPolicy(binding.getPolicyId());
            if (policy != null && policy.isEnabled()) {
                policies.add(policy);
            }
        }
        return policies;
    }

    /**
     * Bind a policy to a route.
     */
    @Transactional(rollbackFor = Exception.class)
    public RouteAuthBindingEntity bindPolicyToRoute(String policyId, String routeId, Integer priority) {
        // Verify policy exists and get instanceId
        Optional<AuthPolicyEntity> policyOpt = authPolicyRepository.findByPolicyId(policyId);
        if (policyOpt.isEmpty()) {
            throw new IllegalArgumentException("Policy not found: " + policyId);
        }
        String instanceId = policyOpt.get().getInstanceId();

        // Check if binding already exists
        Optional<RouteAuthBindingEntity> existing = routeAuthBindingRepository.findByPolicyIdAndRouteId(policyId, routeId);
        if (existing.isPresent()) {
            // Update existing binding
            RouteAuthBindingEntity binding = existing.get();
            binding.setEnabled(true);
            if (priority != null) {
                binding.setPriority(priority);
            }
            binding.setInstanceId(instanceId);
            binding = routeAuthBindingRepository.save(binding);
            updatePolicyRoutesInNacos(instanceId, policyId);
            return binding;
        }

        // Create new binding
        RouteAuthBindingEntity binding = new RouteAuthBindingEntity();
        binding.setBindingId(UUID.randomUUID().toString());
        binding.setPolicyId(policyId);
        binding.setRouteId(routeId);
        binding.setPriority(priority != null ? priority : 100);
        binding.setEnabled(true);
        binding.setInstanceId(instanceId);

        binding = routeAuthBindingRepository.save(binding);
        log.info("Created binding: policy {} -> route {} (priority {})", policyId, routeId, priority);

        // Update routes list for this policy in Nacos
        updatePolicyRoutesInNacos(instanceId, policyId);

        return binding;
    }

    /**
     * Unbind a policy from a route.
     */
    @Transactional(rollbackFor = Exception.class)
    public void unbindPolicyFromRoute(String policyId, String routeId) {
        routeAuthBindingRepository.deleteByPolicyIdAndRouteId(policyId, routeId);
        log.info("Deleted binding: policy {} -> route {}", policyId, routeId);

        // Get instanceId from policy
        Optional<AuthPolicyEntity> policyOpt = authPolicyRepository.findByPolicyId(policyId);
        String instanceId = policyOpt.map(AuthPolicyEntity::getInstanceId).orElse(null);

        // Update routes list for this policy in Nacos
        updatePolicyRoutesInNacos(instanceId, policyId);
    }

    /**
     * Delete a binding by binding ID.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBinding(String bindingId) {
        Optional<RouteAuthBindingEntity> optBinding = routeAuthBindingRepository.findByBindingId(bindingId);
        if (optBinding.isEmpty()) {
            throw new IllegalArgumentException("Binding not found: " + bindingId);
        }

        RouteAuthBindingEntity binding = optBinding.get();
        String policyId = binding.getPolicyId();
        String instanceId = binding.getInstanceId();

        routeAuthBindingRepository.deleteByBindingId(bindingId);
        log.info("Deleted binding: {}", bindingId);

        // Update routes list for this policy in Nacos
        updatePolicyRoutesInNacos(instanceId, policyId);
    }

    /**
     * Enable a binding.
     */
    @Transactional(rollbackFor = Exception.class)
    public void enableBinding(String bindingId) {
        Optional<RouteAuthBindingEntity> optBinding = routeAuthBindingRepository.findByBindingId(bindingId);
        if (optBinding.isEmpty()) {
            throw new IllegalArgumentException("Binding not found: " + bindingId);
        }

        RouteAuthBindingEntity binding = optBinding.get();
        binding.setEnabled(true);
        routeAuthBindingRepository.save(binding);

        updatePolicyRoutesInNacos(binding.getInstanceId(), binding.getPolicyId());
        log.info("Binding enabled: {}", bindingId);
    }

    /**
     * Disable a binding.
     */
    @Transactional(rollbackFor = Exception.class)
    public void disableBinding(String bindingId) {
        Optional<RouteAuthBindingEntity> optBinding = routeAuthBindingRepository.findByBindingId(bindingId);
        if (optBinding.isEmpty()) {
            throw new IllegalArgumentException("Binding not found: " + bindingId);
        }

        RouteAuthBindingEntity binding = optBinding.get();
        binding.setEnabled(false);
        routeAuthBindingRepository.save(binding);

        updatePolicyRoutesInNacos(binding.getInstanceId(), binding.getPolicyId());
        log.info("Binding disabled: {}", bindingId);
    }

    /**
     * Batch bind policies to a route.
     */
    @Transactional(rollbackFor = Exception.class)
    public List<RouteAuthBindingEntity> batchBind(String routeId, List<String> policyIds) {
        List<RouteAuthBindingEntity> bindings = new ArrayList<>();
        for (String policyId : policyIds) {
            try {
                bindings.add(bindPolicyToRoute(policyId, routeId, null));
            } catch (Exception e) {
                log.warn("Failed to bind policy {} to route {}: {}", policyId, routeId, e.getMessage());
            }
        }
        return bindings;
    }

    /**
     * Count bindings for a policy.
     */
    public long countBindingsForPolicy(String policyId) {
        return routeAuthBindingRepository.countByPolicyId(policyId);
    }

    // ==================== Private Helper Methods ====================

    private void validateAuthType(String authType) {
        if (authType == null) {
            throw new IllegalArgumentException("Auth type is required");
        }
        AuthType type = AuthType.fromCode(authType);
        if (type == AuthType.NONE) {
            throw new IllegalArgumentException("Invalid auth type: " + authType);
        }
    }

    /**
     * Update routes list for a policy in Nacos for a specific instance.
     */
    private void updatePolicyRoutesInNacos(String instanceId, String policyId) {
        List<RouteAuthBindingEntity> bindings = routeAuthBindingRepository.findByPolicyIdAndEnabledTrueAndInstanceId(policyId, instanceId);
        List<String> routeIds = bindings.stream()
                .map(RouteAuthBindingEntity::getRouteId)
                .collect(Collectors.toList());
        publishPolicyRoutesToNacos(instanceId, policyId, routeIds);
    }

    /**
     * Publish routes list for a policy to Nacos for a specific instance.
     */
    private void publishPolicyRoutesToNacos(String instanceId, String policyId, List<String> routeIds) {
        String dataId = AUTH_ROUTES_PREFIX + policyId;
        String namespace = getNacosNamespace(instanceId);
        if (routeIds == null || routeIds.isEmpty()) {
            configCenterService.removeConfig(dataId, namespace);
            log.debug("Removed empty routes list from Nacos: {} (namespace: {})", dataId, namespace);
        } else {
            configCenterService.publishConfig(dataId, namespace, routeIds);
            log.debug("Published routes list to Nacos: {} -> {} routes (namespace: {})", dataId, routeIds.size(), namespace);
        }
    }

    /**
     * Publish policies index to Nacos for a specific instance.
     * This allows gateway instance to discover its enabled policies.
     */
    private void publishPoliciesIndex(String instanceId) {
        List<AuthPolicyEntity> enabledPolicies;
        if (instanceId != null && !instanceId.isEmpty()) {
            enabledPolicies = authPolicyRepository.findByInstanceIdAndEnabledTrue(instanceId);
        } else {
            enabledPolicies = authPolicyRepository.findByEnabledTrue();
        }
        List<String> policyIds = enabledPolicies.stream()
                .map(AuthPolicyEntity::getPolicyId)
                .collect(Collectors.toList());
        String namespace = getNacosNamespace(instanceId);
        configCenterService.publishConfig(AUTH_POLICIES_INDEX, namespace, policyIds);
        log.info("Published auth policies index for namespace {}: {} policies", namespace, policyIds.size());
    }

    private AuthPolicyEntity toEntity(AuthPolicyDefinition policy) {
        AuthPolicyEntity entity = new AuthPolicyEntity();
        entity.setPolicyId(policy.getPolicyId());
        entity.setPolicyName(policy.getPolicyName());
        entity.setAuthType(policy.getAuthTypeEnum());
        entity.setEnabled(policy.isEnabled());
        entity.setDescription(policy.getDescription());

        try {
            String configJson = objectMapper.writeValueAsString(policy);
            entity.setConfig(configJson);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize policy config", e);
        }

        return entity;
    }

    private AuthPolicyDefinition toDefinition(AuthPolicyEntity entity) {
        if (entity == null) {
            return null;
        }

        log.info("toDefinition: entity.config length = {}, content = {}", entity.getConfig() != null ? entity.getConfig().length() : 0, entity.getConfig());

        // Try to parse from config JSON first
        if (entity.getConfig() != null && !entity.getConfig().isEmpty()) {
            try {
                AuthPolicyDefinition policy = objectMapper.readValue(entity.getConfig(), AuthPolicyDefinition.class);
                // Ensure basic fields are set from entity
                policy.setPolicyId(entity.getPolicyId());
                policy.setPolicyName(entity.getPolicyName());
                policy.setAuthTypeEnum(entity.getAuthType());
                policy.setEnabled(entity.getEnabled());
                policy.setDescription(entity.getDescription());
                log.info("toDefinition result: apiKey={}, apiKeyHeader={}", policy.getApiKey(), policy.getApiKeyHeader());
                return policy;
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse policy config JSON, using entity fields: {}", e.getMessage());
            }
        }

        // Fallback: create from entity fields
        AuthPolicyDefinition policy = new AuthPolicyDefinition();
        policy.setPolicyId(entity.getPolicyId());
        policy.setPolicyName(entity.getPolicyName());
        policy.setAuthTypeEnum(entity.getAuthType());
        policy.setEnabled(entity.getEnabled());
        policy.setDescription(entity.getDescription());

        return policy;
    }

    /**
     * Publish raw config JSON string to Nacos.
     * This avoids re-serialization issues with ObjectMapper and @JsonInclude annotation.
     */
    private boolean publishRawConfig(String dataId, String namespace, String content) {
        log.info("Publishing raw config to Nacos: dataId={}, namespace={}, content has apiKey: {}",
                 dataId, namespace, content.contains("apiKey"));
        return configCenterService.publishRawConfig(dataId, namespace, content);
    }
}