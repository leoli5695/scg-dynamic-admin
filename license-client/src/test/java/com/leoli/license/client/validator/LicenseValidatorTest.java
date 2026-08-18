package com.leoli.license.client.validator;

import com.leoli.license.client.exception.LicenseExpiredException;
import com.leoli.license.client.exception.LicenseInvalidException;
import com.leoli.license.client.exception.LicenseNotLoadedException;
import com.leoli.license.client.model.LicenseInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * License Validator Unit Tests.
 * Tests offline signature verification and license validation.
 */
class LicenseValidatorTest {

    private LicenseValidator licenseValidator;
    private LicenseStore licenseStore;

    @BeforeEach
    void setUp() {
        licenseStore = new LicenseStore();
        licenseValidator = new LicenseValidator(licenseStore);
    }

    @Test
    @DisplayName("Validate Valid License - Success")
    void validateValidLicense_success() {
        LicenseInfo license = createValidLicense();

        assertDoesNotThrow(() -> {
            licenseValidator.validate(license);
        });
    }

    @Test
    @DisplayName("Validate Expired License - Exception")
    void validateExpiredLicense_exception() {
        LicenseInfo license = createExpiredLicense();

        assertThrows(LicenseExpiredException.class, () -> {
            licenseValidator.validate(license);
        });
    }

    @Test
    @DisplayName("Validate License with Null Expiry - Exception")
    void validateLicenseWithNullExpiry_exception() {
        LicenseInfo license = LicenseInfo.builder()
                .licenseId("test-id")
                .edition(LicenseInfo.Edition.ADVANCED)
                .issuedAt(Instant.now())
                .expiresAt(null)
                .build();

        assertThrows(LicenseExpiredException.class, () -> {
            licenseValidator.validate(license);
        });
    }

    @Test
    @DisplayName("Check License Not Expired")
    void checkLicenseNotExpired_success() {
        LicenseInfo license = createValidLicense();

        assertFalse(licenseValidator.isExpired(license));
    }

    @Test
    @DisplayName("Check License Expired")
    void checkLicenseExpired_true() {
        LicenseInfo license = createExpiredLicense();

        assertTrue(licenseValidator.isExpired(license));
    }

    @Test
    @DisplayName("Check License Expiring Soon")
    void checkLicenseExpiringSoon_true() {
        LicenseInfo license = LicenseInfo.builder()
                .licenseId("expiring-soon")
                .edition(LicenseInfo.Edition.ADVANCED)
                .issuedAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .expiresAt(Instant.now().plus(5, ChronoUnit.DAYS))
                .build();

        assertTrue(licenseValidator.isExpiringSoon(license, 7));
        assertFalse(licenseValidator.isExpiringSoon(license, 3));
    }

    @Test
    @DisplayName("Calculate Remaining Days")
    void calculateRemainingDays_success() {
        LicenseInfo license = LicenseInfo.builder()
                .licenseId("remaining-days-test")
                .edition(LicenseInfo.Edition.ENTERPRISE)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();

        int remainingDays = licenseValidator.calculateRemainingDays(license);

        assertTrue(remainingDays >= 29 && remainingDays <= 31);
    }

    @Test
    @DisplayName("Calculate Remaining Days - Expired License")
    void calculateRemainingDays_expiredLicense_negative() {
        LicenseInfo license = createExpiredLicense();

        int remainingDays = licenseValidator.calculateRemainingDays(license);

        assertTrue(remainingDays < 0);
    }

    @Test
    @DisplayName("Validate License Code Format - Valid Base64")
    void validateLicenseCodeFormat_validBase64() {
        // Valid Base64 string
        String validBase64 = "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ";

        assertTrue(licenseValidator.isValidBase64Format(validBase64));
    }

    @Test
    @DisplayName("Validate License Code Format - Invalid Base64")
    void validateLicenseCodeFormat_invalidBase64() {
        String invalidBase64 = "Invalid!!!Base64";

        assertFalse(licenseValidator.isValidBase64Format(invalidBase64));
    }

    @Test
    @DisplayName("Validate License Code Format - Empty String")
    void validateLicenseCodeFormat_emptyString() {
        assertFalse(licenseValidator.isValidBase64Format(""));
        assertFalse(licenseValidator.isValidBase64Format(null));
    }

    @Test
    @DisplayName("Load and Validate License Code")
    void loadAndValidateLicenseCode_success() throws Exception {
        // Create a valid license and store it
        LicenseInfo license = createValidLicense();
        licenseStore.setLicense(license);

        // Verify the license is loaded
        assertTrue(licenseValidator.isLicenseLoaded());
    }

    @Test
    @DisplayName("Validate Without Loading - Exception")
    void validateWithoutLoading_exception() {
        // Clear the store
        licenseStore.clearLicense();

        assertThrows(LicenseNotLoadedException.class, () -> {
            licenseValidator.validateCurrentLicense();
        });
    }

    @Test
    @DisplayName("Validate Current License - Loaded")
    void validateCurrentLicense_loaded_success() {
        LicenseInfo license = createValidLicense();
        licenseStore.setLicense(license);

        assertDoesNotThrow(() -> {
            licenseValidator.validateCurrentLicense();
        });
    }

    @Test
    @DisplayName("Get Current License - Not Loaded")
    void getCurrentLicense_notLoaded_null() {
        licenseStore.clearLicense();

        assertNull(licenseValidator.getCurrentLicense());
    }

    @Test
    @DisplayName("Get Current License - Loaded")
    void getCurrentLicense_loaded_success() {
        LicenseInfo license = createValidLicense();
        licenseStore.setLicense(license);

        LicenseInfo current = licenseValidator.getCurrentLicense();

        assertNotNull(current);
        assertEquals("valid-license-id", current.getLicenseId());
    }

    private LicenseInfo createValidLicense() {
        List<LicenseInfo.Feature> features = Arrays.asList(
                LicenseInfo.Feature.WEIGHTED_ROUTING,
                LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT,
                LicenseInfo.Feature.CIRCUIT_BREAKER
        );

        return LicenseInfo.builder()
                .licenseId("valid-license-id")
                .edition(LicenseInfo.Edition.ADVANCED)
                .issuedAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .expiresAt(Instant.now().plus(365, ChronoUnit.DAYS))
                .features(features)
                .customer(LicenseInfo.Customer.builder()
                        .name("Test Customer")
                        .email("test@example.com")
                        .build())
                .quotas(LicenseInfo.Quotas.builder()
                        .maxInstances(5)
                        .maxRoutes(500)
                        .maxServices(100)
                        .maxRateLimitQps(10000)
                        .build())
                .valid(true)
                .build();
    }

    private LicenseInfo createExpiredLicense() {
        return LicenseInfo.builder()
                .licenseId("expired-license-id")
                .edition(LicenseInfo.Edition.BASIC)
                .issuedAt(Instant.now().minus(365, ChronoUnit.DAYS))
                .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .valid(true)
                .build();
    }
}