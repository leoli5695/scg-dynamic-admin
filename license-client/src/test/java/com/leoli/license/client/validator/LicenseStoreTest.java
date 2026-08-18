package com.leoli.license.client.validator;

import com.leoli.license.client.model.LicenseInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * License Store Unit Tests.
 * Tests thread-safe license storage and retrieval.
 */
class LicenseStoreTest {

    private LicenseStore licenseStore;

    @BeforeEach
    void setUp() {
        licenseStore = new LicenseStore();
    }

    @Test
    @DisplayName("Set and Get License")
    void setAndGetLicense_success() {
        LicenseInfo license = createTestLicense();

        licenseStore.setLicense(license);

        LicenseInfo retrieved = licenseStore.getLicense();

        assertNotNull(retrieved);
        assertEquals(license.getLicenseId(), retrieved.getLicenseId());
        assertEquals(license.getEdition(), retrieved.getEdition());
    }

    @Test
    @DisplayName("Get License - Not Set")
    void getLicense_notSet_null() {
        LicenseInfo retrieved = licenseStore.getLicense();

        assertNull(retrieved);
    }

    @Test
    @DisplayName("Clear License")
    void clearLicense_success() {
        LicenseInfo license = createTestLicense();
        licenseStore.setLicense(license);

        assertNotNull(licenseStore.getLicense());

        licenseStore.clearLicense();

        assertNull(licenseStore.getLicense());
    }

    @Test
    @DisplayName("Check Has License - True")
    void hasLicense_true() {
        LicenseInfo license = createTestLicense();
        licenseStore.setLicense(license);

        assertTrue(licenseStore.hasLicense());
    }

    @Test
    @DisplayName("Check Has License - False")
    void hasLicense_false() {
        assertFalse(licenseStore.hasLicense());
    }

    @Test
    @DisplayName("Update License")
    void updateLicense_success() {
        LicenseInfo license1 = createTestLicense();
        license1.setLicenseId("license-1");

        licenseStore.setLicense(license1);
        assertEquals("license-1", licenseStore.getLicense().getLicenseId());

        LicenseInfo license2 = createTestLicense();
        license2.setLicenseId("license-2");

        licenseStore.setLicense(license2);
        assertEquals("license-2", licenseStore.getLicense().getLicenseId());
    }

    @Test
    @DisplayName("Concurrent Access - Thread Safety")
    void concurrentAccess_threadSafety() throws InterruptedException {
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    if (index % 2 == 0) {
                        // Half threads set license
                        LicenseInfo license = createTestLicense();
                        license.setLicenseId("concurrent-" + index);
                        licenseStore.setLicense(license);
                    } else {
                        // Half threads get license
                        LicenseInfo retrieved = licenseStore.getLicense();
                        // May be null or some value set by another thread
                    }
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, successCount.get());
        // Final license should be one of the values set
        assertNotNull(licenseStore.getLicense());
    }

    @Test
    @DisplayName("Get License Edition")
    void getLicenseEdition_success() {
        LicenseInfo license = createTestLicense();
        licenseStore.setLicense(license);

        LicenseInfo.Edition edition = licenseStore.getLicenseEdition();

        assertNotNull(edition);
        assertEquals(LicenseInfo.Edition.ADVANCED, edition);
    }

    @Test
    @DisplayName("Get License Edition - Not Set")
    void getLicenseEdition_notSet_null() {
        assertNull(licenseStore.getLicenseEdition());
    }

    @Test
    @DisplayName("Get License Features")
    void getLicenseFeatures_success() {
        LicenseInfo license = createTestLicense();
        licenseStore.setLicense(license);

        List<LicenseInfo.Feature> features = licenseStore.getLicenseFeatures();

        assertNotNull(features);
        assertTrue(features.contains(LicenseInfo.Feature.WEIGHTED_ROUTING));
        assertTrue(features.contains(LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT));
    }

    @Test
    @DisplayName("Get License Features - Not Set")
    void getLicenseFeatures_notSet_emptyList() {
        List<LicenseInfo.Feature> features = licenseStore.getLicenseFeatures();

        assertNotNull(features);
        assertTrue(features.isEmpty());
    }

    @Test
    @DisplayName("Get License Quotas")
    void getLicenseQuotas_success() {
        LicenseInfo license = createTestLicense();
        licenseStore.setLicense(license);

        LicenseInfo.Quotas quotas = licenseStore.getLicenseQuotas();

        assertNotNull(quotas);
        assertEquals(5, quotas.getMaxInstances());
        assertEquals(500, quotas.getMaxRoutes());
    }

    @Test
    @DisplayName("Get License Quotas - Not Set")
    void getLicenseQuotas_notSet_null() {
        assertNull(licenseStore.getLicenseQuotas());
    }

    @Test
    @DisplayName("Check License Valid - True")
    void isLicenseValid_true() {
        LicenseInfo license = createTestLicense();
        license.setValid(true);
        licenseStore.setLicense(license);

        assertTrue(licenseStore.isLicenseValid());
    }

    @Test
    @DisplayName("Check License Valid - False")
    void isLicenseValid_false() {
        LicenseInfo license = createTestLicense();
        license.setValid(false);
        licenseStore.setLicense(license);

        assertFalse(licenseStore.isLicenseValid());
    }

    @Test
    @DisplayName("Check License Valid - Not Set")
    void isLicenseValid_notSet_false() {
        assertFalse(licenseStore.isLicenseValid());
    }

    @Test
    @DisplayName("Check License Expired - Not Expired")
    void isLicenseExpired_notExpired_false() {
        LicenseInfo license = createTestLicense();
        license.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        licenseStore.setLicense(license);

        assertFalse(licenseStore.isLicenseExpired());
    }

    @Test
    @DisplayName("Check License Expired - Expired")
    void isLicenseExpired_expired_true() {
        LicenseInfo license = createTestLicense();
        license.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        licenseStore.setLicense(license);

        assertTrue(licenseStore.isLicenseExpired());
    }

    @Test
    @DisplayName("Get Remaining Days")
    void getRemainingDays_success() {
        LicenseInfo license = createTestLicense();
        license.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        licenseStore.setLicense(license);

        int remainingDays = licenseStore.getRemainingDays();

        assertTrue(remainingDays >= 29 && remainingDays <= 31);
    }

    @Test
    @DisplayName("Get Remaining Days - Expired")
    void getRemainingDays_expired_negative() {
        LicenseInfo license = createTestLicense();
        license.setExpiresAt(Instant.now().minus(10, ChronoUnit.DAYS));
        licenseStore.setLicense(license);

        int remainingDays = licenseStore.getRemainingDays();

        assertTrue(remainingDays < 0);
    }

    @Test
    @DisplayName("Set Null License")
    void setNullLicense_success() {
        licenseStore.setLicense(createTestLicense());
        assertNotNull(licenseStore.getLicense());

        licenseStore.setLicense(null);

        assertNull(licenseStore.getLicense());
    }

    private LicenseInfo createTestLicense() {
        return LicenseInfo.builder()
                .licenseId("test-license-id")
                .edition(LicenseInfo.Edition.ADVANCED)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(365, ChronoUnit.DAYS))
                .features(Arrays.asList(
                        LicenseInfo.Feature.WEIGHTED_ROUTING,
                        LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT,
                        LicenseInfo.Feature.CIRCUIT_BREAKER
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
}