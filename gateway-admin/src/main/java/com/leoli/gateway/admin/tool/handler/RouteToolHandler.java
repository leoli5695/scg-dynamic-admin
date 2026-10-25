package com.leoli.gateway.admin.tool.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.RouteService;
import com.leoli.gateway.admin.validation.RouteValidator;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getBoolArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredBoolArg;

/**
 * Route CRUD tools: list routes, route detail, toggle, create, delete and modify.
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
public class RouteToolHandler {

    private final RouteService routeService;
    private final ObjectMapper objectMapper;

    public Object listRoutes(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");
        boolean enabledOnly = getBoolArg(args, "enabledOnly", false);

        List<?> routes;
        if (instanceId != null) {
            routes = routeService.getAllRoutesByInstanceId(instanceId);
        } else {
            routes = routeService.getAllRoutes();
        }

        // 如果只返回启用的路由
        if (enabledOnly) {
            routes = routes.stream()
                    .filter(r -> {
                        if (r instanceof Map) {
                            Object enabled = ((Map<?, ?>) r).get("enabled");
                            return Boolean.TRUE.equals(enabled);
                        }
                        return true;
                    })
                    .toList();
        }

        // 简化输出，只保留关键信息
        return routes.stream()
                .map(r -> {
                    if (r instanceof Map) {
                        Map<?, ?> route = (Map<?, ?>) r;
                        Map<String, Object> simplified = new LinkedHashMap<>();
                        simplified.put("routeId", route.get("routeId"));
                        simplified.put("routeName", route.get("routeName"));
                        simplified.put("uri", route.get("uri"));
                        simplified.put("enabled", route.get("enabled"));
                        simplified.put("order", route.get("order"));
                        return simplified;
                    }
                    return r;
                })
                .toList();
    }

    public Object getRouteDetail(Map<String, Object> args) {
        String routeId = getRequiredStringArg(args, "routeId");
        return routeService.getRoute(routeId);
    }

    public Object toggleRoute(Map<String, Object> args) {
        String routeId = getRequiredStringArg(args, "routeId");
        boolean enabled = getRequiredBoolArg(args, "enabled");

        if (enabled) {
            routeService.enableRouteByRouteId(routeId);
        } else {
            routeService.disableRouteByRouteId(routeId);
        }

        return Map.of(
                "success", true,
                "routeId", routeId,
                "enabled", enabled,
                "message", enabled ? "路由已启用" : "路由已禁用"
        );
    }

    /**
     * 创建路由（写操作）.
     * requiresConfirmation=true：AI 只能"提议"，真正执行必须经用户在界面确认后走
     * {@link #executeConfirmed}（见 {@link #doExecute} 的确认守卫），模型无法自确认。
     */
    public Object createRoute(Map<String, Object> args) {
        String routeJson = getRequiredStringArg(args, "routeJson");
        String instanceId = getStringArg(args, "instanceId");

        try {
            // 解析 JSON 为 RouteDefinition
            RouteDefinition route = objectMapper.readValue(routeJson, RouteDefinition.class);

            // 验证路由配置
            RouteValidator.validateAndThrow(route);

            // 创建路由
            RouteEntity entity = routeService.createRoute(route, instanceId);

            return Map.of(
                    "success", true,
                    "routeId", entity.getRouteId(),
                    "routeName", entity.getRouteName(),
                    "enabled", entity.getEnabled(),
                    "message", "路由创建成功，已推送到 Nacos，网关将自动获取最新配置"
            );

        } catch (IllegalArgumentException e) {
            log.warn("Route creation validation failed: {}", e.getMessage());
            return Map.of(
                    "success", false,
                    "error", "路由配置验证失败: " + e.getMessage()
            );
        } catch (Exception e) {
            log.error("Route creation failed", e);
            return Map.of(
                    "success", false,
                    "error", "路由创建失败: " + e.getMessage()
            );
        }
    }

    /**
     * 删除路由（写操作）.
     * requiresConfirmation=true：AI 只能"提议"删除，实际执行须经用户界面确认后走
     * {@link #executeConfirmed}。删除会从数据库和 Nacos 同时移除，可用 rollback_route 回滚。
     */
    public Object deleteRoute(Map<String, Object> args) {
        String routeId = getRequiredStringArg(args, "routeId");

        try {
            // 检查路由是否存在
            RouteDefinition route = routeService.getRoute(routeId);
            if (route == null) {
                return Map.of(
                        "success", false,
                        "error", "路由不存在: " + routeId
                );
            }

            // 删除路由
            routeService.deleteRouteByRouteId(routeId);

            return Map.of(
                    "success", true,
                    "routeId", routeId,
                    "routeName", route.getRouteName(),
                    "message", "路由已删除，配置已从 Nacos 移除，网关将自动获取最新配置"
            );

        } catch (Exception e) {
            log.error("Route deletion failed for routeId: {}", routeId, e);
            return Map.of(
                    "success", false,
                    "error", "路由删除失败: " + e.getMessage()
            );
        }
    }

    /**
     * 修改路由（写操作）.
     * requiresConfirmation=true：AI 只能"提议"修改，实际执行须经用户界面确认后走
     * {@link #executeConfirmed}，会更新数据库和 Nacos。
     */
    public Object modifyRoute(Map<String, Object> args) {
        String routeId = getRequiredStringArg(args, "routeId");
        String routeJson = getRequiredStringArg(args, "routeJson");

        try {
            // 检查路由是否存在
            RouteDefinition existingRoute = routeService.getRoute(routeId);
            if (existingRoute == null) {
                return Map.of(
                        "success", false,
                        "error", "路由不存在: " + routeId
                );
            }

            // 解析 JSON 为 RouteDefinition
            RouteDefinition route = objectMapper.readValue(routeJson, RouteDefinition.class);

            // 设置路由 ID（确保修改的是正确的路由）
            route.setId(routeId);

            // 验证路由配置
            RouteValidator.validateAndThrow(route);

            // 更新路由
            RouteEntity entity = routeService.updateRouteByRouteId(routeId, route);

            return Map.of(
                    "success", true,
                    "routeId", entity.getRouteId(),
                    "routeName", entity.getRouteName(),
                    "enabled", entity.getEnabled(),
                    "message", "路由已修改，配置已推送到 Nacos，网关将自动获取最新配置"
            );

        } catch (IllegalArgumentException e) {
            log.warn("Route modification validation failed: {}", e.getMessage());
            return Map.of(
                    "success", false,
                    "error", "路由配置验证失败: " + e.getMessage()
            );
        } catch (Exception e) {
            log.error("Route modification failed for routeId: {}", routeId, e);
            return Map.of(
                    "success", false,
                    "error", "路由修改失败: " + e.getMessage()
            );
        }
    }
}
