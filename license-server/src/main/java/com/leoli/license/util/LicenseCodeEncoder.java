package com.leoli.license.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leoli.license.model.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.Base64;

/**
 * License Code Encoder
 * <p>
 * Encodes LicenseDefinition to Base64 License Code.
 * <p>
 * License Code format:
 * 1. Build payload JSON (licenseId, edition, customer, features, quotas, etc.)
 * 2. Sign payload with RSA-SHA256
 * 3. Add signature and public key
 * 4. Base64 encode the entire JSON
 * <p>
 * Output format (similar to JetBrains License):
 * eyJwYXlsb2FkIjp7ImxpY2Vuc2VJZCI6ImZlYmE3Y...
 */
@Slf4j
public class LicenseCodeEncoder {

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /**
     * Encode LicenseDefinition to License Code
     *
     * @param definition    License definition
     * @param signature     RSA signature (Base64)
     * @param publicKey     RSA public key (Base64)
     * @return License Code (Base64 encoded JSON)
     */
    public static String encode(LicenseDefinition definition, String signature, String publicKey) {
        try {
            // Build payload map
            Map<String, Object> payload = buildPayload(definition);

            // Build complete license code structure
            Map<String, Object> licenseCode = new LinkedHashMap<>();
            licenseCode.put("payload", payload);
            licenseCode.put("signature", signature);
            licenseCode.put("publicKey", publicKey);

            // Convert to JSON and Base64 encode
            String json = objectMapper.writeValueAsString(licenseCode);
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to encode license", e);
            throw new RuntimeException("License encoding failed", e);
        }
    }

    /**
     * Build payload map from LicenseDefinition
     */
    private static Map<String, Object> buildPayload(LicenseDefinition definition) {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("licenseId", definition.getLicenseId());
        payload.put("edition", definition.getEdition().getCode());

        // Customer info
        Map<String, Object> customer = new LinkedHashMap<>();
        if (definition.getCustomer() != null) {
            customer.put("name", definition.getCustomer().getName());
            customer.put("email", definition.getCustomer().getEmail());
            customer.put("organization", definition.getCustomer().getOrganization());
        }
        payload.put("customer", customer);

        // Binding info
        Map<String, Object> binding = new LinkedHashMap<>();
        if (definition.getBinding() != null) {
            binding.put("machineId", definition.getBinding().getMachineId());
            binding.put("instanceId", definition.getBinding().getInstanceId());
        }
        payload.put("binding", binding);

        // Timestamps (ISO 8601 format)
        payload.put("issuedAt", formatTimestamp(definition.getIssuedAt()));
        payload.put("expiresAt", definition.getExpiresAt() != null 
            ? formatTimestamp(definition.getExpiresAt()) 
            : null);

        // Features (list of feature names)
        List<String> featureNames = definition.getFeatures() != null
            ? definition.getFeatures().stream().map(LicenseFeature::name).toList()
            : Collections.emptyList();
        payload.put("features", featureNames);

        // Quotas
        payload.put("quotas", buildQuotasMap(definition.getQuotas()));

        // Metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (definition.getMetadata() != null) {
            metadata.put("version", definition.getMetadata().getVersion());
            metadata.put("notes", definition.getMetadata().getNotes());
        } else {
            metadata.put("version", "1.0");
        }
        payload.put("metadata", metadata);

        return payload;
    }

    /**
     * Build quotas map from LicenseQuota
     */
    private static Map<String, Object> buildQuotasMap(LicenseQuota quotas) {
        if (quotas == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> quotasMap = new LinkedHashMap<>();
        quotasMap.put("maxInstances", quotas.getMaxInstances());
        quotasMap.put("maxRoutes", quotas.getMaxRoutes());
        quotasMap.put("maxServices", quotas.getMaxServices());
        quotasMap.put("maxStrategies", quotas.getMaxStrategies());
        quotasMap.put("maxAuthPolicies", quotas.getMaxAuthPolicies());
        quotasMap.put("maxRateLimitQPS", quotas.getMaxRateLimitQPS());
        quotasMap.put("maxApiCopilotCalls", quotas.getMaxApiCopilotCalls());
        quotasMap.put("maxTraceRetention", quotas.getMaxTraceRetention());
        quotasMap.put("maxAuditLogRetention", quotas.getMaxAuditLogRetention());
        quotasMap.put("supportLevel", quotas.getSupportLevel());

        return quotasMap;
    }

    /**
     * Format LocalDateTime to ISO 8601 string
     */
    private static String formatTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(ZoneOffset.UTC).toString();
    }

    /**
     * Get payload bytes for signing
     */
    public static byte[] getPayloadBytes(LicenseDefinition definition) {
        try {
            Map<String, Object> payload = buildPayload(definition);
            String json = objectMapper.writeValueAsString(payload);
            return json.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to serialize payload", e);
            throw new RuntimeException("Payload serialization failed", e);
        }
    }
}