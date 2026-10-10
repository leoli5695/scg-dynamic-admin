package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.entity.TokenUsageHistoryEntity;
import com.leoli.gateway.admin.repository.TokenUsageHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 计量可观测聚合（控制台"Token 计量"页数据源）。
 * <p>
 * 维度：租户 / 路由 / 模型 / 实例 / 时间（小时·天桶）；所有维度过滤可空。
 * 数据来自网关结算后上报的 {@code token_usage_history}（见 my-gateway TokenUsageReporter）。
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageAnalyticsService {

    private final TokenUsageHistoryRepository repository;

    /** 总量卡：请求数、三类 token、平均延迟、活跃租户/路由/模型数。 */
    public Map<String, Object> overview(LocalDateTime from, LocalDateTime to,
                                        String instanceId, String routeId, String tenantId, String model) {
        List<Object[]> rows = repository.overview(from, to, instanceId, routeId, tenantId, model);
        Object[] r = rows == null || rows.isEmpty() ? null : rows.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) {
            return m;
        }
        m.put("requests", asLong(r[0]));
        m.put("promptTokens", asLong(r[1]));
        m.put("completionTokens", asLong(r[2]));
        m.put("totalTokens", asLong(r[3]));
        m.put("avgLatencyMs", asLong(r[4]));
        m.put("activeTenants", asLong(r[5]));
        m.put("activeRoutes", asLong(r[6]));
        m.put("activeModels", asLong(r[7]));
        return m;
    }

    /** 时序桶：granularity=hour|day，桶标签 + 请求数 + 三类 token。 */
    public List<Map<String, Object>> timeseries(LocalDateTime from, LocalDateTime to, String granularity,
                                                String instanceId, String routeId, String tenantId, String model) {
        boolean hourly = !"day".equalsIgnoreCase(granularity);
        List<Object[]> rows = hourly
                ? repository.timeseriesHour(from, to, instanceId, routeId, tenantId, model)
                : repository.timeseriesDay(from, to, instanceId, routeId, tenantId, model);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            int y = ((Number) r[0]).intValue();
            int mo = ((Number) r[1]).intValue();
            int da = ((Number) r[2]).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            String bucket = String.format("%04d-%02d-%02d", y, mo, da);
            int idx = 3;
            if (hourly) {
                bucket += String.format(" %02d:00", ((Number) r[3]).intValue());
                idx = 4;
            }
            m.put("bucket", bucket);
            m.put("requests", asLong(r[idx]));
            m.put("promptTokens", asLong(r[idx + 1]));
            m.put("completionTokens", asLong(r[idx + 2]));
            m.put("totalTokens", asLong(r[idx + 3]));
            out.add(m);
        }
        return out;
    }

    /** 分组榜：dimension=tenant|route|model|instance，按 totalTokens 降序。 */
    public List<Map<String, Object>> breakdown(String dimension, LocalDateTime from, LocalDateTime to, int limit,
                                               String instanceId, String routeId, String tenantId, String model) {
        var pageable = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
        List<Object[]> rows = switch (dimension == null ? "tenant" : dimension.toLowerCase()) {
            case "route" -> repository.breakdownByRoute(from, to, instanceId, routeId, tenantId, model, pageable);
            case "model" -> repository.breakdownByModel(from, to, instanceId, routeId, tenantId, model, pageable);
            case "instance" -> repository.breakdownByInstance(from, to, instanceId, routeId, tenantId, model, pageable);
            default -> repository.breakdownByTenant(from, to, instanceId, routeId, tenantId, model, pageable);
        };
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r[0] != null ? r[0].toString() : "(unknown)");
            m.put("requests", asLong(r[1]));
            m.put("promptTokens", asLong(r[2]));
            m.put("completionTokens", asLong(r[3]));
            m.put("totalTokens", asLong(r[4]));
            m.put("avgLatencyMs", asLong(r[5]));
            m.put("lastSeen", r[6] != null ? r[6].toString() : null);
            out.add(m);
        }
        return out;
    }

    /** 明细分页（时间倒序），供页面表格逐条核对（含 traceId 可回链追踪页）。 */
    public Map<String, Object> events(LocalDateTime from, LocalDateTime to, int page, int size,
                                      String instanceId, String routeId, String tenantId, String model) {
        Page<TokenUsageHistoryEntity> p = repository.events(
                from, to, instanceId, routeId, tenantId, model,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
        List<Map<String, Object>> items = new ArrayList<>();
        for (TokenUsageHistoryEntity e : p.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("requestTime", e.getRequestTime() != null ? e.getRequestTime().toString() : null);
            m.put("tenantId", e.getTenantId());
            m.put("routeId", e.getRouteId());
            m.put("model", e.getModel());
            m.put("responseFormat", e.getResponseFormat());
            m.put("promptTokens", e.getPromptTokens());
            m.put("completionTokens", e.getCompletionTokens());
            m.put("totalTokens", e.getTotalTokens());
            m.put("latencyMs", e.getLatencyMs());
            m.put("traceId", e.getTraceId());
            m.put("instanceId", e.getInstanceId());
            items.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", items);
        body.put("total", p.getTotalElements());
        body.put("page", p.getNumber());
        body.put("size", p.getSize());
        return body;
    }

    private static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return Math.round(n.doubleValue());
        }
        return 0L;
    }
}
