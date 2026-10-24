package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the transaction reshape in {@link RequestReplayService}.
 *
 * <p>{@code executeReplay} used to be {@code @Transactional} around a call that can block for 35s
 * (5s connect + 30s read), so a pooled connection was held for the whole HTTP round trip and batch
 * replay multiplied it. The write it needed - {@code incrementReplayCount}, a {@code @Modifying}
 * query that cannot run without a transaction - moved into {@code recordReplayOutcome}, invoked
 * after the call returns.
 *
 * <p>These tests pin the two things that refactor could silently break: that the counter is still
 * written on every outcome path, and that it is written <b>after</b> the HTTP call rather than
 * around it.
 */
@ExtendWith(MockitoExtension.class)
class RequestReplayServiceTest {

    private static final Long TRACE_ID = 42L;
    private static final String INSTANCE_ID = "inst-1";
    private static final String GATEWAY_URL = "http://gateway.local";

    @Mock
    private RequestTraceRepository traceRepository;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private GatewayInstanceService instanceService;
    @Mock
    private JsonDiffService jsonDiffService;

    private RequestReplayService service;

    @BeforeEach
    void setUp() {
        service = new RequestReplayService(traceRepository, restTemplate, new ObjectMapper(),
                instanceService, jsonDiffService);
        // executeReplay records the counter through the Spring proxy (`self`) so that @Transactional
        // applies when it is called from the async batch thread. There is no proxy in a unit test, so
        // point the field at the instance itself: the call still routes through recordReplayOutcome,
        // which is what is under test here.
        ReflectionTestUtils.setField(service, "self", service);
    }

    private void givenReplayableTrace() {
        RequestTrace trace = new RequestTrace();
        trace.setTraceId("uuid-1");
        trace.setMethod("GET");
        trace.setPath("/api/ping");
        trace.setReplayType("HTTP");
        trace.setReplayable(true);
        when(traceRepository.findById(TRACE_ID)).thenReturn(Optional.of(trace));
        when(instanceService.getAccessUrl(INSTANCE_ID)).thenReturn(GATEWAY_URL);
    }

    private void givenHttpReturns(HttpStatus status, String body) {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, status));
    }

    @Test
    @DisplayName("a successful replay still records the counter")
    void successRecordsCounter() {
        givenReplayableTrace();
        givenHttpReturns(HttpStatus.OK, "pong");

        RequestReplayService.ReplayResult result = service.executeReplay(TRACE_ID, INSTANCE_ID, null);

        assertTrue(result.isSuccess());
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(traceRepository).incrementReplayCount(eq(TRACE_ID), outcome.capture());
        assertTrue(outcome.getValue().startsWith("Status: 200"),
                "counter message should describe the response, got: " + outcome.getValue());
    }

    @Test
    @DisplayName("a connection failure still records the counter with the error")
    void connectionErrorRecordsCounter() {
        givenReplayableTrace();
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        RequestReplayService.ReplayResult result = service.executeReplay(TRACE_ID, INSTANCE_ID, null);

        assertFalse(result.isSuccess());
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(traceRepository).incrementReplayCount(eq(TRACE_ID), outcome.capture());
        assertTrue(outcome.getValue().startsWith("Error: "),
                "counter message should carry the failure, got: " + outcome.getValue());
    }

    @Test
    @DisplayName("the HTTP call completes before the counter is written")
    void httpPrecedesCounterWrite() {
        givenReplayableTrace();
        givenHttpReturns(HttpStatus.OK, "pong");

        service.executeReplay(TRACE_ID, INSTANCE_ID, null);

        // This ordering is the point of the refactor: the network wait must not sit inside the
        // transaction that writes the counter.
        InOrder ordered = inOrder(restTemplate, traceRepository);
        ordered.verify(restTemplate)
                .exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
        ordered.verify(traceRepository).incrementReplayCount(eq(TRACE_ID), anyString());
    }

    @Test
    @DisplayName("a counter write failure is logged, not thrown - the replay result must survive")
    void counterFailureIsContained() {
        doThrow(new IllegalStateException("db down"))
                .when(traceRepository).incrementReplayCount(anyLong(), anyString());

        assertDoesNotThrow(() -> service.recordReplayOutcome(TRACE_ID, "Status: 200, Latency: 3ms"));
    }

    @Test
    @DisplayName("a null trace id or outcome records nothing")
    void nullsRecordNothing() {
        service.recordReplayOutcome(null, "Status: 200");
        service.recordReplayOutcome(TRACE_ID, null);

        verifyNoInteractions(traceRepository);
    }

    @Test
    @DisplayName("a non-replayable trace short-circuits before any HTTP call or counter write")
    void nonReplayableShortCircuits() {
        RequestTrace trace = new RequestTrace();
        trace.setTraceId("uuid-2");
        trace.setMethod("GET");
        trace.setPath("/api/stream");
        trace.setReplayType("SSE");
        trace.setReplayable(true);
        when(traceRepository.findById(TRACE_ID)).thenReturn(Optional.of(trace));

        RequestReplayService.ReplayResult result = service.executeReplay(TRACE_ID, INSTANCE_ID, null);

        assertFalse(result.isSuccess());
        assertEquals("UNSUPPORTED_TYPE", result.getErrorType());
        verifyNoInteractions(restTemplate);
        verify(traceRepository, never()).incrementReplayCount(anyLong(), anyString());
    }
}
