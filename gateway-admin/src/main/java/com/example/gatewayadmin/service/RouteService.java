package com.example.gatewayadmin.service;

import com.example.gatewayadmin.center.ConfigCenterService;
import com.example.gatewayadmin.mapper.RouteMapper;
import com.example.gatewayadmin.model.GatewayRoutesConfig;
import com.example.gatewayadmin.model.RouteDefinition;
import com.example.gatewayadmin.properties.GatewayAdminProperties;
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
 * Route configuration service
 *
 * @author leoli
 */
@Slf4j
@Service
public class RouteService {

  private String routesDataId;
  private ConfigCenterPublisher publisher;
  
    @Autowired
  private GatewayAdminProperties properties;
  
    @Autowired
  private ConfigCenterService configCenterService;
  
    @Autowired
  private RouteMapper routeMapper;
  
    // Local cache
  private final ConcurrentHashMap<String, RouteDefinition> routeCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        routesDataId = properties.getNacos().getDataIds().getRoutes();
        publisher=new ConfigCenterPublisher(configCenterService, routesDataId);
        // Load initial route config from config center
        loadRoutesFromConfigCenter();
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
        RouteDefinition route = routeCache.get(id);
     if (route == null) {
            // Cache miss, reload from config center
           log.info("Route not found in cache, reloading from config center: {}", id);
          loadRoutesFromConfigCenter();
          return routeCache.get(id);
        }
      return route;
    }

    /**
     * Create route- with transaction support for dual-write to database and config center
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean createRoute(RouteDefinition route) {
     if (route == null || route.getId() == null || route.getId().isEmpty()) {
            log.warn("Invalid route definition");
        return false;
        }

     if (routeCache.containsKey(route.getId())) {
            log.warn("Route already exists: {}", route.getId());
        return false;
        }

        // 1. Write to H2 database (TODO: Add RouteConverter for Entity conversion)
       log.info("Writing route to database: {}", route.getId());
      // int rows = routeMapper.insert(route); // TODO: Convert RouteDefinition to RouteEntity

        // 2. Update memory cache
       routeCache.put(route.getId(), route);

        // 3. Publish to config center (if fails, transaction will rollback)
       log.info("Publishing route to config center: {}", route.getId());
      boolean success= publisher.publish(new GatewayRoutesConfig(new ArrayList<>(routeCache.values())));
      
   if (!success) {
            throw new RuntimeException("Failed to publish route to config center: " + route.getId());
        }
        
       log.info("Route created successfully: {} (Cache + Config Center)", route.getId());
    return true;
    }

    /**
     * Update route- with transaction support
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean updateRoute(String id, RouteDefinition route) {
      if (route == null || id == null || id.isEmpty()) {
            log.warn("Invalid route definition");
       return false;
        }

    if (!routeCache.containsKey(id)) {
            log.warn("Route not found: {}", id);
       return false;
        }

        // 1. Update H2 database (TODO: Add conversion)
       log.info("Updating route in database: {}", id);
      // int rows = routeMapper.updateById(route); // TODO: Convert to RouteEntity

        // 2. Update memory cache
       route.setId(id);
       routeCache.put(id, route);

        // 3. Publish to config center
       log.info("Publishing updated route to config center: {}", id);
      boolean success= publisher.publish(new GatewayRoutesConfig(new ArrayList<>(routeCache.values())));
      
  if (!success) {
            throw new RuntimeException("Failed to publish route to config center: " + id);
        }
        
       log.info("Route updated successfully: {} (Cache + Config Center)", id);
   return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRoute(String id) {
    if (id == null || id.isEmpty()) {
       return false;
        }

       log.info("Deleting route from database and cache: {}", id);

        // 1. Delete from H2 database (TODO)
      // int rows = routeMapper.deleteById(id); // TODO

        // 2. Remove from memory cache
       routeCache.remove(id);

        // 3. If cache empty, remove config from config center; otherwise publish updated config
    if (routeCache.isEmpty()) {
            log.info("Route cache is empty, removing config from config center: {}", routesDataId);
         boolean result= publisher.remove();
      if (!result) {
                throw new RuntimeException("Failed to remove config from config center");
            }
        } else {
           log.info("Publishing updated routes to config center after deletion");
         boolean result= publisher.publish(new GatewayRoutesConfig(new ArrayList<>(routeCache.values())));
      if (!result) {
                throw new RuntimeException("Failed to publish updated routes to config center");
            }
        }
        
       log.info("Route deleted successfully: {} (Cache + Config Center)", id);
   return true;
    }

    /**
     * Batch create/update routes
     */
    public boolean batchUpdateRoutes(List<RouteDefinition> routes) {
        if (routes == null) {
            return false;
        }

        for (RouteDefinition route : routes) {
            if (route.getId() != null && !route.getId().isEmpty()) {
                routeCache.put(route.getId(), route);
            }
        }

        return publisher.publish(new GatewayRoutesConfig(routes));
    }

    /**
     * Reload route configuration
     */
    public void reloadRoutes() {
        loadRoutesFromConfigCenter();
    }

    /**
     * Load route configuration from config center
     */
  private void loadRoutesFromConfigCenter() {
        try {
            GatewayRoutesConfig config = configCenterService.getConfig(routesDataId, GatewayRoutesConfig.class);
            if (config != null && config.getRoutes() != null) {
                routeCache.clear();
                for (RouteDefinition route : config.getRoutes()) {
                    if (route.getId() != null) {
                        routeCache.put(route.getId(), route);
                    }
                }
                log.info("Loaded {} routes from config center", routeCache.size());
            } else {
                log.info("No routes config found in config center, using empty config");
            }
        } catch (Exception e) {
            log.error("Error loading routes from config center", e);
        }
    }

    /**
     * Find routes by service name
     */
    public List<RouteDefinition> getRoutesByService(String serviceName) {
        return routeCache.values().stream()
                .filter(route -> route.getUri() != null && route.getUri().contains(serviceName))
                .collect(Collectors.toList());
    }

    /**
     * Get route statistics
     */
    public RouteStats getRouteStats() {
        RouteStats stats = new RouteStats();
        stats.setTotalCount(routeCache.size());
        stats.setLbRoutes((int) routeCache.values().stream()
                .filter(r -> r.getUri() != null && r.getUri().startsWith("lb://"))
                .count());
        stats.setHttpRoutes((int) routeCache.values().stream()
                .filter(r -> r.getUri() != null && r.getUri().startsWith("http"))
                .count());
        return stats;
    }

    /**
     * Force refresh cache from config center
     */
    public List<RouteDefinition> refreshFromNacos() {
       log.info("Force refreshing routes from config center");
      loadRoutesFromConfigCenter();
     return getAllRoutes();
    }

    /**
     * Route statistics
     */
    public static class RouteStats {
        private int totalCount;
        private int lbRoutes;
        private int httpRoutes;

        public int getTotalCount() {
            return totalCount;
        }

        public void setTotalCount(int totalCount) {
            this.totalCount = totalCount;
        }

        public int getLbRoutes() {
            return lbRoutes;
        }

        public void setLbRoutes(int lbRoutes) {
            this.lbRoutes = lbRoutes;
        }

        public int getHttpRoutes() {
            return httpRoutes;
        }

        public void setHttpRoutes(int httpRoutes) {
            this.httpRoutes = httpRoutes;
        }
    }
}
