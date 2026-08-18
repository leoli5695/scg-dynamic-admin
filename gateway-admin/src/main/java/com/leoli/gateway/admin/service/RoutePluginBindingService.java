package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.PluginBindingEntity;
import com.leoli.gateway.admin.model.PluginEntity;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.repository.PluginBindingRepository;
import com.leoli.gateway.admin.repository.PluginRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import com.leoli.gateway.admin.event.GlobalBindingChangedEvent;

/**
 * Route Plugin Binding Service.
 * Manages the binding relationship between routes and plugins.
 * Handles CRUD operations and configuration management.
 *
 * Key responsibilities:
 * - Bind plugins to routes with configuration parameters
 * - Update bindings (config, order, enable/disable)
 * - Provide bindings for route filter merging
 *
 * @author leoli
 */
@Service
@Slf4j
public class RoutePluginBindingService {

    @Autowired
    private PluginBindingRepository pluginBindingRepository;

    @Autowired
    private PluginRepository pluginRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * Create a binding between a route and a plugin.
     *
     * @param routeId the route ID (use "GLOBAL" for global binding)
     * @param pluginId the plugin ID
     * @param config the configuration JSON
     * @param order the execution order
     * @param instanceId the instance ID
     * @return the created binding entity
     */
    @Transactional
    public PluginBindingEntity bindPluginToRoute(
        String routeId, String pluginId, Map<String, Object> config, Integer order, String instanceId) {

        // Validate plugin exists and is enabled
        Optional<PluginEntity> pluginOpt = pluginRepository.findByPluginId(pluginId);
        if (pluginOpt.isEmpty()) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }

        PluginEntity plugin = pluginOpt.get();
        if (!Boolean.TRUE.equals(plugin.getEnabled())) {
            throw new IllegalArgumentException("Plugin is not enabled: " + pluginId);
        }

        // Validate route exists (skip for global binding)
        boolean isGlobalBinding = PluginBindingRepository.GLOBAL_ROUTE_ID.equals(routeId);
        if (!isGlobalBinding) {
            Optional<RouteEntity> routeOpt = routeRepository.findByRouteId(routeId);
            if (routeOpt.isEmpty()) {
                throw new IllegalArgumentException("Route not found: " + routeId);
            }
        }

        // Check for existing binding
        Optional<PluginBindingEntity> existingOpt = pluginBindingRepository.findByRouteIdAndPluginId(routeId, pluginId);
        if (existingOpt.isPresent()) {
            throw new IllegalArgumentException("Binding already exists between route " + routeId + " and plugin " + pluginId);
        }

        // Create binding entity
        PluginBindingEntity binding = new PluginBindingEntity();
        binding.setBindingId(UUID.randomUUID().toString());
        binding.setRouteId(routeId);
        binding.setPluginId(pluginId);
        binding.setPluginName(plugin.getPluginName());
        binding.setOrder(order != null ? order : 0);
        binding.setEnabled(true);
        binding.setInstanceId(instanceId);

        // Serialize config to JSON
        if (config != null) {
            try {
                binding.setConfig(objectMapper.writeValueAsString(config));
            } catch (Exception e) {
                log.warn("Failed to serialize config for binding", e);
            }
        }

        binding = pluginBindingRepository.save(binding);

        log.info("Plugin bound to route: routeId={}, pluginId={}, bindingId={}, isGlobal={}",
            routeId, pluginId, binding.getBindingId(), isGlobalBinding);

        // Publish event if this is a global binding change
        if (isGlobalBinding) {
            eventPublisher.publishEvent(new GlobalBindingChangedEvent(
                GlobalBindingChangedEvent.ChangeType.CREATED,
                binding.getBindingId(),
                pluginId
            ));
        }

        return binding;
    }

    /**
     * Update a binding's configuration.
     *
     * @param bindingId the binding ID
     * @param config the new configuration
     * @param order the new order
     * @return the updated binding
     */
    @Transactional
    public PluginBindingEntity updateBinding(String bindingId, Map<String, Object> config, Integer order) {
        Optional<PluginBindingEntity> bindingOpt = pluginBindingRepository.findByBindingId(bindingId);
        if (bindingOpt.isEmpty()) {
            throw new IllegalArgumentException("Binding not found: " + bindingId);
        }

        PluginBindingEntity binding = bindingOpt.get();

        if (config != null) {
            try {
                binding.setConfig(objectMapper.writeValueAsString(config));
            } catch (Exception e) {
                log.warn("Failed to serialize config", e);
            }
        }

        if (order != null) {
            binding.setOrder(order);
        }

        binding = pluginBindingRepository.save(binding);

        log.info("Binding updated: bindingId={}", bindingId);

        // Publish event if this is a global binding change
        if (PluginBindingRepository.GLOBAL_ROUTE_ID.equals(binding.getRouteId())) {
            eventPublisher.publishEvent(new GlobalBindingChangedEvent(
                GlobalBindingChangedEvent.ChangeType.UPDATED,
                bindingId,
                binding.getPluginId()
            ));
        }

        return binding;
    }

    /**
     * Enable a binding.
     */
    @Transactional
    public PluginBindingEntity enableBinding(String bindingId) {
        Optional<PluginBindingEntity> bindingOpt = pluginBindingRepository.findByBindingId(bindingId);
        if (bindingOpt.isEmpty()) {
            throw new IllegalArgumentException("Binding not found: " + bindingId);
        }

        PluginBindingEntity binding = bindingOpt.get();
        binding.setEnabled(true);
        binding = pluginBindingRepository.save(binding);

        log.info("Binding enabled: bindingId={}", bindingId);

        // Publish event if this is a global binding change
        if (PluginBindingRepository.GLOBAL_ROUTE_ID.equals(binding.getRouteId())) {
            eventPublisher.publishEvent(new GlobalBindingChangedEvent(
                GlobalBindingChangedEvent.ChangeType.ENABLED,
                bindingId,
                binding.getPluginId()
            ));
        }

        return binding;
    }

    /**
     * Disable a binding.
     */
    @Transactional
    public PluginBindingEntity disableBinding(String bindingId) {
        Optional<PluginBindingEntity> bindingOpt = pluginBindingRepository.findByBindingId(bindingId);
        if (bindingOpt.isEmpty()) {
            throw new IllegalArgumentException("Binding not found: " + bindingId);
        }

        PluginBindingEntity binding = bindingOpt.get();
        binding.setEnabled(false);
        binding = pluginBindingRepository.save(binding);

        log.info("Binding disabled: bindingId={}", bindingId);

        // Publish event if this is a global binding change
        if (PluginBindingRepository.GLOBAL_ROUTE_ID.equals(binding.getRouteId())) {
            eventPublisher.publishEvent(new GlobalBindingChangedEvent(
                GlobalBindingChangedEvent.ChangeType.DISABLED,
                bindingId,
                binding.getPluginId()
            ));
        }

        return binding;
    }

    /**
     * Delete a binding (unbind plugin from route).
     */
    @Transactional
    public void deleteBinding(String bindingId) {
        Optional<PluginBindingEntity> bindingOpt = pluginBindingRepository.findByBindingId(bindingId);
        if (bindingOpt.isEmpty()) {
            throw new IllegalArgumentException("Binding not found: " + bindingId);
        }

        PluginBindingEntity binding = bindingOpt.get();
        String routeId = binding.getRouteId();
        String pluginId = binding.getPluginId();

        pluginBindingRepository.delete(binding);
        log.info("Binding deleted: bindingId={}", bindingId);

        // Publish event if this is a global binding change
        if (PluginBindingRepository.GLOBAL_ROUTE_ID.equals(routeId)) {
            eventPublisher.publishEvent(new GlobalBindingChangedEvent(
                GlobalBindingChangedEvent.ChangeType.DELETED,
                bindingId,
                pluginId
            ));
        }
    }

    /**
     * Delete binding by route and plugin ID.
     */
    @Transactional
    public void deleteBindingByRouteAndPlugin(String routeId, String pluginId) {
        Optional<PluginBindingEntity> bindingOpt = pluginBindingRepository.findByRouteIdAndPluginId(routeId, pluginId);
        if (bindingOpt.isPresent()) {
            pluginBindingRepository.delete(bindingOpt.get());
            log.info("Binding deleted: routeId={}, pluginId={}", routeId, pluginId);
        }
    }

    /**
     * Get all bindings for a route.
     */
    public List<PluginBindingEntity> getBindingsByRouteId(String routeId) {
        return pluginBindingRepository.findByRouteId(routeId);
    }

    /**
     * Get enabled bindings for a route (ordered).
     */
    public List<PluginBindingEntity> getEnabledBindingsByRouteId(String routeId) {
        return pluginBindingRepository.findEnabledBindingsByRouteIdOrdered(routeId);
    }

    /**
     * Get all bindings for a plugin.
     */
    public List<PluginBindingEntity> getBindingsByPluginId(String pluginId) {
        return pluginBindingRepository.findByPluginId(pluginId);
    }

    /**
     * Get binding by ID.
     */
    public Optional<PluginBindingEntity> getBindingById(String bindingId) {
        return pluginBindingRepository.findByBindingId(bindingId);
    }

    /**
     * Get bindings for multiple routes (batch query for RouteService).
     */
    public List<PluginBindingEntity> getBindingsByRouteIds(List<String> routeIds) {
        return pluginBindingRepository.findEnabledBindingsByRouteIds(routeIds);
    }

    /**
     * Convert binding config JSON to Map.
     */
    public Map<String, String> parseBindingConfig(PluginBindingEntity binding) {
        if (binding.getConfig() == null || binding.getConfig().isEmpty()) {
            return new HashMap<>();
        }

        try {
            Map<String, Object> configMap = objectMapper.readValue(binding.getConfig(), Map.class);
            // Convert all values to String for FilterDefinition.args
            Map<String, String> args = new HashMap<>();
            for (Map.Entry<String, Object> entry : configMap.entrySet()) {
                args.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            return args;
        } catch (Exception e) {
            log.warn("Failed to parse binding config: {}", binding.getBindingId(), e);
            return new HashMap<>();
        }
    }

    /**
     * Get routes that use a specific plugin.
     */
    public List<String> getRoutesByPluginId(String pluginId) {
        List<PluginBindingEntity> bindings = pluginBindingRepository.findByPluginIdAndEnabledTrue(pluginId);
        return bindings.stream()
            .map(PluginBindingEntity::getRouteId)
            .distinct()
            .collect(Collectors.toList());
    }

    /**
     * Count bindings for a route.
     */
    public long countBindingsByRouteId(String routeId) {
        return pluginBindingRepository.countByRouteIdAndEnabledTrue(routeId);
    }

    /**
     * Count bindings for a plugin.
     */
    public long countBindingsByPluginId(String pluginId) {
        return pluginBindingRepository.countByPluginId(pluginId);
    }

    /**
     * Binding statistics.
     */
    public BindingStats getBindingStats() {
        List<PluginBindingEntity> allBindings = pluginBindingRepository.findAll();
        BindingStats stats = new BindingStats();
        stats.setTotal(allBindings.size());
        stats.setEnabled(allBindings.stream().filter(b -> Boolean.TRUE.equals(b.getEnabled())).count());
        stats.setDisabled(allBindings.stream().filter(b -> Boolean.FALSE.equals(b.getEnabled())).count());
        return stats;
    }

    public static class BindingStats {
        private long total;
        private long enabled;
        private long disabled;

        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public long getEnabled() { return enabled; }
        public void setEnabled(long enabled) { this.enabled = enabled; }
        public long getDisabled() { return disabled; }
        public void setDisabled(long disabled) { this.disabled = disabled; }
    }

    // ==================== Global Binding Methods ====================

    /**
     * Get all global bindings (bindings with routeId = "GLOBAL").
     */
    public List<PluginBindingEntity> getGlobalBindings() {
        return pluginBindingRepository.findByRouteId(PluginBindingRepository.GLOBAL_ROUTE_ID);
    }

    /**
     * Get enabled global bindings ordered by order field.
     */
    public List<PluginBindingEntity> getEnabledGlobalBindings() {
        return pluginBindingRepository.findEnabledGlobalBindingsOrdered();
    }

    /**
     * Bind a plugin globally (to all routes).
     *
     * @param pluginId the plugin ID
     * @param config the configuration JSON
     * @param order the execution order
     * @param instanceId the instance ID
     * @return the created global binding entity
     */
    @Transactional
    public PluginBindingEntity bindPluginGlobally(String pluginId, Map<String, Object> config, Integer order, String instanceId) {
        return bindPluginToRoute(PluginBindingRepository.GLOBAL_ROUTE_ID, pluginId, config, order, instanceId);
    }

    /**
     * Check if a plugin has a global binding.
     */
    public boolean hasGlobalBinding(String pluginId) {
        return pluginBindingRepository.existsGlobalBindingForPlugin(pluginId);
    }
}