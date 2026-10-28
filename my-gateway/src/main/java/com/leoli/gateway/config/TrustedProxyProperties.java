package com.leoli.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Trusted proxy configuration for X-Forwarded-For header validation.
 * <p>
 * When the gateway is behind a trusted proxy (like Nginx, CloudFlare, AWS ALB),
 * the X-Forwarded-For header contains the real client IP. However, this header
 * can be forged by malicious clients.
 * <p>
 * This configuration specifies which proxy IPs are trusted, so the gateway
 * will only accept X-Forwarded-For from these trusted proxies.
 *
 * @author leoli
 */
@Data
@ConfigurationProperties(prefix = "gateway.trusted-proxy")
public class TrustedProxyProperties {

    /**
     * Whether to trust X-Forwarded-For header.
     * If false, always use direct remote address.
     */
    private boolean enabled = true;

    /**
     * List of trusted proxy IP addresses or CIDR ranges.
     * Only X-Forwarded-For headers from these IPs will be trusted.
     * <p>
     * Common examples:
     * - "10.0.0.0/8" - Private network (internal proxies)
     * - "172.16.0.0/12" - Private network
     * - "192.168.0.0/16" - Private network
     * - "127.0.0.1" - Localhost
     */
    private List<String> proxyIps = new ArrayList<>();

    /**
     * Whether to trust all private network IPs as proxies.
     * This includes: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8
     * <p>
     * Enable this for internal deployments where all proxies are within the private network.
     */
    private boolean trustPrivateNetworks = false;

    /**
     * Which IP to use from X-Forwarded-For chain.
     * - FIRST: Walk from the right past trusted proxies to the nearest untrusted sender.
     * - LAST: Use the last IP (closest to gateway)
     * <p>
     * FIRST ignores caller-inserted entries to the left of that sender.
     */
    private ForwardedIpPosition position = ForwardedIpPosition.FIRST;

    /**
     * Maximum number of IPs accepted across all X-Forwarded-For header values.
     * Longer or malformed chains fall back to the direct peer.
     */
    private int maxForwardedIps = 10;

    public enum ForwardedIpPosition {
        FIRST,
        LAST
    }
}
