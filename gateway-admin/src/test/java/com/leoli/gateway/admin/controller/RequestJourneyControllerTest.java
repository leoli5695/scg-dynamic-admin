package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import com.leoli.gateway.admin.repository.AccessLogEntryRepository;
import com.leoli.gateway.admin.service.TokenUsageAnalyticsService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestJourneyControllerTest {
    @Test void exactRequestLookupIsScopedAndSupportsDelayedRecords() {
        var traces = mock(RequestTraceRepository.class);
        var usage = mock(TokenUsageAnalyticsService.class);
        var trace = new RequestTrace(); trace.setId(1L); trace.setTraceId("trace-a"); trace.setInstanceId("gw-a"); trace.setStatusCode(200);
        when(traces.findByTraceId("trace-a")).thenReturn(Optional.of(trace));
        when(usage.eventsByTrace("gw-a","trace-a")).thenReturn(Map.of("content",List.of(Map.of("totalTokens",5))));
        when(usage.eventsByTrace("gw-b","trace-a")).thenReturn(Map.of("content",List.of()));
        var logs = mock(AccessLogEntryRepository.class);
        var controller = new RequestJourneyController(traces,usage,logs);
        assertNotNull(controller.get("trace-a","gw-a").getData().get("trace"));
        assertNull(controller.get("trace-a","gw-b").getData().get("trace"));
        assertEquals(List.of(),controller.get("trace-a","gw-b").getData().get("usage"));
        when(traces.findByTraceId("later")).thenReturn(Optional.empty());
        when(usage.eventsByTrace("gw-a","later")).thenReturn(Map.of("content",List.of()));
        var delayed = controller.get("later","gw-a").getData();
        assertNull(delayed.get("trace"));
        assertNull(delayed.get("accessLog"));
        verify(usage).eventsByTrace("gw-a","later");
    }
}
