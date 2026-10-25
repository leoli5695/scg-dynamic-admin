package com.leoli.gateway.admin.tool.handler;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import static com.leoli.gateway.admin.tool.ToolArgs.getBoolArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredLongArg;

/**
 * Kubernetes cluster tools: list clusters, cluster detail (with the kubeconfig stripped before it reaches the model), and cross-instance comparison.
 *
 * <p>Extracted from {@code ToolExecutor} as part of breaking up a 4241-line / 125-method / 27-dependency
 * god class. Moved mechanically: only the dispatch entry points changed signature
 * ({@code private Object executeX} became {@code public Object x}); helpers are verbatim. Behaviour is
 * unchanged, and {@code ToolExecutorTest} still exercises these tools end to end because it builds this
 * handler from the same mocks and passes it to the dispatcher.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterToolHandler {

    private final GatewayInstanceService gatewayInstanceService;
    private final KubernetesClusterRepository kubernetesClusterRepository;
    private final RestTemplate restTemplate;

    public Object listClusters(Map<String, Object> args) {
        boolean enabledOnly = getBoolArg(args, "enabledOnly", false);

        List<KubernetesCluster> clusters = enabledOnly
                ? kubernetesClusterRepository.findByEnabledTrue()
                : kubernetesClusterRepository.findAll();

        // 简化输出
        return clusters.stream()
                .map(c -> {
                    Map<String, Object> simplified = new LinkedHashMap<>();
                    simplified.put("id", c.getId());
                    simplified.put("clusterName", c.getClusterName());
                    simplified.put("serverUrl", c.getServerUrl());
                    simplified.put("clusterVersion", c.getClusterVersion());
                    simplified.put("nodeCount", c.getNodeCount());
                    simplified.put("podCount", c.getPodCount());
                    simplified.put("totalCpuCores", c.getTotalCpuCores());
                    simplified.put("totalMemoryGb", c.getTotalMemoryGb());
                    simplified.put("connectionStatus", c.getConnectionStatus());
                    simplified.put("enabled", c.getEnabled());
                    return simplified;
                })
                .toList();
    }

    public Object getClusterDetail(Map<String, Object> args) {
        Long clusterId = getRequiredLongArg(args, "clusterId");

        KubernetesCluster cluster = kubernetesClusterRepository.findById(clusterId)
                .orElse(null);

        if (cluster == null) {
            return Map.of("error", "Cluster not found: " + clusterId);
        }

        // 获取该集群下的实例列表
        List<GatewayInstanceEntity> instances = gatewayInstanceService.getAllInstances()
                .stream()
                .filter(i -> i.getClusterId() != null && i.getClusterId().equals(clusterId))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        // FIX (P0): never hand the raw KubernetesCluster entity to the LLM - it
        // carries the plaintext kubeconfig (client certs / tokens). Emit only a
        // whitelist of non-credential operational metadata.
        result.put("cluster", sanitizeClusterForAi(cluster));
        result.put("instances", instances.stream()
                .map(i -> Map.of(
                        "instanceId", i.getInstanceId(),
                        "instanceName", i.getInstanceName(),
                        "namespace", i.getNamespace(),
                        "status", i.getStatus(),
                        "statusCode", i.getStatusCode()
                ))
                .toList());
        result.put("instanceCount", instances.size());

        return result;
    }

    /**
     * Build an AI-safe view of a cluster: operational metadata only, with the
     * plaintext kubeconfig and other credential material stripped out.
     */
    private Map<String, Object> sanitizeClusterForAi(KubernetesCluster cluster) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("id", cluster.getId());
        safe.put("clusterName", cluster.getClusterName());
        safe.put("contextName", cluster.getContextName());
        safe.put("clusterVersion", cluster.getClusterVersion());
        safe.put("nodeCount", cluster.getNodeCount());
        safe.put("podCount", cluster.getPodCount());
        safe.put("namespaceCount", cluster.getNamespaceCount());
        safe.put("totalCpuCores", cluster.getTotalCpuCores());
        safe.put("totalMemoryGb", cluster.getTotalMemoryGb());
        safe.put("connectionStatus", cluster.getConnectionStatus());
        safe.put("lastCheckedAt", cluster.getLastCheckedAt());
        safe.put("enabled", cluster.getEnabled());
        safe.put("description", cluster.getDescription());
        return safe;
    }

    public Object compareInstances(Map<String, Object> args) {
        String instanceIdsStr = getStringArg(args, "instanceIds");
        String compareType = getStringArg(args, "compareType", "all");

        // 获取要对比的实例列表
        List<GatewayInstanceEntity> instances;
        if (instanceIdsStr != null && !instanceIdsStr.isEmpty()) {
            String[] ids = instanceIdsStr.split(",");
            instances = new ArrayList<>();
            for (String id : ids) {
                try {
                    GatewayInstanceEntity instance = gatewayInstanceService.getInstanceByInstanceId(id.trim());
                    instances.add(instance);
                } catch (Exception e) {
                    log.warn("Instance not found: {}", id.trim());
                }
            }
        } else {
            // 不提供时对比所有运行中的实例
            instances = gatewayInstanceService.getAllInstances()
                    .stream()
                    .filter(i -> i.getStatusCode() != null && i.getStatusCode() == 1)  // RUNNING
                    .toList();
        }

        if (instances.isEmpty()) {
            return Map.of("error", "No instances found to compare");
        }

        if (instances.size() < 2) {
            return Map.of("warning", "Only one instance found, comparison requires at least 2 instances",
                    "instance", instances.get(0));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceCount", instances.size());
        result.put("compareType", compareType);

        // 配置对比
        if ("config".equals(compareType) || "all".equals(compareType)) {
            List<Map<String, Object>> configComparison = instances.stream()
                    .map(i -> {
                        Map<String, Object> config = new LinkedHashMap<>();
                        config.put("instanceId", i.getInstanceId());
                        config.put("instanceName", i.getInstanceName());
                        config.put("specType", i.getSpecType());
                        config.put("cpuCores", i.getCpuCores());
                        config.put("memoryMB", i.getMemoryMB());
                        config.put("replicas", i.getReplicas());
                        config.put("image", i.getImage());
                        config.put("serverPort", i.getServerPort());
                        config.put("managementPort", i.getManagementPort());
                        config.put("clusterId", i.getClusterId());
                        config.put("clusterName", i.getClusterName());
                        config.put("namespace", i.getNamespace());
                        return config;
                    })
                    .toList();
            result.put("configComparison", configComparison);

            // 配置差异检测
            Map<String, Object> differences = detectConfigDifferences(instances);
            result.put("configDifferences", differences);
        }

        // 性能对比
        if ("performance".equals(compareType) || "all".equals(compareType)) {
            List<Map<String, Object>> performanceComparison = new ArrayList<>();
            for (GatewayInstanceEntity instance : instances) {
                Map<String, Object> perf = new LinkedHashMap<>();
                perf.put("instanceId", instance.getInstanceId());
                perf.put("instanceName", instance.getInstanceName());
                perf.put("statusCode", instance.getStatusCode());
                perf.put("lastHeartbeatTime", instance.getLastHeartbeatTime());

                // 获取实时指标（如果实例运行）
                if (instance.getStatusCode() != null && instance.getStatusCode() == 1) {
                    try {
                        Map<String, Object> metrics = fetchInstanceMetrics(instance);
                        perf.put("metrics", metrics);
                    } catch (Exception e) {
                        perf.put("metricsError", "Failed to fetch metrics: " + e.getMessage());
                    }
                }
                performanceComparison.add(perf);
            }
            result.put("performanceComparison", performanceComparison);
        }

        return result;
    }

    /**
     * 检测配置差异.
     */
    private Map<String, Object> detectConfigDifferences(List<GatewayInstanceEntity> instances) {
        Map<String, Object> differences = new LinkedHashMap<>();
        List<String> diffFields = new ArrayList<>();

        // 检测关键配置差异
        Set<String> specTypes = instances.stream().map(GatewayInstanceEntity::getSpecType).collect(Collectors.toSet());
        if (specTypes.size() > 1) {
            diffFields.add("specType: " + specTypes);
        }

        Set<Double> cpuCores = instances.stream().map(GatewayInstanceEntity::getCpuCores).filter(Objects::nonNull).collect(Collectors.toSet());
        if (cpuCores.size() > 1) {
            diffFields.add("cpuCores: " + cpuCores);
        }

        Set<Integer> memoryMBs = instances.stream().map(GatewayInstanceEntity::getMemoryMB).filter(Objects::nonNull).collect(Collectors.toSet());
        if (memoryMBs.size() > 1) {
            diffFields.add("memoryMB: " + memoryMBs);
        }

        Set<String> images = instances.stream().map(GatewayInstanceEntity::getImage).filter(Objects::nonNull).collect(Collectors.toSet());
        if (images.size() > 1) {
            diffFields.add("image: " + images);
        }

        Set<String> clusterNames = instances.stream().map(GatewayInstanceEntity::getClusterName).filter(Objects::nonNull).collect(Collectors.toSet());
        if (clusterNames.size() > 1) {
            diffFields.add("clusterName: " + clusterNames);
        }

        differences.put("hasDifferences", !diffFields.isEmpty());
        differences.put("diffFields", diffFields);
        differences.put("summary", diffFields.isEmpty()
                ? "所有实例配置一致"
                : "发现 " + diffFields.size() + " 个配置差异");

        return differences;
    }

    /**
     * 获取实例实时指标.
     */
    private Map<String, Object> fetchInstanceMetrics(GatewayInstanceEntity instance) {
        String accessUrl = instance.getEffectiveAccessUrl();
        if (accessUrl == null) {
            return Map.of("error", "No access URL available");
        }

        try {
            String url = accessUrl + "/actuator/metrics";
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = restTemplate.getForObject(url, Map.class);
            return metrics != null ? metrics : Map.of("error", "No metrics returned");
        } catch (Exception e) {
            return Map.of("error", "Failed to fetch metrics: " + e.getMessage());
        }
    }
}
