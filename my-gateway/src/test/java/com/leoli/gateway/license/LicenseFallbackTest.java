package com.leoli.gateway.license;

import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.model.LicenseInfo;
import com.leoli.license.client.validator.LicenseValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the offline fallback license built by {@link LicenseValidator}.
 * Verifies that {@code license.client.fallback-edition} is honored (previously
 * the fallback was hardcoded to BASIC).
 */
@DisplayName("License fallback edition")
class LicenseFallbackTest {

    private final LicenseValidator validator = new LicenseValidator();

    private LicenseProperties propertiesWith(String edition) {
        LicenseProperties properties = new LicenseProperties();
        properties.setFallbackEdition(edition);
        return properties;
    }

    @Test
    @DisplayName("Default fallback edition is BASIC with only BASIC features")
    void defaultFallbackIsBasic() {
        LicenseInfo license = validator.buildFallbackLicense(propertiesWith("BASIC"));

        assertEquals("BASIC", license.getEdition());
        assertEquals("FALLBACK-BASIC", license.getLicenseId());
        assertTrue(license.hasFeature(LicenseInfo.Feature.BASIC_ROUTING));
        assertFalse(license.hasFeature(LicenseInfo.Feature.CIRCUIT_BREAKER),
                "BASIC fallback must not include ADVANCED features");
        assertFalse(license.isExpired());
    }

    @Test
    @DisplayName("ENTERPRISE fallback grants every feature including ADVANCED ones")
    void enterpriseFallbackGrantsAllFeatures() {
        LicenseInfo license = validator.buildFallbackLicense(propertiesWith("ENTERPRISE"));

        assertEquals("ENTERPRISE", license.getEdition());
        assertEquals("FALLBACK-ENTERPRISE", license.getLicenseId());
        Set<String> features = license.getFeatures();
        assertEquals(LicenseInfo.Feature.values().length, features.size(),
                "ENTERPRISE fallback must grant all features");
        assertTrue(license.hasFeature(LicenseInfo.Feature.CIRCUIT_BREAKER));
        assertTrue(license.hasFeature(LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT));
    }

    @Test
    @DisplayName("ADVANCED fallback includes ADVANCED features but not ENTERPRISE ones")
    void advancedFallbackGrantsAdvancedFeatures() {
        LicenseInfo license = validator.buildFallbackLicense(propertiesWith("advanced"));

        assertEquals("ADVANCED", license.getEdition());
        assertTrue(license.hasFeature(LicenseInfo.Feature.CIRCUIT_BREAKER));
        assertFalse(license.hasFeature(LicenseInfo.Feature.AI_COPilot),
                "ADVANCED fallback must not include ENTERPRISE features");
    }

    @Test
    @DisplayName("Unknown fallback edition degrades to BASIC")
    void unknownEditionDegradesToBasic() {
        LicenseInfo license = validator.buildFallbackLicense(propertiesWith("PLATINUM"));

        assertEquals("BASIC", license.getEdition());
        assertFalse(license.hasFeature(LicenseInfo.Feature.CIRCUIT_BREAKER));
    }

    @Test
    @DisplayName("Blank fallback edition degrades to BASIC")
    void blankEditionDegradesToBasic() {
        LicenseInfo license = validator.buildFallbackLicense(propertiesWith(""));

        assertEquals("BASIC", license.getEdition());
        assertEquals("FALLBACK-BASIC", license.getLicenseId());
    }
}
