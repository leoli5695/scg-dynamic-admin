package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.DistributedTraceEntity;
import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.DistributedTraceRepository;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 全链路追踪Service
 * <p>
 * 整合网关层 RequestTrace 和下游服务 DistributedTraceEntity，
 * 通过 X-Trace-Id 关联，形成完整的请求链路视图：
 * Client → Gateway → Backend Service(s)
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FullChainTraceService {

    private final RequestTraceRepository requestTraceRepository;
    private final DistributedTraceRepository distributedTraceRepository;
    private final ObjectMapper objectMapper;

    /**
     * 根据 TraceId 查询完整链路
     *
     * @param traceId 追踪ID（X-Trace-Id）
     * @return 完整链路数据，包含网关层和下游服务层
     */
    public Map<String, Object> getFullChain(String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);

        // 1. 查询网关层追踪
        Optional<RequestTrace> gatewayTraceOpt = requestTraceRepository.findByTraceId(traceId);
        if (gatewayTraceOpt.isPresent()) {
            result.put("gateway", buildGatewayNode(gatewayTraceOpt.get()));
            result.put("hasGatewayTrace", true);
        } else {
            result.put("hasGatewayTrace", false);
        }

        // 2. 查询下游服务追踪（可能多个服务）
        List<DistributedTraceEntity> serviceTraces = distributedTraceRepository.findAllByTraceId(traceId);
        if (!serviceTraces.isEmpty()) {
            List<Map<String, Object>> serviceNodes = serviceTraces.stream()
                    .map(this::buildServiceNode)
                    .toList();
            result.put("services", serviceNodes);
            result.put("hasServiceTrace", true);
            result.put("serviceCount", serviceTraces.size());
        } else {
            result.put("services", Collections.emptyList());
            result.put("hasServiceTrace", false);
            result.put("serviceCount", 0);
        }

        // 3. 链路完整性判断
        boolean hasGateway = gatewayTraceOpt.isPresent();
        boolean hasService = !serviceTraces.isEmpty();
        if (hasGateway && hasService) {
            result.put("chainStatus", "COMPLETE");
        } else if (hasGateway) {
            result.put("chainStatus", "GATEWAY_ONLY");
        } else if (hasService) {
            result.put("chainStatus", "SERVICE_ONLY");
        } else {
            result.put("chainStatus", "NOT_FOUND");
        }

        // 4. 计算全链路耗时分布
        if (hasGateway && hasService) {
            buildDurationBreakdown(result, gatewayTraceOpt.get(), serviceTraces);
        }

        return result;
    }

    /**
     * 查询最近的全链路追踪列表（分页）
     * <p>
     * 以网关 RequestTrace 为主表，关联 DistributedTraceEntity
     */
    public Map<String, Object> getRecentFullChains(int page, int size) {
        // 1. 查询最近的网关追踪
        Page<RequestTrace> gatewayPage = requestTraceRepository.findAll(
                PageRequest.of(page, size, Sort.by("traceTime").descending())
        );

        // 2. 批量查询对应的服务追踪
        List<String> traceIds = gatewayPage.getContent().stream()
                .map(RequestTrace::getTraceId)
                .filter(Objects::nonNull)
                .toList();

        Map<String, List<DistributedTraceEntity>> serviceTraceMap = Collections.emptyMap();
        if (!traceIds.isEmpty()) {
            List<DistributedTraceEntity> allServiceTraces = distributedTraceRepository.findByTraceIdIn(traceIds);
            serviceTraceMap = allServiceTraces.stream()
                    .collect(Collectors.groupingBy(DistributedTraceEntity::getTraceId));
        }

        // 3. 组装全链路列表
        List<Map<String, Object>> chains = new ArrayList<>();
        for (RequestTrace gateway : gatewayPage.getContent()) {
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("traceId", gateway.getTraceId());
            chain.put("method", gateway.getMethod());
            chain.put("path", gateway.getPath());
            chain.put("statusCode", gateway.getStatusCode());
            chain.put("latencyMs", gateway.getLatencyMs());
            chain.put("routeId", gateway.getRouteId());
            chain.put("targetInstance", gateway.getTargetInstance());
            chain.put("clientIp", gateway.getClientIp());
            chain.put("traceTime", gateway.getTraceTime());
            chain.put("errorMessage", gateway.getErrorMessage());

            List<DistributedTraceEntity> services = serviceTraceMap.getOrDefault(
                    gateway.getTraceId(), Collections.emptyList());
            chain.put("hasServiceTrace", !services.isEmpty());
            chain.put("serviceCount", services.size());

            // 服务摘要
            if (!services.isEmpty()) {
                List<Map<String, Object>> serviceSummaries = services.stream()
                        .map(s -> {
                            Map<String, Object> summary = new LinkedHashMap<>();
                            summary.put("serviceName", s.getServiceName());
                            summary.put("durationMs", s.getTotalDurationMs());
                            summary.put("success", s.getSuccess());
                            summary.put("statusCode", s.getStatusCode());
                            return summary;
                        })
                        .toList();
                chain.put("services", serviceSummaries);

                // 判断链路状态
                boolean allSuccess = services.stream()
                        .allMatch(s -> Boolean.TRUE.equals(s.getSuccess()));
                chain.put("chainStatus", allSuccess ? "COMPLETE" : "COMPLETE_WITH_ERRORS");
            } else {
                chain.put("services", Collections.emptyList());
                chain.put("chainStatus", "GATEWAY_ONLY");
            }

            chains.add(chain);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chains", chains);
        result.put("page", page);
        result.put("size", size);
        result.put("totalElements", gatewayPage.getTotalElements());
        result.put("totalPages", gatewayPage.getTotalPages());

        return result;
    }

    /**
     * 构建网关节点数据
     */
    private Map<String, Object> buildGatewayNode(RequestTrace trace) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeType", "GATEWAY");
        node.put("instanceId", trace.getInstanceId());
        node.put("method", trace.getMethod());
        node.put("uri", trace.getUri());
        node.put("path", trace.getPath());
        node.put("routeId", trace.getRouteId());
        node.put("queryString", trace.getQueryString());
        node.put("statusCode", trace.getStatusCode());
        node.put("latencyMs", trace.getLatencyMs());
        node.put("targetInstance", trace.getTargetInstance());
        node.put("clientIp", trace.getClientIp());
        node.put("userAgent", trace.getUserAgent());
        node.put("errorMessage", trace.getErrorMessage());
        node.put("errorType", trace.getErrorType());
        node.put("traceType", trace.getTraceType());
        node.put("traceTime", trace.getTraceTime());
        // 请求/响应数据（全链路详情页展示）
        node.put("requestHeaders", trace.getRequestHeaders());
        node.put("requestBody", trace.getRequestBody());
        node.put("responseHeaders", trace.getResponseHeaders());
        node.put("responseBody", trace.getResponseBody());
        return node;
    }

    /**
     * 构建服务节点数据
     */
    private Map<String, Object> buildServiceNode(DistributedTraceEntity trace) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeType", "SERVICE");
        node.put("serviceName", trace.getServiceName());
        node.put("path", trace.getPath());
        node.put("method", trace.getMethod());
        node.put("statusCode", trace.getStatusCode());
        node.put("totalDurationMs", trace.getTotalDurationMs());
        node.put("success", trace.getSuccess());
        node.put("errorMessage", trace.getErrorMessage());
        node.put("clientIp", trace.getClientIp());
        node.put("isSlow", trace.getIsSlow());
        node.put("traceTime", trace.getTraceTime());

        // 解析 Spans
        if (trace.getSpans() != null) {
            try {
                List<Map<String, Object>> spans = objectMapper.readValue(
                        trace.getSpans(),
                        new TypeReference<>() {}
                );
                node.put("spans", spans);
                node.put("spanCount", spans.size());
            } catch (Exception e) {
                log.warn("Failed to parse spans JSON for traceId={}: {}", trace.getTraceId(), e.getMessage());
                node.put("spans", Collections.emptyList());
                node.put("spanCount", 0);
            }
        } else {
            node.put("spans", Collections.emptyList());
            node.put("spanCount", 0);
        }

        return node;
    }

    /**
     * 构建全链路耗时分布
     * <p>
     * 分析网关耗时 vs 下游服务耗时，计算网关自身开销
     */
    private void buildDurationBreakdown(Map<String, Object> result,
                                        RequestTrace gateway,
                                        List<DistributedTraceEntity> serviceTraces) {
        Map<String, Object> breakdown = new LinkedHashMap<>();

        Long gatewayTotalMs = gateway.getLatencyMs();
        breakdown.put("gatewayTotalMs", gatewayTotalMs);

        // 下游服务总耗时（取最大值，因为可能并行调用）
        long maxServiceDurationMs = serviceTraces.stream()
                .mapToLong(s -> s.getTotalDurationMs() != null ? s.getTotalDurationMs() : 0)
                .max()
                .orElse(0);
        breakdown.put("serviceMaxDurationMs", maxServiceDurationMs);

        // 网关自身开销 = 网关总耗时 - 下游服务最大耗时
        if (gatewayTotalMs != null && gatewayTotalMs > 0) {
            long gatewayOverheadMs = Math.max(0, gatewayTotalMs - maxServiceDurationMs);
            breakdown.put("gatewayOverheadMs", gatewayOverheadMs);
            breakdown.put("gatewayOverheadPercent",
                    Math.round(gatewayOverheadMs * 100.0 / gatewayTotalMs));
            breakdown.put("servicePercent",
                    Math.round(maxServiceDurationMs * 100.0 / gatewayTotalMs));
        }

        // 每个服务的耗时明细
        List<Map<String, Object>> serviceBreakdowns = serviceTraces.stream()
                .map(s -> {
                    Map<String, Object> sb = new LinkedHashMap<>();
                    sb.put("serviceName", s.getServiceName());
                    sb.put("durationMs", s.getTotalDurationMs());
                    return sb;
                })
                .toList();
        breakdown.put("services", serviceBreakdowns);

        result.put("durationBreakdown", breakdown);
    }
}
