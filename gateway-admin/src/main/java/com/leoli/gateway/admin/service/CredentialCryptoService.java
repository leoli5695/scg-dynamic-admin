package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
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
 * <p>
 * Key derivation (点9): new ciphertexts are {@code v2:base64(salt || iv || ct)} with the AES key
 * derived via PBKDF2WithHmacSHA256 (210k iterations, random 16-byte salt) instead of a single
 * unsalted SHA-256, so a weak passphrase is no longer trivially dictionary/GPU-attackable. The
 * {@code v2:} prefix is also the key-version hook for future master-key rotation. Ciphertexts
 * written before this change carry no prefix and are still decrypted with the legacy SHA-256 key,
 * so existing stored credentials keep working; they are upgraded to v2 on the next re-encrypt.
 *
 * @author leoli
 */
@Slf4j
@Service
public class CredentialCryptoService {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Ciphertext version prefix; also the key-version hook for future rotation. */
    private static final String V2_PREFIX = "v2:";
    private static final int PBKDF2_SALT_LEN = 16;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int PBKDF2_KEY_BITS = 256;
    /** Passphrases shorter than this are warned about (not rejected, to avoid bricking a live deploy). */
    private static final int RECOMMENDED_SECRET_LEN = 32;

    /** Raw passphrase, retained for per-ciphertext PBKDF2 derivation (null when unconfigured). */
    private final String secret;
    /** Legacy SHA-256-derived key, kept only to decrypt pre-v2 ciphertexts. */
    private final SecretKeySpec legacyKey;
    private final SecureRandom random = new SecureRandom();

    public CredentialCryptoService(GatewayAdminProperties properties) {
        String configured = properties.getSecurity().getCredentialKey();
        if (configured == null || configured.isBlank()) {
            this.secret = null;
            this.legacyKey = null;
            log.warn("gateway.admin.security.credential-key is NOT configured - provider credential "
                    + "encrypt/decrypt will fail closed. Set GATEWAY_ADMIN_SECURITY_CREDENTIAL_KEY "
                    + "(generate with: openssl rand -base64 32) before using the AI proxy.");
        } else {
            this.secret = configured;
            this.legacyKey = deriveLegacyKey(configured);
            if (configured.length() < RECOMMENDED_SECRET_LEN) {
                log.warn("gateway.admin.security.credential-key is only {} chars; >= {} chars "
                        + "(openssl rand -base64 32) is strongly recommended - a short passphrase weakens "
                        + "PBKDF2 against dictionary/GPU attacks.", configured.length(), RECOMMENDED_SECRET_LEN);
            }
            log.info("Provider credential encryption enabled (AES-GCM-256, PBKDF2 key derivation)");
        }
    }

    /** Legacy unsalted single-pass SHA-256 KDF, retained solely to decrypt pre-v2 ciphertexts. */
    private static SecretKeySpec deriveLegacyKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to derive credential encryption key", e);
        }
    }

    /** PBKDF2WithHmacSHA256 KDF with a per-ciphertext random salt. */
    private static SecretKeySpec deriveKeyPbkdf2(String secret, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    public boolean isConfigured() {
        return secret != null;
    }

    /** Encrypt plaintext, returning {@code v2:base64(salt || iv || ciphertext)}. */
    public String encrypt(String plaintext) {
        requireKey();
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext is null");
        }
        try {
            byte[] salt = new byte[PBKDF2_SALT_LEN];
            random.nextBytes(salt);
            byte[] iv = new byte[GCM_IV_LEN];
            random.nextBytes(iv);
            SecretKeySpec key = deriveKeyPbkdf2(secret, salt);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[salt.length + iv.length + ct.length];
            System.arraycopy(salt, 0, out, 0, salt.length);
            System.arraycopy(iv, 0, out, salt.length, iv.length);
            System.arraycopy(ct, 0, out, salt.length + iv.length, ct.length);
            return V2_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    /**
     * Decrypt a credential. {@code v2:}-prefixed ciphertexts use PBKDF2 with the embedded salt;
     * unprefixed (legacy) ciphertexts use the retained SHA-256 key.
     */
    public String decrypt(String encoded) {
        requireKey();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("ciphertext is empty");
        }
        try {
            if (encoded.startsWith(V2_PREFIX)) {
                return decryptV2(encoded.substring(V2_PREFIX.length()));
            }
            return decryptLegacy(encoded);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt credential (wrong key or tampered data)", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed credential ciphertext", e);
        }
    }

    private String decryptV2(String base64) throws GeneralSecurityException {
        byte[] in = Base64.getDecoder().decode(base64);
        if (in.length <= PBKDF2_SALT_LEN + GCM_IV_LEN) {
            throw new IllegalArgumentException("ciphertext too short");
        }
        byte[] salt = Arrays.copyOfRange(in, 0, PBKDF2_SALT_LEN);
        byte[] iv = Arrays.copyOfRange(in, PBKDF2_SALT_LEN, PBKDF2_SALT_LEN + GCM_IV_LEN);
        byte[] ct = Arrays.copyOfRange(in, PBKDF2_SALT_LEN + GCM_IV_LEN, in.length);
        SecretKeySpec key = deriveKeyPbkdf2(secret, salt);
        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    private String decryptLegacy(String base64) throws GeneralSecurityException {
        byte[] in = Base64.getDecoder().decode(base64);
        if (in.length <= GCM_IV_LEN) {
            throw new IllegalArgumentException("ciphertext too short");
        }
        byte[] iv = Arrays.copyOfRange(in, 0, GCM_IV_LEN);
        byte[] ct = Arrays.copyOfRange(in, GCM_IV_LEN, in.length);
        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.DECRYPT_MODE, legacyKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    /** Masked display form: keep only the last 4 chars. */
    public static String mask(String plaintext) {
        if (plaintext == null || plaintext.length() <= 4) {
            return "****";
        }
        return "****" + plaintext.substring(plaintext.length() - 4);
    }

    private void requireKey() {
        if (secret == null) {
            throw new IllegalStateException("gateway.admin.security.credential-key is not configured; "
                    + "cannot encrypt/decrypt provider credentials");
        }
    }
}
