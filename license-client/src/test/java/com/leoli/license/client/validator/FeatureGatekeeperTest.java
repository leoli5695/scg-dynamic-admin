package com.leoli.license.client.validator;

import com.leoli.license.client.exception.LicenseFeatureNotAllowedException;
import com.leoli.license.client.exception.LicenseNotLoadedException;
import com.leoli.license.client.exception.LicenseQuotaExceededException;
import com.leoli.license.client.model.LicenseInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feature Gatekeeper Unit Tests.
 * Tests feature authorization and quota enforcement.
 */
class FeatureGatekeeperTest {

    private FeatureGatekeeper featureGatekeeper;
    private LicenseStore licenseStore;

    @BeforeEach
    void setUp() {
        licenseStore = new LicenseStore();
        featureGatekeeper = new FeatureGatekeeper(licenseStore);
    }

    // ==================== Feature Authorization Tests ====================

    @Test
    @DisplayName("Check Feature Allowed - Authorized Feature")
    void checkFeatureAllowed_authorizedFeature_success() {
        LicenseInfo license = createAdvancedLicense();
        licenseStore.setLicense(license);

        assertTrue(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.WEIGHTED_ROUTING));
        assertTrue(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT));
        assertTrue(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.CIRCUIT_BREAKER));
    }

    @Test
    @DisplayName("Check Feature Allowed - Unauthorized Feature")
    void checkFeatureAllowed_unauthorizedFeature_failure() {
        LicenseInfo license = createAdvancedLicense();
        licenseStore.setLicense(license);

        // Enterprise-only features should not be allowed for Advanced edition
        assertFalse(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.AI_COPilot));
        assertFalse(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.STRESS_TEST));
        assertFalse(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.MULTI_TENANT));
    }

    @Test
    @DisplayName("Check Feature Allowed - Enterprise License")
    void checkFeatureAllowed_enterpriseLicense_allFeatures() {
        LicenseInfo license = createEnterpriseLicense();
        licenseStore.setLicense(license);

        // All features should be allowed for Enterprise edition
        for (LicenseInfo.Feature feature : LicenseInfo.Feature.values()) {
            assertTrue(featureGatekeeper.isFeatureAllowed(feature),
                    "Feature " + feature + " should be allowed for Enterprise");
        }
    }

    @Test
    @DisplayName("Check Feature Allowed - Basic License")
    void checkFeatureAllowed_basicLicense_onlyBasicFeatures() {
        LicenseInfo license = createBasicLicense();
        licenseStore.setLicense(license);

        // Only basic features should be allowed
        assertTrue(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.BASIC_ROUTING));
        assertTrue(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.LOCAL_RATE_LIMIT));

        // Advanced and Enterprise features should not be allowed
        assertFalse(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.WEIGHTED_ROUTING));
        assertFalse(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT));
        assertFalse(featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.AI_COPilot));
    }

    @Test
    @DisplayName("Check Feature Allowed - License Not Loaded")
    void checkFeatureAllowed_licenseNotLoaded_exception() {
        licenseStore.clearLicense();

        assertThrows(LicenseNotLoadedException.class, () -> {
            featureGatekeeper.isFeatureAllowed(LicenseInfo.Feature.WEIGHTED_ROUTING);
        });
    }

    @Test
    @DisplayName("Check Feature Allowed Or Throw - Authorized")
    void checkFeatureAllowedOrThrow_authorized_noException() {
        LicenseInfo license = createAdvancedLicense();
        licenseStore.setLicense(license);

        assertDoesNotThrow(() -> {
            featureGatekeeper.checkFeatureAllowed(LicenseInfo.Feature.WEIGHTED_ROUTING);
        });
    }

    @Test
    @DisplayName("Check Feature Allowed Or Throw - Unauthorized")
    void checkFeatureAllowedOrThrow_unauthorized_exception() {
        LicenseInfo license = createAdvancedLicense();
        licenseStore.setLicense(license);

        assertThrows(LicenseFeatureNotAllowedException.class, () -> {
            featureGatekeeper.checkFeatureAllowed(LicenseInfo.Feature.AI_COPilot);
        });
    }

    @Test
    @DisplayName("Get Authorized Features")
    void getAuthorizedFeatures_success() {
        LicenseInfo license = createAdvancedLicense();
        licenseStore.setLicense(license);

        List<LicenseInfo.Feature> authorizedFeatures = featureGatekeeper.getAuthorizedFeatures();

        assertNotNull(authorizedFeatures);
        assertTrue(authorizedFeatures.contains(LicenseInfo.Feature.WEIGHTED_ROUTING));
        assertTrue(authorizedFeatures.contains(LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT));
        assertFalse(authorizedFeatures.contains(LicenseInfo.Feature.AI_COPilot));
    }

    // ==================== Quota Enforcement Tests ====================

    @Test
    @DisplayName("Check Quota Within Limit - Within Limit")
    void checkQuotaWithinLimit_withinLimit_success() {
        LicenseInfo license = createAdvancedLicense();
        licenseStore.setLicense(license);

        assertTrue(featureGatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.MAX_INSTANCES, 3));
        assertTrue(featureGatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.MAX_ROUTES, 100));
        assertTrue(featureGatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.MAX_SERVICES, 50));
    }

    @Test
    @DisplayName("Check Quota Within Limit - At Limit")
    void checkQuotaWithinLimit_atLimit_success() {
        LicenseInfo license = createAdvancedLicense(); // maxInstances = 5
        licenseStore.setLicense(license);

        assertTrue(featureGatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.MAX_INSTANCES, 5));
    }

    @Test
    @DisplayName("Check Quota Within Limit - Exceeds Limit")
    void checkQuotaWithinLimit_exceedsLimit_failure() {
        LicenseInfo license = createAdvancedLicense(); // maxInstances = 5
        licenseStore.setLicense(license);

        assertFalse(featureGatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.MAX_INSTANCES, 6));
        assertFalse(featureGatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.MAX_ROUTES, 600));
    }

    @Test
    @DisplayName("Check Quota Within Limit - Enterprise Unlimited")
    void checkQuotaWithinLimit_enterpriseUnlimited() {
        LicenseInfo license = createEnterpriseLicense(); // Unlimited quotas
        licenseStore.setLicense(license);

        // Enterprise should have unlimited quotas (high limits)
        assertTrue(featureGatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.MAX_INSTANCES, 1000));
        assertTrue(featureGatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.MAX_ROUTES, 100000));
    }

    @Test
    @DisplayName("Check Quota Within Limit - License Not Loaded")
    void checkQuotaWithinLimit_licenseNotLoaded_exception() {
        licenseStore.clearLicense();

        assertThrows(LicenseNotLoadedException.class, () -> {
            featureGatekeeper.isQuotaWithinLimit(LicenseInfo.QuotaType.MAX_INSTANCES, 1);
        });
    }

    @Test
    @DisplayName("Check Quota Or Throw - Within Limit")
    void checkQuotaOrThrow_withinLimit_noException() {
        LicenseInfo license = createAdvancedLicense();
        licenseStore.setLicense(license);

        assertDoesNotThrow(() -> {
            featureGatekeeper.checkQuotaWithinLimit(LicenseInfo.QuotaType.MAX_INSTANCES, 4);
        });
    }

    @Test
    @DisplayName("Check Quota Or Throw - Exceeds Limit")
    void checkQuotaOrThrow_exceedsLimit_exception() {
        LicenseInfo license = createAdvancedLicense(); // maxInstances = 5
        licenseStore.setLicense(license);

        assertThrows(LicenseQuotaExceededException.class, () -> {
            featureGatekeeper.checkQuotaWithinLimit(LicenseInfo.QuotaType.MAX_INSTANCES, 10);
        });
    }

    @Test
    @DisplayName("Get Quota Limit")
    void getQuotaLimit_success() {
        LicenseInfo license = createAdvancedLicense();
        licenseStore.setLicense(license);

        int maxInstances = featureGatekeeper.getQuotaLimit(LicenseInfo.QuotaType.MAX_INSTANCES);
        int maxRoutes = featureGatekeeper.getQuotaLimit(LicenseInfo.QuotaType.MAX_ROUTES);

        assertEquals(5, maxInstances);
        assertEquals(500, maxRoutes);
    }

    @Test
    @DisplayName("Get Quota Usage Percentage")
    void getQuotaUsagePercentage_success() {
        LicenseInfo license = createAdvancedLicense();
        licenseStore.setLicense(license);

        double percentage = featureGatekeeper.getQuotaUsagePercentage(LicenseInfo.QuotaType.MAX_INSTANCES, 3);

        // 3 / 5 = 60%
        assertEquals(60.0, percentage, 0.1);
    }

    // ==================== Edition Comparison Tests ====================

    @Test
    @DisplayName("Compare Editions - BASIC vs ADVANCED")
    void compareEditions_basicVsAdvanced() {
        assertFalse(featureGatekeeper.isEditionSufficient(LicenseInfo.Edition.BASIC, LicenseInfo.Edition.ADVANCED));
        assertTrue(featureGatekeeper.isEditionSufficient(LicenseInfo.Edition.ADVANCED, LicenseInfo.Edition.BASIC));
    }

    @Test
    @DisplayName("Compare Editions - ADVANCED vs ENTERPRISE")
    void compareEditions_advancedVsEnterprise() {
        assertFalse(featureGatekeeper.isEditionSufficient(LicenseInfo.Edition.ADVANCED, LicenseInfo.Edition.ENTERPRISE));
        assertTrue(featureGatekeeper.isEditionSufficient(LicenseInfo.Edition.ENTERPRISE, LicenseInfo.Edition.ADVANCED));
    }

    @Test
    @DisplayName("Compare Editions - Same Edition")
    void compareEditions_sameEdition() {
        assertTrue(featureGatekeeper.isEditionSufficient(LicenseInfo.Edition.ADVANCED, LicenseInfo.Edition.ADVANCED));
        assertTrue(featureGatekeeper.isEditionSufficient(LicenseInfo.Edition.ENTERPRISE, LicenseInfo.Edition.ENTERPRISE));
    }

    // ==================== Helper Methods ====================

    private LicenseInfo createBasicLicense() {
        return LicenseInfo.builder()
                .licenseId("basic-license")
                .edition(LicenseInfo.Edition.BASIC)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(365, ChronoUnit.DAYS))
                .features(Arrays.asList(
                        LicenseInfo.Feature.BASIC_ROUTING,
                        LicenseInfo.Feature.LOCAL_RATE_LIMIT
                ))
                .quotas(LicenseInfo.Quotas.builder()
                        .maxInstances(1)
                        .maxRoutes(100)
                        .maxServices(10)
                        .maxRateLimitQps(100)
                        .build())
                .valid(true)
                .build();
    }

    private LicenseInfo createAdvancedLicense() {
        return LicenseInfo.builder()
                .licenseId("advanced-license")
                .edition(LicenseInfo.Edition.ADVANCED)
                .issuedAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .expiresAt(Instant.now().plus(365, ChronoUnit.DAYS))
                .features(Arrays.asList(
                        LicenseInfo.Feature.WEIGHTED_ROUTING,
                        LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT,
                        LicenseInfo.Feature.CIRCUIT_BREAKER,
                        LicenseInfo.Feature.K8S_INTEGRATION,
                        LicenseInfo.Feature.GLOBAL_CACHE
                ))
                .quotas(LicenseInfo.Quotas.builder()
                        .maxInstances(5)
                        .maxRoutes(500)
                        .maxServices(100)
                        .maxRateLimitQps(10000)
                        .build())
                .valid(true)
                .build();
    }

    private LicenseInfo createEnterpriseLicense() {
        return LicenseInfo.builder()
                .licenseId("enterprise-license")
                .edition(LicenseInfo.Edition.ENTERPRISE)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(3650, ChronoUnit.DAYS)) // 10 years
                .features(Arrays.asList(LicenseInfo.Feature.values())) // All features
                .quotas(LicenseInfo.Quotas.builder()
                        .maxInstances(Integer.MAX_VALUE)
                        .maxRoutes(Integer.MAX_VALUE)
                        .maxServices(Integer.MAX_VALUE)
                        .maxRateLimitQps(Integer.MAX_VALUE)
                        .build())
                .valid(true)
                .build();
    }
}