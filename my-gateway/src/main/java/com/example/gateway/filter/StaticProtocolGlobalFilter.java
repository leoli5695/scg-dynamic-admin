package com.example.gateway.filter;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Properties;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/**
 * Global filter for static:// protocol
 * Resolves static service names to real HTTP addresses via gateway-services.json in Nacos.
 *
 * @author leoli
 */
@Slf4j
@Component
public class StaticProtocolGlobalFilter implements GlobalFilter, Ordered {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConfigService configService;
    
    // Service configuration cache
    private ServicesConfig cachedServicesConfig;
    private volatile long servicesLastLoadTime = 0;
    private static final long SERVICES_CACHE_TTL_MS = 10000;
    
    static {
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public StaticProtocolGlobalFilter() {
        try {
            // Get Nacos configuration from environment variables or default values
            String serverAddr = System.getProperty("spring.cloud.nacos.config.server-addr", "127.0.0.1:8848");
            String namespace = System.getProperty("spring.cloud.nacos.config.namespace", "");
            
            Properties props = new Properties();
            props.setProperty("serverAddr", serverAddr);
            if (!namespace.isEmpty()) {
                props.setProperty("namespace", namespace);
            }
            
            this.configService = NacosFactory.createConfigService(props);
            log.info("StaticProtocolGlobalFilter - Nacos ConfigService initialized: {}", serverAddr);
            
            // Add listener to clear cache when configuration is deleted
            configService.addListener("gateway-services.json", "DEFAULT_GROUP", new com.alibaba.nacos.api.config.listener.Listener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("Received gateway-services.json update");
                    if (configInfo == null || configInfo.trim().isEmpty()) {
                        log.info("Configuration deleted or empty, clearing cache");
                        clearCache();
                    } else {
                        // Configuration updated, will reload on next request
                        cachedServicesConfig = null;
                        servicesLastLoadTime = 0;
                    }
                }
                
                @Override
                public java.util.concurrent.Executor getExecutor() {
                    return null; // Use default executor
                }
            });
            
        } catch (Exception e) {
            log.error("Failed to initialize Nacos config service", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Get route object
        Object routeObj = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        
        log.debug("StaticProtocolGlobalFilter - Route object: {}", routeObj);
        log.debug("StaticProtocolGlobalFilter - All attributes count: {}", exchange.getAttributes().size());
        
        URI routeUri = null;
        if (routeObj != null) {
            // Get URI from Route object
            try {
                // Get uri property of Route object using reflection
                java.lang.reflect.Method getUriMethod = routeObj.getClass().getMethod("getUri");
                routeUri = (URI) getUriMethod.invoke(routeObj);
            } catch (Exception e) {
                log.error("Failed to get URI from Route object", e);
            }
        }
        
        log.debug("StaticProtocolGlobalFilter - Route URI: {}", routeUri);
        
        if (routeUri != null && "static".equalsIgnoreCase(routeUri.getScheme())) {
            log.info("妫ｅ啯鏁?Intercepting static:// protocol for route: {}", routeUri);
            try {
                URI resolvedUri = resolveServiceUri(routeUri);
                if (resolvedUri != null) {
                    // Get original request path (after StripPrefix processing)
                    URI requestUri = exchange.getRequest().getURI();
                    String path = requestUri.getPath();
                                
                    // Extract IP and port from resolved URI
                    String instanceIp = resolvedUri.getHost();
                    int instancePort = resolvedUri.getPort();
                                
                    // Build complete HTTP URL: http://127.0.0.1:8080/hello
                    URI finalUri = new URI(
                        "http",  // Force use of http protocol
                        null,    // userInfo
                        instanceIp,  // host
                        instancePort, // port
                        path,    // path
                        requestUri.getQuery(), // query
                        requestUri.getFragment() // fragment
                    );
                                
                    log.info("闁?Resolved static://{} -> {} (with path: {})", routeUri.getHost(), finalUri, path);
                                
                    // Directly set final HTTP URL to avoid RouteToRequestUrlFilter processing again
                    exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, finalUri);
                                
                    log.debug("Set GATEWAY_REQUEST_URL_ATTR to: {}", finalUri);
                                 
                    return chain.filter(exchange);
                } else {
                    log.error("闁?Failed to resolve static://{}", routeUri.getHost());
                    return Mono.error(new NotFoundException("Unable to find instance for service: " + routeUri.getHost()));
                }
            } catch (Exception e) {
                log.error("闁?Error resolving static protocol", e);
                return Mono.error(e);
            }
        } else if (routeUri != null && "lb".equalsIgnoreCase(routeUri.getScheme())) {
            log.debug("lb:// protocol detected, will use SCG built-in load balancer: {}", routeUri);
        } else {
            log.debug("Not a static/lb:// protocol or route URI not available. Route URI: {}", routeUri);
        }
        
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 10001; // Execute after RouteToRequestUrlFilter (order=10000) to avoid overwriting
    }
    
    /**
     * Resolve service URI to real HTTP address (supports static:// and lb:// protocols)
     */
    private URI resolveServiceUri(URI uri) throws Exception {
        String serviceName = uri.getHost();
        String scheme = uri.getScheme();
        log.debug("Resolving service: {} (scheme: {})", serviceName, scheme);
        
        // If static:// protocol, get from gateway-services.json configuration
        if ("static".equalsIgnoreCase(scheme)) {
            return resolveFromGatewayServices(serviceName);
        }
        
        // lb:// protocol won't be intercepted, return null to let SCG's built-in load balancer handle it
        return null;
    }
    
    /**
     * Parse service from gateway-services.json configuration (for static:// protocol)
     */
    private URI resolveFromGatewayServices(String serviceName) throws Exception {
        log.debug("Resolving service from gateway-services.json: {}", serviceName);
        
        // Check if cache is expired
        long now = System.currentTimeMillis();
        if (cachedServicesConfig != null && (now - servicesLastLoadTime) < SERVICES_CACHE_TTL_MS) {
            log.debug("Using cached services config");
            return resolveFromConfig(cachedServicesConfig, serviceName);
        }
        
        // Read gateway-services.json configuration from Nacos
        String servicesJson = configService.getConfig("gateway-services.json", "DEFAULT_GROUP", 5000);
        if (servicesJson == null || servicesJson.isEmpty()) {
            log.warn("No services configuration found in Nacos");
            cachedServicesConfig = null;  // Clear cache
            servicesLastLoadTime = now;
            return null;
        }
        
        log.debug("Services config from Nacos: {}", servicesJson);
        
        ServicesConfig config = objectMapper.readValue(servicesJson, ServicesConfig.class);
        cachedServicesConfig = config;
        servicesLastLoadTime = now;
        
        return resolveFromConfig(config, serviceName);
    }
    
    /**
     * Parse service from configuration
     */
    private URI resolveFromConfig(ServicesConfig config, String serviceName) throws Exception {
        if (config == null || config.getServices() == null) {
            log.warn("No services found in configuration");
            return null;
        }
        
        for (ServiceDefinition service : config.getServices()) {
            if (serviceName.equals(service.getName())) {
                if (service.getInstances() == null || service.getInstances().isEmpty()) {
                    log.warn("No instances defined for service: {}", serviceName);
                    return null;
                }
                
                // Filter healthy and enabled instances
                java.util.List<ServiceInstanceConfig> healthyInstances = new java.util.ArrayList<>();
                for (ServiceInstanceConfig instance : service.getInstances()) {
                    if (instance.isEnabled() && instance.isHealthy()) {
                        healthyInstances.add(instance);
                    }
                }
                
                if (healthyInstances.isEmpty()) {
                    log.warn("No healthy/enabled instances for service: {}", serviceName);
                    return null;
                }
                
                // Select instance based on load balancing strategy
                ServiceInstanceConfig selectedInstance = selectInstance(healthyInstances, service.getLoadBalancer());
                
                URI resolvedUri = new URI("http://" + selectedInstance.getIp() + ":" + selectedInstance.getPort());
                log.debug("Resolved {} to {} (strategy: {})", serviceName, resolvedUri, service.getLoadBalancer());
                return resolvedUri;
            }
        }
        
        log.warn("Service not found: {}", serviceName);
        return null;
    }
    
    /**
     * Clear service configuration cache (called when Nacos configuration is deleted)
     */
    public void clearCache() {
        cachedServicesConfig = null;
        servicesLastLoadTime = 0;
        log.info("Cleared services config cache");
    }
    
    /**
     * Select instance based on load balancing strategy
     */
    private ServiceInstanceConfig selectInstance(java.util.List<ServiceInstanceConfig> instances, String strategy) {
        if (instances.isEmpty()) {
            return null;
        }
        
        // Use round-robin strategy by default
        if (strategy == null || "round-robin".equalsIgnoreCase(strategy)) {
            return roundRobinSelect(instances);
        } else if ("random".equalsIgnoreCase(strategy)) {
            return randomSelect(instances);
        } else if ("weight".equalsIgnoreCase(strategy) || "weighted".equalsIgnoreCase(strategy)) {
            return weightSelect(instances);
        } else {
            // Default round-robin
            return roundRobinSelect(instances);
        }
    }
    
    /**
     * Round-robin strategy (simple implementation: timestamp modulo)
     */
    private ServiceInstanceConfig roundRobinSelect(java.util.List<ServiceInstanceConfig> instances) {
        int index = (int)(System.currentTimeMillis() / 1000) % instances.size();
        return instances.get(index);
    }
    
    /**
     * Random strategy
     */
    private ServiceInstanceConfig randomSelect(java.util.List<ServiceInstanceConfig> instances) {
        int index = new java.util.Random().nextInt(instances.size());
        return instances.get(index);
    }
    
    // Weighted round-robin counter (atomic, thread-safe)
    private final java.util.concurrent.atomic.AtomicLong weightedCounter = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Weight strategy: deterministic weighted round-robin
     * e.g. weight 1:2 -> sequence: B A B A B A B ...
     */
    private ServiceInstanceConfig weightSelect(java.util.List<ServiceInstanceConfig> instances) {
        // Build expanded list: weight=1 -> 1 slot, weight=2 -> 2 slots
        java.util.List<ServiceInstanceConfig> expanded = new java.util.ArrayList<>();
        for (ServiceInstanceConfig inst : instances) {
            int w = (int) Math.max(1, inst.getWeight());
            for (int i = 0; i < w; i++) {
                expanded.add(inst);
            }
        }
        long idx = weightedCounter.getAndIncrement() % expanded.size();
        return expanded.get((int) idx);
    }
    
    // Configuration class
    public static class ServicesConfig {
        private String version;
        private java.util.List<ServiceDefinition> services;
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public java.util.List<ServiceDefinition> getServices() { return services; }
        public void setServices(java.util.List<ServiceDefinition> services) { this.services = services; }
    }
    
    public static class ServiceDefinition {
        private String name;
        private String description;
        private java.util.List<ServiceInstanceConfig> instances;
        private String loadBalancer;
        private java.util.Map<String, Object> metadata;
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public java.util.List<ServiceInstanceConfig> getInstances() { return instances; }
        public void setInstances(java.util.List<ServiceInstanceConfig> instances) { this.instances = instances; }
        public String getLoadBalancer() { return loadBalancer; }
        public void setLoadBalancer(String loadBalancer) { this.loadBalancer = loadBalancer; }
        public java.util.Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(java.util.Map<String, Object> metadata) { this.metadata = metadata; }
    }
    
    public static class ServiceInstanceConfig {
        private String instanceId;
        private String ip;
        private int port;
        private double weight;
        private boolean healthy;
        private boolean enabled;
        private java.util.Map<String, String> metadata;
        
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public double getWeight() { return weight; }
        public void setWeight(double weight) { this.weight = weight; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public java.util.Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(java.util.Map<String, String> metadata) { this.metadata = metadata; }
    }
}
