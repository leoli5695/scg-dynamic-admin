package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and fail-closed behaviour of the provider-key encryptor.
 */
class CredentialCryptoServiceTest {

    private static CredentialCryptoService serviceWithKey(String key) {
        GatewayAdminProperties props = new GatewayAdminProperties();
        props.getSecurity().setCredentialKey(key);
        return new CredentialCryptoService(props);
    }

    @Test
    void encryptDecryptRoundTrips() {
        CredentialCryptoService svc = serviceWithKey("test-passphrase-0123456789");
        String plain = "sk-deepseek-abcdef123456";
        String cipher = svc.encrypt(plain);
        assertNotEquals(plain, cipher);
        assertEquals(plain, svc.decrypt(cipher));
    }

    @Test
    void encryptionIsNonDeterministicPerCall() {
        CredentialCryptoService svc = serviceWithKey("test-passphrase-0123456789");
        String plain = "same-key";
        assertNotEquals(svc.encrypt(plain), svc.encrypt(plain), "random IV must yield distinct ciphertexts");
    }

    @Test
    void wrongKeyFailsToDecrypt() {
        String cipher = serviceWithKey("key-A").encrypt("secret");
        CredentialCryptoService other = serviceWithKey("key-B");
        assertThrows(IllegalStateException.class, () -> other.decrypt(cipher));
    }

    @Test
    void unconfiguredKeyFailsClosed() {
        CredentialCryptoService svc = serviceWithKey("");
        assertFalse(svc.isConfigured());
        assertThrows(IllegalStateException.class, () -> svc.encrypt("x"));
        assertThrows(IllegalStateException.class, () -> svc.decrypt("x"));
    }

    @Test
    void maskKeepsOnlyLastFour() {
        assertEquals("****3456", CredentialCryptoService.mask("sk-123456abcdef3456"));
        assertEquals("****", CredentialCryptoService.mask("abc"));
        assertEquals("****", CredentialCryptoService.mask(null));
        assertTrue(CredentialCryptoService.mask("sk-123456abcdef3456").startsWith("****"));
    }
}
