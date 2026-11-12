package com.leoli.gateway.admin.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract shared with my-gateway's SensitiveHeadersTest. The two modules have no common
 * dependency, so the tables must stay identical; changing one without the other is the bug.
 */
class SensitiveHeadersTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Authorization", "authorization", "Proxy-Authorization", "X-Authorization",
            "Cookie", "Set-Cookie",
            "X-API-Key", "Api-Key", "ApiKey", "X-ApiKey", "x_api_key", "X-Api-Key ",
            "X-Access-Key", "X-AccessKey", "Access-Key", "AccessKey",
            "X-Auth-Token", "X-Access-Token", "X-Refresh-Token", "X-Id-Token",
            "X-Secret", "X-Client-Secret", "X-Secret-Key",
            "X-Password", "X-User-Password",
            "X-Credential", "X-Credentials",
            "X-Signature", "X-Hmac-Signature",
            "X-Private-Key"
    })
    void credentialHeadersAreSensitiveRegardlessOfSpelling(String header) {
        assertTrue(SensitiveHeaders.isSensitive(header), header + " must be masked");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Content-Type", "Accept", "User-Agent", "Content-Length", "ETag",
            "X-Request-Id", "X-Trace-Id", "X-Forwarded-For", "X-Real-IP",
            "X-Timestamp", "X-Nonce", "X-Client-Id", "X-RateLimit-Remaining"
    })
    void diagnosticHeadersStayReadable(String header) {
        assertFalse(SensitiveHeaders.isSensitive(header), header + " must stay readable");
    }

    @Test
    void nullAndEmptyAreNotSensitive() {
        assertFalse(SensitiveHeaders.isSensitive(null));
        assertFalse(SensitiveHeaders.isSensitive(""));
    }
}