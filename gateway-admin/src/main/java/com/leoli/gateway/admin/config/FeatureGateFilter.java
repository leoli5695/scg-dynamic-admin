package com.leoli.gateway.admin.config;

import com.leoli.gateway.admin.service.SystemSettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects expensive write paths for features switched off in system settings.
 * Read-only history stays reachable; the console also hides the entries.
 */
@Component
@RequiredArgsConstructor
public class FeatureGateFilter extends OncePerRequestFilter {

    private final SystemSettingsService settings;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String feature = requiredFeature(request.getMethod(), request.getRequestURI());
        if (feature != null && !enabled(feature)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"Feature disabled in system settings: " + feature + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String requiredFeature(String method, String path) {
        boolean write = !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);
        if (write && (path.equals("/api/stress-test/start") || path.equals("/api/stress-test/quick"))) {
            return "stressTest";
        }
        if (write && (path.startsWith("/api/plugins/") || path.startsWith("/api/plugin-bindings"))) {
            return "plugins";
        }
        if (path.startsWith("/api/kubernetes")) {
            return "kubernetes";
        }
        return null;
    }

    private boolean enabled(String feature) {
        return switch (feature) {
            case "stressTest" -> settings.isStressTestEnabled();
            case "plugins" -> settings.isPluginsEnabled();
            case "kubernetes" -> settings.isKubernetesEnabled();
            case "middleware" -> settings.isMiddlewareEnabled();
            default -> true;
        };
    }
}