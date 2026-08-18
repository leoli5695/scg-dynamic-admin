package com.leoli.license.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * RSA Key Generator Utility
 * <p>
 * Generates RSA-2048 key pairs for License signing.
 * Follows the same pattern as JwtAuthProcessor for key handling.
 * <p>
 * Key format:
 * - Private Key: PKCS8EncodedKeySpec (Base64)
 * - Public Key: X509EncodedKeySpec (Base64)
 */
@Slf4j
public class RsaKeyGenerator {

    private static final int DEFAULT_KEY_SIZE = 2048;
    private static final String RSA_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /**
     * Generate a new RSA key pair
     */
    public static RsaKeyPair generateKeyPair() throws NoSuchAlgorithmException {
        return generateKeyPair(DEFAULT_KEY_SIZE);
    }

    /**
     * Generate a new RSA key pair with specified size
     */
    public static RsaKeyPair generateKeyPair(int keySize) throws NoSuchAlgorithmException {
        log.info("Generating RSA key pair with size: {} bits", keySize);

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        keyGen.initialize(keySize);
        KeyPair keyPair = keyGen.generateKeyPair();

        // Format keys to Base64
        String privateKey = formatPrivateKey(keyPair.getPrivate());
        String publicKey = formatPublicKey(keyPair.getPublic());

        log.info("RSA key pair generated successfully");
        return new RsaKeyPair(privateKey, publicKey);
    }

    /**
     * Format private key to Base64 (PKCS8 format)
     */
    public static String formatPrivateKey(PrivateKey privateKey) {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        return Base64.getEncoder().encodeToString(spec.getEncoded());
    }

    /**
     * Format public key to Base64 (X509 format)
     */
    public static String formatPublicKey(PublicKey publicKey) {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKey.getEncoded());
        return Base64.getEncoder().encodeToString(spec.getEncoded());
    }

    /**
     * Parse private key from Base64 (PKCS8 format)
     * Reference: DynamicSslContextManager private key parsing pattern
     */
    public static PrivateKey parsePrivateKey(String privateKeyBase64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
        return keyFactory.generatePrivate(spec);
    }

    /**
     * Parse public key from Base64 (X509 format)
     * Reference: JwtAuthProcessor.getPublicKey pattern
     */
    public static PublicKey parsePublicKey(String publicKeyBase64) throws Exception {
        // Handle PEM format if present
        String publicKeyContent = publicKeyBase64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(publicKeyContent);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
        return keyFactory.generatePublic(spec);
    }

    /**
     * RSA Key Pair container
     */
    @Data
    public static class RsaKeyPair {
        private final String privateKey;
        private final String publicKey;

        /**
         * Get PEM formatted private key
         */
        public String getPrivateKeyPem() {
            return "-----BEGIN PRIVATE KEY-----\n" + privateKey + "\n-----END PRIVATE KEY-----";
        }

        /**
         * Get PEM formatted public key
         */
        public String getPublicKeyPem() {
            return "-----BEGIN PUBLIC KEY-----\n" + publicKey + "\n-----END PUBLIC KEY-----";
        }
    }
}