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

        // Get namespace for this instance
        String namespace = getNacosNamespace(entity.getInstanceId());

        // Remove policy config from Nacos
        String policyDataId = AUTH_POLICY_PREFIX + policyId;
        configCenterService.removeConfig(policyDataId, namespace);
        log.info("Auth policy removed from Nacos: {} (namespace: {})", policyDataId, namespace);

        // Remove routes list from Nacos
        String routesDataId = AUTH_ROUTES_PREFIX + policyId;
        configCenterService.removeConfig(routesDataId, namespace);
        log.info("Auth routes removed from Nacos: {} (namespace: {})", routesDataId, namespace);

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

    // ==================== Usage Example Generation ====================

    /**
     * Generate usage example for an auth policy.
     * Returns headers, curl commands, and calculated values for testing.
     *
     * @param policyId Policy ID
     * @return Map containing usage example details
     */
    public Map<String, Object> generateUsageExample(String policyId) {
        AuthPolicyDefinition policy = getPolicy(policyId);
        if (policy == null) {
            throw new IllegalArgumentException("Policy not found: " + policyId);
        }

        String authType = policy.getAuthType();
        Map<String, Object> example = new HashMap<>();
        example.put("policyId", policyId);
        example.put("policyName", policy.getPolicyName());
        example.put("authType", authType);

        switch (authType.toUpperCase()) {
            case "JWT":
                generateJwtExample(policy, example);
                break;
            case "API_KEY":
                generateApiKeyExample(policy, example);
                break;
            case "BASIC":
                generateBasicExample(policy, example);
                break;
            case "OAUTH2":
                generateOAuth2Example(policy, example);
                break;
            case "HMAC":
                generateHmacExample(policy, example);
                break;
            default:
                example.put("error", "Unknown auth type: " + authType);
        }

        return example;
    }

    /**
     * Generate JWT usage example.
     */
    private void generateJwtExample(AuthPolicyDefinition policy, Map<String, Object> example) {
        String secretKey = policy.getSecretKey();
        if (secretKey == null || secretKey.isEmpty()) {
            example.put("error", "Secret key not configured");
            return;
        }

        // Generate a test JWT token
        String algorithm = policy.getJwtAlgorithm() != null ? policy.getJwtAlgorithm() : "HS256";
        String issuer = policy.getJwtIssuer();
        String audience = policy.getJwtAudience();

        try {
            // Create a simple JWT token for testing
            long now = System.currentTimeMillis() / 1000;
            long exp = now + 3600; // 1 hour expiration

            // Build JWT payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("iat", now);
            payload.put("exp", exp);
            if (issuer != null) payload.put("iss", issuer);
            if (audience != null) payload.put("aud", audience);
            payload.put("sub", "test-user");

            // Generate token using io.jsonwebtoken (JJwt)
            String token = generateTestJwtToken(secretKey, algorithm, payload);

            // Header format
            example.put("headerName", "Authorization");
            example.put("headerValue", "Bearer " + token);
            example.put("headerFormat", "Authorization: Bearer <token>");

            // Curl example
            String curlExample = String.format("curl -H \"Authorization: Bearer %s\" http://your-api-endpoint", token);
            example.put("curlExample", curlExample);

            // Token info
            example.put("tokenExpiresIn", "1 hour");
            example.put("algorithm", algorithm);
            if (issuer != null) example.put("issuer", issuer);
            if (audience != null) example.put("audience", audience);

        } catch (Exception e) {
            log.error("Failed to generate JWT token", e);
            example.put("error", "Failed to generate test token: " + e.getMessage());
        }
    }

    /**
     * Generate a test JWT token using JJwt.
     * Handles short secret keys by padding with zeros (for demo purposes only).
     */
    private String generateTestJwtToken(String secretKey, String algorithm, Map<String, Object> payload) {
        // Use io.jsonwebtoken library
        io.jsonwebtoken.JwtBuilder builder = io.jsonwebtoken.Jwts.builder();

        // Set claims
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            builder.claim(entry.getKey(), entry.getValue());
        }

        // Determine algorithm
        String jwtAlg = algorithm.startsWith("HS") ? algorithm : "HS256";
        
        // HS256 requires at least 256 bits (32 bytes), HS512 requires 512 bits (64 bytes)
        byte[] keyBytes = secretKey.getBytes();
        int requiredLength = jwtAlg.equals("HS512") ? 64 : 32;
        
        if (keyBytes.length < requiredLength) {
            // Pad the key with zeros to meet minimum length requirement
            // This is only for demo purposes - in production, use properly generated keys
            byte[] paddedKey = new byte[requiredLength];
            System.arraycopy(keyBytes, 0, paddedKey, 0, keyBytes.length);
            keyBytes = paddedKey;
        }

        // Sign with secret
        builder.signWith(io.jsonwebtoken.SignatureAlgorithm.valueOf(jwtAlg), keyBytes);

        return builder.compact();
    }

    /**
     * Generate API Key usage example.
     */
    private void generateApiKeyExample(AuthPolicyDefinition policy, Map<String, Object> example) {
        String apiKey = policy.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            example.put("error", "API key not configured");
            return;
        }

        String headerName = policy.getApiKeyHeader() != null ? policy.getApiKeyHeader() : "X-API-Key";
        String prefix = policy.getApiKeyPrefix() != null ? policy.getApiKeyPrefix() : "";
        String fullValue = prefix + apiKey;

        // Header format
        example.put("headerName", headerName);
        example.put("headerValue", fullValue);
        example.put("headerFormat", headerName + ": <prefix><apiKey>");
        if (!prefix.isEmpty()) {
            example.put("prefixNote", "Key prefix: " + prefix);
        }

        // Curl example
        String curlExample = String.format("curl -H \"%s: %s\" http://your-api-endpoint", headerName, fullValue);
        example.put("curlExample", curlExample);

        // Raw key info (for admin reference)
        example.put("apiKeyRaw", apiKey);
    }

    /**
     * Generate Basic Auth usage example.
     */
    private void generateBasicExample(AuthPolicyDefinition policy, Map<String, Object> example) {
        String username = policy.getBasicUsername();
        String password = policy.getBasicPassword();

        if (username == null || password == null) {
            example.put("error", "Username or password not configured");
            return;
        }

        // Calculate Base64 encoded credentials
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        String headerValue = "Basic " + encoded;

        // Header format
        example.put("headerName", "Authorization");
        example.put("headerValue", headerValue);
        example.put("headerFormat", "Authorization: Basic <base64(username:password)>");
        example.put("credentialsEncoded", encoded);

        // Show raw credentials (for admin reference)
        example.put("usernameRaw", username);
        example.put("credentialsFormat", username + ":" + password);

        // Curl example
        String curlExample = String.format("curl -H \"Authorization: Basic %s\" http://your-api-endpoint", encoded);
        example.put("curlExample", curlExample);

        // Alternative curl with -u flag
        String curlWithU = String.format("curl -u \"%s:%s\" http://your-api-endpoint", username, password);
        example.put("curlWithAuthFlag", curlWithU);

        // Realm info
        String realm = policy.getRealm();
        if (realm != null) {
            example.put("realm", realm);
        }
    }

    /**
     * Generate OAuth2 usage example.
     */
    private void generateOAuth2Example(AuthPolicyDefinition policy, Map<String, Object> example) {
        String clientId = policy.getClientId();
        String clientSecret = policy.getClientSecret();
        String tokenEndpoint = policy.getTokenEndpoint();

        if (clientId == null || clientSecret == null || tokenEndpoint == null) {
            example.put("error", "OAuth2 credentials not fully configured");
            return;
        }

        // Header format (once you have the token)
        example.put("headerName", "Authorization");
        example.put("headerValue", "Bearer <access_token>");
        example.put("headerFormat", "Authorization: Bearer <access_token>");

        // Curl to get token
        String getTokenCurl = String.format(
            "curl -X POST \"%s\" -H \"Content-Type: application/x-www-form-urlencoded\" " +
            "-d \"grant_type=client_credentials&client_id=%s&client_secret=%s\"",
            tokenEndpoint, clientId, clientSecret
        );
        example.put("getTokenCurl", getTokenCurl);

        // Client info
        example.put("clientId", clientId);
        example.put("tokenEndpoint", tokenEndpoint);
        if (policy.getRequiredScopes() != null) {
            example.put("requiredScopes", policy.getRequiredScopes());
        }

        // Note about getting token first
        example.put("note", "OAuth2 requires obtaining an access token first via the token endpoint");
    }

    /**
     * Generate HMAC usage example.
     */
    private void generateHmacExample(AuthPolicyDefinition policy, Map<String, Object> example) {
        String accessKey = policy.getAccessKey();
        String signatureAlgorithm = policy.getSignatureAlgorithm();

        if (accessKey == null) {
            example.put("error", "Access key not configured");
            return;
        }

        // HMAC requires multiple headers
        example.put("headerFormatNote", "HMAC authentication requires multiple headers");

        // Required headers list
        List<Map<String, String>> headers = new ArrayList<>();
        headers.add(Map.of("name", "X-Access-Key", "value", accessKey));
        headers.add(Map.of("name", "X-Signature", "value", "<calculated_signature>"));
        headers.add(Map.of("name", "X-Timestamp", "value", "<current_timestamp>"));
        if (policy.isRequireNonce()) {
            headers.add(Map.of("name", "X-Nonce", "value", "<random_string>"));
        }
        example.put("requiredHeaders", headers);

        // Signature calculation info
        example.put("signatureAlgorithm", signatureAlgorithm != null ? signatureAlgorithm : "HMAC-SHA256");
        example.put("signatureCalculation", "signature = HMAC-SHA256(secretKey, stringToSign)");
        example.put("stringToSignFormat", "Method + Path + Timestamp + Nonce + BodyHash");

        // Access key info
        example.put("accessKey", accessKey);
        example.put("clockSkewMinutes", policy.getClockSkewMinutes());

        // Note about complexity
        example.put("note", "HMAC signature calculation is complex. Consider using a client SDK or providing signature calculation endpoint.");

        // Curl example (with placeholder signature)
        String curlExample = String.format(
            "curl -H \"X-Access-Key: %s\" -H \"X-Signature: <calculated>\" " +
            "-H \"X-Timestamp: <timestamp>\" http://your-api-endpoint",
            accessKey
        );
        example.put("curlExample", curlExample);
    }
}