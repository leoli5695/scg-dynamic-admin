package com.leoli.gateway.util;

import com.leoli.gateway.config.TrustedProxyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Trusted-proxy-aware client IP resolver.
 * <p>
 * FIX (P0): rate limiting and quota checks previously derived their keys from the
 * raw first hop of {@code X-Forwarded-For} (via the retired {@code IpAddressExtractor#extractClientIp}),
 * which is fully client-controlled. An attacker could rotate the header to mint an
 * unbounded number of distinct limit keys, defeating IP/user/tenant rate limiting.
 * <p>
 * This resolver only honours {@code X-Forwarded-For} when the direct TCP peer is a
 * configured trusted proxy; otherwise it falls back to the real remote address.
 * It mirrors the logic already used by {@code IPFilterGlobalFilter} so that every
 * consumer agrees on who the client is.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String UNKNOWN = "unknown";

    private final TrustedProxyProperties trustedProxyProperties;

    /**
     * Resolve the true client IP for the given exchange.
     *
     * @param exchange ServerWebExchange
     * @return a non-spoofable client IP (remote address unless a trusted proxy forwarded it)
     */
    public String resolve(ServerWebExchange exchange) {
        String remoteIp = getRemoteAddress(exchange);

        if (!trustedProxyProperties.isEnabled()) {
            return remoteIp;
        }

        if (isTrustedProxy(remoteIp)) {
            String forwarded = exchange.getRequest().getHeaders().getFirst(X_FORWARDED_FOR);
            if (forwarded != null && !forwarded.isEmpty()) {
                return extractForwardedIp(forwarded);
            }
        }
        return remoteIp;
    }

    /**
     * Get the direct TCP peer address (never client-controlled).
     */
    public String getRemoteAddress(ServerWebExchange exchange) {
        if (exchange.getRequest().getRemoteAddress() != null
                && exchange.getRequest().getRemoteAddress().getAddress() != null) {
            return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        return UNKNOWN;
    }

    /**
     * Whether the given IP is a trusted proxy (configured list or private networks).
     */
    public boolean isTrustedProxy(String ip) {
        List<String> proxyIps = trustedProxyProperties.getProxyIps();
        if (proxyIps != null && !proxyIps.isEmpty() && isIpInRange(ip, proxyIps)) {
            return true;
        }
        return trustedProxyProperties.isTrustPrivateNetworks() && isPrivateNetworkIp(ip);
    }

    /**
     * Extract the client IP from an X-Forwarded-For chain per configured position,
     * bounded by maxForwardedIps to avoid DoS via very long headers.
     */
    private String extractForwardedIp(String forwarded) {
        String[] ips = forwarded.split(",");
        int maxIps = Math.min(ips.length, trustedProxyProperties.getMaxForwardedIps());
        if (maxIps <= 0) {
            return UNKNOWN;
        }
        String ip = trustedProxyProperties.getPosition() == TrustedProxyProperties.ForwardedIpPosition.LAST
                ? ips[maxIps - 1].trim()
                : ips[0].trim();
        return stripPort(ip);
    }

    /**
     * Strip an optional port without corrupting IPv6 addresses.
     */
    private String stripPort(String ip) {
        if (ip.startsWith("[")) {
            int closeBracket = ip.indexOf(']');
            if (closeBracket > 1) {
                return ip.substring(1, closeBracket);
            }
            return ip;
        }
        // Exactly one colon => IPv4:port. Multiple colons => bare IPv6, keep as-is.
        if (ip.indexOf(':') != -1 && ip.indexOf(':') == ip.lastIndexOf(':')) {
            return ip.substring(0, ip.lastIndexOf(':'));
        }
        return ip;
    }

    /**
     * Whether the IP matches any entry in the list (exact IP or CIDR).
     */
    public boolean isIpInRange(String ip, List<String> ranges) {
        if (ip == null || ip.isEmpty() || ranges == null) {
            return false;
        }
        for (String range : ranges) {
            if (range == null || range.isEmpty()) {
                continue;
            }
            if (range.contains("/")) {
                if (isIpInCidr(ip, range)) {
                    return true;
                }
            } else if (range.equals(ip)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CIDR membership test supporting both IPv4 and IPv6.
     */
    private boolean isIpInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }
            InetAddress address = InetAddress.getByName(ip);
            InetAddress network = InetAddress.getByName(parts[0]);
            byte[] addressBytes = address.getAddress();
            byte[] networkBytes = network.getAddress();
            if (addressBytes.length != networkBytes.length) {
                return false; // IPv4 vs IPv6 mismatch
            }
            int prefixLength = Integer.parseInt(parts[1].trim());
            if (prefixLength < 0 || prefixLength > addressBytes.length * 8) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addressBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits > 0 && fullBytes < addressBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((addressBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            log.debug("Invalid CIDR match ip={} cidr={}: {}", ip, cidr, e.getMessage());
            return false;
        }
    }

    /**
     * Whether the IP belongs to a private/loopback/link-local range.
     */
    private boolean isPrivateNetworkIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if (ip.contains(":")) {
            return "::1".equals(ip)
                    || "0:0:0:0:0:0:0:1".equals(ip)
                    || isIpInCidr(ip, "fc00::/7")
                    || isIpInCidr(ip, "fe80::/10");
        }
        return isIpInCidr(ip, "10.0.0.0/8")
                || isIpInCidr(ip, "172.16.0.0/12")
                || isIpInCidr(ip, "192.168.0.0/16")
                || isIpInCidr(ip, "127.0.0.0/8");
    }
}
