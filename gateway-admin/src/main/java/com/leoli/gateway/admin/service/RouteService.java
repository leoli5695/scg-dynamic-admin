package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.converter.RouteConverter;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.model.RouteResponse;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.validation.RouteValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Route configuration service with per-route incremental refresh.
 * Each route is stored in Nacos as an independent key: config/gateway/routes/route-{routeId}
 * 
 * @author leoli
 */
@Slf4j
@Service
public class RouteService {

  private static final String ROUTE_PREFIX = "config.gateway.route-";
  private static final String ROUTES_INDEX = "config.gateway.metadata.routes-index";
  private static final String GROUP = "DEFAULT_GROUP";
  
  private final ObjectMapper objectMapper = new ObjectMapper();
  
  @Autowired
  private GatewayAdminProperties properties;
  
  @Autowired
  private ConfigCenterService configCenterService;
  
  @Autowired
  private RouteRepository routeRepository;
  
  @Autowired
  private RouteConverter routeConverter;

  @PostConstruct
  public void init() {
    // Load initial routes from database and sync enabled ones to Nacos
    loadRoutesFromDatabase();
    log.info("RouteService initialized with per-route Nacos storage");
  }

  /**
   * Load routes from H2 database on startup and recover missing configs in Nacos.
   * Only pushes to Nacos if config is missing (to avoid unnecessary Gateway refresh).
   */
  private void loadRoutesFromDatabase() {
    try {
      // Load ALL routes from database (including disabled ones)
      List<RouteEntity> entities = routeRepository.findAll();
      if (entities != null && !entities.isEmpty()) {
        long enabledCount = entities.stream().filter(RouteEntity::getEnabled).count();
        long disabledCount = entities.size() - enabledCount;
        
        log.info("Loaded {} routes from database ({} enabled, {} disabled)", 
            entities.size(), enabledCount, disabledCount);
        
        // Check and recover only ENABLED routes that are missing in Nacos
        int recoveredCount = 0;
        for (RouteEntity entity : entities) {
          if (!entity.getEnabled()) {
            continue; // Skip disabled routes
          }
          
          String routeDataId = ROUTE_PREFIX + entity.getRouteId();
          
          // Check if route config exists in Nacos
          if (!configCenterService.configExists(routeDataId)) {
            // Config is missing, recover it
            RouteDefinition route = toDefinition(entity);
            configCenterService.publishConfig(routeDataId, route);
            recoveredCount++;
            log.info("Recovered missing route in Nacos: {}", routeDataId);
          }
        }
        
        if (recoveredCount > 0) {
          log.info("Recovered {} missing routes in Nacos on startup", recoveredCount);
          // Update routes index after recovery
          updateRoutesIndex(getAllRouteIds());
        } else {
          log.info("All enabled routes are already in Nacos, no recovery needed");
        }
        
      } else {
        log.info("No routes found in database (table is empty)");
      }
    } catch (Exception e) {
      log.warn("Failed to load routes from database: {}", e.getMessage());
    }
  }
  /**
   * Get all routes with routeId (UUID).
   */
  public List<RouteResponse> getAllRoutes() {
    // Query from database instead of cache
    List<RouteEntity> entities = routeRepository.findAll();
    return entities.stream()
        .map(entity -> {
          RouteDefinition def = toDefinition(entity);
          RouteResponse response = new RouteResponse();
          response.setId(entity.getRouteId());  // UUID as primary key
          response.setRouteName(def.getId());  // route_name as business field
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
        })
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * Get route configuration by ID
   */
  public RouteDefinition getRoute(String id) {
    // Query from database by route_id (UUID)
    RouteEntity entity = routeRepository.findByRouteId(id);
    if (entity == null) {
      return null;
    }
    return toDefinition(entity);
  }

  /**
   * Get route configuration by route name (business identifier)
   */
  public RouteDefinition getRouteByName(String routeName) {
    // Query from database
    RouteEntity entity = routeRepository.findByRouteName(routeName);
    if (entity == null) {
      return null;
    }
    return toDefinition(entity);
  }

  /**
   * Create route with dual-write to database and Nacos (per-route format).
   * Description is saved to database only, not pushed to Nacos metadata.
   */
  @Transactional(rollbackFor = Exception.class)
  public RouteEntity createRoute(RouteDefinition route) {
    // Validate route definition
    RouteValidator.validateAndThrow(route);

    // Use route ID as business route name
    String routeName = route.getId();
    
    // Check if route already exists in database
    RouteEntity existing = routeRepository.findByRouteName(routeName);
    if (existing != null) {
      throw new IllegalArgumentException("Route already exists: " + routeName);
    }

    // ✅ Note: RouteDefinition no longer has description field
    // Description is only stored in RouteEntity for UI display

    // 1. Convert to entity and save to H2 database
    log.info("Saving route to database: {}", routeName);
    RouteEntity entity = routeConverter.toEntity(route);
    entity.setRouteName(routeName);
    entity.setRouteId(java.util.UUID.randomUUID().toString());
    entity.setDescription(route.getDescription()); // Save description to database only
    entity = routeRepository.save(entity);
    
    log.info("Route saved with DB id={}, route_name={}", 
             entity.getId(), entity.getRouteName());

    // 2. Push to Nacos (per-route format using route_id UUID)
    String routeDataId = ROUTE_PREFIX + entity.getRouteId();
    configCenterService.publishConfig(routeDataId, route);
    log.info("Route pushed to Nacos: {}", routeDataId);

    // 3. Update routes index
    updateRoutesIndex(getAllRouteIds());
    
    log.info("Route created successfully: {} (Database + Nacos)", routeName);
    return entity;
  }

  /**
   * Update route with dual-write to database and Nacos (per-route format).
   */
  @Transactional(rollbackFor = Exception.class)
  public RouteEntity updateRoute(Long id, RouteDefinition route) {
    if (route == null || id == null) {
      throw new IllegalArgumentException("Invalid route definition or ID");
    }

    RouteEntity entity = routeRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Route not found with DB id: " + id));

    // ✅ Note: RouteDefinition no longer has description field
    // Keep existing description in database unchanged
    
    // Store configuration as JSON in metadata field for backup
    try {
      String configJson = objectMapper.writeValueAsString(route);
      entity.setMetadata(configJson);
    } catch (Exception e) {
      log.warn("Failed to serialize route config to JSON", e);
    }

    // 1. Update database
    log.info("Updating route in database: {}", entity.getRouteName());
    entity = routeRepository.save(entity);

    // 2. Push to Nacos (overwrite per-route key using route_id UUID)
    String routeDataId = ROUTE_PREFIX + entity.getRouteId();
    configCenterService.publishConfig(routeDataId, route);
    log.info("Route updated in Nacos: {}", routeDataId);

    // Note: No need to update index since routeName didn't change
    
    log.info("Route updated successfully: {} (Database + Nacos)", entity.getRouteName());
    return entity;
  }

  @Transactional(rollbackFor = Exception.class)
  public RouteEntity updateRouteByRouteId(String routeId, RouteDefinition route) {
    // Validate route definition
    RouteValidator.validateAndThrow(route);

    if (routeId == null) {
      throw new IllegalArgumentException("Route ID cannot be null");
    }

    RouteEntity entity = routeRepository.findByRouteId(routeId);
    if (entity == null) {
      throw new IllegalArgumentException("Route not found with route_id: " + routeId);
    }

    // ✅ Note: RouteDefinition no longer has description field
    // Keep existing description in database unchanged
    
    // Store configuration as JSON in metadata field for backup
    try {
      String configJson = objectMapper.writeValueAsString(route);
      entity.setMetadata(configJson);
    } catch (Exception e) {
      log.warn("Failed to serialize route config to JSON", e);
    }

    // 1. Update database
    log.info("Updating route in database: {}", entity.getRouteName());
    entity = routeRepository.save(entity);

    // 2. Push to Nacos (overwrite per-route key using route_id UUID)
    String routeDataId = ROUTE_PREFIX + entity.getRouteId();
    configCenterService.publishConfig(routeDataId, route);
    log.info("Route updated in Nacos: {}", routeDataId);

    // Note: No need to update index since routeName didn't change
    
    log.info("Route updated successfully: {} (Database + Nacos)", entity.getRouteName());
    return entity;
  }

  /**
   * Delete route from database and Nacos (per-route format).
   */
  @Transactional(rollbackFor = Exception.class)
  public void deleteRoute(Long id) {
    RouteEntity entity = routeRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Route not found with DB id: " + id));
    
    String routeName = entity.getRouteName();
    String routeId = entity.getRouteId();
    log.info("Deleting route: {} (DB id: {}, route_id: {})", routeName, id, routeId);

    // 1. Delete from Nacos (remove config using route_id UUID)
    String routeDataId = ROUTE_PREFIX + entity.getRouteId();
    configCenterService.removeConfig(routeDataId);
    log.info("Route removed from Nacos: {}", routeDataId);

    // 2. Delete from database
    routeRepository.delete(entity);
    log.info("Route deleted from database: {}", id);

    // 3. Update routes index
    updateRoutesIndex(getAllRouteIds());
    
    log.info("Route deleted successfully: {} (Database + Nacos)", routeName);
  }

  /**
   * Enable a route (persist to database and publish to Nacos).
   */
  @Transactional(rollbackFor = Exception.class)
  public void enableRoute(Long id) {
    RouteEntity entity = routeRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Route not found with DB id: " + id));
    
    if (entity.getEnabled()) {
      log.warn("Route is already enabled: {} (DB id: {})", entity.getRouteName(), id);
      return;
    }

    // 1. Update database
    entity.setEnabled(true);
    routeRepository.save(entity);
    log.info("Route enabled in database: {} (DB id: {})", entity.getRouteName(), id);

    // 2. Restore route definition from metadata
    RouteDefinition route = toDefinition(entity);
    
    // 3. Publish to Nacos (this will trigger Gateway to add the route)
    String routeDataId = ROUTE_PREFIX + entity.getRouteId();
    configCenterService.publishConfig(routeDataId, route);
    log.info("Route published to Nacos: {}", routeDataId);

    // 4. Update routes index
    updateRoutesIndex(getAllRouteIds());
    
    log.info("Route enabled successfully: {} (Database + Nacos)", entity.getRouteName());
  }

  /**
   * Enable a route by route_id (UUID).
   */
  @Transactional(rollbackFor = Exception.class)
  public void enableRouteByRouteId(String routeId) {
    RouteEntity entity = routeRepository.findByRouteId(routeId);
    if (entity == null) {
      throw new IllegalArgumentException("Route not found with route_id: " + routeId);
    }
    enableRoute(entity.getId());
  }

  /**
   * Disable a route (update database and remove from Nacos).
   */
  @Transactional(rollbackFor = Exception.class)
  public void disableRoute(Long id) {
    RouteEntity entity = routeRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Route not found with DB id: " + id));
    
    if (!entity.getEnabled()) {
      log.warn("Route is already disabled: {} (DB id: {})", entity.getRouteName(), id);
      return;
    }

    // 1. Update database
    entity.setEnabled(false);
    routeRepository.save(entity);
    log.info("Route disabled in database: {} (DB id: {})", entity.getRouteName(), id);

    // 2. Remove from Nacos (this will trigger Gateway to remove the route)
    // Keep it in cache for quick re-enable
    String routeDataId = ROUTE_PREFIX + entity.getRouteId();
    configCenterService.removeConfig(routeDataId);
    log.info("Route removed from Nacos: {}", routeDataId);

    // 3. Update routes index
    updateRoutesIndex(getAllRouteIds());
    
    log.info("Route disabled successfully: {} (Database + Nacos)", entity.getRouteName());
  }

  /**
   * Disable a route by route_id (UUID).
   */
  @Transactional(rollbackFor = Exception.class)
  public void disableRouteByRouteId(String routeId) {
    RouteEntity entity = routeRepository.findByRouteId(routeId);
    if (entity == null) {
      throw new IllegalArgumentException("Route not found with route_id: " + routeId);
    }
    disableRoute(entity.getId());
  }

  /**
   * Delete route by route name (business identifier).
   */
  @Transactional(rollbackFor = Exception.class)
  public void deleteRouteByName(String routeName) {
    RouteEntity entity = routeRepository.findByRouteName(routeName);
    if (entity == null) {
      throw new IllegalArgumentException("Route not found: " + routeName);
    }
    
    deleteRoute(entity.getId());
  }

  /**
   * Delete route by route_id (UUID).
   */
  @Transactional(rollbackFor = Exception.class)
  public void deleteRouteByRouteId(String routeId) {
    // Find entity by route_id
    List<RouteEntity> entities = routeRepository.findByEnabledTrue();
    RouteEntity targetEntity = entities.stream()
        .filter(e -> routeId.equals(e.getRouteId()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
    
    deleteRoute(targetEntity.getId());
  }

  /**
   * Get all route IDs from cache.
   */
  private List<String> getAllRouteIds() {
    // Query from database
    List<RouteEntity> entities = routeRepository.findAll();
    return entities.stream()
        .map(RouteEntity::getRouteId)
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * Update routes index in Nacos.
   */
  private void updateRoutesIndex(List<String> routeIds) {
    try {
      // Publish as JSON array directly, not stringified JSON
      configCenterService.publishConfig(ROUTES_INDEX, routeIds);
      log.debug("Routes index updated: {} routes", routeIds.size());
    } catch (Exception e) {
      log.error("Failed to update routes index", e);
    }
  }

  /**
   * Convert RouteDefinition to JSON string.
   */
  private String toJson(RouteDefinition route) {
    try {
      return objectMapper.writeValueAsString(route);
    } catch (Exception e) {
      throw new RuntimeException("Failed to convert route to JSON", e);
    }
  }

  /**
   * Convert RouteDefinition to RouteEntity.
   * Stores complete configuration as JSON for backup purposes.
   */
  private RouteEntity toEntity(RouteDefinition route) {
    RouteEntity entity = new RouteEntity();
    entity.setRouteName(route.getId());
    // ✅ Note: RouteDefinition no longer has description field
    entity.setDescription(null);
    
    // Store complete configuration as JSON in metadata field for backup
    try {
      String configJson = objectMapper.writeValueAsString(route);
      entity.setMetadata(configJson);
    } catch (Exception e) {
      log.warn("Failed to serialize route config to JSON", e);
    }
    
    return entity;
  }

  /**
   * Convert RouteEntity to RouteDefinition.
   * Restores complete configuration from JSON backup.
   */
  private RouteDefinition toDefinition(RouteEntity entity) {
    // Try to restore from JSON backup first
    if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
      try {
        RouteDefinition route = objectMapper.readValue(entity.getMetadata(), RouteDefinition.class);
        if (route != null && route.getUri() != null && !route.getUri().isEmpty()) {
          // Valid route with URI
          return route;
        }
      } catch (Exception e) {
        log.warn("Failed to deserialize route config from JSON, using fallback", e);
      }
    }
    
    // Fallback: create minimal definition from database fields
    RouteDefinition route = new RouteDefinition();
    route.setId(entity.getRouteName());
    
    // Note: If metadata is corrupted, we can't recover predicates/filters/uri
    // This should only happen in development/testing scenarios
    log.warn("Route metadata is missing or invalid for route: {} (DB id: {}). Using partial definition.", 
        entity.getRouteName(), entity.getId());
    
    return route;
  }
}
