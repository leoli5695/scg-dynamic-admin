package com.example.mygateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.env.Environment;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

@Slf4j
@Component
public class CustomLoadBalancerGatewayFilterFactory extends AbstractGatewayFilterFactory<CustomLoadBalancerGatewayFilterFactory.Config> {

    private final Environment environment;
    private final ObjectMapper objectMapper;
    
    private static final String GATEWAY_SERVICES_DATA_ID = "gateway-services.json";
    private static final long SERVICES_CACHE_TTL_MS = 10000;
    
    private ServicesConfig cachedServicesConfig;
    private volatile long servicesLastLoadTime = 0;

    public CustomLoadBalancerGatewayFilterFactory(Environment environment) {
        super(Config.class);
        this.environment = environment;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new CustomLoadBalancerClientFilter(config);
    }

    @Override
    public String name() {
        return "CustomLoadBalancer";
    }

    private class CustomLoadBalancerClientFilter implements GatewayFilter {
        private final Config config;

        public CustomLoadBalancerClientFilter(Config config) {
            this.config = config;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            String originalUri = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR).toString();
            
            log.debug("CustomLoadBalancerClientFilter - Original URI: {}", originalUri);
            
            try {
                // Process static:// protocol
                if (originalUri.startsWith("static://")) {
                    log.info("🔍 Processing static:// protocol: {}", originalUri);
                    URI serviceUri = resolveServiceUri(URI.create(originalUri));
                    
                    if (serviceUri == null) {
                        return Mono.error(new NotFoundException("Unable to find instance for service"));
                    }
                    
                    log.info("✅ Resolved static:// -> {}", serviceUri);
                    
                    // Replace URI and continue
                    ServerHttpRequest request = exchange.getRequest().mutate()
                            .uri(serviceUri)
                            .build();
                    
                    return chain.filter(exchange.mutate().request(request).build());
                }
                
                // Other protocols (such as lb://) continue to use original logic
                URI serviceUri = resolveServiceUri(URI.create(originalUri));
                
                if (serviceUri == null) {
                    return Mono.error(new NotFoundException("Unable to find instance for service"));
                }
                
                log.debug("Resolved lb:// -> {}", serviceUri);
                
                ServerHttpRequest request = exchange.getRequest().mutate()
                        .uri(serviceUri)
                        .build();
                
                return chain.filter(exchange.mutate().request(request).build());
                
            } catch (Exception e) {
                log.error("❌ Error resolving service", e);
                return Mono.error(e);
            }
        }
    }

    private URI resolveServiceUri(URI originalUri) {
        if (originalUri == null) return null;
        
        String scheme = originalUri.getScheme();
        String host = originalUri.getHost();
        
        if (host == null || !scheme.matches("(?i)(static|lb)")) {
            return originalUri;
        }
        
        String serviceName = host;
        
        if ("static".equalsIgnoreCase(scheme)) {
            return resolveStaticService(serviceName);
        } else if ("lb".equalsIgnoreCase(scheme)) {
            log.debug("Delegating lb://{} to Spring Cloud LoadBalancer", serviceName);
            return originalUri;
        }
        
        return originalUri;
    }

    private URI resolveStaticService(String serviceName) {
        ServicesConfig cfg = getServicesConfig();
        
        if (cfg == null || cfg.getServices() == null) {
            log.warn("No services config available, cannot resolve static://" + serviceName);
            return null;
        }
        
        for (ServicesConfig.ServiceDefinition service : cfg.getServices().values()) {
            if (serviceName.equalsIgnoreCase(service.getName()) && service.getInstances() != null) {
                for (ServicesConfig.StaticServiceInstance inst : service.getInstances()) {
                    if (inst.isHealthy() && inst.isEnabled()) {
                        String resolved = "http://" + inst.getHost() + ":" + inst.getPort();
                        log.info("Resolved static://" + serviceName + " -> " + resolved);
                        return URI.create(resolved);
                    }
                }
            }
        }
        
        log.warn("No healthy instance found for static://" + serviceName);
        return null;
    }

    private ServicesConfig getServicesConfig() {
        long now = System.currentTimeMillis();
        if (cachedServicesConfig != null && (now - servicesLastLoadTime) < SERVICES_CACHE_TTL_MS) {
            return cachedServicesConfig;
        }
        
        try {
            String serverAddr = environment.getProperty("spring.cloud.nacos.config.server-addr", "127.0.0.1:8848");
            String namespace = environment.getProperty("spring.cloud.nacos.config.namespace", "");
            
            com.alibaba.nacos.client.config.NacosConfigService configService = 
                new com.alibaba.nacos.client.config.NacosConfigService(createNacosProperties(serverAddr, namespace));
            
            String content = configService.getConfig(GATEWAY_SERVICES_DATA_ID, "DEFAULT_GROUP", 5000);
            configService.shutDown();
            
            if (content != null && !content.trim().isEmpty()) {
                cachedServicesConfig = objectMapper.readValue(content, ServicesConfig.class);
                servicesLastLoadTime = now;
                log.debug("Loaded gateway-services.json from Nacos");
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse gateway-services.json", e);
        } catch (Exception e) {
            log.error("Failed to load gateway-services.json from Nacos", e);
        }
        
        return cachedServicesConfig;
    }

    private java.util.Properties createNacosProperties(String serverAddr, String namespace) {
        java.util.Properties props = new java.util.Properties();
        props.put("serverAddr", serverAddr);
        if (namespace != null && !namespace.isEmpty()) {
            props.put("namespace", namespace);
        }
        return props;
    }

    public static class Config {
    }

    public static class ServicesConfig {
        private Map<String, ServiceDefinition> services;
        public Map<String, ServiceDefinition> getServices() { return services; }
        public void setServices(Map<String, ServiceDefinition> services) { this.services = services; }
        
        public static class ServiceDefinition {
            private String name;
            private List<StaticServiceInstance> instances;
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public List<StaticServiceInstance> getInstances() { return instances; }
            public void setInstances(List<StaticServiceInstance> instances) { this.instances = instances; }
        }
        
        public static class StaticServiceInstance implements ServiceInstance {
            private String ip; private int port; private boolean healthy; private boolean enabled;
            @Override public String getServiceId() { return null; }
            @Override public String getHost() { return ip; }
            @Override public int getPort() { return port; }
            @Override public boolean isSecure() { return false; }
            @Override public URI getUri() { return URI.create("http://" + ip + ":" + port); }
            @Override public Map<String, String> getMetadata() { return Collections.emptyMap(); }
            public boolean isHealthy() { return healthy; }
            public boolean isEnabled() { return enabled; }
            public void setIp(String ip) { this.ip = ip; }
            public void setPort(int port) { this.port = port; }
            public void setHealthy(boolean h) { this.healthy = h; }
            public void setEnabled(boolean e) { this.enabled = e; }
        }
    }
}
