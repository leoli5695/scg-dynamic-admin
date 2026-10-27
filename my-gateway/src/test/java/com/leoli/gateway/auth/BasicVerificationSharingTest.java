package com.leoli.gateway.auth;

import com.leoli.gateway.model.AuthConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BasicVerificationSharingTest {
    private static class CountingProcessor extends BasicAuthProcessor {
        final AtomicInteger checks = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(0);

        @Override
        protected Mono<Boolean> validateCredentials(String username, String password, AuthConfig config) {
            checks.incrementAndGet(); started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) return Mono.error(new IllegalStateException("test timeout"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return Mono.error(e);
            }
            return super.validateCredentials(username, password, config);
        }
    }

    private AuthConfig policy() {
        var policy = new AuthConfig();
        policy.setPolicyId("policy-one");
        policy.setAuthType("BASIC");
        policy.setBasicUsername("alice");
        policy.setBasicPassword(BCrypt.hashpw("correct", BCrypt.gensalt(4)));
        policy.setPasswordHashAlgorithm("BCRYPT");
        return policy;
    }

    private MockServerWebExchange request(String password) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/private")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("alice:" + password).getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void concurrentIdenticalCredentialsShareOneCheckAndSuccessfulCacheIsOffByDefault() throws Exception {
        var processor = new CountingProcessor();
        var policy = policy();
        processor.release = new CountDownLatch(1);
        var exchanges = new ArrayList<MockServerWebExchange>();
        var work = new ArrayList<Mono<Void>>();
        for (int i = 0; i < 32; i++) {
            var exchange = request("correct"); exchanges.add(exchange);
            work.add(processor.process(exchange, policy));
        }
        var all = Mono.when(work).toFuture();
        try {
            assertTrue(processor.started.await(5, TimeUnit.SECONDS));
            assertEquals(1, processor.checks.get());
            Map<?, ?> pending = (Map<?, ?>) ReflectionTestUtils.getField(processor, "verifications");
            assertEquals(1, pending.size());
            assertTrue(pending.keySet().iterator().next().toString().matches("[0-9a-f]{64}"));
        } finally {
            processor.release.countDown();
        }
        all.get(5, TimeUnit.SECONDS);
        exchanges.forEach(exchange -> assertEquals("alice", exchange.getAttribute("auth_user")));
        processor.process(request("correct"), policy).block();
        assertEquals(2, processor.checks.get(), "later requests must verify again when result caching is disabled");
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(processor, "verifications")).isEmpty());
    }

    @Test
    void changedPasswordDuringVerificationRejectsTheOldSnapshot() throws Exception {
        var processor = new CountingProcessor();
        var policy = policy();
        processor.release = new CountDownLatch(1);
        var pending = processor.process(request("correct"), policy).toFuture();
        try {
            assertTrue(processor.started.await(5, TimeUnit.SECONDS));
            policy.setBasicPassword(BCrypt.hashpw("changed", BCrypt.gensalt(4)));
        } finally {
            processor.release.countDown();
        }
        assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        processor.process(request("changed"), policy).block();
        assertEquals(2, processor.checks.get());
    }

    @Test
    void optInReuseIsBoundToPasswordAlgorithmAndPolicyEnabledState() {
        var processor = new CountingProcessor();
        ReflectionTestUtils.setField(processor, "successCacheTtlMs", 5000L);
        var policy = policy();
        processor.process(request("correct"), policy).block();
        processor.process(request("correct"), policy).block();
        assertEquals(1, processor.checks.get());
        assertThrows(RuntimeException.class, () -> processor.process(request("wrong"), policy).block());
        assertThrows(RuntimeException.class, () -> processor.process(request("wrong"), policy).block());
        assertEquals(3, processor.checks.get(), "wrong passwords must not enter the success cache");
        policy.setBasicPassword(BCrypt.hashpw("changed", BCrypt.gensalt(4)));
        assertThrows(RuntimeException.class, () -> processor.process(request("correct"), policy).block());
        processor.process(request("changed"), policy).block();
        int checked = processor.checks.get();
        policy.setEnabled(false);
        assertThrows(RuntimeException.class, () -> processor.process(request("changed"), policy).block());
        assertEquals(checked, processor.checks.get());
        policy.setEnabled(true);
        policy.setPasswordHashAlgorithm("UNKNOWN");
        assertThrows(RuntimeException.class, () -> processor.process(request("changed"), policy).block());
    }

    @Test
    void expiredSuccessAndDifferentPolicyRequireVerification() {
        var processor = new CountingProcessor();
        ReflectionTestUtils.setField(processor, "successCacheTtlMs", 5000L);
        var policy = policy();
        processor.process(request("correct"), policy).block();
        @SuppressWarnings("unchecked")
        var cache = (com.github.benmanes.caffeine.cache.Cache<String, Long>)
                ReflectionTestUtils.getField(processor, "successfulChecks");
        cache.asMap().replaceAll((key, until) -> System.nanoTime() - 1);
        processor.process(request("correct"), policy).block();
        assertEquals(2, processor.checks.get());
        var otherPolicy = policy();
        otherPolicy.setPolicyId("policy-two");
        otherPolicy.setBasicPassword(policy.getBasicPassword());
        processor.process(request("correct"), otherPolicy).block();
        assertEquals(3, processor.checks.get());
    }

    @Test
    void cancellingOneWaiterDoesNotCancelSharedVerificationOrLeakAdmissionSlot() throws Exception {
        var processor = new CountingProcessor();
        var policy = policy();
        processor.release = new CountDownLatch(1);
        var cancelled = processor.process(request("correct"), policy).subscribe();
        assertTrue(processor.started.await(5, TimeUnit.SECONDS));
        var survivor = processor.process(request("correct"), policy).toFuture();
        cancelled.dispose();
        processor.release.countDown();
        survivor.get(5, TimeUnit.SECONDS);
        assertEquals(1, processor.checks.get());
        assertEquals(512, ((java.util.concurrent.Semaphore) ReflectionTestUtils
                .getField(processor, "pendingChecks")).availablePermits());
    }

    @Test
    void disablingCoalescingAndAdmissionSaturationNeverSkipVerification() throws Exception {
        var processor = new CountingProcessor();
        var policy = policy();
        ReflectionTestUtils.setField(processor, "coalesceEnabled", false);
        Mono.when(processor.process(request("correct"), policy), processor.process(request("correct"), policy)).block();
        assertEquals(2, processor.checks.get());
        ReflectionTestUtils.setField(processor, "coalesceEnabled", true);
        var slots = (java.util.concurrent.Semaphore) ReflectionTestUtils.getField(processor, "pendingChecks");
        slots.acquire(512);
        try {
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> processor.process(request("correct"), policy).block());
            assertEquals(2, processor.checks.get());
        } finally {
            slots.release(512);
        }
    }
}
