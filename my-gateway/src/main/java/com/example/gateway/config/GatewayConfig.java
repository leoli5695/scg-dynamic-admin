package com.example.gateway.config;

import com.example.gateway.filter.CustomLoadBalancerGatewayFilterFactory;
import com.example.gateway.route.NacosRouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class GatewayConfig {

    @Bean
    public RouteDefinitionLocator nacosRouteDefinitionLocator(Environment environment) {
        return new NacosRouteDefinitionLocator(environment);
    }

    @Bean
    public CustomLoadBalancerGatewayFilterFactory customLoadBalancerGatewayFilterFactory(Environment environment) {
        return new CustomLoadBalancerGatewayFilterFactory(environment);
    }
}
