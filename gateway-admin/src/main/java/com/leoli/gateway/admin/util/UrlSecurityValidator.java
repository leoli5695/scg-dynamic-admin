package com.leoli.gateway.admin.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

/**
 * SSRF protection for outbound HTTP calls whose target URL is user-supplied
 * (AI provider baseUrl, replay custom target URL, stress test target, etc.).
 *
 * Rules:
 * - Only http/https schemes are allowed.
 * - Host must resolve; DNS failure is fail-closed (rejected).
 * - All resolved addresses are checked (not just the first one) against
 *   loopback / link-local / multicast / any-local / IPv6 ULA ranges.
 * - Cloud metadata endpoints (169.254.169.254) are covered by the
 *   link-local check.
 * - Site-local (RFC1918 private) addresses are rejected in strict mode,
 *   but allowed in internal mode because gateway instances are legitimately
 *   deployed on private networks.
 *
 * @author leoli
 */
public final class UrlSecurityValidator {

    private UrlSecurityValidator() {
    }

    /**
     * Validate a URL that must point to an external public endpoint
     * (e.g. AI provider baseUrl). Private networks are rejected.
     *
     * @throws IllegalArgumentException when the URL is unsafe
     */
    public static void validateExternalUrl(String url) {
        validate(url, false);
    }

    /**
     * Validate a URL that may legitimately target internal gateway instances
     * (e.g. replay / stress test targets). Private networks are allowed,
     * loopback, link-local, metadata and multicast addresses are not.
     *
     * @throws IllegalArgumentException when the URL is unsafe
     */
    public static void validateInternalTargetUrl(String url) {
        validate(url, true);
    }

    private static void validate(String url, boolean allowSiteLocal) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http/https URLs are allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must contain a valid host");
        }

        InetAddress[] addresses;
        try {
            // Fail closed: if the host cannot be resolved, reject the URL
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot resolve host: " + host);
        }

        // Check every resolved address to prevent bypass via multi-A records
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isMulticastAddress()
                    || isIPv6UniqueLocal(address)) {
                throw new IllegalArgumentException("URL points to a forbidden address range");
            }
            if (!allowSiteLocal && address.isSiteLocalAddress()) {
                throw new IllegalArgumentException("URL points to a private network address");
            }
        }
    }

    /**
     * IPv6 unique-local (fc00::/7) and site-local (fec0::/10) are not covered
     * by InetAddress.isSiteLocalAddress(), which only handles RFC1918 IPv4.
     */
    private static boolean isIPv6UniqueLocal(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xFF;
        // fc00::/7 -> first byte 0xFC or 0xFD; fec0::/10 -> 0xFE with top bits 1111111011
        return first == 0xFC || first == 0xFD || (first & 0xFF) == 0xFE && (bytes[1] & 0xC0) == 0xC0;
    }

    /**
     * Non-throwing variant for call sites that prefer a boolean check.
     */
    public static boolean isSafeExternalUrl(String url) {
        try {
            validateExternalUrl(url);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
