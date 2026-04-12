package com.leoli.gateway.autoconfig;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.leoli.gateway.center.nacos.NacosConfigService;
import com.leoli.gateway.discovery.nacos.NacosDiscoveryService;
import com.leoli.gateway.discovery.nacos.NacosNamingServiceFactory;
import jakarta.annotation.PreDestroy;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Nacos auto-configuration for both Config Center and Service Discovery.
 */
@Slf4j
@Setter
@Configuration
@ConfigurationProperties(prefix = "spring.cloud.nacos")
@ConditionalOnProperty(name = "gateway.center.type", havingValue = "nacos", matchIfMissing = true)
public class NacosCenterAutoConfiguration {

    private String namespace;
    private String serverAddr;

    // Hold references for cleanup
    private ConfigService configService;
    private NamingService namingService;

    /**
     * Create Nacos ConfigService bean.
     */
    @Bean
    public ConfigService nacosConfigService() throws Exception {
        Properties props = new Properties();
        props.setProperty("serverAddr", serverAddr != null ? serverAddr : "127.0.0.1:8848");

        // namespace is REQUIRED - gateway instance must have its own namespace for config isolation
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalStateException("Nacos namespace is REQUIRED for gateway instance. " +
                    "Please set NACOS_NAMESPACE environment variable or spring.cloud.nacos.namespace property. " +
                    "Gateway cannot start without namespace isolation.");
        }
        props.setProperty("namespace", namespace);

        log.info("Initializing Nacos ConfigService with server: {} namespace: {}",
                props.getProperty("serverAddr"), namespace);

        this.configService = NacosFactory.createConfigService(props);
        log.info("Nacos ConfigService initialized successfully");
        return this.configService;
    }

    /**
     * Create Nacos NamingService bean.
     */
    @Bean
    public NamingService nacosNamingService() throws Exception {
        String actualServerAddr = serverAddr != null ? serverAddr : "127.0.0.1:8848";
        Properties props = new Properties();
        props.setProperty("serverAddr", actualServerAddr);

        // namespace is REQUIRED - gateway instance must have its own namespace for service discovery isolation
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalStateException("Nacos namespace is REQUIRED for gateway instance. " +
                    "Please set NACOS_NAMESPACE environment variable or spring.cloud.nacos.namespace property. " +
                    "Gateway cannot start without namespace isolation.");
        }
        props.setProperty("namespace", namespace);

        log.info("Initializing Nacos NamingService with server: {} namespace: {}", actualServerAddr, namespace);

        try {
            this.namingService = NacosFactory.createNamingService(props);
            log.info("Nacos NamingService initialized successfully");
            return this.namingService;
        } catch (Exception e) {
            log.error("Failed to create Nacos NamingService. ServerAddr: {}, Namespace: {}. Error: {}",
                    actualServerAddr, namespace, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Create NacosConfigService wrapper bean.
     */
    @Bean
    public NacosConfigService nacosConfigServiceWrapper(ConfigService nacosConfigService) {
        return new NacosConfigService(nacosConfigService);
    }

    /**
     * Create NacosNamingServiceFactory bean for multi-namespace support.
     */
    @Bean
    public NacosNamingServiceFactory nacosNamingServiceFactory() {
        log.info("NacosNamingServiceFactory created for multi-namespace discovery support");
        return new NacosNamingServiceFactory();
    }

    /**
     * Create NacosDiscoveryService wrapper bean with multi-namespace support.
     */
    @Bean
    public NacosDiscoveryService nacosDiscoveryService(NamingService nacosNamingService,
                                                        NacosNamingServiceFactory namingServiceFactory) {
        return new NacosDiscoveryService(nacosNamingService, namingServiceFactory);
    }

    /**
     * Cleanup Nacos resources on shutdown.
     */
    @PreDestroy
    public void destroy() {
        // Shutdown ConfigService
        if (configService != null) {
            try {
                configService.shutDown();
                log.info("Nacos ConfigService shut down successfully");
            } catch (NacosException e) {
                log.warn("Error shutting down Nacos ConfigService: {}", e.getMessage());
            }
        }

        // Shutdown NamingService
        if (namingService != null) {
            try {
                namingService.shutDown();
                log.info("Nacos NamingService shut down successfully");
            } catch (NacosException e) {
                log.warn("Error shutting down Nacos NamingService: {}", e.getMessage());
            }
        }

        log.info("Nacos resources cleanup completed");
    }
}
