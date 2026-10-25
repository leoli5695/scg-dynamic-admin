package com.leoli.gateway.admin.tool.handler;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import static com.leoli.gateway.admin.tool.ToolArgs.getIntArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredLongArg;

/**
 * Filter-chain observability tools: per-instance chain stats, slowest filters, slow requests, single-trace filter detail, and the slow-threshold setting.
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
public class FilterChainToolHandler {

    private final GatewayInstanceService gatewayInstanceService;
    private final RestTemplate restTemplate;

    public Object getFilterChainStats(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/stats";
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = restTemplate.getForObject(url, Map.class);

            // 格式化输出，突出关键信息
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("totalRecords", stats != null ? stats.get("totalRecords") : 0);
            result.put("filterCount", stats != null ? stats.get("filterCount") : 0);
            result.put("slowRequestCount", stats != null ? stats.get("slowRequestCount") : 0);
            result.put("slowThresholdMs", stats != null ? stats.get("slowThresholdMs") : 1000);

            // 过滤器统计列表
            if (stats != null && stats.get("filters") != null) {
                result.put("filters", stats.get("filters"));
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to get filter chain stats for instance: {}", instanceId, e);
            return Map.of("error", "Failed to get filter chain stats: " + e.getMessage());
        }
    }

    public Object getSlowestFilters(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        int limit = getIntArg(args, "limit", 10);

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/slowest-filters?limit=" + limit;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("limit", limit);
            if (response != null) {
                result.put("totalFilters", response.get("totalFilters"));
                result.put("slowestFilters", response.get("slowestFilters"));
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to get slowest filters for instance: {}", instanceId, e);
            return Map.of("error", "Failed to get slowest filters: " + e.getMessage());
        }
    }

    public Object getSlowRequests(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        int limit = getIntArg(args, "limit", 20);

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/slow?limit=" + limit;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("limit", limit);
            if (response != null) {
                result.put("thresholdMs", response.get("thresholdMs"));
                result.put("slowRequestCount", response.get("slowRequestCount"));
                result.put("slowRequests", response.get("slowRequests"));
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to get slow requests for instance: {}", instanceId, e);
            return Map.of("error", "Failed to get slow requests: " + e.getMessage());
        }
    }

    public Object getFilterTraceDetail(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        String traceId = getRequiredStringArg(args, "traceId");

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/trace/" + traceId;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("traceId", traceId);
            if (response != null) {
                result.putAll(response);
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to get filter trace detail for instance: {}, trace: {}", instanceId, traceId, e);
            return Map.of("error", "Failed to get filter trace detail: " + e.getMessage());
        }
    }

    public Object setSlowThreshold(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        long thresholdMs = getRequiredLongArg(args, "thresholdMs");

        if (thresholdMs < 0) {
            return Map.of("error", "Threshold must be positive");
        }

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/threshold?thresholdMs=" + thresholdMs;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, null, Map.class);

            return Map.of(
                    "success", true,
                    "instanceId", instanceId,
                    "thresholdMs", thresholdMs,
                    "message", "Slow threshold updated successfully"
            );
        } catch (Exception e) {
            log.error("Failed to set slow threshold for instance: {}", instanceId, e);
            return Map.of("error", "Failed to set slow threshold: " + e.getMessage());
        }
    }
}
