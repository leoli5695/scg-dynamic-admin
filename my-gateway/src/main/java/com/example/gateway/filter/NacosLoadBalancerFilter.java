package com.example.gateway.filter;

import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.*;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Properties;

/**
 * Load Balancer Filter based on Nacos Service Discovery
 * Replaces Spring Cloud Gateway's built-in ReactiveLoadBalancerClientFilter
 * 
 * @author leoli
 * @version 1.0
 */
@Slf4j
@Component
public class NacosLoadBalancerFilter implements GlobalFilter, Ordered {

    private final LoadBalancerClientFactory clientFactory;
    private final NacosNamingServiceWrapper nacosNamingService;

    public NacosLoadBalancerFilter(LoadBalancerClientFactory clientFactory) {
        this.clientFactory = clientFactory;
        
        // Initialize Nacos NamingService
        try {
            String serverAddr = System.getProperty("spring.cloud.nacos.discovery.server-addr", "127.0.0.1:8848");
            String namespace = System.getProperty("spring.cloud.nacos.discovery.namespace", "");
            
            Properties props = new Properties();
            props.setProperty("serverAddr", serverAddr);
            if (!namespace.isEmpty()) {
                props.setProperty("namespace", namespace);
            }
            
            this.nacosNamingService = new NacosNamingServiceWrapper(props);
            log.info("NacosLoadBalancerFilter - Nacos NamingService initialized: {}", serverAddr);
        } catch (Exception e) {
            log.error("Failed to initialize Nacos naming service", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI url = (URI) exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        String schemePrefix = (String) exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_SCHEME_PREFIX_ATTR);
        
        // Only process requests with lb:// protocol
        if (url != null && ("lb".equals(url.getScheme()) || "lb".equals(schemePrefix))) {
            ServerWebExchangeUtils.addOriginalRequestUrl(exchange, url);
            
            if (log.isTraceEnabled()) {
                log.trace("NacosLoadBalancerFilter url before: " + url);
            }

            URI requestUri = (URI) exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            String serviceId = requestUri.getHost();
            
            // Get service instances from Nacos
            return chooseFromNacos(serviceId, exchange)
                .flatMap(response -> {
                    if (!response.hasServer()) {
                        throw NotFoundException.create(true, 
                            "Unable to find instance for " + url.getHost());
                    }

                    ServiceInstance retrievedInstance = response.getServer();
                    URI uri = exchange.getRequest().getURI();
                    String overrideScheme = retrievedInstance.isSecure() ? "https" : "http";
                    if (schemePrefix != null) {
                        overrideScheme = url.getScheme();
                    }

                    DelegatingServiceInstance serviceInstance = 
                        new DelegatingServiceInstance(retrievedInstance, overrideScheme);
                    URI requestUrl = reconstructURI(serviceInstance, uri);

                    if (log.isTraceEnabled()) {
                        log.trace("LoadBalancerClientFilter url chosen: " + requestUrl);
                    }

                    exchange.getAttributes().put(
                        ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, requestUrl);
                    
                    return chain.filter(exchange);
                });
        } else {
            return chain.filter(exchange);
        }
    }

    /**
     * Choose service instance from Nacos
     */
    private Mono<Response<ServiceInstance>> chooseFromNacos(String serviceId, ServerWebExchange exchange) {
        log.debug("Choosing instance for service: {} from Nacos", serviceId);
        
        try {
            // Get service instances list from Nacos
            List<Instance> instances = nacosNamingService.getAllInstances(serviceId);
            
            if (instances == null || instances.isEmpty()) {
                log.warn("No instances found for service: {} in Nacos", serviceId);
                return Mono.empty();
            }
            
            log.info("Found {} instance(s) for service: {} in Nacos", instances.size(), serviceId);
            
            // Filter healthy instances
            List<Instance> healthyInstances = instances.stream()
                    .filter(Instance::isHealthy)
                    .filter(Instance::isEnabled)
                    .toList();
            
            if (healthyInstances.isEmpty()) {
                log.warn("No healthy/enabled instances for service: {}", serviceId);
                return Mono.empty();
            }
            
            // Convert Nacos Instance to Spring Cloud ServiceInstance
            List<ServiceInstance> serviceInstances = healthyInstances.stream()
                    .map(instance -> (ServiceInstance) new NacosServiceInstance(instance))
                    .toList();
            
            // Use SCG's built-in load balancer to select instance (supports weighted strategies)
            ReactorServiceInstanceLoadBalancer loadBalancer = 
                clientFactory.getInstance(serviceId, ReactorServiceInstanceLoadBalancer.class);
            
            if (loadBalancer == null) {
                log.warn("No loadbalancer available for {}, using weighted round-robin", serviceId);
                // If no load balancer is configured, use weighted round-robin strategy (considering Nacos instance weights)
                Instance selected = selectByWeightedRoundRobin(healthyInstances);
                ServiceInstance serviceInstance = new NacosServiceInstance(selected);
                return Mono.just(new SimpleResponse<>(serviceInstance));
            }
            
            // Build Request object for the load balancer
            RequestData requestData = new RequestData(exchange.getRequest());
            RequestDataContext requestContext = new RequestDataContext(requestData, getHint(serviceId));
            DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(requestContext);
            
            // Directly use SCG's load balancer (it applies the configured weight strategy)
            // SCG's load balancer will automatically get instances from Nacos and consider weights
            return loadBalancer.choose(lbRequest)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    // If load balancer returns empty, use weighted round-robin
                    Instance selected = selectByWeightedRoundRobin(healthyInstances);
                    return new SimpleResponse<>(new NacosServiceInstance(selected));
                }));
            
        } catch (Exception e) {
            log.error("Error choosing instance from Nacos for service: {}", serviceId, e);
            return Mono.error(e);
        }
    }
    
    /**
     * Select instance by round-robin strategy
     */
    private Instance selectByRoundRobin(List<Instance> instances) {
        int index = (int)(System.currentTimeMillis() / 1000) % instances.size();
        return instances.get(index);
    }
    
    /**
     * Select instance by weighted round-robin strategy (considering Nacos instance weights)
     */
    private Instance selectByWeightedRoundRobin(List<Instance> instances) {
        if (instances.size() == 1) {
            return instances.get(0);
        }
        
        // Calculate total weight
        double totalWeight = instances.stream()
                .mapToDouble(Instance::getWeight)
                .sum();
        
        // Calculate average weight
        double avgWeight = totalWeight / instances.size();
        
        // If all weights are the same, use normal round-robin
        boolean sameWeight = instances.stream()
                .allMatch(inst -> Math.abs(inst.getWeight() - avgWeight) < 0.01);
        if (sameWeight) {
            return selectByRoundRobin(instances);
        }
        
        // Select randomly based on weight proportion
        double random = Math.random() * totalWeight;
        double weightSum = 0;
        
        for (Instance instance : instances) {
            weightSum += instance.getWeight();
            if (random <= weightSum) {
                log.debug("Selected instance {}:{} with weight {} (total: {})", 
                    instance.getIp(), instance.getPort(), instance.getWeight(), totalWeight);
                return instance;
            }
        }
        
        return instances.get(instances.size() - 1);
    }
    
    /**
     * Reconstruct URI
     */
    protected URI reconstructURI(ServiceInstance serviceInstance, URI original) {
        return LoadBalancerUriTools.reconstructURI(serviceInstance, original);
    }
    
    /**
     * Get Hint
     */
    private String getHint(String serviceId) {
        LoadBalancerProperties loadBalancerProperties = clientFactory.getProperties(serviceId);
        java.util.Map<String, String> hints = loadBalancerProperties.getHint();
        String defaultHint = hints.getOrDefault("default", "default");
        String hintPropertyValue = hints.get(serviceId);
        return hintPropertyValue != null ? hintPropertyValue : defaultHint;
    }

    @Override
    public int getOrder() {
        return 10150; // Same priority as native Filter
    }
    
    /**
     * Simple Response implementation
     */
    private static class SimpleResponse<T> implements Response<T> {
        private final T server;
        
        SimpleResponse(T server) {
            this.server = server;
        }
        
        @Override
        public boolean hasServer() {
            return server != null;
        }
        
        @Override
        public T getServer() {
            return server;
        }
    }
    
    /**
     * DelegatingServiceInstance 包装类
     */
    private static class DelegatingServiceInstance implements ServiceInstance {
        private final ServiceInstance delegate;
        private final String scheme;
        
        DelegatingServiceInstance(ServiceInstance delegate, String scheme) {
            this.delegate = delegate;
            this.scheme = scheme;
        }
        
        @Override
        public String getServiceId() {
            return delegate.getServiceId();
        }
        
        @Override
        public String getHost() {
            return delegate.getHost();
        }
        
        @Override
        public int getPort() {
            return delegate.getPort();
        }
        
        @Override
        public boolean isSecure() {
            return "https".equals(scheme);
        }
        
        @Override
        public URI getUri() {
            return delegate.getUri();
        }
        
        @Override
        public java.util.Map<String, String> getMetadata() {
            return delegate.getMetadata();
        }
        
        @Override
        public String getInstanceId() {
            return delegate.getInstanceId();
        }
    }
}
