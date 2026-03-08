package com.example.gatewayadmin.service;

import com.example.gatewayadmin.config.GatewayAdminProperties;
import com.example.gatewayadmin.config.NacosConfigManager;
import com.example.gatewayadmin.model.GatewayRoutesConfig;
import com.example.gatewayadmin.model.RouteDefinition;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 路由配置服务
 */
@Slf4j
@Service
public class RouteService {

    private String routesDataId;
    private NacosPublisher publisher;
    @Autowired
    private GatewayAdminProperties properties;
    @Autowired
    private NacosConfigManager nacosConfigManager;
    // 本地缓存
    private final ConcurrentHashMap<String, RouteDefinition> routeCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        routesDataId = properties.getNacos().getDataIds().getRoutes();
        publisher = new NacosPublisher(nacosConfigManager, routesDataId);
        // 从 Nacos 加载初始路由配置
        loadRoutesFromNacos();
    }

    /**
     * 获取所有路由
     */
    public List<RouteDefinition> getAllRoutes() {
        return new ArrayList<>(routeCache.values());
    }

    /**
     * 根据ID获取路由
     * 缓存miss时从Nacos查询，确保数据不丢失
     */
    public RouteDefinition getRouteById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }

        // 先从缓存获取
        RouteDefinition route = routeCache.get(id);
        if (route != null) {
            return route;
        }

        // 缓存miss，从Nacos重新加载
        log.info("Route not found in cache, reloading from Nacos: {}", id);
        reloadRoutes();

        return routeCache.get(id);
    }

    /**
     * 创建路由
     */
    public boolean createRoute(RouteDefinition route) {
        if (route == null || route.getId() == null || route.getId().isEmpty()) {
            log.warn("Invalid route definition");
            return false;
        }

        if (routeCache.containsKey(route.getId())) {
            log.warn("Route already exists: {}", route.getId());
            return false;
        }

        routeCache.put(route.getId(), route);
        return publisher.publish(new GatewayRoutesConfig(new ArrayList<>(routeCache.values())));
    }

    /**
     * 更新路由
     */
    public boolean updateRoute(String id, RouteDefinition route) {
        if (route == null || id == null || id.isEmpty()) {
            log.warn("Invalid route definition");
            return false;
        }

        if (!routeCache.containsKey(id)) {
            log.warn("Route not found: {}", id);
            return false;
        }

        route.setId(id);
        routeCache.put(id, route);
        return publisher.publish(new GatewayRoutesConfig(new ArrayList<>(routeCache.values())));
    }

    /**
     * 删除路由
     */
    public boolean deleteRoute(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }

        log.info("Deleting route from cache: {}", id);
        routeCache.remove(id);

        // 如果缓存为空，直接删除 Nacos 配置
        if (routeCache.isEmpty()) {
            log.info("Route cache is empty, removing config from Nacos: {}", routesDataId);
            return publisher.remove();
        }

        // 否则发布更新后的配置
        boolean result = publisher.publish(new GatewayRoutesConfig(new ArrayList<>(routeCache.values())));
        if (result) {
            log.info("Successfully deleted route '{}' and published to Nacos", id);
        } else {
            log.error("Failed to publish route deletion to Nacos for route: {}", id);
        }
        return result;
    }

    /**
     * 批量创建/更新路由
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
     * 重新加载路由配置
     */
    public void reloadRoutes() {
        loadRoutesFromNacos();
    }

    /**
     * 从Nacos加载路由配置
     */
    private void loadRoutesFromNacos() {
        try {
            GatewayRoutesConfig config = nacosConfigManager.getConfig(routesDataId, GatewayRoutesConfig.class);
            if (config != null && config.getRoutes() != null) {
                routeCache.clear();
                for (RouteDefinition route : config.getRoutes()) {
                    if (route.getId() != null) {
                        routeCache.put(route.getId(), route);
                    }
                }
                log.info("Loaded {} routes from Nacos", routeCache.size());
            } else {
                log.info("No routes config found in Nacos, using empty config");
            }
        } catch (Exception e) {
            log.error("Error loading routes from Nacos", e);
        }
    }

    /**
     * 根据服务名查找路由
     */
    public List<RouteDefinition> getRoutesByService(String serviceName) {
        return routeCache.values().stream()
                .filter(route -> route.getUri() != null && route.getUri().contains(serviceName))
                .collect(Collectors.toList());
    }

    /**
     * 获取路由统计信息
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
     * 强制从Nacos刷新缓存
     */
    public List<RouteDefinition> refreshFromNacos() {
        log.info("Force refreshing routes from Nacos");
        loadRoutesFromNacos();
        return getAllRoutes();
    }

    /**
     * 路由统计
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
