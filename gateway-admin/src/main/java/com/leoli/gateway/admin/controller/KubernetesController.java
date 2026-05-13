package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.KubernetesService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Kubernetes cluster management controller.
 * Uses ApiResponse for standardized response format.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/kubernetes")
@RequiredArgsConstructor
public class KubernetesController extends BaseController {

    private final KubernetesService kubernetesService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Test kubeconfig connection without saving.
     */
    @PostMapping("/test-connection")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testConnection(@RequestBody Map<String, Object> request) {
        try {
            String kubeconfig = (String) request.get("kubeconfig");

            if (kubeconfig == null || kubeconfig.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.badRequest("kubeconfig is required"));
            }

            log.info("Testing kubeconfig connection");
            Map<String, Object> testResult = kubernetesService.testKubeconfigConnection(kubeconfig);
            return ResponseEntity.ok(ApiResponse.success(testResult, "Connection test completed"));
        } catch (Exception e) {
            log.error("Failed to test connection", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to test connection: " + e.getMessage()));
        }
    }

    /**
     * Import kubeconfig to create a new cluster.
     */
    @PostMapping("/clusters")
    public ResponseEntity<ApiResponse<KubernetesCluster>> importCluster(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        try {
            String clusterName = (String) request.get("clusterName");
            String kubeconfig = (String) request.get("kubeconfig");
            String description = (String) request.get("description");

            if (clusterName == null || kubeconfig == null) {
                return ResponseEntity.badRequest().body(ApiResponse.badRequest("clusterName and kubeconfig are required"));
            }

            log.info("Importing cluster: {}", clusterName);
            KubernetesCluster cluster = kubernetesService.importKubeconfig(clusterName, kubeconfig, description);

            // Record audit log
            auditLogService.recordAuditLog(getOperator(), "CREATE", "KUBERNETES_CLUSTER", String.valueOf(cluster.getId()),
                    cluster.getClusterName(), null, objectMapper.writeValueAsString(cluster), getIpAddress(httpRequest));

            return ResponseEntity.ok(ApiResponse.success(cluster, "Cluster imported successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to import cluster: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to import cluster", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to import cluster: " + e.getMessage()));
        }
    }

    /**
     * Get all clusters.
     */
    @GetMapping("/clusters")
    public ResponseEntity<ApiResponse<List<KubernetesCluster>>> getAllClusters() {
        List<KubernetesCluster> clusters = kubernetesService.getAllClusters();
        return ResponseEntity.ok(ApiResponse.success(clusters));
    }

    /**
     * Get cluster by ID.
     */
    @GetMapping("/clusters/{id}")
    public ResponseEntity<ApiResponse<KubernetesCluster>> getClusterById(@PathVariable Long id) {
        try {
            KubernetesCluster cluster = kubernetesService.getClusterById(id);
            return ResponseEntity.ok(ApiResponse.success(cluster));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        }
    }

    /**
     * Update cluster.
     */
    @PutMapping("/clusters/{id}")
    public ResponseEntity<ApiResponse<KubernetesCluster>> updateCluster(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        try {
            String clusterName = (String) request.get("clusterName");
            String description = (String) request.get("description");
            Boolean enabled = (Boolean) request.get("enabled");

            KubernetesCluster oldCluster = kubernetesService.getClusterById(id);
            String oldValue = objectMapper.writeValueAsString(oldCluster);

            KubernetesCluster cluster = kubernetesService.updateCluster(id, clusterName, description, enabled);

            // Record audit log
            auditLogService.recordAuditLog(getOperator(), "UPDATE", "KUBERNETES_CLUSTER", String.valueOf(id),
                    cluster.getClusterName(), oldValue, objectMapper.writeValueAsString(cluster), getIpAddress(httpRequest));

            return ResponseEntity.ok(ApiResponse.success(cluster, "Cluster updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update cluster", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update cluster: " + e.getMessage()));
        }
    }

    /**
     * Delete cluster.
     */
    @DeleteMapping("/clusters/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCluster(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        try {
            KubernetesCluster oldCluster = kubernetesService.getClusterById(id);
            String oldValue = objectMapper.writeValueAsString(oldCluster);

            kubernetesService.deleteCluster(id);

            // Record audit log
            auditLogService.recordAuditLog(getOperator(), "DELETE", "KUBERNETES_CLUSTER", String.valueOf(id),
                    oldCluster.getClusterName(), oldValue, null, getIpAddress(httpRequest));

            return ResponseEntity.ok(ApiResponse.success("Cluster deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete cluster", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete cluster: " + e.getMessage()));
        }
    }

    /**
     * Refresh cluster connection status.
     */
    @PostMapping("/clusters/{id}/refresh")
    public ResponseEntity<ApiResponse<KubernetesCluster>> refreshConnection(@PathVariable Long id) {
        try {
            KubernetesCluster cluster = kubernetesService.refreshConnection(id);
            return ResponseEntity.ok(ApiResponse.success(cluster, "Connection refreshed"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to refresh connection", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to refresh connection: " + e.getMessage()));
        }
    }

    /**
     * Get cluster nodes.
     */
    @GetMapping("/clusters/{id}/nodes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getClusterNodes(@PathVariable Long id) {
        try {
            List<Map<String, Object>> nodes = kubernetesService.getClusterNodes(id);
            return ResponseEntity.ok(ApiResponse.success(nodes));
        } catch (Exception e) {
            log.error("Failed to get nodes", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get nodes: " + e.getMessage()));
        }
    }

    /**
     * Get cluster namespaces.
     */
    @GetMapping("/clusters/{id}/namespaces")
    public ResponseEntity<ApiResponse<List<String>>> getClusterNamespaces(@PathVariable Long id) {
        try {
            List<String> namespaces = kubernetesService.getClusterNamespaces(id);
            return ResponseEntity.ok(ApiResponse.success(namespaces));
        } catch (Exception e) {
            log.error("Failed to get namespaces", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get namespaces: " + e.getMessage()));
        }
    }

    /**
     * Get deployments in namespace.
     */
    @GetMapping("/clusters/{id}/deployments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDeployments(
            @PathVariable Long id,
            @RequestParam(required = false) String namespace) {
        try {
            List<Map<String, Object>> deployments = kubernetesService.getDeployments(id, namespace);
            return ResponseEntity.ok(ApiResponse.success(deployments));
        } catch (Exception e) {
            log.error("Failed to get deployments", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get deployments: " + e.getMessage()));
        }
    }

    /**
     * Get pods in namespace.
     */
    @GetMapping("/clusters/{id}/pods")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPods(
            @PathVariable Long id,
            @RequestParam(required = false) String namespace) {
        try {
            List<Map<String, Object>> pods = kubernetesService.getPods(id, namespace);
            return ResponseEntity.ok(ApiResponse.success(pods));
        } catch (Exception e) {
            log.error("Failed to get pods", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get pods: " + e.getMessage()));
        }
    }

    /**
     * Get detailed pod information.
     */
    @GetMapping("/clusters/{id}/pods/{namespace}/{name}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPodDetail(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name) {
        try {
            Map<String, Object> podDetail = kubernetesService.getPodDetail(id, namespace, name);
            return ResponseEntity.ok(ApiResponse.success(podDetail));
        } catch (Exception e) {
            log.error("Failed to get pod detail", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get pod detail: " + e.getMessage()));
        }
    }

    /**
     * Get pod YAML manifest.
     */
    @GetMapping("/clusters/{id}/pods/{namespace}/{name}/yaml")
    public ResponseEntity<ApiResponse<String>> getPodYaml(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name) {
        try {
            String yaml = kubernetesService.getPodYaml(id, namespace, name);
            return ResponseEntity.ok(ApiResponse.success(yaml));
        } catch (Exception e) {
            log.error("Failed to get pod YAML", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get pod YAML: " + e.getMessage()));
        }
    }

    /**
     * Get pod logs.
     * Supports both historical logs (by tailLines or sinceSeconds) and real-time logs.
     */
    @GetMapping("/clusters/{id}/pods/{namespace}/{name}/logs")
    public ResponseEntity<ApiResponse<String>> getPodLogs(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(required = false) String container,
            @RequestParam(required = false, defaultValue = "500") Integer tailLines,
            @RequestParam(required = false) Integer sinceSeconds) {
        try {
            String logs = kubernetesService.getPodLogs(id, namespace, name, container, tailLines, sinceSeconds);
            return ResponseEntity.ok(ApiResponse.success(logs));
        } catch (Exception e) {
            log.error("Failed to get pod logs", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get pod logs: " + e.getMessage()));
        }
    }

    /**
     * Deploy gateway application.
     */
    @PostMapping("/clusters/{id}/deploy")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deployGateway(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        try {
            String namespace = (String) request.getOrDefault("namespace", "gateway");
            String appName = (String) request.getOrDefault("appName", "my-gateway");
            int replicas = (Integer) request.getOrDefault("replicas", 1);
            String image = (String) request.getOrDefault("image", "my-gateway:latest");
            Map<String, String> envVars = (Map<String, String>) request.get("envVars");
            int containerPort = (Integer) request.getOrDefault("containerPort", 8080);

            log.info("Deploying gateway {} to cluster {}, namespace {}", appName, id, namespace);

            Map<String, Object> deploymentResult = kubernetesService.deployGateway(
                    id, namespace, appName, replicas, image, envVars, containerPort);

            // Record audit log
            auditLogService.recordAuditLog(getOperator(), "CREATE", "KUBERNETES_DEPLOYMENT",
                    deploymentResult.get("deploymentName").toString(), appName, null,
                    objectMapper.writeValueAsString(deploymentResult), getIpAddress(httpRequest));

            return ResponseEntity.ok(ApiResponse.success(deploymentResult, "Gateway deployed successfully"));
        } catch (Exception e) {
            log.error("Failed to deploy gateway", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to deploy gateway: " + e.getMessage()));
        }
    }

    /**
     * Delete deployment.
     */
    @DeleteMapping("/clusters/{id}/deployments/{namespace}/{name}")
    public ResponseEntity<ApiResponse<Void>> deleteDeployment(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name,
            HttpServletRequest httpRequest) {
        try {
            log.info("Deleting deployment {} in namespace {} from cluster {}", name, namespace, id);

            kubernetesService.deleteDeployment(id, namespace, name);

            // Record audit log
            auditLogService.recordAuditLog(getOperator(), "DELETE", "KUBERNETES_DEPLOYMENT",
                    name, name, null, null, getIpAddress(httpRequest));

            return ResponseEntity.ok(ApiResponse.success("Deployment deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete deployment", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete deployment: " + e.getMessage()));
        }
    }

    /**
     * Scale deployment.
     */
    @PutMapping("/clusters/{id}/deployments/{namespace}/{name}/scale")
    public ResponseEntity<ApiResponse<Void>> scaleDeployment(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        try {
            int replicas = (Integer) request.get("replicas");

            log.info("Scaling deployment {} to {} replicas", name, replicas);
            kubernetesService.scaleDeployment(id, namespace, name, replicas);

            // Record audit log
            auditLogService.recordAuditLog(getOperator(), "UPDATE", "KUBERNETES_DEPLOYMENT",
                    name, name, null, "replicas=" + replicas, getIpAddress(httpRequest));

            return ResponseEntity.ok(ApiResponse.success("Deployment scaled successfully"));
        } catch (Exception e) {
            log.error("Failed to scale deployment", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to scale deployment: " + e.getMessage()));
        }
    }

    /**
     * Get deployment status.
     */
    @GetMapping("/clusters/{id}/deployments/{namespace}/{name}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDeploymentStatus(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name) {
        try {
            Map<String, Object> status = kubernetesService.getDeploymentStatus(id, namespace, name);
            return ResponseEntity.ok(ApiResponse.success(status));
        } catch (Exception e) {
            log.error("Failed to get deployment status", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get deployment status: " + e.getMessage()));
        }
    }

    /**
     * Get local images from cluster nodes.
     */
    @GetMapping("/clusters/{id}/images")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getClusterImages(@PathVariable Long id) {
        try {
            List<Map<String, Object>> images = kubernetesService.getClusterImages(id);
            return ResponseEntity.ok(ApiResponse.success(images));
        } catch (Exception e) {
            log.error("Failed to get cluster images", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get cluster images: " + e.getMessage()));
        }
    }
}