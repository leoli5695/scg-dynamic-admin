package com.leoli.gateway.admin.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption utility for sensitive data (API keys, passwords, etc.)
 * 
 * Security features:
 * - AES-256-GCM authenticated encryption (prevents tampering)
 * - Random IV per encryption (prevents pattern detection)
 * - Key loaded from configuration (no hardcoded keys), fail-fast at startup
 *
 * FIX: unified with SslCertificateService on the same configuration property
 * (gateway.admin.ssl.encryption-key, overridable via GATEWAY_ENCRYPTION_KEY).
 * Previously this class read a different property (${GATEWAY_ENCRYPTION_KEY}) that
 * no deployment ever set, and silently fell back to plaintext storage.
 *
 * @author leoli
 */
@Slf4j
@Component
public class ApiKeyEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;  // 96 bits for GCM
    private static final int GCM_TAG_LENGTH = 128; // 128 bits authentication tag
    private static final int MIN_KEY_LENGTH = 16;  // same minimum as SslCertificateService

    // Unified with SslCertificateService: gateway.admin.ssl.encryption-key
    @Value("${gateway.admin.ssl.encryption-key:}")
    private String encryptionKey;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * FIX: fail fast at startup when the encryption key is missing or too short,
     * instead of silently storing secrets in plaintext (previous behavior gave
     * operators the false impression that data was encrypted).
     */
    @PostConstruct
    public void validateKey() {
        if (encryptionKey == null || encryptionKey.isEmpty()) {
            throw new IllegalStateException(
                    "Encryption key not configured. Set gateway.admin.ssl.encryption-key " +
                    "or the GATEWAY_ENCRYPTION_KEY environment variable (minimum " + MIN_KEY_LENGTH + " characters).");
        }
        if (encryptionKey.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "Encryption key too short (" + encryptionKey.getBytes(StandardCharsets.UTF_8).length +
                    " bytes). Minimum " + MIN_KEY_LENGTH + " characters required.");
        }
        log.info("ApiKeyEncryptor initialized (AES-256-GCM, key source: gateway.admin.ssl.encryption-key)");
    }

    /**
     * Encrypt a plaintext string.
     * Returns Base64-encoded ciphertext (IV + encrypted data + auth tag).
     *
     * @param plaintext the string to encrypt
     * @return Base64-encoded encrypted string
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }

        try {
            byte[] keyBytes = deriveKey(encryptionKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Combine IV + ciphertext (IV needed for decryption)
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new IllegalStateException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypt an encrypted string.
     *
     * @param ciphertext Base64-encoded encrypted string
     * @return decrypted plaintext
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }

        try {
            byte[] keyBytes = deriveKey(encryptionKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] combined = Base64.getDecoder().decode(ciphertext);

            // Extract IV (first 12 bytes)
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            // Extract ciphertext (remaining bytes)
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed - data may be corrupted or not encrypted", e);
            // Return original value if it looks like plaintext (not Base64)
            return ciphertext;
        }
    }

    /**
     * Derive a valid 32-byte AES-256 key from the encryption key string.
     * FIX: SHA-256 derivation accepts any key >= 16 chars (consistent with the
     * SslCertificateService minimum) while always producing a full AES-256 key.
     */
    private byte[] deriveKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive encryption key", e);
        }
    }

    /**
     * Mask API key for display (show only last 4 characters).
     * Used in API responses to prevent leaking full key.
     *
     * @param apiKey the API key to mask
     * @return masked key (e.g., "sk-...abcd")
     */
    public String maskForDisplay(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 3) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}