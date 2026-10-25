package com.leoli.gateway.admin.tool.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.RouteService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getBoolArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredLongArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredBoolArg;

/**
 * Route operations beyond CRUD: batch enable/disable, rollback to an audited version, and route-match simulation (header/query parsing, path-pattern matching).
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
public class RouteOpsToolHandler {

    private final RouteService routeService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * 批量启用/禁用路由.
     */
    public Object batchToggleRoutes(Map<String, Object> args) {
        String routeIdsStr = getRequiredStringArg(args, "routeIds");
        boolean enabled = getRequiredBoolArg(args, "enabled");

        String[] routeIds = routeIdsStr.split(",");
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String routeId : routeIds) {
            routeId = routeId.trim();
            try {
                if (enabled) {
                    routeService.enableRouteByRouteId(routeId);
                } else {
                    routeService.disableRouteByRouteId(routeId);
                }
                results.add(Map.of(
                        "routeId", routeId,
                        "success", true,
                        "enabled", enabled
                ));
                successCount++;
            } catch (Exception e) {
                results.add(Map.of(
                        "routeId", routeId,
                        "success", false,
                        "error", e.getMessage()
                ));
                failCount++;
            }
        }

        return Map.of(
                "success", true,
                "totalRoutes", routeIds.length,
                "successCount", successCount,
                "failCount", failCount,
                "results", results,
                "message", String.format("批量操作完成：成功 %d 个，失败 %d 个", successCount, failCount)
        );
    }

    /**
     * 路由配置回滚.
     * 通过审计日志 ID 回滚到历史版本。
     * 支持事务、并发检测和版本校验。
     */
    public Object rollbackRoute(Map<String, Object> args) {
        Long logId = getRequiredLongArg(args, "logId");
        boolean skipVersionCheck = getBoolArg(args, "skipVersionCheck", false);

        try {
            // 获取审计日志详情
            Map<String, Object> diff = auditLogService.getDiff(logId);
            if (diff.containsKey("error")) {
                return Map.of("error", "审计日志不存在: " + logId);
            }

            // 检查是否是路由相关的操作
            String targetType = (String) diff.get("targetType");
            if (!"ROUTE".equals(targetType)) {
                return Map.of("error", "此审计日志不是路由操作（类型: " + targetType + "），无法回滚路由");
            }

            String routeId = (String) diff.get("targetId");
            String operationType = (String) diff.get("operationType");
            String operator = (String) diff.get("operator");
            LocalDateTime logCreatedAt = (LocalDateTime) diff.get("createdAt");

            // 获取当前路由状态
            RouteDefinition currentRoute = routeService.getRoute(routeId);
            Object newValue = diff.get("newValue");

            // 版本校验：检查当前路由是否与审计日志中的 newValue 一致
            // 如果不一致，说明路由已被其他操作修改
            if (!skipVersionCheck && newValue != null && currentRoute != null) {
                RouteDefinition expectedState = objectMapper.convertValue(newValue, RouteDefinition.class);
                if (!isRouteVersionMatch(currentRoute, expectedState)) {
                    return Map.of(
                            "error", "版本冲突：路由已被其他操作修改",
                            "detail", Map.of(
                                    "auditLogId", logId,
                                    "auditLogTime", logCreatedAt != null ? logCreatedAt.toString() : "unknown",
                                    "auditOperator", operator != null ? operator : "unknown",
                                    "currentRouteName", currentRoute.getRouteName(),
                                    "expectedRouteName", expectedState.getRouteName(),
                                    "suggestion", "路由可能已被其他操作修改，请确认后再回滚。如需强制回滚，设置 skipVersionCheck=true"
                            )
                    );
                }
            }

            // 获取操作前的快照（beforeValue）
            Object beforeSnapshot = diff.get("oldValue");
            if (beforeSnapshot == null) {
                // 如果是 CREATE 操作，beforeValue 为空，回滚意味着删除
                if ("CREATE".equals(operationType)) {
                    if (currentRoute == null) {
                        return Map.of(
                                "success", true,
                                "action", "no_action",
                                "routeId", routeId,
                                "message", "路由已不存在，无需回滚"
                        );
                    }
                    // 使用 AuditLogService.rollback() 执行事务回滚
                    Map<String, Object> rollbackResult = auditLogService.rollback(logId, "AI_COPILOT");
                    if (Boolean.TRUE.equals(rollbackResult.get("success"))) {
                        return Map.of(
                                "success", true,
                                "action", "deleted",
                                "routeId", routeId,
                                "auditLogId", rollbackResult.get("auditLogId"),
                                "message", "回滚成功：已删除新创建的路由"
                        );
                    } else {
                        return Map.of("error", "回滚删除失败: " + rollbackResult.get("message"));
                    }
                }
                return Map.of("error", "无法回滚：缺少历史版本数据（操作类型: " + operationType + "）");
            }

            // 解析历史版本配置
            RouteDefinition historicalRoute = objectMapper.convertValue(beforeSnapshot, RouteDefinition.class);
            historicalRoute.setId(routeId);  // 保持原有 ID

            // 执行回滚（使用 AuditLogService.rollback 的事务支持）
            Map<String, Object> rollbackResult = auditLogService.rollback(logId, "AI_COPILOT");

            if (!Boolean.TRUE.equals(rollbackResult.get("success"))) {
                return Map.of("error", "回滚失败: " + rollbackResult.get("message"));
            }

            // 构建成功结果
            String actionType;
            String message;
            if (currentRoute == null) {
                actionType = "created";
                message = "回滚成功：已恢复被删除的路由";
            } else {
                actionType = "restored";
                message = "回滚成功：路由已恢复到历史版本";
            }

            return Map.of(
                    "success", true,
                    "action", actionType,
                    "routeId", routeId,
                    "routeName", historicalRoute.getRouteName(),
                    "auditLogId", rollbackResult.get("auditLogId"),
                    "rolledBackFrom", Map.of(
                            "auditLogId", logId,
                            "operationType", operationType,
                            "operator", operator,
                            "createdAt", logCreatedAt != null ? logCreatedAt.toString() : "unknown"
                    ),
                    "message", message
            );

        } catch (IllegalArgumentException e) {
            log.warn("Rollback validation failed for logId: {}", logId, e);
            return Map.of("error", "回滚校验失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Rollback route failed for logId: {}", logId, e);
            return Map.of("error", "回滚失败: " + e.getMessage());
        }
    }

    /**
     * 检查路由版本是否匹配.
     * 用于检测并发修改。
     */
    private boolean isRouteVersionMatch(RouteDefinition current, RouteDefinition expected) {
        if (current == null || expected == null) {
            return current == expected;
        }
        // 比较关键字段
        return Objects.equals(current.getId(), expected.getId())
                && Objects.equals(current.getRouteName(), expected.getRouteName())
                && Objects.equals(current.getUri(), expected.getUri());
    }

    /**
     * 模拟路由匹配.
     * 输入 URL，返回会匹配到的路由。
     * 支持 Path、Method、Header、Query 参数匹配。
     */
    public Object simulateRouteMatch(Map<String, Object> args) {
        String url = getRequiredStringArg(args, "url");
        String instanceId = getStringArg(args, "instanceId");

        try {
            // 获取所有路由
            List<RouteResponse> routes = routeService.getAllRoutes();

            // 解析 URL 和请求参数
            String path = extractPath(url);
            String method = getStringArg(args, "method", "GET").toUpperCase();

            // 解析 Headers（可选）
            Map<String, String> headers = parseHeadersArg(args.get("headers"));

            // 解析 Query 参数（可选）- 如果未提供，从 URL 中提取
            Object queryParamsArg = args.get("queryParams");
            Map<String, String> queryParams;
            if (queryParamsArg != null) {
                queryParams = parseQueryParamsArg(queryParamsArg);
            } else {
                queryParams = extractQueryFromUrl(url);
            }

            // 匹配路由
            List<Map<String, Object>> matchedRoutes = new ArrayList<>();
            List<Map<String, Object>> partialMatches = new ArrayList<>();  // 部分匹配（用于调试）

            for (RouteResponse route : routes) {
                if (!Boolean.TRUE.equals(route.getEnabled())) {
                    continue;  // 跳过禁用的路由
                }

                // 检查路由匹配
                MatchResult matchResult = checkRouteMatchDetailed(route, path, method, headers, queryParams);

                if (matchResult.isFullMatch()) {
                    Map<String, Object> matchInfo = new LinkedHashMap<>();
                    matchInfo.put("routeId", route.getId());
                    matchInfo.put("routeName", route.getRouteName());
                    matchInfo.put("uri", route.getUri());
                    matchInfo.put("order", route.getOrder());
                    matchInfo.put("matchedPath", path);
                    matchInfo.put("matchedPredicates", matchResult.getMatchedPredicates());
                    matchInfo.put("predicates", route.getPredicates());
                    matchedRoutes.add(matchInfo);
                } else if (matchResult.hasPartialMatch()) {
                    // 记录部分匹配的路由（帮助调试）
                    Map<String, Object> partialInfo = new LinkedHashMap<>();
                    partialInfo.put("routeId", route.getId());
                    partialInfo.put("routeName", route.getRouteName());
                    partialInfo.put("order", route.getOrder());
                    partialInfo.put("mismatchReason", matchResult.getMismatchReason());
                    partialInfo.put("matchedPredicates", matchResult.getMatchedPredicates());
                    partialMatches.add(partialInfo);
                }
            }

            // 按 order 排序
            matchedRoutes.sort((a, b) -> {
                Integer orderA = (Integer) a.get("order");
                Integer orderB = (Integer) b.get("order");
                if (orderA == null) orderA = 0;
                if (orderB == null) orderB = 0;
                return orderA.compareTo(orderB);
            });

            partialMatches.sort((a, b) -> {
                Integer orderA = (Integer) a.get("order");
                Integer orderB = (Integer) b.get("order");
                if (orderA == null) orderA = 0;
                if (orderB == null) orderB = 0;
                return orderA.compareTo(orderB);
            });

            // 构建请求摘要
            Map<String, Object> requestSummary = new LinkedHashMap<>();
            requestSummary.put("path", path);
            requestSummary.put("method", method);
            if (!headers.isEmpty()) {
                requestSummary.put("headers", headers);
            }
            if (!queryParams.isEmpty()) {
                requestSummary.put("queryParams", queryParams);
            }

            if (matchedRoutes.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("matched", false);
                result.put("url", url);
                result.put("request", requestSummary);
                if (!partialMatches.isEmpty()) {
                    result.put("partialMatches", partialMatches);
                }
                result.put("message", partialMatches.isEmpty()
                        ? "没有匹配到任何路由，请求将返回 404"
                        : String.format("没有完全匹配的路由，但有 %d 个部分匹配（可能缺少 Method/Header/Query 条件）", partialMatches.size()));
                return result;
            }

            // 返回匹配结果（优先级最高的路由）
            Map<String, Object> bestMatch = matchedRoutes.get(0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matched", true);
            result.put("url", url);
            result.put("request", requestSummary);
            result.put("matchedRoutes", matchedRoutes);
            result.put("bestMatch", bestMatch);
            result.put("totalMatches", matchedRoutes.size());
            if (!partialMatches.isEmpty()) {
                result.put("partialMatches", partialMatches);
            }
            result.put("message", String.format("匹配到 %d 个路由，优先级最高的是: %s",
                    matchedRoutes.size(), bestMatch.get("routeName")));
            return result;

        } catch (Exception e) {
            log.error("Simulate route match failed for URL: {}", url, e);
            return Map.of("error", "模拟匹配失败: " + e.getMessage());
        }
    }

    /**
     * 解析 Headers 参数.
     */
    private Map<String, String> parseHeadersArg(Object headersArg) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headersArg == null) {
            return headers;
        }
        if (headersArg instanceof Map) {
            Map<?, ?> headersMap = (Map<?, ?>) headersArg;
            for (Map.Entry<?, ?> entry : headersMap.entrySet()) {
                headers.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        } else if (headersArg instanceof String) {
            // 支持字符串格式：Header1:Value1,Header2:Value2
            String[] pairs = ((String) headersArg).split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    headers.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return headers;
    }

    /**
     * 解析 Query 参数.
     */
    private Map<String, String> parseQueryParamsArg(Object queryParamsArg) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (queryParamsArg == null) {
            return queryParams;  // 返回空 Map，调用方会从 URL 中提取
        }
        if (queryParamsArg instanceof Map) {
            Map<?, ?> paramsMap = (Map<?, ?>) queryParamsArg;
            for (Map.Entry<?, ?> entry : paramsMap.entrySet()) {
                queryParams.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        } else if (queryParamsArg instanceof String) {
            // 支持字符串格式：param1=value1&param2=value2
            String[] pairs = ((String) queryParamsArg).split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    queryParams.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return queryParams;
    }

    /**
     * 从 URL 中提取 Query 参数.
     */
    private Map<String, String> extractQueryFromUrl(String url) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (url == null || !url.contains("?")) {
            return queryParams;
        }
        String queryString = url.substring(url.indexOf("?") + 1);
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length >= 1) {
                String key = kv[0].trim();
                String value = kv.length >= 2 ? kv[1].trim() : "";
                queryParams.put(key, value);
            }
        }
        return queryParams;
    }

    /**
     * 详细检查路由匹配（支持多种 Predicate 类型）.
     */
    private MatchResult checkRouteMatchDetailed(RouteResponse route, String path, String method,
                                                Map<String, String> headers, Map<String, String> queryParams) {
        MatchResult result = new MatchResult();

        try {
            List<?> predicates = route.getPredicates();
            if (predicates == null || predicates.isEmpty()) {
                result.setMismatchReason("路由没有定义任何 Predicate");
                return result;
            }

            boolean hasPathPredicate = false;
            boolean pathMatched = false;

            for (Object predObj : predicates) {
                String name = null;
                Map<?, ?> args = null;

                if (predObj instanceof RouteDefinition.PredicateDefinition) {
                    RouteDefinition.PredicateDefinition predicate = (RouteDefinition.PredicateDefinition) predObj;
                    name = predicate.getName();
                    args = predicate.getArgs();
                } else if (predObj instanceof Map) {
                    Map<?, ?> predicate = (Map<?, ?>) predObj;
                    name = (String) predicate.get("name");
                    args = (Map<?, ?>) predicate.get("args");
                }

                if (name == null) {
                    continue;
                }

                switch (name) {
                    case "Path":
                        hasPathPredicate = true;
                        if (args != null) {
                            String pattern = (String) args.get("pattern");
                            if (pattern != null && matchPathPattern(pattern, path)) {
                                pathMatched = true;
                                result.addMatchedPredicate("Path", Map.of("pattern", pattern, "actual", path));
                            } else {
                                result.addMismatchDetail("Path", "Pattern '" + pattern + "' does not match path '" + path + "'");
                            }
                        }
                        break;

                    case "Method":
                        if (args != null) {
                            String methods = (String) args.get("methods");
                            if (methods == null) {
                                methods = (String) args.get("_key_0");  // Spring Cloud Gateway 格式
                            }
                            if (methods != null) {
                                String[] allowedMethods = methods.split(",");
                                boolean methodMatched = Arrays.stream(allowedMethods)
                                        .anyMatch(m -> m.trim().equalsIgnoreCase(method));
                                if (methodMatched) {
                                    result.addMatchedPredicate("Method", Map.of("allowed", methods, "actual", method));
                                } else {
                                    result.addMismatchDetail("Method", "Method '" + method + "' not in allowed methods: " + methods);
                                }
                            }
                        }
                        break;

                    case "Header":
                        if (args != null && !headers.isEmpty()) {
                            String headerName = (String) args.get("header");
                            if (headerName == null) {
                                headerName = (String) args.get("_key_0");
                            }
                            String headerPattern = (String) args.get("regexp");
                            if (headerName != null) {
                                String actualValue = headers.get(headerName);
                                if (actualValue != null) {
                                    if (headerPattern == null || actualValue.matches(headerPattern)) {
                                        result.addMatchedPredicate("Header", Map.of("header", headerName, "actual", actualValue));
                                    } else {
                                        result.addMismatchDetail("Header", "Header '" + headerName + "' value '" + actualValue + "' does not match regex '" + headerPattern + "'");
                                    }
                                } else {
                                    result.addMismatchDetail("Header", "Missing required header: " + headerName);
                                }
                            }
                        }
                        break;

                    case "Query":
                        if (args != null && !queryParams.isEmpty()) {
                            String queryParam = (String) args.get("param");
                            if (queryParam == null) {
                                queryParam = (String) args.get("_key_0");
                            }
                            String queryPattern = (String) args.get("regexp");
                            if (queryParam != null) {
                                String actualValue = queryParams.get(queryParam);
                                if (actualValue != null) {
                                    if (queryPattern == null || actualValue.matches(queryPattern)) {
                                        result.addMatchedPredicate("Query", Map.of("param", queryParam, "actual", actualValue));
                                    } else {
                                        result.addMismatchDetail("Query", "Query param '" + queryParam + "' value '" + actualValue + "' does not match regex '" + queryPattern + "'");
                                    }
                                } else {
                                    result.addMismatchDetail("Query", "Missing required query param: " + queryParam);
                                }
                            }
                        }
                        break;

                    default:
                        // 其他 Predicate 类型暂不处理
                        log.debug("Unsupported predicate type: {}", name);
                        break;
                }
            }

            // 必须至少有 Path Predicate 且匹配
            if (!hasPathPredicate) {
                result.setMismatchReason("路由没有 Path Predicate");
            } else if (!pathMatched) {
                result.setMismatchReason("Path 不匹配");
            } else {
                // Path 匹配后，检查是否有其他 Predicate 不匹配
                if (result.getMismatchDetails().isEmpty()) {
                    result.setFullMatch(true);
                } else {
                    result.setMismatchReason("部分 Predicate 不匹配: " + result.getMismatchDetails());
                }
            }

        } catch (Exception e) {
            log.warn("Failed to check detailed route match for route: {}", route.getId(), e);
            result.setMismatchReason("匹配检查异常: " + e.getMessage());
        }

        return result;
    }

    /**
     * 匹配结果.
     */
    private static class MatchResult {
        private boolean fullMatch = false;
        private boolean partialMatch = false;  // Path 匹配但其他条件不匹配
        private String mismatchReason;
        private List<Map<String, Object>> matchedPredicates = new ArrayList<>();
        private List<String> mismatchDetails = new ArrayList<>();

        public boolean isFullMatch() {
            return fullMatch;
        }

        public void setFullMatch(boolean fullMatch) {
            this.fullMatch = fullMatch;
            this.partialMatch = !fullMatch && !matchedPredicates.isEmpty();
        }

        public boolean hasPartialMatch() {
            return partialMatch;
        }

        public String getMismatchReason() {
            return mismatchReason;
        }

        public void setMismatchReason(String mismatchReason) {
            this.mismatchReason = mismatchReason;
        }

        public List<Map<String, Object>> getMatchedPredicates() {
            return matchedPredicates;
        }

        public List<String> getMismatchDetails() {
            return mismatchDetails;
        }

        public void addMatchedPredicate(String type, Map<String, Object> details) {
            Map<String, Object> pred = new LinkedHashMap<>();
            pred.put("type", type);
            pred.put("details", details);
            matchedPredicates.add(pred);
            partialMatch = true;
        }

        public void addMismatchDetail(String type, String detail) {
            mismatchDetails.add(type + ": " + detail);
        }
    }

    /**
     * 从 URL 中提取 path.
     */
    private String extractPath(String url) {
        if (url == null || url.isEmpty()) {
            return "/";
        }
        // 如果是完整 URL，提取 path 部分
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                int pathStart = url.indexOf("/", url.indexOf("://") + 3);
                if (pathStart > 0) {
                    return url.substring(pathStart);
                }
                return "/";
            }
            // 如果已经是 path，直接返回
            return url.startsWith("/") ? url : "/" + url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 检查路由是否匹配（简化版，用于批量匹配）.
     */
    private boolean checkRouteMatch(RouteResponse route, String path, String method) {
        MatchResult result = checkRouteMatchDetailed(route, path, method, Map.of(), Map.of());
        return result.isFullMatch();
    }

    /**
     * 简单的 path pattern 匹配（支持 ** 和 *）.
     */
    private boolean matchPathPattern(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }

        // 精确匹配
        if (pattern.equals(path)) {
            return true;
        }

        // ** 匹配任意字符
        if (pattern.endsWith("**")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (path.startsWith(prefix)) {
                return true;
            }
        }

        // * 匹配单级
        if (pattern.contains("*") && !pattern.contains("**")) {
            String regex = pattern.replace("*", "[^/]+");
            return path.matches(regex);
        }

        return false;
    }
}
