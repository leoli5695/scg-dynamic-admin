package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 实例配置清理助手.
 * 从 GatewayInstanceService 拆分而来，负责在删除网关实例时清理关联的数据库记录、
 * Nacos 配置和命名空间.
 *
 * @author leoli
 */
@Slf4j
@Component
public class InstanceConfigCleanupHelper {

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private StrategyRepository strategyRepository;

    @Autowired
    private AuthPolicyRepository authPolicyRepository;

    @Autowired
    private SslCertificateRepository sslCertificateRepository;

    @Autowired
    private RouteAuthBindingRepository routeAuthBindingRepository;

    @Autowired
    private RequestTraceRepository requestTraceRepository;

    @Autowired
    private AlertHistoryRepository alertHistoryRepository;

    @Autowired
    private AlertConfigRepository alertConfigRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AccessLogConfigRepository accessLogConfigRepository;

    @Autowired
    private GatewayInstanceRepository instanceRepository;

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${nacos.server-addr:localhost:8848}")
    private String nacosServerAddr;

    /**
     * Prefix of the per-instance Nacos namespace created by {@code createInstance}
     * ("gateway-" + instanceId). Only namespaces with this prefix are ever candidates for
     * orphan cleanup; {@code public} and any non-gateway namespace are always left untouched.
     */
    private static final String GATEWAY_NS_PREFIX = "gateway-";

    /**
     * Delete all related data from database by instance ID.
     */
    void deleteRelatedDataFromDatabase(String instanceId) {
        log.info("Deleting related data from database for instance: {}", instanceId);

        // Delete route auth bindings
        int bindingCount = routeAuthBindingRepository.deleteByInstanceId(instanceId);
        log.info("Deleted {} route auth bindings", bindingCount);

        // Delete request traces
        int traceCount = requestTraceRepository.deleteByInstanceId(instanceId);
        log.info("Deleted {} request traces", traceCount);

        // Delete alert history
        alertHistoryRepository.deleteByInstanceId(instanceId);
        log.info("Deleted alert history");

        // Delete alert config
        alertConfigRepository.deleteByInstanceId(instanceId);
        log.info("Deleted alert config");

        // Delete audit logs
        auditLogRepository.deleteByInstanceId(instanceId);
        log.info("Deleted audit logs");

        // Delete SSL certificates
        int certCount = sslCertificateRepository.deleteByInstanceId(instanceId);
        log.info("Deleted {} SSL certificates", certCount);

        // Delete auth policies
        int policyCount = authPolicyRepository.deleteByInstanceId(instanceId);
        log.info("Deleted {} auth policies", policyCount);

        // Delete strategies
        int strategyCount = strategyRepository.deleteByInstanceId(instanceId);
        log.info("Deleted {} strategies", strategyCount);

        // Delete routes
        int routeCount = routeRepository.deleteByInstanceId(instanceId);
        log.info("Deleted {} routes", routeCount);

        // Delete services
        int serviceCount = serviceRepository.deleteByInstanceId(instanceId);
        log.info("Deleted {} services", serviceCount);

        // Delete access log config
        int accessLogConfigCount = accessLogConfigRepository.deleteByInstanceId(instanceId);
        log.info("Deleted {} access log configs", accessLogConfigCount);
    }

    /**
     * Delete all configs from Nacos namespace.
     */
    void deleteConfigsFromNacos(String nacosNamespace) {
        if (nacosNamespace == null || nacosNamespace.isEmpty()) {
            log.info("No Nacos namespace to clean up");
            return;
        }

        log.info("Deleting configs from Nacos namespace: {}", nacosNamespace);

        try {
            // Delete routes index
            configCenterService.removeConfig("config.gateway.metadata.routes-index", nacosNamespace);
            log.info("Deleted routes-index from Nacos");

            // Delete services index
            configCenterService.removeConfig("config.gateway.metadata.services-index", nacosNamespace);
            log.info("Deleted services-index from Nacos");

            // Delete strategies index
            configCenterService.removeConfig("config.gateway.metadata.strategies-index", nacosNamespace);
            log.info("Deleted strategies-index from Nacos");

            // Delete access log config
            configCenterService.removeConfig("config.gateway.access-log", nacosNamespace);
            log.info("Deleted access-log config from Nacos");

            // Delete ssl certificates index
            configCenterService.removeConfig("config.gateway.metadata.ssl-certificates-index", nacosNamespace);
            log.info("Deleted ssl-certificates-index from Nacos");

            // Get all configs in the namespace and delete them
            deleteAllConfigsInNamespace(nacosNamespace);

            log.info("All configs deleted from Nacos namespace: {}", nacosNamespace);
        } catch (Exception e) {
            log.error("Failed to delete configs from Nacos namespace: {}", nacosNamespace, e);
        }
    }

    /**
     * Delete all gateway-related configs in a Nacos namespace.
     */
    void deleteAllConfigsInNamespace(String nacosNamespace) {
        // Delete route configs
        List<String> routeIds = routeRepository.findByInstanceId(
                instanceRepository.findByInstanceId(nacosNamespace)
                        .map(GatewayInstanceEntity::getInstanceId)
                        .orElse(nacosNamespace)
        ).stream().map(r -> r.getRouteId()).toList();

        for (String routeId : routeIds) {
            configCenterService.removeConfig("config.gateway.route-" + routeId, nacosNamespace);
        }

        // Delete service configs
        List<String> serviceIds = serviceRepository.findByInstanceId(
                instanceRepository.findByInstanceId(nacosNamespace)
                        .map(GatewayInstanceEntity::getInstanceId)
                        .orElse(nacosNamespace)
        ).stream().map(s -> s.getServiceId()).toList();

        for (String serviceId : serviceIds) {
            configCenterService.removeConfig("config.gateway.service-" + serviceId, nacosNamespace);
        }

        // Delete strategy configs
        List<String> strategyIds = strategyRepository.findByInstanceId(
                instanceRepository.findByInstanceId(nacosNamespace)
                        .map(GatewayInstanceEntity::getInstanceId)
                        .orElse(nacosNamespace)
        ).stream().map(s -> s.getStrategyId()).toList();

        for (String strategyId : strategyIds) {
            configCenterService.removeConfig("config.gateway.strategy-" + strategyId, nacosNamespace);
        }

        log.info("Deleted {} routes, {} services, {} strategies from Nacos",
                routeIds.size(), serviceIds.size(), strategyIds.size());

        // Delete the Nacos namespace itself
        deleteNacosNamespace(nacosNamespace);
    }

    /**
     * Delete Nacos namespace.
     * <p>
     * Best-effort: failures are logged, not propagated, so a Nacos outage cannot make an instance
     * permanently undeletable. The trade-off is that a transient failure here can leave an orphan
     * namespace behind; {@code NamespaceReconcileTask} self-heals those on the reconcile cadence.
     */
    public void deleteNacosNamespace(String namespaceId) {
        if (namespaceId == null || namespaceId.isEmpty()) {
            log.info("No Nacos namespace to delete");
            return;
        }

        try {
            String url = String.format("%s/nacos/v1/console/namespaces?namespaceId=%s",
                    resolveBaseUrl(),
                    URLEncoder.encode(namespaceId, StandardCharsets.UTF_8));

            log.info("Deleting Nacos namespace: {}", namespaceId);
            restTemplate.delete(url);
            log.info("Nacos namespace {} deleted successfully", namespaceId);
        } catch (Exception e) {
            log.warn("Failed to delete Nacos namespace {}: {}", namespaceId, e.getMessage());
        }
    }

    /**
     * List the ids of all {@code gateway-*} namespaces currently present in Nacos.
     * <p>
     * Used by {@code NamespaceReconcileTask} to find orphan namespaces (a {@code gateway-<id>}
     * namespace whose instance no longer exists in the DB). The {@code public} namespace and any
     * non-gateway namespace are excluded by the prefix filter. On any failure an empty set is
     * returned, which makes the caller detect zero orphans and delete nothing (fail-closed).
     */
    public Set<String> listGatewayNamespaces() {
        Set<String> gatewayNamespaces = new HashSet<>();
        try {
            String url = resolveBaseUrl() + "/nacos/v1/console/namespaces";
            String body = restTemplate.getForObject(url, String.class);
            if (body == null || body.isEmpty()) {
                return gatewayNamespaces;
            }
            JsonNode data = objectMapper.readTree(body).path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    // The custom namespace id lives in "namespace" ("" for public). trim() guards
                    // against stray \r\n leaking in from the response (see the manual-cleanup trap).
                    String id = node.path("namespace").asText("").trim();
                    if (id.startsWith(GATEWAY_NS_PREFIX)) {
                        gatewayNamespaces.add(id);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list Nacos namespaces: {}", e.getMessage());
        }
        return gatewayNamespaces;
    }

    /**
     * Resolve the Nacos base URL, tolerating a scheme already present in the configured address.
     * Formatting "http://%s" over "http://nacos:8848" yields "http://http://nacos:8848", which
     * throws - and since namespace deletion only logs on failure, that would leak the namespace
     * silently. NacosConfigCenterService guards the same way.
     */
    private String resolveBaseUrl() {
        String addr = (nacosServerAddr == null || nacosServerAddr.isEmpty()) ? "localhost:8848" : nacosServerAddr;
        return addr.startsWith("http") ? addr : "http://" + addr;
    }
}
