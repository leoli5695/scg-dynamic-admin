package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.dto.InstanceCreateRequest;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.InstanceSpec;
import com.leoli.gateway.admin.model.InstanceStatus;
import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.*;
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
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private RestTemplate restTemplate;

    // Repositories for cascade delete
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
    private InstanceNamespaceCache namespaceCache;

    @Value("${gateway.image.default:my-gateway:latest}")
    private String defaultImage;

    @Value("${gateway.image.pull-policy:IfNotPresent}")
    private String imagePullPolicy;

    @Value("${nacos.server-addr:localhost:8848}")
    private String nacosServerAddr;

    @Value("${nacos.k8s-server-addr:}")
    private String nacosK8sServerAddr;

    @Value("${nacos.k8s-namespace:test}")
    private String nacosK8sNamespace;

    @Value("${nacos.k8s-service-name:nacos}")
    private String nacosK8sServiceName;

    @Value("${nacos.k8s-port:8848}")
    private Integer nacosK8sPort;

    @Value("${nacos.group:DEFAULT_GROUP}")
    private String nacosGroup;

    // Redis configuration (optional, for distributed rate limiting)
    @Value("${redis.k8s-server-addr:}")
    private String redisK8sServerAddr;

    @Value("${redis.k8s-namespace:test}")
    private String redisK8sNamespace;

    @Value("${redis.k8s-service-name:redis}")
    private String redisK8sServiceName;

    @Value("${redis.k8s-port:6379}")
    private Integer redisK8sPort;

    @Value("${gateway.jaeger.k8s-server-addr:}")
    private String jaegerK8sServerAddr;

    @Value("${gateway.jaeger.k8s-namespace:test}")
    private String jaegerK8sNamespace;

    @Value("${gateway.jaeger.k8s-service-name:jaeger}")
    private String jaegerK8sServiceName;

    @Value("${gateway.jaeger.k8s-port:4317}")
    private Integer jaegerK8sPort;

    @Value("${gateway.prometheus.k8s-server-addr:}")
    private String prometheusK8sServerAddr;

    @Value("${gateway.prometheus.k8s-namespace:test}")
    private String prometheusK8sNamespace;

    @Value("${gateway.prometheus.k8s-service-name:prometheus}")
    private String prometheusK8sServiceName;

    @Value("${gateway.prometheus.k8s-port:9090}")
    private Integer prometheusK8sPort;

    @Value("${gateway.admin.url:http://localhost:9090}")
    private String gatewayAdminUrl;

    @Value("${gateway.admin.k8sUrl:}")
    private String gatewayAdminK8sUrl;

    @Value("${gateway.admin.host:localhost}")
    private String gatewayAdminHost;

    @Value("${gateway.admin.port:9090}")
    private Integer gatewayAdminPort;

    @Value("${gateway.instance.port:9090}")
    private Integer gatewayInstancePort;

    @Value("${gateway.instance.server-port:80}")
    private Integer defaultServerPort;

    @Value("${gateway.instance.management-port:9091}")
    private Integer defaultManagementPort;

    // Cache for ApiClients by cluster ID
    private final Map<Long, ApiClient> clientCache = new HashMap<>();

    /**
     * Get Nacos server address for K8s internal access.
     * Priority: 1. Instance custom address  2. Global k8s-server-addr  3. Auto-built from namespace/service/port
     * @param instance the gateway instance (can be null for default address)
     */
    private String getNacosK8sAddress(GatewayInstanceEntity instance) {
        // 1. If instance has a custom Nacos address, use it (for cross-cluster scenarios)
        if (instance != null && instance.getNacosServerAddr() != null && !instance.getNacosServerAddr().isEmpty()) {
            return instance.getNacosServerAddr();
        }
        // 2. If global k8s-server-addr is explicitly set, use it
        if (nacosK8sServerAddr != null && !nacosK8sServerAddr.isEmpty()) {
            return nacosK8sServerAddr;
        }
        // 3. Build K8s internal DNS: {service-name}.{namespace}.svc.cluster.local:{port}
        return String.format("%s.%s.svc.cluster.local:%d",
            nacosK8sServiceName, nacosK8sNamespace, nacosK8sPort);
    }

    /**
     * Get Redis server address for K8s internal access.
     * Redis is optional - returns null if not configured, gateway will use local rate limiting.
     * Priority: 1. Instance custom address  2. Global k8s-server-addr  3. Auto-built from namespace/service/port
     */
    private String getRedisK8sAddress(GatewayInstanceEntity instance) {
        // 1. If instance has a custom Redis address, use it (for cross-cluster scenarios)
        if (instance != null && instance.getRedisServerAddr() != null && !instance.getRedisServerAddr().isEmpty()) {
            return instance.getRedisServerAddr();
        }
        // 2. If global k8s-server-addr is explicitly set, use it
        if (redisK8sServerAddr != null && !redisK8sServerAddr.isEmpty()) {
            return redisK8sServerAddr;
        }
        // 3. Build K8s internal DNS: {service-name}.{namespace}.svc.cluster.local:{port}
        return String.format("%s.%s.svc.cluster.local:%d",
                redisK8sServiceName, redisK8sNamespace, redisK8sPort);
    }

    /**
     * Get Jaeger OTLP server address for K8s internal access.
     * Jaeger is optional - returns null if not configured, gateway will disable distributed tracing.
     * Priority: 1. Instance custom address  2. Global k8s-server-addr  3. Auto-built from namespace/service/port
     */
    private String getJaegerK8sAddress(GatewayInstanceEntity instance) {
        // 1. If instance has a custom Jaeger address, use it (for cross-cluster scenarios)
        if (instance != null && instance.getJaegerServerAddr() != null && !instance.getJaegerServerAddr().isEmpty()) {
            return instance.getJaegerServerAddr();
        }
        // 2. If global k8s-server-addr is explicitly set, use it
        if (jaegerK8sServerAddr != null && !jaegerK8sServerAddr.isEmpty()) {
            return jaegerK8sServerAddr;
        }
        // 3. Build K8s internal DNS: {service-name}.{namespace}.svc.cluster.local:{port}
        return String.format("%s.%s.svc.cluster.local:%d",
                jaegerK8sServiceName, jaegerK8sNamespace, jaegerK8sPort);
    }

    /**
     * Get Prometheus server address for K8s internal access.
     * Prometheus is optional - returns null if not configured.
     * Priority: 1. Instance custom address  2. Global k8s-server-addr  3. Auto-built from namespace/service/port
     */
    private String getPrometheusK8sAddress(GatewayInstanceEntity instance) {
        // 1. If instance has a custom Prometheus address, use it (for cross-cluster scenarios)
        if (instance != null && instance.getPrometheusServerAddr() != null && !instance.getPrometheusServerAddr().isEmpty()) {
            return instance.getPrometheusServerAddr();
        }
        // 2. If global k8s-server-addr is explicitly set, use it
        if (prometheusK8sServerAddr != null && !prometheusK8sServerAddr.isEmpty()) {
            return prometheusK8sServerAddr;
        }
        // 3. Build K8s internal DNS: {service-name}.{namespace}.svc.cluster.local:{port}
        return String.format("%s.%s.svc.cluster.local:%d",
                prometheusK8sServiceName, prometheusK8sNamespace, prometheusK8sPort);
    }

    /**
     * Create Nacos namespace for gateway instance.
     * Uses Nacos HTTP API to create the namespace.
     */
    private void createNacosNamespace(String namespaceId, String namespaceName, String description) {
        try {
            // Build Nacos API URL
            String nacosApiUrl = nacosServerAddr;
            if (nacosApiUrl == null || nacosApiUrl.isEmpty()) {
                nacosApiUrl = "localhost:8848";
            }

            // Create namespace via Nacos HTTP API
            // Note: Nacos requires 'customNamespaceId' parameter for custom namespaces
            String url = String.format("http://%s/nacos/v1/console/namespaces?customNamespaceId=%s&namespaceName=%s&namespaceDesc=%s",
                    nacosApiUrl,
                    URLEncoder.encode(namespaceId, StandardCharsets.UTF_8),
                    URLEncoder.encode(namespaceName, StandardCharsets.UTF_8),
                    URLEncoder.encode(description != null ? description : "Gateway instance namespace", StandardCharsets.UTF_8));

            log.info("Creating Nacos namespace: {} ({})", namespaceName, namespaceId);
            String response = restTemplate.postForObject(url, null, String.class);
            log.info("Nacos namespace creation response: {}", response);
        } catch (Exception e) {
            log.warn("Failed to create Nacos namespace {}: {}. Config will still be published to this namespace.",
                    namespaceId, e.getMessage());
            // Don't throw - Nacos will auto-create namespace when config is published
        }
    }

    /**
     * Delete Nacos namespace.
     */
    private void deleteNacosNamespace(String namespaceId) {
        if (namespaceId == null || namespaceId.isEmpty()) {
            log.info("No Nacos namespace to delete");
            return;
        }

        try {
            String nacosApiUrl = nacosServerAddr;
            if (nacosApiUrl == null || nacosApiUrl.isEmpty()) {
                nacosApiUrl = "localhost:8848";
            }

            String url = String.format("http://%s/nacos/v1/console/namespaces?namespaceId=%s",
                    nacosApiUrl,
                    URLEncoder.encode(namespaceId, StandardCharsets.UTF_8));

            log.info("Deleting Nacos namespace: {}", namespaceId);
            restTemplate.delete(url);
            log.info("Nacos namespace {} deleted successfully", namespaceId);
        } catch (Exception e) {
            log.warn("Failed to delete Nacos namespace {}: {}", namespaceId, e.getMessage());
        }
    }

    /**
     * Get all instances.
     */
    public List<GatewayInstanceEntity> getAllInstances() {
        List<GatewayInstanceEntity> instances = instanceRepository.findAll();
        // Refresh service info from K8s for each instance
        for (GatewayInstanceEntity instance : instances) {
            refreshServiceInfoFromK8s(instance);
        }
        return instances;
    }

    /**
     * Get instance by ID.
     */
    public GatewayInstanceEntity getInstanceById(Long id) {
        GatewayInstanceEntity instance = instanceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + id));
        // Refresh service info from K8s
        refreshServiceInfoFromK8s(instance);
        fillDefaultNacosAddr(instance);
        return instance;
    }

    /**
     * Refresh service info (serviceType, nodePort, nodeIp) from Kubernetes.
     * Falls back to database values if K8s query fails.
     */
    private void refreshServiceInfoFromK8s(GatewayInstanceEntity instance) {
        if (instance.getClusterId() == null || instance.getServiceName() == null) {
            return;
        }

        try {
            KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId()).orElse(null);
            if (cluster == null) {
                return;
            }

            ApiClient client = getApiClient(cluster.getId(), cluster.getKubeconfig());
            CoreV1Api coreApi = new CoreV1Api(client);

            // Query Service from K8s
            V1Service service = coreApi.readNamespacedService(
                    instance.getServiceName(),
                    instance.getNamespace()
            ).execute();

            if (service != null && service.getSpec() != null) {
                String serviceType = service.getSpec().getType();
                if (serviceType != null) {
                    instance.setServiceType(serviceType);
                }
                if (service.getSpec().getPorts() != null) {
                    Integer nodePort = service.getSpec().getPorts().get(0).getNodePort();
                    if (nodePort != null) {
                        instance.setNodePort(nodePort);
                    }
                }
            }

            // Query Node IP from K8s
            String nodeIp = getFirstNodeIp(coreApi);
            if (nodeIp != null) {
                instance.setNodeIp(nodeIp);
            }

            log.debug("Refreshed service info for instance {}: serviceType={}, nodePort={}, nodeIp={}",
                    instance.getInstanceId(), instance.getServiceType(), instance.getNodePort(), instance.getNodeIp());

        } catch (Exception e) {
            // K8s query failed, keep database values
            log.debug("Failed to refresh service info from K8s for instance {}: {}",
                    instance.getInstanceId(), e.getMessage());
        }
    }

    /**
     * Get instance by instance ID (UUID).
     */
    public GatewayInstanceEntity getInstanceByInstanceId(String instanceId) {
        GatewayInstanceEntity instance = instanceRepository.findByInstanceId(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));
        fillDefaultNacosAddr(instance);
        return instance;
    }

    /**
     * Fill default Nacos address if instance doesn't have one.
     */
    private void fillDefaultNacosAddr(GatewayInstanceEntity instance) {
        if (instance.getNacosServerAddr() == null || instance.getNacosServerAddr().isEmpty()) {
            instance.setNacosServerAddr(nacosServerAddr);
        }
    }

    /**
     * Get access URL for an instance.
     */
    public String getAccessUrl(String instanceId) {
        GatewayInstanceEntity instance = instanceRepository.findByInstanceId(instanceId)
                .orElse(null);
        if (instance == null) {
            return null;
        }
        return instance.getEffectiveAccessUrl();
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
        String shortId = instanceId.substring(0, 8);
        String deploymentName = "gateway-" + shortId;
        String nacosNamespace = deploymentName;  // Use same name as deployment (e.g., gateway-o0m1rhg5)

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

        // Generate service name
        String serviceName = deploymentName + "-service";

        // Get port configuration
        int serverPort = request.getServerPort() != null ? request.getServerPort() : defaultServerPort;
        int managementPort = request.getManagementPort() != null ? request.getManagementPort() : defaultManagementPort;

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
        entity.setServerPort(serverPort);
        entity.setManagementPort(managementPort);
        entity.setNacosServerAddr(request.getNacosServerAddr());  // Custom Nacos address (optional)
        entity.setRedisServerAddr(request.getRedisServerAddr());  // Custom Redis address (optional)
        entity.setJaegerServerAddr(request.getJaegerServerAddr());  // Custom Jaeger address (optional)
        entity.setPrometheusServerAddr(request.getPrometheusServerAddr());  // Custom Prometheus address (optional)
        entity.setStatus(InstanceStatus.STARTING.getDescription());
        entity.setStatusCode(InstanceStatus.STARTING.getCode());
        entity.setDeploymentName(deploymentName);
        entity.setServiceName(serviceName);
        entity.setEnabled(true);
        entity.setDescription(request.getDescription());
        entity.setMissedHeartbeats(0);

        // Save to database first
        entity = instanceRepository.save(entity);

        // Create Nacos namespace for this instance (use same name as deployment)
        createNacosNamespace(nacosNamespace,
                nacosNamespace,
                "Gateway instance namespace for " + request.getInstanceName());

        // Deploy to Kubernetes
        try {
            deployToKubernetes(entity, cluster, request.getCreateNamespace(), request.getImagePullPolicy(), serverPort, managementPort);
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

        // Update namespace cache
        namespaceCache.put(instanceId, nacosNamespace);

        return instanceRepository.save(entity);
    }

    /**
     * Deploy gateway to Kubernetes.
     */
    private void deployToKubernetes(GatewayInstanceEntity instance, KubernetesCluster cluster, boolean createNamespace, String imagePullPolicy, int serverPort, int managementPort) {
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
            V1Deployment deployment = createDeploymentManifest(instance, imagePullPolicy, serverPort, managementPort);
            V1Deployment createdDeployment = appsApi.createNamespacedDeployment(namespace, deployment).execute();
            log.info("Created deployment: {} with serverPort={}, managementPort={}", createdDeployment.getMetadata().getName(), serverPort, managementPort);

            // Create Service
            V1Service service = createServiceManifest(instance, serverPort);
            V1Service createdService = coreApi.createNamespacedService(namespace, service).execute();
            log.info("Created service: {}", createdService.getMetadata().getName());

            // Get Service type and NodePort
            if (createdService.getSpec() != null) {
                String serviceType = createdService.getSpec().getType();
                instance.setServiceType(serviceType);
                if (createdService.getSpec().getPorts() != null) {
                    Integer nodePort = createdService.getSpec().getPorts().get(0).getNodePort();
                    instance.setNodePort(nodePort);
                }
            }

            // Get Node IP for external access URL
            String nodeIp = getFirstNodeIp(coreApi);
            instance.setNodeIp(nodeIp);
            log.info("Set nodeIp for instance {}: {}", instance.getInstanceId(), nodeIp);

        } catch (ApiException e) {
            log.error("Kubernetes API error: code={}, message={}, body={}",
                    e.getCode(), e.getMessage(), e.getResponseBody());
            throw new RuntimeException("Kubernetes deployment failed: " + e.getMessage());
        }
    }

    /**
     * Create Deployment manifest.
     */
    private V1Deployment createDeploymentManifest(GatewayInstanceEntity instance, String pullPolicy, int serverPort, int managementPort) {
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
        // Use provided pullPolicy, default to Never if null
        container.setImagePullPolicy(pullPolicy != null ? pullPolicy : "Never");

        // Container ports - use serverPort for HTTP traffic
        List<V1ContainerPort> ports = new ArrayList<>();
        ports.add(new V1ContainerPort().containerPort(serverPort).name("http"));
        // Also expose management port for actuator/health endpoints
        ports.add(new V1ContainerPort().containerPort(managementPort).name("management"));
        container.setPorts(ports);

        // Environment variables
        List<V1EnvVar> envVars = new ArrayList<>();
        envVars.add(new V1EnvVar().name("GATEWAY_ID").value(instance.getInstanceId()));
        envVars.add(new V1EnvVar().name("GATEWAY_INSTANCE_ID").value(instance.getInstanceId()));
        envVars.add(new V1EnvVar().name("NACOS_SERVER_ADDR").value(getNacosK8sAddress(instance)));
        envVars.add(new V1EnvVar().name("NACOS_NAMESPACE").value(instance.getNacosNamespace()));
        envVars.add(new V1EnvVar().name("NACOS_GROUP").value(nacosGroup));
        // Use K8s internal URL if configured, otherwise fall back to external URL
        String adminUrl = (gatewayAdminK8sUrl != null && !gatewayAdminK8sUrl.isEmpty())
                ? gatewayAdminK8sUrl
                : gatewayAdminUrl;
        envVars.add(new V1EnvVar().name("GATEWAY_ADMIN_URL").value(adminUrl));
        // Redis is optional - if not configured, gateway uses local rate limiting
        String redisAddr = getRedisK8sAddress(instance);
        if (redisAddr != null && !redisAddr.isEmpty()) {
            envVars.add(new V1EnvVar().name("REDIS_HOST").value(redisAddr.split(":")[0]));
            envVars.add(new V1EnvVar().name("REDIS_PORT").value(redisAddr.contains(":") ? redisAddr.split(":")[1] : "6379"));
        }
        // Jaeger OTLP address for distributed tracing
        String jaegerAddr = getJaegerK8sAddress(instance);
        if (jaegerAddr != null && !jaegerAddr.isEmpty()) {
            envVars.add(new V1EnvVar().name("OTEL_EXPORTER_OTLP_ENDPOINT").value("http://" + jaegerAddr));
            envVars.add(new V1EnvVar().name("OTEL_TRACING_ENABLED").value("true"));
        }
        // Prometheus address for metrics push
        String prometheusAddr = getPrometheusK8sAddress(instance);
        if (prometheusAddr != null && !prometheusAddr.isEmpty()) {
            envVars.add(new V1EnvVar().name("PROMETHEUS_PUSH_URL").value("http://" + prometheusAddr));
        }
        // Add port environment variables for gateway to use
        envVars.add(new V1EnvVar().name("SERVER_PORT").value(String.valueOf(serverPort)));
        envVars.add(new V1EnvVar().name("MANAGEMENT_SERVER_PORT").value(String.valueOf(managementPort)));
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

        // Health checks - Liveness probe (use management port for actuator)
        V1Probe livenessProbe = new V1Probe();
        livenessProbe.setHttpGet(new V1HTTPGetAction()
                .path("/actuator/health/liveness")
                .port(new io.kubernetes.client.custom.IntOrString(managementPort)));
        livenessProbe.setInitialDelaySeconds(60);
        livenessProbe.setPeriodSeconds(10);
        livenessProbe.setTimeoutSeconds(5);
        livenessProbe.setFailureThreshold(3);
        container.setLivenessProbe(livenessProbe);

        // Health checks - Readiness probe (use management port for actuator)
        V1Probe readinessProbe = new V1Probe();
        readinessProbe.setHttpGet(new V1HTTPGetAction()
                .path("/actuator/health/readiness")
                .port(new io.kubernetes.client.custom.IntOrString(managementPort)));
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
    private V1Service createServiceManifest(GatewayInstanceEntity instance, int serverPort) {
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
        servicePort.setPort(serverPort);
        servicePort.setTargetPort(new io.kubernetes.client.custom.IntOrString(serverPort));
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
     * Delete an instance with all related data.
     * This will delete:
     * 1. All routes, services, strategies, auth policies, certificates from database
     * 2. All configs from Nacos namespace
     * 3. Kubernetes deployment and service
     * 4. The instance itself
     */
    @Transactional
    public void deleteInstance(Long id) {
        GatewayInstanceEntity instance = getInstanceById(id);
        String instanceId = instance.getInstanceId();
        String nacosNamespace = instance.getNacosNamespace();

        log.info("Deleting instance {} (instanceId={}, nacosNamespace={})", id, instanceId, nacosNamespace);

        // 1. Delete all related data from database
        deleteRelatedDataFromDatabase(instanceId);

        // 2. Delete all configs from Nacos namespace
        deleteConfigsFromNacos(nacosNamespace);

        // 2.1 Delete Nacos namespace itself
        deleteNacosNamespace(nacosNamespace);

        // 3. Delete from Kubernetes
        try {
            KubernetesCluster cluster = clusterRepository.findById(instance.getClusterId())
                    .orElse(null);
            if (cluster != null) {
                deleteFromKubernetes(instance, cluster);
            }
        } catch (Exception e) {
            log.warn("Failed to delete instance {} from Kubernetes: {}", id, e.getMessage());
        }

        // 4. Remove from namespace cache
        namespaceCache.remove(instanceId);

        // 5. Delete the instance itself
        instanceRepository.deleteById(id);
        log.info("Instance {} deleted successfully", instanceId);
    }

    /**
     * Delete all related data from database by instance ID.
     */
    private void deleteRelatedDataFromDatabase(String instanceId) {
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
    }

    /**
     * Delete all configs from Nacos namespace.
     */
    private void deleteConfigsFromNacos(String nacosNamespace) {
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

            // Note: Individual route/service/strategy configs are deleted by their respective services
            // when we delete them from database above. But we also need to clean up any remaining configs.

            // Get all configs in the namespace and delete them
            // This is a safety net to ensure complete cleanup
            deleteAllConfigsInNamespace(nacosNamespace);

            log.info("All configs deleted from Nacos namespace: {}", nacosNamespace);
        } catch (Exception e) {
            log.error("Failed to delete configs from Nacos namespace: {}", nacosNamespace, e);
        }
    }

    /**
     * Delete all gateway-related configs in a Nacos namespace.
     */
    private void deleteAllConfigsInNamespace(String nacosNamespace) {
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
     * Delete Nacos namespace via HTTP API.
     */
    private void deleteNacosNamespace(String namespaceId) {
        if (namespaceId == null || namespaceId.isEmpty()) {
            return;
        }

        try {
            String nacosApiUrl = nacosServerAddr;
            if (nacosApiUrl == null || nacosApiUrl.isEmpty()) {
                nacosApiUrl = "localhost:8848";
            }

            String url = String.format("http://%s/nacos/v1/console/namespaces?namespaceId=%s",
                    nacosApiUrl, URLEncoder.encode(namespaceId, StandardCharsets.UTF_8));

            log.info("Deleting Nacos namespace: {}", namespaceId);
            restTemplate.delete(url);
            log.info("Nacos namespace {} deleted successfully", namespaceId);
        } catch (Exception e) {
            log.warn("Failed to delete Nacos namespace {}: {}", namespaceId, e.getMessage());
        }
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
     *
     * @param instanceId     The instance ID
     * @param metrics        Optional metrics from the gateway
     * @param accessUrl      The access URL reported by gateway (for local dev, ECS direct)
     * @param serverPort     The server port reported by gateway
     * @param managementPort The management port reported by gateway
     */
    @Transactional
    public void handleHeartbeat(String instanceId, Map<String, Object> metrics,
                                String accessUrl, Integer serverPort, Integer managementPort) {
        GatewayInstanceEntity instance = instanceRepository.findByInstanceId(instanceId)
                .orElse(null);

        if (instance == null) {
            log.warn("Heartbeat received for unknown instance: {}", instanceId);
            return;
        }

        // Update heartbeat time (all states)
        instance.setLastHeartbeatTime(LocalDateTime.now());
        instance.setMissedHeartbeats(0);

        // Update reported access URL if provided
        if (accessUrl != null && !accessUrl.isEmpty()) {
            instance.setReportedAccessUrl(accessUrl);
            log.debug("Instance {} reported access URL: {}", instanceId, accessUrl);
        }

        // Update ports if provided
        if (serverPort != null) {
            instance.setServerPort(serverPort);
        }
        if (managementPort != null) {
            instance.setManagementPort(managementPort);
        }

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

    /**
     * Get the first available node IP from Kubernetes cluster.
     * Priority: ExternalIP > InternalIP
     * For local development environments (Rancher Desktop, Docker Desktop, etc.),
     * returns "localhost" since NodePort is accessible via localhost.
     */
    private String getFirstNodeIp(CoreV1Api coreApi) {
        try {
            V1NodeList nodes = coreApi.listNode().execute();
            if (nodes.getItems() == null || nodes.getItems().isEmpty()) {
                log.warn("No nodes found in Kubernetes cluster");
                return null;
            }

            for (V1Node node : nodes.getItems()) {
                if (node.getStatus() != null && node.getStatus().getAddresses() != null) {
                    // Priority: ExternalIP first, then InternalIP
                    String externalIp = null;
                    String internalIp = null;

                    for (V1NodeAddress address : node.getStatus().getAddresses()) {
                        log.debug("Node address - type: {}, address: {}", address.getType(), address.getAddress());
                        if ("ExternalIP".equals(address.getType())) {
                            externalIp = address.getAddress();
                        } else if ("InternalIP".equals(address.getType())) {
                            internalIp = address.getAddress();
                        }
                    }

                    // Check if this is a local development environment
                    boolean isLocalDev = isLocalDevelopmentEnvironment(node);

                    // Return ExternalIP if available
                    if (externalIp != null) {
                        log.info("Found ExternalIP: {}", externalIp);
                        return externalIp;
                    }

                    // For local development environments, return localhost
                    // because NodePort is accessible via localhost (not InternalIP)
                    if (isLocalDev) {
                        log.info("Local development environment detected, using localhost for NodePort access");
                        return "localhost";
                    }

                    // Otherwise return InternalIP
                    if (internalIp != null) {
                        log.info("Found InternalIP: {}", internalIp);
                        return internalIp;
                    }
                }
            }

            log.warn("No node IP found in any node");
            return null;
        } catch (ApiException e) {
            log.error("Failed to get node IP: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if the node is running in a local development environment.
     * Local environments like Rancher Desktop, Docker Desktop, Minikube, etc.
     * have NodePort accessible via localhost, not via InternalIP.
     */
    private boolean isLocalDevelopmentEnvironment(V1Node node) {
        // Check OS Image for Rancher Desktop or Docker Desktop
        if (node.getStatus() != null && node.getStatus().getNodeInfo() != null) {
            String osImage = node.getStatus().getNodeInfo().getOsImage();
            if (osImage != null) {
                String osImageLower = osImage.toLowerCase();
                if (osImageLower.contains("rancher desktop") ||
                        osImageLower.contains("docker desktop") ||
                        osImageLower.contains("docker desktop")) {
                    return true;
                }
            }
        }

        // Check hostname for common local dev patterns
        if (node.getMetadata() != null && node.getMetadata().getName() != null) {
            String hostname = node.getMetadata().getName().toLowerCase();
            if (hostname.equals("localhost") ||
                    hostname.equals("docker-desktop") ||
                    hostname.equals("minikube") ||
                    hostname.startsWith("kind-") ||
                    hostname.startsWith("k3d-")) {
                return true;
            }
        }

        return false;
    }
}