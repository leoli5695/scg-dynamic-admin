package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.health.ActiveHealthChecker;
import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.health.InstanceHealth;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeSelectionPolicyTest {
    private final HybridHealthChecker health = mock(HybridHealthChecker.class);
    private final ActiveHealthChecker active = mock(ActiveHealthChecker.class);
    private final InstanceFilter filter = new InstanceFilter(health, active);
    private final InstanceRetryExecutor retry = new InstanceRetryExecutor(filter, health);
    private final ServiceInstance one = instance(8001, true);
    private final ServiceInstance two = instance(8002, true);
    private final ServiceInstance disabled = instance(8003, false);

    private ServiceInstance instance(int port, boolean enabled) {
        var instance = new DefaultServiceInstance("node-" + port, "svc", "127.0.0.1", port, false);
        instance.getMetadata().put("enabled", String.valueOf(enabled));
        return instance;
    }

    private void status(ServiceInstance instance, boolean healthy) {
        var observation = new InstanceHealth();
        observation.setHealthy(healthy); observation.setCheckType("ACTIVE");
        when(health.getHealth("svc", instance.getHost(), instance.getPort())).thenReturn(observation);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    }

    @Test
    void disabledNodesNeverParticipateEvenIfTheyAreTheOnlyHealthyNodes() {
        status(one, false); status(two, false); status(disabled, true);
        assertEquals(List.of(one, two), filter.filter(List.of(disabled, one, two)));
        assertTrue(filter.filter(List.of(disabled)).isEmpty());
        assertEquals(two, filter.findAlternative("svc", List.of(disabled, one, two), Set.of("127.0.0.1:8001")));
        verifyNoInteractions(active);
    }

    @Test
    void healthyNodesExcludeUnhealthyAndUnknownNodes() {
        status(one, true); status(two, false);
        var unknown = instance(8004, true);
        assertEquals(List.of(one), filter.filter(List.of(two, unknown, disabled, one)));
        verifyNoInteractions(active);
    }

    @Test
    void failedPendingProbeStillAllowsLastResortRetry() {
        status(one, false);
        assertEquals(two, filter.findAlternative("svc", List.of(one, two, disabled), Set.of("127.0.0.1:8001")));
        verify(active).probe("svc", "127.0.0.1", 8002);
    }

    @Test
    void allUnhealthyNodesAreActuallyAttemptedOnceInsteadOfSkippedBeforeSending() {
        status(one, false); status(two, false);
        var attempted = new ArrayList<Integer>();
        retry.execute(exchange(), forwarded -> {
            ServiceInstance selected = forwarded.getRequiredAttribute("selected_instance");
            attempted.add(selected.getPort());
            return selected.getPort() == 8001 ? Mono.error(new IOException("offline")) : Mono.empty();
        }, "svc", one, List.of(one, two, disabled), new HashSet<>()).block(Duration.ofSeconds(5));
        assertEquals(List.of(8001, 8002), attempted);
    }

    @Test
    void healthyAlternativeIsTriedBeforeUnhealthyCandidates() {
        status(one, false); status(two, true);
        retry.execute(exchange(), forwarded -> {
            assertEquals(two, forwarded.getAttribute("selected_instance"));
            return Mono.empty();
        }, "svc", one, List.of(one, two, disabled), new HashSet<>()).block(Duration.ofSeconds(5));
        verify(health, never()).recordSuccess("svc", "127.0.0.1", 8001);
    }

    @Test
    void lostHealthObservationDoesNotTakePriorityOverConfirmedHealthyAlternative() {
        status(two, true);
        retry.execute(exchange(), forwarded -> {
            assertEquals(two, forwarded.getAttribute("selected_instance"));
            return Mono.empty();
        }, "svc", one, List.of(one, two), new HashSet<>()).block(Duration.ofSeconds(5));
    }

    @Test
    void retryReadsCurrentConfigurationAndCannotUseNewlyDisabledNodes() {
        status(one, true); status(two, true);
        var three = instance(8004, true); status(three, true);
        var current = new AtomicReference<>(List.of(one, two));
        var attempted = new ArrayList<Integer>();
        retry.execute(exchange(), forwarded -> {
            ServiceInstance selected = forwarded.getRequiredAttribute("selected_instance");
            attempted.add(selected.getPort());
            if (selected.getPort() == 8001) {
                current.set(List.of(instance(8002, false), three));
                return Mono.error(new IOException("config changed while first backend failed"));
            }
            return Mono.empty();
        }, "svc", one, current::get, new HashSet<>()).block(Duration.ofSeconds(5));
        assertEquals(List.of(8001, 8004), attempted);
    }

    @Test
    void staleInitialSelectionCannotSendToRemovedOrDisabledNode() {
        for (var current : List.of(List.<ServiceInstance>of(), List.of(instance(8001, false)))) {
            var error = assertThrows(ResponseStatusException.class, () -> retry.execute(exchange(), forwarded -> {
                fail("No request may reach this backend"); return Mono.empty();
            }, "svc", one, () -> current, new HashSet<>()).block(Duration.ofSeconds(5)));
            assertEquals(503, error.getStatusCode().value());
        }
    }

    @Test
    void upstream5xxIsNotReportedAsHealthySuccess() {
        var exchange = exchange();
        retry.execute(exchange, forwarded -> {
            forwarded.getResponse().setStatusCode(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            return Mono.empty();
        }, "svc", one, List.of(one), new HashSet<>()).block(Duration.ofSeconds(5));
        verify(health).recordFailure("svc", "127.0.0.1", 8001);
        verify(health, never()).recordSuccess(anyString(), anyString(), anyInt());
    }
}
