package com.leoli.gateway.trace.interceptor;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.decorator.TraceTaskDecorator;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import com.leoli.gateway.trace.reporter.AsyncTraceReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SamplingRegressionTest {
    @AfterEach void clear() { TraceContextHolder.clear(); }
    @Test void unsampledW3cDecisionWinsOverLegacyHeaderAndLocalProbability() {
        var properties = new GatewayTraceProperties(); properties.setSampleRate(1);
        var request = new MockHttpServletRequest("GET", "/");
        request.addHeader("traceparent", "00-11111111111111111111111111111111-2222222222222222-00"); request.addHeader("X-Trace-Sampled", "true");
        new TraceWebInterceptor(properties, "service").preHandle(request, new MockHttpServletResponse(), new Object());
        assertFalse(TraceContextHolder.isSampled()); assertTrue(TraceContextHolder.getTrace().getSpans().isEmpty());
    }
    @Test void forcedErrorIsExplicitlyMarkedAsSummaryOnly() {
        var properties = new GatewayTraceProperties(); properties.setSampleRate(0);
        var reporter = mock(AsyncTraceReporter.class); var request = new MockHttpServletRequest("GET", "/"); var response = new MockHttpServletResponse();
        new TraceWebInterceptor(properties, "service").preHandle(request, response, new Object()); response.setStatus(500);
        new TraceReportInterceptor(reporter, properties).afterCompletion(request, response, new Object(), null);
        var capture = ArgumentCaptor.forClass(DistributedTrace.class); verify(reporter).report(capture.capture());
        assertEquals("SUMMARY_ONLY", capture.getValue().getCaptureMode()); assertNull(TraceContextHolder.getTraceId());
    }
    @Test void callerRunsDecoratorRestoresOriginalRequestContext() {
        TraceContextHolder.setTraceId("parent"); TraceContextHolder.setSampled(true); TraceContextHolder.initTrace("service", "/", "GET");
        var trace = TraceContextHolder.getTrace();
        TraceTaskDecorator.wrap(() -> assertEquals("parent", TraceContextHolder.getTraceId())).run();
        assertEquals("parent", TraceContextHolder.getTraceId()); assertSame(trace, TraceContextHolder.getTrace());
    }
}
