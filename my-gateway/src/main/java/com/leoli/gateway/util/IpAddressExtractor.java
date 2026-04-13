package com.leoli.gateway.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Utility class for extracting client IP address from requests.
 * <p>
 * Handles various scenarios:
 * - Direct connection (remote address)
 * - Proxied requests (X-Forwarded-For, X-Real-IP)
 * - Multiple proxies (chained X-Forwarded-For)
 * - Trusted proxy configuration
 *
 * @author leoli
 */
@Slf4j
public final class IpAddressExtractor {

    /**
     * Default unknown IP value.
     */
    public static final String UNKNOWN_IP = "unknown";

    /**
     * Common headers for client IP extraction.
     */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";
    private static final String PROXY_CLIENT_IP = "Proxy-Client-IP";
    private static final String WL_PROXY_CLIENT_IP = "WL-Proxy-Client-IP";

    private IpAddressExtractor() {
        // Utility class - prevent instantiation
    }

    // ============================================================
    // Basic IP Extraction
    // ============================================================

    /**
     * Extract client IP address from exchange.
     * <p>
     * Priority: X-Forwarded-For -> X-Real-IP -> remote address
     *
     * @param exchange ServerWebExchange
     * @return Client IP address, or "unknown" if not available
     */
    public static String extractClientIp(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();

        // Try X-Forwarded-For header first (for proxied requests)
        String ip = extractFromXForwardedFor(request);
        if (isValidIp(ip)) {
            return ip;
        }

        // Try X-Real-IP header
        ip = extractFromHeader(request, X_REAL_IP);
        if (isValidIp(ip)) {
            return ip;
        }

        // Try other proxy headers
        ip = extractFromHeader(request, PROXY_CLIENT_IP);
        if (isValidIp(ip)) {
            return ip;
        }

        ip = extractFromHeader(request, WL_PROXY_CLIENT_IP);
        if (isValidIp(ip)) {
            return ip;
        }

        // Fall back to remote address
        return extractRemoteAddress(request);
    }

    /**
     * Extract client IP from X-Forwarded-For header.
     * <p>
     * X-Forwarded-For may contain multiple IPs: client, proxy1, proxy2...
     * The first IP is typically the original client.
     *
     * @param request ServerHttpRequest
     * @return First IP from X-Forwarded-For, or null
     */
    public static String extractFromXForwardedFor(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst(X_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // X-Forwarded-For may contain multiple IPs, use the first one
            String[] ips = forwardedFor.split(",");
            for (String ip : ips) {
                String trimmedIp = ip.trim();
                if (isValidIp(trimmedIp)) {
                    return trimmedIp;
                }
            }
        }
        return null;
    }

    /**
     * Extract IP from a specific header.
     *
     * @param request    ServerHttpRequest
     * @param headerName Header name to check
     * @return IP from header, or null if not found/invalid
     */
    public static String extractFromHeader(ServerHttpRequest request, String headerName) {
        String ip = request.getHeaders().getFirst(headerName);
        if (ip != null && !ip.isEmpty()) {
            ip = ip.trim();
            if (isValidIp(ip)) {
                return ip;
            }
        }
        return null;
    }

    /**
     * Extract IP from remote address.
     *
     * @param request ServerHttpRequest
     * @return Remote address IP, or "unknown"
     */
    public static String extractRemoteAddress(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getHostString() != null) {
            return remoteAddress.getHostString();
        }
        return UNKNOWN_IP;
    }

    // ============================================================
    // Trusted Proxy Support
    // ============================================================

    /**
     * Extract client IP considering trusted proxies.
     * <p>
     * When trusted proxies are configured, the first non-trusted IP
     * from X-Forwarded-For is returned.
     *
     * @param exchange       ServerWebExchange
     * @param trustedProxies List of trusted proxy IPs
     * @return Client IP address
     */
    public static String extractClientIpWithTrustedProxies(ServerWebExchange exchange,
                                                           List<String> trustedProxies) {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return extractClientIp(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String forwardedFor = request.getHeaders().getFirst(X_FORWARDED_FOR);

        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            String[] ips = forwardedFor.split(",");
            // Find first IP that is NOT a trusted proxy
            for (int i = ips.length - 1; i >= 0; i--) {
                String ip = ips[i].trim();
                if (isValidIp(ip) && !trustedProxies.contains(ip)) {
                    return ip;
                }
            }
        }

        // If all IPs are trusted proxies, use remote address
        return extractRemoteAddress(request);
    }

    // ============================================================
    // IP Validation
    // ============================================================

    /**
     * Check if IP address is valid.
     * <p>
     * Valid IP should be:
     * - Not null or empty
     * - Not "unknown"
     * - Not a placeholder value
     *
     * @param ip IP address to check
     * @return true if valid
     */
    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if (UNKNOWN_IP.equalsIgnoreCase(ip)) {
            return false;
        }
        // Filter out common placeholder values
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            // IPv6 localhost - still valid but may want special handling
            return true;
        }
        if (ip.startsWith("127.") || "localhost".equalsIgnoreCase(ip)) {
            // IPv4 localhost - still valid
            return true;
        }
        return true;
    }

    /**
     * Check if IP is IPv4 format.
     *
     * @param ip IP address
     * @return true if IPv4
     */
    public static boolean isIPv4(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        return ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}$");
    }

    /**
     * Check if IP is IPv6 format.
     *
     * @param ip IP address
     * @return true if IPv6
     */
    public static boolean isIPv6(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        return ip.contains(":");
    }

    /**
     * Check if IP is localhost.
     *
     * @param ip IP address
     * @return true if localhost
     */
    public static boolean isLocalhost(String ip) {
        if (ip == null) return false;
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "localhost".equalsIgnoreCase(ip);
    }
}