package com.example.gateway.autoconfig;

import com.ecwid.consul.v1.ConsulClient;
import com.example.gateway.center.consul.ConsulConfigService;
import com.example.gateway.discovery.consul.ConsulDiscoveryService;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Consul auto-configuration for both Config Center and Service Discovery.
 */
@Slf4j
@Setter
@Configuration
@ConfigurationProperties(prefix = "spring.cloud.consul")
@ConditionalOnProperty(name = "gateway.center.type", havingValue = "consul")
public class ConsulCenterAutoConfiguration {

    private int port;
    private String host;

    /**
     * Create ConsulClient bean.
     */
    @Bean
    public ConsulClient consulClient() {
        ConsulClient client = new ConsulClient(host, port);
        log.info("ConsulClient initialized with host: {}:{} ", host, port);
        return client;
    }

    /**
     * Create ConsulConfigService wrapper bean.
     */
    @Bean
    public ConsulConfigService consulConfigServiceWrapper(ConsulClient consulClient,
                                                          @Value("${spring.cloud.consul.config.prefix:config}") String prefix) {
        return new ConsulConfigService(consulClient, prefix);
    }

    /**
     * Create ConsulDiscoveryService wrapper bean (uses Spring Cloud DiscoveryClient).
     */
    @Bean
    public ConsulDiscoveryService consulDiscoveryService(DiscoveryClient discoveryClient) {
        return new ConsulDiscoveryService(discoveryClient);
    }
}
