package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * K8s 部署清单构建器.
 * 从 GatewayInstanceService 拆分而来，负责构建 Kubernetes Deployment 和 Service 清单，
 * 以及解析各中间件（Nacos/Redis/Jaeger/Prometheus）的 K8s 内部访问地址.
 *
 * @author leoli
 */
@Slf4j
@Component
public class K8sManifestBuilder {

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

    @Value("${gateway.jaeger.k8s-port:4318}")
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

    /**
     * Get Nacos server address for K8s internal access.
     * Priority: 1. Instance custom address  2. Global k8s-server-addr  3. Auto-built from namespace/service/port
     */
    String getNacosK8sAddress(GatewayInstanceEntity instance) {
        if (instance != null && instance.getNacosServerAddr() != null && !instance.getNacosServerAddr().isEmpty()) {
            return instance.getNacosServerAddr();
        }
        if (nacosK8sServerAddr != null && !nacosK8sServerAddr.isEmpty()) {
            return nacosK8sServerAddr;
        }
        return String.format("%s.%s.svc.cluster.local:%d",
            nacosK8sServiceName, nacosK8sNamespace, nacosK8sPort);
    }

    /**
     * Get Redis server address for K8s internal access.
     * Redis is optional - returns null if not configured, gateway will use local rate limiting.
     */
    String getRedisK8sAddress(GatewayInstanceEntity instance) {
        if (instance != null && instance.getRedisServerAddr() != null && !instance.getRedisServerAddr().isEmpty()) {
            return instance.getRedisServerAddr();
        }
        if (redisK8sServerAddr != null && !redisK8sServerAddr.isEmpty()) {
            return redisK8sServerAddr;
        }
        return String.format("%s.%s.svc.cluster.local:%d",
                redisK8sServiceName, redisK8sNamespace, redisK8sPort);
    }

    /**
     * Get Jaeger OTLP server address for K8s internal access.
     * Jaeger is optional - returns null if not configured.
     */
    String getJaegerK8sAddress(GatewayInstanceEntity instance) {
        if (instance != null && instance.getJaegerServerAddr() != null && !instance.getJaegerServerAddr().isEmpty()) {
            return instance.getJaegerServerAddr();
        }
        if (jaegerK8sServerAddr != null && !jaegerK8sServerAddr.isEmpty()) {
            return jaegerK8sServerAddr;
        }
        return String.format("%s.%s.svc.cluster.local:%d",
                jaegerK8sServiceName, jaegerK8sNamespace, jaegerK8sPort);
    }

    /**
     * Get Prometheus server address for K8s internal access.
     * Prometheus is optional - returns null if not configured.
     */
    String getPrometheusK8sAddress(GatewayInstanceEntity instance) {
        if (instance != null && instance.getPrometheusServerAddr() != null && !instance.getPrometheusServerAddr().isEmpty()) {
            return instance.getPrometheusServerAddr();
        }
        if (prometheusK8sServerAddr != null && !prometheusK8sServerAddr.isEmpty()) {
            return prometheusK8sServerAddr;
        }
        return String.format("%s.%s.svc.cluster.local:%d",
                prometheusK8sServiceName, prometheusK8sNamespace, prometheusK8sPort);
    }

    /**
     * Create Deployment manifest.
     */
    V1Deployment createDeploymentManifest(GatewayInstanceEntity instance, String pullPolicy, int serverPort, int managementPort) {
        V1Deployment deployment = new V1Deployment();

        deployment.setMetadata(new V1ObjectMeta()
                .name(instance.getDeploymentName())
                .namespace(instance.getNamespace())
                .labels(Map.of(
                        "app", "gateway",
                        "gateway-instance-id", instance.getInstanceId())));

        V1DeploymentSpec spec = new V1DeploymentSpec();
        spec.setReplicas(instance.getReplicas());
        spec.setSelector(new V1LabelSelector().matchLabels(Map.of("app", instance.getDeploymentName())));

        V1PodTemplateSpec template = new V1PodTemplateSpec();
        template.setMetadata(new V1ObjectMeta().labels(Map.of(
                "app", instance.getDeploymentName(),
                "gateway-instance-id", instance.getInstanceId())));

        V1PodSpec podSpec = new V1PodSpec();
        List<V1Container> containers = new ArrayList<>();

        V1Container container = new V1Container();
        container.setName("gateway");
        container.setImage(instance.getImage());
        container.setImagePullPolicy(pullPolicy != null ? pullPolicy : "Never");

        // Container ports
        List<V1ContainerPort> ports = new ArrayList<>();
        ports.add(new V1ContainerPort().containerPort(serverPort).name("http"));
        ports.add(new V1ContainerPort().containerPort(managementPort).name("management"));
        container.setPorts(ports);

        // Environment variables
        List<V1EnvVar> envVars = new ArrayList<>();
        envVars.add(new V1EnvVar().name("GATEWAY_ID").value(instance.getInstanceId()));
        envVars.add(new V1EnvVar().name("GATEWAY_INSTANCE_ID").value(instance.getInstanceId()));
        envVars.add(new V1EnvVar().name("NACOS_SERVER_ADDR").value(getNacosK8sAddress(instance)));
        envVars.add(new V1EnvVar().name("NACOS_NAMESPACE").value(instance.getNacosNamespace()));
        envVars.add(new V1EnvVar().name("NACOS_GROUP").value(nacosGroup));
        String adminUrl = (gatewayAdminK8sUrl != null && !gatewayAdminK8sUrl.isEmpty())
                ? gatewayAdminK8sUrl
                : gatewayAdminUrl;
        envVars.add(new V1EnvVar().name("GATEWAY_ADMIN_URL").value(adminUrl));
        String redisAddr = getRedisK8sAddress(instance);
        if (redisAddr != null && !redisAddr.isEmpty()) {
            envVars.add(new V1EnvVar().name("REDIS_HOST").value(redisAddr.split(":")[0]));
            envVars.add(new V1EnvVar().name("REDIS_PORT").value(redisAddr.contains(":") ? redisAddr.split(":")[1] : "6379"));
        }
        String jaegerAddr = getJaegerK8sAddress(instance);
        if (jaegerAddr != null && !jaegerAddr.isEmpty()) {
            envVars.add(new V1EnvVar().name("OTEL_EXPORTER_OTLP_ENDPOINT").value("http://" + jaegerAddr));
            envVars.add(new V1EnvVar().name("OTEL_TRACING_ENABLED").value("true"));
        }
        String prometheusAddr = getPrometheusK8sAddress(instance);
        if (prometheusAddr != null && !prometheusAddr.isEmpty()) {
            envVars.add(new V1EnvVar().name("PROMETHEUS_PUSH_URL").value("http://" + prometheusAddr));
        }
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

        // Health checks - Liveness probe
        V1Probe livenessProbe = new V1Probe();
        livenessProbe.setHttpGet(new V1HTTPGetAction()
                .path("/actuator/health/liveness")
                .port(new io.kubernetes.client.custom.IntOrString(managementPort)));
        livenessProbe.setInitialDelaySeconds(60);
        livenessProbe.setPeriodSeconds(10);
        livenessProbe.setTimeoutSeconds(5);
        livenessProbe.setFailureThreshold(3);
        container.setLivenessProbe(livenessProbe);

        // Health checks - Readiness probe
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
    V1Service createServiceManifest(GatewayInstanceEntity instance, int serverPort, int managementPort) {
        V1Service service = new V1Service();

        service.setMetadata(new V1ObjectMeta()
                .name(instance.getServiceName())
                .namespace(instance.getNamespace())
                .labels(Map.of(
                        "app", "gateway",
                        "gateway-instance-id", instance.getInstanceId())));

        V1ServiceSpec spec = new V1ServiceSpec();
        spec.setSelector(Map.of("app", instance.getDeploymentName()));
        spec.setType("NodePort");

        List<V1ServicePort> ports = new ArrayList<>();

        // HTTP port
        V1ServicePort httpPort = new V1ServicePort();
        httpPort.setPort(serverPort);
        httpPort.setTargetPort(new io.kubernetes.client.custom.IntOrString(serverPort));
        httpPort.setProtocol("TCP");
        httpPort.setName("http");
        ports.add(httpPort);

        // HTTPS port
        V1ServicePort httpsPort = new V1ServicePort();
        httpsPort.setPort(443);
        httpsPort.setTargetPort(new io.kubernetes.client.custom.IntOrString(8443));
        httpsPort.setProtocol("TCP");
        httpsPort.setName("https");
        ports.add(httpsPort);

        // Management port
        V1ServicePort mgmtPort = new V1ServicePort();
        mgmtPort.setPort(managementPort);
        mgmtPort.setTargetPort(new io.kubernetes.client.custom.IntOrString(managementPort));
        mgmtPort.setProtocol("TCP");
        mgmtPort.setName("management");
        ports.add(mgmtPort);

        spec.setPorts(ports);
        service.setSpec(spec);

        return service;
    }
}
