package com.leoli.gateway.health;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.net.ServerSocket;
import static org.mockito.Mockito.*;

class ActiveHealthCheckerTest {
    @Test
    void reachableTcpPortDoesNotOverrideFailedHttpReadiness() throws Exception {
        try (var socket = new ServerSocket(0)) {
            var health = mock(HybridHealthChecker.class);
            var rest = mock(RestTemplate.class);
            var checker = configured(health, rest, true);
            String url = "http://127.0.0.1:" + socket.getLocalPort() + "/ready";
            when(rest.getForEntity(url, String.class)).thenReturn(ResponseEntity.status(503).body("down"));
            checker.probe("svc", "127.0.0.1", socket.getLocalPort());
            verify(health).markUnhealthy(eq("svc"), eq("127.0.0.1"), eq(socket.getLocalPort()), contains("HTTP_UNHEALTHY"), eq("ACTIVE"));
            verify(health, never()).markHealthy(anyString(), anyString(), anyInt(), anyString());
        }
    }

    @Test
    void successfulHttpProbeConfirmsRecovery() throws Exception {
        try (var socket = new ServerSocket(0)) {
            var health = mock(HybridHealthChecker.class);
            var rest = mock(RestTemplate.class);
            var checker = configured(health, rest, true);
            when(rest.getForEntity(anyString(), eq(String.class))).thenReturn(ResponseEntity.ok("up"));
            checker.probe("svc", "127.0.0.1", socket.getLocalPort());
            verify(health).markHealthy("svc", "127.0.0.1", socket.getLocalPort(), "ACTIVE");
        }
    }

    @Test
    void tcpFailureMarksUnhealthyWithoutCallingHttp() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var health = mock(HybridHealthChecker.class);
        var rest = mock(RestTemplate.class);
        configured(health, rest, true).probe("svc", "127.0.0.1", port);
        verify(health).markUnhealthy(eq("svc"), eq("127.0.0.1"), eq(port), contains("TCP_UNREACHABLE"), eq("ACTIVE"));
        verifyNoInteractions(rest);
    }

    @Test
    void missingHttpClientDoesNotSilentlyDowngradeAnEnabledHttpCheckToTcp() throws Exception {
        try (var socket = new ServerSocket(0)) {
            var health = mock(HybridHealthChecker.class);
            configured(health, null, true).probe("svc", "127.0.0.1", socket.getLocalPort());
            verify(health).markUnhealthy(eq("svc"), eq("127.0.0.1"), eq(socket.getLocalPort()), contains("HTTP_CHECK_UNAVAILABLE"), eq("ACTIVE"));
        }
    }

    @Test
    void disabledHealthChecksDoNotProbeOrChangeObservedState() {
        var health = mock(HybridHealthChecker.class);
        var checker = configured(health, null, false);
        ReflectionTestUtils.setField(checker, "checksEnabled", false);
        checker.probe("svc", "127.0.0.1", 1);
        verifyNoInteractions(health);
    }

    private ActiveHealthChecker configured(HybridHealthChecker health, RestTemplate rest, boolean http) {
        var checker = new ActiveHealthChecker();
        ReflectionTestUtils.setField(checker, "hybridHealthChecker", health);
        ReflectionTestUtils.setField(checker, "healthCheckRestTemplate", rest);
        ReflectionTestUtils.setField(checker, "timeoutMs", 300);
        ReflectionTestUtils.setField(checker, "httpCheckEnabled", http);
        ReflectionTestUtils.setField(checker, "healthCheckPath", "/ready");
        return checker;
    }
}
