package com.leoli.gateway.filter;

import com.leoli.gateway.config.TrustedProxyProperties;
import com.leoli.gateway.enums.StrategyType;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/**
 * IP Filter Global Filter
 * <p>
 * Supports blacklist and whitelist modes with CIDR notation.
 * Implements trusted proxy validation for X-Forwarded-For header.
 * </p>
 *
 * @author leoli
 */
@Slf4j
@Component
public class IPFilterGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Autowired
    private TrustedProxyProperties trustedProxyProperties;

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
        // Handle IPv6 addresses (use localhost mapping for simplicity)
        if (ipAddress.contains(":")) {
            // IPv6 loopback
            if ("0:0:0:0:0:0:0:1".equals(ipAddress) || "::1".equals(ipAddress)) {
                return ipToLong("127.0.0.1");
            }
            // For other IPv6, extract IPv4 if embedded, otherwise use hash
            if (ipAddress.startsWith("::ffff:")) {
                return ipToLong(ipAddress.substring(7));
            }
            // Default to 0 for non-IPv4-mapped IPv6
            log.debug("IPv6 address not fully supported for CIDR matching: {}", ipAddress);
            return 0;
        }

        String[] octets = ipAddress.split("\\.");
        if (octets.length != 4) {
            log.warn("Invalid IP address format: {}", ipAddress);
            return 0;
        }

        long result = 0;
        for (int i = 0; i < 4; i++) {
            result = (result << 8) + Integer.parseInt(octets[i]);
        }

        return result;
    }

    /**
     * Get client IP address from exchange.
     * <p>
     * Security-aware implementation that validates X-Forwarded-For header
     * against trusted proxy configuration.
     * </p>
     */
    private String getClientIp(ServerWebExchange exchange) {
        // Get the direct remote address (the actual TCP connection source)
        String remoteIp = getRemoteAddress(exchange);

        // If trusted proxy feature is disabled, always use direct remote address
        if (!trustedProxyProperties.isEnabled()) {
            log.debug("Trusted proxy disabled, using direct remote IP: {}", remoteIp);
            return remoteIp;
        }

        // Check if request comes from a trusted proxy
        if (isTrustedProxy(remoteIp)) {
            // Request is from trusted proxy, check X-Forwarded-For
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");

            if (forwarded != null && !forwarded.isEmpty()) {
                String clientIp = extractForwardedIp(forwarded);
                log.debug("Request from trusted proxy {}, extracted client IP: {}", remoteIp, clientIp);
                return clientIp;
            } else {
                log.debug("Request from trusted proxy {} but no X-Forwarded-For header, using remote IP", remoteIp);
            }
        } else {
            // Request is NOT from trusted proxy, ignore X-Forwarded-For to prevent spoofing
            log.debug("Request from non-trusted proxy {}, ignoring X-Forwarded-For header", remoteIp);
        }

        return remoteIp;
    }

    /**
     * Get the remote address from exchange
     */
    private String getRemoteAddress(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    /**
     * Check if the IP is from a trusted proxy
     */
    private boolean isTrustedProxy(String ip) {
        // Check configured proxy IPs
        List<String> proxyIps = trustedProxyProperties.getProxyIps();
        if (proxyIps != null && !proxyIps.isEmpty()) {
            if (isIPInRange(ip, proxyIps)) {
                return true;
            }
        }

        // Check if private networks are trusted
        if (trustedProxyProperties.isTrustPrivateNetworks()) {
            return isPrivateNetworkIP(ip);
        }

        return false;
    }

    /**
     * Check if IP belongs to private networks (RFC 1918)
     * - 10.0.0.0/8
     * - 172.16.0.0/12
     * - 192.168.0.0/16
     * - 127.0.0.0/8 (loopback)
     */
    private boolean isPrivateNetworkIP(String ip) {
        // Handle IPv6 loopback
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return true;
        }

        // Only handle IPv4 for CIDR matching
        if (ip.contains(":")) {
            return false;
        }

        return isIPInCIDR(ip, "10.0.0.0/8") ||
               isIPInCIDR(ip, "172.16.0.0/12") ||
               isIPInCIDR(ip, "192.168.0.0/16") ||
               isIPInCIDR(ip, "127.0.0.0/8");
    }

    /**
     * Extract IP from X-Forwarded-For header based on configured position.
     * Handles multiple IPs separated by comma.
     */
    private String extractForwardedIp(String forwarded) {
        if (forwarded == null || forwarded.isEmpty()) {
            return "unknown";
        }

        // Split by comma and trim
        String[] ips = forwarded.split(",");
        int maxIps = Math.min(ips.length, trustedProxyProperties.getMaxForwardedIps());

        // Extract IP based on position configuration
        String ip;
        if (trustedProxyProperties.getPosition() == TrustedProxyProperties.ForwardedIpPosition.LAST) {
            ip = ips[Math.min(maxIps - 1, ips.length - 1)].trim();
        } else {
            // Default: FIRST
            ip = ips[0].trim();
        }

        // Remove port if present (e.g., "192.168.1.1:8080")
        if (ip.contains(":") && !ip.startsWith("[")) {
            ip = ip.substring(0, ip.lastIndexOf(":"));
        }

        return ip;
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
        Map<String, Object> ipFilterConfig = strategyManager.getIPFilterConfig(routeId);

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