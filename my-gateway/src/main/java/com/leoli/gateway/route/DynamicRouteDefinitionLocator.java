package com.leoli.gateway.route;

import com.leoli.gateway.manager.RouteManager;
import com.leoli.gateway.refresher.RouteRefresher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

import java.util.Collection;

/**
 * Dynamic route definition locator backed by Config Center.
 * Loads route definitions from Nacos/Consul and caches them for performance.
 * Supports dynamic route refresh when configuration changes.
 *
 * @author leoli
 */
@Slf4j
public class DynamicRouteDefinitionLocator implements RouteDefinitionLocator {

    private final RouteManager routeManager;
    private final RouteRefresher routeRefresher;
    private final ApplicationEventPublisher eventPublisher;

    public DynamicRouteDefinitionLocator(RouteManager routeManager,
                                         RouteRefresher routeRefresher,
                                         ApplicationEventPublisher eventPublisher) {
        this.routeManager = routeManager;
        this.routeRefresher = routeRefresher;
        this.eventPublisher = eventPublisher;
        log.info("DynamicRouteDefinitionLocator initialized with per-route refresh");
    }

    /**
     * Refresh routes manually (called when configuration changes)
     */
    public void refresh() {
        log.info("Refreshing routes from config center");
        // Note: Routes are already updated by RouteRefresher before calling this method.
        // We just need to publish the event to trigger SCG to reload routes from cache.
        // DO NOT clear cache here, otherwise SCG will get empty routes.
        publishRefreshEvent();
    }

    /**
     * Publish RefreshRoutesEvent to notify SCG to reload its internal route cache.
     */
    private void publishRefreshEvent() {
        try {
            eventPublisher.publishEvent(new RefreshRoutesEvent(this));
            log.info("Published RefreshRoutesEvent to SCG");
        } catch (Exception e) {
            log.warn("Failed to publish RefreshRoutesEvent", e);
        }
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        try {
            // Get all routes from RouteManager (per-route cache)
            Collection<RouteDefinition> allRoutes = routeManager.getAllRoutes();

            log.info("Loaded {} route(s) to SCG", allRoutes.size());

            // Always log route details for debugging
            allRoutes.forEach(r -> {
                log.info("  >> Route id={}, uri={}, predicates={}, filters={}",
                        r.getId(), r.getUri(), r.getPredicates(), r.getFilters());
            });

            return Flux.fromIterable(allRoutes);
        } catch (Exception e) {
            log.error("Error loading routes", e);
            return Flux.empty();
        }
    }
}
