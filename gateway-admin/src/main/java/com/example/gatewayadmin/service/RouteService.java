package com.example.gatewayadmin.service;

import com.example.gatewayadmin.center.ConfigCenterService;
import com.example.gatewayadmin.converter.RouteConverter;
import com.example.gatewayadmin.model.RouteDefinition;
import com.example.gatewayadmin.model.RouteEntity;
import com.example.gatewayadmin.properties.GatewayAdminProperties;
import com.example.gatewayadmin.repository.RouteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Route configuration service with per-route incremental refresh.
 * Each route is stored in Nacos as an independent key: config/gateway/routes/route-{routeId}
 * 
 * @author leoli
 */
@Slf4j
@Service
public class RouteService {

  private static final String ROUTE_PREFIX = "config/gateway/routes/route-";
  private static final String ROUTES_INDEX = "config/gateway/metadata/routes-index";
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
  
  // Local cache
  private final ConcurrentHashMap<String, RouteDefinition> routeCache = new ConcurrentHashMap<>();

  @PostConstruct
  public void init() {
    // Load initial routes from database
    loadRoutesFromDatabase();
    log.info("RouteService initialized with per-route Nacos storage");
  }

  /**
   * Load routes from H2 database and sync to Nacos.
   */
  private void loadRoutesFromDatabase() {
    try {
      List<RouteEntity> entities = routeRepository.findAllByOrderByOrderNumAsc();
      if (entities != null && !entities.isEmpty()) {
        List<RouteDefinition> definitions = routeConverter.toDefinitions(entities);
        for (RouteDefinition def : definitions) {
          routeCache.put(def.getId(), def);
        }
        log.info("Loaded {} routes from database", entities.size());
        
        // Sync all routes to Nacos on startup (for migration from old format)
        syncAllRoutesToNacos();
      } else {
        log.info("No routes found in database (table is empty)");
      }
    } catch (Exception e) {
      log.warn("Failed to load routes from database: {}", e.getMessage());
    }
  }
  
  /**
   * Sync all routes from database to Nacos (per-route format).
   * Used for initial migration or recovery.
   */
  private void syncAllRoutesToNacos() {
    log.info("Syncing all routes to Nacos...");
    List<String> routeIds = new ArrayList<>();
    
    for (RouteDefinition route : routeCache.values()) {
      String routeDataId = ROUTE_PREFIX + route.getId();
      String routeJson = toJson(route);
      
      try {
        configCenterService.publishConfig(routeDataId, routeJson);
        routeIds.add(route.getId());
        log.debug("Pushed route to Nacos: {}", routeDataId);
      } catch (Exception e) {
        log.error("Failed to push route to Nacos: {}", routeDataId, e);
      }
    }
    
    // Update routes index
    updateRoutesIndex(routeIds);
    log.info("Synced {} routes to Nacos", routeIds.size());
  }

  /**
   * Get all routes
   */
  public List<RouteDefinition> getAllRoutes() {
    return new ArrayList<>(routeCache.values());
  }

  /**
   * Get route configuration by ID
   */
  public RouteDefinition getRoute(String id) {
    return routeCache.get(id);
  }

  /**
   * Create route with dual-write to database and Nacos (per-route format).
   */
  @Transactional(rollbackFor = Exception.class)
  public RouteEntity createRoute(RouteDefinition route) {
    if (route == null || route.getId() == null || route.getId().isEmpty()) {
      throw new IllegalArgumentException("Invalid route definition");
    }

    if (routeCache.containsKey(route.getId())) {
      throw new IllegalArgumentException("Route already exists: " + route.getId());
    }

    // 1. Convert to entity and save to H2 database
    log.info("Saving route to database: {}", route.getId());
    RouteEntity entity = routeConverter.toEntity(route);
    entity = routeRepository.save(entity);

    // 2. Update memory cache
    routeCache.put(route.getId(), route);
    log.info("Route cached in memory: {}", route.getId());

    // 3. Push to Nacos (per-route format)
    String routeDataId = ROUTE_PREFIX + route.getId();
    String routeJson = toJson(route);
    configCenterService.publishConfig(routeDataId, routeJson);
    log.info("Route pushed to Nacos: {}", routeDataId);

    // 4. Update routes index
    updateRoutesIndex(getAllRouteIds());
    
    log.info("Route created successfully: {} (Database + Cache + Nacos)", route.getId());
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

    RouteEntity entity = routeRepository.findById(String.valueOf(id))
        .orElseThrow(() -> new IllegalArgumentException("Route not found with ID: " + id));

    // 1. Update database fields
    log.info("Updating route in database: {}", route.getId());
    entity.setUri(route.getUri().toString());
    entity.setOrderNum(route.getOrder());
    // Note: predicates, filters, metadata are not persisted to DB in current design
    entity = routeRepository.save(entity);

    // 2. Update memory cache
    routeCache.put(route.getId(), route);
    log.info("Route updated in cache: {}", route.getId());

    // 3. Push to Nacos (overwrite per-route key)
    String routeDataId = ROUTE_PREFIX + route.getId();
    String routeJson = toJson(route);
    configCenterService.publishConfig(routeDataId, routeJson);
    log.info("Route updated in Nacos: {}", routeDataId);

    // Note: No need to update index since routeId didn't change
    
    log.info("Route updated successfully: {} (Database + Cache + Nacos)", route.getId());
    return entity;
  }

  /**
   * Delete route from database and Nacos (per-route format).
   */
  @Transactional(rollbackFor = Exception.class)
  public void deleteRoute(Long id) {
    RouteEntity entity = routeRepository.findById(String.valueOf(id))
        .orElseThrow(() -> new IllegalArgumentException("Route not found with ID: " + id));
    
    String routeId = entity.getId();
    log.info("Deleting route: {} (ID: {})", routeId, id);

    // 1. Remove from cache first (to prevent concurrent access)
    routeCache.remove(routeId);
    log.info("Route removed from cache: {}", routeId);

    // 2. Delete from Nacos (push empty content)
    String routeDataId = ROUTE_PREFIX + routeId;
    configCenterService.publishConfig(routeDataId, "");
    log.info("Route deleted from Nacos: {}", routeDataId);

    // 3. Delete from database
    routeRepository.delete(entity);
    log.info("Route deleted from database: {}", id);

    // 4. Update routes index
    updateRoutesIndex(getAllRouteIds());
    
    log.info("Route deleted successfully: {} (Database + Cache + Nacos)", routeId);
  }

  /**
   * Get all route IDs from cache.
   */
  private List<String> getAllRouteIds() {
    return new ArrayList<>(routeCache.keySet());
  }

  /**
   * Update routes index in Nacos.
   */
  private void updateRoutesIndex(List<String> routeIds) {
    try {
      String indexJson = objectMapper.writeValueAsString(routeIds);
      configCenterService.publishConfig(ROUTES_INDEX, indexJson);
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
}
