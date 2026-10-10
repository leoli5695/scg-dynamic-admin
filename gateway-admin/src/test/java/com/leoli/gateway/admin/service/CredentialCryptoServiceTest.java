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

    @Test
    void newCiphertextIsVersionedV2() {
        CredentialCryptoService svc = serviceWithKey("test-passphrase-0123456789");
        assertTrue(svc.encrypt("sk-x").startsWith("v2:"),
                "new ciphertexts must carry the v2: key-version prefix");
    }

    /**
     * 点9 backward-compat: credentials stored before the PBKDF2 change are plain
     * base64(iv||ct) under a single-pass SHA-256 key, with no version prefix. The service
     * must still decrypt them so an upgrade does not orphan existing stored keys.
     */
    @Test
    void decryptsLegacyUnprefixedSha256Ciphertext() throws Exception {
        String passphrase = "test-passphrase-0123456789";
        String plain = "sk-legacy-abcdef123456";

        // Reproduce the pre-v2 scheme exactly: SHA-256(passphrase) -> AES-GCM -> base64(iv||ct).
        byte[] keyBytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(passphrase.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.crypto.spec.SecretKeySpec legacyKey = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, legacyKey,
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        String legacyCipher = java.util.Base64.getEncoder().encodeToString(out);
        assertFalse(legacyCipher.startsWith("v2:"), "sanity: legacy ciphertext is unprefixed");

        // The upgraded service (which encrypts as v2) must still read the legacy form.
        CredentialCryptoService svc = serviceWithKey(passphrase);
        assertEquals(plain, svc.decrypt(legacyCipher));
    }
}
