package com.example.gateway.filter;

import com.example.gateway.enums.StrategyType;
import com.example.gateway.manager.StrategyManager;
import com.example.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * IP Filter Global Filter
 * <p>
 * Supports blacklist and whitelist modes with CIDR notation.
 * </p>
 *
 * @author leoli
 */
@Slf4j
@Component
public class IPFilterGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    /**
     * Check if IP is in the list (supports CIDR notation)
     */
    private boolean isIPInRange(String ip, List<String> ipList) {
        for (String allowed : ipList) {
            if (allowed.contains("/")) {
                // CIDR notation
                if (isIPInCIDR(ip, allowed)) {
                    return true;
                }
            } else {
                // Exact match
                if (allowed.equals(ip)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if IP is within CIDR range
     */
    private boolean isIPInCIDR(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String networkAddress = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            long ipLong = ipToLong(networkAddress);
            long mask = -1L << (32 - prefixLength);
            long ipNetworkLong = ipToLong(ip) & mask;

            return ipNetworkLong == (ipLong & mask);
        } catch (Exception e) {
            log.warn("Invalid CIDR format: {}", cidr, e);
            return false;
        }
    }

    /**
     * Convert IP address to long
     */
    private long ipToLong(String ipAddress) {
        String[] octets = ipAddress.split("\\.");
        long result = 0;

        for (int i = 0; i < 4; i++) {
            result = (result << 8) + Integer.parseInt(octets[i]);
        }

        return result;
    }

    /**
     * Get client IP address from exchange
     */
    private String getClientIp(ServerWebExchange exchange) {
        // Check X-Forwarded-For header first
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }

        // Fallback to remote address
        var addr = exchange.getRequest().getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }

    /**
     * Write forbidden response
     */
    private Mono<Void> writeForbiddenResponse(ServerWebExchange exchange, String message) {
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        String body = "{\"error\":\"Forbidden\",\"message\":\"" + message + "\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
        );
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        String clientIp = getClientIp(exchange);

        // Get IP filter config for this route
        Map<String, Object> ipFilterConfig = strategyManager.getConfig(StrategyType.IP_FILTER, routeId);

        if (ipFilterConfig != null && !ipFilterConfig.isEmpty()) {
            String mode = (String) ipFilterConfig.get("mode");
            @SuppressWarnings("unchecked")
            List<String> ipList = (List<String>) ipFilterConfig.get("ipList");
            Boolean enabled = (Boolean) ipFilterConfig.get("enabled");

            if (Boolean.TRUE.equals(enabled) && ipList != null && !ipList.isEmpty()) {
                boolean ipInRange = isIPInRange(clientIp, ipList);

                if ("blacklist".equals(mode)) {
                    // Blacklist mode: reject if IP is in the list
                    if (ipInRange) {
                        log.warn("IP {} blocked by blacklist for route {}", clientIp, routeId);
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return writeForbiddenResponse(exchange, "IP address blocked");
                    }
                } else if ("whitelist".equals(mode)) {
                    // Whitelist mode: reject if IP is NOT in the list
                    if (!ipInRange) {
                        log.warn("IP {} not in whitelist for route {}", clientIp, routeId);
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return writeForbiddenResponse(exchange, "IP address not allowed");
                    }
                }
            }
        }

        log.debug("Request from IP: {} for route: {}", clientIp, routeId);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // High priority - execute before authentication
        // IP filtering is coarse-grained protection, should run first to block malicious IPs
        // This avoids unnecessary authentication computation for blocked IPs
        return -280;
    }
}
