package com.leoli.gateway.admin.service.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.service.DiagnosticService;
import com.leoli.gateway.admin.service.PrometheusService;
import com.leoli.gateway.admin.service.RouteService;
import com.leoli.gateway.admin.service.ServiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ============================================================================
 * 上下文数据提供器
 * ============================================================================
 * <p>
 * 收集和预过滤 AI Copilot 所需的上下文数据。
 * <p>
 * 功能:
 * - 获取诊断摘要（健康评分、组件状态）
 * - 智能过滤相关路由（错误分析场景）
 * - 从错误信息提取路径
 * - 获取现有服务/路由名称列表
 * - 获取路由/服务数量
 * - 获取实例规格信息
 * <p>
 * 设计原则:
 * - 数据预过滤：避免 prompt 过长，只返回相关数据
 * - 智能匹配：根据错误路径关键词过滤相关路由
 * - 容错处理：数据获取失败时返回友好提示
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextDataProvider {

    private final DiagnosticService diagnosticService;
    private final RouteService routeService;
    private final ServiceService serviceService;
    private final PrometheusService prometheusService;
    private final GatewayInstanceRepository gatewayInstanceRepository;
    private final ObjectMapper objectMapper;

    // ===================== 诊断数据 =====================

    /**
     * 获取诊断摘要
     *
     * @param instanceId 实例ID（可选）
     * @return 诊断摘要 Map
     */
    public Map<String, Object> getDiagnosticsSummary(String instanceId) {
        Map<String, Object> summary = new LinkedHashMap<>();

        try {
            DiagnosticService.DiagnosticReport report = diagnosticService.runQuickDiagnostic();
            summary.put("健康评分", report.getOverallScore());
            summary.put("数据库状态", report.getDatabase() != null ? report.getDatabase().getStatus() : "未知");
            summary.put("Redis状态", report.getRedis() != null ? report.getRedis().getStatus() : "未知");
            summary.put("配置中心状态", report.getConfigCenter() != null ? report.getConfigCenter().getStatus() : "未知");
        } catch (Exception e) {
            summary.put("诊断错误", e.getMessage());
        }

        return summary;
    }

    // ===================== 路由智能过滤 =====================

    /**
     * 从错误信息中提取请求路径
     * <p>
     * 支持格式:
     * - "请求路径: /api/v2/users/123"
     * - "path: /api/v2/users/123"
     * - "No matching route found for path /api/v2/users/123"
     *
     * @param errorMessage 错误信息
     * @return 提取的路径，无法提取则返回 null
     */
    public String extractPathFromError(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return null;
        }

        String[] patterns = {
            "请求路径:", "request path:", "path:",
            "for path", "路径:", "url:", "URI:"
        };

        for (String pattern : patterns) {
            int idx = errorMessage.toLowerCase().indexOf(pattern.toLowerCase());
            if (idx >= 0) {
                int start = idx + pattern.length();
                String remaining = errorMessage.substring(start).trim();

                int pathStart = remaining.indexOf('/');
                if (pathStart >= 0) {
                    String path = remaining.substring(pathStart).trim();
                    path = path.split("[\\s,\\n\\r]")[0];
                    return path;
                }
            }
        }

        // 尝试直接找以 / 开头的路径
        int slashIdx = errorMessage.indexOf('/');
        if (slashIdx >= 0) {
            String path = errorMessage.substring(slashIdx).trim();
            path = path.split("[\\s,\\n\\r]")[0];
            return path;
        }

        return null;
    }

    /**
     * 智能过滤与错误路径相关的路由
     * <p>
     * 最多返回 5 条相关路由，避免 prompt 过长
     *
     * @param errorPath   错误路径
     * @param instanceId  实例ID（可选）
     * @return 相关路由信息字符串
     */
    public String findRelevantRoutesForError(String errorPath, String instanceId) {
        if (errorPath == null || errorPath.isEmpty()) {
            int count = getRouteCount(instanceId);
            if (count == 0) {
                return "**当前无任何路由配置**";
            } else if (count <= 10) {
                return "**路由数量较少（" + count + "条），可使用工具 `list_routes` 查看完整列表**";
            } else {
                return "**路由数量较多（" + count + "条），建议使用工具 `list_routes` 或 `get_route_detail` 查询特定路由**";
            }
        }

        try {
            List<?> allRoutes;
            if (instanceId != null && !instanceId.isEmpty()) {
                allRoutes = routeService.getAllRoutesByInstanceId(instanceId);
            } else {
                allRoutes = routeService.getAllRoutes();
            }

            if (allRoutes == null || allRoutes.isEmpty()) {
                return "**当前无任何路由配置**";
            }

            // 从错误路径提取关键词
            String[] pathSegments = errorPath.split("/");
            Set<String> keywords = new HashSet<>();
            for (String seg : pathSegments) {
                if (seg.length() >= 2) {
                    keywords.add(seg.toLowerCase());
                }
            }

            // 过滤相关路由
            List<Map<String, Object>> relevantRoutes = new ArrayList<>();
            for (Object r : allRoutes) {
                if (r instanceof Map) {
                    Map<?, ?> route = (Map<?, ?>) r;
                    String routeName = route.get("routeName") != null ? route.get("routeName").toString() : "";
                    String predicatesStr = "";

                    Object predicates = route.get("predicates");
                    if (predicates instanceof List) {
                        for (Object p : (List<?>) predicates) {
                            if (p instanceof Map) {
                                Object args = ((Map<?, ?>) p).get("args");
                                if (args instanceof Map) {
                                    Object pattern = ((Map<?, ?>) args).get("pattern");
                                    if (pattern != null) {
                                        predicatesStr += pattern.toString() + " ";
                                    }
                                }
                            }
                        }
                    }

                    boolean isRelevant = false;
                    String lowerPredicates = predicatesStr.toLowerCase();
                    String lowerName = routeName.toLowerCase();

                    for (String kw : keywords) {
                        if (lowerPredicates.contains(kw) || lowerName.contains(kw)) {
                            isRelevant = true;
                            break;
                        }
                    }

                    if (isRelevant) {
                        Map<String, Object> simplified = new LinkedHashMap<>();
                        simplified.put("routeName", routeName);
                        simplified.put("predicates", predicates);
                        simplified.put("enabled", route.get("enabled"));
                        relevantRoutes.add(simplified);
                    }

                    if (relevantRoutes.size() >= 5) {
                        break;
                    }
                }
            }

            if (relevantRoutes.isEmpty()) {
                return "**未找到与路径 `" + errorPath + "` 相关的路由配置**\n" +
                       "**可能原因**：路径不匹配任何现有路由的 predicates\n" +
                       "**建议**：使用工具 `list_routes` 查看所有路由配置";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("**与路径 `").append(errorPath).append("` 可能相关的路由（").append(relevantRoutes.size()).append("条）**:\n");

            try {
                String routesJson = objectMapper.writeValueAsString(relevantRoutes);
                sb.append("```json\n").append(routesJson).append("\n```\n");
            } catch (Exception e) {
                for (Map<String, Object> route : relevantRoutes) {
                    sb.append("- ").append(route.get("routeName")).append("\n");
                }
            }

            sb.append("\n**提示**：可使用工具 `get_route_detail` 查看完整配置，或 `list_routes` 查看所有路由");
            return sb.toString();

        } catch (Exception e) {
            log.warn("Failed to find relevant routes: {}", e.getMessage());
            return "**路由查询失败，请使用工具 `list_routes` 手动查询**";
        }
    }

    // ===================== 服务/路由名称列表 =====================

    /**
     * 获取现有服务名称列表
     *
     * @param instanceId 实例ID（可选）
     * @return 服务名称列表字符串
     */
    public String getExistingServiceNames(String instanceId) {
        try {
            List<?> services;
            if (instanceId != null && !instanceId.isEmpty()) {
                services = serviceService.getAllServicesByInstanceId(instanceId);
            } else {
                services = serviceService.getAllServices();
            }

            if (services == null || services.isEmpty()) {
                return "暂无已有服务，请用户指定服务名";
            }

            StringBuilder sb = new StringBuilder();
            for (Object s : services) {
                if (s instanceof Map) {
                    Object name = ((Map<?, ?>) s).get("name");
                    if (name != null) {
                        sb.append("- ").append(name).append("\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to get existing services: {}", e.getMessage());
            return "服务列表获取失败，请用户指定服务名";
        }
    }

    /**
     * 获取现有路由命名示例
     *
     * @param instanceId 实例ID（可选）
     * @return 路由名称示例字符串
     */
    public String getExistingRouteNameExamples(String instanceId) {
        try {
            List<?> routes;
            if (instanceId != null && !instanceId.isEmpty()) {
                routes = routeService.getAllRoutesByInstanceId(instanceId);
            } else {
                routes = routeService.getAllRoutes();
            }

            if (routes == null || routes.isEmpty()) {
                return "暂无已有路由，命名建议：xxx-api、xxx-service-route";
            }

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Object r : routes) {
                if (r instanceof Map) {
                    Object name = ((Map<?, ?>) r).get("routeName");
                    if (name != null) {
                        sb.append("- ").append(name).append("\n");
                        count++;
                        if (count >= 5) break;
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to get existing routes: {}", e.getMessage());
            return "路由列表获取失败，命名建议：xxx-api、xxx-service-route";
        }
    }

    // ===================== 数量统计 =====================

    /**
     * 获取路由数量
     *
     * @param instanceId 实例ID（可选）
     * @return 路由数量
     */
    public int getRouteCount(String instanceId) {
        try {
            List<?> routes;
            if (instanceId != null && !instanceId.isEmpty()) {
                routes = routeService.getAllRoutesByInstanceId(instanceId);
            } else {
                routes = routeService.getAllRoutes();
            }
            return routes != null ? routes.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取服务数量
     *
     * @param instanceId 实例ID（可选）
     * @return 服务数量
     */
    public int getServiceCount(String instanceId) {
        try {
            List<?> services;
            if (instanceId != null && !instanceId.isEmpty()) {
                services = serviceService.getAllServicesByInstanceId(instanceId);
            } else {
                services = serviceService.getAllServices();
            }
            return services != null ? services.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ===================== 实例信息 =====================

    /**
     * 获取实例规格信息
     *
     * @param instanceId 实例ID
     * @return 实例规格信息字符串
     */
    public String getInstanceSpecInfo(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return "实例信息未知";
        }

        try {
            Optional<GatewayInstanceEntity> entityOpt = gatewayInstanceRepository.findByInstanceId(instanceId);
            if (entityOpt.isPresent()) {
                GatewayInstanceEntity entity = entityOpt.get();
                return String.format("实例名: %s, 规格: %s, 副本数: %d",
                        entity.getInstanceName(),
                        entity.getSpecType() != null ? entity.getSpecType() : "未知",
                        entity.getReplicas() != null ? entity.getReplicas() : 0);
            }
        } catch (Exception e) {
            log.warn("Failed to get instance spec: {}", e.getMessage());
        }

        return "实例信息获取失败";
    }

    // ===================== 监控指标 =====================

    /**
     * 获取网关监控指标
     *
     * @return 监控指标 Map
     */
    public Map<String, Object> getGatewayMetrics() {
        try {
            return prometheusService.getGatewayMetrics();
        } catch (Exception e) {
            log.warn("Failed to get gateway metrics: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}