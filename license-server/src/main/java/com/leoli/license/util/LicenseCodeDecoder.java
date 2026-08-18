package com.leoli.license.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leoli.license.dto.LicenseValidationResult;
import com.leoli.license.model.*;
import com.leoli.license.service.RsaSignatureService;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.Base64;

/**
 * License Code Decoder
 * <p>
 * Decodes Base64 License Code to LicenseDefinition.
 * Validates signature and expiration.
 * <p>
 * Reference for RSA signature verification:
 * - JwtAuthProcessor.getPublicKey (X509EncodedKeySpec parsing)
 * - HmacSignatureAuthProcessor.constantTimeEquals (timing attack prevention)
 */
@Slf4j
public class LicenseCodeDecoder {

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /**
     * Decode License Code to LicenseDefinition
     *
     * @param licenseCode   Base64 encoded License Code
     * @return LicenseDefinition (if valid)
     * @throws Exception if decoding or validation fails
     */
    public static LicenseDefinition decode(String licenseCode) throws Exception {
        // Base64 decode
        String json = new String(Base64.getDecoder().decode(licenseCode), StandardCharsets.UTF_8);

        // Parse JSON
        Map<String, Object> licenseCodeMap = objectMapper.readValue(json, Map.class);

        // Extract components
        Map<String, Object> payload = (Map<String, Object>) licenseCodeMap.get("payload");
        String signature = (String) licenseCodeMap.get("signature");
        String publicKey = (String) licenseCodeMap.get("publicKey");

        if (payload == null || signature == null || publicKey == null) {
            throw new IllegalArgumentException("Invalid license code format");
        }

        // Build LicenseDefinition from payload
        return buildDefinition(payload);
    }

    /**
     * Validate License Code signature
     *
     * @param licenseCode       Base64 encoded License Code
     * @param signatureService  RSA signature service
     * @return LicenseValidationResult
     */
    public static LicenseValidationResult validate(String licenseCode, RsaSignatureService signatureService) {
        try {
            // Base64 decode
            String json = new String(Base64.getDecoder().decode(licenseCode), StandardCharsets.UTF_8);
            Map<String, Object> licenseCodeMap = objectMapper.readValue(json, Map.class);

            // Extract components
            Map<String, Object> payload = (Map<String, Object>) licenseCodeMap.get("payload");
            String signature = (String) licenseCodeMap.get("signature");
            String publicKey = (String) licenseCodeMap.get("publicKey");

            if (payload == null || signature == null || publicKey == null) {
                return LicenseValidationResult.malformed();
            }

            // Verify signature
            byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
            boolean signatureValid = signatureService.verify(payloadBytes, signature, publicKey);

            if (!signatureValid) {
                return LicenseValidationResult.invalidSignature();
            }

            // Parse license info
            String licenseId = (String) payload.get("licenseId");
            String edition = (String) payload.get("edition");
            Map<String, Object> customer = (Map<String, Object>) payload.get("customer");
            String customerName = customer != null ? (String) customer.get("name") : null;
            String customerEmail = customer != null ? (String) customer.get("email") : null;

            // Features
            List<String> features = (List<String>) payload.get("features");

            // Quotas
            Map<String, Object> quotas = (Map<String, Object>) payload.get("quotas");
            LicenseValidationResult.LicenseQuotaInfo quotaInfo = buildQuotaInfo(quotas);

            // Expiration
            String expiresAtStr = (String) payload.get("expiresAt");
            LocalDateTime expiresAt = parseTimestamp(expiresAtStr);
            boolean permanent = expiresAt == null;

            // Check expiration
            if (!permanent && LocalDateTime.now().isAfter(expiresAt)) {
                return LicenseValidationResult.expired(expiresAtStr);
            }

            // Calculate days remaining
            Integer daysRemaining = null;
            if (!permanent && expiresAt != null) {
                daysRemaining = (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), expiresAt);
                if (daysRemaining < 0) daysRemaining = 0;
            }

            return LicenseValidationResult.success(
                licenseId, edition, customerName, customerEmail,
                features, quotaInfo, expiresAtStr, daysRemaining, permanent
            );

        } catch (IllegalArgumentException e) {
            log.warn("License code malformed: {}", e.getMessage());
            return LicenseValidationResult.malformed();
        } catch (Exception e) {
            log.error("License validation failed", e);
            return LicenseValidationResult.malformed();
        }
    }

    /**
     * Build LicenseDefinition from payload map
     */
    private static LicenseDefinition buildDefinition(Map<String, Object> payload) {
        // Parse edition
        String editionCode = (String) payload.get("edition");
        LicenseEdition edition = LicenseEdition.fromCode(editionCode);

        // Parse customer
        Map<String, Object> customerMap = (Map<String, Object>) payload.get("customer");
        LicenseDefinition.CustomerInfo customer = LicenseDefinition.CustomerInfo.builder()
            .name((String) customerMap.get("name"))
            .email((String) customerMap.get("email"))
            .organization((String) customerMap.get("organization"))
            .build();

        // Parse binding
        Map<String, Object> bindingMap = (Map<String, Object>) payload.get("binding");
        LicenseDefinition.BindingInfo binding = LicenseDefinition.BindingInfo.builder()
            .machineId(bindingMap != null ? (String) bindingMap.get("machineId") : null)
            .instanceId(bindingMap != null ? (String) bindingMap.get("instanceId") : null)
            .build();

        // Parse timestamps
        LocalDateTime issuedAt = parseTimestamp((String) payload.get("issuedAt"));
        LocalDateTime expiresAt = parseTimestamp((String) payload.get("expiresAt"));

        // Parse features
        List<String> featureNames = (List<String>) payload.get("features");
        List<LicenseFeature> features = featureNames != null
            ? featureNames.stream().map(LicenseFeature::valueOf).toList()
            : Collections.emptyList();

        // Parse quotas
        Map<String, Object> quotasMap = (Map<String, Object>) payload.get("quotas");
        LicenseQuota quotas = buildQuota(quotasMap);

        // Parse metadata
        Map<String, Object> metadataMap = (Map<String, Object>) payload.get("metadata");
        LicenseDefinition.MetadataInfo metadata = LicenseDefinition.MetadataInfo.builder()
            .version(metadataMap != null ? (String) metadataMap.get("version") : "1.0")
            .notes(metadataMap != null ? (String) metadataMap.get("notes") : null)
            .build();

        return LicenseDefinition.builder()
            .licenseId((String) payload.get("licenseId"))
            .edition(edition)
            .customer(customer)
            .binding(binding)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .features(features)
            .quotas(quotas)
            .metadata(metadata)
            .build();
    }

    /**
     * Parse timestamp string to LocalDateTime
     */
    private static LocalDateTime parseTimestamp(String timestampStr) {
        if (timestampStr == null) {
            return null;
        }
        try {
            // Handle ISO 8601 format with timezone
            ZonedDateTime zdt = ZonedDateTime.parse(timestampStr);
            return zdt.toLocalDateTime();
        } catch (Exception e) {
            // Try LocalDateTime format
            return LocalDateTime.parse(timestampStr);
        }
    }

    /**
     * Build LicenseQuota from map
     */
    private static LicenseQuota buildQuota(Map<String, Object> quotasMap) {
        if (quotasMap == null) {
            return null;
        }

        return LicenseQuota.builder()
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
     * Build LicenseQuotaInfo from map
     */
    private static LicenseValidationResult.LicenseQuotaInfo buildQuotaInfo(Map<String, Object> quotasMap) {
        if (quotasMap == null) {
            return null;
        }

        return LicenseValidationResult.LicenseQuotaInfo.builder()
            .maxInstances(getInt(quotasMap, "maxInstances"))
            .maxRoutes(getInt(quotasMap, "maxRoutes"))
            .maxRateLimitQPS(getInt(quotasMap, "maxRateLimitQPS"))
            .build();
    }

    /**
     * Get integer value from map
     */
    private static Integer getInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    /**
     * Get signature and public key from license code
     */
    public static Map<String, String> extractSignatureComponents(String licenseCode) throws Exception {
        String json = new String(Base64.getDecoder().decode(licenseCode), StandardCharsets.UTF_8);
        Map<String, Object> map = objectMapper.readValue(json, Map.class);

        Map<String, String> components = new HashMap<>();
        components.put("signature", (String) map.get("signature"));
        components.put("publicKey", (String) map.get("publicKey"));
        return components;
    }

    /**
     * Get payload bytes for verification
     */
    public static byte[] getPayloadBytes(String licenseCode) throws Exception {
        String json = new String(Base64.getDecoder().decode(licenseCode), StandardCharsets.UTF_8);
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        Map<String, Object> payload = (Map<String, Object>) map.get("payload");
        return objectMapper.writeValueAsBytes(payload);
    }
}