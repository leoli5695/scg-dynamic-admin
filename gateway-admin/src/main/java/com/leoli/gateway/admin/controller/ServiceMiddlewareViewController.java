package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.client.PrometheusClient;
import com.leoli.gateway.admin.client.RocketMQConsoleClient;
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

/**
 * 服务中间件查询 Controller（前端页面专用）
 * <p>
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
    private final RocketMQConsoleClient rocketMQConsoleClient;

    /**
     * 获取所有服务及其中间件信息
     * <p>
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

                    // 根据中间件类型选择状态判断方式
                    String realStatus;
                    if ("rocketmq".equalsIgnoreCase(mw.getMiddlewareType())) {
                        // RocketMQ 使用 Console API 检查状态（不依赖 Prometheus exporter）
                        realStatus = rocketMQConsoleClient.isConsoleAvailable() ? "UP" : "down";
                    } else {
                        // 其他中间件从 Prometheus 查询真实状态（优先）
                        realStatus = evaluateMiddlewareRealStatus(mw.getExporterUrl(), exporterUpStatus);
                        // 如果 Prometheus 查询失败，回退到上报时间判断
                        if (realStatus.equals("unknown")) {
                            realStatus = evaluateMiddlewareStatus(mw.getLastReportTime());
                        }
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
     * @param exporterUrl      Exporter地址
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
     * <p>
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
     * <p>
     * 从 Prometheus 查询实时指标
     *
     * @param serviceName     服务名称
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
            Map<String, String> actualInstances = new LinkedHashMap<>(); // 记录每个中间件类型实际使用的 instance

            for (Map.Entry<String, String> entry : exporterMapping.entrySet()) {
                String type = entry.getKey();
                String exporterUrl = entry.getValue();

                try {
                    // 根据中间件类型查询指标
                    Map<String, Object> typeMetrics = queryMiddlewareMetricsByType(type, exporterUrl);

                    // 提取实际使用的 instance（用于调试和状态显示）
                    if (typeMetrics.containsKey("_actualInstance")) {
                        actualInstances.put(type, (String) typeMetrics.get("_actualInstance"));
                        typeMetrics.remove("_actualInstance"); // 不作为数值指标返回
                    }
                    // 移除其他元数据字段
                    typeMetrics.remove("_serviceName");
                    typeMetrics.remove("_exporterUrl");

                    // 转换为前端需要的格式
                    for (Map.Entry<String, Object> metric : typeMetrics.entrySet()) {
                        // 跳过带标签的重复 key（只保留原始指标名）
                        if (metric.getKey().contains("_redis-exporter") ||
                                metric.getKey().contains("_elasticsearch-exporter") ||
                                metric.getKey().contains("_mysql-exporter")) {
                            continue;
                        }

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
            result.put("actualInstances", actualInstances); // 返回实际使用的 instance 信息

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
     * <p>
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
     * <p>
     * 注意：
     * - RocketMQ 使用 Console API 获取指标（替代有 bug 的 exporter）
     * - PostgreSQL、MongoDB、RabbitMQ 目前只有时间段查询方法，
     *   即时查询返回占位数据，完整指标需通过压测关联分析获取
     */
    private Map<String, Object> queryMiddlewareMetricsByType(String type, String exporterUrl) {
        return switch (type.toLowerCase()) {
            case "redis" -> prometheusClient.queryRedisMetrics(exporterUrl);
            case "mysql", "mariadb" -> prometheusClient.queryMysqlMetrics(exporterUrl);
            // RocketMQ 使用 Console API（替代有 bug 的 exporter）
            case "rocketmq" -> rocketMQConsoleClient.queryMetricsForPrometheusFormat();
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
        // 特殊处理：hit_rate 是百分比
        if (metricName.equals("hit_rate") || metricName.equals("redis_hit_rate")) {
            return "%";
        }
        String lowerName = metricName.toLowerCase();
        if (lowerName.contains("latency") || lowerName.contains("response") || lowerName.contains("time") && !lowerName.contains("total")) {
            return "ms";
        }
        if (lowerName.contains("bytes") || lowerName.contains("memory") || lowerName.contains("size")) {
            return "bytes";
        }
        if (lowerName.contains("percent") || lowerName.contains("rate") || lowerName.contains("ratio")) {
            return "%";
        }
        if (lowerName.contains("persec") || lowerName.contains("tps") || lowerName.contains("ops")) {
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

    /**
     * 获取单个中间件的详细指标（专用详情页 API）
     * <p>
     * 包含即时指标 + 时间段趋势数据
     *
     * @param serviceName    服务名称
     * @param middlewareType 中间件类型 (redis, mysql, elasticsearch, rocketmq 等)
     * @param exporterUrl    Exporter URL (可选，从数据库查询)
     * @param start          开始时间（Unix秒，可选，默认最近1小时）
     * @param end            结束时间（Unix秒，可选，默认当前时间）
     */
    @GetMapping("/{serviceName}/middleware/{middlewareType}/detail")
    public ResponseEntity<Map<String, Object>> getMiddlewareDetail(
            @PathVariable String serviceName,
            @PathVariable String middlewareType,
            @RequestParam(required = false) String exporterUrl,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end) {

        try {
            // 1. 查找服务的中间件信息，获取 exporterUrl
            if (exporterUrl == null || exporterUrl.isEmpty()) {
                List<ServiceMiddlewareEntity> middlewares = middlewareRepository.findByServiceName(serviceName);
                for (ServiceMiddlewareEntity mw : middlewares) {
                    if (mw.getMiddlewareType().equalsIgnoreCase(middlewareType)) {
                        exporterUrl = mw.getExporterUrl();
                        break;
                    }
                }
            }

            if (exporterUrl == null || exporterUrl.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("code", 404);
                result.put("message", "未找到中间件配置: " + middlewareType);
                result.put("serviceName", serviceName);
                result.put("middlewareType", middlewareType);
                return ResponseEntity.ok(result);
            }

            // 2. 设置时间范围（默认最近1小时）
            long now = System.currentTimeMillis() / 1000;
            if (end == null) end = now;
            if (start == null) start = end - 3600; // 1小时前

            // 3. 根据中间件类型查询详细指标
            Map<String, Object> detailMetrics = queryMiddlewareDetailByType(middlewareType, exporterUrl, start, end);

            // 4. 构建响应
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("data", detailMetrics);
            result.put("message", "success");
            result.put("serviceName", serviceName);
            result.put("middlewareType", middlewareType);
            result.put("exporterUrl", exporterUrl);
            result.put("periodStart", start);
            result.put("periodEnd", end);
            result.put("periodDescription", formatTimePeriod(start, end));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to get middleware detail for {} {}: {}", serviceName, middlewareType, e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("code", 500);
            errorResult.put("message", e.getMessage());
            errorResult.put("serviceName", serviceName);
            errorResult.put("middlewareType", middlewareType);
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 根据中间件类型查询详细指标（带时间趋势）
     */
    private Map<String, Object> queryMiddlewareDetailByType(String type, String exporterUrl, long start, long end) {
        return switch (type.toLowerCase()) {
            case "redis" -> {
                Map<String, Object> result = new LinkedHashMap<>();

                // 即时指标
                Map<String, Object> instantMetrics = prometheusClient.queryRedisMetrics(exporterUrl);
                result.put("instant", transformMetricsForDisplay(instantMetrics));

                // 时间段趋势（1小时）
                Map<String, Object> trendMetrics = prometheusClient.queryRedisDuringPeriod(exporterUrl, start, end, null);
                result.put("trend", trendMetrics);

                // 计算健康状态
                result.put("healthStatus", evaluateRedisHealth(instantMetrics, trendMetrics));

                result.put("supportedMetrics", getRedisSupportedMetricsDescription());
                yield result;
            }
            case "mysql", "mariadb" -> {
                Map<String, Object> result = new LinkedHashMap<>();

                // 即时指标
                Map<String, Object> instantMetrics = prometheusClient.queryMySQLMetrics(exporterUrl);
                result.put("instant", transformMetricsForDisplay(instantMetrics));

                // 时间段趋势
                Map<String, Object> trendMetrics = prometheusClient.queryMysqlDuringPeriod(exporterUrl, start, end, null);
                result.put("trend", trendMetrics);

                // 健康状态
                result.put("healthStatus", evaluateMySQLHealth(instantMetrics, trendMetrics));

                result.put("supportedMetrics", getMySQLSupportedMetricsDescription());
                yield result;
            }
            case "elasticsearch" -> {
                Map<String, Object> result = new LinkedHashMap<>();

                // 即时指标
                Map<String, Object> instantMetrics = prometheusClient.queryEsMetrics(exporterUrl);
                result.put("instant", transformMetricsForDisplay(instantMetrics));

                // ES 目前没有专门的时间段查询方法，使用即时指标 + 基础趋势
                Map<String, Object> trendMetrics = new LinkedHashMap<>();
                trendMetrics.put("timeSeries", buildBasicESTrend(exporterUrl, start, end));
                result.put("trend", trendMetrics);

                // 健康状态
                result.put("healthStatus", evaluateESHealth(instantMetrics));

                result.put("supportedMetrics", getESSupportedMetricsDescription());
                yield result;
            }
            case "rocketmq" -> {
                Map<String, Object> result = new LinkedHashMap<>();

                // 即时指标 - 使用 Console API（替代有 bug 的 exporter）
                Map<String, Object> instantMetrics = rocketMQConsoleClient.queryClusterOverview();
                result.put("instant", transformMetricsForDisplay(instantMetrics));

                // 时间段趋势 - Console API 不提供历史数据，使用即时数据构建基础趋势
                Map<String, Object> trendMetrics = new LinkedHashMap<>();
                trendMetrics.put("note", "Console API provides real-time data only, no historical trends");
                trendMetrics.put("consumerGroups", rocketMQConsoleClient.queryAllConsumerGroupLags());
                result.put("trend", trendMetrics);

                // 健康状态
                result.put("healthStatus", evaluateRocketMQHealth(instantMetrics, trendMetrics));

                result.put("supportedMetrics", getRocketMQSupportedMetricsDescription());
                yield result;
            }
            case "postgresql" -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("instant", Map.of("status", "connected", "hint", "PostgreSQL exporter connected"));

                // 时间段趋势
                Map<String, Object> trendMetrics = prometheusClient.queryPostgresqlDuringPeriod(exporterUrl, start, end, null);
                result.put("trend", trendMetrics);

                result.put("supportedMetrics", getPostgreSQLSupportedMetricsDescription());
                yield result;
            }
            case "mongodb" -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("instant", Map.of("status", "connected", "hint", "MongoDB exporter connected"));

                // 时间段趋势
                Map<String, Object> trendMetrics = prometheusClient.queryMongodbDuringPeriod(exporterUrl, start, end, null);
                result.put("trend", trendMetrics);

                result.put("supportedMetrics", getMongoDBSupportedMetricsDescription());
                yield result;
            }
            case "rabbitmq" -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("instant", Map.of("status", "connected", "hint", "RabbitMQ exporter connected"));

                // 时间段趋势
                Map<String, Object> trendMetrics = prometheusClient.queryRabbitmqDuringPeriod(exporterUrl, start, end, null, null);
                result.put("trend", trendMetrics);

                result.put("supportedMetrics", getRabbitMQSupportedMetricsDescription());
                yield result;
            }
            default -> Map.of(
                    "instant", Map.of("status", "unsupported", "type", type),
                    "supportedMetrics", Map.of("description", "暂不支持该中间件类型的详细指标查询")
            );
        };
    }

    /**
     * 转换指标格式用于前端展示
     */
    private List<Map<String, Object>> transformMetricsForDisplay(Map<String, Object> metrics) {
        List<Map<String, Object>> displayMetrics = new ArrayList<>();

        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            String key = entry.getKey();
            // 跳过元数据字段
            if (key.startsWith("_")) continue;
            // 跳过带 exporter 标签的重复 key
            if (key.contains("-exporter")) continue;

            Object value = entry.getValue();
            double numericValue = extractNumericValue(value);

            Map<String, Object> metricInfo = new LinkedHashMap<>();
            metricInfo.put("name", key);
            metricInfo.put("value", numericValue);
            metricInfo.put("displayName", getMetricDisplayName(key));
            metricInfo.put("unit", getUnitForMetric(key));
            metricInfo.put("status", evaluateMetricStatus(key, value));
            metricInfo.put("category", categorizeMetric(key));

            displayMetrics.add(metricInfo);
        }

        // 按类别分组排序
        displayMetrics.sort((a, b) -> {
            String catA = (String) a.get("category");
            String catB = (String) b.get("category");
            int catCompare = catA.compareTo(catB);
            if (catCompare != 0) return catCompare;
            return ((String) a.get("displayName")).compareTo((String) b.get("displayName"));
        });

        return displayMetrics;
    }

    /**
     * 获取指标显示名称
     */
    private String getMetricDisplayName(String metricName) {
        Map<String, String> nameMap = new LinkedHashMap<>();

        // Redis 指标名称
        nameMap.put("redis_memory_used_bytes", "内存使用量");
        nameMap.put("redis_memory_max_bytes", "最大内存限制");
        nameMap.put("redis_connected_clients", "连接客户端数");
        nameMap.put("redis_commands_processed_total", "命令处理总数");
        nameMap.put("redis_keyspace_hits_total", "缓存命中次数");
        nameMap.put("redis_keyspace_misses_total", "缓存未命中次数");
        nameMap.put("redis_keyspace_keys", "键总数");
        nameMap.put("redis_instantaneous_ops_per_sec", "瞬时 OPS");
        nameMap.put("redis_hit_rate", "缓存命中率");
        nameMap.put("hit_rate", "缓存命中率");  // 兼容不带前缀的 key
        nameMap.put("redis_blocked_clients", "阻塞客户端数");
        nameMap.put("redis_connected_slaves", "从节点数");
        nameMap.put("redis_expired_keys_total", "过期键总数");
        nameMap.put("redis_evicted_keys_total", "驱逐键总数");
        nameMap.put("redis_total_net_input_bytes", "网络输入流量");
        nameMap.put("redis_total_net_output_bytes", "网络输出流量");

        // MySQL 指标名称
        nameMap.put("mysql_global_status_threads_connected", "当前连接数");
        nameMap.put("mysql_global_status_threads_running", "运行线程数");
        nameMap.put("mysql_global_status_questions", "查询总数");
        nameMap.put("mysql_global_status_slow_queries", "慢查询数");
        nameMap.put("mysql_global_status_connections", "累计连接数");
        nameMap.put("mysql_global_status_aborted_connections", "中断连接数");
        nameMap.put("mysql_global_status_bytes_received", "接收字节");
        nameMap.put("mysql_global_status_bytes_sent", "发送字节");
        nameMap.put("mysql_global_variables_max_connections", "最大连接数限制");
        nameMap.put("mysql_innodb_buffer_pool_pages_data", "缓冲池数据页");

        // Elasticsearch 指标名称
        nameMap.put("elasticsearch_cluster_health_status", "集群健康状态");
        nameMap.put("elasticsearch_cluster_health_number_of_nodes", "节点数");
        nameMap.put("elasticsearch_cluster_health_number_of_data_nodes", "数据节点数");
        nameMap.put("elasticsearch_indices_indexing_index_total", "索引写入总数");
        nameMap.put("elasticsearch_indices_docs", "文档数");
        nameMap.put("elasticsearch_indices_store_size_bytes", "存储大小");
        nameMap.put("elasticsearch_indices_search_query_time_seconds", "查询耗时");
        nameMap.put("elasticsearch_indices_search_query_total", "查询总数");
        nameMap.put("elasticsearch_jvm_memory_used_bytes", "JVM内存使用");
        nameMap.put("elasticsearch_jvm_memory_max_bytes", "JVM最大内存");
        nameMap.put("elasticsearch_process_cpu_percent", "CPU使用率");
        nameMap.put("elasticsearch_thread_pool_active_count", "活跃线程数");
        nameMap.put("elasticsearch_transport_rx_size_bytes_total", "接收流量");
        nameMap.put("elasticsearch_transport_tx_size_bytes_total", "发送流量");

        // RocketMQ 指标名称（使用正确的 Exporter 指标）
        nameMap.put("rocketmq_producer_tps", "生产者 TPS");
        nameMap.put("rocketmq_consumer_tps", "消费者 TPS");
        nameMap.put("rocketmq_group_diff", "消息堆积数");      // 正确的 lag 指标
        nameMap.put("rocketmq_message_accumulation", "消息堆积总量");
        nameMap.put("rocketmq_consumer_lag", "消息堆积数");    // 兼容旧名称（已废弃）
        nameMap.put("rocketmq_broker_total_messages", "消息总数");
        nameMap.put("rocketmq_broker_messages_in_today", "今日入库消息");
        nameMap.put("rocketmq_broker_messages_out_today", "今日出库消息");
        nameMap.put("rocketmq_broker_topic_count", "主题数");
        nameMap.put("rocketmq_broker_group_count", "消费组数");

        // RocketMQ Console API 指标名称（替代 exporter）
        nameMap.put("rocketmq_topic_count", "Topic 数量");
        nameMap.put("rocketmq_consumer_group_count", "消费组数量");
        nameMap.put("rocketmq_max_message_lag", "最大消息堆积");
        nameMap.put("topicCount", "Topic 数量");
        nameMap.put("userTopicCount", "用户 Topic 数量");
        nameMap.put("consumerGroupCount", "消费组数量");
        nameMap.put("totalMessageLag", "消息堆积总量");
        nameMap.put("maxMessageLag", "最大消息堆积");
        nameMap.put("maxLagConsumerGroup", "最大堆积消费组");

        return nameMap.getOrDefault(metricName, metricName);
    }

    /**
     * 分类指标
     */
    private String categorizeMetric(String metricName) {
        if (metricName.contains("memory") || metricName.contains("Memory") || metricName.contains("buffer") || metricName.contains("Buffer")) {
            return "memory";
        }
        if (metricName.contains("connection") || metricName.contains("Connection") || metricName.contains("client") || metricName.contains("Client")) {
            return "connections";
        }
        if (metricName.contains("latency") || metricName.contains("Latency") || metricName.contains("time") || metricName.contains("Time") || metricName.contains("delay") || metricName.contains("Delay")) {
            return "latency";
        }
        if (metricName.contains("ops") || metricName.contains("Ops") || metricName.contains("tps") || metricName.contains("Tps") || metricName.contains("rate") || metricName.contains("Rate")) {
            return "throughput";
        }
        if (metricName.contains("hit") || metricName.contains("Hit") || metricName.contains("miss") || metricName.contains("Miss") || metricName.contains("cache") || metricName.contains("Cache")) {
            return "cache";
        }
        if (metricName.contains("error") || metricName.contains("Error") || metricName.contains("fail") || metricName.contains("Fail") || metricName.contains("slow") || metricName.contains("Slow")) {
            return "errors";
        }
        if (metricName.contains("cluster") || metricName.contains("Cluster") || metricName.contains("node") || metricName.contains("Node") || metricName.contains("health") || metricName.contains("Health")) {
            return "cluster";
        }
        if (metricName.contains("lag") || metricName.contains("Lag") || metricName.contains("queue") || metricName.contains("Queue") || metricName.contains("message") || metricName.contains("Message")) {
            return "messages";
        }
        return "other";
    }

    /**
     * 构建基础 ES 趋势数据
     */
    private Map<String, Object> buildBasicESTrend(String exporterUrl, long start, long end) {
        String instance = prometheusClient.remapExporterUrl(exporterUrl, "elasticsearch");
        Map<String, Object> timeSeries = new LinkedHashMap<>();

        // JVM 内存趋势
        timeSeries.put("jvmMemoryUsedBytes",
                prometheusClient.queryRange("elasticsearch_jvm_memory_used_bytes{instance=\"" + instance + "\"}", start, end, "1m"));

        // 搜索查询趋势
        timeSeries.put("searchQueryTotal",
                prometheusClient.queryRange("rate(elasticsearch_indices_search_query_total{instance=\"" + instance + "\"}[1m])", start, end, "1m"));

        // CPU 使用率趋势
        timeSeries.put("cpuPercent",
                prometheusClient.queryRange("elasticsearch_process_cpu_percent{instance=\"" + instance + "\"}", start, end, "1m"));

        return timeSeries;
    }

    /**
     * 格式化时间范围描述
     */
    private String formatTimePeriod(long start, long end) {
        long duration = end - start;
        if (duration <= 300) return "最近 5 分钟";
        if (duration <= 900) return "最近 15 分钟";
        if (duration <= 1800) return "最近 30 分钟";
        if (duration <= 3600) return "最近 1 小时";
        if (duration <= 7200) return "最近 2 小时";
        if (duration <= 21600) return "最近 6 小时";
        if (duration <= 86400) return "最近 24 小时";
        return duration / 86400 + " 天";
    }

    // ===================== 健康状态评估 =====================

    private Map<String, Object> evaluateRedisHealth(Map<String, Object> instantMetrics, Map<String, Object> trendMetrics) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("overall", "healthy");
        health.put("issues", new ArrayList<>());
        health.put("score", 100);

        double memoryUsed = extractNumericValue(instantMetrics.get("redis_memory_used_bytes"));
        double memoryMax = extractNumericValue(instantMetrics.get("redis_memory_max_bytes"));
        if (memoryMax > 0 && memoryUsed / memoryMax > 0.85) {
            health.put("overall", "warning");
            ((List<String>) health.get("issues")).add("内存使用率超过85%");
            health.put("score", 80);
        }

        double hitRate = extractNumericValue(instantMetrics.get("hit_rate"));
        if (hitRate < 80) {
            health.put("overall", "warning");
            ((List<String>) health.get("issues")).add("缓存命中率低于80%");
            health.put("score", Math.min((int) health.get("score"), 85));
        }

        return health;
    }

    private Map<String, Object> evaluateMySQLHealth(Map<String, Object> instantMetrics, Map<String, Object> trendMetrics) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("overall", "healthy");
        health.put("issues", new ArrayList<>());
        health.put("score", 100);

        double slowQueries = extractNumericValue(instantMetrics.get("mysql_global_status_slow_queries"));
        if (slowQueries > 10) {
            health.put("overall", "warning");
            ((List<String>) health.get("issues")).add("存在慢查询");
            health.put("score", 90);
        }

        return health;
    }

    private Map<String, Object> evaluateESHealth(Map<String, Object> instantMetrics) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("overall", "healthy");
        health.put("issues", new ArrayList<>());
        health.put("score", 100);

        // 集群状态：green=0, yellow=1, red=2
        double clusterStatus = extractNumericValue(instantMetrics.get("elasticsearch_cluster_health_status"));
        if (clusterStatus == 2) {
            health.put("overall", "critical");
            ((List<String>) health.get("issues")).add("集群状态为 RED");
            health.put("score", 0);
        } else if (clusterStatus == 1) {
            health.put("overall", "warning");
            ((List<String>) health.get("issues")).add("集群状态为 YELLOW");
            health.put("score", 70);
        }

        return health;
    }

    private Map<String, Object> evaluateRocketMQHealth(Map<String, Object> instantMetrics, Map<String, Object> trendMetrics) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("overall", "healthy");
        health.put("issues", new ArrayList<>());
        health.put("score", 100);

        // 使用正确的指标名称 rocketmq_group_diff（消息堆积数）
        double lag = extractNumericValue(instantMetrics.get("rocketmq_group_diff"));
        // 兼容：如果 rocketmq_group_diff 没有，尝试 rocketmq_message_accumulation
        if (lag == 0) {
            lag = extractNumericValue(instantMetrics.get("rocketmq_message_accumulation"));
        }
        // 兼容旧名称（已废弃）
        if (lag == 0) {
            lag = extractNumericValue(instantMetrics.get("rocketmq_consumer_lag"));
        }

        if (lag > 1000) {
            health.put("overall", "critical");
            ((List<String>) health.get("issues")).add("消息堆积严重: " + lag);
            health.put("score", 50);
        } else if (lag > 100) {
            health.put("overall", "warning");
            ((List<String>) health.get("issues")).add("存在消息堆积: " + lag);
            health.put("score", 80);
        }

        return health;
    }

    // ===================== 支持的指标说明 =====================

    private Map<String, Object> getRedisSupportedMetricsDescription() {
        return Map.of(
                "type", "Redis",
                "description", "Redis 缓存数据库指标监控",
                "categories", Map.of(
                        "memory", "内存使用、内存限制",
                        "connections", "客户端连接数、阻塞客户端",
                        "throughput", "OPS（每秒操作数）、命令处理统计",
                        "cache", "命中率、键过期、驱逐统计",
                        "latency", "命令延迟 P99"
                )
        );
    }

    private Map<String, Object> getMySQLSupportedMetricsDescription() {
        return Map.of(
                "type", "MySQL",
                "description", "MySQL 关系数据库指标监控",
                "categories", Map.of(
                        "connections", "当前连接数、最大连接数限制",
                        "throughput", "QPS（每秒查询数）",
                        "memory", "InnoDB 缓冲池使用",
                        "errors", "慢查询统计、中断连接"
                )
        );
    }

    private Map<String, Object> getESSupportedMetricsDescription() {
        return Map.of(
                "type", "Elasticsearch",
                "description", "Elasticsearch 搜索引擎指标监控",
                "categories", Map.of(
                        "cluster", "集群健康状态、节点数",
                        "throughput", "索引写入速率、搜索查询速率",
                        "memory", "JVM 内存使用",
                        "latency", "查询延迟、获取延迟"
                )
        );
    }

    private Map<String, Object> getRocketMQSupportedMetricsDescription() {
        return Map.of(
                "type", "RocketMQ",
                "description", "RocketMQ 消息队列指标监控",
                "categories", Map.of(
                        "throughput", "生产者 TPS、消费者 TPS",
                        "messages", "消息堆积、消费延迟",
                        "cluster", "主题数、消费组数"
                )
        );
    }

    private Map<String, Object> getPostgreSQLSupportedMetricsDescription() {
        return Map.of(
                "type", "PostgreSQL",
                "description", "PostgreSQL 关系数据库指标监控",
                "categories", Map.of(
                        "connections", "活跃连接数",
                        "throughput", "事务提交速率、行获取速率",
                        "cache", "缓存命中率"
                )
        );
    }

    private Map<String, Object> getMongoDBSupportedMetricsDescription() {
        return Map.of(
                "type", "MongoDB",
                "description", "MongoDB 文档数据库指标监控",
                "categories", Map.of(
                        "connections", "当前连接数",
                        "throughput", "查询速率、插入速率",
                        "memory", "WiredTiger 缓存使用",
                        "errors", "全局锁等待队列"
                )
        );
    }

    private Map<String, Object> getRabbitMQSupportedMetricsDescription() {
        return Map.of(
                "type", "RabbitMQ",
                "description", "RabbitMQ 消息队列指标监控",
                "categories", Map.of(
                        "messages", "队列消息数、Ready/Unacked 消息",
                        "throughput", "发布速率、投递速率",
                        "connections", "连接数、消费者数"
                )
        );
    }

    // ===================== Debug APIs =====================

    /**
     * 调试接口：查看 Prometheus 中所有的 rocketmq 指标名称
     *
     * 用于诊断：为什么 RocketMQ 数据为空
     */
    @GetMapping("/debug/prometheus/metrics")
    public ResponseEntity<Map<String, Object>> debugPrometheusMetrics(
            @RequestParam(required = false) String prefix) {

        try {
            List<String> metricNames = prometheusClient.queryMetricNames(prefix);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("prometheusUrl", prometheusClient.getInstanceCache());
            result.put("totalMetrics", metricNames.size());
            result.put("metrics", metricNames);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Debug query failed: {}", e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("code", 500);
            errorResult.put("message", e.getMessage());
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 调试接口：查看指定指标的所有实例（带完整标签）
     *
     * 用于诊断：检查 instance 标签是否正确匹配
     */
    @GetMapping("/debug/prometheus/metric/{metricName}")
    public ResponseEntity<Map<String, Object>> debugPrometheusMetricDetail(
            @PathVariable String metricName) {

        try {
            List<Map<String, Object>> instances = prometheusClient.queryMetricWithLabels(metricName);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("metricName", metricName);
            result.put("instanceCount", instances.size());
            result.put("instances", instances);

            // 检查是否有数据
            if (instances.isEmpty()) {
                result.put("hint", "Prometheus 中没有该指标数据，可能原因：");
                result.put("possibleReasons", List.of(
                        "1. Exporter 没有暴露该指标",
                        "2. Prometheus 没有配置采集该 Exporter",
                        "3. Exporter 或目标服务未运行",
                        "4. 指标名称不正确（检查 Exporter 文档）"
                ));
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Debug query failed: {}", e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("code", 500);
            errorResult.put("message", e.getMessage());
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 调试接口：查看 Prometheus 中所有 instance 标签
     */
    @GetMapping("/debug/prometheus/instances")
    public ResponseEntity<Map<String, Object>> debugPrometheusInstances() {
        try {
            Map<String, String> instanceCache = prometheusClient.getInstanceCache();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("instanceCount", instanceCache.size());
            result.put("instances", instanceCache);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Debug query failed: {}", e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("code", 500);
            errorResult.put("message", e.getMessage());
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 调试接口：检查 RocketMQ Console 可用性状态
     * <p>
     * 用于验证 RocketMQ 状态判断逻辑是否正常工作
     */
    @GetMapping("/debug/rocketmq/status")
    public ResponseEntity<Map<String, Object>> debugRocketMQStatus() {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);

            // 检查 Console API 是否可用
            boolean consoleAvailable = rocketMQConsoleClient.isConsoleAvailable();
            result.put("consoleAvailable", consoleAvailable);
            result.put("status", consoleAvailable ? "UP" : "down");

            // 如果可用，获取实时指标
            if (consoleAvailable) {
                Map<String, Object> metrics = rocketMQConsoleClient.queryClusterOverview();
                result.put("metrics", metrics);
                result.put("topicCount", metrics.get("topicCount"));
                result.put("consumerGroupCount", metrics.get("consumerGroupCount"));
                result.put("totalMessageLag", metrics.get("totalMessageLag"));
            } else {
                result.put("message", "RocketMQ Console API 不可用，请检查配置和连接");
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("RocketMQ status check failed: {}", e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("code", 500);
            errorResult.put("consoleAvailable", false);
            errorResult.put("status", "down");
            errorResult.put("message", e.getMessage());
            return ResponseEntity.ok(errorResult);
        }
    }
}