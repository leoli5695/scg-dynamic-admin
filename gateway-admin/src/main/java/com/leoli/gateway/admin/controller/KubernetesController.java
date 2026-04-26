package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.KubernetesService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes cluster management controller.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/kubernetes")
public class KubernetesController {

    @Autowired
    private KubernetesService kubernetesService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    private String getOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
    }

    private String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * Test kubeconfig connection without saving.
     */
    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Map<String, Object> request) {
        try {
            String kubeconfig = (String) request.get("kubeconfig");

            if (kubeconfig == null || kubeconfig.trim().isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("code", 400);
                result.put("message", "kubeconfig is required");
                return ResponseEntity.badRequest().body(result);
            }

            log.info("Testing kubeconfig connection");
            Map<String, Object> testResult = kubernetesService.testKubeconfigConnection(kubeconfig);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Connection test completed");
            result.put("data", testResult);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to test connection", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to test connection: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Import kubeconfig to create a new cluster.
     */
    @PostMapping("/clusters")
    public ResponseEntity<Map<String, Object>> importCluster(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        try {
            String clusterName = (String) request.get("clusterName");
            String kubeconfig = (String) request.get("kubeconfig");
            String description = (String) request.get("description");

            if (clusterName == null || kubeconfig == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("code", 400);
                result.put("message", "clusterName and kubeconfig are required");
                return ResponseEntity.badRequest().body(result);
            }

            log.info("Importing cluster: {}", clusterName);
            KubernetesCluster cluster = kubernetesService.importKubeconfig(clusterName, kubeconfig, description);

            // Record audit log
            auditLogService.recordAuditLog(getOperator(), "CREATE", "KUBERNETES_CLUSTER", String.valueOf(cluster.getId()),
                    cluster.getClusterName(), null, objectMapper.writeValueAsString(cluster), getIpAddress(httpRequest));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Cluster imported successfully");
            result.put("data", cluster);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to import cluster: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 400);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("Failed to import cluster", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to import cluster: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get all clusters.
     */
    @GetMapping("/clusters")
    public ResponseEntity<Map<String, Object>> getAllClusters() {
        List<KubernetesCluster> clusters = kubernetesService.getAllClusters();

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", clusters);
        return ResponseEntity.ok(result);
    }

    /**
     * Get cluster by ID.
     */
    @GetMapping("/clusters/{id}")
    public ResponseEntity<Map<String, Object>> getClusterById(@PathVariable Long id) {
        try {
            KubernetesCluster cluster = kubernetesService.getClusterById(id);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", cluster);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Update cluster.
     */
    @PutMapping("/clusters/{id}")
    public ResponseEntity<Map<String, Object>> updateCluster(
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

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Cluster updated successfully");
            result.put("data", cluster);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 400);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("Failed to update cluster", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to update cluster: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Delete cluster.
     */
    @DeleteMapping("/clusters/{id}")
    public ResponseEntity<Map<String, Object>> deleteCluster(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        try {
            KubernetesCluster oldCluster = kubernetesService.getClusterById(id);
            String oldValue = objectMapper.writeValueAsString(oldCluster);

            kubernetesService.deleteCluster(id);

            // Record audit log
            auditLogService.recordAuditLog(getOperator(), "DELETE", "KUBERNETES_CLUSTER", String.valueOf(id),
                    oldCluster.getClusterName(), oldValue, null, getIpAddress(httpRequest));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Cluster deleted successfully");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to delete cluster", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to delete cluster: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Refresh cluster connection status.
     */
    @PostMapping("/clusters/{id}/refresh")
    public ResponseEntity<Map<String, Object>> refreshConnection(@PathVariable Long id) {
        try {
            KubernetesCluster cluster = kubernetesService.refreshConnection(id);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Connection refreshed");
            result.put("data", cluster);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to refresh connection", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to refresh connection: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get cluster nodes.
     */
    @GetMapping("/clusters/{id}/nodes")
    public ResponseEntity<Map<String, Object>> getClusterNodes(@PathVariable Long id) {
        try {
            List<Map<String, Object>> nodes = kubernetesService.getClusterNodes(id);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", nodes);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get nodes", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get nodes: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get cluster namespaces.
     */
    @GetMapping("/clusters/{id}/namespaces")
    public ResponseEntity<Map<String, Object>> getClusterNamespaces(@PathVariable Long id) {
        try {
            List<String> namespaces = kubernetesService.getClusterNamespaces(id);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", namespaces);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get namespaces", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get namespaces: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get deployments in namespace.
     */
    @GetMapping("/clusters/{id}/deployments")
    public ResponseEntity<Map<String, Object>> getDeployments(
            @PathVariable Long id,
            @RequestParam(required = false) String namespace) {
        try {
            List<Map<String, Object>> deployments = kubernetesService.getDeployments(id, namespace);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", deployments);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get deployments", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get deployments: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get pods in namespace.
     */
    @GetMapping("/clusters/{id}/pods")
    public ResponseEntity<Map<String, Object>> getPods(
            @PathVariable Long id,
            @RequestParam(required = false) String namespace) {
        try {
            List<Map<String, Object>> pods = kubernetesService.getPods(id, namespace);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", pods);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get pods", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get pods: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get detailed pod information.
     */
    @GetMapping("/clusters/{id}/pods/{namespace}/{name}")
    public ResponseEntity<Map<String, Object>> getPodDetail(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name) {
        try {
            Map<String, Object> podDetail = kubernetesService.getPodDetail(id, namespace, name);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", podDetail);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get pod detail", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get pod detail: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get pod YAML manifest.
     */
    @GetMapping("/clusters/{id}/pods/{namespace}/{name}/yaml")
    public ResponseEntity<Map<String, Object>> getPodYaml(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name) {
        try {
            String yaml = kubernetesService.getPodYaml(id, namespace, name);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", yaml);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get pod YAML", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get pod YAML: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get pod logs.
     * Supports both historical logs (by tailLines or sinceSeconds) and real-time logs.
     */
    @GetMapping("/clusters/{id}/pods/{namespace}/{name}/logs")
    public ResponseEntity<Map<String, Object>> getPodLogs(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(required = false) String container,
            @RequestParam(required = false, defaultValue = "500") Integer tailLines,
            @RequestParam(required = false) Integer sinceSeconds) {
        try {
            String logs = kubernetesService.getPodLogs(id, namespace, name, container, tailLines, sinceSeconds);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", logs);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get pod logs", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get pod logs: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Deploy gateway application.
     */
    @PostMapping("/clusters/{id}/deploy")
    public ResponseEntity<Map<String, Object>> deployGateway(
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

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Gateway deployed successfully");
            result.put("data", deploymentResult);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to deploy gateway", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to deploy gateway: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Delete deployment.
     */
    @DeleteMapping("/clusters/{id}/deployments/{namespace}/{name}")
    public ResponseEntity<Map<String, Object>> deleteDeployment(
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

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Deployment deleted successfully");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to delete deployment", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to delete deployment: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Scale deployment.
     */
    @PutMapping("/clusters/{id}/deployments/{namespace}/{name}/scale")
    public ResponseEntity<Map<String, Object>> scaleDeployment(
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

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Deployment scaled successfully");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to scale deployment", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to scale deployment: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get deployment status.
     */
    @GetMapping("/clusters/{id}/deployments/{namespace}/{name}/status")
    public ResponseEntity<Map<String, Object>> getDeploymentStatus(
            @PathVariable Long id,
            @PathVariable String namespace,
            @PathVariable String name) {
        try {
            Map<String, Object> status = kubernetesService.getDeploymentStatus(id, namespace, name);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", status);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get deployment status", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get deployment status: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get local images from cluster nodes.
     */
    @GetMapping("/clusters/{id}/images")
    public ResponseEntity<Map<String, Object>> getClusterImages(@PathVariable Long id) {
        try {
            List<Map<String, Object>> images = kubernetesService.getClusterImages(id);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", images);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get cluster images", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get cluster images: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}