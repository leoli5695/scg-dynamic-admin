package com.leoli.gateway.util;

import com.leoli.gateway.config.TrustedProxyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import java.util.ArrayList;
import java.util.List;

/** Resolves a canonical literal IP without DNS or trusting caller-inserted proxy hops. */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {
    private static final String UNKNOWN = "unknown";
    private final TrustedProxyProperties trustedProxyProperties;

    public String resolve(ServerWebExchange exchange) {
        String remote = getRemoteAddress(exchange);
        if (!trustedProxyProperties.isEnabled() || !isTrustedProxy(remote)) return remote;
        List<String> values = exchange.getRequest().getHeaders().get("X-Forwarded-For");
        if (values == null || values.isEmpty()) return remote;
        int maximum = trustedProxyProperties.getMaxForwardedIps();
        if (maximum <= 0) return remote;
        List<String> ips = new ArrayList<>();
        for (String value : values) {
            int start = 0;
            while (start <= value.length()) {
                if (ips.size() >= maximum) return remote;
                int comma = value.indexOf(',', start);
                String ip = forwardedLiteral(value.substring(start, comma < 0 ? value.length() : comma).trim());
                if (ip == null) return remote;
                ips.add(ip);
                if (comma < 0) break;
                start = comma + 1;
            }
        }
        int index = ips.size() - 1;
        if (trustedProxyProperties.getPosition() != TrustedProxyProperties.ForwardedIpPosition.LAST) {
            // A trusted proxy may append rather than replace XFF. Ignore every hop
            // to the left of the closest untrusted sender: that sender can forge it.
            while (index > 0 && isTrustedProxy(ips.get(index))) index--;
        }
        return ips.get(index);
    }

    public String getRemoteAddress(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return UNKNOWN;
        String normalized = IpAddressUtils.normalize(remote.getAddress().getHostAddress());
        return normalized != null ? normalized : UNKNOWN;
    }

    public boolean isTrustedProxy(String ip) {
        if (isIpInRange(ip, trustedProxyProperties.getProxyIps())) return true;
        return trustedProxyProperties.isTrustPrivateNetworks() && isIpInRange(ip,
                List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
                        "::1", "fc00::/7", "fe80::/10"));
    }

    public boolean isIpInRange(String ip, List<String> ranges) {
        if (ranges == null) return false;
        for (String range : ranges) {
            if (IpAddressUtils.matches(ip, range)) return true;
        }
        return false;
    }

    private static String forwardedLiteral(String value) {
        String direct = IpAddressUtils.normalize(value);
        if (direct != null) return direct;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end > 1 && validPort(value.substring(end + 1))) {
                return IpAddressUtils.normalize(value.substring(1, end));
            }
        } else if (value.indexOf(':') > 0 && value.indexOf(':') == value.lastIndexOf(':')) {
            int colon = value.indexOf(':');
            if (validPort(value.substring(colon))) return IpAddressUtils.normalize(value.substring(0, colon));
        }
        return null;
    }

    private static boolean validPort(String value) {
        if (!value.startsWith(":")) return false;
        String port = value.substring(1);
        if (port.isEmpty() || port.length() > 5 || !port.chars().allMatch(Character::isDigit)) return false;
        int numeric = Integer.parseInt(port);
        return numeric > 0 && numeric <= 65535;
    }
}
