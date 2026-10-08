package com.leoli.license.client.validator;

import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.exception.LicenseExpiredException;
import com.leoli.license.client.exception.LicenseInvalidException;
import com.leoli.license.client.model.LicenseInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * License Validator Unit Tests.
 * Covers offline RSA signature verification, expiry, machine binding and fallback licenses.
 */
class LicenseValidatorTest {

    private LicenseValidator validator;
    private LicenseProperties properties;

    @BeforeEach
    void setUp() {
        validator = new LicenseValidator();
        properties = new LicenseProperties();
    }

    // ==================== Signature verification ====================

    @Test
    @DisplayName("Validate signed license - returns parsed LicenseInfo")
    void validate_signedLicense_success() {
        LicenseInfo info = validator.validate(LicenseFixtures.advancedCode(), properties);

        assertEquals("LIC-ADVANCED", info.getLicenseId());
        assertEquals("ADVANCED", info.getEdition());
        assertEquals(LicenseInfo.Edition.ADVANCED, info.getEditionEnum());
        assertEquals("Test Customer", info.getCustomerName());
        assertEquals("test@example.com", info.getCustomerEmail());
        assertEquals("Test Org", info.getOrganization());
        assertNotNull(info.getIssuedAt());
        assertNotNull(info.getExpiresAt());
        assertNotNull(info.getLoadedAt());
        assertFalse(info.isExpired());
    }

    @Test
    @DisplayName("Validate signed license - features and quotas are mapped")
    void validate_signedLicense_mapsFeaturesAndQuotas() {
        LicenseInfo info = validator.validate(LicenseFixtures.advancedCode(), properties);

        assertTrue(info.hasFeature("WEIGHTED_ROUTING"));
        assertTrue(info.hasFeature(LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT));
        assertFalse(info.hasFeature("AI_COPilot"));

        LicenseInfo.QuotaInfo quotas = info.getQuotas();
        assertNotNull(quotas);
        assertEquals(5, quotas.getMaxInstances());
        assertEquals(500, quotas.getMaxRoutes());
        assertEquals(100, quotas.getMaxServices());
        assertEquals(50, quotas.getMaxStrategies());
        assertEquals(1000, quotas.getMaxRateLimitQPS());
        assertEquals("STANDARD", quotas.getSupportLevel());
    }

    @Test
    @DisplayName("Validate signed license - retains the raw license code")
    void validate_signedLicense_retainsRawCode() {
        String code = LicenseFixtures.enterpriseCode();

        assertEquals(code, validator.validate(code, properties).getLicenseCode());
    }

    @Test
    @DisplayName("Validate tampered payload - signature verification fails")
    void validate_tamperedPayload_throwsInvalid() {
        String code = LicenseFixtures.advancedCode();
        String json = new String(Base64.getDecoder().decode(code), StandardCharsets.UTF_8);
        String tampered = Base64.getEncoder()
                .encodeToString(json.replace("\"ADVANCED\"", "\"ENTERPRISE\"").getBytes(StandardCharsets.UTF_8));

        LicenseInvalidException e = assertThrows(LicenseInvalidException.class,
                () -> validator.validate(tampered, properties));
        assertTrue(e.getReason().contains("Signature verification failed"));
    }

    @Test
    @DisplayName("Validate license missing publicKey - throws invalid")
    void validate_missingPublicKey_throwsInvalid() {
        Map<String, Object> license = new LinkedHashMap<>();
        license.put("payload", LicenseFixtures.payload("LIC-NOKEY", "ADVANCED", null));
        license.put("signature", Base64.getEncoder().encodeToString(new byte[256]));

        LicenseInvalidException e = assertThrows(LicenseInvalidException.class,
                () -> validator.validate(LicenseFixtures.encodeUnsigned(license), properties));
        assertTrue(e.getReason().contains("missing components"));
    }

    @Test
    @DisplayName("Validate non-Base64 garbage - wrapped as invalid")
    void validate_garbage_throwsInvalid() {
        assertThrows(LicenseInvalidException.class,
                () -> validator.validate("not-a-license-code!!!", properties));
    }

    @Test
    @DisplayName("Validate Base64 that is not a license document - wrapped as invalid")
    void validate_base64OfPlainJson_throwsInvalid() {
        String code = Base64.getEncoder()
                .encodeToString("{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));

        assertThrows(LicenseInvalidException.class, () -> validator.validate(code, properties));
    }

    // ==================== Expiry ====================

    @Test
    @DisplayName("Validate expired license - throws expired with license id")
    void validate_expiredLicense_throwsExpired() {
        LicenseExpiredException e = assertThrows(LicenseExpiredException.class,
                () -> validator.validate(LicenseFixtures.expiredCode(), properties));

        assertEquals("LIC-EXPIRED", e.getLicenseId());
        assertNotNull(e.getExpiresAt());
    }

    @Test
    @DisplayName("Validate license without expiresAt - treated as permanent")
    void validate_permanentLicense_notExpired() {
        LicenseInfo info = validator.validate(LicenseFixtures.permanentCode(), properties);

        assertTrue(info.isPermanent());
        assertNull(info.getExpiresAt());
        assertFalse(info.isExpired());
        assertNull(info.getDaysRemaining());
    }

    @Test
    @DisplayName("Days remaining is reported for a time-limited license")
    void validate_timeLimitedLicense_reportsDaysRemaining() {
        LicenseInfo info = validator.validate(LicenseFixtures.advancedCode(), properties);

        assertNotNull(info.getDaysRemaining());
        assertTrue(info.getDaysRemaining() >= 363 && info.getDaysRemaining() <= 365,
                "expected ~365 days remaining but was " + info.getDaysRemaining());
    }

    // ==================== Machine binding ====================

    @Test
    @DisplayName("Machine binding match - accepted")
    void validate_machineIdMatches_success() {
        properties.setMachineId("machine-A");

        LicenseInfo info = validator.validate(LicenseFixtures.codeBoundTo("machine-A"), properties);

        assertEquals("machine-A", info.getMachineId());
    }

    @Test
    @DisplayName("Machine binding mismatch - rejected")
    void validate_machineIdMismatch_throwsInvalid() {
        properties.setMachineId("machine-B");
        String code = LicenseFixtures.codeBoundTo("machine-A");

        LicenseInvalidException e = assertThrows(LicenseInvalidException.class,
                () -> validator.validate(code, properties));
        assertTrue(e.getReason().contains("Machine binding mismatch"));
    }

    @Test
    @DisplayName("No machine id configured - binding check is skipped")
    void validate_noMachineIdConfigured_skipsBindingCheck() {
        properties.setMachineId(null);

        assertDoesNotThrow(() -> validator.validate(LicenseFixtures.codeBoundTo("machine-A"), properties));
    }

    // ==================== Loading from file ====================

    @Test
    @DisplayName("Load license from file - content is trimmed")
    void loadLicenseFromFile_trimsContent(@TempDir Path dir) throws Exception {
        String code = LicenseFixtures.basicCode();
        Path file = dir.resolve("license.lic");
        Files.writeString(file, "  " + code + "\n");

        String loaded = validator.loadLicenseFromFile(file.toString());

        assertEquals(code, loaded);
        assertEquals("LIC-BASIC", validator.validate(loaded, properties).getLicenseId());
    }

    @Test
    @DisplayName("Load license from missing file - throws invalid")
    void loadLicenseFromFile_missingFile_throwsInvalid(@TempDir Path dir) {
        Path missing = dir.resolve("absent.lic");

        LicenseInvalidException e = assertThrows(LicenseInvalidException.class,
                () -> validator.loadLicenseFromFile(missing.toString()));
        assertTrue(e.getReason().contains("not found"));
    }

    // ==================== Fallback licenses ====================

    @Test
    @DisplayName("Fallback defaults to BASIC with community quotas")
    void buildFallbackLicense_default_basic() {
        LicenseInfo fallback = validator.buildFallbackLicense(properties);

        assertEquals("FALLBACK-BASIC", fallback.getLicenseId());
        assertEquals("BASIC", fallback.getEdition());
        assertEquals("Fallback", fallback.getCustomerName());
        assertEquals(1, fallback.getQuotas().getMaxInstances());
        assertEquals(10, fallback.getQuotas().getMaxRoutes());
        assertEquals("COMMUNITY", fallback.getQuotas().getSupportLevel());
        assertFalse(fallback.isExpired());
    }

    @Test
    @DisplayName("Fallback grants exactly the features of its edition and below")
    void buildFallbackLicense_grantsFeaturesUpToEdition() {
        LicenseInfo basic = fallbackFor("BASIC");
        LicenseInfo advanced = fallbackFor("ADVANCED");
        LicenseInfo enterprise = fallbackFor("ENTERPRISE");

        assertTrue(basic.hasFeature("BASIC_ROUTING"));
        assertFalse(basic.hasFeature("WEIGHTED_ROUTING"), "BASIC fallback must not grant ADVANCED features");

        assertTrue(advanced.hasFeature("BASIC_ROUTING"));
        assertTrue(advanced.hasFeature("WEIGHTED_ROUTING"));
        assertFalse(advanced.hasFeature("AI_COPilot"), "ADVANCED fallback must not grant ENTERPRISE features");

        assertEquals(LicenseInfo.Feature.values().length, enterprise.getFeatures().size());
    }

    @Test
    @DisplayName("Fallback honours fallback-edition instead of hardcoding BASIC")
    void buildFallbackLicense_honoursConfiguredEdition() {
        LicenseInfo advanced = fallbackFor("ADVANCED");
        assertEquals("FALLBACK-ADVANCED", advanced.getLicenseId());
        assertEquals(3, advanced.getQuotas().getMaxInstances());
        assertEquals("STANDARD", advanced.getQuotas().getSupportLevel());

        LicenseInfo enterprise = fallbackFor("ENTERPRISE");
        assertEquals("FALLBACK-ENTERPRISE", enterprise.getLicenseId());
        assertEquals(-1, enterprise.getQuotas().getMaxInstances());
        assertTrue(enterprise.getQuotas().isUnlimited(enterprise.getQuotas().getMaxInstances()));
        assertEquals("PRIORITY", enterprise.getQuotas().getSupportLevel());
    }

    @Test
    @DisplayName("Fallback degrades to BASIC for unknown or blank edition")
    void buildFallbackLicense_unknownEdition_degradesToBasic() {
        assertEquals("FALLBACK-BASIC", fallbackFor("PLATINUM").getLicenseId());
        assertEquals("FALLBACK-BASIC", fallbackFor("").getLicenseId());
        assertEquals("FALLBACK-BASIC", fallbackFor(null).getLicenseId());
    }

    @Test
    @DisplayName("Fallback edition matching is case-insensitive and trimmed")
    void buildFallbackLicense_normalizesEditionInput() {
        assertEquals("FALLBACK-ADVANCED", fallbackFor("  advanced ").getLicenseId());
    }

    @Test
    @DisplayName("Fallback licenses never expire")
    void buildFallbackLicense_isPermanent() {
        LicenseInfo fallback = validator.buildFallbackLicense(properties);

        assertNotNull(fallback.getExpiresAt());
        assertFalse(fallback.isExpired());
        assertFalse(fallback.isPermanent(), "fallback carries an explicit far-future expiry, not a null one");
        assertEquals(2099, fallback.getExpiresAt().getYear());
    }

    private LicenseInfo fallbackFor(String edition) {
        LicenseProperties props = new LicenseProperties();
        props.setFallbackEdition(edition);
        return validator.buildFallbackLicense(props);
    }
}
