package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.config.TrustedProxyProperties;
import com.leoli.gateway.util.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Locks the P0 unification: TraceCaptureGlobalFilter must derive the recorded
 * {@code clientIp} from the shared {@link ClientIpResolver} — the same component
 * the rate-limit and IP-filter paths use — so trace/audit never diverges from the
 * IP that actually gets limited. The old private X-Forwarded-For parsing (fed by a
 * separate {@code gateway.trace.trusted-proxy-ips} config) is gone.
 */
@ExtendWith(MockitoExtension.class)
class TraceCaptureGlobalFilterTest {

    @Mock
    private TrustedProxyProperties trustedProxyProperties;

    @Mock
    private WebClient webClient;
    @Mock
    private WebClient.RequestBodyUriSpec uriSpec;
    @Mock
    private WebClient.RequestBodySpec bodySpec;
    @Mock
    private WebClient.RequestHeadersSpec<?> headersSpec;
    @Mock
    private WebClient.ResponseSpec responseSpec;

    private TraceCaptureGlobalFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ClientIpResolver clientIpResolver = new ClientIpResolver(trustedProxyProperties);
        filter = new TraceCaptureGlobalFilter(new ObjectMapper(), clientIpResolver);

        ReflectionTestUtils.setField(filter, "adminUrl", "http://127.0.0.1:9090");
        ReflectionTestUtils.setField(filter, "internalToken", "");
        ReflectionTestUtils.setField(filter, "instanceId", "gateway-test");
        ReflectionTestUtils.setField(filter, "includeFilterChain", false);
        ReflectionTestUtils.setField(filter, "maskSensitiveBody", true);
        ReflectionTestUtils.setField(filter, "webClient", webClient);

        // Fluent WebClient chain ending in a no-op Mono so sendTraceToAdmin can be captured.
        lenient().when(webClient.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        lenient().when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        lenient().when(bodySpec.headers(any())).thenReturn(bodySpec);
        lenient().when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        lenient().when(headersSpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("ok"));
    }

    @Test
    @DisplayName("Records the resolver's IP, ignoring spoofed X-Forwarded-For from an untrusted peer")
    void untrustedPeer_spoofedXff_recordsRemoteAddress() throws Exception {
        when(trustedProxyProperties.isEnabled()).thenReturn(true);
        when(trustedProxyProperties.getProxyIps()).thenReturn(Collections.emptyList());
        when(trustedProxyProperties.isTrustPrivateNetworks()).thenReturn(false);

        MockServerWebExchange exchange = exchangeFrom("203.0.113.7", "10.9.9.9");

        invokeCapture(exchange);

        assertEquals("203.0.113.7", capturedClientIp(),
                "untrusted peer's X-Forwarded-For must not override the recorded client IP");
    }

    @Test
    @DisplayName("Records the forwarded client IP when the direct peer is a configured trusted proxy")
    void trustedProxy_forwardedXff_recordsForwardedIp() throws Exception {
        when(trustedProxyProperties.isEnabled()).thenReturn(true);
        when(trustedProxyProperties.getProxyIps()).thenReturn(List.of("203.0.113.7"));
        lenient().when(trustedProxyProperties.isTrustPrivateNetworks()).thenReturn(false);
        lenient().when(trustedProxyProperties.getPosition()).thenReturn(TrustedProxyProperties.ForwardedIpPosition.FIRST);
        lenient().when(trustedProxyProperties.getMaxForwardedIps()).thenReturn(10);

        MockServerWebExchange exchange = exchangeFrom("203.0.113.7", "198.51.100.23");

        invokeCapture(exchange);

        assertEquals("198.51.100.23", capturedClientIp(),
                "trusted proxy's X-Forwarded-For client IP must be recorded");
    }

    // ------------------------------------------------------------------

    private MockServerWebExchange exchangeFrom(String remoteIp, String xForwardedFor) throws Exception {
        InetAddress addr = InetAddress.getByName(remoteIp);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Forwarded-For", xForwardedFor)
                .remoteAddress(new InetSocketAddress(addr, 8080))
                .build();
        return MockServerWebExchange.builder(request).build();
    }

    private void invokeCapture(MockServerWebExchange exchange) {
        ReflectionTestUtils.invokeMethod(filter, "captureTrace",
                exchange, System.currentTimeMillis(), "trace-1",
                null, null, 5L, 200,
                false, false, "NONE", false, "NORMAL");
    }

    @SuppressWarnings("unchecked")
    private String capturedClientIp() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(bodySpec).bodyValue(captor.capture());
        return (String) captor.getValue().get("clientIp");
    }
}
