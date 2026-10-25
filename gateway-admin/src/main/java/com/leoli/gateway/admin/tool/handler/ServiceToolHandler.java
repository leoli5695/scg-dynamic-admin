package com.leoli.gateway.admin.tool.handler;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.leoli.gateway.admin.center.NacosConfigCenterService;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.ServiceService;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;

/**
 * Service-registry tools: list services, read one service's detail, and query Nacos service discovery.
 *
 * <p>Extracted from {@code ToolExecutor} as part of breaking up a 4241-line / 125-method / 27-dependency
 * god class. Moved mechanically: only the dispatch entry points changed signature
 * ({@code private Object executeX} became {@code public Object x}); helpers are verbatim. Behaviour is
 * unchanged, and {@code ToolExecutorTest} still exercises these tools end to end because it builds this
 * handler from the same mocks and passes it to the dispatcher.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceToolHandler {

    private final ServiceService serviceService;
    private final NacosConfigCenterService nacosConfigCenterService;

    public Object listServices(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");

        List<?> services;
        if (instanceId != null) {
            services = serviceService.getAllServicesByInstanceId(instanceId);
        } else {
            services = serviceService.getAllServices();
        }

        // 简化输出
        return services.stream()
                .map(s -> {
                    if (s instanceof Map) {
                        Map<?, ?> service = (Map<?, ?>) s;
                        Map<String, Object> simplified = new LinkedHashMap<>();
                        simplified.put("serviceId", service.get("serviceId"));
                        simplified.put("name", service.get("name"));
                        simplified.put("loadBalancer", service.get("loadBalancer"));
                        simplified.put("instanceCount", service.get("instances") != null ?
                                ((List<?>) service.get("instances")).size() : 0);
                        return simplified;
                    }
                    return s;
                })
                .toList();
    }

    public Object getServiceDetail(Map<String, Object> args) {
        String serviceName = getRequiredStringArg(args, "serviceName");
        return serviceService.getServiceByName(serviceName);
    }

    /**
     * Nacos 服务发现查询（最高优先级工具）.
     * 当路由 URI 以 lb:// 开头时，AI 应优先调用此工具查询真实实例信息。
     */
    public Object nacosServiceDiscovery(Map<String, Object> args) {
        String serviceName = getRequiredStringArg(args, "serviceName");
        String namespace = getStringArg(args, "namespace");
        String group = getStringArg(args, "group", "DEFAULT_GROUP");

        try {
            // 获取指定 namespace 的 NamingService
            var namingService = nacosConfigCenterService.getNamingServiceForNamespace(namespace);

            // 查询所有实例（包含健康和不健康的）
            List<Instance> allInstances = namingService.getAllInstances(serviceName, group);

            if (allInstances == null || allInstances.isEmpty()) {
                return Map.of(
                        "serviceName", serviceName,
                        "namespace", namespace != null ? namespace : "public",
                        "group", group,
                        "found", false,
                        "totalInstances", 0,
                        "healthyInstances", 0,
                        "message", "在 Nacos 中未找到该服务的注册实例。可能原因：1) 服务未启动或未注册到 Nacos；2) namespace/group 配置错误；3) 服务名拼写错误。"
                );
            }

            // 分离健康和不健康实例
            List<Map<String, Object>> healthyList = new ArrayList<>();
            List<Map<String, Object>> unhealthyList = new ArrayList<>();

            for (Instance instance : allInstances) {
                Map<String, Object> instanceInfo = new LinkedHashMap<>();
                instanceInfo.put("ip", instance.getIp());
                instanceInfo.put("port", instance.getPort());
                instanceInfo.put("weight", instance.getWeight());
                instanceInfo.put("healthy", instance.isHealthy());
                instanceInfo.put("enabled", instance.isEnabled());
                instanceInfo.put("serviceName", instance.getServiceName());
                instanceInfo.put("metadata", instance.getMetadata());
                instanceInfo.put("ephemeral", instance.isEphemeral());

                if (instance.isHealthy() && instance.isEnabled()) {
                    healthyList.add(instanceInfo);
                } else {
                    unhealthyList.add(instanceInfo);
                }
            }

            // 构建返回结果
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("serviceName", serviceName);
            result.put("namespace", namespace != null && !namespace.isEmpty() ? namespace : "public");
            result.put("group", group);
            result.put("found", true);
            result.put("totalInstances", allInstances.size());
            result.put("healthyInstances", healthyList.size());
            result.put("unhealthyInstances", unhealthyList.size());

            // 构建实例地址列表（IP:端口格式）
            List<String> healthyAddresses = healthyList.stream()
                    .map(i -> i.get("ip") + ":" + i.get("port"))
                    .toList();
            result.put("healthyAddresses", healthyAddresses);

            // 详细实例列表
            result.put("healthyInstancesDetail", healthyList);
            if (!unhealthyList.isEmpty()) {
                result.put("unhealthyInstancesDetail", unhealthyList);
                result.put("warning", "存在不健康实例，请检查后端服务状态");
            }

            // 服务发现来源说明（用于 AI 报告）
            result.put("discoverySource", "Nacos 服务发现（LoadBalancer 动态发现模式）");
            result.put("discoveryMode", "lb://" + serviceName);

            return result;

        } catch (Exception e) {
            log.error("Failed to query Nacos service discovery for: {}", serviceName, e);
            return Map.of(
                    "serviceName", serviceName,
                    "namespace", namespace != null ? namespace : "public",
                    "group", group,
                    "found", false,
                    "error", "Nacos 服务发现查询失败: " + e.getMessage(),
                    "message", "无法连接到 Nacos 或查询失败。请检查 Nacos 服务状态和网络连接。"
            );
        }
    }
}
