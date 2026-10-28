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

    @Test
    void zeroBodySamplingStillReportsErrorMetadata() {
        ReflectionTestUtils.setField(filter, "captureErrors", true);
        ReflectionTestUtils.setField(filter, "samplingRateForErrors", 100);
        ReflectionTestUtils.setField(filter, "bodySamplingRate", 0);
        ReflectionTestUtils.setField(filter, "maxBodySize", 100);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/failed")
                .contentType(MediaType.APPLICATION_JSON).body("{}"));
        filter.filter(exchange, forwarded -> {
            org.junit.jupiter.api.Assertions.assertSame(exchange.getResponse(), forwarded.getResponse());
            forwarded.getResponse().setStatusCode(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            return Mono.empty();
        }).block();
        ArgumentCaptor<Map<String, Object>> trace = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(reportDispatcher).submit(trace.capture());
        Map<?, ?> payload = (Map<?, ?>) trace.getValue();
        assertEquals(500, payload.get("statusCode"));
        org.junit.jupiter.api.Assertions.assertNull(payload.get("responseBody"));
        assertEquals(false, payload.get("replayable"));
    }

    @Test
    void bodyBudgetBypassesCaptureWhenBusyAndIsReleasedOnCancellation() {
        ReflectionTestUtils.setField(filter, "captureAll", true);
        ReflectionTestUtils.setField(filter, "samplingRate", 100);
        ReflectionTestUtils.setField(filter, "bodySamplingRate", 100);
        ReflectionTestUtils.setField(filter, "maxBodySize", 100);
        ReflectionTestUtils.setField(filter, "maxInflightBodyBytes", 400L);
        var first = MockServerWebExchange.from(MockServerHttpRequest.get("/first"));
        var subscription = filter.filter(first, forwarded -> {
            org.junit.jupiter.api.Assertions.assertNotSame(first.getResponse(), forwarded.getResponse());
            return Mono.never();
        }).subscribe();
        var reserved = (java.util.concurrent.atomic.AtomicLong) ReflectionTestUtils.getField(filter, "inflightBodyBytes");
        try {
            assertEquals(400, reserved.get());
            var second = MockServerWebExchange.from(MockServerHttpRequest.get("/second"));
            filter.filter(second, forwarded -> {
                org.junit.jupiter.api.Assertions.assertSame(second.getResponse(), forwarded.getResponse());
                return Mono.empty();
            }).block();
            assertEquals(400, reserved.get());
        } finally {
            subscription.dispose();
        }
        assertEquals(0, reserved.get());
    }

    @Mock
    private TrustedProxyProperties trustedProxyProperties;

    @Mock
    private com.leoli.gateway.monitor.TraceReportDispatcher reportDispatcher;
    @Mock
    private WebClient.RequestBodyUriSpec uriSpec;
    @Mock
    private WebClient.RequestBodySpec bodySpec;
    @Mock
    private WebClient.RequestHeadersSpec<?> headersSpec;
    @Mock
    private WebClient.ResponseSpec responseSpec;

    private TraceCaptureGlobalFilter filter;

    @Test
    void verifiedDebugRequestsRetainStreamingMetadataEvenWhenNormalSamplingIsDisabled() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/stream").header("Accept","text/event-stream"));
        exchange.getAttributes().put(com.leoli.gateway.util.DebugTraceVerifier.TRUSTED_ATTR,true);
        exchange.getAttributes().put(TraceIdGlobalFilter.TRACE_ID_ATTR,"verified-debug");
        filter.filter(exchange, ignored -> Mono.empty()).block();
        org.mockito.Mockito.verify(reportDispatcher).submit(org.mockito.ArgumentMatchers.argThat(row ->
                "verified-debug".equals(row.get("traceId")) && "DEBUG".equals(row.get("traceType"))));
    }

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
        ReflectionTestUtils.setField(filter, "reportDispatcher", reportDispatcher);


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
        org.mockito.Mockito.verify(reportDispatcher).submit(captor.capture());
        return (String) captor.getValue().get("clientIp");
    }
}
