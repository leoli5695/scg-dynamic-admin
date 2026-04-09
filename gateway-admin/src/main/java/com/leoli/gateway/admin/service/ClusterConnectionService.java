package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.custom.Quantity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages cluster connectivity: health checks, connection verification, and ApiClient caching.
 */
@Slf4j
@Service
public class ClusterConnectionService {

    private final KubernetesClusterRepository clusterRepository;
    private final KubeConfigService kubeConfigService;
    private final Map<Long, ApiClient> clientCache = new HashMap<>();

    @Value("${gateway.kubernetes.enabled:true}")
    private boolean kubernetesEnabled;

    public ClusterConnectionService(KubernetesClusterRepository clusterRepository, KubeConfigService kubeConfigService) {
        this.clusterRepository = clusterRepository;
        this.kubeConfigService = kubeConfigService;
    }

    @Scheduled(fixedRate = 30000)
    @Transactional
    public void checkClusterConnectivity() {
        if (!kubernetesEnabled) {
            log.debug("Kubernetes is disabled, skipping cluster connectivity check");
            return;
        }
        log.debug("Starting cluster connectivity check...");
        List<KubernetesCluster> enabledClusters = clusterRepository.findByEnabledTrue();
        for (KubernetesCluster cluster : enabledClusters) {
            try {
                verifyClusterConnection(cluster);
            } catch (Exception e) {
                log.error("Error checking cluster {}: {}", cluster.getClusterName(), e.getMessage());
                cluster.setConnectionStatus("ERROR");
                cluster.setLastCheckedAt(LocalDateTime.now());
                clusterRepository.save(cluster);
            }
        }
        log.debug("Cluster connectivity check completed for {} clusters", enabledClusters.size());
    }

    public void verifyClusterConnection(KubernetesCluster cluster) {
        ApiClient client = kubeConfigService.createApiClient(cluster.getKubeconfig());
        if (client == null) { markClusterError(cluster); return; }

        try {
            CoreV1Api api = new CoreV1Api(client);
            V1NodeList nodes = api.listNode().execute();
            cluster.setConnectionStatus("CONNECTED");
            cluster.setLastCheckedAt(LocalDateTime.now());
            cluster.setNodeCount(nodes.getItems().size());

            if (!nodes.getItems().isEmpty()) {
                V1Node firstNode = nodes.getItems().get(0);
                if (firstNode.getStatus() != null && firstNode.getStatus().getNodeInfo() != null) {
                    cluster.setClusterVersion(firstNode.getStatus().getNodeInfo().getKubeletVersion());
                }
            }

            double[] totals = calculateClusterResources(nodes);
            cluster.setTotalCpuCores(totals[0]);
            cluster.setTotalMemoryGb(totals[1]);
            cluster.setPodCount(countRunningPods(api, cluster.getClusterName()));

            V1NamespaceList namespaces = api.listNamespace().execute();
            cluster.setNamespaceCount(namespaces.getItems().size());

            clientCache.put(cluster.getId(), client);
            log.info("Cluster {} connected successfully, version: {}, nodes: {}",
                    cluster.getClusterName(), cluster.getClusterVersion(), cluster.getNodeCount());
        } catch (ApiException e) {
            log.error("API error verifying cluster {}: code={}, message={}, body={}",
                    cluster.getClusterName(), e.getCode(), e.getMessage(), e.getResponseBody());
            markClusterError(cluster);
        } catch (Exception e) {
            log.error("Unexpected error verifying cluster {}: {}", cluster.getClusterName(), e.getMessage(), e);
            markClusterError(cluster);
        }
        clusterRepository.save(cluster);
    }

    public ApiClient getApiClient(Long clusterId) {
        KubernetesCluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found: " + clusterId));
        if (!cluster.getEnabled()) {
            throw new IllegalArgumentException("Cluster is disabled: " + cluster.getClusterName());
        }
        ApiClient client = clientCache.get(clusterId);
        if (client == null) {
            client = kubeConfigService.createApiClient(cluster.getKubeconfig());
            if (client != null) clientCache.put(clusterId, client);
        }
        return client;
    }

    public void evictClient(Long clusterId) { clientCache.remove(clusterId); }

    public Map<String, Object> testKubeconfigConnection(String kubeconfigContent) {
        Map<String, Object> result = new HashMap<>();
        result.put("serverUrl", kubeConfigService.extractServerUrl(kubeconfigContent));
        result.put("contextName", kubeConfigService.extractCurrentContext(kubeconfigContent));

        ApiClient client = kubeConfigService.createApiClient(kubeconfigContent);
        if (client == null) {
            result.put("success", false);
            result.put("error", "Invalid kubeconfig format");
            return result;
        }

        try {
            CoreV1Api api = new CoreV1Api(client);
            V1NodeList nodes = api.listNode().execute();
            result.put("success", true);
            result.put("nodeCount", nodes.getItems().size());
            if (!nodes.getItems().isEmpty()) {
                V1Node firstNode = nodes.getItems().get(0);
                if (firstNode.getStatus() != null && firstNode.getStatus().getNodeInfo() != null) {
                    result.put("clusterVersion", firstNode.getStatus().getNodeInfo().getKubeletVersion());
                }
            }
            V1NamespaceList namespaces = api.listNamespace().execute();
            result.put("namespaceCount", namespaces.getItems().size());
        } catch (ApiException e) {
            log.error("API error testing kubeconfig: {}", e.getMessage());
            result.put("success", false);
            result.put("error", "Connection failed: " + e.getMessage());
            result.put("errorCode", e.getCode());
        } catch (Exception e) {
            log.error("Error testing kubeconfig: {}", e.getMessage());
            result.put("success", false);
            result.put("error", "Connection failed: " + e.getMessage());
        }
        return result;
    }

    private void markClusterError(KubernetesCluster cluster) {
        cluster.setConnectionStatus("ERROR");
        cluster.setLastCheckedAt(LocalDateTime.now());
    }

    private double[] calculateClusterResources(V1NodeList nodes) {
        double totalCpu = 0.0, totalMemoryGb = 0.0;
        for (V1Node node : nodes.getItems()) {
            if (node.getStatus() != null && node.getStatus().getCapacity() != null) {
                Quantity cpu = node.getStatus().getCapacity().get("cpu");
                if (cpu != null) totalCpu += parseQuantityToDouble(cpu);
                Quantity memory = node.getStatus().getCapacity().get("memory");
                if (memory != null) totalMemoryGb += parseQuantityToDouble(memory) / (1024 * 1024 * 1024);
            }
        }
        return new double[]{totalCpu, totalMemoryGb};
    }

    private int countRunningPods(CoreV1Api api, String clusterName) {
        try {
            V1PodList pods = api.listPodForAllNamespaces().execute();
            int count = 0;
            if (pods.getItems() != null) {
                for (V1Pod pod : pods.getItems()) {
                    if (pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase())) count++;
                }
            }
            log.debug("Found {} running pods in cluster {}", count, clusterName);
            return count;
        } catch (Exception e) {
            log.warn("Failed to list pods for cluster {}: {}", clusterName, e.getMessage());
            return 0;
        }
    }

    private double parseQuantityToDouble(Quantity quantity) {
        if (quantity == null) return 0.0;
        String amount = quantity.toString();
        if (amount == null || amount.isEmpty()) return 0.0;
        try {
            if (amount.endsWith("Ki")) return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024;
            if (amount.endsWith("Mi")) return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024 * 1024;
            if (amount.endsWith("Gi")) return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024L * 1024L * 1024L;
            if (amount.endsWith("Ti")) return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024L * 1024L * 1024L * 1024L;
            if (amount.endsWith("Pi")) return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024L * 1024L * 1024L * 1024L * 1024L;
            if (amount.endsWith("Ei")) return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024L * 1024L * 1024L * 1024L * 1024L * 1024L;
            if (amount.endsWith("k")) return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000;
            if (amount.endsWith("M")) return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000 * 1000;
            if (amount.endsWith("G")) return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000L * 1000L * 1000L;
            if (amount.endsWith("T")) return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000L * 1000L * 1000L * 1000L;
            if (amount.endsWith("P")) return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000L * 1000L * 1000L * 1000L * 1000L;
            if (amount.endsWith("E")) return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000L * 1000L * 1000L * 1000L * 1000L * 1000L;
            if (amount.endsWith("m")) return Double.parseDouble(amount.substring(0, amount.length() - 1)) / 1000.0;
            if (amount.endsWith("u") || amount.endsWith("\u00b5")) return Double.parseDouble(amount.substring(0, amount.length() - 1)) / 1000000.0;
            if (amount.endsWith("n")) return Double.parseDouble(amount.substring(0, amount.length() - 1)) / 1000000000.0;
            return Double.parseDouble(amount);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse quantity: {}", amount);
            return 0.0;
        }
    }
}
