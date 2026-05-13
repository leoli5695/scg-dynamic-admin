package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.client.PrometheusClient;
import com.leoli.gateway.admin.model.ServiceMiddlewareEntity;
import com.leoli.gateway.admin.repository.ServiceMiddlewareRepository;
import com.leoli.gateway.admin.service.ServiceMiddlewareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 服务中间件查询 Controller（前端页面专用）
 *
 * 提供 ServiceMiddlewarePage 所需的 API
 * 直接从 service_middleware 表查询，按服务名分组
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/service-middleware")
@RequiredArgsConstructor
public class ServiceMiddlewareViewController {

    private final ServiceMiddlewareService middlewareService;
    private final ServiceMiddlewareRepository middlewareRepository;
    private final PrometheusClient prometheusClient;

    /**
     * 获取所有服务及其中间件信息
     *
     * 直接从 service_middleware 表查询，按 service_name 分组
     */
    @GetMapping("/services")
    public ResponseEntity<Map<String, Object>> getAllServicesWithMiddlewares() {

        try {
            // 1. 直接从 service_middleware 表查询所有服务名（去重）
            List<String> serviceNames = middlewareRepository.findAllServiceNames();

            if (serviceNames.isEmpty()) {
                log.info("No services found in service_middleware table");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("code", 200);
                result.put("data", Collections.emptyList());
                result.put("message", "暂无服务上报中间件信息");
                result.put("totalServices", 0);
                return ResponseEntity.ok(result);
            }

            log.debug("Found {} services in service_middleware table", serviceNames.size());

            // 2. 批量查询 Prometheus 中所有 exporter 的 up 状态（一次查询优化性能）
            Map<String, Integer> exporterUpStatus = prometheusClient.queryAllExporterUpStatus();
            log.debug("Exporter up status from Prometheus: {}", exporterUpStatus);

            // 3. 查询每个服务的中间件信息（按服务名分组）
            List<Map<String, Object>> services = new ArrayList<>();
            for (String serviceName : serviceNames) {
                List<ServiceMiddlewareEntity> middlewares = middlewareRepository.findByServiceName(serviceName);

                if (middlewares.isEmpty()) {
                    continue;  // 不可能，因为是从 serviceNames 列表来的
                }

                Map<String, Object> serviceInfo = new LinkedHashMap<>();
                serviceInfo.put("serviceName", serviceName);
                serviceInfo.put("reportTime", middlewares.get(0).getLastReportTime());
                serviceInfo.put("middlewareCount", middlewares.size());

                // 构建中间件列表
                List<Map<String, Object>> mwList = new ArrayList<>();
                for (ServiceMiddlewareEntity mw : middlewares) {
                    Map<String, Object> mwInfo = new LinkedHashMap<>();
                    mwInfo.put("type", mw.getMiddlewareType());
                    mwInfo.put("host", mw.getMiddlewareHost());
                    mwInfo.put("port", mw.getMiddlewarePort());
                    mwInfo.put("exporterUrl", mw.getExporterUrl());

                    // 从 Prometheus 查询真实状态（优先）
                    String realStatus = evaluateMiddlewareRealStatus(mw.getExporterUrl(), exporterUpStatus);
                    // 如果 Prometheus 查询失败，回退到上报时间判断
                    if (realStatus.equals("unknown")) {
                        realStatus = evaluateMiddlewareStatus(mw.getLastReportTime());
                    }
                    mwInfo.put("status", realStatus);
                    mwInfo.put("lastReportTime", mw.getLastReportTime());
                    mwList.add(mwInfo);
                }
                serviceInfo.put("middlewares", mwList);

                // 计算健康状态
                serviceInfo.put("status", calculateServiceHealthStatus(mwList));

                services.add(serviceInfo);
            }

            // 4. 按上报时间倒序排列（最近上报的在前）
            services.sort((a, b) -> {
                LocalDateTime timeA = (LocalDateTime) a.get("reportTime");
                LocalDateTime timeB = (LocalDateTime) b.get("reportTime");
                if (timeA == null && timeB == null) return 0;
                if (timeA == null) return 1;
                if (timeB == null) return -1;
                return timeB.compareTo(timeA);
            });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("data", services);
            result.put("message", "success");
            result.put("totalServices", services.size());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to get services middleware: {}", e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("code", 500);
            errorResult.put("data", Collections.emptyList());
            errorResult.put("message", e.getMessage());
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 从 Prometheus 查询真实的 Exporter 状态
     *
     * @param exporterUrl Exporter地址
     * @param exporterUpStatus 已查询的 up 状态缓存
     * @return "UP", "down", "unknown"
     */
    private String evaluateMiddlewareRealStatus(String exporterUrl, Map<String, Integer> exporterUpStatus) {
        if (exporterUrl == null || exporterUrl.isEmpty()) {
            return "unknown";
        }

        // 从缓存中查找状态（精确匹配）
        Integer upStatus = exporterUpStatus.get(exporterUrl);

        if (upStatus == null) {
            // 尝试按端口匹配（localhost:9121 → redis-exporter:9121）
            String port = extractPort(exporterUrl);
            if (port != null) {
                for (Map.Entry<String, Integer> entry : exporterUpStatus.entrySet()) {
                    String instancePort = extractPort(entry.getKey());
                    if (port.equals(instancePort)) {
                        // 端口匹配，使用该状态
                        upStatus = entry.getValue();
                        log.debug("Port match: {} → {} (status={})", exporterUrl, entry.getKey(), upStatus);
                        break;
                    }
                }
            }

            // 仍没找到，尝试字符串包含匹配
            if (upStatus == null) {
                for (Map.Entry<String, Integer> entry : exporterUpStatus.entrySet()) {
                    if (entry.getKey().contains(exporterUrl) || exporterUrl.contains(entry.getKey())) {
                        upStatus = entry.getValue();
                        break;
                    }
                }
            }
        }

        if (upStatus != null) {
            return upStatus == 1 ? "UP" : "down";
        }

        // Prometheus 未找到该 exporter
        return "unknown";
    }

    /**
     * 从地址中提取端口部分
     * 例如: "localhost:9121" → "9121", "redis-exporter:9121" → "9121"
     */
    private String extractPort(String address) {
        if (address == null) return null;
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon < address.length() - 1) {
            return address.substring(lastColon + 1);
        }
        return null;
    }

    /**
     * 评估中间件状态（根据最后上报时间 - 回退方案）
     */
    private String evaluateMiddlewareStatus(LocalDateTime lastReportTime) {
        if (lastReportTime == null) {
            return "unknown";
        }

        long minutesSinceReport = ChronoUnit.MINUTES.between(lastReportTime, LocalDateTime.now());

        if (minutesSinceReport < 5) {
            return "UP";
        } else if (minutesSinceReport < 30) {
            return "stale";
        } else {
            return "down";
        }
    }

    /**
     * 计算服务整体健康状态
     */
    private String calculateServiceHealthStatus(List<Map<String, Object>> middlewares) {
        if (middlewares.isEmpty()) {
            return "no_data";
        }

        int upCount = 0;
        int staleCount = 0;
        int downCount = 0;

        for (Map<String, Object> mw : middlewares) {
            String status = (String) mw.get("status");
            if ("UP".equals(status)) {
                upCount++;
            } else if ("stale".equals(status)) {
                staleCount++;
            } else if ("down".equals(status) || "unknown".equals(status)) {
                downCount++;
            }
        }

        int total = middlewares.size();
        if (upCount == total) {
            return "healthy";
        } else if (downCount > 0) {
            return "critical";
        } else if (staleCount > total / 2) {
            return "warning";
        } else {
            return "degraded";
        }
    }

    /**
     * 获取指定服务的中间件指标（所有实例合并，兼容旧 API）
     *
     * 从 Prometheus 查询实时指标
     */
    @GetMapping("/{serviceName}/metrics")
    public ResponseEntity<Map<String, Object>> getServiceMiddlewareMetrics(
            @PathVariable String serviceName) {
        // 默认取第一个实例（兼容旧 API）
        List<String> instances = middlewareRepository.findAllInstanceAddressesByServiceName(serviceName);
        if (instances.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("data", Collections.emptyList());
            result.put("message", "服务暂无中间件信息");
            return ResponseEntity.ok(result);
        }
        // 取最近上报的实例
        return getServiceInstanceMiddlewareMetrics(serviceName, instances.get(0));
    }

    /**
     * 获取指定服务实例的中间件指标（按实例隔离）
     *
     * 从 Prometheus 查询实时指标
     *
     * @param serviceName 服务名称
     * @param instanceAddress 实例地址（IP:port）
     */
    @GetMapping("/{serviceName}/instances/{instanceAddress}/metrics")
    public ResponseEntity<Map<String, Object>> getServiceInstanceMiddlewareMetrics(
            @PathVariable String serviceName,
            @PathVariable String instanceAddress) {

        try {
            // 按服务实例查询中间件列表
            List<ServiceMiddlewareEntity> middlewares = middlewareRepository
                .findByServiceNameAndInstanceAddress(serviceName, instanceAddress);

            log.info("Found {} middlewares for service {} instance {}", middlewares.size(), serviceName, instanceAddress);

            if (middlewares.isEmpty()) {
                log.warn("No middleware found for service: {} instance: {}", serviceName, instanceAddress);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("code", 200);
                result.put("data", Collections.emptyList());
                result.put("message", "服务实例暂无中间件信息");
                result.put("serviceName", serviceName);
                result.put("instanceAddress", instanceAddress);
                return ResponseEntity.ok(result);
            }

            // 构建 Exporter 映射（该实例专属）
            Map<String, String> exporterMapping = new LinkedHashMap<>();
            for (ServiceMiddlewareEntity mw : middlewares) {
                log.debug("Middleware: type={}, exporterUrl={}", mw.getMiddlewareType(), mw.getExporterUrl());
                if (mw.getExporterUrl() != null && !mw.getExporterUrl().isEmpty()) {
                    exporterMapping.put(mw.getMiddlewareType(), mw.getExporterUrl());
                }
            }

            log.info("Exporter mapping for {} ({}): {}", serviceName, instanceAddress, exporterMapping);

            List<Map<String, Object>> metrics = new ArrayList<>();

            for (Map.Entry<String, String> entry : exporterMapping.entrySet()) {
                String type = entry.getKey();
                String exporterUrl = entry.getValue();

                try {
                    // 根据中间件类型查询指标
                    Map<String, Object> typeMetrics = queryMiddlewareMetricsByType(type, exporterUrl);

                    // 转换为前端需要的格式
                    for (Map.Entry<String, Object> metric : typeMetrics.entrySet()) {
                        Map<String, Object> metricInfo = new LinkedHashMap<>();
                        metricInfo.put("name", metric.getKey());
                        metricInfo.put("value", extractNumericValue(metric.getValue()));
                        metricInfo.put("unit", getUnitForMetric(metric.getKey()));
                        metricInfo.put("status", evaluateMetricStatus(metric.getKey(), metric.getValue()));
                        metrics.add(metricInfo);
                    }

                } catch (Exception e) {
                    log.warn("Failed to query {} metrics from {}: {}", type, exporterUrl, e.getMessage());
                    // 添加失败状态的指标
                    Map<String, Object> errorMetric = new LinkedHashMap<>();
                    errorMetric.put("name", type + "_status");
                    errorMetric.put("value", 0);
                    errorMetric.put("unit", "");
                    errorMetric.put("status", "critical");
                    errorMetric.put("error", e.getMessage());
                    metrics.add(errorMetric);
                }
            }

            log.info("Collected {} metrics for service {} instance {}", metrics.size(), serviceName, instanceAddress);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("data", metrics);
            result.put("message", "success");
            result.put("serviceName", serviceName);
            result.put("instanceAddress", instanceAddress);
            result.put("middlewareCount", middlewares.size());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to get middleware metrics for {} instance {}: {}", serviceName, instanceAddress, e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("code", 500);
            errorResult.put("data", Collections.emptyList());
            errorResult.put("message", e.getMessage());
            errorResult.put("serviceName", serviceName);
            errorResult.put("instanceAddress", instanceAddress);
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 获取服务的所有实例列表
     *
     * 用于前端选择具体实例查看中间件指标
     */
    @GetMapping("/{serviceName}/instances")
    public ResponseEntity<Map<String, Object>> getServiceInstances(
            @PathVariable String serviceName) {
        try {
            List<String> instances = middlewareRepository.findAllInstanceAddressesByServiceName(serviceName);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("serviceName", serviceName);
            result.put("instances", instances);
            result.put("instanceCount", instances.size());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to get instances for {}: {}", serviceName, e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("code", 500);
            errorResult.put("message", e.getMessage());
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 根据中间件类型查询指标
     *
     * 注意：PostgreSQL、MongoDB、RabbitMQ 目前只有时间段查询方法，
     * 即时查询返回占位数据，完整指标需通过压测关联分析获取
     */
    private Map<String, Object> queryMiddlewareMetricsByType(String type, String exporterUrl) {
        return switch (type.toLowerCase()) {
            case "redis" -> prometheusClient.queryRedisMetrics(exporterUrl);
            case "mysql", "mariadb" -> prometheusClient.queryMysqlMetrics(exporterUrl);
            case "rocketmq" -> prometheusClient.queryRocketmqMetrics(exporterUrl, null);
            case "elasticsearch" -> prometheusClient.queryEsMetrics(exporterUrl);
            // PostgreSQL、MongoDB、RabbitMQ 即时查询暂不支持，返回基础状态
            case "postgresql" -> Map.of(
                "status", "connected",
                "hint", "Use query_postgresql_during_period for detailed metrics during stress test"
            );
            case "mongodb" -> Map.of(
                "status", "connected",
                "hint", "Use query_mongodb_during_period for detailed metrics during stress test"
            );
            case "rabbitmq" -> Map.of(
                "status", "connected",
                "hint", "Use query_rabbitmq_during_period for detailed metrics during stress test"
            );
            default -> Map.of("status", "unsupported", "type", type);
        };
    }

    /**
     * 提取数值
     */
    private double extractNumericValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 获取指标单位
     */
    private String getUnitForMetric(String metricName) {
        if (metricName.contains("Latency") || metricName.contains("ResponseTime")) {
            return "ms";
        }
        if (metricName.contains("Bytes") || metricName.contains("Memory")) {
            return "bytes";
        }
        if (metricName.contains("Percent") || metricName.contains("Rate") || metricName.contains("Ratio")) {
            return "%";
        }
        if (metricName.contains("PerSec") || metricName.contains("Tps") || metricName.contains("Ops")) {
            return "/s";
        }
        return "";
    }

    /**
     * 评估指标状态
     */
    private String evaluateMetricStatus(String metricName, Object value) {
        double numericValue = extractNumericValue(value);

        // 简单的健康判断逻辑
        if (metricName.contains("Latency") || metricName.contains("ResponseTime")) {
            if (numericValue > 100) return "critical";
            if (numericValue > 50) return "warning";
            return "healthy";
        }
        if (metricName.contains("Error") || metricName.contains("Failed")) {
            if (numericValue > 5) return "critical";
            if (numericValue > 1) return "warning";
            return "healthy";
        }
        if (metricName.contains("Lag") || metricName.contains("Queue")) {
            if (numericValue > 1000) return "critical";
            if (numericValue > 100) return "warning";
            return "healthy";
        }
        if (metricName.contains("Connection")) {
            if (numericValue > 80) return "warning";
            return "healthy";
        }

        return "healthy";
    }
}