package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.dto.InstanceCreateRequest;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.InstanceSpec;
import com.leoli.gateway.admin.model.InstanceStatus;
import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Gateway Instance Service.
 * Manages gateway instance lifecycle and Kubernetes deployment.
 *
 * @author leoli
 */
@Slf4j
@Service
public class GatewayInstanceService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private GatewayInstanceRepository instanceRepository;

    @Autowired
    private KubernetesClusterRepository clusterRepository;

    @Value("${gateway.image.default:my-gateway:latest}")
    private String defaultImage;

    @Value("${gateway.image.pull-policy:IfNotPresent}")
    private String imagePullPolicy;

    @Value("${nacos.server-addr:localhost:8848}")
    private String nacosServerAddr;

    @Value("${nacos.group:DEFAULT_GROUP}")
    private String nacosGroup;

    @Value("${gateway.admin.url:http://localhost:9090}")
    private String gatewayAdminUrl;

    @Value("${gateway.admin.host:localhost}")
    private String gatewayAdminHost;

    @Value("${gateway.admin.port:9090}")
    private Integer gatewayAdminPort;

    // Cache for ApiClients by cluster ID
    private final Map<Long, ApiClient> clientCache = new HashMap<>();

    /**
     * Get all instances.
     */
    public List<GatewayInstanceEntity> getAllInstances() {
        return instanceRepository.findAll();
    }

    /**
     * Get instance by ID.
     */
    public GatewayInstanceEntity getInstanceById(Long id) {
        return instanceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + id));
    }

    /**
     * Get instance by instance ID (UUID).
     */
    public GatewayInstanceEntity getInstanceByInstanceId(String instanceId) {
        return instanceRepository.findByInstanceId(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));
    }

    /**
     * Get all enabled instances.
     */
    public List<GatewayInstanceEntity> getEnabledInstances() {
        return instanceRepository.findByEnabledTrue();
    }

    /**
     * Get available spec types.
     */
    public List<Map<String, Object>> getAvailableSpecs() {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (InstanceSpec spec : InstanceSpec.values()) {
            Map<String, Object> specMap = new HashMap<>();
            specMap.put("type", spec.getType());
            specMap.put("cpuCores", spec.getCpuCores());
            specMap.put("memoryMB", spec.getMemoryMB());
            specMap.put("description", spec.getDescription());
            specs.add(specMap);
        }
        return specs;
    }

    /**
     * Create a new gateway instance.
     * This will deploy the gateway to the specified Kubernetes cluster.
     */
    @Transactional
    public GatewayInstanceEntity createInstance(InstanceCreateRequest request) {
        // Validate cluster exists
        KubernetesCluster cluster = clusterRepository.findById(request.getClusterId())
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found: " + request.getClusterId()));

        // Check instance name uniqueness
        if (instanceRepository.existsByInstanceName(request.getInstanceName())) {
            throw new IllegalArgumentException("Instance name already exists: " + request.getInstanceName());
        }

        // Check namespace uniqueness
        if (instanceRepository.existsByNamespace(request.getNamespace())) {
            throw new IllegalArgumentException("Namespace already used by another instance: " + request.getNamespace());
        }

        // Generate instance ID (12 characters, lowercase alphanumeric)
        String instanceId = generateShortId();
        String nacosNamespace = instanceId;  // Use full instance ID as Nacos namespace

        // Get spec configuration
        InstanceSpec spec = InstanceSpec.fromType(request.getSpecType());
        Double cpuCores = request.getCpuCores();
        Integer memoryMB = request.getMemoryMB();

        if (!spec.isCustom()) {
            cpuCores = spec.getCpuCores();
            memoryMB = spec.getMemoryMB();
        }

        // Get image
        String image = request.getImage();
        if (image == null || image.isEmpty()) {
            image = defaultImage;
        }

        // Generate deployment and service names
        String shortId = instanceId.substring(0, 8);
        String deploymentName = "gateway-" + shortId;
        String serviceName = "gateway-" + shortId + "-service";

        // Create entity
        GatewayInstanceEntity entity = new GatewayInstanceEntity();
        entity.setInstanceId(instanceId);
        entity.setInstanceName(request.getInstanceName());
        entity.setClusterId(request.getClusterId());
        entity.setClusterName(cluster.getClusterName());
        entity.setNamespace(request.getNamespace());
        entity.setNacosNamespace(nacosNamespace);
        entity.setSpecType(request.getSpecType());
        entity.setCpuCores(cpuCores);
        entity.setMemoryMB(memoryMB);
        entity.setReplicas(request.getReplicas());
        entity.setImage(image);
        entity.setStatus(InstanceStatus.STARTING.getDescription());
        entity.setStatusCode(InstanceStatus.STARTING.getCode());
        entity.setDeploymentName(deploymentName);
        entity.setServiceName(serviceName);
        entity.setEnabled(true);
        entity.setDescription(request.getDescription());
        entity.setMissedHeartbeats(0);

        // Save to database first
        entity = instanceRepository.save(entity);

        // Deploy to Kubernetes
        try {
            deployToKubernetes(entity, cluster, request.getCreateNamespace(), request.getImagePullPolicy());
            // Status will be updated to RUNNING when heartbeat is received
            entity.setStatus(InstanceStatus.STARTING.getDescription());
            entity.setStatusCode(InstanceStatus.STARTING.getCode());
            entity.setStatusMessage("Instance deployed, waiting for heartbeat");
        } catch (Exception e) {
            log.error("Failed to deploy instance {} to Kubernetes: {}", instanceId, e.getMessage(), e);
            entity.setStatus(InstanceStatus.ERROR.getDescription());
            entity.setStatusCode(InstanceStatus.ERROR.getCode());
            entity.setStatusMessage("Deployment failed: " + e.getMessage());
        }

        return instanceRepository.save(entity);
    }

    /**
     * Deploy gateway to Kubernetes.
     */
    private void deployToKubernetes(GatewayInstanceEntity instance, KubernetesCluster cluster, boolean createNamespace, String imagePullPolicy) {
        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        CoreV1Api coreApi = new CoreV1Api(client);
        AppsV1Api appsApi = new AppsV1Api(client);

        String namespace = instance.getNamespace();

        try {
            // Create namespace if needed
            if (createNamespace) {
                try {
                    coreApi.readNamespace(namespace).execute();
                    log.info("Namespace {} already exists", namespace);
                } catch (ApiException e) {
                    if (e.getCode() == 404) {
                        V1Namespace ns = new V1Namespace();
                        ns.setMetadata(new V1ObjectMeta()
                                .name(namespace)
                                .labels(Map.of("app.kubernetes.io/managed-by", "gateway-admin",
                                        "gateway-instance-id", instance.getInstanceId())));
                        coreApi.createNamespace(ns).execute();
                        log.info("Created namespace: {}", namespace);
                    }
                }
            }

            // Create Deployment
            V1Deployment deployment = createDeploymentManifest(instance, imagePullPolicy);
            V1Deployment createdDeployment = appsApi.createNamespacedDeployment(namespace, deployment).execute();
            log.info("Created deployment: {}", createdDeployment.getMetadata().getName());

            // Create Service
            V1Service service = createServiceManifest(instance);
            V1Service createdService = coreApi.createNamespacedService(namespace, service).execute();
            log.info("Created service: {}", createdService.getMetadata().getName());

            // Get NodePort
            if (createdService.getSpec() != null && createdService.getSpec().getPorts() != null) {
                Integer nodePort = createdService.getSpec().getPorts().get(0).getNodePort();
                instance.setNodePort(nodePort);
            }

        } catch (ApiException e) {
            log.error("Kubernetes API error: code={}, message={}, body={}", 
                    e.getCode(), e.getMessage(), e.getResponseBody());
            throw new RuntimeException("Kubernetes deployment failed: " + e.getMessage());
        }
    }

    /**
     * Create Deployment manifest.
     */
    private V1Deployment createDeploymentManifest(GatewayInstanceEntity instance, String pullPolicy) {
        V1Deployment deployment = new V1Deployment();

        deployment.setMetadata(new V1ObjectMeta()
                .name(instance.getDeploymentName())
                .namespace(instance.getNamespace())
                .labels(Map.of(
                        "app", "gateway",
                        "gateway-instance-id", instance.getInstanceId())));

        V1DeploymentSpec spec = new V1DeploymentSpec();
        spec.setReplicas(instance.getReplicas());
        spec.setSelector(new V1LabelSelector().matchLabels(Map.of("app", "gateway-" + instance.getInstanceId().substring(0, 8))));

        V1PodTemplateSpec template = new V1PodTemplateSpec();
        template.setMetadata(new V1ObjectMeta().labels(Map.of(
                "app", "gateway-" + instance.getInstanceId().substring(0, 8),
                "gateway-instance-id", instance.getInstanceId())));

        V1PodSpec podSpec = new V1PodSpec();
        List<V1Container> containers = new ArrayList<>();

        V1Container container = new V1Container();
        container.setName("gateway");
        container.setImage(instance.getImage());
        // Use provided pullPolicy, default to IfNotPresent if null
        container.setImagePullPolicy(pullPolicy != null ? pullPolicy : "IfNotPresent");

        // Container ports
        List<V1ContainerPort> ports = new ArrayList<>();
        ports.add(new V1ContainerPort().containerPort(8080).name("http"));
        container.setPorts(ports);

        // Environment variables
        List<V1EnvVar> envVars = new ArrayList<>();
        envVars.add(new V1EnvVar().name("GATEWAY_ID").value(instance.getInstanceId()));
        envVars.add(new V1EnvVar().name("GATEWAY_INSTANCE_ID").value(instance.getInstanceId()));
        envVars.add(new V1EnvVar().name("NACOS_SERVER_ADDR").value(nacosServerAddr));
        envVars.add(new V1EnvVar().name("NACOS_NAMESPACE").value(instance.getNacosNamespace()));
        envVars.add(new V1EnvVar().name("NACOS_GROUP").value(nacosGroup));
        envVars.add(new V1EnvVar().name("GATEWAY_ADMIN_URL").value(gatewayAdminUrl));
        container.setEnv(envVars);

        // Resource limits and requests
        if (instance.getCpuCores() != null && instance.getMemoryMB() != null) {
            V1ResourceRequirements resources = new V1ResourceRequirements();
            Map<String, Quantity> limits = new HashMap<>();
            Map<String, Quantity> requests = new HashMap<>();

            limits.put("cpu", new Quantity(instance.getCpuCores().toString()));
            limits.put("memory", new Quantity(instance.getMemoryMB() + "Mi"));

            requests.put("cpu", new Quantity((instance.getCpuCores() / 2) + ""));
            requests.put("memory", new Quantity((instance.getMemoryMB() / 2) + "Mi"));

            resources.setLimits(limits);
            resources.setRequests(requests);
            container.setResources(resources);
        }

        // Health checks - Liveness probe
        V1Probe livenessProbe = new V1Probe();
        livenessProbe.setHttpGet(new V1HTTPGetAction()
                .path("/actuator/health")
                .port(new io.kubernetes.client.custom.IntOrString(8080)));
        livenessProbe.setInitialDelaySeconds(60);
        livenessProbe.setPeriodSeconds(10);
        livenessProbe.setTimeoutSeconds(5);
        livenessProbe.setFailureThreshold(3);
        container.setLivenessProbe(livenessProbe);

        // Health checks - Readiness probe
        V1Probe readinessProbe = new V1Probe();
        readinessProbe.setHttpGet(new V1HTTPGetAction()
                .path("/actuator/health")
                .port(new io.kubernetes.client.custom.IntOrString(8080)));
        readinessProbe.setInitialDelaySeconds(30);
        readinessProbe.setPeriodSeconds(5);
        readinessProbe.setTimeoutSeconds(3);
        readinessProbe.setFailureThreshold(3);
        container.setReadinessProbe(readinessProbe);

        containers.add(container);
        podSpec.setContainers(containers);
        template.setSpec(podSpec);
        spec.setTemplate(template);
        deployment.setSpec(spec);

        return deployment;
    }

    /**
     * Create Service manifest.
     */
    private V1Service createServiceManifest(GatewayInstanceEntity instance) {
        V1Service service = new V1Service();

        service.setMetadata(new V1ObjectMeta()
                .name(instance.getServiceName())
                .namespace(instance.getNamespace())
                .labels(Map.of(
                        "app", "gateway",
                        "gateway-instance-id", instance.getInstanceId())));

        V1ServiceSpec spec = new V1ServiceSpec();
        spec.setSelector(Map.of("app", "gateway-" + instance.getInstanceId().substring(0, 8)));
        spec.setType("NodePort");

        List<V1ServicePort> ports = new ArrayList<>();
        V1ServicePort servicePort = new V1ServicePort();
        servicePort.setPort(8080);
        servicePort.setTargetPort(new io.kubernetes.client.custom.IntOrString(8080));
        servicePort.setProtocol("TCP");
        servicePort.setName("http");
        ports.add(servicePort);

        spec.setPorts(ports);
        service.setSpec(spec);

        return service;
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
     * Delete an instance.
     */
    @Transactional
    public void deleteInstance(Long id) {
        GatewayInstanceEntity instance = getInstanceById(id);

        // Delete from Kubernetes
        try {
            KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId())
                    .orElse(null);
            if (cluster != null) {
                deleteFromKubernetes(instance, cluster);
            }
        } catch (Exception e) {
            log.warn("Failed to delete instance {} from Kubernetes: {}", id, e.getMessage());
        }

        instanceRepository.deleteById(id);
    }

    /**
     * Delete deployment from Kubernetes.
     */
    private void deleteFromKubernetes(GatewayInstanceEntity instance, KubernetesCluster cluster) {
        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        AppsV1Api appsApi = new AppsV1Api(client);
        CoreV1Api coreApi = new CoreV1Api(client);

        String namespace = instance.getNamespace();
        String deploymentName = instance.getDeploymentName();
        String serviceName = instance.getServiceName();

        try {
            // Delete deployment
            appsApi.deleteNamespacedDeployment(deploymentName, namespace)
                    .pretty("true")
                    .execute();
            log.info("Deleted deployment: {}", deploymentName);

            // Delete service
            try {
                coreApi.deleteNamespacedService(serviceName, namespace)
                        .pretty("true")
                        .execute();
                log.info("Deleted service: {}", serviceName);
            } catch (ApiException e) {
                if (e.getCode() != 404) {
                    log.warn("Failed to delete service: {}", e.getMessage());
                }
            }
        } catch (ApiException e) {
            log.error("Failed to delete from Kubernetes: {}", e.getMessage());
        }
    }

    /**
     * Start an instance (from STOPPED state).
     */
    @Transactional
    public GatewayInstanceEntity startInstance(Long id) {
        GatewayInstanceEntity instance = getInstanceById(id);
        
        // Can only start from STOPPED state
        if (instance.getStatusCode() != null && 
            instance.getStatusCode() != InstanceStatus.STOPPED.getCode() &&
            instance.getStatusCode() != InstanceStatus.ERROR.getCode()) {
            throw new IllegalStateException("Cannot start instance in current state: " + 
                InstanceStatus.fromCode(instance.getStatusCode()).getDescription());
        }
        
        if (instance.getReplicas() == null || instance.getReplicas() <= 0) {
            instance.setReplicas(1);
        }
        
        // Set status to STARTING
        instance.setStatus(InstanceStatus.STARTING.getDescription());
        instance.setStatusCode(InstanceStatus.STARTING.getCode());
        instance.setStatusMessage("Starting instance");
        instance.setMissedHeartbeats(0);
        instanceRepository.save(instance);
        
        return scaleInstance(instance, instance.getReplicas());
    }

    /**
     * Stop an instance (user initiated).
     */
    @Transactional
    public GatewayInstanceEntity stopInstance(Long id) {
        GatewayInstanceEntity instance = getInstanceById(id);
        
        // Can only stop from RUNNING or ERROR state
        if (instance.getStatusCode() != null && 
            instance.getStatusCode() != InstanceStatus.RUNNING.getCode() &&
            instance.getStatusCode() != InstanceStatus.ERROR.getCode() &&
            instance.getStatusCode() != InstanceStatus.STARTING.getCode()) {
            throw new IllegalStateException("Cannot stop instance in current state: " + 
                InstanceStatus.fromCode(instance.getStatusCode()).getDescription());
        }
        
        // Set status to STOPPING
        instance.setStatus(InstanceStatus.STOPPING.getDescription());
        instance.setStatusCode(InstanceStatus.STOPPING.getCode());
        instance.setStatusMessage("Stopping instance");
        instanceRepository.save(instance);
        
        return scaleInstance(instance, 0);
    }

    /**
     * Scale instance replicas.
     */
    private GatewayInstanceEntity scaleInstance(GatewayInstanceEntity instance, int replicas) {
        KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId())
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        AppsV1Api appsApi = new AppsV1Api(client);

        try {
            V1Deployment deployment = appsApi.readNamespacedDeployment(
                    instance.getDeploymentName(), 
                    instance.getNamespace()
            ).execute();

            deployment.getSpec().setReplicas(replicas);
            appsApi.replaceNamespacedDeployment(
                    instance.getDeploymentName(),
                    instance.getNamespace(),
                    deployment
            ).execute();

            // Status will be updated by heartbeat (STARTING->RUNNING) or health check (STOPPING->STOPPED)
            return instance;
        } catch (ApiException e) {
            log.error("Failed to scale instance: {}", e.getMessage());
            instance.setStatus(InstanceStatus.ERROR.getDescription());
            instance.setStatusCode(InstanceStatus.ERROR.getCode());
            instance.setStatusMessage("Scale failed: " + e.getMessage());
            return instanceRepository.save(instance);
        }
    }

    /**
     * Get pods for an instance.
     */
    public List<Map<String, Object>> getInstancePods(Long id) {
        GatewayInstanceEntity instance = getInstanceById(id);
        KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId())
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        CoreV1Api coreApi = new CoreV1Api(client);

        try {
            String labelSelector = "gateway-instance-id=" + instance.getInstanceId();
            V1PodList pods = coreApi.listNamespacedPod(instance.getNamespace())
                    .labelSelector(labelSelector)
                    .execute();

            List<Map<String, Object>> result = new ArrayList<>();
            for (V1Pod pod : pods.getItems()) {
                Map<String, Object> podInfo = new HashMap<>();
                podInfo.put("name", pod.getMetadata().getName());
                podInfo.put("namespace", pod.getMetadata().getNamespace());
                podInfo.put("phase", pod.getStatus().getPhase());
                podInfo.put("podIP", pod.getStatus().getPodIP());
                podInfo.put("startTime", pod.getStatus().getStartTime());

                if (pod.getStatus().getContainerStatuses() != null) {
                    List<Map<String, Object>> containers = new ArrayList<>();
                    for (V1ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                        Map<String, Object> containerInfo = new HashMap<>();
                        containerInfo.put("name", status.getName());
                        containerInfo.put("ready", status.getReady());
                        containerInfo.put("restartCount", status.getRestartCount());
                        containerInfo.put("image", status.getImage());
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
     * Refresh instance status from Kubernetes.
     * This is a manual refresh, not the automatic health check.
     */
    @Transactional
    public GatewayInstanceEntity refreshStatus(Long id) {
        GatewayInstanceEntity instance = getInstanceById(id);
        KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId())
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        AppsV1Api appsApi = new AppsV1Api(client);

        try {
            V1Deployment deployment = appsApi.readNamespacedDeployment(
                    instance.getDeploymentName(),
                    instance.getNamespace()
            ).execute();

            Integer readyReplicas = deployment.getStatus().getReadyReplicas();
            Integer desiredReplicas = deployment.getSpec().getReplicas();

            // Only update status if it's a manual refresh, not overriding heartbeat-based status
            if (desiredReplicas == null || desiredReplicas == 0) {
                if (instance.getStatusCode() == InstanceStatus.STOPPING.getCode()) {
                    // Still stopping, will be set to STOPPED by health check
                }
            }
            // Don't override RUNNING status - it's determined by heartbeat

            return instanceRepository.save(instance);
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                instance.setStatus(InstanceStatus.ERROR.getDescription());
                instance.setStatusCode(InstanceStatus.ERROR.getCode());
                instance.setStatusMessage("Deployment not found");
            } else {
                instance.setStatus(InstanceStatus.ERROR.getDescription());
                instance.setStatusCode(InstanceStatus.ERROR.getCode());
                instance.setStatusMessage("Refresh failed: " + e.getMessage());
            }
            return instanceRepository.save(instance);
        }
    }

    /**
     * Handle heartbeat from gateway instance.
     * This is called by the InstanceHealthController.
     */
    @Transactional
    public void handleHeartbeat(String instanceId, Map<String, Object> metrics) {
        GatewayInstanceEntity instance = instanceRepository.findByInstanceId(instanceId)
                .orElse(null);
        
        if (instance == null) {
            log.warn("Heartbeat received for unknown instance: {}", instanceId);
            return;
        }
        
        // Update heartbeat time (all states)
        instance.setLastHeartbeatTime(LocalDateTime.now());
        instance.setMissedHeartbeats(0);
        
        // Only update status from STARTING or ERROR to RUNNING
        Integer currentStatus = instance.getStatusCode();
        if (currentStatus == null || 
            currentStatus == InstanceStatus.STARTING.getCode() || 
            currentStatus == InstanceStatus.ERROR.getCode()) {
            instance.setStatus(InstanceStatus.RUNNING.getDescription());
            instance.setStatusCode(InstanceStatus.RUNNING.getCode());
            instance.setStatusMessage(null);
            log.info("Instance {} status changed to RUNNING", instanceId);
        }
        
        // RUNNING state: only update heartbeat time, don't change status
        
        instanceRepository.save(instance);
        log.debug("Heartbeat received from instance: {}", instanceId);
    }

    /**
     * Update instance replicas (direct scale, no restart needed).
     */
    @Transactional
    public GatewayInstanceEntity updateReplicas(Long id, Integer replicas) {
        GatewayInstanceEntity instance = getInstanceById(id);
        
        if (replicas == null || replicas < 1 || replicas > 10) {
            throw new IllegalArgumentException("Replicas must be between 1 and 10");
        }
        
        // Only allow update when running
        if (instance.getStatusCode() != InstanceStatus.RUNNING.getCode()) {
            throw new IllegalStateException("Can only update replicas when instance is running");
        }
        
        instance.setReplicas(replicas);
        
        KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId())
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));
        
        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        AppsV1Api appsApi = new AppsV1Api(client);
        
        try {
            V1Deployment deployment = appsApi.readNamespacedDeployment(
                    instance.getDeploymentName(),
                    instance.getNamespace()
            ).execute();
            
            deployment.getSpec().setReplicas(replicas);
            appsApi.replaceNamespacedDeployment(
                    instance.getDeploymentName(),
                    instance.getNamespace(),
                    deployment
            ).execute();
            
            return instanceRepository.save(instance);
        } catch (ApiException e) {
            log.error("Failed to update replicas: {}", e.getMessage());
            throw new RuntimeException("Failed to update replicas: " + e.getMessage());
        }
    }

    /**
     * Update instance spec (CPU/memory) - requires pod restart.
     */
    @Transactional
    public GatewayInstanceEntity updateSpec(Long id, String specType, Double cpuCores, Integer memoryMB) {
        GatewayInstanceEntity instance = getInstanceById(id);
        
        // Only allow update when running or stopped
        Integer currentStatus = instance.getStatusCode();
        if (currentStatus != InstanceStatus.RUNNING.getCode() && 
            currentStatus != InstanceStatus.STOPPED.getCode()) {
            throw new IllegalStateException("Can only update spec when instance is running or stopped");
        }
        
        // Determine spec values
        InstanceSpec spec = InstanceSpec.fromType(specType != null ? specType : "custom");
        if (!spec.isCustom()) {
            cpuCores = spec.getCpuCores();
            memoryMB = spec.getMemoryMB();
        }
        
        if (cpuCores == null || memoryMB == null) {
            throw new IllegalArgumentException("CPU and memory must be specified");
        }
        
        instance.setSpecType(specType != null ? specType : "custom");
        instance.setCpuCores(cpuCores);
        instance.setMemoryMB(memoryMB);
        
        KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId())
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));
        
        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        AppsV1Api appsApi = new AppsV1Api(client);
        
        try {
            V1Deployment deployment = appsApi.readNamespacedDeployment(
                    instance.getDeploymentName(),
                    instance.getNamespace()
            ).execute();
            
            // Update container resources
            V1Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
            V1ResourceRequirements resources = container.getResources();
            if (resources == null) {
                resources = new V1ResourceRequirements();
            }
            
            Map<String, Quantity> limits = new HashMap<>();
            Map<String, Quantity> requests = new HashMap<>();
            limits.put("cpu", new Quantity(cpuCores.toString()));
            limits.put("memory", new Quantity(memoryMB + "Mi"));
            requests.put("cpu", new Quantity((cpuCores / 2) + ""));
            requests.put("memory", new Quantity((memoryMB / 2) + "Mi"));
            
            resources.setLimits(limits);
            resources.setRequests(requests);
            container.setResources(resources);
            
            appsApi.replaceNamespacedDeployment(
                    instance.getDeploymentName(),
                    instance.getNamespace(),
                    deployment
            ).execute();
            
            // Pods will restart automatically due to deployment update
            if (currentStatus == InstanceStatus.RUNNING.getCode()) {
                instance.setStatus(InstanceStatus.STARTING.getDescription());
                instance.setStatusCode(InstanceStatus.STARTING.getCode());
                instance.setStatusMessage("Spec updated, waiting for pods to restart");
            }
            
            return instanceRepository.save(instance);
        } catch (ApiException e) {
            log.error("Failed to update spec: {}", e.getMessage());
            throw new RuntimeException("Failed to update spec: " + e.getMessage());
        }
    }

    /**
     * Update instance image - supports rolling update for multi-replica.
     */
    @Transactional
    public GatewayInstanceEntity updateImage(Long id, String image) {
        GatewayInstanceEntity instance = getInstanceById(id);
        
        // Only allow update when running
        if (instance.getStatusCode() != InstanceStatus.RUNNING.getCode()) {
            throw new IllegalStateException("Can only update image when instance is running");
        }
        
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Image must be specified");
        }
        
        instance.setImage(image);
        
        KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId())
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));
        
        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        AppsV1Api appsApi = new AppsV1Api(client);
        
        try {
            V1Deployment deployment = appsApi.readNamespacedDeployment(
                    instance.getDeploymentName(),
                    instance.getNamespace()
            ).execute();
            
            // Update image
            V1Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
            container.setImage(image);
            
            // For multi-replica, K8s will do rolling update automatically
            // For single replica, it will restart the pod
            appsApi.replaceNamespacedDeployment(
                    instance.getDeploymentName(),
                    instance.getNamespace(),
                    deployment
            ).execute();
            
            int replicas = instance.getReplicas() != null ? instance.getReplicas() : 1;
            if (replicas == 1) {
                // Single replica: will have brief downtime
                instance.setStatus(InstanceStatus.STARTING.getDescription());
                instance.setStatusCode(InstanceStatus.STARTING.getCode());
                instance.setStatusMessage("Image updated, pod restarting");
            } else {
                // Multi-replica: rolling update, status stays RUNNING
                instance.setStatusMessage("Image updated, rolling update in progress");
            }
            
            return instanceRepository.save(instance);
        } catch (ApiException e) {
            log.error("Failed to update image: {}", e.getMessage());
            throw new RuntimeException("Failed to update image: " + e.getMessage());
        }
    }

    /**
     * Get pod count for an instance from Kubernetes.
     */
    public int getRunningPodCount(GatewayInstanceEntity instance) {
        KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId())
                .orElse(null);
        if (cluster == null) {
            return 0;
        }
        
        ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
        CoreV1Api coreApi = new CoreV1Api(client);
        
        try {
            String labelSelector = "gateway-instance-id=" + instance.getInstanceId();
            V1PodList pods = coreApi.listNamespacedPod(instance.getNamespace())
                    .labelSelector(labelSelector)
                    .execute();
            
            int runningCount = 0;
            for (V1Pod pod : pods.getItems()) {
                if ("Running".equals(pod.getStatus().getPhase())) {
                    runningCount++;
                }
            }
            return runningCount;
        } catch (ApiException e) {
            log.error("Failed to get pod count: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Generate a short random ID (12 characters, lowercase alphanumeric).
     * Format: abc123def456
     */
    private String generateShortId() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}