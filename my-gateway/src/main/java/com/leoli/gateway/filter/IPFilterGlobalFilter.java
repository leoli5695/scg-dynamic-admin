package com.leoli.gateway.filter;

import com.leoli.gateway.config.TrustedProxyProperties;
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
import java.util.ArrayList;
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
     * Supports both IPv4 and IPv6 CIDR notation.
     */
    private boolean isIPInCIDR(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                log.warn("Invalid CIDR format: {}", cidr);
                return false;
            }
            String networkAddress = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            // Determine if IPv4 or IPv6
            boolean isIPv6 = ip.contains(":") || networkAddress.contains(":");

            if (isIPv6) {
                return isIPv6InCIDR(ip, networkAddress, prefixLength);
            } else {
                return isIPv4InCIDR(ip, networkAddress, prefixLength);
            }
        } catch (Exception e) {
            log.warn("Invalid CIDR format: {}", cidr, e);
            return false;
        }
    }

    /**
     * Check if IPv4 is within CIDR range
     */
    private boolean isIPv4InCIDR(String ip, String networkAddress, int prefixLength) {
        if (prefixLength < 0 || prefixLength > 32) {
            log.warn("Invalid IPv4 prefix length: {}", prefixLength);
            return false;
        }

        long ipLong = ipv4ToLong(ip);
        long networkLong = ipv4ToLong(networkAddress);
        long mask = prefixLength == 0 ? 0 : (-1L << (32 - prefixLength));

        return (ipLong & mask) == (networkLong & mask);
    }

    /**
     * Check if IPv6 is within CIDR range
     */
    private boolean isIPv6InCIDR(String ip, String networkAddress, int prefixLength) {
        if (prefixLength < 0 || prefixLength > 128) {
            log.warn("Invalid IPv6 prefix length: {}", prefixLength);
            return false;
        }

        byte[] ipBytes = ipv6ToBytes(ip);
        byte[] networkBytes = ipv6ToBytes(networkAddress);

        if (ipBytes == null || networkBytes == null) {
            return false;
        }

        // Compare bytes up to prefix length
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;

        // Compare full bytes
        for (int i = 0; i < fullBytes; i++) {
            if (ipBytes[i] != networkBytes[i]) {
                return false;
            }
        }

        // Compare remaining bits
        if (remainingBits > 0 && fullBytes < 16) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            if ((ipBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Convert IPv4 address to long
     */
    private long ipv4ToLong(String ipAddress) {
        // Handle IPv6-mapped IPv4 addresses
        if (ipAddress.startsWith("::ffff:")) {
            ipAddress = ipAddress.substring(7);
        }

        // Handle IPv6 loopback mapped to IPv4
        if ("0:0:0:0:0:0:0:1".equals(ipAddress) || "::1".equals(ipAddress)) {
            return ipv4ToLong("127.0.0.1");
        }

        // If still IPv6, return 0 (can't convert to IPv4 long)
        if (ipAddress.contains(":")) {
            return 0;
        }

        String[] octets = ipAddress.split("\\.");
        if (octets.length != 4) {
            log.warn("Invalid IPv4 address format: {}", ipAddress);
            return 0;
        }

        long result = 0;
        for (int i = 0; i < 4; i++) {
            try {
                result = (result << 8) + Integer.parseInt(octets[i]);
            } catch (NumberFormatException e) {
                log.warn("Invalid IPv4 octet: {}", octets[i]);
                return 0;
            }
        }

        return result;
    }

    /**
     * Convert IPv6 address to 16-byte array
     */
    private byte[] ipv6ToBytes(String ipAddress) {
        try {
            // Normalize the IPv6 address
            String normalized = normalizeIPv6(ipAddress);
            if (normalized == null) {
                return null;
            }

            // Expand :: to appropriate number of 0 groups
            String expanded = expandIPv6(normalized);

            String[] groups = expanded.split(":");
            if (groups.length != 8) {
                log.warn("Invalid IPv6 address after expansion: {}", expanded);
                return null;
            }

            byte[] result = new byte[16];
            for (int i = 0; i < 8; i++) {
                int value = Integer.parseInt(groups[i], 16);
                result[i * 2] = (byte) ((value >> 8) & 0xFF);
                result[i * 2 + 1] = (byte) (value & 0xFF);
            }

            return result;
        } catch (Exception e) {
            log.warn("Failed to parse IPv6 address: {}", ipAddress, e);
            return null;
        }
    }

    /**
     * Normalize IPv6 address
     */
    private String normalizeIPv6(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return null;
        }

        // Handle IPv4-mapped IPv6 addresses (::ffff:192.168.1.1)
        if (ipAddress.startsWith("::ffff:") && !ipAddress.substring(7).contains(":")) {
            // Convert IPv4 part to hex
            String ipv4Part = ipAddress.substring(7);
            String[] octets = ipv4Part.split("\\.");
            if (octets.length == 4) {
                int o1 = Integer.parseInt(octets[0]);
                int o2 = Integer.parseInt(octets[1]);
                int o3 = Integer.parseInt(octets[2]);
                int o4 = Integer.parseInt(octets[3]);
                return String.format("0:0:0:0:0:ffff:%02x%02x:%02x%02x", o1, o2, o3, o4);
            }
        }

        return ipAddress;
    }

    /**
     * Expand compressed IPv6 address (:: notation)
     */
    private String expandIPv6(String ipAddress) {
        if (!ipAddress.contains("::")) {
            return ipAddress;
        }

        // Count existing groups
        String[] parts = ipAddress.split(":", -1);
        int existingGroups = 0;
        for (String part : parts) {
            if (!part.isEmpty()) {
                existingGroups++;
            }
        }

        // Calculate how many 0 groups to insert
        int zerosToInsert = 8 - existingGroups;

        // Build expanded address
        StringBuilder expanded = new StringBuilder();
        boolean inserted = false;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() && !inserted) {
                // This is the :: position
                for (int j = 0; j < zerosToInsert; j++) {
                    if (expanded.length() > 0) {
                        expanded.append(":");
                    }
                    expanded.append("0");
                }
                inserted = true;
            } else if (!parts[i].isEmpty()) {
                if (expanded.length() > 0) {
                    expanded.append(":");
                }
                expanded.append(parts[i]);
            }
        }

        return expanded.toString();
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
     * Check if IP belongs to private networks.
     * IPv4 (RFC 1918):
     * - 10.0.0.0/8
     * - 172.16.0.0/12
     * - 192.168.0.0/16
     * - 127.0.0.0/8 (loopback)
     * IPv6:
     * - fc00::/7 (Unique Local Addresses)
     * - fe80::/10 (Link-Local Addresses)
     * - ::1/128 (loopback)
     */
    private boolean isPrivateNetworkIP(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        // IPv6 private networks
        if (ip.contains(":")) {
            // IPv6 loopback
            if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
                return true;
            }
            // IPv6 Unique Local Addresses (fc00::/7)
            if (isIPv6InCIDR(ip, "fc00::", 7)) {
                return true;
            }
            // IPv6 Link-Local Addresses (fe80::/10)
            if (isIPv6InCIDR(ip, "fe80::", 10)) {
                return true;
            }
            return false;
        }

        // IPv4 private networks
        return isIPv4InCIDR(ip, "10.0.0.0", 8) ||
               isIPv4InCIDR(ip, "172.16.0.0", 12) ||
               isIPv4InCIDR(ip, "192.168.0.0", 16) ||
               isIPv4InCIDR(ip, "127.0.0.0", 8);
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
            String mode = getStringValue(ipFilterConfig, "mode", "blacklist");
            List<String> ipList = getStringListValue(ipFilterConfig, "ipList");
            Boolean enabled = getBooleanValue(ipFilterConfig, "enabled", false);

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

    /**
     * Safely get String value from config map.
     */
    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        return String.valueOf(value);
    }

    /**
     * Safely get Boolean value from config map.
     */
    private Boolean getBooleanValue(Map<String, Object> map, String key, Boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * Safely get List<String> value from config map.
     */
    @SuppressWarnings("unchecked")
    private List<String> getStringListValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;

        // Handle List<String>
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }

        log.warn("Unexpected type for key {}: {}", key, value.getClass().getName());
        return null;
    }

    @Override
    public int getOrder() {
        // High priority - execute before authentication
        // IP filtering is coarse-grained protection, should run first to block malicious IPs
        // This avoids unnecessary authentication computation for blocked IPs
        return -280;
    }
}