package com.leoli.gateway.filter;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.*;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TracePropagationRegressionTest {
    @Test void usesExistingObservationSpanAndSetsCorrelationHeadersBeforeCommit() {
        Span span = mock(Span.class); TraceContext context = mock(TraceContext.class);
        when(span.context()).thenReturn(context); when(context.traceId()).thenReturn("11111111111111111111111111111111");
        when(context.spanId()).thenReturn("2222222222222222"); when(context.sampled()).thenReturn(true);
        var tracing = new TracingObservationHandler.TracingContext(); tracing.setSpan(span);
        var observationContext = new Observation.Context(); observationContext.put(TracingObservationHandler.TracingContext.class, tracing);
        Observation observation = mock(Observation.class); when(observation.getContextView()).thenReturn(observationContext);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        new TraceIdGlobalFilter().filter(exchange, forwarded -> {
            assertEquals("00-11111111111111111111111111111111-2222222222222222-01", forwarded.getRequest().getHeaders().getFirst("traceparent"));
            return forwarded.getResponse().writeWith(Mono.just(forwarded.getResponse().bufferFactory().wrap(new byte[]{1})));
        }).contextWrite(contextView -> contextView.put(ObservationThreadLocalAccessor.KEY, observation)).block();
        assertEquals("11111111111111111111111111111111", exchange.getResponse().getHeaders().getFirst("X-Trace-Id"));
        assertEquals("2222222222222222", exchange.getResponse().getHeaders().getFirst("X-Gateway-Span-Id"));
        verify(span, never()).end();
    }
    @Test void withoutSdkNeverInventsAnUnexportedGatewayParent() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        new TraceIdGlobalFilter().filter(exchange, forwarded -> {
            assertNull(forwarded.getRequest().getHeaders().getFirst("traceparent"));
            assertNotNull(forwarded.getRequest().getHeaders().getFirst("X-Trace-Id")); return Mono.empty();
        }).block();
    }
    @Test void withoutSdkPreservesActualUpstreamContext() {
        String parent = "00-11111111111111111111111111111111-2222222222222222-00";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("traceparent", parent));
        new TraceIdGlobalFilter().filter(exchange, forwarded -> {
            assertEquals(parent, forwarded.getRequest().getHeaders().getFirst("traceparent")); return Mono.empty();
        }).block();
    }
}
