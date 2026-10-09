package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CROSS-MODULE CONTRACT (writer side).
 * <p>
 * The gateway's {@code TokenRevocationChecker} (my-gateway) reads the blacklist that
 * this service writes. The two modules share no common library, so the key protocol
 * is duplicated and kept in sync by hand. This test pins the writer to the exact
 * protocol literals; the mirror-image reader-side contract test
 * ({@code TokenRevocationCheckerContractTest} in my-gateway) pins the reader to the
 * SAME literals. If either side drifts, that side's build fails — turning the
 * "Must stay in sync" comment into an executable assertion.
 * <p>
 * Protocol (must match the reader test byte-for-byte):
 * <ul>
 *   <li>blacklist key = {@value #KEY_PREFIX} + id, where id is the jti and/or the
 *       lowercase SHA-256 hex of the raw token</li>
 *   <li>runtime switch key = {@value #RUNTIME_FLAG_KEY}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GatewayTokenRevocationServiceContractTest {

    /** Protocol literals — duplicated verbatim in the reader-side contract test. */
    private static final String KEY_PREFIX = "gateway:jwt:revoked:";
    private static final String RUNTIME_FLAG_KEY = "gateway:jwt:revocation:enabled";

    /** Fixed inputs; TOKEN_HASH is sha256Hex(TOKEN) computed independently of production code. */
    private static final String TOKEN = "contract-test-jwt-token";
    private static final String JTI = "jti-abc-123";
    private static final String TOKEN_HASH =
            "61ffb536af9f0b7be12ce53640710aa47f312b3e9a973c271f3661874274445b";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private GatewayTokenRevocationService service;

    @BeforeEach
    void setUp() {
        service = new GatewayTokenRevocationService();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    @Test
    @DisplayName("revoke() writes the exact keys the gateway reader looks up (jti + token hash)")
    void revoke_writesProtocolKeys() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.revoke(TOKEN, JTI, 3600L);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2))
                .set(keys.capture(), eq("1"), eq(Duration.ofSeconds(3600)));

        assertEquals(List.of(KEY_PREFIX + JTI, KEY_PREFIX + TOKEN_HASH), keys.getAllValues(),
                "writer must produce exactly the blacklist keys the reader queries");
    }

    @Test
    @DisplayName("isRevoked() checks the same prefixed key the writer produced")
    void isRevoked_readsProtocolKey() {
        when(redisTemplate.hasKey(KEY_PREFIX + JTI)).thenReturn(true);

        org.junit.jupiter.api.Assertions.assertTrue(service.isRevoked(JTI));
        verify(redisTemplate).hasKey(KEY_PREFIX + JTI);
    }

    @Test
    @DisplayName("runtime switch is stored under the agreed flag key")
    void setCheckEnabled_usesProtocolFlagKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.setCheckEnabled(false);

        verify(valueOps).set(RUNTIME_FLAG_KEY, "false");
    }
}
