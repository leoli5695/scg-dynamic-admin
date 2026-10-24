package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.config.TrustedProxyProperties;
import com.leoli.gateway.util.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the invariant behind the consistent-hash load balancer key: it must not be
 * derivable from a header the caller sets, except through the explicit
 * {@code X-Hash-Key} affinity knob.
 * <p>
 * Before this test existed, {@code InstanceSelector#getHashKey} took the first hop of a raw
 * {@code X-Forwarded-For}, so any client could name its own hash key and therefore choose
 * which instance served it - concentrating an attack on one instance while the others looked
 * healthy, or colliding keys to defeat everyone else's affinity.
 *
 * @author leoli
 */
class InstanceSelectorHashKeyTest {

    private static final String SERVICE_ID = "demo-service";
    private static final String STRATEGY = "consistent-hash";
    private static final String PEER_IP = "203.0.113.7";

    private final List<ServiceInstance> instances = buildInstances();

    private InstanceSelector selector(TrustedProxyProperties properties) {
        return new InstanceSelector(new ClientIpResolver(properties));
    }

    private TrustedProxyProperties defaults() {
        return new TrustedProxyProperties();
    }

    private ServerWebExchange exchangeFrom(String peerIp, String... headerPairs) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress(peerIp, 54321));
        for (int i = 0; i < headerPairs.length; i += 2) {
            builder.header(headerPairs[i], headerPairs[i + 1]);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private ServiceInstance select(InstanceSelector selector, String peerIp, String... headerPairs) {
        return selector.select(instances, STRATEGY, exchangeFrom(peerIp, headerPairs));
    }

    private static List<ServiceInstance> buildInstances() {
        List<ServiceInstance> list = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            list.add(new DefaultServiceInstance(
                    SERVICE_ID + "-" + i, SERVICE_ID, "10.0.0." + (i + 1), 8080 + i, false));
        }
        return list;
    }

    private static String hostPort(ServiceInstance instance) {
        return instance.getHost() + ":" + instance.getPort();
    }

    @Test
    @DisplayName("a spoofed X-Forwarded-For cannot steer which instance is selected")
    void spoofedForwardedForCannotSteerTheSelection() {
        InstanceSelector selector = selector(defaults());
        String honest = hostPort(select(selector, PEER_IP));

        Set<String> selected = new HashSet<>();
        for (int i = 1; i <= 20; i++) {
            // Same TCP peer every time; only the caller-controlled header varies. Under the
            // pre-fix code roughly two thirds of these landed on a different instance.
            String spoofed = "198.51.100." + i;
            selected.add(hostPort(select(selector, PEER_IP, "X-Forwarded-For", spoofed)));
        }

        assertEquals(Set.of(honest), selected,
                "varying X-Forwarded-For from one peer must not change the selected instance");
    }

    @Test
    @DisplayName("the explicit X-Hash-Key affinity knob still works")
    void explicitHashKeyStillPinsAffinity() {
        InstanceSelector selector = selector(defaults());

        // Same key from two different peers => same instance.
        String fromPeerA = hostPort(select(selector, "203.0.113.7", "X-Hash-Key", "tenant-42"));
        String fromPeerB = hostPort(select(selector, "198.51.100.9", "X-Hash-Key", "tenant-42"));
        assertEquals(fromPeerA, fromPeerB, "X-Hash-Key must pin affinity across peers");

        // Distinct keys must still spread, otherwise the knob is useless for sharding.
        Set<String> spread = new HashSet<>();
        for (int i = 1; i <= 40; i++) {
            spread.add(hostPort(select(selector, PEER_IP, "X-Hash-Key", "tenant-" + i)));
        }
        assertTrue(spread.size() > 1,
                "distinct X-Hash-Key values collapsed onto a single instance: " + spread);
    }

    @Test
    @DisplayName("honest peers still spread across instances")
    void selectionStillSpreadsAcrossInstances() {
        InstanceSelector selector = selector(defaults());

        Set<String> spread = new HashSet<>();
        for (int i = 1; i <= 60; i++) {
            // No forwarded header at all: the key must come from the real TCP peer and still
            // distribute. This guards the failure mode where the fix accidentally collapses
            // every request onto one instance.
            spread.add(hostPort(select(selector, "198.51.100." + i)));
        }

        assertTrue(spread.size() >= 2,
                "consistent-hash selection collapsed onto " + spread.size() + " instance(s)");
    }

    @Test
    @DisplayName("X-Forwarded-For is honoured when the peer is a configured trusted proxy")
    void forwardedHeaderIsHonouredForConfiguredTrustedProxy() {
        TrustedProxyProperties properties = defaults();
        properties.setProxyIps(List.of("203.0.113.0/24"));

        InstanceSelector trusted = selector(properties);

        // Client 198.51.100.9 arriving through the trusted proxy must hash on the forwarded
        // IP, i.e. land exactly where a direct connection from that client would.
        String viaProxy = hostPort(select(trusted, PEER_IP, "X-Forwarded-For", "198.51.100.9"));

        InstanceSelector untrusted = selector(defaults());
        String direct = hostPort(select(untrusted, "198.51.100.9"));

        assertEquals(direct, viaProxy,
                "trusted-proxy forwarding is not wired through ClientIpResolver");

        // And with the default (no trusted proxies configured) the header is ignored outright,
        // so the same peer selects the same instance whether or not it sends one.
        assertEquals(hostPort(select(untrusted, PEER_IP)),
                hostPort(select(untrusted, PEER_IP, "X-Forwarded-For", "198.51.100.9")),
                "an untrusted peer must not be able to claim a forwarded identity");
    }
}
