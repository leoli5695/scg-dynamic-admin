package com.leoli.gateway.admin.service;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Queries Kubernetes resources: nodes, namespaces, pods, images.
 */
@Slf4j
@Service
public class KubernetesResourceService {

    private final ClusterConnectionService connectionService;

    public KubernetesResourceService(ClusterConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    public List<Map<String, Object>> getClusterNodes(Long clusterId) {
        ApiClient client = connectionService.getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1NodeList nodes = api.listNode().execute();
            List<Map<String, Object>> result = new ArrayList<>();

            for (V1Node node : nodes.getItems()) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", node.getMetadata().getName());

                if (node.getStatus() != null) {
                    info.put("status", Optional.ofNullable(node.getStatus().getConditions())
                            .orElse(Collections.emptyList()).stream()
                            .filter(c -> "Ready".equals(c.getType()))
                            .findFirst()
                            .map(V1NodeCondition::getStatus)
                            .orElse("Unknown"));

                    if (node.getStatus().getNodeInfo() != null) {
                        info.put("kubeletVersion", node.getStatus().getNodeInfo().getKubeletVersion());
                        info.put("osImage", node.getStatus().getNodeInfo().getOsImage());
                    }
                }
                result.add(info);
            }
            return result;
        } catch (ApiException e) {
            log.error("Failed to get nodes: {}", e.getMessage());
            throw new RuntimeException("Failed to get cluster nodes: " + e.getMessage());
        }
    }

    public List<String> getClusterNamespaces(Long clusterId) {
        ApiClient client = connectionService.getApiClient(clusterId);
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

    public List<Map<String, Object>> getPods(Long clusterId, String namespace) {
        ApiClient client = connectionService.getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1PodList pods = (namespace == null || namespace.isEmpty())
                    ? api.listPodForAllNamespaces().execute()
                    : api.listNamespacedPod(namespace).execute();

            List<Map<String, Object>> result = new ArrayList<>();
            for (V1Pod pod : pods.getItems()) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", pod.getMetadata().getName());
                info.put("namespace", pod.getMetadata().getNamespace());
                info.put("phase", pod.getStatus().getPhase());
                info.put("podIP", pod.getStatus().getPodIP());
                info.put("labels", pod.getMetadata().getLabels());

                if (pod.getStatus().getContainerStatuses() != null) {
                    List<Map<String, Object>> containers = pod.getStatus().getContainerStatuses().stream()
                            .map(s -> Map.<String, Object>of(
                                    "name", s.getName(),
                                    "ready", s.getReady(),
                                    "restartCount", s.getRestartCount()))
                            .toList();
                    info.put("containers", containers);
                }
                result.add(info);
            }
            return result;
        } catch (ApiException e) {
            log.error("Failed to get pods: {}", e.getMessage());
            throw new RuntimeException("Failed to get pods: " + e.getMessage());
        }
    }

    public Map<String, Object> getPodDetail(Long clusterId, String namespace, String podName) {
        ApiClient client = connectionService.getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1Pod pod = api.readNamespacedPod(podName, namespace).execute();
            Map<String, Object> result = new HashMap<>();

            result.put("name", pod.getMetadata().getName());
            result.put("namespace", pod.getMetadata().getNamespace());
            result.put("phase", pod.getStatus().getPhase());
            result.put("podIP", pod.getStatus().getPodIP());
            result.put("hostIP", pod.getStatus().getHostIP());
            result.put("startTime", pod.getStatus().getStartTime());
            result.put("labels", pod.getMetadata().getLabels());
            result.put("annotations", pod.getMetadata().getAnnotations());

            if (pod.getSpec() != null) {
                result.put("nodeName", pod.getSpec().getNodeName());
                result.put("serviceAccountName", pod.getSpec().getServiceAccountName());
                result.put("restartPolicy", pod.getSpec().getRestartPolicy());
                result.put("dnsPolicy", pod.getSpec().getDnsPolicy());

                List<Map<String, Object>> containers = Optional.ofNullable(pod.getSpec().getContainers())
                        .orElse(Collections.emptyList()).stream()
                        .map(this::toContainerInfo)
                        .toList();
                result.put("containers", containers);
            }

            if (pod.getStatus().getContainerStatuses() != null) {
                List<Map<String, Object>> statuses = pod.getStatus().getContainerStatuses().stream()
                        .map(this::toContainerStatusInfo)
                        .toList();
                result.put("containerStatuses", statuses);
            }

            if (pod.getStatus().getConditions() != null) {
                List<Map<String, Object>> conditions = pod.getStatus().getConditions().stream()
                        .map(c -> {
                            Map<String, Object> map = new HashMap<>();
                            map.put("type", c.getType());
                            map.put("status", c.getStatus());
                            map.put("reason", c.getReason());
                            map.put("message", c.getMessage());
                            map.put("lastTransitionTime", c.getLastTransitionTime());
                            return map;
                        })
                        .toList();
                result.put("conditions", conditions);
            }

            // Events
            try {
                String fieldSelector = "involvedObject.name=" + podName + ",involvedObject.namespace=" + namespace;
                CoreV1EventList events = api.listNamespacedEvent(namespace)
                        .fieldSelector(fieldSelector).execute();
                if (events.getItems() != null) {
                    List<Map<String, Object>> eventList = events.getItems().stream()
                            .map(e -> {
                                Map<String, Object> map = new HashMap<>();
                                map.put("type", e.getType());
                                map.put("reason", e.getReason());
                                map.put("message", e.getMessage());
                                map.put("count", e.getCount());
                                map.put("firstTimestamp", e.getFirstTimestamp());
                                map.put("lastTimestamp", e.getLastTimestamp());
                                return map;
                            })
                            .toList();
                    result.put("events", eventList);
                }
            } catch (Exception e) {
                log.warn("Failed to get events for pod {}: {}", podName, e.getMessage());
            }

            return result;
        } catch (ApiException e) {
            log.error("Failed to get pod detail: {}", e.getMessage());
            throw new RuntimeException("Failed to get pod detail: " + e.getMessage());
        }
    }

    public String getPodYaml(Long clusterId, String namespace, String podName) {
        ApiClient client = connectionService.getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1Pod pod = api.readNamespacedPod(podName, namespace).execute();
            return io.kubernetes.client.util.Yaml.dump(pod);
        } catch (ApiException e) {
            log.error("Failed to get pod YAML: {}", e.getMessage());
            throw new RuntimeException("Failed to get pod YAML: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to serialize pod to YAML: {}", e.getMessage());
            throw new RuntimeException("Failed to serialize pod to YAML: " + e.getMessage());
        }
    }

    public String getPodLogs(Long clusterId, String namespace, String podName, String containerName, Integer tailLines) {
        ApiClient client = connectionService.getApiClient(clusterId);
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

    public List<Map<String, Object>> getClusterImages(Long clusterId) {
        ApiClient client = connectionService.getApiClient(clusterId);
        CoreV1Api api = new CoreV1Api(client);

        try {
            V1NodeList nodes = api.listNode().execute();
            Set<String> imageSet = new TreeSet<>();

            for (V1Node node : nodes.getItems()) {
                if (node.getStatus() != null && node.getStatus().getImages() != null) {
                    for (V1ContainerImage image : node.getStatus().getImages()) {
                        if (image.getNames() != null) {
                            image.getNames().stream()
                                    .filter(n -> !n.startsWith("sha256:"))
                                    .forEach(imageSet::add);
                        }
                    }
                }
            }

            return imageSet.stream()
                    .map(img -> Map.<String, Object>of("name", img))
                    .toList();
        } catch (ApiException e) {
            log.error("Failed to get cluster images: {}", e.getMessage());
            throw new RuntimeException("Failed to get cluster images: " + e.getMessage());
        }
    }

    private Map<String, Object> toContainerInfo(V1Container c) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", c.getName());
        info.put("image", c.getImage());
        info.put("imagePullPolicy", c.getImagePullPolicy());

        if (c.getPorts() != null) {
            info.put("ports", c.getPorts().stream()
                    .map(p -> {
                        Map<String, Object> portMap = new HashMap<>();
                        portMap.put("containerPort", p.getContainerPort());
                        portMap.put("protocol", p.getProtocol());
                        portMap.put("name", p.getName());
                        return portMap;
                    })
                    .toList());
        }
        if (c.getEnv() != null) {
            info.put("env", c.getEnv().stream()
                    .map(e -> {
                        Map<String, String> envMap = new HashMap<>();
                        envMap.put("name", e.getName());
                        envMap.put("value", e.getValue());
                        return envMap;
                    })
                    .toList());
        }
        if (c.getResources() != null) {
            Map<String, Object> resources = new HashMap<>();
            if (c.getResources().getRequests() != null) resources.put("requests", c.getResources().getRequests());
            if (c.getResources().getLimits() != null) resources.put("limits", c.getResources().getLimits());
            info.put("resources", resources);
        }
        return info;
    }

    private Map<String, Object> toContainerStatusInfo(V1ContainerStatus s) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", s.getName());
        info.put("ready", s.getReady());
        info.put("restartCount", s.getRestartCount());
        info.put("image", s.getImage());

        if (s.getState() != null) {
            Map<String, Object> state = new HashMap<>();
            if (s.getState().getRunning() != null) state.put("running", s.getState().getRunning().getStartedAt());
            if (s.getState().getWaiting() != null) {
                Map<String, Object> waiting = new HashMap<>();
                waiting.put("reason", s.getState().getWaiting().getReason());
                waiting.put("message", s.getState().getWaiting().getMessage());
                state.put("waiting", waiting);
            }
            if (s.getState().getTerminated() != null) {
                Map<String, Object> terminated = new HashMap<>();
                terminated.put("exitCode", s.getState().getTerminated().getExitCode());
                terminated.put("reason", s.getState().getTerminated().getReason());
                terminated.put("finishedAt", s.getState().getTerminated().getFinishedAt());
                terminated.put("startedAt", s.getState().getTerminated().getStartedAt());
                state.put("terminated", terminated);
            }
            info.put("state", state);
        }
        return info;
    }
}
