package com.leoli.gateway.config;

import com.leoli.gateway.manager.RouteManager;
import com.leoli.gateway.refresher.RouteRefresher;
import com.leoli.gateway.route.DynamicRouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    @Bean
    public DynamicRouteDefinitionLocator dynamicRouteDefinitionLocator(RouteManager routeManager,
                                                                       @Lazy RouteRefresher routeRefresher,
                                                                       ApplicationEventPublisher eventPublisher) {
        return new DynamicRouteDefinitionLocator(routeManager, routeRefresher, eventPublisher);
    }
}