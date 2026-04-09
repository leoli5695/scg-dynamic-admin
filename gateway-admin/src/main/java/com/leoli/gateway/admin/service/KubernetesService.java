package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.CoreV1EventList;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Kubernetes management service.
 * Handles kubeconfig import, cluster connection, and deployment operations.
 *
 * @author leoli
 */
@Slf4j
@Service
public class KubernetesService {

    @Autowired
    private KubernetesClusterRepository clusterRepository;

    // Cache for ApiClients by cluster ID
    private final Map<Long, ApiClient> clientCache = new HashMap<>();

    /**
     * Scheduled task to check cluster connectivity every 30 seconds.
     */
    @Scheduled(fixedRate = 30000)
    @Transactional
    public void checkClusterConnectivity() {
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

    /**
     * Import a kubeconfig and create a cluster record.
     */
    @Transactional
    public KubernetesCluster importKubeconfig(String clusterName, String kubeconfigContent, String description) {
        // Check if cluster name already exists
        if (clusterRepository.existsByClusterName(clusterName)) {
            throw new IllegalArgumentException("Cluster name already exists: " + clusterName);
        }

        // Parse kubeconfig to extract server URL and context
        String serverUrl = extractServerUrl(kubeconfigContent);
        String contextName = extractCurrentContext(kubeconfigContent);

        KubernetesCluster cluster = new KubernetesCluster();
        cluster.setClusterName(clusterName);
        cluster.setKubeconfig(kubeconfigContent);
        cluster.setServerUrl(serverUrl);
        cluster.setContextName(contextName);
        cluster.setDescription(description);
        cluster.setEnabled(true);
        cluster.setConnectionStatus("IMPORTED");

        cluster = clusterRepository.save(cluster);

        // Try to connect and verify
        try {
            verifyClusterConnection(cluster);
        } catch (Exception e) {
            log.warn("Failed to verify cluster connection for {}: {}", clusterName, e.getMessage());
            cluster.setConnectionStatus("CONNECTION_FAILED");
            clusterRepository.save(cluster);
        }

        return cluster;
    }

    /**
     * Verify cluster connection by calling Kubernetes API.
     */
    public void verifyClusterConnection(KubernetesCluster cluster) {
        try {
            ApiClient client = createApiClient(cluster);
            if (client == null) {
                log.error("Failed to create ApiClient for cluster {}", cluster.getClusterName());
                cluster.setConnectionStatus("ERROR");
                cluster.setLastCheckedAt(LocalDateTime.now());
                clusterRepository.save(cluster);
                return;
            }

            CoreV1Api api = new CoreV1Api(client);
            V1NodeList nodes = api.listNode().execute();
            cluster.setConnectionStatus("CONNECTED");
            cluster.setLastCheckedAt(LocalDateTime.now());

            // Get cluster version from nodes
            if (nodes.getItems() != null && !nodes.getItems().isEmpty()) {
                V1Node firstNode = nodes.getItems().get(0);
                if (firstNode.getStatus() != null && firstNode.getStatus().getNodeInfo() != null) {
                    cluster.setClusterVersion(firstNode.getStatus().getNodeInfo().getKubeletVersion());
                }
            }

            // Collect cluster statistics
            cluster.setNodeCount(nodes.getItems().size());
            
            // Calculate total resources
            double totalCpu = 0.0;
            double totalMemoryGb = 0.0;
            
            for (V1Node node : nodes.getItems()) {
                if (node.getStatus() != null && node.getStatus().getCapacity() != null) {
                    // CPU
                    Quantity cpuQuantity = node.getStatus().getCapacity().get("cpu");
                    if (cpuQuantity != null) {
                        totalCpu += parseQuantityToDouble(cpuQuantity);
                    }

                    // Memory
                    Quantity memoryQuantity = node.getStatus().getCapacity().get("memory");
                    if (memoryQuantity != null) {
                        totalMemoryGb += parseQuantityToDouble(memoryQuantity) / (1024 * 1024 * 1024);
                    }
                }
            }
            
            cluster.setTotalCpuCores(totalCpu);
            cluster.setTotalMemoryGb(totalMemoryGb);
            
            // Get actual running pod count from API
            try {
                V1PodList pods = api.listPodForAllNamespaces().execute();
                int runningPods = 0;
                if (pods.getItems() != null) {
                    for (V1Pod pod : pods.getItems()) {
                        if (pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase())) {
                            runningPods++;
                        }
                    }
                }
                cluster.setPodCount(runningPods);
                log.debug("Found {} running pods in cluster {}", runningPods, cluster.getClusterName());
            } catch (Exception e) {
                log.warn("Failed to list pods for cluster {}: {}", cluster.getClusterName(), e.getMessage());
                cluster.setPodCount(0);
            }
            
            // Get namespace count
            V1NamespaceList namespaces = api.listNamespace().execute();
            cluster.setNamespaceCount(namespaces.getItems().size());

            // Cache the client
            clientCache.put(cluster.getId(), client);
            log.info("Cluster {} connected successfully, version: {}, nodes: {}", 
                cluster.getClusterName(), cluster.getClusterVersion(), cluster.getNodeCount());

        } catch (ApiException e) {
            log.error("API error verifying cluster {}: code={}, message={}, body={}",
                    cluster.getClusterName(), e.getCode(), e.getMessage(), e.getResponseBody());
            cluster.setConnectionStatus("ERROR");
            cluster.setLastCheckedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.error("Unexpected error verifying cluster {}: {}", cluster.getClusterName(), e.getMessage(), e);
            cluster.setConnectionStatus("ERROR");
            cluster.setLastCheckedAt(LocalDateTime.now());
        }
        clusterRepository.save(cluster);
    }

    /**
     * Create ApiClient from kubeconfig.
     */
    private ApiClient createApiClient(KubernetesCluster cluster) {
        try {
            StringReader reader = new StringReader(cluster.getKubeconfig());
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);

            ApiClient client = ClientBuilder.kubeconfig(kubeConfig).build();
            // Configure SSL - disable verification for local development
            client.setVerifyingSsl(false);
            Configuration.setDefaultApiClient(client);
            return client;
        } catch (IOException e) {
            log.error("Failed to create ApiClient: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Create ApiClient from kubeconfig string (for testing without saving).
     */
    private ApiClient createApiClientFromKubeconfig(String kubeconfigContent) {
        try {
            StringReader reader = new StringReader(kubeconfigContent);
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);

            ApiClient client = ClientBuilder.kubeconfig(kubeConfig).build();
            // Configure SSL - disable verification for local development
            client.setVerifyingSsl(false);
            return client;
        } catch (IOException e) {
            log.error("Failed to create ApiClient from kubeconfig: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Test kubeconfig connection without saving to database.
     * Returns connection result with cluster info.
     */
    public Map<String, Object> testKubeconfigConnection(String kubeconfigContent) {
        Map<String, Object> result = new HashMap<>();

        // Extract server URL and context
        String serverUrl = extractServerUrl(kubeconfigContent);
        String contextName = extractCurrentContext(kubeconfigContent);

        result.put("serverUrl", serverUrl);
        result.put("contextName", contextName);

        ApiClient client = createApiClientFromKubeconfig(kubeconfigContent);
        if (client == null) {
            result.put("success", false);
            result.put("error", "Invalid kubeconfig format");
            return result;
        }

        CoreV1Api api = new CoreV1Api(client);
        try {
            // Try to list nodes to verify connection
            V1NodeList nodes = api.listNode().execute();

            result.put("success", true);
            result.put("nodeCount", nodes.getItems().size());

            // Get cluster version from first node
            if (!nodes.getItems().isEmpty()) {
                V1Node firstNode = nodes.getItems().get(0);
                if (firstNode.getStatus() != null && firstNode.getStatus().getNodeInfo() != null) {
                    result.put("clusterVersion", firstNode.getStatus().getNodeInfo().getKubeletVersion());
                }
            }

            // Get namespaces
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

    /**
     * Get ApiClient for a cluster (from cache or create new).
     */
    private ApiClient getApiClient(Long clusterId) {
        KubernetesCluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found: " + clusterId));

        if (!cluster.getEnabled()) {
            throw new IllegalArgumentException("Cluster is disabled: " + cluster.getClusterName());
        }

        ApiClient client = clientCache.get(clusterId);
        if (client == null) {
            client = createApiClient(cluster);
            if (client != null) {
                clientCache.put(clusterId, client);
            }
        }
        return client;
    }

    /**
     * Extract server URL from kubeconfig.
     */
    @SuppressWarnings("unchecked")
    private String extractServerUrl(String kubeconfigContent) {
        try {
            StringReader reader = new StringReader(kubeconfigContent);
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);

            // Get current context
            String currentContext = kubeConfig.getCurrentContext();
            if (currentContext == null) return null;

            // Find context
            List<?> contexts = kubeConfig.getContexts();
            if (contexts == null) return null;
            
            for (Object ctx : contexts) {
                if (!(ctx instanceof Map)) continue;
                Map<String, Object> context = (Map<String, Object>) ctx;
                Object nameObj = context.get("name");
                if (nameObj == null || !nameObj.equals(currentContext)) continue;
                
                Object contextDetailObj = context.get("context");
                if (!(contextDetailObj instanceof Map)) continue;
                Map<String, Object> contextDetail = (Map<String, Object>) contextDetailObj;
                
                Object clusterNameObj = contextDetail.get("cluster");
                if (!(clusterNameObj instanceof String)) continue;
                String clusterName = (String) clusterNameObj;

                // Find cluster
                List<?> clusters = kubeConfig.getClusters();
                if (clusters == null) continue;
                
                for (Object cls : clusters) {
                    if (!(cls instanceof Map)) continue;
                    Map<String, Object> cluster = (Map<String, Object>) cls;
                    Object clusterNameInList = cluster.get("name");
                    if (clusterNameInList == null || !clusterNameInList.equals(clusterName)) continue;
                    
                    Object clusterDetailObj = cluster.get("cluster");
                    if (!(clusterDetailObj instanceof Map)) continue;
                    Map<String, Object> clusterDetail = (Map<String, Object>) clusterDetailObj;
                    
                    Object serverObj = clusterDetail.get("server");
                    return serverObj != null ? serverObj.toString() : null;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract server URL: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract current context from kubeconfig.
     */
    private String extractCurrentContext(String kubeconfigContent) {
        try {
            StringReader reader = new StringReader(kubeconfigContent);
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);
            return kubeConfig.getCurrentContext();
        } catch (Exception e) {
            log.warn("Failed to extract current context: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get all clusters.
     */
    public List<KubernetesCluster> getAllClusters() {
        return clusterRepository.findAll();
    }

    /**
     * Get cluster by ID.
     */
    public KubernetesCluster getClusterById(Long id) {
        return clusterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found: " + id));
    }

    /**
     * Update cluster.
     */
    @Transactional
    public KubernetesCluster updateCluster(Long id, String clusterName, String description, Boolean enabled) {
        KubernetesCluster cluster = getClusterById(id);

        if (clusterName != null && !clusterName.equals(cluster.getClusterName())) {
            if (clusterRepository.existsByClusterName(clusterName)) {
                throw new IllegalArgumentException("Cluster name already exists: " + clusterName);
            }
            cluster.setClusterName(clusterName);
        }

        if (description != null) {
            cluster.setDescription(description);
        }

        if (enabled != null) {
            cluster.setEnabled(enabled);
            if (!enabled) {
                clientCache.remove(id);
            }
        }

        return clusterRepository.save(cluster);
    }

    /**
     * Delete cluster.
     */
    @Transactional
    public void deleteCluster(Long id) {
        clientCache.remove(id);
        clusterRepository.deleteById(id);
    }

    /**
     * Refresh cluster connection status.
     */
    @Transactional
    public KubernetesCluster refreshConnection(Long id) {
        KubernetesCluster cluster = getClusterById(id);
        verifyClusterConnection(cluster);
        return cluster;
    }

    /**
     * Get cluster nodes.
     */
    public List<Map<String, Object>> getClusterNodes(Long clusterId) {
        ApiClient client = getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1NodeList nodes = api.listNode().execute();
            List<Map<String, Object>> result = new ArrayList<>();

            for (V1Node node : nodes.getItems()) {
                Map<String, Object> nodeInfo = new HashMap<>();
                nodeInfo.put("name", node.getMetadata().getName());

                if (node.getStatus() != null) {
                    nodeInfo.put("status", node.getStatus().getConditions() != null ?
                            node.getStatus().getConditions().stream()
                                    .filter(c -> "Ready".equals(c.getType()))
                                    .findFirst()
                                    .map(c -> c.getStatus())
                                    .orElse("Unknown") : "Unknown");

                    if (node.getStatus().getNodeInfo() != null) {
                        nodeInfo.put("kubeletVersion", node.getStatus().getNodeInfo().getKubeletVersion());
                        nodeInfo.put("osImage", node.getStatus().getNodeInfo().getOsImage());
                    }
                }

                result.add(nodeInfo);
            }
            return result;
        } catch (ApiException e) {
            log.error("Failed to get nodes: {}", e.getMessage());
            throw new RuntimeException("Failed to get cluster nodes: " + e.getMessage());
        }
    }

    /**
     * Get cluster namespaces.
     */
    public List<String> getClusterNamespaces(Long clusterId) {
        ApiClient client = getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1NamespaceList namespaces = api.listNamespace().execute();
            return namespaces.getItems().stream()
                    .map(ns -> ns.getMetadata().getName())
                    .toList();
        } catch (ApiException e) {
            log.error("Failed to get namespaces: {}", e.getMessage());
            throw new RuntimeException("Failed to get namespaces: " + e.getMessage());
        }
    }

    /**
     * Get deployments in a namespace.
     */
    public List<Map<String, Object>> getDeployments(Long clusterId, String namespace) {
        ApiClient client = getApiClient(clusterId);
        AppsV1Api api = new AppsV1Api(client);

        try {
            // If namespace is empty or null, list deployments from all namespaces
            V1DeploymentList deployments;
            if (namespace == null || namespace.isEmpty()) {
                deployments = api.listDeploymentForAllNamespaces().execute();
            } else {
                deployments = api.listNamespacedDeployment(namespace).execute();
            }
            List<Map<String, Object>> result = new ArrayList<>();

            for (V1Deployment deployment : deployments.getItems()) {
                Map<String, Object> depInfo = new HashMap<>();
                depInfo.put("name", deployment.getMetadata().getName());
                depInfo.put("namespace", deployment.getMetadata().getNamespace());
                depInfo.put("replicas", deployment.getSpec().getReplicas());
                depInfo.put("readyReplicas", deployment.getStatus().getReadyReplicas());
                depInfo.put("availableReplicas", deployment.getStatus().getAvailableReplicas());
                depInfo.put("labels", deployment.getMetadata().getLabels());
                depInfo.put("createdAt", deployment.getMetadata().getCreationTimestamp());

                result.add(depInfo);
            }
            return result;
        } catch (ApiException e) {
            log.error("Failed to get deployments: {}", e.getMessage());
            throw new RuntimeException("Failed to get deployments: " + e.getMessage());
        }
    }

    /**
     * Get pods in a namespace.
     */
    public List<Map<String, Object>> getPods(Long clusterId, String namespace) {
        ApiClient client = getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            // If namespace is empty or null, list pods from all namespaces
            V1PodList pods;
            if (namespace == null || namespace.isEmpty()) {
                pods = api.listPodForAllNamespaces().execute();
            } else {
                pods = api.listNamespacedPod(namespace).execute();
            }
            List<Map<String, Object>> result = new ArrayList<>();

            for (V1Pod pod : pods.getItems()) {
                Map<String, Object> podInfo = new HashMap<>();
                podInfo.put("name", pod.getMetadata().getName());
                podInfo.put("namespace", pod.getMetadata().getNamespace());
                podInfo.put("phase", pod.getStatus().getPhase());
                podInfo.put("podIP", pod.getStatus().getPodIP());
                podInfo.put("labels", pod.getMetadata().getLabels());

                if (pod.getStatus().getContainerStatuses() != null) {
                    List<Map<String, Object>> containers = new ArrayList<>();
                    for (V1ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                        Map<String, Object> containerInfo = new HashMap<>();
                        containerInfo.put("name", status.getName());
                        containerInfo.put("ready", status.getReady());
                        containerInfo.put("restartCount", status.getRestartCount());
                        containers.add(containerInfo);
                    }
                    podInfo.put("containers", containers);
                }

                result.add(podInfo);
            }
            return result;
        } catch (ApiException e) {
            log.error("Failed to get pods: {}", e.getMessage());
            throw new RuntimeException("Failed to get pods: " + e.getMessage());
        }
    }

    /**
     * Get detailed information about a specific pod.
     */
    public Map<String, Object> getPodDetail(Long clusterId, String namespace, String podName) {
        ApiClient client = getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1Pod pod = api.readNamespacedPod(podName, namespace).execute();
            Map<String, Object> result = new HashMap<>();

            // Basic info
            result.put("name", pod.getMetadata().getName());
            result.put("namespace", pod.getMetadata().getNamespace());
            result.put("phase", pod.getStatus().getPhase());
            result.put("podIP", pod.getStatus().getPodIP());
            result.put("hostIP", pod.getStatus().getHostIP());
            result.put("startTime", pod.getStatus().getStartTime());
            result.put("labels", pod.getMetadata().getLabels());
            result.put("annotations", pod.getMetadata().getAnnotations());

            // Node info
            if (pod.getSpec() != null) {
                result.put("nodeName", pod.getSpec().getNodeName());
                result.put("serviceAccountName", pod.getSpec().getServiceAccountName());
                result.put("restartPolicy", pod.getSpec().getRestartPolicy());
                result.put("dnsPolicy", pod.getSpec().getDnsPolicy());
            }

            // Container details
            List<Map<String, Object>> containers = new ArrayList<>();
            if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
                for (V1Container container : pod.getSpec().getContainers()) {
                    Map<String, Object> containerInfo = new HashMap<>();
                    containerInfo.put("name", container.getName());
                    containerInfo.put("image", container.getImage());
                    containerInfo.put("imagePullPolicy", container.getImagePullPolicy());

                    // Ports
                    if (container.getPorts() != null) {
                        List<Map<String, Object>> ports = new ArrayList<>();
                        for (V1ContainerPort port : container.getPorts()) {
                            Map<String, Object> portInfo = new HashMap<>();
                            portInfo.put("containerPort", port.getContainerPort());
                            portInfo.put("protocol", port.getProtocol());
                            portInfo.put("name", port.getName());
                            ports.add(portInfo);
                        }
                        containerInfo.put("ports", ports);
                    }

                    // Environment variables
                    if (container.getEnv() != null) {
                        List<Map<String, String>> envVars = new ArrayList<>();
                        for (V1EnvVar env : container.getEnv()) {
                            Map<String, String> envInfo = new HashMap<>();
                            envInfo.put("name", env.getName());
                            envInfo.put("value", env.getValue());
                            envVars.add(envInfo);
                        }
                        containerInfo.put("env", envVars);
                    }

                    // Resource limits
                    if (container.getResources() != null) {
                        Map<String, Object> resources = new HashMap<>();
                        if (container.getResources().getRequests() != null) {
                            resources.put("requests", container.getResources().getRequests());
                        }
                        if (container.getResources().getLimits() != null) {
                            resources.put("limits", container.getResources().getLimits());
                        }
                        containerInfo.put("resources", resources);
                    }

                    containers.add(containerInfo);
                }
            }
            result.put("containers", containers);

            // Container statuses
            if (pod.getStatus().getContainerStatuses() != null) {
                List<Map<String, Object>> containerStatuses = new ArrayList<>();
                for (V1ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                    Map<String, Object> statusInfo = new HashMap<>();
                    statusInfo.put("name", status.getName());
                    statusInfo.put("ready", status.getReady());
                    statusInfo.put("restartCount", status.getRestartCount());
                    statusInfo.put("image", status.getImage());
                    if (status.getState() != null) {
                        Map<String, Object> state = new HashMap<>();
                        if (status.getState().getRunning() != null) {
                            state.put("running", status.getState().getRunning().getStartedAt());
                        }
                        if (status.getState().getWaiting() != null) {
                            Map<String, String> waiting = new HashMap<>();
                            waiting.put("reason", status.getState().getWaiting().getReason());
                            waiting.put("message", status.getState().getWaiting().getMessage());
                            state.put("waiting", waiting);
                        }
                        if (status.getState().getTerminated() != null) {
                            Map<String, Object> terminated = new HashMap<>();
                            terminated.put("exitCode", status.getState().getTerminated().getExitCode());
                            terminated.put("reason", status.getState().getTerminated().getReason());
                            terminated.put("finishedAt", status.getState().getTerminated().getFinishedAt());
                            terminated.put("startedAt", status.getState().getTerminated().getStartedAt());
                            state.put("terminated", terminated);
                        }
                        statusInfo.put("state", state);
                    }
                    containerStatuses.add(statusInfo);
                }
                result.put("containerStatuses", containerStatuses);
            }

            // Conditions
            if (pod.getStatus().getConditions() != null) {
                List<Map<String, Object>> conditions = new ArrayList<>();
                for (V1PodCondition condition : pod.getStatus().getConditions()) {
                    Map<String, Object> condInfo = new HashMap<>();
                    condInfo.put("type", condition.getType());
                    condInfo.put("status", condition.getStatus());
                    condInfo.put("reason", condition.getReason());
                    condInfo.put("message", condition.getMessage());
                    condInfo.put("lastTransitionTime", condition.getLastTransitionTime());
                    conditions.add(condInfo);
                }
                result.put("conditions", conditions);
            }

            // Events
            try {
                String fieldSelector = "involvedObject.name=" + podName + ",involvedObject.namespace=" + namespace;
                CoreV1EventList events = api.listNamespacedEvent(namespace)
                        .fieldSelector(fieldSelector)
                        .execute();
                List<Map<String, Object>> eventList = new ArrayList<>();
                if (events.getItems() != null) {
                    for (CoreV1Event event : events.getItems()) {
                        Map<String, Object> eventInfo = new HashMap<>();
                        eventInfo.put("type", event.getType());
                        eventInfo.put("reason", event.getReason());
                        eventInfo.put("message", event.getMessage());
                        eventInfo.put("count", event.getCount());
                        eventInfo.put("firstTimestamp", event.getFirstTimestamp());
                        eventInfo.put("lastTimestamp", event.getLastTimestamp());
                        eventList.add(eventInfo);
                    }
                }
                result.put("events", eventList);
            } catch (Exception e) {
                log.warn("Failed to get events for pod {}: {}", podName, e.getMessage());
            }

            return result;
        } catch (ApiException e) {
            log.error("Failed to get pod detail: {}", e.getMessage());
            throw new RuntimeException("Failed to get pod detail: " + e.getMessage());
        }
    }

    /**
     * Get pod YAML manifest.
     */
    public String getPodYaml(Long clusterId, String namespace, String podName) {
        ApiClient client = getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1Pod pod = api.readNamespacedPod(podName, namespace).execute();
            // Use YAML serialization
            return io.kubernetes.client.util.Yaml.dump(pod);
        } catch (ApiException e) {
            log.error("Failed to get pod YAML: {}", e.getMessage());
            throw new RuntimeException("Failed to get pod YAML: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to serialize pod to YAML: {}", e.getMessage());
            throw new RuntimeException("Failed to serialize pod to YAML: " + e.getMessage());
        }
    }

    /**
     * Get pod logs.
     */
    public String getPodLogs(Long clusterId, String namespace, String podName, String containerName, Integer tailLines) {
        ApiClient client = getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            var request = api.readNamespacedPodLog(podName, namespace);
            if (containerName != null && !containerName.isEmpty()) {
                request.container(containerName);
            }
            if (tailLines != null && tailLines > 0) {
                request.tailLines(tailLines);
            }
            return request.execute();
        } catch (ApiException e) {
            log.error("Failed to get pod logs: {}", e.getMessage());
            throw new RuntimeException("Failed to get pod logs: " + e.getMessage());
        }
    }

    /**
     * Deploy gateway application to Kubernetes.
     */
    @Transactional
    public Map<String, Object> deployGateway(Long clusterId, String namespace, String appName, int replicas,
                                              String image, Map<String, String> envVars, int containerPort) {
        ApiClient client = getApiClient(clusterId);
        AppsV1Api appsApi = new AppsV1Api(client);
        CoreV1Api coreApi = new CoreV1Api(client);

        try {
            // Create namespace if not exists
            try {
                coreApi.readNamespace(namespace).execute();
            } catch (ApiException e) {
                if (e.getCode() == 404) {
                    V1Namespace ns = new V1Namespace();
                    ns.setMetadata(new V1ObjectMeta().name(namespace));
                    coreApi.createNamespace(ns).execute();
                    log.info("Created namespace: {}", namespace);
                }
            }

            // Create Deployment
            V1Deployment deployment = createDeploymentManifest(appName, namespace, replicas, image, envVars, containerPort);
            V1Deployment createdDeployment = appsApi.createNamespacedDeployment(namespace, deployment).execute();

            // Create Service
            V1Service service = createServiceManifest(appName, namespace, containerPort);
            V1Service createdService = coreApi.createNamespacedService(namespace, service).execute();

            Map<String, Object> result = new HashMap<>();
            result.put("deploymentName", createdDeployment.getMetadata().getName());
            result.put("serviceName", createdService.getMetadata().getName());
            result.put("namespace", namespace);
            result.put("replicas", replicas);
            result.put("status", "DEPLOYED");

            return result;
        } catch (ApiException e) {
            log.error("Failed to deploy gateway: {}", e.getMessage());
            throw new RuntimeException("Failed to deploy gateway: " + e.getMessage());
        }
    }

    /**
     * Create deployment manifest.
     */
    private V1Deployment createDeploymentManifest(String appName, String namespace, int replicas,
                                                    String image, Map<String, String> envVars, int containerPort) {
        V1Deployment deployment = new V1Deployment();

        deployment.setMetadata(new V1ObjectMeta()
                .name(appName)
                .namespace(namespace)
                .labels(Map.of("app", appName)));

        V1DeploymentSpec spec = new V1DeploymentSpec();
        spec.setReplicas(replicas);
        spec.setSelector(new V1LabelSelector().matchLabels(Map.of("app", appName)));

        V1PodTemplateSpec template = new V1PodTemplateSpec();
        template.setMetadata(new V1ObjectMeta().labels(Map.of("app", appName)));

        V1PodSpec podSpec = new V1PodSpec();
        List<V1Container> containers = new ArrayList<>();

        V1Container container = new V1Container();
        container.setName(appName);
        container.setImage(image);

        List<V1ContainerPort> ports = new ArrayList<>();
        ports.add(new V1ContainerPort().containerPort(containerPort));
        container.setPorts(ports);

        if (envVars != null && !envVars.isEmpty()) {
            List<V1EnvVar> envList = envVars.entrySet().stream()
                    .map(e -> new V1EnvVar().name(e.getKey()).value(e.getValue()))
                    .toList();
            container.setEnv(envList);
        }

        containers.add(container);
        podSpec.setContainers(containers);
        template.setSpec(podSpec);
        spec.setTemplate(template);
        deployment.setSpec(spec);

        return deployment;
    }

    /**
     * Create service manifest.
     */
    private V1Service createServiceManifest(String appName, String namespace, int port) {
        V1Service service = new V1Service();

        service.setMetadata(new V1ObjectMeta()
                .name(appName + "-service")
                .namespace(namespace)
                .labels(Map.of("app", appName)));

        V1ServiceSpec spec = new V1ServiceSpec();
        spec.setSelector(Map.of("app", appName));
        spec.setType("NodePort");

        List<V1ServicePort> ports = new ArrayList<>();
        V1ServicePort servicePort = new V1ServicePort();
        servicePort.setPort(port);
        servicePort.setTargetPort(new IntOrString(port));
        servicePort.setNodePort(null);  // Let K8s auto-assign
        ports.add(servicePort);

        spec.setPorts(ports);
        service.setSpec(spec);

        return service;
    }

    /**
     * Delete deployment.
     */
    @Transactional
    public void deleteDeployment(Long clusterId, String namespace, String deploymentName) {
        ApiClient client = getApiClient(clusterId);
        AppsV1Api appsApi = new AppsV1Api(client);
        CoreV1Api coreApi = new CoreV1Api(client);

        try {
            // Delete deployment
            appsApi.deleteNamespacedDeployment(deploymentName, namespace).execute();

            // Delete associated service
            try {
                coreApi.deleteNamespacedService(deploymentName + "-service", namespace).execute();
            } catch (ApiException e) {
                if (e.getCode() != 404) {
                    log.warn("Failed to delete service: {}", e.getMessage());
                }
            }

            log.info("Deleted deployment {} in namespace {}", deploymentName, namespace);
        } catch (ApiException e) {
            log.error("Failed to delete deployment: {}", e.getMessage());
            throw new RuntimeException("Failed to delete deployment: " + e.getMessage());
        }
    }

    /**
     * Scale deployment.
     */
    @Transactional
    public void scaleDeployment(Long clusterId, String namespace, String deploymentName, int replicas) {
        ApiClient client = getApiClient(clusterId);
        AppsV1Api api = new AppsV1Api(client);

        try {
            V1Deployment deployment = api.readNamespacedDeployment(deploymentName, namespace).execute();
            deployment.getSpec().setReplicas(replicas);
            api.replaceNamespacedDeployment(deploymentName, namespace, deployment).execute();

            log.info("Scaled deployment {} to {} replicas", deploymentName, replicas);
        } catch (ApiException e) {
            log.error("Failed to scale deployment: {}", e.getMessage());
            throw new RuntimeException("Failed to scale deployment: " + e.getMessage());
        }
    }

    /**
     * Get deployment status.
     */
    public Map<String, Object> getDeploymentStatus(Long clusterId, String namespace, String deploymentName) {
        ApiClient client = getApiClient(clusterId);
        AppsV1Api api = new AppsV1Api(client);

        try {
            V1Deployment deployment = api.readNamespacedDeployment(deploymentName, namespace).execute();

            Map<String, Object> result = new HashMap<>();
            result.put("name", deployment.getMetadata().getName());
            result.put("namespace", deployment.getMetadata().getNamespace());
            result.put("replicas", deployment.getSpec().getReplicas());
            result.put("readyReplicas", deployment.getStatus().getReadyReplicas());
            result.put("availableReplicas", deployment.getStatus().getAvailableReplicas());
            result.put("updatedReplicas", deployment.getStatus().getUpdatedReplicas());
            result.put("conditions", deployment.getStatus().getConditions());

            return result;
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return Map.of("status", "NOT_FOUND");
            }
            log.error("Failed to get deployment status: {}", e.getMessage());
            throw new RuntimeException("Failed to get deployment status: " + e.getMessage());
        }
    }

    /**
     * Get local images from all nodes in the cluster.
     * This retrieves images from Node.status.images field.
     */
    public List<Map<String, Object>> getClusterImages(Long clusterId) {
        ApiClient client = getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1NodeList nodes = api.listNode().execute();
            Set<String> imageSet = new TreeSet<>(); // Use TreeSet for sorted, unique results

            for (V1Node node : nodes.getItems()) {
                if (node.getStatus() != null && node.getStatus().getImages() != null) {
                    for (V1ContainerImage image : node.getStatus().getImages()) {
                        if (image.getNames() != null) {
                            for (String name : image.getNames()) {
                                // Filter out SHA256 digest references, keep only readable names
                                if (!name.startsWith("sha256:")) {
                                    imageSet.add(name);
                                }
                            }
                        }
                    }
                }
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (String image : imageSet) {
                Map<String, Object> imageInfo = new HashMap<>();
                imageInfo.put("name", image);
                result.add(imageInfo);
            }

            return result;
        } catch (ApiException e) {
            log.error("Failed to get cluster images: {}", e.getMessage());
            throw new RuntimeException("Failed to get cluster images: " + e.getMessage());
        }
    }

    /**
     * Parse a Kubernetes Quantity to double value.
     * Uses Quantity.toString() to get the full string representation.
     */
    private double parseQuantityToDouble(Quantity quantity) {
        if (quantity == null) {
            return 0.0;
        }

        // Get the string representation (e.g., "1", "1000m", "2Gi", "512Mi")
        String amount = quantity.toString();
        if (amount == null || amount.isEmpty()) {
            return 0.0;
        }

        try {
            // Handle suffixes
            if (amount.endsWith("Ki")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024;
            } else if (amount.endsWith("Mi")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024 * 1024;
            } else if (amount.endsWith("Gi")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024 * 1024 * 1024;
            } else if (amount.endsWith("Ti")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024L * 1024L * 1024L * 1024L;
            } else if (amount.endsWith("Pi")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024L * 1024L * 1024L * 1024L * 1024L;
            } else if (amount.endsWith("Ei")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024L * 1024L * 1024L * 1024L * 1024L * 1024L;
            } else if (amount.endsWith("k")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000;
            } else if (amount.endsWith("M")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000 * 1000;
            } else if (amount.endsWith("G")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000 * 1000 * 1000;
            } else if (amount.endsWith("T")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000L * 1000L * 1000L * 1000L;
            } else if (amount.endsWith("P")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000L * 1000L * 1000L * 1000L * 1000L;
            } else if (amount.endsWith("E")) {
                return Double.parseDouble(amount.substring(0, amount.length() - 1)) * 1000L * 1000L * 1000L * 1000L * 1000L * 1000L;
            } else if (amount.endsWith("m")) {
                // millicores (CPU) or millibytes
                return Double.parseDouble(amount.substring(0, amount.length() - 1)) / 1000.0;
            } else if (amount.endsWith("u") || amount.endsWith("µ")) {
                // micro
                return Double.parseDouble(amount.substring(0, amount.length() - 1)) / 1000000.0;
            } else if (amount.endsWith("n")) {
                // nano
                return Double.parseDouble(amount.substring(0, amount.length() - 1)) / 1000000000.0;
            } else {
                return Double.parseDouble(amount);
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse quantity: {}", amount);
            return 0.0;
        }
    }
}