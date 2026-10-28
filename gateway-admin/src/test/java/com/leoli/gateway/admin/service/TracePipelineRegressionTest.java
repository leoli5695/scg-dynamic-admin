package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.controller.ServiceMiddlewareController;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TracePipelineRegressionTest {
    final ObjectMapper mapper = new ObjectMapper();
    @Test void batchAcknowledgesRejectedPositionsWithoutClaimingPersistence() {
        var buffer = mock(DistributedTraceBufferService.class);
        when(buffer.offerBatch(anyList())).thenReturn(List.of(new DistributedTraceBufferService.OfferResult(true, 90, 0), new DistributedTraceBufferService.OfferResult(false, 100, 1)));
        var controller = new ServiceMiddlewareController(mock(ServiceMiddlewareService.class), mock(DistributedTraceService.class), buffer, mapper);
        var response = controller.receiveBatchTraces(List.of(Map.of("traceId", "trace", "serviceName", "a"), Map.of("traceId", "trace", "serviceName", "b")));
        assertEquals(202, response.getStatusCode().value());
        assertEquals("ENQUEUED", response.getBody().get("stage")); assertEquals(1, response.getBody().get("accepted"));
        assertEquals(List.of(1), response.getBody().get("rejectedIndexes"));
        assertEquals(400, controller.receiveBatchTraces(Collections.nCopies(101, Map.of())).getStatusCode().value());
        assertEquals(400, controller.receiveBatchTraces(List.of(Map.of("traceId", "trace", "serviceName", "a"), Map.of())).getStatusCode().value());
        verify(buffer, times(1)).offerBatch(anyList());
    }
    @Test void historicalDetailNeverCallsLiveGatewayAndMergesSequentialIntervals() throws Exception {
        var requests = mock(RequestTraceRepository.class); var traces = mock(DistributedTraceRepository.class);
        var filters = mock(FilterChainExecutionService.class); var instances = mock(GatewayInstanceService.class); var http = mock(RestTemplate.class);
        var service = new FullChainTraceService(requests, traces, mock(FilterChainExecutionRepository.class), filters, instances, mapper, http);
        RequestTrace gateway = new RequestTrace(); gateway.setTraceId("trace"); gateway.setLatencyMs(100L); gateway.setStatusCode(200);
        gateway.setTraceTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(1000), ZoneId.systemDefault()));
        gateway.setResponseHeaders("{\"X-Gateway-Span-Id\":\"gateway\"}");
        when(requests.findByTraceId("trace")).thenReturn(Optional.of(gateway));
        var first = record("a", 1000, 40, "gateway"); var second = record("b", 1050, 50, "gateway");
        when(traces.findAllByTraceId("trace")).thenReturn(List.of(first, second));
        when(filters.getTraceExecutionSummary("trace")).thenReturn(Map.of("hasFilterData", false));
        var result = service.getFullChain("trace");
        Map<?,?> breakdown = (Map<?,?>) result.get("durationBreakdown");
        assertEquals(90L, breakdown.get("serviceDurationMs")); assertNull(breakdown.get("networkTransferMs"));
        assertNull(breakdown.get("gatewayOverheadMs")); assertNull(breakdown.get("otherMs"));
        assertEquals("OBSERVED", result.get("chainStatus"));
        assertEquals(false, ((Map<?,?>)result.get("integrity")).get("completenessVerified"));
        verifyNoInteractions(instances, http);
    }
    @Test void graphReportsMissingParentsCyclesSummariesAndUnfinishedSpans() throws Exception {
        var trace = record("a", 1000, 10, "missing"); trace.setCaptureMode("SUMMARY_ONLY"); trace.setOpenSpanCount(2); trace.setDroppedSpanCount(3);
        var graph = TraceGraphAnalysis.analyze(null, List.of(trace), mapper);
        assertEquals(false, graph.get("observedGraphValid")); assertEquals(Set.of("missing"), graph.get("missingParentSpanIds"));
        assertEquals(2, graph.get("openSpanCount")); assertEquals(3, graph.get("droppedSpanCount"));
        var cyclic = record("b", 1000, 10, "b");
        assertTrue(((List<?>) TraceGraphAnalysis.analyze(null, List.of(cyclic), mapper).get("reasons")).contains("CYCLE"));
    }
    @Test void oneFlushTaskAndActiveBatchRemainChargedDuringDatabaseStall() throws Exception {
        var writer = mock(DiagnosticBatchWriter.class);
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        when(writer.writeDistributedTraces(anyList())).thenAnswer(call -> { entered.countDown(); finish.await(3, TimeUnit.SECONDS); throw new RuntimeException("db unavailable"); });
        var buffer = new DistributedTraceBufferService(writer, mapper);
        ReflectionTestUtils.setField(buffer, "maxBytes", 1024L * 1024);
        try {
            for (int i = 0; i < 200; i++) buffer.offer(record("a" + i, 1000, 1, ""));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long retained = buffer.getStats().get("retainedBytes"); assertTrue(retained > 0);
            for (int i = 0; i < 1000; i++) buffer.scheduledFlush();
            var worker = (ThreadPoolExecutor) ReflectionTestUtils.getField(buffer, "worker");
            assertTrue(worker.getQueue().size() <= 1);
            assertEquals(retained, buffer.getStats().get("retainedBytes"));
        } finally { finish.countDown(); buffer.shutdown(); }
        assertEquals(0L, buffer.getStats().get("retainedBytes")); assertEquals(200L, buffer.getStats().get("dropped"));
        verify(writer, times(1)).writeDistributedTraces(anyList());
    }
    private DistributedTraceEntity record(String id, long start, long duration, String parent) throws Exception {
        var trace = new DistributedTraceEntity(); trace.setTraceId("trace"); trace.setReportId(UUID.randomUUID().toString());
        trace.setServiceName(id); trace.setStartTimeMs(start); trace.setTotalDurationMs(duration); trace.setSuccess(true);
        trace.setCaptureMode("SAMPLED"); trace.setOpenSpanCount(0); trace.setDroppedSpanCount(0);
        trace.setSpans(mapper.writeValueAsString(List.of(Map.of("spanId", id, "parentSpanId", parent, "startTimeMs", start, "durationMs", duration))));
        return trace;
    }
}
