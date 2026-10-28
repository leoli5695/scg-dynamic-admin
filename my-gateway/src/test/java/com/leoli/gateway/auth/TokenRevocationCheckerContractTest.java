package com.leoli.gateway.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CROSS-MODULE CONTRACT (reader side).
 * <p>
 * Mirror image of {@code GatewayTokenRevocationServiceContractTest} in gateway-admin.
 * The admin console writes the JWT blacklist; this checker reads it. The two modules
 * share no common library, so both contract tests pin the SAME protocol literals —
 * if either the writer or the reader drifts, that module's build fails. Together they
 * make "write side writes → read side finds it" an executable guarantee instead of a
 * "Must stay in sync" comment.
 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationCheckerContractTest {

    @Test
    void coalescesConcurrentChecksIncludingTheRuntimeSwitch() {
        var flag = reactor.core.publisher.Sinks.<String>one();
        var verdict = reactor.core.publisher.Sinks.<Boolean>one();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RUNTIME_FLAG_KEY)).thenReturn(flag.asMono());
        when(redisTemplate.hasKey(anyString())).thenReturn(verdict.asMono());
        var values = new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
        reactor.core.publisher.Flux.range(0, 64).flatMap(i -> checker.isRevoked(null, TOKEN), 64).subscribe(values::add);
        verify(valueOps, times(1)).get(RUNTIME_FLAG_KEY);
        flag.tryEmitEmpty();
        verify(redisTemplate, times(1)).hasKey(anyString());
        verdict.tryEmitValue(false);
        assertEquals(64, values.size());
        assertEquals(false, checker.isRevoked(null, TOKEN).block());
        verify(redisTemplate, times(1)).hasKey(anyString());
    }

    @Test
    void redisFailureRejectsInsteadOfAllowingAndBrieflyFailsFast() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RUNTIME_FLAG_KEY)).thenReturn(Mono.empty());
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("offline")));
        StepVerifier.create(checker.isRevoked(null, TOKEN)).expectError(RevocationUnavailableException.class).verify();
        StepVerifier.create(checker.isRevoked(null, TOKEN)).expectError(RevocationUnavailableException.class).verify();
        verify(redisTemplate, times(1)).hasKey(anyString());
    }

    @Test
    void totalLookupHasABoundedDeadline() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RUNTIME_FLAG_KEY)).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> checker.isRevoked(null, TOKEN))
                .thenAwait(java.time.Duration.ofMillis(501))
                .expectError(RevocationUnavailableException.class).verify();
    }

    @Test
    void invalidationPreventsAnOlderLookupFromRepopulatingTheCache() {
        var old = reactor.core.publisher.Sinks.<Boolean>one();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RUNTIME_FLAG_KEY)).thenReturn(Mono.empty());
        when(redisTemplate.hasKey(anyString())).thenReturn(old.asMono(), Mono.just(true));
        checker.isRevoked(null, TOKEN).subscribe();
        checker.invalidateLocalCache();
        assertEquals(true, checker.isRevoked(null, TOKEN).block());
        old.tryEmitValue(false);
        assertEquals(true, checker.isRevoked(null, TOKEN).block());
        verify(redisTemplate, times(2)).hasKey(anyString());
    }

    /** Protocol literals — duplicated verbatim in the writer-side contract test. */
    private static final String KEY_PREFIX = "gateway:jwt:revoked:";
    private static final String RUNTIME_FLAG_KEY = "gateway:jwt:revocation:enabled";

    private static final String TOKEN = "contract-test-jwt-token";
    private static final String JTI = "jti-abc-123";
    private static final String TOKEN_HASH =
            "61ffb536af9f0b7be12ce53640710aa47f312b3e9a973c271f3661874274445b";

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;
    @Mock
    private ReactiveValueOperations<String, String> valueOps;
    @Mock
    private Claims claims;

    private TokenRevocationChecker checker;

    @BeforeEach
    void setUp() {
        checker = new TokenRevocationChecker();
        ReflectionTestUtils.setField(checker, "reactiveRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(checker, "enabled", true);
    }

    @Test
    @DisplayName("isRevoked() queries the exact keys the admin writer produces (jti + token hash)")
    void isRevoked_queriesProtocolKeys() {
        when(claims.getId()).thenReturn(JTI);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RUNTIME_FLAG_KEY)).thenReturn(Mono.empty()); // absent => enabled
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        StepVerifier.create(checker.isRevoked(claims, TOKEN))
                .expectNext(Boolean.FALSE)
                .verifyComplete();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, times(2)).hasKey(keys.capture());
        assertEquals(List.of(KEY_PREFIX + JTI, KEY_PREFIX + TOKEN_HASH), keys.getAllValues(),
                "reader must query exactly the blacklist keys the writer wrote");
    }

    @Test
    @DisplayName("A key written by the admin console is honoured as revoked by the gateway")
    void revokedKeyWrittenByAdmin_isRejected() {
        when(claims.getId()).thenReturn(JTI);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RUNTIME_FLAG_KEY)).thenReturn(Mono.empty());
        // The admin writer sets KEY_PREFIX + jti; the reader must find that exact key.
        when(redisTemplate.hasKey(KEY_PREFIX + JTI)).thenReturn(Mono.just(true));
        lenient().when(redisTemplate.hasKey(KEY_PREFIX + TOKEN_HASH)).thenReturn(Mono.just(false));

        StepVerifier.create(checker.isRevoked(claims, TOKEN))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    @DisplayName("The runtime on/off switch is read from the agreed flag key")
    void runtimeSwitch_usesProtocolFlagKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Console flipped the switch off under RUNTIME_FLAG_KEY => gateway must not reject.
        when(valueOps.get(RUNTIME_FLAG_KEY)).thenReturn(Mono.just("false"));

        StepVerifier.create(checker.isRevoked(claims, TOKEN))
                .expectNext(Boolean.FALSE)
                .verifyComplete();

        verify(valueOps).get(RUNTIME_FLAG_KEY);
    }
}
