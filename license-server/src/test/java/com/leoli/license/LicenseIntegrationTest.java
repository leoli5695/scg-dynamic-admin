package com.leoli.license;

import com.leoli.license.dto.LicenseValidationResult;
import com.leoli.license.model.*;
import com.leoli.license.service.RsaSignatureService;
import com.leoli.license.util.LicenseCodeDecoder;
import com.leoli.license.util.LicenseCodeEncoder;
import com.leoli.license.util.RsaKeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * License Integration Tests.
 * Tests the full encode → decode → validate flow using the new static API.
 */
class LicenseIntegrationTest {

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

    private String encodeAndSign(LicenseDefinition definition) throws Exception {
        byte[] payloadBytes = LicenseCodeEncoder.getPayloadBytes(definition);
        String signature = rsaSignatureService.sign(payloadBytes, privateKeyBase64);
        return LicenseCodeEncoder.encode(definition, signature, publicKeyBase64);
    }

    // ============================================================================
    // Full Flow Tests
    // ============================================================================

    @Test
    @DisplayName("Full Flow: ENTERPRISE Edition - Encode → Decode → Validate")
    void fullFlow_enterpriseEdition_success() throws Exception {
        LicenseDefinition definition = LicenseDefinition.builder()
            .licenseId("INTEGRATION-TEST-001")
            .edition(LicenseEdition.ENTERPRISE)
            .customer(LicenseDefinition.CustomerInfo.builder()
                .name("Integration Test Customer")
                .email("integration@test.com")
                .organization("Integration Test Org")
                .build())
            .binding(LicenseDefinition.BindingInfo.builder()
                .machineId("MAC:AA:BB:CC:DD")
                .instanceId("instance-001")
                .build())
            .issuedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(365))
            .features(List.of(
                LicenseFeature.BASIC_ROUTING,
                LicenseFeature.CIRCUIT_BREAKER,
                LicenseFeature.DISTRIBUTED_RATE_LIMIT,
                LicenseFeature.FULL_CHAIN_TRACE
            ))
            .quotas(LicenseQuota.enterpriseDefaults())
            .metadata(LicenseDefinition.MetadataInfo.builder()
                .version("1.0")
                .notes("Integration test license")
                .build())
            .build();

        String licenseCode = encodeAndSign(definition);
        assertNotNull(licenseCode);

        // Decode
        LicenseDefinition decoded = LicenseCodeDecoder.decode(licenseCode);
        assertNotNull(decoded);
        assertEquals("INTEGRATION-TEST-001", decoded.getLicenseId());
        assertEquals(LicenseEdition.ENTERPRISE, decoded.getEdition());
        assertEquals("Integration Test Customer", decoded.getCustomer().getName());
        assertEquals("integration@test.com", decoded.getCustomer().getEmail());

        // Validate
        LicenseValidationResult result = LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, publicKeyBase64);
        assertTrue(result.isValid());
        assertEquals("ENTERPRISE", result.getEdition());
        assertEquals("Integration Test Customer", result.getCustomerName());
        assertNotNull(result.getFeatures());
        assertTrue(result.getFeatures().contains("BASIC_ROUTING"));
        assertTrue(result.getFeatures().contains("CIRCUIT_BREAKER"));
        assertTrue(result.getFeatures().contains("DISTRIBUTED_RATE_LIMIT"));
        assertTrue(result.getFeatures().contains("FULL_CHAIN_TRACE"));
    }

    @Test
    @DisplayName("Full Flow: BASIC Edition (Permanent)")
    void fullFlow_basicEdition_success() throws Exception {
        LicenseDefinition definition = LicenseDefinition.builder()
            .licenseId("BASIC-TEST-001")
            .edition(LicenseEdition.BASIC)
            .customer(LicenseDefinition.CustomerInfo.builder()
                .name("Basic Customer")
                .email("basic@test.com")
                .build())
            .issuedAt(LocalDateTime.now())
            .expiresAt(null) // BASIC is permanent
            .features(List.of(LicenseFeature.BASIC_ROUTING))
            .quotas(LicenseQuota.basicDefaults())
            .build();

        String licenseCode = encodeAndSign(definition);

        LicenseValidationResult result = LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, publicKeyBase64);
        assertTrue(result.isValid());
        assertEquals("BASIC", result.getEdition());
        assertTrue(result.getPermanent());
    }

    @Test
    @DisplayName("Full Flow: ADVANCED Edition with Time Limit")
    void fullFlow_advancedEdition_success() throws Exception {
        LicenseDefinition definition = LicenseDefinition.builder()
            .licenseId("ADVANCED-TEST-001")
            .edition(LicenseEdition.ADVANCED)
            .customer(LicenseDefinition.CustomerInfo.builder()
                .name("Advanced Customer")
                .email("advanced@test.com")
                .organization("Advanced Corp")
                .build())
            .issuedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(90))
            .features(List.of(
                LicenseFeature.BASIC_ROUTING,
                LicenseFeature.CIRCUIT_BREAKER,
                LicenseFeature.WEIGHTED_ROUTING
            ))
            .quotas(LicenseQuota.advancedDefaults())
            .build();

        String licenseCode = encodeAndSign(definition);

        LicenseValidationResult result = LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, publicKeyBase64);
        assertTrue(result.isValid());
        assertEquals("ADVANCED", result.getEdition());
        assertEquals(3, result.getFeatures().size());
        assertFalse(result.getPermanent());
    }

    // ============================================================================
    // Security Tests
    // ============================================================================

    @Test
    @DisplayName("Security: Tampered Code Fails Validation")
    void security_tamperedCode_failsValidation() throws Exception {
        LicenseDefinition definition = LicenseDefinition.builder()
            .licenseId("SECURITY-TEST-001")
            .edition(LicenseEdition.ENTERPRISE)
            .customer(LicenseDefinition.CustomerInfo.builder()
                .name("Security Test")
                .email("security@test.com")
                .build())
            .issuedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(365))
            .features(List.of(LicenseFeature.BASIC_ROUTING))
            .quotas(LicenseQuota.enterpriseDefaults())
            .build();

        String licenseCode = encodeAndSign(definition);

        // Tamper with the code
        char[] chars = licenseCode.toCharArray();
        int mid = chars.length / 2;
        chars[mid] = chars[mid] == 'A' ? 'B' : 'A';
        String tamperedCode = new String(chars);

        LicenseValidationResult result = LicenseCodeDecoder.validate(tamperedCode, rsaSignatureService, publicKeyBase64);
        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("Security: Forged Signature Fails Validation")
    void security_forgedSignature_failsValidation() throws Exception {
        LicenseDefinition definition = LicenseDefinition.builder()
            .licenseId("SECURITY-TEST-002")
            .edition(LicenseEdition.ENTERPRISE)
            .customer(LicenseDefinition.CustomerInfo.builder()
                .name("Security Test 2")
                .email("security2@test.com")
                .build())
            .issuedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(365))
            .features(List.of(LicenseFeature.BASIC_ROUTING))
            .quotas(LicenseQuota.enterpriseDefaults())
            .build();

        // Sign with a different key pair but embed original public key
        RsaKeyGenerator.RsaKeyPair differentKeyPair = rsaSignatureService.generateKeyPair();
        byte[] payloadBytes = LicenseCodeEncoder.getPayloadBytes(definition);
        String forgedSignature = rsaSignatureService.sign(payloadBytes, differentKeyPair.getPrivateKey());
        String forgedCode = LicenseCodeEncoder.encode(definition, forgedSignature, publicKeyBase64);

        LicenseValidationResult result = LicenseCodeDecoder.validate(forgedCode, rsaSignatureService, publicKeyBase64);
        assertFalse(result.isValid());
    }

    // ============================================================================
    // Feature Availability Tests
    // ============================================================================

    @Test
    @DisplayName("Feature Availability: BASIC Edition Features")
    void featureAvailability_basicEdition() {
        List<LicenseFeature> basicFeatures = Arrays.stream(LicenseFeature.values())
            .filter(f -> f.isAvailableIn("BASIC"))
            .collect(Collectors.toList());

        // BASIC should have limited features
        assertTrue(basicFeatures.size() < LicenseFeature.values().length);
        assertTrue(basicFeatures.contains(LicenseFeature.BASIC_ROUTING));
        assertTrue(basicFeatures.contains(LicenseFeature.LOCAL_RATE_LIMIT));
    }

    @Test
    @DisplayName("Feature Availability: ENTERPRISE Has All Features")
    void featureAvailability_enterpriseHasAll() {
        for (LicenseFeature feature : LicenseFeature.values()) {
            assertTrue(feature.isAvailableIn("ENTERPRISE"),
                "ENTERPRISE should have feature: " + feature.name());
        }
    }

    @Test
    @DisplayName("Feature Availability: ADVANCED Subset")
    void featureAvailability_advancedSubset() {
        long basicCount = Arrays.stream(LicenseFeature.values())
            .filter(f -> f.isAvailableIn("BASIC")).count();
        long advancedCount = Arrays.stream(LicenseFeature.values())
            .filter(f -> f.isAvailableIn("ADVANCED")).count();

        // ADVANCED should have more than BASIC but less than total
        assertTrue(advancedCount > basicCount);
        assertTrue(advancedCount < LicenseFeature.values().length);
    }

    // ============================================================================
    // Quota Tests
    // ============================================================================

    @Test
    @DisplayName("Quota Defaults: BASIC")
    void quotaDefaults_basic() {
        LicenseQuota quotas = LicenseQuota.basicDefaults();
        assertEquals(1, quotas.getMaxInstances());
        assertEquals(10, quotas.getMaxRoutes());
        assertEquals(5, quotas.getMaxServices());
        assertEquals("COMMUNITY", quotas.getSupportLevel());
    }

    @Test
    @DisplayName("Quota Defaults: ADVANCED")
    void quotaDefaults_advanced() {
        LicenseQuota quotas = LicenseQuota.advancedDefaults();
        assertEquals(3, quotas.getMaxInstances());
        assertEquals(100, quotas.getMaxRoutes());
        assertEquals(50, quotas.getMaxServices());
        assertEquals("PROFESSIONAL", quotas.getSupportLevel());
    }

    @Test
    @DisplayName("Quota Defaults: ENTERPRISE (Unlimited)")
    void quotaDefaults_enterprise() {
        LicenseQuota quotas = LicenseQuota.enterpriseDefaults();
        assertEquals(-1, quotas.getMaxInstances());
        assertEquals(-1, quotas.getMaxRoutes());
        assertEquals(-1, quotas.getMaxServices());
        assertEquals("ENTERPRISE", quotas.getSupportLevel());
        assertTrue(quotas.isUnlimited(quotas.getMaxInstances()));
    }

    @Test
    @DisplayName("Quota: defaultsForEdition")
    void quotaDefaultsForEdition() {
        assertEquals(1, LicenseQuota.defaultsForEdition(LicenseEdition.BASIC).getMaxInstances());
        assertEquals(3, LicenseQuota.defaultsForEdition(LicenseEdition.ADVANCED).getMaxInstances());
        assertEquals(-1, LicenseQuota.defaultsForEdition(LicenseEdition.ENTERPRISE).getMaxInstances());
    }

    @Test
    @DisplayName("Quota: exceedsLimit")
    void quotaExceedsLimit() {
        LicenseQuota quotas = LicenseQuota.basicDefaults();
        assertTrue(quotas.exceedsLimit(quotas.getMaxInstances(), 1));
        assertFalse(quotas.exceedsLimit(quotas.getMaxInstances(), 0));
        assertFalse(quotas.exceedsLimit(-1, 1000)); // unlimited
        assertFalse(quotas.exceedsLimit(null, 1000)); // null = no limit
    }

    // ============================================================================
    // Edition Tests
    // ============================================================================

    @Test
    @DisplayName("Edition: fromCode")
    void editionFromCode() {
        assertEquals(LicenseEdition.BASIC, LicenseEdition.fromCode("BASIC"));
        assertEquals(LicenseEdition.ADVANCED, LicenseEdition.fromCode("ADVANCED"));
        assertEquals(LicenseEdition.ENTERPRISE, LicenseEdition.fromCode("ENTERPRISE"));
    }

    @Test
    @DisplayName("Edition: isPermanent")
    void editionIsPermanent() {
        assertTrue(LicenseEdition.BASIC.isPermanent());
        assertTrue(LicenseEdition.ENTERPRISE.isPermanent());
        assertFalse(LicenseEdition.ADVANCED.isPermanent());
    }

    // ============================================================================
    // Edge Cases
    // ============================================================================

    @Test
    @DisplayName("Edge Case: License with No Features")
    void edgeCase_noFeatures() throws Exception {
        LicenseDefinition definition = LicenseDefinition.builder()
            .licenseId("NO-FEATURES-001")
            .edition(LicenseEdition.BASIC)
            .customer(LicenseDefinition.CustomerInfo.builder()
                .name("No Features Customer")
                .email("nofeatures@test.com")
                .build())
            .issuedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(30))
            .features(List.of())
            .quotas(LicenseQuota.basicDefaults())
            .build();

        String licenseCode = encodeAndSign(definition);

        LicenseValidationResult result = LicenseCodeDecoder.validate(licenseCode, rsaSignatureService, publicKeyBase64);
        assertTrue(result.isValid());
        assertNotNull(result.getFeatures());
        assertTrue(result.getFeatures().isEmpty());
    }

    @Test
    @DisplayName("Edge Case: License with No Metadata")
    void edgeCase_noMetadata() throws Exception {
        LicenseDefinition definition = LicenseDefinition.builder()
            .licenseId("NO-METADATA-001")
            .edition(LicenseEdition.ADVANCED)
            .customer(LicenseDefinition.CustomerInfo.builder()
                .name("No Metadata Customer")
                .email("nometa@test.com")
                .build())
            .issuedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(60))
            .features(List.of(LicenseFeature.BASIC_ROUTING))
            .quotas(LicenseQuota.advancedDefaults())
            .metadata(null)
            .build();

        String licenseCode = encodeAndSign(definition);

        LicenseDefinition decoded = LicenseCodeDecoder.decode(licenseCode);
        assertNotNull(decoded);
        assertEquals("NO-METADATA-001", decoded.getLicenseId());
    }
}
