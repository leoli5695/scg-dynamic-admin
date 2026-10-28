package com.leoli.gateway.filter;

import com.leoli.gateway.util.DebugTraceVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DebugTraceRoutingTest {
    @Test void stripsPrivateDebugSignatureAndReturnsGatewayOwnedCorrelationHeaders() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/hello")
                .header(DebugTraceVerifier.HEADER,"signed-proof"));
        exchange.getAttributes().put(org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("actual-route").uri("http://upstream").predicate(ignored -> true).build());
        var filter = new TraceIdGlobalFilter();
        var verifier = mock(DebugTraceVerifier.class);
        when(verifier.verify(eq("signed-proof"),eq("GET"),any())).thenReturn(true);
        ReflectionTestUtils.setField(filter,"debugTraceVerifier",verifier);
        filter.filter(exchange, forwarded -> {
            assertNull(forwarded.getRequest().getHeaders().getFirst(DebugTraceVerifier.HEADER));
            assertEquals(true,forwarded.getAttribute(DebugTraceVerifier.TRUSTED_ATTR));
            forwarded.getResponse().getHeaders().set("X-Trace-Id","upstream-spoof");
            forwarded.getResponse().getHeaders().set("X-Gateway-Route-Id","upstream-route");
            return forwarded.getResponse().writeWith(Mono.just(forwarded.getResponse().bufferFactory().wrap(new byte[]{1})));
        }).block();
        assertEquals("actual-route",exchange.getResponse().getHeaders().getFirst("X-Gateway-Route-Id"));
        assertEquals(exchange.getAttribute(TraceIdGlobalFilter.TRACE_ID_ATTR),exchange.getResponse().getHeaders().getFirst("X-Trace-Id"));
    }
}
