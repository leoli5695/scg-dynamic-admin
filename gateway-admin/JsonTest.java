package com.example.gatewayadmin;

import com.example.gatewayadmin.model.GatewayPluginsConfig;
import com.example.gatewayadmin.model.PluginConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonTest {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper= new ObjectMapper();
        
        // Create a test CircuitBreakerConfig
        PluginConfig.CircuitBreakerConfig cb = new PluginConfig.CircuitBreakerConfig();
        cb.setRouteId("test-route");
        cb.setFailureRateThreshold(50.0f);
        cb.setWaitDurationInOpenState(30000L);
        cb.setEnabled(true);
        
        PluginConfig config = new PluginConfig();
       config.getCircuitBreakers().add(cb);
        
        GatewayPluginsConfig pluginsConfig = new GatewayPluginsConfig(config);
        
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pluginsConfig);
        System.out.println(json);
    }
}
