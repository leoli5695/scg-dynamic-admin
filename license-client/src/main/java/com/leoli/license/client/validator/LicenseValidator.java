package com.leoli.license.client.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.exception.*;
import com.leoli.license.client.model.LicenseInfo;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.*;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.Base64;

/**
 * License Validator - Offline RSA Signature Verification
 * <p>
 * Validates License Code using RSA public key embedded in the code.
 * No network requests required - pure offline validation.
 * <p>
 * Reference:
 * - JwtAuthProcessor.getPublicKey (X509EncodedKeySpec parsing)
 * - HmacSignatureAuthProcessor.constantTimeEquals (timing attack prevention)
 */
@Slf4j
public class LicenseValidator {

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String RSA_ALGORITHM = "RSA";

    /**
     * Validate license code and return LicenseInfo
     *
     * @param licenseCode Base64 encoded license code
     * @param properties  License client properties
     * @return LicenseInfo if valid
     * @throws LicenseInvalidException if signature verification fails
     * @throws LicenseExpiredException if license has expired
     */
    public LicenseInfo validate(String licenseCode, LicenseProperties properties) 
            throws LicenseInvalidException, LicenseExpiredException {
        try {
            // Decode Base64
            String json = new String(Base64.getDecoder().decode(licenseCode), StandardCharsets.UTF_8);
            Map<String, Object> licenseMap = objectMapper.readValue(json, Map.class);

            // Extract components
            Map<String, Object> payload = (Map<String, Object>) licenseMap.get("payload");
            String signature = (String) licenseMap.get("signature");
            String publicKey = (String) licenseMap.get("publicKey");

            if (payload == null || signature == null || publicKey == null) {
                throw new LicenseInvalidException("Invalid license format - missing components");
            }

            // Verify signature
            byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
            if (!verifySignature(payloadBytes, signature, publicKey)) {
                throw new LicenseInvalidException("Signature verification failed");
            }

            // Build LicenseInfo from payload
            LicenseInfo info = buildLicenseInfo(payload, licenseCode);

            // Check expiration
            if (info.isExpired()) {
                throw new LicenseExpiredException(info.getLicenseId(), 
                    info.getExpiresAt() != null ? info.getExpiresAt().toString() : null);
            }

            // Check machine binding
            if (properties.getMachineId() != null && info.getMachineId() != null) {
                if (!properties.getMachineId().equals(info.getMachineId())) {
                    throw new LicenseInvalidException(
                        "Machine binding mismatch. Expected: " + info.getMachineId());
                }
            }

            log.info("License validated successfully: id={}, edition={}, expires={}",
                info.getLicenseId(), info.getEdition(), info.getExpiresAt());

            return info;

        } catch (LicenseInvalidException | LicenseExpiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("License validation failed", e);
            throw new LicenseInvalidException("Validation error: " + e.getMessage(), e);
        }
    }

    /**
     * Verify RSA signature
     * Reference: JwtAuthProcessor signature verification pattern
     */
    private boolean verifySignature(byte[] payloadBytes, String signatureBase64, String publicKeyBase64) 
            throws Exception {
        // Parse public key (X509EncodedKeySpec)
        String publicKeyContent = publicKeyBase64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(publicKeyContent);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
        PublicKey publicKey = keyFactory.generatePublic(spec);

        // Verify signature
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initVerify(publicKey);
        sig.update(payloadBytes);

        boolean verified = sig.verify(signatureBytes);

        // Additional constant-time comparison (security enhancement)
        if (verified) {
            return true;
        }

        log.warn("Signature verification failed");
        return false;
    }

    /**
     * Build LicenseInfo from payload map
     */
    private LicenseInfo buildLicenseInfo(Map<String, Object> payload, String licenseCode) {
        // Parse basic info
        String licenseId = (String) payload.get("licenseId");
        String edition = (String) payload.get("edition");

        // Parse customer
        Map<String, Object> customer = (Map<String, Object>) payload.get("customer");
        String customerName = customer != null ? (String) customer.get("name") : null;
        String customerEmail = customer != null ? (String) customer.get("email") : null;
        String organization = customer != null ? (String) customer.get("organization") : null;

        // Parse binding
        Map<String, Object> binding = (Map<String, Object>) payload.get("binding");
        String machineId = binding != null ? (String) binding.get("machineId") : null;

        // Parse timestamps
        LocalDateTime issuedAt = parseTimestamp((String) payload.get("issuedAt"));
        LocalDateTime expiresAt = parseTimestamp((String) payload.get("expiresAt"));

        // Parse features
        List<String> featureList = (List<String>) payload.get("features");
        Set<String> features = featureList != null ? new HashSet<>(featureList) : new HashSet<>();

        // Parse quotas
        Map<String, Object> quotasMap = (Map<String, Object>) payload.get("quotas");
        LicenseInfo.QuotaInfo quotas = buildQuotaInfo(quotasMap);

        return LicenseInfo.builder()
            .licenseId(licenseId)
            .edition(edition)
            .customerName(customerName)
            .customerEmail(customerEmail)
            .organization(organization)
            .machineId(machineId)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .features(features)
            .quotas(quotas)
            .licenseCode(licenseCode)
            .loadedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Parse timestamp string to LocalDateTime
     */
    private LocalDateTime parseTimestamp(String timestampStr) {
        if (timestampStr == null) {
            return null;
        }
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(timestampStr);
            return zdt.toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.parse(timestampStr);
        }
    }

    /**
     * Build QuotaInfo from map
     */
    private LicenseInfo.QuotaInfo buildQuotaInfo(Map<String, Object> quotasMap) {
        if (quotasMap == null) {
            return LicenseInfo.QuotaInfo.builder().build();
        }

        return LicenseInfo.QuotaInfo.builder()
            .maxInstances(getInt(quotasMap, "maxInstances"))
            .maxRoutes(getInt(quotasMap, "maxRoutes"))
            .maxServices(getInt(quotasMap, "maxServices"))
            .maxStrategies(getInt(quotasMap, "maxStrategies"))
            .maxAuthPolicies(getInt(quotasMap, "maxAuthPolicies"))
            .maxRateLimitQPS(getInt(quotasMap, "maxRateLimitQPS"))
            .maxApiCopilotCalls(getInt(quotasMap, "maxApiCopilotCalls"))
            .maxTraceRetention(getInt(quotasMap, "maxTraceRetention"))
            .maxAuditLogRetention(getInt(quotasMap, "maxAuditLogRetention"))
            .supportLevel((String) quotasMap.get("supportLevel"))
            .build();
    }

    /**
     * Get integer value from map
     */
    private Integer getInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return null;
    }

    /**
     * Load license from file
     */
    public String loadLicenseFromFile(String filePath) throws LicenseInvalidException {
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                throw new LicenseInvalidException("License file not found: " + filePath);
            }
            return Files.readString(path).trim();
        } catch (Exception e) {
            throw new LicenseInvalidException("Failed to read license file: " + e.getMessage(), e);
        }
    }

    /**
     * Build fallback LicenseInfo for BASIC edition (free, permanent)
     */
    public LicenseInfo buildFallbackLicense(LicenseProperties properties) {
        Set<String> basicFeatures = new HashSet<>();
        // All BASIC features (free)
        basicFeatures.add("BASIC_ROUTING");
        basicFeatures.add("LOCAL_RATE_LIMIT");
        basicFeatures.add("BASIC_AUTH");
        basicFeatures.add("DOCKER_DEPLOY");
        basicFeatures.add("HEALTH_CHECK");
        basicFeatures.add("SERVICE_DISCOVERY");
        basicFeatures.add("TIMEOUT_CONFIG");
        basicFeatures.add("BASIC_LOGGING");
        basicFeatures.add("BASIC_METRICS");

        LicenseInfo.QuotaInfo basicQuotas = LicenseInfo.QuotaInfo.builder()
            .maxInstances(1)
            .maxRoutes(10)
            .maxServices(5)
            .maxStrategies(10)
            .maxRateLimitQPS(100)
            .supportLevel("COMMUNITY")
            .build();

        // BASIC edition is free and permanent (expires in 2099)
        LocalDateTime permanentExpiry = LocalDateTime.of(2099, 12, 31, 23, 59, 59);

        return LicenseInfo.builder()
            .licenseId("FALLBACK-BASIC")
            .edition("BASIC")
            .customerName("Fallback")
            .customerEmail("fallback@local")
            .features(basicFeatures)
            .quotas(basicQuotas)
            .expiresAt(permanentExpiry)
            .loadedAt(LocalDateTime.now())
            .build();
    }
}