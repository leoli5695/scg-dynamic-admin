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
            var integrity = TraceGraphAnalysis.analyze(gatewayTraceOpt.get(), serviceTraces, objectMapper);
            result.put("integrity", integrity);
            boolean failed = gatewayTraceOpt.get().getStatusCode() != null && gatewayTraceOpt.get().getStatusCode() >= 400
                    || serviceTraces.stream().anyMatch(service -> Boolean.FALSE.equals(service.getSuccess()));
            result.put("chainStatus", Boolean.TRUE.equals(integrity.get("observedGraphValid"))
                    ? failed ? "OBSERVED_WITH_ERRORS" : "OBSERVED" : "PARTIAL");
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
        page = Math.max(0, page); size = Math.max(1, Math.min(100, size));
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
            Long totalDurationMs = gateway.getLatencyMs();
            if (totalDurationMs != null) {
                chain.put("latencyMs", totalDurationMs);
                chain.put("latencyMsSource", "PERSISTED_REQUEST");
            } else {
                chain.put("latencyMs", filterChainDurationMap.get(gateway.getTraceId()));
                chain.put("latencyMsSource", "PERSISTED_FILTER");
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
                boolean incomplete = services.stream().anyMatch(service -> !"SAMPLED".equals(service.getCaptureMode())
                        || service.getDroppedSpanCount() != null && service.getDroppedSpanCount() > 0
                        || service.getOpenSpanCount() != null && service.getOpenSpanCount() > 0);
                chain.put("chainStatus", incomplete ? "PARTIAL" : allSuccess ? "OBSERVED" : "OBSERVED_WITH_ERRORS");
                chain.put("completenessVerified", false);
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
        node.put("reportId", trace.getReportId()); node.put("captureMode", trace.getCaptureMode());
        node.put("droppedSpanCount", trace.getDroppedSpanCount()); node.put("openSpanCount", trace.getOpenSpanCount());
        node.put("startTimeMs", trace.getStartTimeMs());
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
    private void buildDurationBreakdown(Map<String, Object> result, RequestTrace gateway,
                                        List<DistributedTraceEntity> services) {
        Map<String, Object> summary = filterChainExecutionService.getTraceExecutionSummary(gateway.getTraceId());
        Map<String, Object> breakdown = new LinkedHashMap<>();
        Long total = gateway.getLatencyMs();
        breakdown.put("gatewayTotalMs", total);
        breakdown.put("gatewayTotalMsSource", total != null ? "PERSISTED_REQUEST" : "NO_DATA");
        Long self = Boolean.TRUE.equals(summary.get("hasFilterData")) && summary.get("gatewayOverheadMs") instanceof Number n ? n.longValue() : null;
        if (self != null && (self < 0 || total != null && self > total)) self = null;
        breakdown.put("gatewayOverheadMs", self);
        breakdown.put("gatewayOverheadDataSource", self == null ? "NO_DATA" : "PERSISTED_FILTER_SELF_TIME");
        breakdown.put("gatewayOverheadPercent", self != null && total != null && total > 0 ? self * 100.0 / total : null);
        breakdown.put("responseWriteMs", summary.get("networkTransferMs"));
        // Response-write wall time includes backpressure; it is not measured network RTT.
        breakdown.put("networkTransferMs", null); breakdown.put("networkTransferSource", "NOT_MEASURED");
        breakdown.put("networkTransferPercent", null); breakdown.put("otherMs", null); breakdown.put("otherPercent", null);
        List<long[]> intervals = new ArrayList<>(); boolean comparable = true;
        long start = gateway.getTraceTime() == null ? -1 : gateway.getTraceTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (var service : services) {
            if (service.getStartTimeMs() == null || service.getTotalDurationMs() == null || service.getTotalDurationMs() < 0) { comparable = false; continue; }
            long from = service.getStartTimeMs(), to;
            try { to = Math.addExact(from, service.getTotalDurationMs()); } catch (ArithmeticException error) { comparable = false; continue; }
            if (start < 0 || total == null || from < start || to > start + total) comparable = false;
            intervals.add(new long[]{from, to});
        }
        intervals.sort(Comparator.comparingLong(interval -> interval[0]));
        long union = 0, left = -1, right = -1;
        for (long[] interval : intervals) {
            if (left < 0) { left = interval[0]; right = interval[1]; }
            else if (interval[0] <= right) right = Math.max(right, interval[1]);
            else { union += right - left; left = interval[0]; right = interval[1]; }
        }
        if (left >= 0) union += right - left;
        breakdown.put("serviceDurationMs", comparable && !intervals.isEmpty() ? union : null);
        breakdown.put("servicePercent", comparable && total != null && total > 0 ? union * 100.0 / total : null);
        breakdown.put("timingComparable", comparable);
        breakdown.put("serviceDurationSource", "OBSERVED_INTERVAL_UNION");
        breakdown.put("criticalPathVerified", false);
        breakdown.put("timeModelNote", "服务时间为已观测区间并集，依赖服务时钟同步；嵌套/并行区间不重复相加。过滤器自身时间包含等待，不能等同 CPU 开销；网络和差额未测量。当前数据不能证明完整关键路径。");
        result.put("durationBreakdown", breakdown);
    }
}
