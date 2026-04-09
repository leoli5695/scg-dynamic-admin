package com.leoli.gateway.filter;

import com.leoli.gateway.config.TrustedProxyProperties;
import com.leoli.gateway.manager.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IPFilterGlobalFilter.
 * Tests IP filtering with CIDR notation and trusted proxy validation.
 */
@ExtendWith(MockitoExtension.class)
class IPFilterGlobalFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private TrustedProxyProperties trustedProxyProperties;

    @Mock
    private GatewayFilterChain chain;

    @InjectMocks
    private IPFilterGlobalFilter filter;

    @BeforeEach
    void setUp() {
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
        lenient().when(trustedProxyProperties.isEnabled()).thenReturn(false);
    }

    // ============================================================
    // IPv4 CIDR Tests
    // ============================================================

    @Nested
    @DisplayName("IPv4 CIDR Tests")
    class Ipv4CidrTests {

        @Test
        @DisplayName("Should match IP in /24 CIDR range")
        void testIpv4InCidr_24() {
            // 192.168.1.0/24 should contain 192.168.1.100
            Map<String, Object> config = createBlacklistConfig("192.168.1.0/24");
            MockServerWebExchange exchange = createExchangeFromIp("192.168.1.100");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should not match IP outside /24 CIDR range")
        void testIpv4NotInCidr_24() {
            // 192.168.1.0/24 should NOT contain 192.168.2.100
            Map<String, Object> config = createBlacklistConfig("192.168.1.0/24");
            MockServerWebExchange exchange = createExchangeFromIp("192.168.2.100");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should match IP in /16 CIDR range")
        void testIpv4InCidr_16() {
            // 10.0.0.0/16 should contain 10.0.100.50
            Map<String, Object> config = createBlacklistConfig("10.0.0.0/16");
            MockServerWebExchange exchange = createExchangeFromIp("10.0.100.50");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should match IP in /8 CIDR range")
        void testIpv4InCidr_8() {
            // 172.16.0.0/12 should contain 172.20.100.50
            Map<String, Object> config = createBlacklistConfig("172.16.0.0/12");
            MockServerWebExchange exchange = createExchangeFromIp("172.20.100.50");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should match exact IP")
        void testIpv4ExactMatch() {
            Map<String, Object> config = createBlacklistConfig("192.168.1.100");
            MockServerWebExchange exchange = createExchangeFromIp("192.168.1.100");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should not match different IP")
        void testIpv4NoMatch() {
            Map<String, Object> config = createBlacklistConfig("192.168.1.100");
            MockServerWebExchange exchange = createExchangeFromIp("192.168.1.101");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }
    }

    // ============================================================
    // IPv6 CIDR Tests
    // ============================================================

    @Nested
    @DisplayName("IPv6 CIDR Tests")
    class Ipv6CidrTests {

        @Test
        @DisplayName("Should match IPv6 in CIDR range")
        void testIpv6InCidr() {
            // 2001:db8::/32 should contain 2001:db8:1234::1
            Map<String, Object> config = createBlacklistConfig("2001:db8::/32");
            MockServerWebExchange exchange = createExchangeFromIp("2001:db8:1234::1");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should match IPv6 loopback")
        void testIpv6Loopback() throws Exception {
            // Use expanded format for consistency (InetAddress returns expanded format)
            String ipv6Expanded = "0:0:0:0:0:0:0:1";
            Map<String, Object> config = createBlacklistConfig(ipv6Expanded);
            MockServerWebExchange exchange = createExchangeFromIp("::1");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }
    }

    // ============================================================
    // Blacklist Mode Tests
    // ============================================================

    @Nested
    @DisplayName("Blacklist Mode Tests")
    class BlacklistModeTests {

        @Test
        @DisplayName("Should block blacklisted IP")
        void testBlacklist_block() {
            Map<String, Object> config = createBlacklistConfig("10.0.0.1");
            MockServerWebExchange exchange = createExchangeFromIp("10.0.0.1");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
            verify(chain, never()).filter(exchange);
        }

        @Test
        @DisplayName("Should allow non-blacklisted IP")
        void testBlacklist_allow() {
            Map<String, Object> config = createBlacklistConfig("10.0.0.1");
            MockServerWebExchange exchange = createExchangeFromIp("10.0.0.2");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should block multiple blacklisted IPs")
        void testBlacklist_multiple() {
            Map<String, Object> config = createBlacklistConfig("10.0.0.1", "10.0.0.2", "192.168.1.0/24");

            // IP in list should be blocked
            MockServerWebExchange exchange1 = createExchangeFromIp("10.0.0.1");
            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);
            StepVerifier.create(filter.filter(exchange1, chain)).verifyComplete();
            assertEquals(HttpStatus.FORBIDDEN, exchange1.getResponse().getStatusCode());

            // IP in CIDR should be blocked
            MockServerWebExchange exchange2 = createExchangeFromIp("192.168.1.50");
            StepVerifier.create(filter.filter(exchange2, chain)).verifyComplete();
            assertEquals(HttpStatus.FORBIDDEN, exchange2.getResponse().getStatusCode());

            // IP not in list should pass
            MockServerWebExchange exchange3 = createExchangeFromIp("10.0.0.3");
            StepVerifier.create(filter.filter(exchange3, chain)).verifyComplete();
            verify(chain).filter(exchange3);
        }
    }

    // ============================================================
    // Whitelist Mode Tests
    // ============================================================

    @Nested
    @DisplayName("Whitelist Mode Tests")
    class WhitelistModeTests {

        @Test
        @DisplayName("Should allow whitelisted IP")
        void testWhitelist_allow() {
            Map<String, Object> config = createWhitelistConfig("10.0.0.1");
            MockServerWebExchange exchange = createExchangeFromIp("10.0.0.1");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should block non-whitelisted IP")
        void testWhitelist_block() {
            Map<String, Object> config = createWhitelistConfig("10.0.0.1");
            MockServerWebExchange exchange = createExchangeFromIp("10.0.0.2");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should allow IP in whitelisted CIDR range")
        void testWhitelist_cidr() {
            Map<String, Object> config = createWhitelistConfig("192.168.0.0/16");
            MockServerWebExchange exchange = createExchangeFromIp("192.168.100.50");

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }
    }

    // ============================================================
    // Trusted Proxy Tests
    // ============================================================

    @Nested
    @DisplayName("Trusted Proxy Tests")
    class TrustedProxyTests {

        @Test
        @DisplayName("Should extract client IP from trusted proxy")
        void testTrustedProxy_extractClientIp() throws Exception {
            lenient().when(trustedProxyProperties.isEnabled()).thenReturn(true);
            lenient().when(trustedProxyProperties.getProxyIps()).thenReturn(Arrays.asList("10.0.0.1"));
            lenient().when(trustedProxyProperties.isTrustPrivateNetworks()).thenReturn(false);

            Map<String, Object> config = createBlacklistConfig("192.168.1.100");
            java.net.InetAddress inetAddress = java.net.InetAddress.getByName("10.0.0.1");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Forwarded-For", "192.168.1.100, 10.0.0.1")
                    .remoteAddress(new InetSocketAddress(inetAddress, 8080))
                    .build();
            MockServerWebExchange exchange = createExchangeWithRoute(MockServerWebExchange.builder(request).build());

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Should block based on X-Forwarded-For IP
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should ignore X-Forwarded-For from untrusted proxy")
        void testUntrustedProxy_ignoreXForwardedFor() throws Exception {
            when(trustedProxyProperties.isEnabled()).thenReturn(true);
            when(trustedProxyProperties.getProxyIps()).thenReturn(Arrays.asList("10.0.0.1"));
            when(trustedProxyProperties.isTrustPrivateNetworks()).thenReturn(false);

            Map<String, Object> config = createBlacklistConfig("192.168.1.100");
            // Request from untrusted proxy (10.0.0.2) with spoofed X-Forwarded-For
            java.net.InetAddress inetAddress = java.net.InetAddress.getByName("10.0.0.2");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Forwarded-For", "192.168.1.100")
                    .remoteAddress(new InetSocketAddress(inetAddress, 8080))
                    .build();
            MockServerWebExchange exchange = createExchangeWithRoute(MockServerWebExchange.builder(request).build());

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Should use actual remote IP (10.0.0.2), not X-Forwarded-For
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should trust private networks when configured")
        void testTrustPrivateNetworks() throws Exception {
            when(trustedProxyProperties.isEnabled()).thenReturn(true);
            when(trustedProxyProperties.getProxyIps()).thenReturn(Collections.emptyList());
            when(trustedProxyProperties.isTrustPrivateNetworks()).thenReturn(true);

            Map<String, Object> config = createBlacklistConfig("203.0.113.50");
            java.net.InetAddress inetAddress = java.net.InetAddress.getByName("192.168.1.1");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Forwarded-For", "203.0.113.50")
                    .remoteAddress(new InetSocketAddress(inetAddress, 8080)) // Private IP
                    .build();
            MockServerWebExchange exchange = createExchangeWithRoute(MockServerWebExchange.builder(request).build());

            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Should extract client IP from X-Forwarded-For since source is private network
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }
    }

    // ============================================================
    // Basic Filter Tests
    // ============================================================

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Should pass through when no config")
        void testFilter_noConfig() {
            MockServerWebExchange exchange = createExchangeFromIp("10.0.0.1");
            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(null);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should pass through when IP list is empty")
        void testFilter_emptyIpList() {
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "blacklist");
            config.put("ipList", Collections.emptyList());

            MockServerWebExchange exchange = createExchangeFromIp("10.0.0.1");
            when(strategyManager.getIPFilterConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }
    }

    // ============================================================
    // Filter Order Test
    // ============================================================

    @Test
    @DisplayName("Filter order should be -280")
    void testGetOrder() {
        assertEquals(-280, filter.getOrder());
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    private MockServerWebExchange createExchangeFromIp(String ip) {
        try {
            // Create a resolved InetSocketAddress to ensure getAddress() returns non-null
            java.net.InetAddress inetAddress = java.net.InetAddress.getByName(ip);
            InetSocketAddress socketAddress = new InetSocketAddress(inetAddress, 8080);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .remoteAddress(socketAddress)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

            // Set route attribute
            org.springframework.cloud.gateway.route.Route route =
                    org.springframework.cloud.gateway.route.Route.async()
                            .id("test-route")
                            .uri(java.net.URI.create("http://localhost:8080"))
                            .predicate(ex -> true)
                            .build();
            exchange.getAttributes().put(
                    org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                    route);

            return exchange;
        } catch (java.net.UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> createBlacklistConfig(String... ips) {
        Map<String, Object> config = new HashMap<>();
        config.put("mode", "blacklist");
        config.put("ipList", Arrays.asList(ips));
        return config;
    }

    private Map<String, Object> createWhitelistConfig(String... ips) {
        Map<String, Object> config = new HashMap<>();
        config.put("mode", "whitelist");
        config.put("ipList", Arrays.asList(ips));
        return config;
    }

    private MockServerWebExchange createExchangeWithRoute(MockServerWebExchange exchange) {
        org.springframework.cloud.gateway.route.Route route =
                org.springframework.cloud.gateway.route.Route.async()
                        .id("test-route")
                        .uri(java.net.URI.create("http://localhost:8080"))
                        .predicate(ex -> true)
                        .build();
        exchange.getAttributes().put(
                org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                route);
        return exchange;
    }
}