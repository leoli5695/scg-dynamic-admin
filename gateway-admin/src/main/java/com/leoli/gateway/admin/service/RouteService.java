package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.config.CacheConfig;
import com.leoli.gateway.admin.converter.RouteConverter;
import com.leoli.gateway.admin.metrics.BusinessMetrics;
import com.leoli.gateway.admin.metrics.TracingHelper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.RouteDefinition;
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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

            // Batch load all gateway instances to avoid N+1 queries
            Map<String, String> instanceNamespaceMap = gatewayInstanceRepository.findAll().stream()
                    .filter(i -> i.getNacosNamespace() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            GatewayInstanceEntity::getInstanceId,
                            GatewayInstanceEntity::getNacosNamespace,
                            (a, b) -> a // handle duplicates
                    ));

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
                    configCenterService.publishConfig(routeDataId, nacosNamespace, route);
                    recoveredCount++;
                    log.info("Recovered missing route in Nacos: {} (namespace: {})", routeDataId, nacosNamespace);
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
     * Get all routes.
     */
    public List<RouteResponse> getAllRoutes() {
        List<RouteEntity> entities = routeRepository.findAll();
        return entities.stream()
                .map(this::toResponse)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get all routes for a specific instance.
     */
    public List<RouteResponse> getAllRoutesByInstanceId(String instanceId) {
        List<RouteEntity> entities = routeRepository.findByInstanceId(instanceId);
        return entities.stream()
                .map(this::toResponse)
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
            RouteEntity entity = routeConverter.toEntity(route);
            entity.setEnabled(true);
            entity.setInstanceId(instanceId);
            entity = routeRepository.save(entity);
            log.info("Route saved to database: id={}, name={}, instanceId={}", entity.getRouteId(), entity.getRouteName(), instanceId);

            // Push to Nacos (with namespace)
            String routeDataId = ROUTE_PREFIX + entity.getRouteId();
            long startTime = System.currentTimeMillis();
            try {
                configCenterService.publishConfig(routeDataId, nacosNamespace, route);
                businessMetrics.recordConfigPublish();
                businessMetrics.recordConfigPublishDuration(System.currentTimeMillis() - startTime);
                log.info("Route pushed to Nacos: {} (namespace: {})", routeDataId, nacosNamespace);
            } catch (Exception e) {
                businessMetrics.recordConfigPublishError();
                throw e;
            }

            // Rebuild routes index for this namespace
            rebuildRoutesIndex(nacosNamespace);

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

        // Validate
        RouteValidator.validateAndThrow(route);

        // Get Nacos namespace from instance
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());

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
            String routeDataId = ROUTE_PREFIX + entity.getRouteId();
            configCenterService.publishConfig(routeDataId, nacosNamespace, route);
            log.info("Route updated in Nacos: {} (namespace: {})", routeDataId, nacosNamespace);
        }

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

            // Remove from Nacos (with namespace)
            String routeDataId = ROUTE_PREFIX + entity.getRouteId();
            configCenterService.removeConfig(routeDataId, nacosNamespace);
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

            // Push to Nacos (with namespace)
            RouteDefinition route = routeConverter.toDefinition(entity);
            String routeDataId = ROUTE_PREFIX + entity.getRouteId();
            configCenterService.publishConfig(routeDataId, nacosNamespace, route);

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
        RouteDefinition def = routeConverter.toDefinition(entity);
        RouteResponse response = new RouteResponse();
        response.setId(entity.getRouteId());
        response.setRouteName(entity.getRouteName());
        response.setUri(def.getUri());
        response.setMode(def.getMode());
        response.setServiceId(def.getServiceId());
        response.setServices(def.getServices());
        response.setGrayRules(def.getGrayRules());
        response.setOrder(def.getOrder());
        response.setPredicates(def.getPredicates());
        response.setFilters(def.getFilters());
        response.setMetadata(def.getMetadata());
        response.setEnabled(entity.getEnabled());
        response.setDescription(entity.getDescription());
        return response;
    }

    /**
     * Rebuild routes index for a specific namespace.
     * @param nacosNamespace the Nacos namespace to publish the index to (null for default)
     */
    private void rebuildRoutesIndex(String nacosNamespace) {
        try {
            // Query enabled routes for the specific namespace
            List<String> routeIds;
            if (nacosNamespace != null && !nacosNamespace.isEmpty()) {
                routeIds = routeRepository.findByEnabledTrue().stream()
                    .filter(entity -> nacosNamespace.equals(getNacosNamespace(entity.getInstanceId())))
                    .map(RouteEntity::getRouteId)
                    .collect(java.util.stream.Collectors.toList());
            } else {
                routeIds = routeRepository.findByEnabledTrue().stream()
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
}