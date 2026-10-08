package com.leoli.license.client.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leoli.license.client.model.LicenseInfo;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mints RSA-signed license codes in the wire format {@link LicenseValidator#validate} expects.
 * The signature is produced over the same Jackson serialization the validator re-computes,
 * so these codes exercise the real crypto path rather than a stub.
 */
final class LicenseFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final KeyPair KEY_PAIR = generateKeyPair();

    private LicenseFixtures() {
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Sign {@code payload} and wrap it into a Base64 license code. */
    static String code(Map<String, Object> payload) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(KEY_PAIR.getPrivate());
            signer.update(MAPPER.writeValueAsBytes(payload));

            Map<String, Object> license = new LinkedHashMap<>();
            license.put("payload", payload);
            license.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
            license.put("publicKey", Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));

            return Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(license));
        } catch (GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to mint license code", e);
        }
    }

    /** Base64-encode an arbitrary license map, for codes that must fail verification. */
    static String encodeUnsigned(Map<String, Object> license) {
        try {
            return Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(license));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
    }

    /** Payload skeleton matching the keys {@code LicenseValidator.buildLicenseInfo} reads. */
    static Map<String, Object> payload(String licenseId, String edition, LocalDateTime expiresAt) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "Test Customer");
        customer.put("email", "test@example.com");
        customer.put("organization", "Test Org");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("licenseId", licenseId);
        payload.put("edition", edition);
        payload.put("customer", customer);
        payload.put("issuedAt", LocalDateTime.now().minusDays(30).toString());
        if (expiresAt != null) {
            payload.put("expiresAt", expiresAt.toString());
        }
        return payload;
    }

    static Map<String, Object> quotas(Integer maxInstances, Integer maxRoutes, Integer maxServices,
                                      Integer maxStrategies, Integer maxRateLimitQPS, String supportLevel) {
        Map<String, Object> quotas = new LinkedHashMap<>();
        quotas.put("maxInstances", maxInstances);
        quotas.put("maxRoutes", maxRoutes);
        quotas.put("maxServices", maxServices);
        quotas.put("maxStrategies", maxStrategies);
        quotas.put("maxRateLimitQPS", maxRateLimitQPS);
        quotas.put("supportLevel", supportLevel);
        return quotas;
    }

    static List<String> allFeatureNames() {
        List<String> names = new ArrayList<>();
        for (LicenseInfo.Feature feature : LicenseInfo.Feature.values()) {
            names.add(feature.name());
        }
        return names;
    }

    static String basicCode() {
        Map<String, Object> payload = payload("LIC-BASIC", "BASIC", LocalDateTime.now().plusDays(365));
        payload.put("features", List.of("BASIC_ROUTING", "LOCAL_RATE_LIMIT"));
        payload.put("quotas", quotas(1, 10, 5, 10, 100, "COMMUNITY"));
        return code(payload);
    }

    static String advancedCode() {
        Map<String, Object> payload = payload("LIC-ADVANCED", "ADVANCED", LocalDateTime.now().plusDays(365));
        payload.put("features", List.of("WEIGHTED_ROUTING", "DISTRIBUTED_RATE_LIMIT", "CIRCUIT_BREAKER"));
        payload.put("quotas", quotas(5, 500, 100, 50, 1000, "STANDARD"));
        return code(payload);
    }

    static String enterpriseCode() {
        Map<String, Object> payload = payload("LIC-ENTERPRISE", "ENTERPRISE", LocalDateTime.now().plusDays(3650));
        payload.put("features", allFeatureNames());
        payload.put("quotas", quotas(-1, -1, -1, -1, -1, "PRIORITY"));
        return code(payload);
    }

    static String expiredCode() {
        Map<String, Object> payload = payload("LIC-EXPIRED", "ADVANCED", LocalDateTime.now().minusDays(1));
        payload.put("features", List.of("WEIGHTED_ROUTING"));
        payload.put("quotas", quotas(5, 500, 100, 50, 1000, "STANDARD"));
        return code(payload);
    }

    /** No {@code expiresAt} key, which the client treats as a permanent license. */
    static String permanentCode() {
        Map<String, Object> payload = payload("LIC-PERMANENT", "ENTERPRISE", null);
        payload.put("features", allFeatureNames());
        payload.put("quotas", quotas(-1, -1, -1, -1, -1, "PRIORITY"));
        return code(payload);
    }

    static String codeBoundTo(String machineId) {
        Map<String, Object> payload = payload("LIC-BOUND", "ADVANCED", LocalDateTime.now().plusDays(365));
        payload.put("binding", Map.of("machineId", machineId));
        payload.put("features", List.of("WEIGHTED_ROUTING"));
        payload.put("quotas", quotas(5, 500, 100, 50, 1000, "STANDARD"));
        return code(payload);
    }

    /** A license that expires shortly after being loaded, for expiry-on-access paths. */
    static String codeExpiringIn(java.time.Duration ttl) {
        Map<String, Object> payload = payload("LIC-SHORT-LIVED", "ADVANCED", LocalDateTime.now().plus(ttl));
        payload.put("features", List.of("WEIGHTED_ROUTING"));
        payload.put("quotas", quotas(5, 500, 100, 50, 1000, "STANDARD"));
        return code(payload);
    }
}
