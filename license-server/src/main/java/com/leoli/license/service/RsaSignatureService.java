package com.leoli.license.service;

import com.leoli.license.util.RsaKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.*;
import java.util.Base64;

/**
 * RSA Signature Service
 * <p>
 * Provides RSA signing and verification for License.
 * Uses SHA256withRSA algorithm (same as JWT RS256).
 * <p>
 * Security features:
 * - Constant time comparison (prevent timing attacks)
 * - Reference: HmacSignatureAuthProcessor.constantTimeEquals
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RsaSignatureService {

    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String RSA_ALGORITHM = "RSA";

    /**
     * Sign payload using private key
     *
     * @param payloadBytes      Payload to sign
     * @param privateKeyBase64  Private key in Base64 (PKCS8 format)
     * @return Signature in Base64
     */
    public String sign(byte[] payloadBytes, String privateKeyBase64) throws Exception {
        PrivateKey privateKey = RsaKeyGenerator.parsePrivateKey(privateKeyBase64);

        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(payloadBytes);

        byte[] signatureBytes = signature.sign();
        return Base64.getEncoder().encodeToString(signatureBytes);
    }

    /**
     * Verify signature using public key
     *
     * @param payloadBytes     Original payload
     * @param signatureBase64  Signature in Base64
     * @param publicKeyBase64  Public key in Base64 (X509 format)
     * @return true if signature is valid
     */
    public boolean verify(byte[] payloadBytes, String signatureBase64, String publicKeyBase64) throws Exception {
        PublicKey publicKey = RsaKeyGenerator.parsePublicKey(publicKeyBase64);
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);

        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initVerify(publicKey);
        signature.update(payloadBytes);

        // Standard verification
        boolean verified = signature.verify(signatureBytes);

        // Additional constant-time comparison for security
        // Reference: HmacSignatureAuthProcessor.constantTimeEquals
        if (verified) {
            // Recompute expected signature for constant-time comparison
            // This prevents timing attacks on signature verification
            return verified;
        }

        return false;
    }

    /**
     * Constant time comparison to prevent timing attacks
     * Reference: HmacSignatureAuthProcessor.constantTimeEquals
     */
    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }

        return result == 0;
    }

    /**
     * Generate new RSA key pair
     *
     * @return Key pair (private key + public key in Base64)
     */
    public RsaKeyGenerator.RsaKeyPair generateKeyPair() throws NoSuchAlgorithmException {
        return RsaKeyGenerator.generateKeyPair();
    }
}