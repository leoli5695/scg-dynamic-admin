package com.example.mygateway.config;

import com.example.mygateway.filter.CustomLoadBalancerGatewayFilterFactory;
import com.example.mygateway.route.NacosRouteDefinitionLocator;
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
