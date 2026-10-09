package com.leoli.gateway.admin.service;

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
import java.util.List;

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

    @Value("${nacos.server-addr:localhost:8848}")
    private String nacosServerAddr;

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
     */
    void deleteNacosNamespace(String namespaceId) {
        if (namespaceId == null || namespaceId.isEmpty()) {
            log.info("No Nacos namespace to delete");
            return;
        }

        try {
            String nacosApiUrl = nacosServerAddr;
            if (nacosApiUrl == null || nacosApiUrl.isEmpty()) {
                nacosApiUrl = "localhost:8848";
            }
            // Tolerate a scheme already present in the configured address. Formatting
            // "http://%s" over "http://nacos:8848" yields "http://http://nacos:8848", which
            // throws, and the catch below only logs a warning - so the namespace leaks
            // silently. NacosConfigCenterService already guards this way.
            String baseUrl = nacosApiUrl.startsWith("http") ? nacosApiUrl : "http://" + nacosApiUrl;

            String url = String.format("%s/nacos/v1/console/namespaces?namespaceId=%s",
                    baseUrl,
                    URLEncoder.encode(namespaceId, StandardCharsets.UTF_8));

            log.info("Deleting Nacos namespace: {}", namespaceId);
            restTemplate.delete(url);
            log.info("Nacos namespace {} deleted successfully", namespaceId);
        } catch (Exception e) {
            log.warn("Failed to delete Nacos namespace {}: {}", namespaceId, e.getMessage());
        }
    }
}
