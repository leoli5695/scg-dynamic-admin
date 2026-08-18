package com.leoli.license.service;

import com.leoli.license.util.RsaKeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RSA Signature Service Unit Tests.
 * Tests key generation, signing, and verification functionality.
 */
class RsaSignatureServiceTest {

    private RsaSignatureService rsaSignatureService;
    private RsaKeyGenerator.RsaKeyPair keyPair;
    private String privateKeyBase64;
    private String publicKeyBase64;

    @BeforeEach
    void setUp() throws Exception {
        rsaSignatureService = new RsaSignatureService();
        keyPair = rsaSignatureService.generateKeyPair();
        privateKeyBase64 = keyPair.getPrivateKey();
        publicKeyBase64 = keyPair.getPublicKey();
    }

    @Test
    @DisplayName("RSA Key Pair Generation - Success")
    void generateKeyPair_success() throws Exception {
        RsaKeyGenerator.RsaKeyPair generatedKeyPair = rsaSignatureService.generateKeyPair();

        assertNotNull(generatedKeyPair);
        assertNotNull(generatedKeyPair.getPrivateKey());
        assertNotNull(generatedKeyPair.getPublicKey());

        // Verify keys can be parsed back to RSA keys
        var privateKey = RsaKeyGenerator.parsePrivateKey(generatedKeyPair.getPrivateKey());
        var publicKey = RsaKeyGenerator.parsePublicKey(generatedKeyPair.getPublicKey());

        assertEquals("RSA", privateKey.getAlgorithm());
        assertEquals("RSA", publicKey.getAlgorithm());

        // Verify private key format is PKCS#8
        assertEquals("PKCS#8", privateKey.getFormat());
    }

    @Test
    @DisplayName("Sign and Verify - Matching Keys")
    void signAndVerify_matchingKeys_success() throws Exception {
        String testData = "Test data for signing";

        // Sign with private key (Base64)
        String signature = rsaSignatureService.sign(testData.getBytes(), privateKeyBase64);

        assertNotNull(signature);
        assertTrue(signature.length() > 0);

        // Verify with public key (Base64)
        boolean verified = rsaSignatureService.verify(testData.getBytes(), signature, publicKeyBase64);

        assertTrue(verified, "Signature should be verified successfully");
    }

    @Test
    @DisplayName("Sign and Verify - Different Data")
    void signAndVerify_differentData_failure() throws Exception {
        String originalData = "Original data";
        String modifiedData = "Modified data";

        // Sign original data
        String signature = rsaSignatureService.sign(originalData.getBytes(), privateKeyBase64);

        // Try to verify with different data
        boolean verified = rsaSignatureService.verify(modifiedData.getBytes(), signature, publicKeyBase64);

        assertFalse(verified, "Signature should not verify for different data");
    }

    @Test
    @DisplayName("Sign and Verify - Different Public Key")
    void signAndVerify_differentPublicKey_failure() throws Exception {
        String testData = "Test data";

        // Sign with original private key
        String signature = rsaSignatureService.sign(testData.getBytes(), privateKeyBase64);

        // Generate a different key pair
        RsaKeyGenerator.RsaKeyPair differentKeyPair = rsaSignatureService.generateKeyPair();
        String differentPublicKey = differentKeyPair.getPublicKey();

        // Try to verify with different public key
        boolean verified = rsaSignatureService.verify(testData.getBytes(), signature, differentPublicKey);

        assertFalse(verified, "Signature should not verify with different public key");
    }

    @Test
    @DisplayName("Format Private Key to Base64")
    void formatPrivateKeyToBase64_success() throws Exception {
        var privateKey = RsaKeyGenerator.parsePrivateKey(privateKeyBase64);
        String encoded = RsaKeyGenerator.formatPrivateKey(privateKey);

        assertNotNull(encoded);
        assertTrue(encoded.length() > 0);
        // Base64 encoded string should not contain invalid characters
        assertTrue(encoded.matches("^[A-Za-z0-9+/=]+$"));
    }

    @Test
    @DisplayName("Format Public Key to Base64")
    void formatPublicKeyToBase64_success() throws Exception {
        var publicKey = RsaKeyGenerator.parsePublicKey(publicKeyBase64);
        String encoded = RsaKeyGenerator.formatPublicKey(publicKey);

        assertNotNull(encoded);
        assertTrue(encoded.length() > 0);
        // Base64 encoded string should not contain invalid characters
        assertTrue(encoded.matches("^[A-Za-z0-9+/=]+$"));
    }

    @Test
    @DisplayName("Parse Private Key from Base64")
    void parsePrivateKeyFromBase64_success() throws Exception {
        var decoded = RsaKeyGenerator.parsePrivateKey(privateKeyBase64);

        assertNotNull(decoded);
        assertEquals("RSA", decoded.getAlgorithm());

        // Verify the decoded key can sign
        String testData = "Test for decoded key";
        String signature = rsaSignatureService.sign(testData.getBytes(), privateKeyBase64);
        boolean verified = rsaSignatureService.verify(testData.getBytes(), signature, publicKeyBase64);
        assertTrue(verified);
    }

    @Test
    @DisplayName("Parse Public Key from Base64")
    void parsePublicKeyFromBase64_success() throws Exception {
        var decoded = RsaKeyGenerator.parsePublicKey(publicKeyBase64);

        assertNotNull(decoded);
        assertEquals("RSA", decoded.getAlgorithm());

        // Verify the decoded key can verify signatures
        String testData = "Test for decoded public key";
        String signature = rsaSignatureService.sign(testData.getBytes(), privateKeyBase64);
        boolean verified = rsaSignatureService.verify(testData.getBytes(), signature, publicKeyBase64);
        assertTrue(verified);
    }

    @Test
    @DisplayName("Parse Invalid Private Key - Exception")
    void parsePrivateKeyFromBase64_invalidInput_throwsException() {
        String invalidBase64 = "InvalidBase64String!!!";

        assertThrows(Exception.class, () -> {
            RsaKeyGenerator.parsePrivateKey(invalidBase64);
        });
    }

    @Test
    @DisplayName("Parse Invalid Public Key - Exception")
    void parsePublicKeyFromBase64_invalidInput_throwsException() {
        String invalidBase64 = "InvalidBase64String!!!";

        assertThrows(Exception.class, () -> {
            RsaKeyGenerator.parsePublicKey(invalidBase64);
        });
    }

    @Test
    @DisplayName("Sign Empty Data")
    void sign_emptyData_success() throws Exception {
        byte[] emptyData = new byte[0];

        String signature = rsaSignatureService.sign(emptyData, privateKeyBase64);

        assertNotNull(signature);
        // Empty data signature should still be verifiable
        boolean verified = rsaSignatureService.verify(emptyData, signature, publicKeyBase64);
        assertTrue(verified);
    }

    @Test
    @DisplayName("Sign Large Data")
    void sign_largeData_success() throws Exception {
        // Create large data (1KB)
        byte[] largeData = new byte[1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        String signature = rsaSignatureService.sign(largeData, privateKeyBase64);

        assertNotNull(signature);
        boolean verified = rsaSignatureService.verify(largeData, signature, publicKeyBase64);
        assertTrue(verified);
    }
}
