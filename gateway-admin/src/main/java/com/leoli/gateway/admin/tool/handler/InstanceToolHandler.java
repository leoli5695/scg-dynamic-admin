package com.leoli.gateway.admin.tool.handler;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getBoolArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;

/**
 * Gateway-instance tools: list instances, read one instance's detail, and list an instance's pods.
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
public class InstanceToolHandler {

    private final GatewayInstanceService gatewayInstanceService;

    public Object listInstances(Map<String, Object> args) {
        boolean enabledOnly = getBoolArg(args, "enabledOnly", false);

        // getAllInstances() 返回的是实体列表（不是 Map）。此前用 `instanceof Map` 判断恒为 false，
        // 导致精简逻辑从未生效、直接序列化裸实体：既因 LocalDateTime 让裸 ObjectMapper 序列化失败
        // ("JSON serialization failed")，又可能把 kubeConfig 等敏感字段泄进模型上下文。这里显式按
        // 实体字段映射成精简 Map。
        List<GatewayInstanceEntity> instances = gatewayInstanceService.getAllInstances();

        return instances.stream()
                .filter(i -> !enabledOnly || (i.getStatusCode() != null && i.getStatusCode() == 1))  // 1=RUNNING
                .map(i -> {
                    Map<String, Object> simplified = new LinkedHashMap<>();
                    simplified.put("instanceId", i.getInstanceId());
                    simplified.put("instanceName", i.getInstanceName());
                    simplified.put("status", i.getStatus());
                    simplified.put("statusCode", i.getStatusCode());
                    simplified.put("statusMessage", i.getStatusMessage());
                    simplified.put("specType", i.getSpecType());
                    simplified.put("replicas", i.getReplicas());
                    simplified.put("effectiveAccessUrl", i.getEffectiveAccessUrl());
                    return simplified;
                })
                .toList();
    }

    public Object getInstanceDetail(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        return gatewayInstanceService.getInstanceByInstanceId(instanceId);
    }

    public Object getInstancePods(Map<String, Object> args) {
        String instanceIdStr = getRequiredStringArg(args, "instanceId");
        // instanceId 可能是数据库ID（Long）或实例ID（String）
        try {
            Long id = Long.parseLong(instanceIdStr);
            return gatewayInstanceService.getInstancePods(id);
        } catch (NumberFormatException e) {
            // 尝试作为实例ID查询
            var instance = gatewayInstanceService.getInstanceByInstanceId(instanceIdStr);
            if (instance != null) {
                return gatewayInstanceService.getInstancePods(instance.getId());
            }
            return Map.of("error", "Instance not found: " + instanceIdStr);
        }
    }
}
