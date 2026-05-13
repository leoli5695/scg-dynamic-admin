package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.ServiceMiddlewareEntity;
import com.leoli.gateway.admin.repository.ServiceMiddlewareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Prometheus HTTP Service Discovery Controller
 *
 * 提供 Prometheus http_sd_configs 所需的服务发现 API
 * Prometheus 定期调用此 API 获取动态发现 targets
 *
 * 按服务实例（IP:port）隔离，每个实例只能看到自己依赖的中间件
 *
 * Prometheus 配置示例:
 * <pre>
 * scrape_configs:
 *   - job_name: 'middleware-exporters'
 *     http_sd_configs:
 *       - url: 'http://gateway-admin:9090/api/prometheus/sd/exporters'
 *         refresh_interval: 30s
 * </pre>
 *
 * API 响应格式:
 * <pre>
 * [
 *   {
 *     "targets": ["redis-exporter:9121"],
 *     "labels": {
 *       "middleware_type": "redis",
 *       "services": "seckill-core-engine",
 *       "service_instances": "seckill-core-engine:192.168.1.100:8080"
 *     }
 *   }
 * ]
 * </pre>
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/prometheus/sd")
@RequiredArgsConstructor
public class PrometheusServiceDiscoveryController {

    private final ServiceMiddlewareRepository middlewareRepository;

    /**
     * Prometheus HTTP Service Discovery endpoint
     *
     * 返回所有已上报的 exporter targets，供 Prometheus 动态发现
     * 每个 exporter 关联到具体的服务实例
     *
     * @return Prometheus http_sd_configs 格式的 targets 列表
     */
    @GetMapping("/exporters")
    public ResponseEntity<List<Map<String, Object>>> getExporterTargets() {
        try {
            // 1. 从数据库查询所有中间件信息
            List<ServiceMiddlewareEntity> allMiddlewares = middlewareRepository.findAll();

            if (allMiddlewares.isEmpty()) {
                log.debug("No middleware exporters found in database");
                return ResponseEntity.ok(Collections.emptyList());
            }

            // 2. 按 exporterUrl 去重，同时记录关联的服务实例
            // 同一个 exporter 可能被多个服务实例使用（共享中间件场景）
            Map<String, List<ServiceMiddlewareEntity>> exportersByUrl = allMiddlewares.stream()
                    .filter(mw -> mw.getExporterUrl() != null && !mw.getExporterUrl().isEmpty())
                    .collect(Collectors.groupingBy(
                            ServiceMiddlewareEntity::getExporterUrl,
                            Collectors.toList()
                    ));

            log.debug("Found {} unique exporters from {} middleware records",
                    exportersByUrl.size(), allMiddlewares.size());

            // 3. 构建 Prometheus http_sd_configs 格式的响应
            List<Map<String, Object>> targets = new ArrayList<>();

            for (Map.Entry<String, List<ServiceMiddlewareEntity>> entry : exportersByUrl.entrySet()) {
                String exporterUrl = entry.getKey();
                List<ServiceMiddlewareEntity> middlewares = entry.getValue();

                // 取第一个中间件信息作为基础 labels
                ServiceMiddlewareEntity first = middlewares.get(0);

                Map<String, Object> targetGroup = new LinkedHashMap<>();
                targetGroup.put("targets", Collections.singletonList(exporterUrl));

                // 构建 labels - 包含中间件基本信息
                Map<String, String> labels = new LinkedHashMap<>();
                labels.put("middleware_type", first.getMiddlewareType());
                labels.put("middleware_host", first.getMiddlewareHost());
                labels.put("middleware_port", String.valueOf(first.getMiddlewarePort()));

                // 关联的服务实例列表（格式：service_name:instance_address）
                // 例如：seckill-core-engine:192.168.1.100:8080,user-service:192.168.1.101:8080
                String serviceInstances = middlewares.stream()
                        .map(mw -> mw.getServiceName() + ":" + mw.getInstanceAddress())
                        .distinct()
                        .collect(Collectors.joining(","));
                labels.put("service_instances", serviceInstances);

                // 服务名称列表（去重）
                String services = middlewares.stream()
                        .map(ServiceMiddlewareEntity::getServiceName)
                        .distinct()
                        .collect(Collectors.joining(","));
                labels.put("services", services);

                targetGroup.put("labels", labels);

                targets.add(targetGroup);

                log.debug("Exporter: {} -> type={}, service_instances={}",
                        exporterUrl, first.getMiddlewareType(), serviceInstances);
            }

            log.info("Prometheus SD: returning {} exporter targets", targets.size());
            return ResponseEntity.ok(targets);

        } catch (Exception e) {
            log.error("Failed to get exporter targets for Prometheus SD: {}", e.getMessage(), e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * 按服务实例查询 exporter targets（实例专属）
     *
     * 用于精确查询某个服务实例依赖的中间件 exporters
     *
     * @param serviceName 服务名称
     * @param instanceAddress 实例地址（IP:port）
     * @return 该服务实例的 exporter targets
     */
    @GetMapping("/exporters/{serviceName}/{instanceAddress}")
    public ResponseEntity<List<Map<String, Object>>> getExporterTargetsByInstance(
            @PathVariable String serviceName,
            @PathVariable String instanceAddress) {
        try {
            // 查询指定服务实例的中间件
            List<ServiceMiddlewareEntity> middlewares = middlewareRepository
                    .findByServiceNameAndInstanceAddress(serviceName, instanceAddress);

            if (middlewares.isEmpty()) {
                log.debug("No middleware exporters found for {} instance {}", serviceName, instanceAddress);
                return ResponseEntity.ok(Collections.emptyList());
            }

            List<Map<String, Object>> targets = new ArrayList<>();

            for (ServiceMiddlewareEntity mw : middlewares) {
                if (mw.getExporterUrl() == null || mw.getExporterUrl().isEmpty()) {
                    continue;
                }

                Map<String, Object> targetGroup = new LinkedHashMap<>();
                targetGroup.put("targets", Collections.singletonList(mw.getExporterUrl()));

                Map<String, String> labels = new LinkedHashMap<>();
                labels.put("middleware_type", mw.getMiddlewareType());
                labels.put("middleware_host", mw.getMiddlewareHost());
                labels.put("middleware_port", String.valueOf(mw.getMiddlewarePort()));
                labels.put("service", serviceName);
                labels.put("instance_address", instanceAddress);

                targetGroup.put("labels", labels);
                targets.add(targetGroup);
            }

            log.info("Prometheus SD by instance: returning {} exporters for {} instance {}",
                    targets.size(), serviceName, instanceAddress);
            return ResponseEntity.ok(targets);

        } catch (Exception e) {
            log.error("Failed to get exporter targets for {} instance {}: {}",
                    serviceName, instanceAddress, e.getMessage(), e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * 按中间件类型分组的服务发现 endpoint
     *
     * 可选：为每种中间件类型生成独立的 job
     *
     * @return 按类型分组的 targets
     */
    @GetMapping("/exporters/by-type")
    public ResponseEntity<Map<String, List<Map<String, Object>>>> getExportersByType() {
        try {
            List<ServiceMiddlewareEntity> allMiddlewares = middlewareRepository.findAll();

            if (allMiddlewares.isEmpty()) {
                return ResponseEntity.ok(Collections.emptyMap());
            }

            // 按中间件类型分组
            Map<String, List<ServiceMiddlewareEntity>> byType = allMiddlewares.stream()
                    .filter(mw -> mw.getExporterUrl() != null && !mw.getExporterUrl().isEmpty())
                    .collect(Collectors.groupingBy(
                            mw -> mw.getMiddlewareType().toLowerCase(),
                            Collectors.toList()
                    ));

            Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();

            for (Map.Entry<String, List<ServiceMiddlewareEntity>> typeEntry : byType.entrySet()) {
                String type = typeEntry.getKey();
                List<ServiceMiddlewareEntity> middlewares = typeEntry.getValue();

                // 按 exporterUrl 去重
                Map<String, ServiceMiddlewareEntity> uniqueExporters = middlewares.stream()
                        .collect(Collectors.toMap(
                                ServiceMiddlewareEntity::getExporterUrl,
                                mw -> mw,
                                (a, b) -> a  // 保留第一个
                        ));

                List<Map<String, Object>> targetsForType = new ArrayList<>();
                for (Map.Entry<String, ServiceMiddlewareEntity> exporterEntry : uniqueExporters.entrySet()) {
                    ServiceMiddlewareEntity mw = exporterEntry.getValue();

                    Map<String, Object> target = new LinkedHashMap<>();
                    target.put("targets", Collections.singletonList(exporterEntry.getKey()));
                    target.put("labels", Map.of(
                            "middleware_type", type,
                            "services", middlewares.stream()
                                    .map(ServiceMiddlewareEntity::getServiceName)
                                    .distinct()
                                    .collect(Collectors.joining(","))
                    ));
                    targetsForType.add(target);
                }

                result.put(type, targetsForType);
            }

            log.info("Prometheus SD by-type: returning {} types", result.size());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to get exporters by type: {}", e.getMessage(), e);
            return ResponseEntity.ok(Collections.emptyMap());
        }
    }
}