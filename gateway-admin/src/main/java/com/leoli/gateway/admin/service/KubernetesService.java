package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes cluster management coordination layer.
 * Delegates to specialized services for config parsing, connection, resources, and deployments.
 */
@Slf4j
@Service
public class KubernetesService {

    private final KubernetesClusterRepository clusterRepository;
    private final KubeConfigService kubeConfigService;
    private final ClusterConnectionService connectionService;
    private final KubernetesResourceService resourceService;
    private final DeploymentService deploymentService;

    public KubernetesService(KubernetesClusterRepository clusterRepository,
                              KubeConfigService kubeConfigService,
                              ClusterConnectionService connectionService,
                              KubernetesResourceService resourceService,
                              DeploymentService deploymentService) {
        this.clusterRepository = clusterRepository;
        this.kubeConfigService = kubeConfigService;
        this.connectionService = connectionService;
        this.resourceService = resourceService;
        this.deploymentService = deploymentService;
    }

    // === Cluster CRUD ===

    public List<KubernetesCluster> getAllClusters() {
        return clusterRepository.findAll();
    }

    public KubernetesCluster getClusterById(Long id) {
        return clusterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found: " + id));
    }

    @Transactional
    public KubernetesCluster importKubeconfig(String clusterName, String kubeconfigContent, String description) {
        if (clusterRepository.existsByClusterName(clusterName)) {
            throw new IllegalArgumentException("Cluster name already exists: " + clusterName);
        }

        KubernetesCluster cluster = new KubernetesCluster();
        cluster.setClusterName(clusterName);
        cluster.setKubeconfig(kubeconfigContent);
        cluster.setServerUrl(kubeConfigService.extractServerUrl(kubeconfigContent));
        cluster.setContextName(kubeConfigService.extractCurrentContext(kubeconfigContent));
        cluster.setDescription(description);
        cluster.setEnabled(true);
        cluster.setConnectionStatus("IMPORTED");
        cluster = clusterRepository.save(cluster);

        try {
            connectionService.verifyClusterConnection(cluster);
        } catch (Exception e) {
            log.warn("Failed to verify cluster connection for {}: {}", clusterName, e.getMessage());
            cluster.setConnectionStatus("CONNECTION_FAILED");
            clusterRepository.save(cluster);
        }
        return cluster;
    }

    @Transactional
    public KubernetesCluster updateCluster(Long id, String clusterName, String description, Boolean enabled) {
        KubernetesCluster cluster = getClusterById(id);

        if (clusterName != null && !clusterName.equals(cluster.getClusterName())) {
            if (clusterRepository.existsByClusterName(clusterName)) {
                throw new IllegalArgumentException("Cluster name already exists: " + clusterName);
            }
            cluster.setClusterName(clusterName);
        }
        if (description != null) cluster.setDescription(description);
        if (enabled != null) {
            cluster.setEnabled(enabled);
            if (!enabled) connectionService.evictClient(id);
        }
        return clusterRepository.save(cluster);
    }

    @Transactional
    public void deleteCluster(Long id) {
        connectionService.evictClient(id);
        clusterRepository.deleteById(id);
    }

    @Transactional
    public KubernetesCluster refreshConnection(Long id) {
        KubernetesCluster cluster = getClusterById(id);
        connectionService.verifyClusterConnection(cluster);
        return cluster;
    }

    // === Connection testing ===

    public Map<String, Object> testKubeconfigConnection(String kubeconfigContent) {
        return connectionService.testKubeconfigConnection(kubeconfigContent);
    }

    // === Resource queries (delegated) ===

    public List<Map<String, Object>> getClusterNodes(Long clusterId) {
        return resourceService.getClusterNodes(clusterId);
    }

    public List<String> getClusterNamespaces(Long clusterId) {
        return resourceService.getClusterNamespaces(clusterId);
    }

    public List<Map<String, Object>> getPods(Long clusterId, String namespace) {
        return resourceService.getPods(clusterId, namespace);
    }

    public Map<String, Object> getPodDetail(Long clusterId, String namespace, String podName) {
        return resourceService.getPodDetail(clusterId, namespace, podName);
    }

    public String getPodYaml(Long clusterId, String namespace, String podName) {
        return resourceService.getPodYaml(clusterId, namespace, podName);
    }

    public String getPodLogs(Long clusterId, String namespace, String podName, String containerName, Integer tailLines) {
        return resourceService.getPodLogs(clusterId, namespace, podName, containerName, tailLines);
    }

    public String getPodLogs(Long clusterId, String namespace, String podName, String containerName, Integer tailLines, Integer sinceSeconds) {
        return resourceService.getPodLogs(clusterId, namespace, podName, containerName, tailLines, sinceSeconds);
    }

    public List<Map<String, Object>> getClusterImages(Long clusterId) {
        return resourceService.getClusterImages(clusterId);
    }

    // === Deployment operations (delegated) ===

    public List<Map<String, Object>> getDeployments(Long clusterId, String namespace) {
        return deploymentService.getDeployments(clusterId, namespace);
    }

    @Transactional
    public Map<String, Object> deployGateway(Long clusterId, String namespace, String appName, int replicas,
                                              String image, Map<String, String> envVars, int containerPort) {
        return deploymentService.deployGateway(clusterId, namespace, appName, replicas, image, envVars, containerPort);
    }

    @Transactional
    public void deleteDeployment(Long clusterId, String namespace, String deploymentName) {
        deploymentService.deleteDeployment(clusterId, namespace, deploymentName);
    }

    @Transactional
    public void scaleDeployment(Long clusterId, String namespace, String deploymentName, int replicas) {
        deploymentService.scaleDeployment(clusterId, namespace, deploymentName, replicas);
    }

    public Map<String, Object> getDeploymentStatus(Long clusterId, String namespace, String deploymentName) {
        return deploymentService.getDeploymentStatus(clusterId, namespace, deploymentName);
    }
}
