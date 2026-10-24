package com.leoli.gateway.admin.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AuditLogEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the audit-secret leak is closed at the serialisation boundary.
 *
 * <p>The vulnerability was: {@code StrategyController} writes
 * {@code objectMapper.writeValueAsString(strategy)} into {@code audit_logs.new_value}, a
 * {@code StrategyConfig} embeds {@code AuthConfig} whose {@code secretKey} / {@code apiKey} /
 * {@code clientSecret} are plain fields, {@code AuditLogService} did no masking, and
 * {@code GET /api/audit-logs/export/json} serialised whole entities for any authenticated user.
 * One request returned every credential the gateway had ever been configured with.
 *
 * <p>The entity-level test below goes through the same Jackson path the export used, so it fails
 * if the {@code @JsonSerialize} masker is removed - masking only the util would not catch that.
 */
class SensitiveDataMaskerTest {

    private static final String JWT_SECRET = "super-secret-jwt-signing-key-0123456789";
    private static final String API_KEY = "ak-live-99887766554433221100";
    private static final String CLIENT_SECRET = "oauth-client-secret-abcdef";

    /** Shape of a real STRATEGY audit row: secrets nested two levels down inside a list. */
    private static final String STRATEGY_SNAPSHOT = """
            {"strategyId":"st-1","strategyName":"auth","enabled":true,
             "authConfigs":[{"routeId":"r-1","authType":"JWT","enabled":true,
                             "secretKey":"%s","apiKey":"%s","clientId":"public-id",
                             "clientSecret":"%s","tokenEndpoint":"https://idp.example.com/token"}]}
            """.formatted(JWT_SECRET, API_KEY, CLIENT_SECRET);

    @Test
    @DisplayName("regression: serialising an audit entity no longer emits stored credentials")
    void entitySerialisationIsMasked() throws Exception {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setOperationType("UPDATE");
        entity.setTargetType("STRATEGY");
        entity.setNewValue(STRATEGY_SNAPSHOT);
        entity.setOldValue(STRATEGY_SNAPSHOT);

        // Same call the JSON export makes (it builds its own ObjectMapper, so the masking has to
        // live on the entity, not in a controller-local mapper configuration).
        String json = new ObjectMapper().writeValueAsString(entity);

        assertFalse(json.contains(JWT_SECRET), "JWT secretKey must not reach the HTTP response");
        assertFalse(json.contains(API_KEY), "apiKey must not reach the HTTP response");
        assertFalse(json.contains(CLIENT_SECRET), "OAuth clientSecret must not reach the HTTP response");
        // Non-secret fields must survive, or the audit trail is useless. (Plain substrings: the
        // snapshot is a String field, so its inner JSON is escaped in the serialised entity.)
        assertTrue(json.contains("st-1"), "non-sensitive fields must not be masked");
        assertTrue(json.contains("public-id"), "clientId is not a secret and must stay readable");
    }

    @Test
    @DisplayName("masking preserves structure and non-secret values")
    void maskingPreservesNonSecretContent() {
        String masked = SensitiveDataMasker.mask(STRATEGY_SNAPSHOT);

        assertFalse(masked.contains(JWT_SECRET));
        assertFalse(masked.contains(API_KEY));
        assertFalse(masked.contains(CLIENT_SECRET));
        assertTrue(masked.contains("\"strategyId\":\"st-1\""), "sibling fields keep their values");
        assertTrue(masked.contains("\"clientId\":\"public-id\""), "a client ID is not a client secret");
        assertTrue(masked.contains("\"enabled\":true"), "booleans must survive masking");
        assertTrue(masked.contains("\"authType\":\"JWT\""), "enum-ish strings must survive masking");
        // tokenEndpoint matches the "token" substring rule; losing it is acceptable and intended,
        // but assert it so a future widening of the rule set is a conscious decision.
        assertFalse(masked.contains("https://idp.example.com/token"));
    }

    @Test
    @DisplayName("the stored value itself is untouched, so rollback can still restore it")
    void storedValueIsNotMutated() {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setNewValue(STRATEGY_SNAPSHOT);

        SensitiveDataMasker.mask(entity.getNewValue());

        // AuditLogService.rollback restores configuration from oldValue. If masking happened on the
        // way in (or mutated the entity), a rollback would push "secretKey":"****" into Nacos and
        // break gateway authentication.
        assertEquals(STRATEGY_SNAPSHOT, entity.getNewValue());
        assertTrue(entity.getNewValue().contains(JWT_SECRET));
    }

    @Test
    @DisplayName("masking is recursive through objects and arrays")
    void masksNestedStructures() {
        String masked = SensitiveDataMasker.mask(
                "{\"a\":{\"b\":[{\"password\":\"p4ssw0rd-value\",\"keep\":\"visible\"}]}}");

        assertFalse(masked.contains("p4ssw0rd-value"));
        assertTrue(masked.contains("visible"));
        assertTrue(masked.contains("****"), "masked values keep the **** prefix");
    }

    @Test
    @DisplayName("key matching is case-insensitive and separator-agnostic")
    void keyMatchingIsNormalised() {
        assertTrue(SensitiveDataMasker.isSensitiveKey("apiKey"));
        assertTrue(SensitiveDataMasker.isSensitiveKey("api_key"));
        assertTrue(SensitiveDataMasker.isSensitiveKey("X-Api-Key"));
        assertTrue(SensitiveDataMasker.isSensitiveKey("SMTP_PASSWORD"));
        assertTrue(SensitiveDataMasker.isSensitiveKey("keystorePassword"));
        assertTrue(SensitiveDataMasker.isSensitiveKey("kubeconfig"));
        assertFalse(SensitiveDataMasker.isSensitiveKey("routeId"));
        assertFalse(SensitiveDataMasker.isSensitiveKey("strategyName"));
        assertFalse(SensitiveDataMasker.isSensitiveKey(null));
    }

    @Test
    @DisplayName("short secrets are fully masked, longer ones keep the last 4 for correlation")
    void maskValueShape() {
        assertEquals("****", SensitiveDataMasker.maskValue("abc"));
        assertEquals("****", SensitiveDataMasker.maskValue("abcd"));
        assertEquals("****cdef", SensitiveDataMasker.maskValue("abcdef"));
        assertNull(SensitiveDataMasker.maskValue(null));
    }

    @Test
    @DisplayName("non-JSON input falls back to string-pair masking instead of throwing")
    void nonJsonFallsBackSafely() {
        String masked = SensitiveDataMasker.mask("not json at all");
        assertEquals("not json at all", masked, "prose without key:value pairs passes through");

        String partial = SensitiveDataMasker.mask("prefix \"password\": \"hunter2-long\" suffix");
        assertFalse(partial.contains("hunter2-long"));
        assertTrue(partial.contains("prefix"));
    }

    @Test
    @DisplayName("null and blank pass through, so callers need no null guards")
    void nullAndBlankPassThrough() {
        assertNull(SensitiveDataMasker.mask(null));
        assertEquals("", SensitiveDataMasker.mask(""));
        assertEquals("   ", SensitiveDataMasker.mask("   "));
        assertNull(SensitiveDataMasker.maskTree(null));
    }
}
