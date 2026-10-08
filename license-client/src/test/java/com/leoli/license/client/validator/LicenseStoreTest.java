package com.leoli.license.client.validator;

import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.exception.LicenseInvalidException;
import com.leoli.license.client.model.LicenseInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * License Store Unit Tests.
 * Covers startup loading, fallback degradation, renewal and periodic-validation bookkeeping.
 */
class LicenseStoreTest {

    private LicenseValidator validator;
    private LicenseProperties properties;
    private LicenseStore store;

    @BeforeEach
    void setUp() {
        validator = new LicenseValidator();
        properties = new LicenseProperties();
        store = new LicenseStore(validator, properties);
    }

    // ==================== Startup loading ====================

    @Test
    @DisplayName("Load valid license code from configuration")
    void initialize_withValidLicenseCode_loadsLicense() {
        properties.setLicenseCode(LicenseFixtures.advancedCode());

        store.initialize();

        LicenseInfo license = store.getLicense();
        assertNotNull(license);
        assertEquals("LIC-ADVANCED", license.getLicenseId());
        assertEquals("ADVANCED", license.getEdition());
        assertNotNull(store.getLastValidation());
    }

    @Test
    @DisplayName("Load license code from file when no inline code is set")
    void initialize_withLicenseFile_loadsLicense(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("license.lic");
        Files.writeString(file, LicenseFixtures.enterpriseCode());
        properties.setLicenseFile(file.toString());

        store.initialize();

        assertEquals("LIC-ENTERPRISE", store.getLicense().getLicenseId());
    }

    @Test
    @DisplayName("Inline license code wins over license file")
    void initialize_prefersLicenseCodeOverFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("license.lic");
        Files.writeString(file, LicenseFixtures.enterpriseCode());
        properties.setLicenseFile(file.toString());
        properties.setLicenseCode(LicenseFixtures.basicCode());

        store.initialize();

        assertEquals("LIC-BASIC", store.getLicense().getLicenseId());
    }

    @Test
    @DisplayName("No license configured - falls back")
    void initialize_withoutAnyLicense_loadsFallback() {
        store.initialize();

        LicenseInfo license = store.getLicense();
        assertNotNull(license);
        assertTrue(license.getLicenseId().startsWith("FALLBACK-"));
        assertNull(store.getLastValidation(), "fallback loading is not a successful validation");
    }

    @Test
    @DisplayName("Validation disabled - falls back without reading any license")
    void initialize_disabled_loadsFallback() {
        properties.setEnabled(false);
        properties.setLicenseCode(LicenseFixtures.advancedCode());

        store.initialize();

        assertTrue(store.getLicense().getLicenseId().startsWith("FALLBACK-"));
    }

    // ==================== Fallback degradation ====================

    @Test
    @DisplayName("Invalid license with allowBasicFallback - degrades to fallback")
    void initialize_invalidLicense_fallsBackWhenAllowed() {
        properties.setLicenseCode("corrupted-license-code");
        properties.setAllowBasicFallback(true);

        store.initialize();

        assertTrue(store.getLicense().getLicenseId().startsWith("FALLBACK-"));
    }

    @Test
    @DisplayName("Invalid license without allowBasicFallback - no license is loaded")
    void initialize_invalidLicense_noLicenseWhenFallbackDisabled() {
        properties.setLicenseCode("corrupted-license-code");
        properties.setAllowBasicFallback(false);

        store.initialize();

        assertNull(store.getLicense());
    }

    @Test
    @DisplayName("Expired license with allowBasicFallback - degrades to fallback")
    void initialize_expiredLicense_fallsBackWhenAllowed() {
        properties.setLicenseCode(LicenseFixtures.expiredCode());
        properties.setAllowBasicFallback(true);

        store.initialize();

        assertTrue(store.getLicense().getLicenseId().startsWith("FALLBACK-"));
    }

    @Test
    @DisplayName("Expired license without allowBasicFallback - no license is loaded")
    void initialize_expiredLicense_noLicenseWhenFallbackDisabled() {
        properties.setLicenseCode(LicenseFixtures.expiredCode());
        properties.setAllowBasicFallback(false);

        store.initialize();

        assertNull(store.getLicense());
    }

    @Test
    @DisplayName("Configured fallback-edition is honoured when degrading")
    void initialize_invalidLicense_fallbackUsesConfiguredEdition() {
        properties.setLicenseCode("corrupted-license-code");
        properties.setFallbackEdition("ADVANCED");

        store.initialize();

        assertEquals("FALLBACK-ADVANCED", store.getLicense().getLicenseId());
    }

    // ==================== Expiry detected on access ====================

    @Test
    @DisplayName("License that expires after load is swapped for a fallback on access")
    void getLicense_licenseExpiresAfterLoad_switchesToFallback() throws Exception {
        properties.setLicenseCode(LicenseFixtures.codeExpiringIn(Duration.ofSeconds(1)));
        store.initialize();
        assertEquals("LIC-SHORT-LIVED", store.getLicense().getLicenseId());

        Thread.sleep(1_600);

        LicenseInfo after = store.getLicense();
        assertNotNull(after);
        assertTrue(after.getLicenseId().startsWith("FALLBACK-"),
                "expired license should be replaced by a fallback but was " + after.getLicenseId());
    }

    // ==================== Renewal and refresh ====================

    @Test
    @DisplayName("Update license replaces the current one")
    void updateLicense_validCode_replacesLicense() {
        properties.setLicenseCode(LicenseFixtures.basicCode());
        store.initialize();
        assertEquals("LIC-BASIC", store.getLicense().getLicenseId());

        store.updateLicense(LicenseFixtures.enterpriseCode());

        assertEquals("LIC-ENTERPRISE", store.getLicense().getLicenseId());
    }

    @Test
    @DisplayName("Update license with an invalid code throws and keeps the previous license")
    void updateLicense_invalidCode_throwsAndKeepsPrevious() {
        properties.setLicenseCode(LicenseFixtures.advancedCode());
        store.initialize();

        assertThrows(LicenseInvalidException.class, () -> store.updateLicense("corrupted"));

        assertEquals("LIC-ADVANCED", store.getLicense().getLicenseId());
    }

    @Test
    @DisplayName("Refresh re-reads configuration and replaces the license")
    void refreshLicense_picksUpNewlyConfiguredCode() {
        properties.setLicenseCode(LicenseFixtures.advancedCode());
        store.initialize();
        assertEquals("LIC-ADVANCED", store.getLicense().getLicenseId());

        properties.setLicenseCode(LicenseFixtures.basicCode());
        store.refreshLicense();

        assertEquals("LIC-BASIC", store.getLicense().getLicenseId());
    }

    @Test
    @DisplayName("Refresh with a broken code logs and keeps the current license")
    void refreshLicense_invalidCode_keepsCurrentLicense() {
        properties.setLicenseCode(LicenseFixtures.advancedCode());
        store.initialize();

        properties.setLicenseCode("corrupted");
        assertDoesNotThrow(() -> store.refreshLicense());

        assertEquals("LIC-ADVANCED", store.getLicense().getLicenseId());
    }

    @Test
    @DisplayName("Refresh without any configured code is a no-op")
    void refreshLicense_noConfiguredCode_isNoOp() {
        properties.setLicenseCode(LicenseFixtures.advancedCode());
        store.initialize();

        properties.setLicenseCode(null);
        store.refreshLicense();

        assertEquals("LIC-ADVANCED", store.getLicense().getLicenseId());
    }

    // ==================== Periodic validation ====================

    @Test
    @DisplayName("Periodic validation is needed before the first load")
    void needsPeriodicValidation_beforeInitialize_true() {
        assertTrue(store.needsPeriodicValidation());
        assertNull(store.getLastValidation());
    }

    @Test
    @DisplayName("Periodic validation is not needed right after a successful load")
    void needsPeriodicValidation_afterInitialize_false() {
        properties.setLicenseCode(LicenseFixtures.advancedCode());
        store.initialize();

        assertFalse(store.needsPeriodicValidation());
    }

    @Test
    @DisplayName("Zero validation interval always requires revalidation")
    void needsPeriodicValidation_zeroInterval_true() {
        properties.setLicenseCode(LicenseFixtures.advancedCode());
        properties.setValidationIntervalSeconds(0);
        store.initialize();

        assertTrue(store.needsPeriodicValidation());
    }
}
