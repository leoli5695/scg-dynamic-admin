package com.leoli.gateway.admin.tool;

import com.leoli.gateway.admin.service.DistributedTraceService;
import com.leoli.gateway.admin.service.ServiceMiddlewareService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务中间件和链路追踪工具注册器
 * 
 * 扩展ToolRegistry，添加中间件监控和分布式链路追踪相关工具
 * 
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MiddlewareTraceToolRegistry {

    private final ToolRegistry toolRegistry;
    private final ServiceMiddlewareService middlewareService;
    private final DistributedTraceService traceService;

    @PostConstruct
    public void init() {
        log.info("Registering middleware and trace tools...");
        
        registerMiddlewareTools();
        registerTraceTools();
        registerPrometheusQueryTools();
        registerPeriodQueryTools();
        
        log.info("Middleware and trace tools registered: {} new tools", 
            countNewTools());
    }

    /**
     * 注册中间件相关工具
     */
    private void registerMiddlewareTools() {
        // get_service_middlewares - 查询服务中间件信息
        toolRegistry.getTools().put("get_service_middlewares", ToolDefinition.create(
            "get_service_middlewares",
            "获取指定服务依赖的所有中间件信息，包括类型、主机地址、Exporter地址等。AI分析瓶颈时需要知道服务依赖哪些中间件才能查询对应的监控数据。",
            Map.of(
                "serviceName", Map.of(
                    "type", "string",
                    "description", "服务名称，如 seckill-service, order-service"
                )
            ),
            List.of("serviceName"),
            "middleware",
            true
        ));

        // get_all_services_with_middlewares - 获取所有服务列表
        toolRegistry.getTools().put("get_all_services_with_middlewares", ToolDefinition.create(
            "get_all_services_with_middlewares",
            "获取所有已上报中间件元数据的服务名称列表。用于概览系统中有哪些服务接入了监控。",
            Map.of(),
            List.of(),
            "middleware",
            true
        ));

        // get_middleware_statistics - 中间件统计
        toolRegistry.getTools().put("get_middleware_statistics", ToolDefinition.create(
            "get_middleware_statistics",
            "获取中间件统计信息，包括服务总数、各类型中间件数量（Redis/RocketMQ/MySQL/ES等）。用于概览系统规模。",
            Map.of(),
            List.of(),
            "middleware",
            true
        ));

        // get_exporter_mapping - 获取Exporter地址映射
        toolRegistry.getTools().put("get_exporter_mapping", ToolDefinition.create(
            "get_exporter_mapping",
            "获取指定服务的Exporter地址映射表。返回 Map<中间件类型, Exporter地址>。AI查询Prometheus时需要此地址。",
            Map.of(
                "serviceName", Map.of(
                    "type", "string",
                    "description", "服务名称"
                )
            ),
            List.of("serviceName"),
            "middleware",
            true
        ));
    }

    /**
     * 注册链路追踪相关工具
     */
    private void registerTraceTools() {
        // get_distributed_trace - 查询完整链路
        toolRegistry.getTools().put("get_distributed_trace", ToolDefinition.create(
            "get_distributed_trace",
            "获取指定TraceId的完整链路数据，包括服务名称、路径、耗时、HTTP状态码、错误信息、Span列表等。用于分析单个请求的完整执行过程。",
            Map.of(
                "traceId", Map.of(
                    "type", "string",
                    "description", "TraceId，与网关生成的X-Trace-Id对应"
                )
            ),
            List.of("traceId"),
            "trace",
            true
        ));

        // get_service_traces - 查询服务的Trace数据
        toolRegistry.getTools().put("get_service_traces", ToolDefinition.create(
            "get_service_traces",
            "获取指定服务的链路追踪数据，支持分页查询。返回Trace列表，包括TraceId、路径、耗时、状态等。用于分析服务的请求情况。",
            Map.of(
                "serviceName", Map.of(
                    "type", "string",
                    "description", "服务名称"
                ),
                "page", Map.of(
                    "type", "integer",
                    "description", "页码（默认0）",
                    "default", 0
                ),
                "size", Map.of(
                    "type", "integer",
                    "description", "每页数量（默认20）",
                    "default", 20
                )
            ),
            List.of("serviceName"),
            "trace",
            true
        ));

        // get_slow_traces - 查询慢请求
        toolRegistry.getTools().put("get_slow_traces", ToolDefinition.create(
            "get_slow_traces",
            "获取慢请求的链路追踪数据（耗时超过阈值）。返回慢请求列表，用于定位性能瓶颈。",
            Map.of(
                "serviceName", Map.of(
                    "type", "string",
                    "description", "服务名称（可选）。不提供时返回所有服务的慢请求。"
                ),
                "limit", Map.of(
                    "type", "integer",
                    "description", "返回数量限制（默认50）",
                    "default", 50
                )
            ),
            List.of(),
            "trace",
            true
        ));

        // get_failed_traces - 查询失败请求
        toolRegistry.getTools().put("get_failed_traces", ToolDefinition.create(
            "get_failed_traces",
            "获取失败的链路追踪数据（HTTP状态码>=400或success=false）。返回失败请求列表，用于故障排查。",
            Map.of(
                "serviceName", Map.of(
                    "type", "string",
                    "description", "服务名称（可选）"
                ),
                "limit", Map.of(
                    "type", "integer",
                    "description", "返回数量限制（默认50）",
                    "default", 50
                )
            ),
            List.of(),
            "trace",
            true
        ));

        // get_trace_statistics - 服务Trace统计
        toolRegistry.getTools().put("get_trace_statistics", ToolDefinition.create(
            "get_trace_statistics",
            "获取指定服务的链路追踪统计信息，包括总请求数、慢请求数、失败请求数、平均耗时、P99耗时等。用于概览服务性能。",
            Map.of(
                "serviceName", Map.of(
                    "type", "string",
                    "description", "服务名称"
                )
            ),
            List.of("serviceName"),
            "trace",
            true
        ));

        // analyze_request_bottleneck - 分析请求瓶颈
        toolRegistry.getTools().put("analyze_request_bottleneck", ToolDefinition.create(
            "analyze_request_bottleneck",
            "分析指定TraceId的请求瓶颈。自动查询：1) Trace链路数据 2) 服务中间件Exporter地址 3) Prometheus中间件指标。输出：瓶颈在哪（Redis？MQ？MySQL？）、耗时占比、优化建议。",
            Map.of(
                "traceId", Map.of(
                    "type", "string",
                    "description", "TraceId"
                )
            ),
            List.of("traceId"),
            "trace",
            true
        ));
    }

    /**
     * 注册Prometheus查询工具
     */
    private void registerPrometheusQueryTools() {
        // query_redis_metrics - 查询Redis指标
        toolRegistry.getTools().put("query_redis_metrics", ToolDefinition.create(
            "query_redis_metrics",
            "查询指定Exporter的Redis性能指标，包括：内存使用、连接数、命令延迟P99、命中率等。需要先通过get_exporter_mapping获取Exporter地址。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "Redis Exporter地址，格式：host:port，如 redis-exporter:9121"
                ),
                "serviceName", Map.of(
                    "type", "string",
                    "description", "服务名称（可选，用于上下文关联）"
                )
            ),
            List.of("exporterUrl"),
            "prometheus",
            true
        ));

        // query_rocketmq_metrics - 查询RocketMQ指标
        toolRegistry.getTools().put("query_rocketmq_metrics", ToolDefinition.create(
            "query_rocketmq_metrics",
            "查询指定Exporter的RocketMQ性能指标，包括：消息堆积量、TPS、Consumer延迟等。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "RocketMQ Exporter地址"
                ),
                "topic", Map.of(
                    "type", "string",
                    "description", "Topic名称（可选）"
                )
            ),
            List.of("exporterUrl"),
            "prometheus",
            true
        ));

        // query_mysql_metrics - 查询MySQL指标
        toolRegistry.getTools().put("query_mysql_metrics", ToolDefinition.create(
            "query_mysql_metrics",
            "查询指定Exporter的MySQL性能指标，包括：连接数、QPS、慢查询数、InnoDB缓冲池使用率等。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "MySQL Exporter地址"
                )
            ),
            List.of("exporterUrl"),
            "prometheus",
            true
        ));

        // query_es_metrics - 查询Elasticsearch指标
        toolRegistry.getTools().put("query_es_metrics", ToolDefinition.create(
            "query_es_metrics",
            "查询指定Exporter的Elasticsearch性能指标，包括：索引写入速率、搜索延迟、集群健康状态等。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "ES Exporter地址"
                )
            ),
            List.of("exporterUrl"),
            "prometheus",
            true
        ));
    }

    /**
     * 统计新增工具数量
     */
    private int countNewTools() {
        return 21; // middleware(4) + trace(6) + prometheus(4) + period_query(7)
    }

    /**
     * 注册时间段查询工具（Phase 1 MVP）
     * 支持查询指定时间范围内的中间件指标趋势
     */
    private void registerPeriodQueryTools() {
        // query_redis_during_period - 时间段Redis指标查询
        toolRegistry.getTools().put("query_redis_during_period", ToolDefinition.create(
            "query_redis_during_period",
            "查询指定时间段内Redis的性能指标趋势（OPS、内存、连接数、P99延迟、命中率），使用rate()计算速率变化。适合压测期间或故障时段的Redis分析。返回timeSeries时间序列和summary统计摘要。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "Redis Exporter地址，格式：host:port，如 redis-exporter:9121"
                ),
                "startTime", Map.of(
                    "type", "integer",
                    "description", "开始时间（Unix秒），可通过压测记录获取"
                ),
                "endTime", Map.of(
                    "type", "integer",
                    "description", "结束时间（Unix秒）"
                ),
                "step", Map.of(
                    "type", "string",
                    "description", "查询步长（可选，如15s/30s/1m/5m），不提供时根据时间范围自动计算"
                )
            ),
            List.of("exporterUrl", "startTime", "endTime"),
            "prometheus",
            true
        ));

        // query_mysql_during_period - 时间段MySQL指标查询
        toolRegistry.getTools().put("query_mysql_during_period", ToolDefinition.create(
            "query_mysql_during_period",
            "查询指定时间段内MySQL的性能指标趋势（QPS、连接数、慢查询速率、InnoDB缓冲池）。适合压测期间的数据库分析。返回timeSeries和summary。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "MySQL Exporter地址，格式：host:port，如 mysqld-exporter:9104"
                ),
                "startTime", Map.of(
                    "type", "integer",
                    "description", "开始时间（Unix秒）"
                ),
                "endTime", Map.of(
                    "type", "integer",
                    "description", "结束时间（Unix秒）"
                ),
                "step", Map.of(
                    "type", "string",
                    "description", "查询步长（可选）"
                )
            ),
            List.of("exporterUrl", "startTime", "endTime"),
            "prometheus",
            true
        ));

        // query_rocketmq_during_period - 时间段RocketMQ指标查询
        toolRegistry.getTools().put("query_rocketmq_during_period", ToolDefinition.create(
            "query_rocketmq_during_period",
            "查询指定时间段内RocketMQ的性能指标趋势（Producer TPS、Consumer TPS、消费堆积Lag）。适合压测期间的消息队列分析。返回timeSeries和summary。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "RocketMQ Exporter地址"
                ),
                "startTime", Map.of(
                    "type", "integer",
                    "description", "开始时间（Unix秒）"
                ),
                "endTime", Map.of(
                    "type", "integer",
                    "description", "结束时间（Unix秒）"
                ),
                "step", Map.of(
                    "type", "string",
                    "description", "查询步长（可选）"
                ),
                "topic", Map.of(
                    "type", "string",
                    "description", "Topic名称（可选，不提供时查询所有Topic）"
                )
            ),
            List.of("exporterUrl", "startTime", "endTime"),
            "prometheus",
            true
        ));

        // analyze_stress_test_with_middleware - 压测中间件关联分析
        toolRegistry.getTools().put("analyze_stress_test_with_middleware", ToolDefinition.create(
            "analyze_stress_test_with_middleware",
            "分析压测期间中间件的性能表现。自动根据压测时间窗口（±30s扩展）查询所有关联中间件（Redis/MySQL/RocketMQ）的指标趋势，生成关联分析报告。需要压测已完成。",
            Map.of(
                "testId", Map.of(
                    "type", "integer",
                    "description", "压测ID，可通过list_stress_test_history获取"
                ),
                "serviceName", Map.of(
                    "type", "string",
                    "description", "服务名称（可选），如果不提供则自动检测"
                )
            ),
            List.of("testId"),
            "analysis",
            true
        ));

        // query_postgresql_during_period - 时间段PostgreSQL指标查询
        toolRegistry.getTools().put("query_postgresql_during_period", ToolDefinition.create(
            "query_postgresql_during_period",
            "查询指定时间段内PostgreSQL的性能指标趋势（活跃连接、事务提交/回滚速率、缓存命中率、行获取速率）。适合压测期间的PG数据库分析。返回timeSeries和summary。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "PostgreSQL Exporter地址，格式：host:port，如 pg-exporter:9187"
                ),
                "startTime", Map.of(
                    "type", "integer",
                    "description", "开始时间（Unix秒）"
                ),
                "endTime", Map.of(
                    "type", "integer",
                    "description", "结束时间（Unix秒）"
                ),
                "step", Map.of(
                    "type", "string",
                    "description", "查询步长（可选）"
                )
            ),
            List.of("exporterUrl", "startTime", "endTime"),
            "prometheus",
            true
        ));

        // query_mongodb_during_period - 时间段MongoDB指标查询
        toolRegistry.getTools().put("query_mongodb_during_period", ToolDefinition.create(
            "query_mongodb_during_period",
            "查询指定时间段内MongoDB的性能指标趋势（连接数、查询/插入OPS、WiredTiger缓存、锁等待队列）。适合压测期间的MongoDB分析。返回timeSeries和summary。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "MongoDB Exporter地址，格式：host:port，如 mongodb-exporter:9216"
                ),
                "startTime", Map.of(
                    "type", "integer",
                    "description", "开始时间（Unix秒）"
                ),
                "endTime", Map.of(
                    "type", "integer",
                    "description", "结束时间（Unix秒）"
                ),
                "step", Map.of(
                    "type", "string",
                    "description", "查询步长（可选）"
                )
            ),
            List.of("exporterUrl", "startTime", "endTime"),
            "prometheus",
            true
        ));

        // query_rabbitmq_during_period - 时间段RabbitMQ指标查询
        toolRegistry.getTools().put("query_rabbitmq_during_period", ToolDefinition.create(
            "query_rabbitmq_during_period",
            "查询指定时间段内RabbitMQ的性能指标趋势（队列消息数、消费者数、发布/投递速率、连接数）。适合压测期间的消息队列分析。返回timeSeries和summary。",
            Map.of(
                "exporterUrl", Map.of(
                    "type", "string",
                    "description", "RabbitMQ Exporter地址，格式：host:port，如 rabbitmq-exporter:9419"
                ),
                "startTime", Map.of(
                    "type", "integer",
                    "description", "开始时间（Unix秒）"
                ),
                "endTime", Map.of(
                    "type", "integer",
                    "description", "结束时间（Unix秒）"
                ),
                "step", Map.of(
                    "type", "string",
                    "description", "查询步长（可选）"
                ),
                "queue", Map.of(
                    "type", "string",
                    "description", "队列名称（可选，不提供时查询所有队列汇总）"
                )
            ),
            List.of("exporterUrl", "startTime", "endTime"),
            "prometheus",
            true
        ));
    }
}