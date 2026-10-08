package com.leoli.gateway.filter.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.ClientIpResolver;
import com.leoli.gateway.util.RouteUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * IP Filter Global Filter
 * <p>
 * Supports blacklist and whitelist modes with CIDR notation.
 * Implements trusted proxy validation for X-Forwarded-For header.
 * </p>
 *
 * FIX (M4): Added Caffeine cache for IP-to-long conversion.
 * Hot IPs (common IPs from repeated requests) are cached to avoid
 * repeated parsing overhead in high QPS scenarios.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IPFilterGlobalFilter implements GlobalFilter, Ordered {

    private final StrategyManager strategyManager;
    private final ClientIpResolver clientIpResolver;

    /**
     * FIX (M4): Caffeine cache for IPv4-to-long conversion.
     * Cache size: 10000 entries (covers common IPs)
     * TTL: 10 minutes (refresh stale entries)
     * Purpose: Avoid repeated split parsing for hot IPs
     */
    private final Cache<String, Long> ipLongCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /**
     * FIX (performance): the network side of each configured CIDR was split,
     * parsed and (for IPv6) byte-expanded on every request even though it only
     * changes when the strategy config changes. Pre-parsed CIDRs are cached by
     * their textual form; invalid entries are cached as invalid (negative cache).
     */
    private final Cache<String, ParsedCidr> cidrCache = Caffeine.newBuilder()
            .maximumSize(1024)
            .build();

    private record ParsedCidr(boolean valid, boolean ipv6,
                              long maskedNetworkLong, long mask, byte[] networkBytes, int prefixLength) {
        static ParsedCidr invalid() {
            return new ParsedCidr(false, false, 0L, 0L, null, 0);
        }
    }

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
     * Check if IP is within CIDR range.
     * Supports both IPv4 and IPv6 CIDR notation.
     * <p>
     * FIX (performance): the CIDR network side is parsed once per unique CIDR
     * string and cached, instead of being re-split/re-parsed on every request.
     */
    private boolean isIPInCIDR(String ip, String cidr) {
        ParsedCidr parsed = cidrCache.get(cidr, this::parseCidr);
        if (parsed == null || !parsed.valid()) {
            return false;
        }

        // Classify the client IP (::ffff: mapped addresses are treated as IPv4,
        // matching the previous ipv4ToLong behavior).
        boolean ipIsV6 = !ip.startsWith("::ffff:") && ip.contains(":");
        if (ipIsV6 != parsed.ipv6()) {
            // Mixed address families never matched in the old implementation either.
            return false;
        }

        if (parsed.ipv6()) {
            byte[] ipBytes = ipv6ToBytes(ip);
            if (ipBytes == null) {
                return false;
            }
            byte[] networkBytes = parsed.networkBytes();
            int prefixLength = parsed.prefixLength();

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            if (remainingBits > 0 && fullBytes < 16) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                return (ipBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
            }
            return true;
        }

        long ipLong = ipv4ToLong(ip);
        return (ipLong & parsed.mask()) == parsed.maskedNetworkLong();
    }

    /**
     * Parse a CIDR string into its pre-computed matching components.
     */
    private ParsedCidr parseCidr(String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                log.warn("Invalid CIDR format: {}", cidr);
                return ParsedCidr.invalid();
            }
            String networkAddress = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            if (networkAddress.contains(":")) {
                if (prefixLength < 0 || prefixLength > 128) {
                    log.warn("Invalid IPv6 prefix length: {}", prefixLength);
                    return ParsedCidr.invalid();
                }
                byte[] networkBytes = ipv6ToBytes(networkAddress);
                if (networkBytes == null) {
                    return ParsedCidr.invalid();
                }
                return new ParsedCidr(true, true, 0L, 0L, networkBytes, prefixLength);
            }

            if (prefixLength < 0 || prefixLength > 32) {
                log.warn("Invalid IPv4 prefix length: {}", prefixLength);
                return ParsedCidr.invalid();
            }
            long networkLong = ipv4ToLong(networkAddress);
            long mask = prefixLength == 0 ? 0 : (-1L << (32 - prefixLength));
            return new ParsedCidr(true, false, networkLong & mask, mask, null, prefixLength);
        } catch (Exception e) {
            log.warn("Invalid CIDR format: {}", cidr, e);
            return ParsedCidr.invalid();
        }
    }

    /**
     * Convert IPv4 address to long
     * FIX (M4): Added Caffeine cache for hot IPs
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

        // FIX (M4): Check cache first for hot IPs
        Long cached = ipLongCache.getIfPresent(ipAddress);
        if (cached != null) {
            return cached;
        }

        // Parse and cache the result
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

        // Cache the parsed result for future requests
        ipLongCache.put(ipAddress, result);

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
        String clientIp = clientIpResolver.resolve(exchange);

        // Get IP filter config for this route
        Map<String, Object> ipFilterConfig = strategyManager.getIPFilterConfig(routeId);

        if (ipFilterConfig != null && !ipFilterConfig.isEmpty()) {
            String mode = getStringValue(ipFilterConfig, "mode", "blacklist");
            List<String> ipList = getStringListValue(ipFilterConfig, "ipList");

            // IP list is configured, apply filter
            if (ipList != null && !ipList.isEmpty()) {
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
        return FilterOrderConstants.IP_FILTER;
    }
}