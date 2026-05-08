package com.seckill.interceptor;

import com.seckill.annotation.InternalApi;
import com.seckill.config.SeckillConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * ============================================================================
 * 内部 API 拦截器
 * ============================================================================
 *
 * 功能：
 * 1. 检查 @InternalApi 注解的接口
 * 2. 校验调用方 IP 是否在白名单中
 * 3. 非白名单 IP 返回 403 Forbidden
 *
 * IP 白名单支持格式：
 * - 单个 IP：127.0.0.1
 * - CIDR 网段：10.0.0.0/8（表示 10.0.0.0 - 10.255.255.255）
 * - IPv6：::1（本地回环）
 */
@Slf4j
@RequiredArgsConstructor
public class InternalApiInterceptor implements HandlerInterceptor {

    private final SeckillConfig seckillConfig;
    private final Environment environment;

    /**
     * 本地回环地址白名单
     */
    private static final Set<String> LOCAL_IPS = new HashSet<>(Arrays.asList(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost"
    ));

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 非 Controller 方法，直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 检查是否有 @InternalApi 注解
        InternalApi annotation = handlerMethod.getMethodAnnotation(InternalApi.class);
        if (annotation == null) {
            return true;  // 无注解，放行
        }

        // 获取配置
        SeckillConfig.InternalApiConfig config = seckillConfig.getInternalApi();
        if (config == null || !config.isEnabled()) {
            log.warn("@InternalApi 接口访问: 功能未启用，建议生产环境开启 - uri={}", request.getRequestURI());
            return true;  // 功能未启用，放行（但告警）
        }

        // 获取调用方 IP
        String clientIp = getClientIp(request);
        log.debug("@InternalApi 接口访问: uri={}, clientIp={}, description={}",
                request.getRequestURI(), clientIp, annotation.description());

        // 开发环境绕过校验（可选）
        if (annotation.allowDevBypass() && isDevEnvironment()) {
            log.info("@InternalApi 开发环境绕过: uri={}, clientIp={}", request.getRequestURI(), clientIp);
            return true;
        }

        // IP 白名单校验
        if (isIpAllowed(clientIp, config.getWhitelist())) {
            log.info("@InternalApi 接口访问成功: uri={}, clientIp={}", request.getRequestURI(), clientIp);
            return true;
        }

        // 拒绝访问
        log.warn("@InternalApi 接口访问被拒绝: uri={}, clientIp={}, whitelist={}",
                request.getRequestURI(), clientIp, config.getWhitelist());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"Forbidden: Internal API requires IP whitelist\"}");

        return false;
    }

    /**
     * 获取客户端真实 IP
     * 支持 Nginx/网关代理场景：优先取 X-Forwarded-For 或 X-Real-IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For 可能包含多个 IP，取第一个
            int index = ip.indexOf(',');
            if (index != -1) {
                return ip.substring(0, index).trim();
            }
            return ip.trim();
        }

        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }

        ip = request.getHeader("Proxy-Client-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }

        // 最后 fallback 到 RemoteAddr
        return request.getRemoteAddr();
    }

    /**
     * 检查 IP 是否在白名单中
     * 支持 CIDR 网段匹配
     */
    private boolean isIpAllowed(String clientIp, String whitelist) {
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }

        // 本地回环地址始终允许
        if (LOCAL_IPS.contains(clientIp)) {
            return true;
        }

        // 解析白名单（逗号分隔）
        String[] allowedPatterns = whitelist.split(",");
        for (String pattern : allowedPatterns) {
            pattern = pattern.trim();

            if (pattern.isEmpty()) {
                continue;
            }

            // CIDR 网段匹配
            if (pattern.contains("/")) {
                if (isIpInCidr(clientIp, pattern)) {
                    return true;
                }
            }

            // 精确匹配
            if (pattern.equals(clientIp)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查 IP 是否在 CIDR 网段中
     * 仅支持 IPv4 CIDR（如 10.0.0.0/8）
     */
    private boolean isIpInCidr(String ip, String cidr) {
        try {
            String[] cidrParts = cidr.split("/");
            String networkAddress = cidrParts[0];
            int prefixLength = Integer.parseInt(cidrParts[1]);

            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            byte[] networkBytes = InetAddress.getByName(networkAddress).getAddress();

            // 仅支持 IPv4
            if (ipBytes.length != 4 || networkBytes.length != 4) {
                return false;
            }

            int mask = 0xFFFFFFFF << (32 - prefixLength);

            int ipInt = ((ipBytes[0] & 0xFF) << 24)
                    | ((ipBytes[1] & 0xFF) << 16)
                    | ((ipBytes[2] & 0xFF) << 8)
                    | (ipBytes[3] & 0xFF);

            int networkInt = ((networkBytes[0] & 0xFF) << 24)
                    | ((networkBytes[1] & 0xFF) << 16)
                    | ((networkBytes[2] & 0xFF) << 8)
                    | (networkBytes[3] & 0xFF);

            return (ipInt & mask) == (networkInt & mask);

        } catch (UnknownHostException e) {
            log.warn("CIDR 匹配失败: ip={}, cidr={}, error={}", ip, cidr, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("CIDR 格式错误: cidr={}", cidr);
            return false;
        }
    }

    /**
     * 检查是否为开发环境
     */
    private boolean isDevEnvironment() {
        String[] activeProfiles = environment.getActiveProfiles();
        for (String profile : activeProfiles) {
            if ("dev".equalsIgnoreCase(profile) || "local".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}