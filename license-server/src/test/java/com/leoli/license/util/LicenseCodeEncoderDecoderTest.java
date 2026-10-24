package com.leoli.license.util;

import com.leoli.license.dto.LicenseValidationResult;
import com.leoli.license.model.*;
import com.leoli.license.service.RsaSignatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * License Code Encoder/Decoder Unit Tests.
 * Tests encoding and decoding of license codes using the new static API.
 */
class LicenseCodeEncoderDecoderTest {

    private RsaSignatureService rsaSignatureService;
    private RsaKeyGenerator.RsaKeyPair keyPair;
    private String privateKeyBase64;
    private String publicKeyBase64;

    private LicenseDefinition testDefinition;

    @BeforeEach
    void setUp() throws Exception {
        rsaSignatureService = new RsaSignatureService();
        keyPair = rsaSignatureService.generateKeyPair();
        privateKeyBase64 = keyPair.getPrivateKey();
        publicKeyBase64 = keyPair.getPublicKey();

        testDefinition = buildTestDefinition();
    }

    private LicenseDefinition buildTestDefinition() {
        return LicenseDefinition.builder()
            .licenseId("TEST-LICENSE-001")
            .edition(LicenseEdition.ENTERPRISE)
            .customer(LicenseDefinition.CustomerInfo.builder()
                .name("Test Customer")
                .email("test@example.com")
                .organization("Test Org")
                .build())
            .binding(LicenseDefinition.BindingInfo.builder()
                .machineId("machine-123")
                .instanceId("instance-456")
                .build())
            .issuedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(365))
            .features(List.of(LicenseFeature.BASIC_ROUTING, LicenseFeature.CIRCUIT_BREAKER))
            .quotas(LicenseQuota.enterpriseDefaults())
            .metadata(LicenseDefinition.MetadataInfo.builder()
                .version("1.0")
                .notes("Test license")
                .build())
            .build();
    }

    @Test
    @DisplayName("Encode License Code - Success")
    void encodeLicenseCode_success() throws Exception {
        String signature = rsaSignatureService.sign(
            LicenseCodeEncoder.getPayloadBytes(testDefinition), privateKeyBase64);

        String licenseCode = LicenseCodeEncoder.encode(testDefinition, signature, publicKeyBase64);

        assertNotNull(licenseCode);
        assertTrue(licenseCode.length() > 0);
        // License code should be Base64 encoded
        assertTrue(licenseCode.matches("^[A-Za-z0-9+/=]+$"));
    }

    @Test
    @DisplayName("Decode License Code - Success")
    void decodeLicenseCode_success() throws Exception {
        String signature = rsaSignatureService.sign(
            LicenseCodeEncoder.getPayloadBytes(testDefinition), privateKeyBase64);

        String licenseCode = LicenseCodeEncoder.encode(testDefinition, signature, publicKeyBase64);

        LicenseDefinition decoded = LicenseCodeDecoder.decode(licenseCode);

        assertNotNull(decoded);
        assertEquals("TEST-LICENSE-001", decoded.getLicenseId());
        assertEquals(LicenseEdition.ENTERPRISE, decoded.getEdition());
        assertEquals("Test Customer", decoded.getCustomer().getName());
        assertEquals("test@example.com", decoded.getCustomer().getEmail());
        assertEquals("Test Org", decoded.getCustomer().getOrganization());
        assertEquals("machine-123", decoded.getBinding().getMachineId());
        assertEquals("instance-456", decoded.getBinding().getInstanceId());
        assertNotNull(decoded.getFeatures());
        assertTrue(decoded.getFeatures().contains(LicenseFeature.BASIC_ROUTING));
        assertTrue(decoded.getFeatures().contains(LicenseFeature.CIRCUIT_BREAKER));
    }

    @Test
    @DisplayName("Validate License Code - Valid Signature")
    void validateLicenseCode_validSignature() throws Exception {
        String signature = rsaSignatureService.sign(
            LicenseCodeEncoder.getPayloadBytes(testDefinition), privateKeyBase64);

        String licenseCode = LicenseCodeEncoder.encode(testDefinition, signature, publicKeyBase64);

        LicenseValidationResult result = LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, publicKeyBase64);

        assertTrue(result.isValid());
        assertEquals("ENTERPRISE", result.getEdition());
        assertEquals("Test Customer", result.getCustomerName());
    }

    @Test
    @DisplayName("Validate License Code - Invalid Signature")
    void validateLicenseCode_invalidSignature() throws Exception {
        // Sign with a different key
        RsaKeyGenerator.RsaKeyPair differentKeyPair = rsaSignatureService.generateKeyPair();
        String signature = rsaSignatureService.sign(
            LicenseCodeEncoder.getPayloadBytes(testDefinition), differentKeyPair.getPrivateKey());

        // Encode with original public key (mismatch)
        String licenseCode = LicenseCodeEncoder.encode(testDefinition, signature, publicKeyBase64);

        LicenseValidationResult result = LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, publicKeyBase64);

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("Validate License Code - Tampered Code")
    void validateLicenseCode_tamperedCode() throws Exception {
        String signature = rsaSignatureService.sign(
            LicenseCodeEncoder.getPayloadBytes(testDefinition), privateKeyBase64);

        String licenseCode = LicenseCodeEncoder.encode(testDefinition, signature, publicKeyBase64);

        // Tamper with the code
        char[] chars = licenseCode.toCharArray();
        int mid = chars.length / 2;
        chars[mid] = chars[mid] == 'A' ? 'B' : 'A';
        String tamperedCode = new String(chars);

        LicenseValidationResult result = LicenseCodeDecoder.validate(tamperedCode, rsaSignatureService, publicKeyBase64);

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("Validate License Code - Malformed Input")
    void validateLicenseCode_malformedInput() {
        LicenseValidationResult result = LicenseCodeDecoder.validate("not-a-valid-base64!!!", rsaSignatureService, publicKeyBase64);
        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("Encode/Decode - BASIC Edition with null expiresAt")
    void encodeDecodeBasicEdition_permanent() throws Exception {
        LicenseDefinition basicDef = LicenseDefinition.builder()
            .licenseId("BASIC-001")
            .edition(LicenseEdition.BASIC)
            .customer(LicenseDefinition.CustomerInfo.builder()
                .name("Basic User")
                .email("basic@test.com")
                .build())
            .issuedAt(LocalDateTime.now())
            .expiresAt(null)
            .features(List.of(LicenseFeature.BASIC_ROUTING))
            .quotas(LicenseQuota.basicDefaults())
            .build();

        String signature = rsaSignatureService.sign(
            LicenseCodeEncoder.getPayloadBytes(basicDef), privateKeyBase64);
        String licenseCode = LicenseCodeEncoder.encode(basicDef, signature, publicKeyBase64);

        LicenseValidationResult result = LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, publicKeyBase64);
        assertTrue(result.isValid());
        assertEquals("BASIC", result.getEdition());
        assertTrue(result.getPermanent());
    }

    @Test
    @DisplayName("GetPayloadBytes - Returns Valid JSON Bytes")
    void getPayloadBytes_returnsValidJson() {
        byte[] payloadBytes = LicenseCodeEncoder.getPayloadBytes(testDefinition);

        assertNotNull(payloadBytes);
        assertTrue(payloadBytes.length > 0);
        String json = new String(payloadBytes);
        assertTrue(json.contains("TEST-LICENSE-001"));
        assertTrue(json.contains("ENTERPRISE"));
    }

    /**
     * Regression for the self-signed validation oracle. {@code validate} used to verify the
     * signature against the publicKey carried INSIDE the submitted license, so the check proved
     * only that the caller held the matching private key. Generating a keypair, signing an
     * ENTERPRISE payload with it and submitting both was enough to get {@code valid:true} out of
     * {@code POST /api/licenses/validate} - from the supposed authority on licensing.
     */
    @Test
    @DisplayName("Trust anchor: a license signed by an attacker-generated keypair is rejected")
    void selfSignedLicenseIsRejected() throws Exception {
        RsaKeyGenerator.RsaKeyPair attackerKeyPair = rsaSignatureService.generateKeyPair();
        String attackerSignature = rsaSignatureService.sign(
            LicenseCodeEncoder.getPayloadBytes(testDefinition), attackerKeyPair.getPrivateKey());
        String selfSignedCode = LicenseCodeEncoder.encode(
            testDefinition, attackerSignature, attackerKeyPair.getPublicKey());

        LicenseValidationResult result =
            LicenseCodeDecoder.validate(selfSignedCode, rsaSignatureService, publicKeyBase64);

        assertFalse(result.isValid(),
            "a self-signed license must not validate against the server's own trust anchor");
    }

    @Test
    @DisplayName("Trust anchor: validation fails closed when the server has no trusted key configured")
    void missingTrustAnchorFailsClosed() throws Exception {
        String signature = rsaSignatureService.sign(
            LicenseCodeEncoder.getPayloadBytes(testDefinition), privateKeyBase64);
        String licenseCode = LicenseCodeEncoder.encode(testDefinition, signature, publicKeyBase64);

        assertFalse(LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, null).isValid(),
            "no configured trust anchor must not mean 'trust anything'");
        assertFalse(LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, "").isValid());
        assertFalse(LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, "   ").isValid());
        assertFalse(LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, "not-base64!").isValid());
    }

    @Test
    @DisplayName("Trust anchor: PEM armour and whitespace differences do not change the verdict")
    void trustAnchorComparisonIsNormalised() throws Exception {
        String signature = rsaSignatureService.sign(
            LicenseCodeEncoder.getPayloadBytes(testDefinition), privateKeyBase64);
        String licenseCode = LicenseCodeEncoder.encode(testDefinition, signature, publicKeyBase64);

        String pemWrapped = "-----BEGIN PUBLIC KEY-----\n" + publicKeyBase64 + "\n-----END PUBLIC KEY-----";
        assertTrue(LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, pemWrapped).isValid(),
            "the same key wrapped in PEM armour must still be recognised as trusted");
    }
}
