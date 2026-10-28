package com.leoli.gateway.admin.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AiSecurityStrategyValidatorTest {
    @Test void acceptsLocalAndExternalConfiguration() {
        assertDoesNotThrow(() -> AiSecurityStrategyValidator.validate(Map.of("bannedWords", List.of("test"),
                "externalUrl", "https://example.test/check", "externalAdapter", "MY_PROVIDER",
                "externalHeaders", Map.of("Authorization", "${MODERATION_AUTH}"))));
    }
    @Test void rejectsBrokenRulesAndUnsafeConfiguration() {
        for (Map<String, Object> config : List.<Map<String, Object>>of(
                Map.of("bannedPatterns", List.of("[")), Map.of("bannedWords", "not-an-array"),
                Map.of("externalTimeoutMs", -1), Map.of("externalFailureAction", "ALLOW"),
                Map.of("externalUrl", "file:///tmp/check"), Map.of("externalUrl", "https://user:secret@example.test"),
                Map.of("externalHeaders", Map.of("Authorization", "secret\r\nInjected: true")),
                Map.of("piiTypes", List.of("UNSUPPORTED")))) {
            assertThrows(IllegalArgumentException.class, () -> AiSecurityStrategyValidator.validate(config));
        }
    }
}
