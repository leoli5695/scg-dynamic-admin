package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AccessLogGlobalConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessLogEntryQueryServiceTest {
    @TempDir
    Path tempDir;

    private final AccessLogConfigService configService = mock(AccessLogConfigService.class);
    private final KubernetesResourceService kubernetesResourceService = mock(KubernetesResourceService.class);
    private AccessLogEntryQueryService service;

    private static final String K8S_LOGS = """
            {"@timestamp":"2026-10-25T10:00:00Z","method":"GET","path":"/old","statusCode":200,"traceId":"a","durationMs":10}
            {"@timestamp":"2026-10-25T11:00:00Z","method":"POST","path":"/new","statusCode":500,"traceId":"b","durationMs":30}
            {"@timestamp":"2026-10-25T12:00:00Z","statusCode":500}
            not-json
            """;

    @BeforeEach
    void setUp() throws Exception {
        AccessLogGlobalConfig config = new AccessLogGlobalConfig();
        config.setLogDirectory(tempDir.toString());
        when(configService.getConfig()).thenReturn(config);
        service = new AccessLogEntryQueryService(configService, kubernetesResourceService, new ObjectMapper());
        Files.writeString(tempDir.resolve("access-2026-10-25.log"), """
                {"@timestamp":"2026-10-25T10:00:00Z","method":"GET","path":"/api/a","statusCode":200,"traceId":"a","durationMs":10}
                {"@timestamp":"2026-10-25T10:01:00Z","method":"POST","path":"/api/b","statusCode":500,"traceId":"b","durationMs":20}
                {"@timestamp":"2026-10-25T10:02:00Z","method":"GET","path":"/api/c","statusCode":500,"traceId":"c","durationMs":30}
                {"@timestamp":"2026-10-25T10:03:00Z","method":"GET","path":"/api/d","statusCode":500,"traceId":"d","durationMs":40}
                malformed
                """);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fileQueriesFilterPageAndReportMissingFilesWithoutChangingTheEnvelope() {
        Map<String, Object> filtered = service.getLogEntries(
                "2026-10-25", 0, 100, "GET", 500, "/api/c", "c");

        assertEquals(1, filtered.get("total"));
        assertEquals("access-2026-10-25.log", filtered.get("file"));
        List<Map<String, Object>> entries = (List<Map<String, Object>>) filtered.get("entries");
        assertEquals("/api/c", entries.get(0).get("path"));

        assertEquals(3, service.getLogEntries(
                "2026-10-25", 0, 100, "GET", null, null, null).get("total"));
        assertEquals(3, service.getLogEntries(
                "2026-10-25", 0, 100, null, 500, null, null).get("total"));
        Map<String, Object> traceFiltered = service.getLogEntries(
                "2026-10-25", 0, 100, null, null, null, "d");
        assertEquals(1, traceFiltered.get("total"));
        assertEquals("/api/d", ((List<Map<String, Object>>) traceFiltered.get("entries")).get(0).get("path"));

        Map<String, Object> paged = service.getLogEntries(
                "2026-10-25", 1, 2, null, null, null, null);

        assertEquals(4, paged.get("total"));
        assertEquals(1, paged.get("page"));
        assertEquals(2, paged.get("size"));
        List<Map<String, Object>> secondPage = (List<Map<String, Object>>) paged.get("entries");
        assertEquals(2, secondPage.size());
        assertEquals("/api/c", secondPage.get(0).get("path"));
        assertEquals("/api/d", secondPage.get(1).get("path"));

        Map<String, Object> missing = service.getLogEntries(
                "2026-10-26", 0, 100, null, null, null, null);

        assertEquals(0, missing.get("total"));
        assertEquals("access-2026-10-26.log", missing.get("file"));
        assertTrue(((List<?>) missing.get("entries")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fileStatsAggregateOnlyParsedEntries() {
        Map<String, Object> stats = service.getLogStats("2026-10-25");

        assertEquals(4L, stats.get("totalRequests"));
        assertEquals(25.0, ((Number) stats.get("avgDuration")).doubleValue());
        assertEquals(Map.of(200, 1, 500, 3), stats.get("statusCodes"));
        assertEquals(Map.of("GET", 3, "POST", 1), stats.get("methods"));
        assertEquals(4, ((List<?>) stats.get("topPaths")).size());
        assertEquals("access-2026-10-25.log", stats.get("file"));

        Map<String, Object> missing = service.getLogStats("2026-10-26");

        assertEquals(0, missing.get("totalRequests"));
        assertTrue(((Map<?, ?>) missing.get("statusCodes")).isEmpty());
        assertTrue(((List<?>) missing.get("topPaths")).isEmpty());
    }

    @Test
    void malformedDatesAreRejectedWithoutEchoingThePayload() {
        AccessLogEntryQueryService.InvalidLogDateException exception = assertThrows(
                AccessLogEntryQueryService.InvalidLogDateException.class,
                () -> service.getLogEntries("x/../../../etc/passwd", 0, 100, null, null, null, null));

        assertTrue(!exception.getMessage().contains("passwd") && !exception.getMessage().contains(".."),
                exception.getMessage());
        assertThrows(AccessLogEntryQueryService.InvalidLogDateException.class,
                () -> service.getLogStats("2026-1-5"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void kubernetesQueriesClampTailSortNewestFirstAndKeepTheEnvelope() {
        when(kubernetesResourceService.getPodLogs(1L, "ns", "pod", null, 10_000, null))
                .thenReturn(K8S_LOGS);

        Map<String, Object> data = service.getK8sLogEntries(
                1L, "ns", "pod", 1_000_000, 0, 1,
                null, null, null, null, null);

        assertEquals(2, data.get("total"));
        assertEquals("pod", data.get("podName"));
        assertEquals("ns", data.get("namespace"));
        assertEquals(true, data.get("realtime"));
        List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
        assertEquals(1, entries.size());
        assertEquals("POST", entries.get(0).get("method"));
        assertEquals("/new", entries.get(0).get("path"));
        verify(kubernetesResourceService).getPodLogs(1L, "ns", "pod", null, 10_000, null);
    }

    @Test
    void kubernetesStatsUseSinceSecondsInsteadOfTailAndAggregateEntries() {
        when(kubernetesResourceService.getPodLogs(1L, "ns", "pod", null, null, 60))
                .thenReturn(K8S_LOGS);

        Map<String, Object> stats = service.getK8sLogStats(1L, "ns", "pod", 500, 60);

        assertEquals(2L, stats.get("totalRequests"));
        assertEquals(20.0, ((Number) stats.get("avgDuration")).doubleValue());
        assertEquals(Map.of("GET", 1, "POST", 1), stats.get("methods"));
        assertEquals("pod", stats.get("podName"));
        assertEquals(true, stats.get("realtime"));
        verify(kubernetesResourceService).getPodLogs(1L, "ns", "pod", null, null, 60);
    }
}