package com.leoli.gateway.perf;

import com.leoli.gateway.MyGatewayApplication;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.discovery.nacos.NacosDiscoveryService;
import com.leoli.gateway.enums.CenterType;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.manager.RouteManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.route.DynamicRouteDefinitionLocator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.function.server.RouterFunctions;

import java.lang.management.ManagementFactory;
import java.lang.management.BufferPoolMXBean;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Test-classpath-only, loopback-only performance fixture. Never packaged in the application JAR. */
public final class GatewayBaselineApplication {
    private static final String SECRET = "baseline-only-jwt-secret-0123456789abcdef";

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MyGatewayApplication.class, Fixture.class);
        app.setAdditionalProfiles("e2e");
        app.setDefaultProperties(Map.of("server.address", "127.0.0.1"));
        app.run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Fixture {
        @Bean
        @org.springframework.context.annotation.Primary
        com.leoli.gateway.ai.ProviderKeyResolver baselineProviderKey() {
            return ref -> reactor.core.publisher.Mono.just("baseline-only-provider-key");
        }
        @Bean(name = "nacosConfigServiceWrapper")
        ConfigCenterService isolatedConfigCenter() {
            return new ConfigCenterService() {
                public String getConfig(String id, String group) { return null; }
                public Map<String, Object> getAllConfigData(String id, String group) { return Map.of(); }
                public void addListener(String id, String group, ConfigListener listener) { }
                public void removeListener(String id, String group, ConfigListener listener) { }
                public CenterType getCenterType() { return CenterType.NACOS; }
            };
        }

        @Bean
        NacosDiscoveryService isolatedNacosDiscovery() {
            // All fixture routes are direct HTTP; this satisfies the inactive lb:// filter only.
            return new NacosDiscoveryService(null);
        }

        @Bean
        DiscoveryClient isolatedDiscovery() {
            return new DiscoveryClient() {
                public String description() { return "isolated performance fixture"; }
                public List<ServiceInstance> getInstances(String id) { return List.of(); }
                public List<String> getServices() { return List.of(); }
            };
        }

        @Bean
        RouterFunction<ServerResponse> baselineMetrics() {
            return RouterFunctions.route().GET("/__baseline/metrics", request -> {
                var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                var memory = ManagementFactory.getMemoryMXBean();
                long direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                        .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
                long gcCount = ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
                long gcMs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
                return ServerResponse.ok().bodyValue(Map.of(
                        "timestampMs", System.currentTimeMillis(), "pid", ProcessHandle.current().pid(),
                        "processCpuNs", os.getProcessCpuTime(), "heapUsedBytes", memory.getHeapMemoryUsage().getUsed(),
                        "heapCommittedBytes", memory.getHeapMemoryUsage().getCommitted(),
                        "nonHeapUsedBytes", memory.getNonHeapMemoryUsage().getUsed(), "directBytes", direct,
                        "gcCount", gcCount, "gcMs", gcMs, "threads", ManagementFactory.getThreadMXBean().getThreadCount()));
            }).build();
        }

        @Bean
        ApplicationRunner baselineRoutes(RouteManager routes, StrategyManager strategies,
                                          AuthBindingManager bindings, DynamicRouteDefinitionLocator locator,
                                          org.springframework.context.ConfigurableApplicationContext context) {
            return arguments -> {
                // Freeze the fixture: no registry refresh, admin sync or scheduled background probes.
                context.getBean(ScheduledAnnotationBeanPostProcessor.class).destroy();
                String upstream = System.getenv("BASELINE_UPSTREAM_URL");
                if (upstream == null || !URI.create(upstream).getHost().equals("127.0.0.1")) {
                    throw new IllegalArgumentException("BASELINE_UPSTREAM_URL must be a loopback URL");
                }
                for (String scenario : List.of("ordinary", "jwt", "api-key", "basic", "body", "sse")) {
                    RouteDefinition route = new RouteDefinition();
                    route.setId("baseline-" + scenario);
                    route.setUri(URI.create(upstream));
                    route.setPredicates(List.of(new PredicateDefinition("Path=/baseline/" + scenario)));
                    var service = new com.leoli.gateway.model.MultiServiceConfig();
                    service.setServiceId("baseline-upstream");
                    route.setMetadata(Map.of(com.leoli.gateway.model.MultiServiceConfig.METADATA_KEY, service));
                    routes.putRoute(route.getId(), route);
                }
                for (String type : List.of("jwt", "api-key", "basic")) {
                    String routeId = "baseline-" + type;
                    String authType = type.equals("api-key") ? "API_KEY" : type.toUpperCase(java.util.Locale.ROOT);
                    AuthConfig config = new AuthConfig();
                    config.setPolicyId(routeId);
                    config.setAuthType(authType);
                    config.setSecretKey(SECRET);
                    config.setJwtAlgorithm("HS256");
                    config.setApiKey("baseline-only-api-key");
                    config.setBasicUsername("baseline");
                    config.setBasicPassword(BCrypt.hashpw("baseline-only-password", BCrypt.gensalt(10)));
                    config.setPasswordHashAlgorithm("BCRYPT");
                    bindings.putPolicy(routeId, config);
                    bindings.setPolicyRoutes(routeId, List.of(routeId));
                    StrategyDefinition strategy = new StrategyDefinition();
                    strategy.setStrategyId(routeId);
                    strategy.setStrategyType(StrategyDefinition.TYPE_AUTH);
                    strategy.setScope("ROUTE");
                    strategy.setRouteId(routeId);
                    strategy.setConfig(Map.of("authType", authType));
                    strategies.putStrategy(routeId, strategy);
                }
                StrategyDefinition ai = new StrategyDefinition();
                ai.setStrategyId("baseline-ai-proxy");
                ai.setStrategyType(StrategyDefinition.TYPE_AI_PROXY);
                ai.setScope("ROUTE");
                ai.setRouteId("baseline-sse");
                ai.setConfig(Map.of("enabled", true, "providerSelection", "FIXED", "fixedProvider", "qwen",
                        "providerApiKeyRefs", Map.of("qwen", "baseline-credential"),
                        "providerBaseUrlOverride", Map.of("qwen", upstream)));
                strategies.putStrategy(ai.getStrategyId(), ai);
                locator.refresh();
                String jwt = Jwts.builder().subject("baseline-user").issuedAt(new Date())
                        .expiration(Date.from(Instant.now().plusSeconds(14400)))
                        .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
                int port = ((org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext) context)
                        .getWebServer().getPort();
                var ready = Map.of("port", port, "pid", ProcessHandle.current().pid(), "java", System.getProperty("java.version"),
                        "processors", Runtime.getRuntime().availableProcessors(), "heapMaxBytes", Runtime.getRuntime().maxMemory(),
                        "jwt", jwt, "profile", "isolated-direct-http-no-redis-no-discovery-no-telemetry");
                Files.writeString(Path.of(System.getenv("BASELINE_READY_FILE")),
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ready));
            };
        }
    }
}
