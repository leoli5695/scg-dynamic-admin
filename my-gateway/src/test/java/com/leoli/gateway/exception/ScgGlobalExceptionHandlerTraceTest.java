package com.leoli.gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.monitor.TraceReportDispatcher;
import com.leoli.gateway.filter.TraceCaptureGlobalFilter;
import com.leoli.gateway.filter.TraceIdGlobalFilter;
import com.leoli.gateway.util.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class ScgGlobalExceptionHandlerTraceTest {
    @Test
    void failedMatchedRequestReportsOnlyRouteIdInsteadOfSerializingTheRoute() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/demo/hello"));
        String routeId = "ba17ccb4-4a8f-4e1c-9743-a8763c146d67";
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id(routeId)
                .uri("static://demo-service").predicate(e -> true).build());

        Map<String, Object> trace = reportError(exchange, HttpStatus.SERVICE_UNAVAILABLE);

        assertEquals(routeId, trace.get("routeId"));
        var serialized = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(trace));
        assertTrue(serialized.get("routeId").isTextual());
        assertEquals(routeId, serialized.get("routeId").asText());
        assertEquals(503, serialized.get("statusCode").asInt());
        assertEquals("ERROR", serialized.get("traceType").asText());
    }

    @Test
    void unmatchedRequestReportsTheSameUnknownRouteFallbackAsNormalCapture() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/missing"));
        assertEquals("unknown", reportError(exchange, HttpStatus.NOT_FOUND).get("routeId"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void filterAndExceptionHandlerExportOneCompleteErrorTraceForTheSameRequest(boolean captureBody) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/demo/hello"));
        exchange.getAttributes().put(TraceIdGlobalFilter.TRACE_ID_ATTR, "single-error-trace");
        var dispatcher = mock(TraceReportDispatcher.class);
        var resolver = mock(ClientIpResolver.class);
        var handler = new ScgGlobalExceptionHandler(new ObjectMapper(), resolver);
        ReflectionTestUtils.setField(handler, "reportDispatcher", dispatcher);
        var filter = new TraceCaptureGlobalFilter(new ObjectMapper(), resolver);
        ReflectionTestUtils.setField(filter, "reportDispatcher", dispatcher);
        ReflectionTestUtils.setField(filter, "captureAll", true);
        ReflectionTestUtils.setField(filter, "captureErrors", true);
        ReflectionTestUtils.setField(filter, "samplingRateForErrors", 100);
        ReflectionTestUtils.setField(filter, "samplingRate", 100);
        ReflectionTestUtils.setField(filter, "includeFilterChain", false);
        ReflectionTestUtils.setField(filter, "bodySamplingRate", 100);
        ReflectionTestUtils.setField(filter, "maxBodySize", captureBody ? 1024 : 0);

        filter.filter(exchange, forwarded -> Mono.error(
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Backend unavailable")))
                .onErrorResume(error -> handler.handle(exchange, error)).block();

        verify(dispatcher, times(1)).submit(anyMap());
        verify(dispatcher).submit(argThat(trace ->
                "single-error-trace".equals(trace.get("traceId"))
                        && "ERROR".equals(trace.get("traceType"))
                        && trace.get("errorMessage") != null
                        && trace.get("responseBody") != null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> reportError(MockServerWebExchange exchange, HttpStatus status) {
        var dispatcher = mock(TraceReportDispatcher.class);
        var handler = new ScgGlobalExceptionHandler(new ObjectMapper(), mock(ClientIpResolver.class));
        ReflectionTestUtils.setField(handler, "reportDispatcher", dispatcher);
        ReflectionTestUtils.setField(handler, "instanceId", "test-gateway");

        handler.handle(exchange, new ResponseStatusException(status, "Backend unavailable")).block();

        assertEquals(status, exchange.getResponse().getStatusCode());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(dispatcher).submit(captor.capture());
        return captor.getValue();
    }
}
