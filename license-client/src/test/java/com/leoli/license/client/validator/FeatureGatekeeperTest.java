package com.leoli.license.client.validator;

import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.exception.FeatureNotLicensedException;
import com.leoli.license.client.exception.LicenseFeatureNotAllowedException;
import com.leoli.license.client.exception.LicenseNotLoadedException;
import com.leoli.license.client.exception.QuotaExceededException;
import com.leoli.license.client.model.LicenseInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Feature Gatekeeper Unit Tests.
 * Covers feature authorization, edition fallbacks and quota enforcement.
 * LicenseStore is mocked so these tests exercise gatekeeper logic only.
 */
class FeatureGatekeeperTest {

    private LicenseStore store;
    private LicenseProperties properties;
    private FeatureGatekeeper gatekeeper;

    @BeforeEach
    void setUp() {
        store = mock(LicenseStore.class);
        properties = new LicenseProperties();
        gatekeeper = new FeatureGatekeeper(store, properties);
    }

    private void givenLicense(LicenseInfo license) {
        when(store.getLicense()).thenReturn(license);
    }

    private LicenseInfo license(String licenseId, String edition, Set<String> features,
                                LicenseInfo.QuotaInfo quotas, LocalDateTime expiresAt) {
        return LicenseInfo.builder()
                .licenseId(licenseId)
                .edition(edition)
                .customerName("Test Customer")
                .features(features)
                .quotas(quotas)
                .expiresAt(expiresAt)
                .build();
    }

    private LicenseInfo advancedLicense() {
        return license("LIC-ADVANCED", "ADVANCED",
                Set.of("WEIGHTED_ROUTING", "DISTRIBUTED_RATE_LIMIT", "CIRCUIT_BREAKER"),
                LicenseInfo.QuotaInfo.builder()
                        .maxInstances(5)
                        .maxRoutes(500)
                        .maxServices(100)
                        .maxRateLimitQPS(1000)
                        .supportLevel("STANDARD")
                        .build(),
                LocalDateTime.now().plusDays(365));
    }

    // ==================== Feature authorization ====================

    @Test
    @DisplayName("hasFeature - authorized feature returns true")
    void hasFeature_authorized_true() {
        givenLicense(advancedLicense());

        assertTrue(gatekeeper.hasFeature(LicenseInfo.Feature.WEIGHTED_ROUTING));
        assertTrue(gatekeeper.hasFeature(LicenseInfo.Feature.CIRCUIT_BREAKER));
    }

    @Test
    @DisplayName("hasFeature - feature outside the license returns false")
    void hasFeature_notLicensed_false() {
        givenLicense(advancedLicense());

        assertFalse(gatekeeper.hasFeature(LicenseInfo.Feature.AI_COPilot));
        assertFalse(gatekeeper.hasFeature(LicenseInfo.Feature.MULTI_TENANT));
    }

    @Test
    @DisplayName("hasFeature - no license degrades to BASIC features when fallback allowed")
    void hasFeature_noLicense_fallbackAllowsBasicOnly() {
        givenLicense(null);
        properties.setAllowBasicFallback(true);

        assertTrue(gatekeeper.hasFeature(LicenseInfo.Feature.BASIC_ROUTING));
        assertTrue(gatekeeper.hasFeature(LicenseInfo.Feature.LOCAL_RATE_LIMIT));
        assertFalse(gatekeeper.hasFeature(LicenseInfo.Feature.WEIGHTED_ROUTING));
        assertFalse(gatekeeper.hasFeature(LicenseInfo.Feature.AI_COPilot));
    }

    @Test
    @DisplayName("hasFeature - no license and no fallback denies everything")
    void hasFeature_noLicenseAndNoFallback_deniesAll() {
        givenLicense(null);
        properties.setAllowBasicFallback(false);

        assertFalse(gatekeeper.hasFeature(LicenseInfo.Feature.BASIC_ROUTING));
        assertFalse(gatekeeper.hasFeature(LicenseInfo.Feature.WEIGHTED_ROUTING));
    }

    @Test
    @DisplayName("hasFeature - expired license degrades to BASIC features when fallback allowed")
    void hasFeature_expiredLicense_fallbackAllowsBasicOnly() {
        LicenseInfo expired = license("LIC-EXPIRED", "ADVANCED",
                Set.of("WEIGHTED_ROUTING"), null, LocalDateTime.now().minusDays(1));
        givenLicense(expired);
        properties.setAllowBasicFallback(true);

        assertTrue(gatekeeper.hasFeature(LicenseInfo.Feature.BASIC_ROUTING));
        assertFalse(gatekeeper.hasFeature(LicenseInfo.Feature.WEIGHTED_ROUTING),
                "an expired license must not keep granting its licensed features");
    }

    @Test
    @DisplayName("hasFeature - expired license and no fallback denies everything")
    void hasFeature_expiredLicenseAndNoFallback_deniesAll() {
        LicenseInfo expired = license("LIC-EXPIRED", "ADVANCED",
                Set.of("WEIGHTED_ROUTING"), null, LocalDateTime.now().minusDays(1));
        givenLicense(expired);
        properties.setAllowBasicFallback(false);

        assertFalse(gatekeeper.hasFeature(LicenseInfo.Feature.BASIC_ROUTING));
    }

    @Test
    @DisplayName("isFeatureAllowed - throws when no license is loaded")
    void isFeatureAllowed_noLicense_throws() {
        givenLicense(null);

        assertThrows(LicenseNotLoadedException.class,
                () -> gatekeeper.isFeatureAllowed(LicenseInfo.Feature.WEIGHTED_ROUTING));
    }

    @Test
    @DisplayName("isFeatureAllowed - reflects the loaded license")
    void isFeatureAllowed_loadedLicense_delegatesToHasFeature() {
        givenLicense(advancedLicense());

        assertTrue(gatekeeper.isFeatureAllowed(LicenseInfo.Feature.WEIGHTED_ROUTING));
        assertFalse(gatekeeper.isFeatureAllowed(LicenseInfo.Feature.AI_COPilot));
    }

    @Test
    @DisplayName("requireFeature - authorized feature does not throw")
    void requireFeature_authorized_noException() {
        givenLicense(advancedLicense());

        assertDoesNotThrow(() -> gatekeeper.requireFeature(LicenseInfo.Feature.WEIGHTED_ROUTING));
    }

    @Test
    @DisplayName("requireFeature - unauthorized feature throws with edition context")
    void requireFeature_unauthorized_throwsWithContext() {
        givenLicense(advancedLicense());

        FeatureNotLicensedException e = assertThrows(FeatureNotLicensedException.class,
                () -> gatekeeper.requireFeature(LicenseInfo.Feature.AI_COPilot));

        assertEquals(LicenseInfo.Feature.AI_COPilot, e.getFeature());
        assertEquals("ADVANCED", e.getCurrentEdition());
    }

    @Test
    @DisplayName("requireFeature - no license still permits BASIC features via fallback")
    void requireFeature_noLicense_permitsBasicFeature() {
        givenLicense(null);
        properties.setAllowBasicFallback(true);

        assertDoesNotThrow(() -> gatekeeper.requireFeature(LicenseInfo.Feature.BASIC_ROUTING));
    }

    @Test
    @DisplayName("checkFeatureAllowed - authorized feature does not throw")
    void checkFeatureAllowed_authorized_noException() {
        givenLicense(advancedLicense());

        assertDoesNotThrow(() -> gatekeeper.checkFeatureAllowed(LicenseInfo.Feature.WEIGHTED_ROUTING));
    }

    @Test
    @DisplayName("checkFeatureAllowed - unauthorized feature reports required and current edition")
    void checkFeatureAllowed_unauthorized_throwsWithEditions() {
        givenLicense(advancedLicense());

        LicenseFeatureNotAllowedException e = assertThrows(LicenseFeatureNotAllowedException.class,
                () -> gatekeeper.checkFeatureAllowed(LicenseInfo.Feature.AI_COPilot));

        assertEquals("AI_COPilot", e.getFeature());
        assertEquals("ENTERPRISE", e.getRequiredEdition());
        assertEquals("ADVANCED", e.getCurrentEdition());
    }

    @Test
    @DisplayName("checkFeatureAllowed - throws when no license is loaded")
    void checkFeatureAllowed_noLicense_throwsNotLoaded() {
        givenLicense(null);

        assertThrows(LicenseNotLoadedException.class,
                () -> gatekeeper.checkFeatureAllowed(LicenseInfo.Feature.BASIC_ROUTING));
    }

    // ==================== Quota enforcement (string API) ====================

    @Test
    @DisplayName("checkQuota - below the limit passes")
    void checkQuota_belowLimit_true() {
        givenLicense(advancedLicense());

        assertTrue(gatekeeper.checkQuota("maxInstances", 0));
        assertTrue(gatekeeper.checkQuota("maxInstances", 4));
        assertTrue(gatekeeper.checkQuota("maxRoutes", 499));
    }

    @Test
    @DisplayName("checkQuota - at or above the limit fails")
    void checkQuota_atOrAboveLimit_false() {
        givenLicense(advancedLicense());

        assertFalse(gatekeeper.checkQuota("maxInstances", 5), "the limit is exclusive: current must stay below it");
        assertFalse(gatekeeper.checkQuota("maxInstances", 6));
        assertFalse(gatekeeper.checkQuota("maxRoutes", 500));
    }

    @Test
    @DisplayName("checkQuota - unlimited (-1) never fails")
    void checkQuota_unlimited_true() {
        givenLicense(license("LIC-ENTERPRISE", "ENTERPRISE", Set.of(),
                LicenseInfo.QuotaInfo.builder().maxInstances(-1).maxRoutes(-1).build(),
                LocalDateTime.now().plusDays(365)));

        assertTrue(gatekeeper.checkQuota("maxInstances", Integer.MAX_VALUE));
        assertTrue(gatekeeper.checkQuota("maxRoutes", 1_000_000));
    }

    @Test
    @DisplayName("checkQuota - unset limit is unrestricted")
    void checkQuota_nullLimit_true() {
        givenLicense(license("LIC-PARTIAL", "ADVANCED", Set.of(),
                LicenseInfo.QuotaInfo.builder().maxInstances(5).build(),
                LocalDateTime.now().plusDays(365)));

        assertTrue(gatekeeper.checkQuota("maxRoutes", 10_000), "a quota that was never set must not restrict");
    }

    @Test
    @DisplayName("checkQuota - unknown quota type is unrestricted")
    void checkQuota_unknownType_true() {
        givenLicense(advancedLicense());

        assertTrue(gatekeeper.checkQuota("maxUnobtainium", 999));
    }

    @Test
    @DisplayName("checkQuota - no license or no quotas is unrestricted")
    void checkQuota_noLicense_true() {
        givenLicense(null);
        assertTrue(gatekeeper.checkQuota("maxInstances", 999));

        givenLicense(license("LIC-NOQUOTA", "BASIC", Set.of(), null, LocalDateTime.now().plusDays(365)));
        assertTrue(gatekeeper.checkQuota("maxInstances", 999));
    }

    @Test
    @DisplayName("requireQuota - below the limit does not throw")
    void requireQuota_belowLimit_noException() {
        givenLicense(advancedLicense());

        assertDoesNotThrow(() -> gatekeeper.requireQuota("maxInstances", 4));
    }

    @Test
    @DisplayName("requireQuota - at or above the limit throws with limit details")
    void requireQuota_atOrAboveLimit_throws() {
        givenLicense(advancedLicense());

        QuotaExceededException atLimit = assertThrows(QuotaExceededException.class,
                () -> gatekeeper.requireQuota("maxInstances", 5));
        assertEquals("maxInstances", atLimit.getQuotaType());
        assertEquals(5, atLimit.getLimit());
        assertEquals(5, atLimit.getCurrent());

        assertThrows(QuotaExceededException.class, () -> gatekeeper.requireQuota("maxInstances", 6));
    }

    @Test
    @DisplayName("requireQuota - no license does not throw")
    void requireQuota_noLicense_noException() {
        givenLicense(null);

        assertDoesNotThrow(() -> gatekeeper.requireQuota("maxInstances", 999));
    }

    // ==================== Quota enforcement (enum API) ====================

    @Test
    @DisplayName("isQuotaWithinLimit - enforces the license quota")
    void isQuotaWithinLimit_enforcesLimit() {
        givenLicense(advancedLicense());

        assertTrue(gatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.INSTANCES, 4));
        assertFalse(gatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.INSTANCES, 5));
        assertFalse(gatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.INSTANCES, 6));
        assertTrue(gatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.ROUTES, 499));
        assertFalse(gatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.ROUTES, 500));
    }

    @Test
    @DisplayName("Every QuotaType resolves to a real limit instead of being treated as unlimited")
    void isQuotaWithinLimit_allQuotaTypesAreEnforced() {
        givenLicense(license("LIC-ALL-QUOTAS", "ENTERPRISE", Set.of(),
                LicenseInfo.QuotaInfo.builder()
                        .maxInstances(5)
                        .maxRoutes(5)
                        .maxServices(5)
                        .maxStrategies(5)
                        .maxAuthPolicies(5)
                        .maxRateLimitQPS(5)
                        .maxApiCopilotCalls(5)
                        .maxTraceRetention(5)
                        .maxAuditLogRetention(5)
                        .build(),
                LocalDateTime.now().plusDays(365)));

        for (LicenseInfo.QuotaType type : LicenseInfo.QuotaType.values()) {
            assertTrue(gatekeeper.isQuotaWithinLimit(type, 4),
                    type + " should allow usage below the limit");
            assertFalse(gatekeeper.isQuotaWithinLimit(type, 5),
                    type + " must map to a real limit - an unmapped key silently means unlimited");
        }
    }

    @Test
    @DisplayName("checkQuotaWithinLimit - throws when the quota is exceeded")
    void checkQuotaWithinLimit_exceeded_throws() {
        givenLicense(advancedLicense());

        assertDoesNotThrow(() -> gatekeeper.checkQuotaWithinLimit(LicenseInfo.QuotaType.INSTANCES, 4));

        // checkQuotaWithinLimit declares LicenseQuotaExceededException but requireQuota throws
        // its unrelated sibling QuotaExceededException; asserted as actually thrown.
        QuotaExceededException e = assertThrows(QuotaExceededException.class,
                () -> gatekeeper.checkQuotaWithinLimit(LicenseInfo.QuotaType.INSTANCES, 10));

        assertEquals("maxInstances", e.getQuotaType());
        assertEquals(5, e.getLimit());
        assertEquals(10, e.getCurrent());
    }

    @Test
    @DisplayName("Enum quota API - throws when no license is loaded")
    void quotaEnumApi_noLicense_throwsNotLoaded() {
        givenLicense(null);

        assertThrows(LicenseNotLoadedException.class,
                () -> gatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.INSTANCES, 1));
        assertThrows(LicenseNotLoadedException.class,
                () -> gatekeeper.checkQuotaWithinLimit(LicenseInfo.QuotaType.INSTANCES, 1));
    }

    // ==================== Edition and status reporting ====================

    @Test
    @DisplayName("getCurrentEdition - reports the loaded license edition")
    void getCurrentEdition_withLicense_returnsLicenseEdition() {
        givenLicense(advancedLicense());

        assertEquals("ADVANCED", gatekeeper.getCurrentEdition());
    }

    @Test
    @DisplayName("getCurrentEdition - falls back to the configured edition")
    void getCurrentEdition_noLicense_returnsConfiguredFallback() {
        givenLicense(null);
        properties.setFallbackEdition("ENTERPRISE");

        assertEquals("ENTERPRISE", gatekeeper.getCurrentEdition());
    }

    @Test
    @DisplayName("isLicenseValid - true only for a loaded, unexpired license")
    void isLicenseValid_reflectsLoadStateAndExpiry() {
        givenLicense(advancedLicense());
        assertTrue(gatekeeper.isLicenseValid());

        givenLicense(null);
        assertFalse(gatekeeper.isLicenseValid());

        givenLicense(license("LIC-EXPIRED", "ADVANCED", Set.of(), null, LocalDateTime.now().minusDays(1)));
        assertFalse(gatekeeper.isLicenseValid());
    }

    @Test
    @DisplayName("getDaysRemaining - counts down to expiry, null when no license")
    void getDaysRemaining_reportsRemainingDays() {
        givenLicense(advancedLicense());
        Integer days = gatekeeper.getDaysRemaining();
        assertNotNull(days);
        assertTrue(days >= 363 && days <= 365, "expected ~365 days but was " + days);

        givenLicense(null);
        assertNull(gatekeeper.getDaysRemaining());
    }

    @Test
    @DisplayName("getDaysRemaining - permanent license reports null")
    void getDaysRemaining_permanentLicense_null() {
        givenLicense(license("LIC-PERMANENT", "ENTERPRISE", Set.of(), null, null));

        assertNull(gatekeeper.getDaysRemaining());
    }

    @Test
    @DisplayName("shouldWarnExpiration - true inside the warning window")
    void shouldWarnExpiration_withinWindow_true() {
        givenLicense(license("LIC-SOON", "ADVANCED", Set.of(), null, LocalDateTime.now().plusDays(10)));
        properties.setWarnDaysBeforeExpiry(30);

        assertTrue(gatekeeper.shouldWarnExpiration());
    }

    @Test
    @DisplayName("shouldWarnExpiration - false outside the window, when permanent, or when unloaded")
    void shouldWarnExpiration_otherCases_false() {
        properties.setWarnDaysBeforeExpiry(30);

        givenLicense(license("LIC-FAR", "ADVANCED", Set.of(), null, LocalDateTime.now().plusDays(100)));
        assertFalse(gatekeeper.shouldWarnExpiration());

        givenLicense(license("LIC-PERMANENT", "ENTERPRISE", Set.of(), null, null));
        assertFalse(gatekeeper.shouldWarnExpiration());

        givenLicense(null);
        assertFalse(gatekeeper.shouldWarnExpiration());
    }

    @Test
    @DisplayName("getLicenseSummary - includes edition and customer")
    void getLicenseSummary_withLicense_includesEditionAndCustomer() {
        givenLicense(advancedLicense());

        String summary = gatekeeper.getLicenseSummary();

        assertTrue(summary.contains("ADVANCED"), summary);
        assertTrue(summary.contains("Test Customer"), summary);
    }

    @Test
    @DisplayName("getLicenseSummary - reports the fallback edition when no license is loaded")
    void getLicenseSummary_noLicense_mentionsFallback() {
        givenLicense(null);
        properties.setFallbackEdition("BASIC");

        String summary = gatekeeper.getLicenseSummary();

        assertTrue(summary.contains("No license"), summary);
        assertTrue(summary.contains("BASIC"), summary);
    }
}
