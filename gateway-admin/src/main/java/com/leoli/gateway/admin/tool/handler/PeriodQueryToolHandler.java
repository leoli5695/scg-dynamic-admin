package com.leoli.gateway.admin.tool.handler;

import com.leoli.gateway.admin.client.PrometheusClient;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.DistributedTraceService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getLongArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;

/**
 * Time-range metric queries per middleware engine (Redis, MySQL, RocketMQ, PostgreSQL, MongoDB, RabbitMQ) and stress-test/middleware correlation analysis.
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
public class PeriodQueryToolHandler {

    private final DistributedTraceService distributedTraceService;
    private final PrometheusClient prometheusClient;

    public Object queryRedisDuringPeriod(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        long startTime = getLongArg(args, "startTime", 0);
        long endTime = getLongArg(args, "endTime", 0);
        if (startTime == 0 || endTime == 0) {
            return Map.of("error", "startTime and endTime are required (Unix seconds)");
        }
        String step = getStringArg(args, "step"); // nullable, auto-calc
        return prometheusClient.queryRedisDuringPeriod(exporterUrl, startTime, endTime, step);
    }

    public Object queryMysqlDuringPeriod(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        long startTime = getLongArg(args, "startTime", 0);
        long endTime = getLongArg(args, "endTime", 0);
        if (startTime == 0 || endTime == 0) {
            return Map.of("error", "startTime and endTime are required (Unix seconds)");
        }
        String step = getStringArg(args, "step");
        return prometheusClient.queryMysqlDuringPeriod(exporterUrl, startTime, endTime, step);
    }

    public Object queryRocketmqDuringPeriod(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        long startTime = getLongArg(args, "startTime", 0);
        long endTime = getLongArg(args, "endTime", 0);
        if (startTime == 0 || endTime == 0) {
            return Map.of("error", "startTime and endTime are required (Unix seconds)");
        }
        String step = getStringArg(args, "step");
        String topic = getStringArg(args, "topic");
        return prometheusClient.queryRocketmqDuringPeriod(exporterUrl, startTime, endTime, step, topic);
    }

    public Object analyzeStressTestWithMiddleware(Map<String, Object> args) {
        long testId = getLongArg(args, "testId", 0);
        if (testId == 0) {
            return Map.of("error", "testId is required");
        }
        String serviceName = getStringArg(args, "serviceName");
        return distributedTraceService.analyzeStressTestWithMiddleware(testId, serviceName);
    }

    public Object queryPostgresqlDuringPeriod(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        long startTime = getLongArg(args, "startTime", 0);
        long endTime = getLongArg(args, "endTime", 0);
        if (startTime == 0 || endTime == 0) {
            return Map.of("error", "startTime and endTime are required (Unix seconds)");
        }
        String step = getStringArg(args, "step");
        return prometheusClient.queryPostgresqlDuringPeriod(exporterUrl, startTime, endTime, step);
    }

    public Object queryMongodbDuringPeriod(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        long startTime = getLongArg(args, "startTime", 0);
        long endTime = getLongArg(args, "endTime", 0);
        if (startTime == 0 || endTime == 0) {
            return Map.of("error", "startTime and endTime are required (Unix seconds)");
        }
        String step = getStringArg(args, "step");
        return prometheusClient.queryMongodbDuringPeriod(exporterUrl, startTime, endTime, step);
    }

    public Object queryRabbitmqDuringPeriod(Map<String, Object> args) {
        String exporterUrl = getStringArg(args, "exporterUrl");
        if (exporterUrl == null) {
            return Map.of("error", "exporterUrl is required");
        }
        long startTime = getLongArg(args, "startTime", 0);
        long endTime = getLongArg(args, "endTime", 0);
        if (startTime == 0 || endTime == 0) {
            return Map.of("error", "startTime and endTime are required (Unix seconds)");
        }
        String step = getStringArg(args, "step");
        String queue = getStringArg(args, "queue");
        return prometheusClient.queryRabbitmqDuringPeriod(exporterUrl, startTime, endTime, step, queue);
    }
}
