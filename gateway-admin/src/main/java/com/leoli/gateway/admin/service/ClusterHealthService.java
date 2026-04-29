package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.V1NodeStatus;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cluster Health Service.
 * Calculates cluster health score based on node status, resource usage, and pod health.
 *
 * @author leoli
 */
@Slf4j
@Service
public class ClusterHealthService {

    @Autowired
    private KubernetesClusterRepository clusterRepository;

    @Autowired
    private GatewayInstanceService instanceService;

    // Cache for ApiClients by cluster ID
    private final Map<Long, ApiClient> clientCache = new HashMap<>();

    /**
     * Get cluster health score for a specific instance.
     * Score ranges from 0-100, higher is better.
     *
     * @param instanceId Gateway instance ID
     * @return Map containing health score and details
     */
    public Map<String, Object> getInstanceClusterHealth(String instanceId) {
        GatewayInstanceEntity instance = instanceService.getInstanceByInstanceId(instanceId);
        return getClusterHealth(instance.getClusterId(), instance.getNamespace());
    }

    /**
     * Get cluster health score by cluster ID.
     *
     * @param clusterId Cluster database ID
     * @param namespace Namespace to check pod health (optional)
     * @return Map containing health score and details
     */
    public Map<String, Object> getClusterHealth(Long clusterId, String namespace) {
        KubernetesCluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null) {
            log.warn("Cluster not found: {}", clusterId);
            Map<String, Object> result = new HashMap<>();
            result.put("score", 0);
            result.put("level", "Unknown");
            result.put("error", "Cluster not found: " + clusterId);
            result.put("clusterId", clusterId);
            result.put("namespace", namespace);
            return result;
        }

        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        CoreV1Api coreApi = new CoreV1Api(client);

        try {
            // 1. Node Health Score (40% weight)
            Map<String, Object> nodeHealth = calculateNodeHealth(coreApi);

            // 2. Pod Health Score (40% weight) - for the namespace
            Map<String, Object> podHealth = calculatePodHealth(coreApi, namespace);

            // 3. Resource Availability Score (20% weight)
            Map<String, Object> resourceScore = calculateResourceScore(coreApi);

            // Calculate overall score
            int nodeScore = (int) nodeHealth.get("score");
            int podScore = (int) podHealth.get("score");
            int resourceAvailabilityScore = (int) resourceScore.get("score");

            // Weighted average: Node 40%, Pod 40%, Resource 20%
            int overallScore = (int) (nodeScore * 0.4 + podScore * 0.4 + resourceAvailabilityScore * 0.2);

            // Generate issues list
            List<Map<String, Object>> issues = new ArrayList<>();
            
            // Node issues
            if ((int) nodeHealth.get("notReadyNodes") > 0) {
                Map<String, Object> issue = new HashMap<>();
                issue.put("type", "node");
                issue.put("severity", "critical");
                issue.put("message", nodeHealth.get("details"));
                issues.add(issue);
            }
            
            // Pod issues
            List<Map<String, Object>> problematicPods = (List<Map<String, Object>>) podHealth.get("problematicPods");
            if (problematicPods != null) {
                for (Map<String, Object> pod : problematicPods) {
                    Map<String, Object> issue = new HashMap<>();
                    issue.put("type", "pod");
                    String phase = (String) pod.get("phase");
                    int restartCount = (int) pod.get("restartCount");
                    String podMessage = (String) pod.get("message");
                    
                    if (!"Running".equals(phase)) {
                        issue.put("severity", "critical");
                        issue.put("message", String.format("Pod %s is in %s state: %s", 
                                pod.get("name"), phase, podMessage != null ? podMessage : "no message"));
                    } else if (restartCount > 5) {
                        issue.put("severity", "high");
                        issue.put("message", String.format("Pod %s has %d restarts, may be crashing", 
                                pod.get("name"), restartCount));
                    } else if (restartCount > 3) {
                        issue.put("severity", "warning");
                        issue.put("message", String.format("Pod %s has %d restarts", 
                                pod.get("name"), restartCount));
                    }
                    issue.put("podName", pod.get("name"));
                    issue.put("podPhase", phase);
                    issue.put("restartCount", restartCount);
                    issues.add(issue);
                }
            }
            
            // Resource issues
            int totalNodes = (int) resourceScore.get("totalNodes");
            if (totalNodes < 3) {
                Map<String, Object> issue = new HashMap<>();
                issue.put("type", "resource");
                issue.put("severity", totalNodes == 1 ? "warning" : "info");
                issue.put("message", resourceScore.get("details"));
                issues.add(issue);
            }

            // Generate recommendations
            List<String> recommendations = new ArrayList<>();
            if (overallScore < 60) {
                recommendations.add("集群健康状态较差，建议立即排查问题");
            }
            if ((int) nodeHealth.get("notReadyNodes") > 0) {
                recommendations.add("存在不可用节点，请检查节点状态和网络连接");
            }
            if ((int) podHealth.get("totalRestarts") > 10) {
                recommendations.add("Pod重启次数较多，建议检查应用日志排查崩溃原因");
            }
            if ((int) podHealth.get("pendingPods") > 0) {
                recommendations.add("存在Pending状态的Pod，可能资源不足或调度限制问题");
            }
            if (totalNodes < 2) {
                recommendations.add("建议增加节点数量以提高集群可用性");
            }
            if (overallScore >= 80) {
                recommendations.add("集群健康状态良好");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("score", overallScore);
            result.put("level", getHealthLevel(overallScore));
            result.put("nodeHealth", nodeHealth);
            result.put("podHealth", podHealth);
            result.put("resourceScore", resourceScore);
            result.put("clusterId", clusterId);
            result.put("clusterName", cluster.getClusterName());
            result.put("namespace", namespace);
            result.put("issues", issues);
            result.put("recommendations", recommendations);
            result.put("timestamp", System.currentTimeMillis());

            return result;
        } catch (ApiException e) {
            log.error("Failed to get cluster health: {}", e.getMessage());
            throw new RuntimeException("Failed to get cluster health: " + e.getMessage());
        }
    }

    /**
     * Calculate node health score.
     * Based on node readiness status.
     */
    private Map<String, Object> calculateNodeHealth(CoreV1Api coreApi) throws ApiException {
        V1NodeList nodes = coreApi.listNode().execute();

        int totalNodes = nodes.getItems() != null ? nodes.getItems().size() : 0;
        if (totalNodes == 0) {
            Map<String, Object> result = new HashMap<>();
            result.put("score", 0);
            result.put("totalNodes", 0);
            result.put("readyNodes", 0);
            result.put("notReadyNodes", 0);
            result.put("details", "No nodes found");
            result.put("nodeDetails", List.of());
            return result;
        }

        int readyNodes = 0;
        int notReadyNodes = 0;
        StringBuilder details = new StringBuilder();
        List<Map<String, Object>> nodeDetails = new ArrayList<>();

        for (V1Node node : nodes.getItems()) {
            Map<String, Object> nodeInfo = new HashMap<>();
            nodeInfo.put("name", node.getMetadata().getName());
            nodeInfo.put("labels", node.getMetadata().getLabels());
            
            V1NodeStatus status = node.getStatus();
            if (status != null) {
                // Check readiness
                boolean isReady = status.getConditions() != null &&
                        status.getConditions().stream()
                                .anyMatch(cond -> "Ready".equals(cond.getType()) && "True".equals(cond.getStatus()));
                nodeInfo.put("ready", isReady);
                
                // Get conditions (all conditions, not just Ready)
                if (status.getConditions() != null) {
                    List<Map<String, Object>> conditions = new ArrayList<>();
                    for (var cond : status.getConditions()) {
                        if (!"Ready".equals(cond.getType())) {
                            // Only include non-Ready conditions if they are not True
                            if (!"True".equals(cond.getStatus())) {
                                Map<String, Object> condInfo = new HashMap<>();
                                condInfo.put("type", cond.getType());
                                condInfo.put("status", cond.getStatus());
                                condInfo.put("reason", cond.getReason());
                                condInfo.put("message", cond.getMessage());
                                conditions.add(condInfo);
                            }
                        }
                    }
                    nodeInfo.put("conditions", conditions);
                }
                
                // Get addresses
                if (status.getAddresses() != null) {
                    Map<String, String> addresses = new HashMap<>();
                    for (var addr : status.getAddresses()) {
                        addresses.put(addr.getType(), addr.getAddress());
                    }
                    nodeInfo.put("addresses", addresses);
                }
                
                // Get allocatable resources
                if (status.getAllocatable() != null) {
                    Map<String, String> allocatable = new HashMap<>();
                    allocatable.put("cpu", status.getAllocatable().get("cpu") != null ? 
                            status.getAllocatable().get("cpu").toString() : "N/A");
                    allocatable.put("memory", status.getAllocatable().get("memory") != null ? 
                            status.getAllocatable().get("memory").toString() : "N/A");
                    allocatable.put("pods", status.getAllocatable().get("pods") != null ? 
                            status.getAllocatable().get("pods").toString() : "N/A");
                    nodeInfo.put("allocatable", allocatable);
                }
                
                // Get node info (kernel version, OS, etc.)
                if (status.getNodeInfo() != null) {
                    Map<String, String> nodeSystemInfo = new HashMap<>();
                    nodeSystemInfo.put("kernelVersion", status.getNodeInfo().getKernelVersion());
                    nodeSystemInfo.put("osImage", status.getNodeInfo().getOsImage());
                    nodeSystemInfo.put("kubeletVersion", status.getNodeInfo().getKubeletVersion());
                    nodeSystemInfo.put("containerRuntime", status.getNodeInfo().getContainerRuntimeVersion());
                    nodeInfo.put("nodeInfo", nodeSystemInfo);
                }
            }
            
            nodeDetails.add(nodeInfo);
            
            if (status != null && status.getConditions() != null) {
                boolean isReady = status.getConditions().stream()
                        .anyMatch(cond -> "Ready".equals(cond.getType()) && "True".equals(cond.getStatus()));
                if (isReady) {
                    readyNodes++;
                } else {
                    notReadyNodes++;
                    if (details.length() > 0) details.append("; ");
                    details.append("Node ").append(node.getMetadata().getName()).append(" not ready");
                }
            }
        }

        // Score = (readyNodes / totalNodes) * 100
        int score = (int) ((readyNodes / (double) totalNodes) * 100);

        Map<String, Object> result = new HashMap<>();
        result.put("score", score);
        result.put("totalNodes", totalNodes);
        result.put("readyNodes", readyNodes);
        result.put("notReadyNodes", notReadyNodes);
        result.put("details", details.length() > 0 ? details.toString() : "All nodes ready");
        result.put("nodeDetails", nodeDetails);

        return result;
    }

    /**
     * Calculate pod health score for a namespace.
     * Based on pod running status and restart counts.
     */
    private Map<String, Object> calculatePodHealth(CoreV1Api coreApi, String namespace) throws ApiException {
        V1PodList pods;
        if (namespace != null && !namespace.isEmpty()) {
            pods = coreApi.listNamespacedPod(namespace).execute();
        } else {
            pods = coreApi.listPodForAllNamespaces().execute();
        }

        int totalPods = pods.getItems() != null ? pods.getItems().size() : 0;
        if (totalPods == 0) {
            Map<String, Object> result = new HashMap<>();
            result.put("score", 100); // No pods = healthy (nothing to worry about)
            result.put("totalPods", 0);
            result.put("runningPods", 0);
            result.put("failedPods", 0);
            result.put("pendingPods", 0);
            result.put("details", "No pods in namespace");
            result.put("podDetails", List.of());
            return result;
        }

        int runningPods = 0;
        int failedPods = 0;
        int pendingPods = 0;
        int totalRestarts = 0;
        StringBuilder details = new StringBuilder();
        List<Map<String, Object>> podDetails = new ArrayList<>();

        for (V1Pod pod : pods.getItems()) {
            Map<String, Object> podInfo = new HashMap<>();
            podInfo.put("name", pod.getMetadata().getName());
            podInfo.put("namespace", pod.getMetadata().getNamespace());
            
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
            podInfo.put("phase", phase);
            podInfo.put("podIP", pod.getStatus() != null ? pod.getStatus().getPodIP() : null);
            podInfo.put("startTime", pod.getStatus() != null ? pod.getStatus().getStartTime() : null);
            
            int podRestarts = 0;
            if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                List<Map<String, Object>> containers = new ArrayList<>();
                for (var containerStatus : pod.getStatus().getContainerStatuses()) {
                    Map<String, Object> containerInfo = new HashMap<>();
                    containerInfo.put("name", containerStatus.getName());
                    containerInfo.put("ready", containerStatus.getReady());
                    containerInfo.put("restartCount", containerStatus.getRestartCount() != null ? containerStatus.getRestartCount() : 0);
                    containerInfo.put("image", containerStatus.getImage());
                    containerInfo.put("state", containerStatus.getState() != null ? 
                            (containerStatus.getState().getRunning() != null ? "Running" :
                             containerStatus.getState().getWaiting() != null ? "Waiting: " + containerStatus.getState().getWaiting().getReason() :
                             containerStatus.getState().getTerminated() != null ? "Terminated: " + containerStatus.getState().getTerminated().getReason() : "Unknown") : "Unknown");
                    containers.add(containerInfo);
                    podRestarts += containerStatus.getRestartCount() != null ? containerStatus.getRestartCount() : 0;
                }
                podInfo.put("containers", containers);
            }
            podInfo.put("restartCount", podRestarts);
            
            // Get events for this pod (recent warnings)
            String podMessage = null;
            if (pod.getStatus() != null && pod.getStatus().getMessage() != null) {
                podMessage = pod.getStatus().getMessage();
            }
            if (pod.getStatus() != null && pod.getStatus().getReason() != null) {
                podMessage = (podMessage != null ? podMessage + " - " : "") + pod.getStatus().getReason();
            }
            podInfo.put("message", podMessage);
            
            podDetails.add(podInfo);
            
            switch (phase) {
                case "Running":
                    runningPods++;
                    totalRestarts += podRestarts;
                    break;
                case "Failed":
                    failedPods++;
                    if (details.length() > 0) details.append("; ");
                    details.append("Pod ").append(pod.getMetadata().getName()).append(" failed");
                    break;
                case "Pending":
                    pendingPods++;
                    break;
                default:
                    // Other states (Succeeded, Unknown) count as neutral
                    break;
            }
        }

        // Base score from running ratio
        double runningRatio = runningPods / (double) totalPods;
        int baseScore = (int) (runningRatio * 80);

        // Penalty for restarts (max -20 points)
        int restartPenalty = Math.min(20, totalRestarts * 2);

        // Penalty for failed pods (max -20 points)
        int failedPenalty = Math.min(20, failedPods * 5);

        int score = Math.max(0, baseScore - restartPenalty - failedPenalty);

        // Find problematic pods
        List<Map<String, Object>> problematicPods = new ArrayList<>();
        for (Map<String, Object> pod : podDetails) {
            String podPhase = (String) pod.get("phase");
            int restartCount = (int) pod.get("restartCount");
            if (!"Running".equals(podPhase) || restartCount > 3 || pod.get("message") != null) {
                problematicPods.add(pod);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("score", score);
        result.put("totalPods", totalPods);
        result.put("runningPods", runningPods);
        result.put("failedPods", failedPods);
        result.put("pendingPods", pendingPods);
        result.put("totalRestarts", totalRestarts);
        result.put("details", details.length() > 0 ? details.toString() : "All pods healthy");
        result.put("podDetails", podDetails);
        result.put("problematicPods", problematicPods);

        return result;
    }

    /**
     * Calculate resource availability score.
     * Simple check based on node count and availability.
     */
    private Map<String, Object> calculateResourceScore(CoreV1Api coreApi) throws ApiException {
        V1NodeList nodes = coreApi.listNode().execute();

        int totalNodes = nodes.getItems() != null ? nodes.getItems().size() : 0;

        // Simple scoring: 3+ nodes = 100, 2 nodes = 80, 1 node = 60, 0 nodes = 0
        int score;
        if (totalNodes >= 3) {
            score = 100;
        } else if (totalNodes == 2) {
            score = 80;
        } else if (totalNodes == 1) {
            score = 60;
        } else {
            score = 0;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("score", score);
        result.put("totalNodes", totalNodes);
        result.put("details", totalNodes >= 3 ? "Good redundancy" :
                totalNodes == 2 ? "Minimal redundancy" :
                totalNodes == 1 ? "No redundancy (single node)" : "No nodes available");

        return result;
    }

    /**
     * Get health level description based on score.
     */
    private String getHealthLevel(int score) {
        if (score >= 90) return "Excellent";
        if (score >= 80) return "Good";
        if (score >= 60) return "Fair";
        if (score >= 40) return "Poor";
        return "Critical";
    }

    /**
     * Get ApiClient for a cluster.
     */
    private ApiClient getApiClient(Long clusterId, String kubeconfig) {
        ApiClient client = clientCache.get(clusterId);
        if (client == null) {
            try {
                StringReader reader = new StringReader(kubeconfig);
                KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);
                client = ClientBuilder.kubeconfig(kubeConfig).build();
                client.setVerifyingSsl(false);
                clientCache.put(clusterId, client);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create Kubernetes client: " + e.getMessage());
            }
        }
        return client;
    }

    /**
     * Get cluster resource metrics for an instance.
     * Returns node and pod resource usage information.
     *
     * @param instanceId Gateway instance ID
     * @return Map containing resource metrics
     */
    public Map<String, Object> getInstanceResourceMetrics(String instanceId) {
        GatewayInstanceEntity instance = instanceService.getInstanceByInstanceId(instanceId);
        return getResourceMetrics(instance.getClusterId(), instance.getNamespace(), instance.getInstanceId());
    }

    /**
     * Get resource metrics for a cluster/namespace.
     *
     * @param clusterId  Cluster database ID
     * @param namespace  Namespace to check
     * @param instanceId Gateway instance ID for pod filtering
     * @return Map containing resource metrics
     */
    public Map<String, Object> getResourceMetrics(Long clusterId, String namespace, String instanceId) {
        KubernetesCluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null) {
            log.warn("Cluster not found: {}", clusterId);
            Map<String, Object> result = new HashMap<>();
            result.put("clusterId", clusterId);
            result.put("namespace", namespace);
            result.put("error", "Cluster not found: " + clusterId);
            result.put("nodes", Map.of("total", 0, "list", List.of()));
            result.put("pods", Map.of("total", 0, "running", 0, "list", List.of()));
            return result;
        }

        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        CoreV1Api coreApi = new CoreV1Api(client);

        try {
            // Get node metrics
            Map<String, Object> nodeMetrics = getNodeMetrics(coreApi);

            // Get pod metrics for the instance
            Map<String, Object> podMetrics = getPodMetrics(coreApi, namespace, instanceId);

            Map<String, Object> result = new HashMap<>();
            result.put("clusterId", clusterId);
            result.put("clusterName", cluster.getClusterName());
            result.put("namespace", namespace);
            result.put("nodes", nodeMetrics);
            result.put("pods", podMetrics);

            return result;
        } catch (ApiException e) {
            log.error("Failed to get resource metrics: {}", e.getMessage());
            throw new RuntimeException("Failed to get resource metrics: " + e.getMessage());
        }
    }

    /**
     * Get node resource metrics.
     */
    private Map<String, Object> getNodeMetrics(CoreV1Api coreApi) throws ApiException {
        V1NodeList nodes = coreApi.listNode().execute();

        List<Map<String, Object>> nodeList = new ArrayList<>();
        int totalNodes = nodes.getItems() != null ? nodes.getItems().size() : 0;

        for (V1Node node : nodes.getItems()) {
            Map<String, Object> nodeInfo = new HashMap<>();
            nodeInfo.put("name", node.getMetadata().getName());

            // Get node status
            if (node.getStatus() != null) {
                // Check if node is ready
                boolean isReady = node.getStatus().getConditions() != null &&
                        node.getStatus().getConditions().stream()
                                .anyMatch(cond -> "Ready".equals(cond.getType()) && "True".equals(cond.getStatus()));
                nodeInfo.put("ready", isReady);

                // Get node addresses
                List<Map<String, String>> addresses = new ArrayList<>();
                if (node.getStatus().getAddresses() != null) {
                    for (var addr : node.getStatus().getAddresses()) {
                        Map<String, String> addressInfo = new HashMap<>();
                        addressInfo.put("type", addr.getType());
                        addressInfo.put("address", addr.getAddress());
                        addresses.add(addressInfo);
                    }
                }
                nodeInfo.put("addresses", addresses);

                // Get allocatable resources (capacity)
                if (node.getStatus().getAllocatable() != null) {
                    Map<String, Object> allocatable = new HashMap<>();
                    var cpu = node.getStatus().getAllocatable().get("cpu");
                    var memory = node.getStatus().getAllocatable().get("memory");
                    var pods = node.getStatus().getAllocatable().get("pods");
                    if (cpu != null) allocatable.put("cpu", cpu.toString());
                    if (memory != null) allocatable.put("memory", memory.toString());
                    if (pods != null) allocatable.put("pods", pods.toString());
                    nodeInfo.put("allocatable", allocatable);
                }
            }
            nodeList.add(nodeInfo);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", totalNodes);
        result.put("list", nodeList);
        return result;
    }

    /**
     * Get pod resource metrics for a namespace.
     */
    private Map<String, Object> getPodMetrics(CoreV1Api coreApi, String namespace, String instanceId) throws ApiException {
        String labelSelector = "gateway-instance-id=" + instanceId;
        V1PodList pods = coreApi.listNamespacedPod(namespace)
                .labelSelector(labelSelector)
                .execute();

        List<Map<String, Object>> podList = new ArrayList<>();
        int totalPods = pods.getItems() != null ? pods.getItems().size() : 0;
        int runningPods = 0;

        for (V1Pod pod : pods.getItems()) {
            Map<String, Object> podInfo = new HashMap<>();
            podInfo.put("name", pod.getMetadata().getName());
            podInfo.put("namespace", pod.getMetadata().getNamespace());

            if (pod.getStatus() != null) {
                podInfo.put("phase", pod.getStatus().getPhase());
                podInfo.put("podIP", pod.getStatus().getPodIP());
                podInfo.put("startTime", pod.getStatus().getStartTime());

                if ("Running".equals(pod.getStatus().getPhase())) {
                    runningPods++;
                }

                // Container statuses
                if (pod.getStatus().getContainerStatuses() != null) {
                    List<Map<String, Object>> containers = new ArrayList<>();
                    for (var container : pod.getStatus().getContainerStatuses()) {
                        Map<String, Object> containerInfo = new HashMap<>();
                        containerInfo.put("name", container.getName());
                        containerInfo.put("ready", container.getReady());
                        containerInfo.put("restartCount", container.getRestartCount());
                        containerInfo.put("image", container.getImage());
                        containers.add(containerInfo);
                    }
                    podInfo.put("containers", containers);
                }
            }
            podList.add(podInfo);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", totalPods);
        result.put("running", runningPods);
        result.put("list", podList);
        return result;
    }
}