package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-GCM encryption for provider API keys at rest.
 * <p>
 * The passphrase comes from {@code gateway.admin.security.credential-key}
 * (env {@code GATEWAY_ADMIN_SECURITY_CREDENTIAL_KEY}). When unset, the service
 * still lets the app boot but {@link #encrypt}/{@link #decrypt} fail closed -
 * a missing key can never silently fall back to storing plaintext.
 *
 * @author leoli
 */
@Slf4j
@Service
public class CredentialCryptoService {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCryptoService(GatewayAdminProperties properties) {
        String secret = properties.getSecurity().getCredentialKey();
        if (secret == null || secret.isBlank()) {
            this.key = null;
            log.warn("gateway.admin.security.credential-key is NOT configured - provider credential "
                    + "encrypt/decrypt will fail closed. Set GATEWAY_ADMIN_SECURITY_CREDENTIAL_KEY "
                    + "(generate with: openssl rand -base64 32) before using the AI proxy.");
        } else {
            this.key = deriveKey(secret);
            log.info("Provider credential encryption enabled (AES-GCM-256)");
        }
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to derive credential encryption key", e);
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    /** Encrypt plaintext, returning base64(iv || ciphertext). */
    public String encrypt(String plaintext) {
        requireKey();
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext is null");
        }
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    /** Decrypt base64(iv || ciphertext) back to plaintext. */
    public String decrypt(String encoded) {
        requireKey();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("ciphertext is empty");
        }
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            if (in.length <= GCM_IV_LEN) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = Arrays.copyOfRange(in, 0, GCM_IV_LEN);
            byte[] ct = Arrays.copyOfRange(in, GCM_IV_LEN, in.length);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt credential (wrong key or tampered data)", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed credential ciphertext", e);
        }
    }

    /** Masked display form: keep only the last 4 chars. */
    public static String mask(String plaintext) {
        if (plaintext == null || plaintext.length() <= 4) {
            return "****";
        }
        return "****" + plaintext.substring(plaintext.length() - 4);
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("gateway.admin.security.credential-key is not configured; "
                    + "cannot encrypt/decrypt provider credentials");
        }
    }
}
