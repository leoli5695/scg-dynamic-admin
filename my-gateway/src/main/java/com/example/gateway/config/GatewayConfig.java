package com.example.gateway.config;

import com.example.gateway.cache.GenericCacheManager;
import com.example.gateway.center.spi.ConfigCenterService;
import com.example.gateway.filter.CustomLoadBalancerGatewayFilterFactory;
import com.example.gateway.manager.RouteManager;
import com.example.gateway.manager.ServiceManager;
import com.example.gateway.refresher.RouteRefresher;
import com.example.gateway.refresher.ServiceRefresher;
import com.example.gateway.route.DynamicRouteDefinitionLocator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    @Bean
    public CustomLoadBalancerGatewayFilterFactory customLoadBalancerGatewayFilterFactory(Environment environment) {
        return new CustomLoadBalancerGatewayFilterFactory(environment);
    }

    @Bean
    public DynamicRouteDefinitionLocator dynamicRouteDefinitionLocator(RouteManager routeManager,
                                                                       @Lazy RouteRefresher routeRefresher,
                                                                       ApplicationEventPublisher eventPublisher,
                                                                       GenericCacheManager<JsonNode> cacheManager,
                                                                       ConfigCenterService configService) {
        return new DynamicRouteDefinitionLocator(routeManager, routeRefresher, eventPublisher, cacheManager, configService);
    }
}
