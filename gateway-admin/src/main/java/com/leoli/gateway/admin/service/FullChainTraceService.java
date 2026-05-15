package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.DistributedTraceEntity;
import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.DistributedTraceRepository;
import com.leoli.gateway.admin.repository.FilterChainExecutionRepository;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
    private final FilterChainExecutionRepository filterChainExecutionRepository;
    private final FilterChainExecutionService filterChainExecutionService;
    private final GatewayInstanceService gatewayInstanceService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

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

        // FIX: 批量查询 Filter 链总耗时，确保列表与详情数据一致
        Map<String, Long> filterChainDurationMap = new HashMap<>();
        if (!traceIds.isEmpty()) {
            List<Object[]> filterDurations = filterChainExecutionRepository.findFirstFilterDurationByTraceIds(traceIds);
            for (Object[] row : filterDurations) {
                String traceId = (String) row[0];
                Long durationMs = (Long) row[1];
                filterChainDurationMap.put(traceId, durationMs);
            }
        }

        // 3. 组装全链路列表
        List<Map<String, Object>> chains = new ArrayList<>();
        for (RequestTrace gateway : gatewayPage.getContent()) {
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("traceId", gateway.getTraceId());
            chain.put("method", gateway.getMethod());
            chain.put("path", gateway.getPath());
            chain.put("statusCode", gateway.getStatusCode());
            
            // FIX: 优先使用 Filter 链总耗时，确保与详情页一致
            // 如果没有 Filter 链数据，fallback 到数据库 latencyMs
            Long totalDurationMs = filterChainDurationMap.get(gateway.getTraceId());
            if (totalDurationMs != null) {
                chain.put("latencyMs", totalDurationMs);
                chain.put("latencyMsSource", "FILTER_CHAIN");
            } else {
                chain.put("latencyMs", gateway.getLatencyMs());
                chain.put("latencyMsSource", "DATABASE");
            }
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
     * FIX: 网关总耗时直接使用 Filter 链的第一个 filter 的 durationMs（累积时间）
     * 这个值包含了从 filter chain 开始到请求完成的全部时间，是最准确的总耗时。
     * <p>
     * 网关开销 = Σ selfTimeMicros（所有 filter 自己的逻辑时间）
     * 服务耗时 = 网关总耗时 - 网关开销（估算）
     * <p>
     * 数据源优先级：
     * 1. 网关实时 API（最准确，包含完整 filter 数据）
     * 2. 数据库 filter_chain_execution 表（fallback）
     */
    private void buildDurationBreakdown(Map<String, Object> result,
                                        RequestTrace gateway,
                                        List<DistributedTraceEntity> serviceTraces) {
        Map<String, Object> breakdown = new LinkedHashMap<>();

        // 下游服务总耗时（取最大值，因为可能并行调用）
        long maxServiceDurationMs = serviceTraces.stream()
                .mapToLong(s -> s.getTotalDurationMs() != null ? s.getTotalDurationMs() : 0)
                .max()
                .orElse(0);
        breakdown.put("serviceMaxDurationMs", maxServiceDurationMs);

        // FIX: 网关总耗时和开销都从 Filter 链数据计算
        long gatewayTotalDurationMs = 0;
        long gatewayOverheadMs = 0;
        long gatewayOverheadMicros = 0;
        boolean gotGatewayData = false;
        String dataSource = "NO_DATA";

        // 尝试从网关实时 API 获取数据
        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(gateway.getInstanceId());
            if (accessUrl == null) {
                accessUrl = "http://127.0.0.1:81";
                log.debug("instanceId '{}' not found in database, using fallback URL: {}", 
                        gateway.getInstanceId(), accessUrl);
            }
            
            String filterChainUrl = accessUrl + "/internal/filter-chain/trace/" + gateway.getTraceId();
            @SuppressWarnings("unchecked")
            Map<String, Object> gatewayFilterData = restTemplate.getForObject(filterChainUrl, Map.class);
            
            if (gatewayFilterData != null && gatewayFilterData.containsKey("executions")) {
                List<Map<String, Object>> executions = (List<Map<String, Object>>) gatewayFilterData.get("executions");
                
                // FIX: 网关总耗时 = 第一个 filter 的 durationMs（累积时间，包含整个请求周期）
                Optional<Map<String, Object>> firstFilter = executions.stream()
                        .min(Comparator.comparingInt(e -> ((Number) e.get("order")).intValue()));
                if (firstFilter.isPresent()) {
                    Object durationObj = firstFilter.get().get("totalDurationMs");
                    if (durationObj instanceof Number) {
                        gatewayTotalDurationMs = ((Number) durationObj).longValue();
                    }
                }
                
                // FIX: 网关开销 = Σ selfTimeMicros，但排除 NettyWriteResponse
                // NettyWriteResponse 的 selfTime 是"响应写入网络的时间"，不是网关处理开销
                // 它应该被归类为网络传输时间
                gatewayOverheadMicros = executions.stream()
                        .filter(e -> {
                            // 排除 NettyWriteResponse，它的 selfTime 是网络写入时间
                            String filterName = (String) e.get("filter");
                            return !"NettyWriteResponse".equals(filterName);
                        })
                        .mapToLong(e -> {
                            Object micros = e.get("selfTimeMicros");
                            if (micros instanceof Number) {
                                return ((Number) micros).longValue();
                            }
                            return 0L;
                        })
                        .sum();
                gatewayOverheadMs = gatewayOverheadMicros / 1000;
                
                // 获取 NettyWriteResponse 的时间（网络传输）
                long nettyWriteTimeMicros = executions.stream()
                        .filter(e -> "NettyWriteResponse".equals((String) e.get("filter")))
                        .mapToLong(e -> {
                            Object micros = e.get("selfTimeMicros");
                            if (micros instanceof Number) {
                                return ((Number) micros).longValue();
                            }
                            return 0L;
                        })
                        .findFirst()
                        .orElse(0L);
                long nettyWriteTimeMs = nettyWriteTimeMicros / 1000;
                
                // 存储网络传输时间
                breakdown.put("networkTransferMs", nettyWriteTimeMs);
                breakdown.put("networkTransferSource", "GATEWAY_API");
                
                gotGatewayData = true;
                dataSource = "GATEWAY_API";
                
                log.info("Got gateway data: traceId={}, total={}ms, overhead={}ms (exclude NettyWrite), network={}ms", 
                        gateway.getTraceId(), gatewayTotalDurationMs, gatewayOverheadMs, nettyWriteTimeMs);
            }
        } catch (Exception e) {
            log.warn("Failed to get filter chain from gateway real-time API for traceId={}, using database fallback: {}", 
                    gateway.getTraceId(), e.getMessage());
        }

        // Fallback: 从数据库获取数据
        if (!gotGatewayData) {
            Map<String, Object> filterSummary = filterChainExecutionService.getTraceExecutionSummary(gateway.getTraceId());
            if (Boolean.TRUE.equals(filterSummary.get("hasFilterData"))) {
                gatewayTotalDurationMs = ((Number) filterSummary.getOrDefault("totalFilterDurationMs", 0)).longValue();
                gatewayOverheadMs = ((Number) filterSummary.getOrDefault("gatewayOverheadMs", 0)).longValue();
                gatewayOverheadMicros = ((Number) filterSummary.getOrDefault("gatewayOverheadMicros", 0)).longValue();
                // FIX: 从数据库获取网络传输时间
                long nettyWriteTimeMs = ((Number) filterSummary.getOrDefault("networkTransferMs", 0)).longValue();
                breakdown.put("networkTransferMs", nettyWriteTimeMs);
                breakdown.put("networkTransferSource", "DATABASE");
                gotGatewayData = true;
                dataSource = "DATABASE";
                log.debug("Got gateway data from database: traceId={}, total={}ms, overhead={}ms, network={}ms", 
                        gateway.getTraceId(), gatewayTotalDurationMs, gatewayOverheadMs, nettyWriteTimeMs);
            }
        }

        // 如果仍然没有数据，使用数据库的 latencyMs 作为兜底
        if (gatewayTotalDurationMs == 0 && gateway.getLatencyMs() != null) {
            gatewayTotalDurationMs = gateway.getLatencyMs();
            dataSource = "DATABASE_LATENCY";
            log.debug("Using database latencyMs as fallback: traceId={}, latencyMs={}ms", 
                    gateway.getTraceId(), gatewayTotalDurationMs);
        }

        // 设置网关总耗时
        breakdown.put("gatewayTotalMs", gatewayTotalDurationMs);
        breakdown.put("gatewayTotalMsSource", dataSource);

        // 验证数据合理性：开销不应超过总耗时
        if (gotGatewayData && gatewayOverheadMs > 0 && gatewayTotalDurationMs > 0) {
            if (gatewayOverheadMs > gatewayTotalDurationMs) {
                log.warn("Invalid gateway overhead data: {}ms > total duration {}ms for traceId={}",
                        gatewayOverheadMs, gatewayTotalDurationMs, gateway.getTraceId());
                // 数据不合理时，重置开销为0，但保留总耗时（总耗时来源可靠）
                gatewayOverheadMs = 0;
                gatewayOverheadMicros = 0;
                gotGatewayData = false;
            }
        }

        // 服务耗时 = 后端服务处理时间
        breakdown.put("serviceDurationMs", maxServiceDurationMs);
        breakdown.put("servicePercent",
                gatewayTotalDurationMs > 0 ?
                Math.round(maxServiceDurationMs * 100.0 / gatewayTotalDurationMs) : 0);

        // 网关开销（仅在数据合理时显示）
        if (gotGatewayData && gatewayOverheadMs > 0) {
            breakdown.put("gatewayOverheadMs", gatewayOverheadMs);
            breakdown.put("gatewayOverheadMicros", gatewayOverheadMicros);
            breakdown.put("gatewayOverheadPercent",
                    gatewayTotalDurationMs > 0 ?
                    Math.round(gatewayOverheadMs * 100.0 / gatewayTotalDurationMs) : 0);
            breakdown.put("gatewayOverheadDataSource", dataSource);
        } else {
            breakdown.put("gatewayOverheadMs", 0);
            breakdown.put("gatewayOverheadPercent", 0);
            breakdown.put("gatewayOverheadDataSource", "NO_DATA");
        }

        // FIX: 网络传输时间百分比（如果没有设置则默认0）
        if (!breakdown.containsKey("networkTransferMs")) {
            breakdown.put("networkTransferMs", 0);
            breakdown.put("networkTransferSource", "NO_DATA");
        }
        long networkTransferMs = ((Number) breakdown.get("networkTransferMs")).longValue();
        breakdown.put("networkTransferPercent",
                gatewayTotalDurationMs > 0 ?
                Math.round(networkTransferMs * 100.0 / gatewayTotalDurationMs) : 0);

        // FIX: 计算其他时间 = 总耗时 - 网关开销 - 服务耗时 - 网络传输
        // 用于解释数据差额（线程调度、异步等待、精度损失等）
        long otherMs = gatewayTotalDurationMs - gatewayOverheadMs - maxServiceDurationMs - networkTransferMs;
        if (otherMs < 0) {
            otherMs = 0; // 防止负数（数据不准时）
        }
        breakdown.put("otherMs", otherMs);
        breakdown.put("otherPercent",
                gatewayTotalDurationMs > 0 ?
                Math.round(otherMs * 100.0 / gatewayTotalDurationMs) : 0);

        // 添加说明
        breakdown.put("timeModelNote", "总耗时 = 网关开销 + 服务耗时 + 网络传输 + 其他(线程调度/异步等待)");

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
