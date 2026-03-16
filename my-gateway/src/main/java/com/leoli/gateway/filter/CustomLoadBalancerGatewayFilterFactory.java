package com.leoli.gateway.filter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

@Slf4j
@Component
public class CustomLoadBalancerGatewayFilterFactory extends AbstractGatewayFilterFactory<CustomLoadBalancerGatewayFilterFactory.Config> {

    private final Environment environment;
    private final ObjectMapper objectMapper;

    private static final String SERVICES_INDEX_DATA_ID = "config.gateway.metadata.services-index";
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
                    log.info("Processing static:// protocol: {}", originalUri);
                    URI serviceUri = resolveServiceUri(URI.create(originalUri));

                    if (Objects.isNull(serviceUri)) {
                        return Mono.error(new NotFoundException("Unable to find instance for service"));
                    }

                    log.info("Resolved static:// -> {}", serviceUri);

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
                log.error("Error resolving service", e);
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
                // Filter healthy and enabled instances
                List<ServicesConfig.StaticServiceInstance> healthyInstances =
                        service.getInstances().stream()
                                .filter(inst -> inst.isHealthy() && inst.isEnabled())
                                .toList();

                if (healthyInstances.isEmpty()) {
                    log.warn("No healthy/enabled instances for static://{}", serviceName);
                    return null;
                }

                // Select instance using weighted strategy
                ServicesConfig.StaticServiceInstance selected = selectByWeightedStrategy(healthyInstances, "round-robin");

                String resolved = "http://" + selected.getHost() + ":" + selected.getPort();
                log.info("Resolved static://{} -> {} (weighted {})", serviceName, resolved, selected.getPort());
                return URI.create(resolved);
            }
        }

        log.warn("No matching service found for static://{}", serviceName);
        return null;
    }

    /**
     * Select instance using weighted strategy (supports round-robin and random with weights)
     */
    private ServicesConfig.StaticServiceInstance selectByWeightedStrategy(
            List<ServicesConfig.StaticServiceInstance> instances, String strategy) {

        if (instances.size() == 1) {
            return instances.get(0);
        }

        // Get weights from metadata (default weight = 1)
        List<Integer> weights = instances.stream()
                .map(inst -> {
                    String weightStr = inst.getMetadata().get("weight");
                    return weightStr != null ? Integer.parseInt(weightStr) : 1;
                })
                .toList();

        // Calculate total weight
        int totalWeight = weights.stream().mapToInt(w -> w).sum();

        // Check if all weights are the same
        boolean sameWeight = weights.stream().allMatch(w -> w == weights.get(0));

        if (sameWeight) {
            // Use simple round-robin or random when all weights are equal
            if ("random".equalsIgnoreCase(strategy)) {
                int index = (int) (Math.random() * instances.size());
                return instances.get(index);
            } else {
                // Default to round-robin
                int index = (int) (System.currentTimeMillis() / 1000) % instances.size();
                return instances.get(index);
            }
        }

        // Weighted selection
        if ("random".equalsIgnoreCase(strategy)) {
            // Weighted random: select based on weight proportion
            double random = Math.random() * totalWeight;
            double weightSum = 0;

            for (int i = 0; i < instances.size(); i++) {
                weightSum += weights.get(i);
                if (random <= weightSum) {
                    ServicesConfig.StaticServiceInstance selected = instances.get(i);
                    log.debug("Selected instance {}:{} with weight {} (total: {})",
                            selected.getHost(), selected.getPort(), weights.get(i), totalWeight);
                    return selected;
                }
            }
            return instances.get(instances.size() - 1);
        } else {
            // Weighted round-robin: use smooth weighted round-robin algorithm
            // For simplicity, use weighted random which gives similar distribution
            double random = Math.random() * totalWeight;
            double weightSum = 0;

            for (int i = 0; i < instances.size(); i++) {
                weightSum += weights.get(i);
                if (random <= weightSum) {
                    ServicesConfig.StaticServiceInstance selected = instances.get(i);
                    log.debug("Selected instance {}:{} with weight {} (total: {})",
                            selected.getHost(), selected.getPort(), weights.get(i), totalWeight);
                    return selected;
                }
            }
            return instances.get(instances.size() - 1);
        }
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

            String content = configService.getConfig(SERVICES_INDEX_DATA_ID, "DEFAULT_GROUP", 5000);
            configService.shutDown();

            if (content != null && !content.trim().isEmpty()) {
                cachedServicesConfig = objectMapper.readValue(content, ServicesConfig.class);
                servicesLastLoadTime = now;
                log.debug("Loaded services config from Nacos");
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse services configuration", e);
        } catch (Exception e) {
            log.error("Failed to load services configuration from Nacos", e);
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

        public Map<String, ServiceDefinition> getServices() {
            return services;
        }

        public void setServices(Map<String, ServiceDefinition> services) {
            this.services = services;
        }

        public static class ServiceDefinition {
            private String name;
            private List<StaticServiceInstance> instances;

            @JsonProperty("serviceId")
            public void setServiceId(String serviceId) {
                this.name = serviceId;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public List<StaticServiceInstance> getInstances() {
                return instances;
            }

            public void setInstances(List<StaticServiceInstance> instances) {
                this.instances = instances;
            }
        }

        public static class StaticServiceInstance implements ServiceInstance {
            private String ip;
            private int port;
            private boolean healthy;
            private boolean enabled;
            private Integer weight; // Weight for load balancing

            @Override
            public String getServiceId() {
                return null;
            }

            @Override
            public String getHost() {
                return ip;
            }

            @Override
            public int getPort() {
                return port;
            }

            @Override
            public boolean isSecure() {
                return false;
            }

            @Override
            public URI getUri() {
                return URI.create("http://" + ip + ":" + port);
            }

            @Override
            public Map<String, String> getMetadata() {
                Map<String, String> metadata = new ConcurrentHashMap<>();
                if (weight != null) {
                    metadata.put("weight", String.valueOf(weight));
                }
                return metadata;
            }

            public boolean isHealthy() {
                return healthy;
            }

            public boolean isEnabled() {
                return enabled;
            }

            public Integer getWeight() {
                return weight;
            }

            public void setIp(String ip) {
                this.ip = ip;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public void setHealthy(boolean h) {
                this.healthy = h;
            }

            public void setEnabled(boolean e) {
                this.enabled = e;
            }

            public void setWeight(Integer weight) {
                this.weight = weight;
            }
        }
    }
}
