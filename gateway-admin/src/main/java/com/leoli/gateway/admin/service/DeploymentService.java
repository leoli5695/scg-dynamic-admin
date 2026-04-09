package com.leoli.gateway.admin.service;

import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages Kubernetes deployments: deploy, scale, delete, status.
 */
@Slf4j
@Service
public class DeploymentService {

    private final ClusterConnectionService connectionService;

    public DeploymentService(ClusterConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    public List<Map<String, Object>> getDeployments(Long clusterId, String namespace) {
        ApiClient client = connectionService.getApiClient(clusterId);
        AppsV1Api api = new AppsV1Api(client);

        try {
            V1DeploymentList deployments = (namespace == null || namespace.isEmpty())
                    ? api.listDeploymentForAllNamespaces().execute()
                    : api.listNamespacedDeployment(namespace).execute();

            return deployments.getItems().stream()
                    .map(d -> Map.<String, Object>of(
                            "name", d.getMetadata().getName(),
                            "namespace", d.getMetadata().getNamespace(),
                            "replicas", d.getSpec().getReplicas(),
                            "readyReplicas", d.getStatus().getReadyReplicas(),
                            "availableReplicas", d.getStatus().getAvailableReplicas(),
                            "labels", d.getMetadata().getLabels(),
                            "createdAt", d.getMetadata().getCreationTimestamp()))
                    .toList();
        } catch (ApiException e) {
            log.error("Failed to get deployments: {}", e.getMessage());
            throw new RuntimeException("Failed to get deployments: " + e.getMessage());
        }
    }

    public Map<String, Object> deployGateway(Long clusterId, String namespace, String appName, int replicas,
                                              String image, Map<String, String> envVars, int containerPort) {
        ApiClient client = connectionService.getApiClient(clusterId);
        AppsV1Api appsApi = new AppsV1Api(client);
        CoreV1Api coreApi = new CoreV1Api(client);

        try {
            // Ensure namespace exists
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

            V1Deployment deployment = buildDeployment(appName, namespace, replicas, image, envVars, containerPort);
            V1Deployment createdDeployment = appsApi.createNamespacedDeployment(namespace, deployment).execute();

            V1Service service = buildService(appName, namespace, containerPort);
            V1Service createdService = coreApi.createNamespacedService(namespace, service).execute();

            return Map.of(
                    "deploymentName", createdDeployment.getMetadata().getName(),
                    "serviceName", createdService.getMetadata().getName(),
                    "namespace", namespace,
                    "replicas", replicas,
                    "status", "DEPLOYED");
        } catch (ApiException e) {
            log.error("Failed to deploy gateway: {}", e.getMessage());
            throw new RuntimeException("Failed to deploy gateway: " + e.getMessage());
        }
    }

    public void deleteDeployment(Long clusterId, String namespace, String deploymentName) {
        ApiClient client = connectionService.getApiClient(clusterId);
        AppsV1Api appsApi = new AppsV1Api(client);
        CoreV1Api coreApi = new CoreV1Api(client);

        try {
            appsApi.deleteNamespacedDeployment(deploymentName, namespace).execute();
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

    public void scaleDeployment(Long clusterId, String namespace, String deploymentName, int replicas) {
        ApiClient client = connectionService.getApiClient(clusterId);
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

    public Map<String, Object> getDeploymentStatus(Long clusterId, String namespace, String deploymentName) {
        ApiClient client = connectionService.getApiClient(clusterId);
        AppsV1Api api = new AppsV1Api(client);

        try {
            V1Deployment deployment = api.readNamespacedDeployment(deploymentName, namespace).execute();
            return Map.of(
                    "name", deployment.getMetadata().getName(),
                    "namespace", deployment.getMetadata().getNamespace(),
                    "replicas", deployment.getSpec().getReplicas(),
                    "readyReplicas", deployment.getStatus().getReadyReplicas(),
                    "availableReplicas", deployment.getStatus().getAvailableReplicas(),
                    "updatedReplicas", deployment.getStatus().getUpdatedReplicas(),
                    "conditions", deployment.getStatus().getConditions());
        } catch (ApiException e) {
            if (e.getCode() == 404) return Map.of("status", "NOT_FOUND");
            log.error("Failed to get deployment status: {}", e.getMessage());
            throw new RuntimeException("Failed to get deployment status: " + e.getMessage());
        }
    }

    private V1Deployment buildDeployment(String appName, String namespace, int replicas,
                                          String image, Map<String, String> envVars, int containerPort) {
        V1Deployment deployment = new V1Deployment();
        deployment.setMetadata(new V1ObjectMeta()
                .name(appName).namespace(namespace).labels(Map.of("app", appName)));

        V1Container container = new V1Container();
        container.setName(appName);
        container.setImage(image);
        container.setPorts(List.of(new V1ContainerPort().containerPort(containerPort)));

        if (envVars != null && !envVars.isEmpty()) {
            container.setEnv(envVars.entrySet().stream()
                    .map(e -> new V1EnvVar().name(e.getKey()).value(e.getValue()))
                    .toList());
        }

        V1PodSpec podSpec = new V1PodSpec().containers(List.of(container));
        V1PodTemplateSpec template = new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta().labels(Map.of("app", appName)))
                .spec(podSpec);

        deployment.setSpec(new V1DeploymentSpec()
                .replicas(replicas)
                .selector(new V1LabelSelector().matchLabels(Map.of("app", appName)))
                .template(template));

        return deployment;
    }

    private V1Service buildService(String appName, String namespace, int port) {
        V1Service service = new V1Service();
        service.setMetadata(new V1ObjectMeta()
                .name(appName + "-service").namespace(namespace).labels(Map.of("app", appName)));

        V1ServicePort servicePort = new V1ServicePort()
                .port(port).targetPort(new IntOrString(port)).nodePort(null);

        service.setSpec(new V1ServiceSpec()
                .selector(Map.of("app", appName))
                .type("NodePort")
                .ports(List.of(servicePort)));

        return service;
    }
}
