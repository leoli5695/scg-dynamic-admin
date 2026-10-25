package com.leoli.gateway.admin.tool.handler;

import com.leoli.gateway.admin.client.PrometheusClient;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.DistributedTraceService;
import com.leoli.gateway.admin.service.ServiceMiddlewareService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getIntArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;

/**
 * Middleware inventory and distributed-tracing tools, including the per-engine exporter metric queries (Redis, RocketMQ, MySQL, Elasticsearch) and request-bottleneck analysis.
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
public class MiddlewareTraceToolHandler {

    private final ServiceMiddlewareService serviceMiddlewareService;
    private final DistributedTraceService distributedTraceService;
    private final PrometheusClient prometheusClient;

    /**
     * 获取指定服务的中间件信息
     */
    public Object getServiceMiddlewares(Map<String, Object> args) {
        String serviceName = getStringArg(args, "serviceName");
        if (serviceName == null) {
            return Map.of("error", "serviceName is required");
        }
        return serviceMiddlewareService.getServiceMiddleware(serviceName);
    }

    /**
     * 获取所有已上报中间件的服务列表
     */
    public Object getAllServicesWithMiddlewares(Map<String, Object> args) {
        return serviceMiddlewareService.getAllServices();
    }

    /**
     * 获取中间件统计信息
     */
    public Object getMiddlewareStatistics(Map<String, Object> args) {
        return serviceMiddlewareService.getMiddlewareStatistics();
    }

    /**
     * 获取Exporter地址映射
     */
    public Object getExporterMapping(Map<String, Object> args) {
        String serviceName = getStringArg(args, "serviceName");
        if (serviceName == null) {
            return Map.of("error", "serviceName is required");
        }
        return serviceMiddlewareService.getExporterMapping(serviceName);
    }

    /**
     * 获取完整链路数据
     */
    public Object getDistributedTrace(Map<String, Object> args) {
        String traceId = getStringArg(args, "traceId");
        if (traceId == null) {
            return Map.of("error", "traceId is required");
        }
        return distributedTraceService.getTraceByTraceId(traceId);
    }

    /**
     * 获取服务的链路追踪数据
     */
    public Object getServiceTraces(Map<String, Object> args) {
        String serviceName = getStringArg(args, "serviceName");
        if (serviceName == null) {
            return Map.of("error", "serviceName is required");
        }
        int page = getIntArg(args, "page", 0);
        int size = getIntArg(args, "size", 20);
        return distributedTraceService.getTracesByService(serviceName, page, size);
    }

    /**
     * 获取慢请求链路
     */
    public Object getSlowTraces(Map<String, Object> args) {
        String serviceName = getStringArg(args, "serviceName");
        int limit = getIntArg(args, "limit", 50);
        if (serviceName != null) {
            return distributedTraceService.getSlowTracesByService(serviceName, limit);
        }
        return distributedTraceService.getSlowTraces(limit);
    }

    /**
     * 获取失败请求链路
     */
    public Object getFailedTraces(Map<String, Object> args) {
        String serviceName = getStringArg(args, "serviceName");
        int limit = getIntArg(args, "limit", 50);
        if (serviceName != null) {
            return distributedTraceService.getFailedTracesByService(serviceName, limit);
        }
        return distributedTraceService.getFailedTraces(limit);
    }

    /**
     * 获取链路统计信息
     */
    public Object getTraceStatistics(Map<String, Object> args) {
        String serviceName = getStringArg(args, "serviceName");
        if (serviceName == null) {
            return Map.of("error", "serviceName is required");
        }
        return distributedTraceService.getTraceStatistics(serviceName);
    }

    /**
     * 分析请求瓶颈（综合分析）
     */
    public Object analyzeRequestBottleneck(Map<String, Object> args) {
        String traceId = getStringArg(args, "traceId");
        if (traceId == null) {
            return Map.of("error", "traceId is required");
        }
        return distributedTraceService.analyzeRequestBottleneck(traceId);
    }

    /**
     * 查询Redis指标
     */
    public Object queryRedisMetrics(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        String serviceName = getStringArg(args, "serviceName");
        return prometheusClient.queryRedisMetrics(exporterUrl, serviceName);
    }

    /**
     * 查询RocketMQ指标
     */
    public Object queryRocketmqMetrics(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        String topic = getStringArg(args, "topic");
        return prometheusClient.queryRocketmqMetrics(exporterUrl, topic);
    }

    /**
     * 查询MySQL指标
     */
    public Object queryMysqlMetrics(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        return prometheusClient.queryMysqlMetrics(exporterUrl);
    }

    /**
     * 查询Elasticsearch指标
     */
    public Object queryEsMetrics(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        return prometheusClient.queryEsMetrics(exporterUrl);
    }
}
