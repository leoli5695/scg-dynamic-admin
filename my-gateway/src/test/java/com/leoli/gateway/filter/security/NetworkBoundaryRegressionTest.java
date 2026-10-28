package com.leoli.gateway.filter.security;

import com.leoli.gateway.config.TrustedProxyProperties;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.ClientIpResolver;
import com.leoli.gateway.util.IpAddressUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NetworkBoundaryRegressionTest {
    @ParameterizedTest
    @CsvSource({
            "999.1.2.3, 0.0.0.0/0, false",
            "0.0.0.0, invalid/0, false",
            "127.0.0.1, localhost/8, false",
            "127.1, 127.0.0.0/8, false",
            "2001:db8::1, 2001:0db8:0:0:0:0:0:1, true",
            "2001:db8::1, 2001:db8::/32, true",
            "2001:db9::1, 2001:db8::/32, false",
            "::FFFF:192.0.2.7, 192.0.2.0/24, true",
            "192.0.2.7, ::ffff:192.0.2.0/120, true",
            "192.0.2.7, 192.0.2.0/33, false",
            "2001:db8:10000::1, 2001:db8::/32, false",
            "2001:db8::1::2, ::/0, false"
    })
    void matchesOnlyValidLiteralsAndCanonicalAddressFamilies(String ip, String range, boolean expected) {
        assertEquals(expected, IpAddressUtils.matches(ip, range));
    }

    private TrustedProxyProperties proxies() {
        var properties = new TrustedProxyProperties();
        properties.setProxyIps(List.of("10.0.0.0/8"));
        return properties;
    }

    private MockServerWebExchange forwarded(String... values) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 443))
                .header("X-Forwarded-For", values));
    }

    @Test void appendedRealClientCannotBeReplacedByCallerInsertedFirstHop() {
        var resolver = new ClientIpResolver(proxies());
        assertEquals("198.51.100.7", resolver.resolve(forwarded("1.2.3.4, 198.51.100.7, 10.0.0.2")));
    }

    @Test void repeatedHeaderValuesAreProcessedAsOneProxyChain() {
        var resolver = new ClientIpResolver(proxies());
        assertEquals("198.51.100.7", resolver.resolve(forwarded("1.2.3.4", "198.51.100.7, 10.0.0.2")));
    }

    @Test void overlongLastPositionFallsBackToPeerRatherThanSelectingTruncatedPrefix() {
        var properties = proxies(); properties.setMaxForwardedIps(2);
        properties.setPosition(TrustedProxyProperties.ForwardedIpPosition.LAST);
        var resolver = new ClientIpResolver(properties);
        assertEquals("10.0.0.1", resolver.resolve(forwarded("1.2.3.4, 198.51.100.7, 10.0.0.2")));
    }

    @ParameterizedTest
    @CsvSource({"localhost", "999.1.2.3", "198.51.100.7:garbage", "[2001:db8::1]:70000", "unknown"})
    void invalidForwardedIdentityFallsBackToPeer(String value) {
        assertEquals("10.0.0.1", new ClientIpResolver(proxies()).resolve(forwarded(value)));
    }

    @Test void validPortsAreRemovedAndIpv6IdentityIsCanonical() {
        var resolver = new ClientIpResolver(proxies());
        assertEquals("198.51.100.7", resolver.resolve(forwarded("198.51.100.7:443")));
        assertEquals(IpAddressUtils.normalize("2001:db8::1"), resolver.resolve(forwarded("[2001:db8::1]:443")));
    }

    @Test void enabledEmptyWhitelistDeniesWhileExplicitlyDisabledPolicyPasses() {
        var manager = mock(StrategyManager.class);
        var filter = new IPFilterGlobalFilter(manager, new ClientIpResolver(proxies()));
        when(manager.getIPFilterConfig(anyString())).thenReturn(Map.of("mode", "WHITELIST", "ipList", List.of()));
        var denied = forwarded("198.51.100.7");
        filter.filter(denied, ex -> Mono.error(new AssertionError("must not reach upstream"))).block();
        assertEquals(HttpStatus.FORBIDDEN, denied.getResponse().getStatusCode());
        when(manager.getIPFilterConfig(anyString())).thenReturn(Map.of("enabled", false, "mode", "whitelist"));
        var allowed = forwarded("198.51.100.7"); filter.filter(allowed, ex -> Mono.empty()).block();
        assertNull(allowed.getResponse().getStatusCode());
    }
}
