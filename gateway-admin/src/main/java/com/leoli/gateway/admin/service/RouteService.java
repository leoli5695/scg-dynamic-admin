package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.config.CacheConfig;
import com.leoli.gateway.admin.converter.RouteConverter;
import com.leoli.gateway.admin.event.GlobalBindingChangedEvent;
import com.leoli.gateway.admin.metrics.BusinessMetrics;
import com.leoli.gateway.admin.metrics.TracingHelper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.PluginBindingEntity;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteDefinition.FilterDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.model.RouteResponse;
import com.leoli.gateway.admin.model.RouteServiceBinding;
import com.leoli.gateway.admin.model.ServiceType;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.validation.RouteValidator;
import io.micrometer.tracing.Span;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Route configuration service with per-route incremental refresh.
 * 
 * Simplified design:
 * - route_id (UUID) is the primary key, also used as Nacos config key
 * - route_name is the business name for display
 * - metadata stores complete JSON configuration
 *
 * @author leoli
 */
@Slf4j
@Service
public class RouteService {

    private static final String ROUTE_PREFIX = "config.gateway.route-";
    private static final String ROUTES_INDEX = "config.gateway.metadata.routes-index";

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteConverter routeConverter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GatewayInstanceRepository gatewayInstanceRepository;

    @Autowired
    private BusinessMetrics businessMetrics;

    @Autowired
    private TracingHelper tracingHelper;

    @Autowired
    private RoutePluginBindingService routePluginBindingService;

    @Autowired
    private AiRouteProjector aiRouteProjector;

    @Autowired
    private CacheManager cacheManager;

    @PostConstruct
    public void init() {
        loadRoutesFromDatabase();
        log.info("RouteService initialized with per-route Nacos storage");
    }

    /**
     * Load routes from database on startup and recover missing configs in Nacos.
     * Only pushes to Nacos if config is missing (to avoid unnecessary Gateway refresh).
     */
    private void loadRoutesFromDatabase() {
        try {
            List<RouteEntity> entities = routeRepository.findAll();
            if (entities == null || entities.isEmpty()) {
                log.info("No routes found in database");
                return;
            }

            long enabledCount = entities.stream().filter(RouteEntity::getEnabled).count();
            log.info("Loaded {} routes from database ({} enabled)", entities.size(), enabledCount);

            // 【性能优化】只加载启用路由关联的实例，避免N+1查询和内存浪费
            Set<String> neededInstanceIds = entities.stream()
                    .filter(RouteEntity::getEnabled)
                    .map(RouteEntity::getInstanceId)
                    .filter(id -> id != null && !id.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
            
            Map<String, String> instanceNamespaceMap = new HashMap<>();
            if (!neededInstanceIds.isEmpty()) {
                instanceNamespaceMap = gatewayInstanceRepository.findByInstanceIdIn(new ArrayList<>(neededInstanceIds)).stream()
                        .filter(i -> i.getNacosNamespace() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                GatewayInstanceEntity::getInstanceId,
                                GatewayInstanceEntity::getNacosNamespace,
                                (a, b) -> a // handle duplicates
                        ));
                log.debug("Loaded {} instances for {} enabled routes", instanceNamespaceMap.size(), neededInstanceIds.size());
            }

            // Recover only ENABLED routes that are missing in Nacos
            int recoveredCount = 0;
            for (RouteEntity entity : entities) {
                if (!Boolean.TRUE.equals(entity.getEnabled())) {
                    continue; // Skip disabled routes
                }

                // Get namespace from pre-loaded map (avoid N+1 query)
                String nacosNamespace = instanceNamespaceMap.get(entity.getInstanceId());
                String routeDataId = ROUTE_PREFIX + entity.getRouteId();
                if (!configCenterService.configExists(routeDataId, nacosNamespace)) {
                    RouteDefinition route = routeConverter.toDefinition(entity);
                    // Merge plugin bindings before publishing
                    RouteDefinition mergedRoute = mergePluginBindingsToRoute(route, entity.getRouteId());
                    configCenterService.publishConfig(routeDataId, nacosNamespace, mergedRoute);
                    recoveredCount++;
                    log.info("Recovered missing route in Nacos: {} (namespace: {}, filters={})",
                        routeDataId, nacosNamespace,
                        mergedRoute.getFilters() != null ? mergedRoute.getFilters().size() : 0);
                }
            }

            if (recoveredCount > 0) {
                log.info("Recovered {} missing routes in Nacos on startup", recoveredCount);
            }
        } catch (Exception e) {
            log.warn("Failed to load routes from database: {}", e.getMessage());
        }
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
     * Publish a route to the config center, failing loudly when the publish does not take.
     * <p>
     * FIX (dual-write divergence): {@code configCenterService.publishConfig} swallows Nacos
     * errors internally and returns {@code false} rather than throwing. The single-route write
     * paths (create/update/enable) are {@code @Transactional(rollbackFor = Exception.class)}, so
     * ignoring that boolean let the DB row commit while the config center never received it -
     * a route that shows up in the console but is invisible to the gateway, with no error surfaced.
     * Throwing here turns a silent {@code false} into a rollback.
     */
    private void publishRouteOrThrow(String routeDataId, String nacosNamespace, RouteDefinition mergedRoute) {
        boolean published = configCenterService.publishConfig(routeDataId, nacosNamespace, mergedRoute);
        if (!published) {
            throw new IllegalStateException(
                    "Failed to publish route config to config center: " + routeDataId
                            + " (namespace: " + (nacosNamespace != null ? nacosNamespace : "default") + ")");
        }
    }

    /**
     * Remove a route from the config center, failing loudly when the removal does not take.
     * <p>
     * FIX (delete orphan): {@code configCenterService.removeConfig} swallows errors and returns
     * {@code false}. The old delete path ignored that boolean and deleted the DB row anyway, so a
     * failed removal left a permanent Nacos orphan that the reconcile task can no longer clean -
     * orphan removal resolves the namespace from the DB row, which by then is gone. Throwing here
     * rolls back the {@code @Transactional} delete, keeping the DB row so state stays consistent
     * and the removal can be retried. Mirrors {@link #publishRouteOrThrow}.
     */
    private void removeRouteOrThrow(String routeDataId, String nacosNamespace) {
        boolean removed = configCenterService.removeConfig(routeDataId, nacosNamespace);
        if (!removed) {
            throw new IllegalStateException(
                    "Failed to remove route config from config center: " + routeDataId
                            + " (namespace: " + (nacosNamespace != null ? nacosNamespace : "default") + ")");
        }
    }

    /**
     * Get all routes.
     */
    public List<RouteResponse> getAllRoutes() {
        List<RouteEntity> entities = routeRepository.findAll();
        // Pre-load global bindings once to avoid N+1 queries
        List<PluginBindingEntity> globalBindings = routePluginBindingService.getEnabledGlobalBindings();
        return entities.stream()
                .map(entity -> toResponse(entity, globalBindings))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get all routes for a specific instance.
     */
    public List<RouteResponse> getAllRoutesByInstanceId(String instanceId) {
        List<RouteEntity> entities = routeRepository.findByInstanceId(instanceId);
        // Pre-load global bindings once to avoid N+1 queries
        List<PluginBindingEntity> globalBindings = routePluginBindingService.getEnabledGlobalBindings();
        return entities.stream()
                .map(entity -> toResponse(entity, globalBindings))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get route by route_id (UUID).
     */
    @Cacheable(value = CacheConfig.CACHE_ROUTES, key = "#routeId", unless = "#result == null")
    public RouteDefinition getRoute(String routeId) {
        return routeRepository.findById(routeId)
                .map(routeConverter::toDefinition)
                .orElse(null);
    }

    /**
     * Get route response by route_id (UUID).
     */
    @Cacheable(value = CacheConfig.CACHE_ROUTES, key = "'response:' + #routeId", unless = "#result == null")
    public RouteResponse getRouteResponse(String routeId) {
        return routeRepository.findById(routeId)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * Get route entity by route_id (UUID) - for audit logging.
     * Not cached as it's used for internal operations.
     */
    public RouteEntity getRouteEntityByRouteId(String routeId) {
        return routeRepository.findById(routeId).orElse(null);
    }

    /**
     * Get route by business name.
     */
    @Cacheable(value = CacheConfig.CACHE_ROUTES, key = "'name:' + #routeName", unless = "#result == null")
    public RouteDefinition getRouteByName(String routeName) {
        return routeRepository.findByRouteName(routeName)
                .map(routeConverter::toDefinition)
                .orElse(null);
    }

    /**
     * Create route with dual-write to database and Nacos.
     */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = CacheConfig.CACHE_ROUTES, allEntries = true)
    public RouteEntity createRoute(RouteDefinition route) {
        return createRoute(route, null);
    }

    /**
     * Create route for a specific instance with dual-write to database and Nacos.
     * @param route Route definition
     * @param instanceId Gateway instance ID (optional, uses default namespace if null)
     */
    @Transactional(rollbackFor = Exception.class)
    public RouteEntity createRoute(RouteDefinition route, String instanceId) {
        Span span = tracingHelper.startRouteSpan("create", route.getId());
        try {
            // Generate UUID if not provided
            if (route.getId() == null || route.getId().isEmpty()) {
                route.setId(UUID.randomUUID().toString());
            }

            // AI routes: fill placeholder URI and validate bound AI services before structural validation
            aiRouteProjector.normalizeAiRoute(route);
            aiRouteProjector.validateAiRoute(route);

            // Validate
            RouteValidator.validateAndThrow(route);

            // Check if route already exists
            if (routeRepository.existsById(route.getId())) {
                throw new IllegalArgumentException("Route already exists with id: " + route.getId());
            }

            // Check route name uniqueness (within instance scope if instanceId provided)
            if (route.getRouteName() != null && !route.getRouteName().isEmpty()) {
                boolean nameExists;
                if (instanceId != null && !instanceId.isEmpty()) {
                    nameExists = routeRepository.existsByRouteNameAndInstanceId(route.getRouteName(), instanceId);
                } else {
                    nameExists = routeRepository.existsByRouteName(route.getRouteName());
                }
                if (nameExists) {
                    throw new IllegalArgumentException("Route name already exists: " + route.getRouteName());
                }
            }

            // Get Nacos namespace from instance
            String nacosNamespace = getNacosNamespace(instanceId);

            // Save to database
            // Same rule as updateRouteByRouteId: never persist synthesized plugin filters, so a
            // route duplicated from a GET payload does not start life with them baked in.
            route.setFilters(stripPluginBindingFilters(route.getId(), route.getFilters()));
            RouteEntity entity = routeConverter.toEntity(route);
            entity.setEnabled(true);
            entity.setInstanceId(instanceId);
            entity = routeRepository.save(entity);
            log.info("Route saved to database: id={}, name={}, instanceId={}", entity.getRouteId(), entity.getRouteName(), instanceId);

            // Push to Nacos (with namespace)
            String routeDataId = ROUTE_PREFIX + entity.getRouteId();
            long startTime = System.currentTimeMillis();
            try {
                // Merge plugin bindings before publishing
                RouteDefinition mergedRoute = mergePluginBindingsToRoute(route, entity.getRouteId());
                publishRouteOrThrow(routeDataId, nacosNamespace, mergedRoute);
                businessMetrics.recordConfigPublish();
                businessMetrics.recordConfigPublishDuration(System.currentTimeMillis() - startTime);
                log.info("Route pushed to Nacos: {} (namespace: {}, filters={})", routeDataId, nacosNamespace,
                    mergedRoute.getFilters() != null ? mergedRoute.getFilters().size() : 0);
            } catch (Exception e) {
                businessMetrics.recordConfigPublishError();
                throw e;
            }

            // Rebuild routes index for this namespace
            rebuildRoutesIndex(nacosNamespace);

            // AI routes: project the bound AI services into the companion AI_PROXY strategy
            aiRouteProjector.syncProxyStrategy(route, entity.getRouteId(), instanceId);

            // Record metrics
            businessMetrics.recordRouteCreate();
            tracingHelper.tag("route.name", entity.getRouteName());

            log.info("Route created successfully: {}", entity.getRouteId());
            return entity;
        } catch (Exception e) {
            tracingHelper.error(e);
            throw e;
        } finally {
            tracingHelper.endSpan(span);
        }
    }

    /**
     * Update route by route_id (UUID).
     */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = CacheConfig.CACHE_ROUTES, allEntries = true)
    public RouteEntity updateRouteByRouteId(String routeId, RouteDefinition route) {
        if (routeId == null || routeId.isEmpty()) {
            throw new IllegalArgumentException("Route ID is required");
        }

        RouteEntity entity = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));

        // Set the route ID for validation (from path parameter)
        route.setId(routeId);

        // AI routes: fill placeholder URI and validate bound AI services before structural validation
        aiRouteProjector.normalizeAiRoute(route);
        aiRouteProjector.validateAiRoute(route);

        // Validate
        RouteValidator.validateAndThrow(route);

        // Get Nacos namespace from instance
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());

        // Fallback: preserve serviceNamespace/serviceGroup if frontend sends null/empty
        // This is a defensive measure - the frontend should send correct values
        // TODO: TECH_DEBT - Frontend should properly handle null fields instead of clearing them
        //       This backend workaround adds overhead and should be removed once frontend is fixed.
        //       Consider: 1) Frontend validation to prevent null for required fields
        //                 2) Backend DTO to separate API model from domain model
        RouteDefinition originalRoute = routeConverter.toDefinition(entity);
        if ((route.getServiceNamespace() == null || route.getServiceNamespace().isEmpty())
                && originalRoute.getServiceNamespace() != null && !originalRoute.getServiceNamespace().isEmpty()) {
            route.setServiceNamespace(originalRoute.getServiceNamespace());
            log.warn("Preserving original serviceNamespace for route {} (frontend sent null/empty)", routeId);
        }
        if ((route.getServiceGroup() == null || route.getServiceGroup().isEmpty())
                && originalRoute.getServiceGroup() != null && !originalRoute.getServiceGroup().isEmpty()) {
            route.setServiceGroup(originalRoute.getServiceGroup());
            log.warn("Preserving original serviceGroup for route {} (frontend sent null/empty)", routeId);
        }

        // Persist only operator-authored filters; the plugin-binding ones in the payload are echoes
        // of a previous GET and are re-added from the bindings when publishing below.
        route.setFilters(stripPluginBindingFilters(routeId, route.getFilters()));

        // Update entity
        entity.setRouteName(route.getRouteName());
        entity.setDescription(route.getDescription());
        try {
            entity.setMetadata(objectMapper.writeValueAsString(route));
        } catch (Exception e) {
            log.warn("Failed to serialize route config", e);
        }

        // Save to database
        entity = routeRepository.save(entity);
        log.info("Route updated in database: {}", routeId);

        // Push to Nacos if enabled (with namespace)
        if (Boolean.TRUE.equals(entity.getEnabled())) {
            // Merge plugin bindings before publishing
            RouteDefinition mergedRoute = mergePluginBindingsToRoute(route, entity.getRouteId());
            String routeDataId = ROUTE_PREFIX + entity.getRouteId();
            publishRouteOrThrow(routeDataId, nacosNamespace, mergedRoute);
            log.info("Route updated in Nacos: {} (namespace: {}, filters={})", routeDataId, nacosNamespace,
                mergedRoute.getFilters() != null ? mergedRoute.getFilters().size() : 0);
        }

        // AI routes: re-project the bound AI services into the companion AI_PROXY strategy
        // (also disables a stale companion when a route is switched back to NORMAL).
        aiRouteProjector.syncProxyStrategy(route, routeId, entity.getInstanceId());

        log.info("Route updated successfully: {}", routeId);
        return entity;
    }

    /**
     * Delete route by route_id (UUID).
     */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = CacheConfig.CACHE_ROUTES, allEntries = true)
    public void deleteRouteByRouteId(String routeId) {
        Span span = tracingHelper.startRouteSpan("delete", routeId);
        try {
            RouteEntity entity = routeRepository.findById(routeId)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));

            // Get Nacos namespace from instance
            String nacosNamespace = getNacosNamespace(entity.getInstanceId());

            // Remove from Nacos (with namespace) - throw on failure so the delete rolls back
            String routeDataId = ROUTE_PREFIX + entity.getRouteId();
            removeRouteOrThrow(routeDataId, nacosNamespace);
            log.info("Route removed from Nacos: {} (namespace: {})", routeDataId, nacosNamespace);

            // Delete from database
            routeRepository.delete(entity);
            log.info("Route deleted from database: {}", routeId);

            // Rebuild routes index for this namespace
            rebuildRoutesIndex(nacosNamespace);

            // Record metrics
            businessMetrics.recordRouteDelete();

            log.info("Route deleted successfully: {}", routeId);
        } catch (Exception e) {
            tracingHelper.error(e);
            throw e;
        } finally {
            tracingHelper.endSpan(span);
        }
    }

    /**
     * Enable route by route_id (UUID).
     */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = CacheConfig.CACHE_ROUTES, allEntries = true)
    public void enableRouteByRouteId(String routeId) {
        Span span = tracingHelper.startRouteSpan("enable", routeId);
        try {
            RouteEntity entity = routeRepository.findById(routeId)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));

            if (Boolean.TRUE.equals(entity.getEnabled())) {
                log.warn("Route is already enabled: {}", routeId);
                return;
            }

            // Get Nacos namespace from instance
            String nacosNamespace = getNacosNamespace(entity.getInstanceId());

            entity.setEnabled(true);
            routeRepository.save(entity);

            // Push to Nacos (with namespace) - merge plugin bindings
            RouteDefinition route = routeConverter.toDefinition(entity);
            RouteDefinition mergedRoute = mergePluginBindingsToRoute(route, entity.getRouteId());
            String routeDataId = ROUTE_PREFIX + entity.getRouteId();
            publishRouteOrThrow(routeDataId, nacosNamespace, mergedRoute);

            // Rebuild routes index
            rebuildRoutesIndex(nacosNamespace);

            // Record metrics
            businessMetrics.recordRouteEnable();

            log.info("Route enabled: {} (namespace: {})", routeId, nacosNamespace);
        } catch (Exception e) {
            tracingHelper.error(e);
            throw e;
        } finally {
            tracingHelper.endSpan(span);
        }
    }

    /**
     * Disable route by route_id (UUID).
     */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = CacheConfig.CACHE_ROUTES, allEntries = true)
    public void disableRouteByRouteId(String routeId) {
        Span span = tracingHelper.startRouteSpan("disable", routeId);
        try {
            RouteEntity entity = routeRepository.findById(routeId)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));

            if (!Boolean.TRUE.equals(entity.getEnabled())) {
                log.warn("Route is already disabled: {}", routeId);
                return;
            }

            // Get Nacos namespace from instance
            String nacosNamespace = getNacosNamespace(entity.getInstanceId());

            entity.setEnabled(false);
            routeRepository.save(entity);

            // Remove from Nacos (with namespace)
            String routeDataId = ROUTE_PREFIX + entity.getRouteId();
            configCenterService.removeConfig(routeDataId, nacosNamespace);

            // Rebuild routes index
            rebuildRoutesIndex(nacosNamespace);

            // Record metrics
            businessMetrics.recordRouteDisable();

            log.info("Route disabled: {} (namespace: {})", routeId, nacosNamespace);
        } catch (Exception e) {
            tracingHelper.error(e);
            throw e;
        } finally {
            tracingHelper.endSpan(span);
        }
    }

    /**
     * Convert RouteEntity to RouteResponse.
     */
    private RouteResponse toResponse(RouteEntity entity) {
        return toResponse(entity, routePluginBindingService.getEnabledGlobalBindings());
    }

    /**
     * Convert RouteEntity to RouteResponse with pre-loaded global bindings.
     * Use this overload in batch operations to avoid N+1 queries.
     */
    private RouteResponse toResponse(RouteEntity entity, List<PluginBindingEntity> globalBindings) {
        RouteDefinition def = routeConverter.toDefinition(entity);
        RouteResponse response = new RouteResponse();
        response.setId(entity.getRouteId());
        response.setRouteName(entity.getRouteName());
        response.setUri(def.getUri());
        response.setMode(def.getMode());
        response.setServiceId(def.getServiceId());
        response.setServiceNamespace(def.getServiceNamespace());
        response.setServiceGroup(def.getServiceGroup());
        response.setServices(def.getServices());
        response.setGrayRules(def.getGrayRules());
        response.setOrder(def.getOrder());
        response.setPredicates(def.getPredicates());
        response.setFilters(mergePluginBindingsToFilters(entity.getRouteId(), def.getFilters(), globalBindings));
        response.setMetadata(def.getMetadata());
        response.setEnabled(entity.getEnabled());
        response.setDescription(entity.getDescription());
        response.setCategory(entity.getCategory() != null ? entity.getCategory() : def.getCategory());
        response.setAiUpstreams(def.getAiUpstreams());
        response.setModelPredicates(def.getModelPredicates());
        response.setAiFallback(def.getAiFallback());
        return response;
    }

    /**
     * Merge plugin bindings into route filters, using pre-loaded global bindings
     * (pass them in batch operations to avoid N+1 queries).
     * <p>
     * FIX (P1, plugin filters multiplied on every edit): {@link #convertToResponse} hands the client
     * the ALREADY-MERGED filter list and the update path persists whatever comes back, so each save
     * baked the synthesized plugin filters into the stored definition and the next publish merged
     * them in again. The list grew by the number of bindings per round trip and the gateway ran each
     * plugin once per copy. Filters identical to one this method generates are now skipped, which
     * makes the round trip idempotent and also heals definitions already dirtied by earlier saves -
     * including the republish/enable paths that publish stored metadata without a new save.
     *
     * @param routeId the route ID
     * @param existingFilters operator-authored filters from the stored route definition
     * @param globalBindings enabled global plugin bindings, pre-loaded by the caller
     * @return merged filter list, each plugin appearing exactly once
     */
    private List<FilterDefinition> mergePluginBindingsToFilters(String routeId, List<FilterDefinition> existingFilters, List<PluginBindingEntity> globalBindings) {
        List<FilterDefinition> globalPluginFilters = toPluginFilters(routeId, globalBindings, "global");
        List<FilterDefinition> routePluginFilters = toPluginFilters(routeId,
                routePluginBindingService.getEnabledBindingsByRouteId(routeId), "route");

        // SCG applies route filters in list order, so keep the original
        // global-plugins -> authored-filters -> route-plugins sequence.
        List<FilterDefinition> mergedFilters = new ArrayList<>(globalPluginFilters);
        if (existingFilters != null) {
            for (FilterDefinition filter : existingFilters) {
                if (globalPluginFilters.contains(filter) || routePluginFilters.contains(filter)) {
                    continue;
                }
                mergedFilters.add(filter);
            }
        }
        mergedFilters.addAll(routePluginFilters);

        return mergedFilters;
    }

    /**
     * Turn plugin bindings into the filter definitions the gateway expects.
     */
    private List<FilterDefinition> toPluginFilters(String routeId, List<PluginBindingEntity> bindings, String scope) {
        List<FilterDefinition> filters = new ArrayList<>();
        if (bindings == null) {
            return filters;
        }
        for (PluginBindingEntity binding : bindings) {
            FilterDefinition pluginFilter = new FilterDefinition();
            pluginFilter.setName(binding.getPluginName()); // SCG uses plugin name as filter factory name
            pluginFilter.setArgs(routePluginBindingService.parseBindingConfig(binding));
            filters.add(pluginFilter);
            log.debug("Added {} plugin filter to route {}: plugin={}, order={}",
                scope, routeId, binding.getPluginName(), binding.getOrder());
        }
        return filters;
    }

    /**
     * Drop the plugin-binding filters from a client-submitted definition so only operator-authored
     * filters are persisted. They are echoes of what a previous GET synthesized, and
     * {@link #mergePluginBindingsToFilters} re-adds them from the bindings at publish time.
     * <p>
     * Without this the stored definition kept a copy of every plugin filter, so disabling a binding
     * left the filter behind and it stayed published forever.
     */
    private List<FilterDefinition> stripPluginBindingFilters(String routeId, List<FilterDefinition> filters) {
        if (filters == null || filters.isEmpty()) {
            return filters;
        }
        List<FilterDefinition> pluginFilters = new ArrayList<>();
        pluginFilters.addAll(toPluginFilters(routeId,
                routePluginBindingService.getEnabledGlobalBindings(), "global"));
        pluginFilters.addAll(toPluginFilters(routeId,
                routePluginBindingService.getEnabledBindingsByRouteId(routeId), "route"));
        if (pluginFilters.isEmpty()) {
            return filters;
        }

        List<FilterDefinition> authored = new ArrayList<>();
        for (FilterDefinition filter : filters) {
            if (!pluginFilters.contains(filter)) {
                authored.add(filter);
            }
        }
        if (authored.size() != filters.size()) {
            log.info("Dropped {} plugin-binding filter(s) echoed back by the client for route {}; "
                    + "they are re-added from the bindings when publishing", filters.size() - authored.size(), routeId);
        }
        return authored;
    }

    /**
     * Merge plugin bindings into RouteDefinition before publishing to Nacos.
     * This ensures the gateway receives the complete filter list including plugins.
     *
     * @param route the original route definition
     * @param routeId the route ID
     * @return route definition with merged plugin filters
     */
    private RouteDefinition mergePluginBindingsToRoute(RouteDefinition route, String routeId) {
        return mergePluginBindingsToRoute(route, routeId, routePluginBindingService.getEnabledGlobalBindings());
    }

    /**
     * Merge plugin bindings into RouteDefinition with pre-loaded global bindings.
     * Use this overload in batch operations to avoid N+1 queries.
     */
    private RouteDefinition mergePluginBindingsToRoute(RouteDefinition route, String routeId, List<PluginBindingEntity> globalBindings) {
        // Create a copy to avoid modifying original
        RouteDefinition mergedRoute = new RouteDefinition();
        mergedRoute.setId(route.getId());
        mergedRoute.setRouteName(route.getRouteName());
        mergedRoute.setUri(route.getUri());
        mergedRoute.setMode(route.getMode());
        mergedRoute.setServiceId(route.getServiceId());
        mergedRoute.setServiceNamespace(route.getServiceNamespace());
        mergedRoute.setServiceGroup(route.getServiceGroup());
        mergedRoute.setServices(route.getServices());
        mergedRoute.setGrayRules(route.getGrayRules());
        mergedRoute.setOrder(route.getOrder());
        mergedRoute.setPredicates(route.getPredicates());
        mergedRoute.setMetadata(route.getMetadata());

        // Merge filters with plugin bindings
        mergedRoute.setFilters(mergePluginBindingsToFilters(routeId, route.getFilters(), globalBindings));

        return mergedRoute;
    }

    /**
     * Rebuild routes index for a specific namespace.
     * @param nacosNamespace the Nacos namespace to publish the index to (null for default)
     */
    private void rebuildRoutesIndex(String nacosNamespace) {
        try {
            // Query enabled routes once
            List<RouteEntity> enabledRoutes = routeRepository.findByEnabledTrue();

            // Query enabled routes for the specific namespace
            List<String> routeIds;
            if (nacosNamespace != null && !nacosNamespace.isEmpty()) {
                // Batch load instance namespaces to avoid N+1 queries in filter
                Set<String> neededInstanceIds = enabledRoutes.stream()
                    .map(RouteEntity::getInstanceId)
                    .filter(id -> id != null && !id.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
                Map<String, String> instanceNamespaceMap = new HashMap<>();
                if (!neededInstanceIds.isEmpty()) {
                    instanceNamespaceMap = gatewayInstanceRepository.findByInstanceIdIn(new ArrayList<>(neededInstanceIds)).stream()
                        .filter(i -> i.getNacosNamespace() != null)
                        .collect(java.util.stream.Collectors.toMap(
                            GatewayInstanceEntity::getInstanceId,
                            GatewayInstanceEntity::getNacosNamespace,
                            (a, b) -> a
                        ));
                }
                final Map<String, String> nsMap = instanceNamespaceMap;
                routeIds = enabledRoutes.stream()
                    .filter(entity -> nacosNamespace.equals(nsMap.get(entity.getInstanceId())))
                    .map(RouteEntity::getRouteId)
                    .collect(java.util.stream.Collectors.toList());
            } else {
                routeIds = enabledRoutes.stream()
                    .map(RouteEntity::getRouteId)
                    .collect(java.util.stream.Collectors.toList());
            }

            // Load current Nacos index
            List<String> currentIndex = null;
            try {
                currentIndex = configCenterService.getConfig(ROUTES_INDEX, nacosNamespace,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.debug("Routes index does not exist or failed to read: {}", e.getMessage());
            }

            // Check if rebuild needed
            boolean needsRebuild = currentIndex == null || !routeIds.equals(currentIndex);

            if (needsRebuild) {
                log.info("Rebuilding routes index for namespace {} with {} routes",
                    nacosNamespace == null || nacosNamespace.isEmpty() ? "public" : nacosNamespace, routeIds.size());
                configCenterService.publishConfig(ROUTES_INDEX, nacosNamespace, routeIds);
                log.info("Routes index rebuilt for namespace {}", 
                    nacosNamespace == null || nacosNamespace.isEmpty() ? "public" : nacosNamespace);
            }
        } catch (Exception e) {
            log.error("Failed to rebuild routes index", e);
        }
    }

    /**
     * Handle global binding change event.
     * Refresh all enabled routes to apply new global plugin bindings.
     *
     * @param event the global binding change event
     */
    @EventListener
    @Async
    public void onGlobalBindingChanged(GlobalBindingChangedEvent event) {
        log.info("Received global binding change event: type={}, bindingId={}, pluginId={}",
            event.getChangeType(), event.getBindingId(), event.getPluginId());

        try {
            // Manually clear cache since self-invocation (this.refreshAllEnabledRoutes())
            // bypasses Spring AOP proxy, making @CacheEvict ineffective
            var cache = cacheManager.getCache(CacheConfig.CACHE_ROUTES);
            if (cache != null) {
                cache.clear();
            }
            refreshAllEnabledRoutes();
            log.info("All routes refreshed after global binding change: {}", event.getChangeType());
        } catch (Exception e) {
            log.error("Failed to refresh routes after global binding change", e);
        }
    }

    /**
     * Refresh all enabled routes to Nacos.
     * Used when global plugin bindings change to ensure all routes have updated filters.
     */
    @CacheEvict(value = CacheConfig.CACHE_ROUTES, allEntries = true)
    public void refreshAllEnabledRoutes() {
        List<RouteEntity> enabledRoutes = routeRepository.findByEnabledTrue();
        if (enabledRoutes.isEmpty()) {
            log.info("No enabled routes to refresh");
            return;
        }

        log.info("Refreshing {} enabled routes after global binding change", enabledRoutes.size());

        // Pre-load global bindings once to avoid N+1 queries in the loop
        List<PluginBindingEntity> globalBindings = routePluginBindingService.getEnabledGlobalBindings();

        // Batch load only needed gateway instances to avoid loading all instances
        Set<String> neededInstanceIds = enabledRoutes.stream()
                .map(RouteEntity::getInstanceId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, String> instanceNamespaceMap = new HashMap<>();
        if (!neededInstanceIds.isEmpty()) {
            instanceNamespaceMap = gatewayInstanceRepository.findByInstanceIdIn(new ArrayList<>(neededInstanceIds)).stream()
                    .filter(i -> i.getNacosNamespace() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            GatewayInstanceEntity::getInstanceId,
                            GatewayInstanceEntity::getNacosNamespace,
                            (a, b) -> a
                    ));
        }

        int successCount = 0;
        int errorCount = 0;

        for (RouteEntity entity : enabledRoutes) {
            try {
                String nacosNamespace = instanceNamespaceMap.get(entity.getInstanceId());
                String routeDataId = ROUTE_PREFIX + entity.getRouteId();

                RouteDefinition route = routeConverter.toDefinition(entity);
                RouteDefinition mergedRoute = mergePluginBindingsToRoute(route, entity.getRouteId(), globalBindings);

                configCenterService.publishConfig(routeDataId, nacosNamespace, mergedRoute);
                successCount++;
                log.debug("Refreshed route: {} (filters={})", routeDataId,
                    mergedRoute.getFilters() != null ? mergedRoute.getFilters().size() : 0);
            } catch (Exception e) {
                errorCount++;
                log.warn("Failed to refresh route {}: {}", entity.getRouteId(), e.getMessage());
            }
        }

        log.info("Route refresh completed: {} success, {} errors", successCount, errorCount);
    }
}