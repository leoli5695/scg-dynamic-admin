package com.leoli.license.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leoli.license.config.LicenseServerProperties;
import com.leoli.license.dto.*;
import com.leoli.license.exception.LicenseException;
import com.leoli.license.model.*;
import com.leoli.license.repository.LicenseRepository;
import com.leoli.license.util.LicenseCodeDecoder;
import com.leoli.license.util.LicenseCodeEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * License Service - Core Business Logic
 * <p>
 * Handles License creation, management, and validation.
 * <p>
 * Follows the same pattern as gateway-admin's RouteService:
 * - Entity <-> Definition conversion
 * - Database persistence
 * - JSON metadata storage
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseService {

    private final LicenseRepository licenseRepository;
    private final RsaSignatureService rsaSignatureService;
    private final LicenseServerProperties properties;

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /**
     * Create a new License
     *
     * @param request Create request DTO
     * @return License entity with generated code
     */
    @Transactional
    public LicenseEntity createLicense(LicenseCreateRequest request) {
        log.info("Creating license for customer: {}, edition: {}", 
            request.getCustomerEmail(), request.getEdition());

        // Validate edition
        LicenseEdition edition = LicenseEdition.fromCode(request.getEdition());
        if (edition == LicenseEdition.BASIC && request.getValidityDays() != null) {
            throw new LicenseException("BASIC edition is permanent, cannot set validity days");
        }

        // Generate UUID
        String licenseId = UUID.randomUUID().toString();

        // Build LicenseDefinition
        LicenseDefinition definition = buildDefinition(licenseId, edition, request);

        // Generate signature
        String signature = generateSignature(definition);

        // Get public key
        String publicKey = getPublicKey();

        // Encode license code
        String licenseCode = LicenseCodeEncoder.encode(definition, signature, publicKey);

        // Build entity
        LicenseEntity entity = buildEntity(definition, licenseCode, signature, publicKey, request);

        // Save to database
        entity = licenseRepository.save(entity);

        log.info("License created successfully: {}", licenseId);
        return entity;
    }

    /**
     * Build LicenseDefinition from request
     */
    private LicenseDefinition buildDefinition(String licenseId, LicenseEdition edition, 
            LicenseCreateRequest request) {
        // Determine features
        List<LicenseFeature> features = determineFeatures(edition, request.getCustomFeatures());

        // Determine quotas
        LicenseQuota quotas = determineQuotas(edition, request.getCustomQuotas());

        // Determine expiration
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expiresAt = determineExpiration(edition, request.getValidityDays());

        // Build customer info
        LicenseDefinition.CustomerInfo customer = LicenseDefinition.CustomerInfo.builder()
            .name(request.getCustomerName())
            .email(request.getCustomerEmail())
            .organization(request.getOrganization())
            .build();

        // Build binding info
        LicenseDefinition.BindingInfo binding = LicenseDefinition.BindingInfo.builder()
            .machineId(request.getMachineId())
            .build();

        // Build metadata
        LicenseDefinition.MetadataInfo metadata = LicenseDefinition.MetadataInfo.builder()
            .version("1.0")
            .notes(request.getNotes())
            .build();

        return LicenseDefinition.builder()
            .licenseId(licenseId)
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
     * Determine features based on edition and custom settings
     */
    private List<LicenseFeature> determineFeatures(LicenseEdition edition, 
            List<String> customFeatures) {
        if (customFeatures != null && !customFeatures.isEmpty()) {
            return customFeatures.stream()
                .map(LicenseFeature::valueOf)
                .filter(f -> f.isAvailableIn(edition.getCode()))
                .collect(Collectors.toList());
        }

        // Use edition defaults
        return Arrays.stream(LicenseFeature.values())
            .filter(f -> f.isAvailableIn(edition.getCode()))
            .collect(Collectors.toList());
    }

    /**
     * Determine quotas based on edition and custom settings
     */
    private LicenseQuota determineQuotas(LicenseEdition edition, 
            LicenseCreateRequest.LicenseQuotaRequest customQuotas) {
        LicenseQuota defaultQuotas = LicenseQuota.defaultsForEdition(edition);

        if (customQuotas == null) {
            return defaultQuotas;
        }

        // Override with custom values
        return LicenseQuota.builder()
            .maxInstances(customQuotas.getMaxInstances() != null 
                ? customQuotas.getMaxInstances() : defaultQuotas.getMaxInstances())
            .maxRoutes(customQuotas.getMaxRoutes() != null 
                ? customQuotas.getMaxRoutes() : defaultQuotas.getMaxRoutes())
            .maxServices(customQuotas.getMaxServices() != null 
                ? customQuotas.getMaxServices() : defaultQuotas.getMaxServices())
            .maxStrategies(customQuotas.getMaxStrategies() != null 
                ? customQuotas.getMaxStrategies() : defaultQuotas.getMaxStrategies())
            .maxAuthPolicies(customQuotas.getMaxAuthPolicies() != null 
                ? customQuotas.getMaxAuthPolicies() : defaultQuotas.getMaxAuthPolicies())
            .maxRateLimitQPS(customQuotas.getMaxRateLimitQPS() != null 
                ? customQuotas.getMaxRateLimitQPS() : defaultQuotas.getMaxRateLimitQPS())
            .maxApiCopilotCalls(customQuotas.getMaxApiCopilotCalls() != null 
                ? customQuotas.getMaxApiCopilotCalls() : defaultQuotas.getMaxApiCopilotCalls())
            .maxTraceRetention(customQuotas.getMaxTraceRetention() != null 
                ? customQuotas.getMaxTraceRetention() : defaultQuotas.getMaxTraceRetention())
            .maxAuditLogRetention(customQuotas.getMaxAuditLogRetention() != null 
                ? customQuotas.getMaxAuditLogRetention() : defaultQuotas.getMaxAuditLogRetention())
            .supportLevel(customQuotas.getSupportLevel() != null 
                ? customQuotas.getSupportLevel() : defaultQuotas.getSupportLevel())
            .build();
    }

    /**
     * Determine expiration date
     */
    private LocalDateTime determineExpiration(LicenseEdition edition, Integer validityDays) {
        if (validityDays != null) {
            return LocalDateTime.now().plusDays(validityDays);
        }
        // Use edition default (null = permanent)
        return edition.getDefaultValidityDays() != null 
            ? LocalDateTime.now().plusDays(edition.getDefaultValidityDays())
            : null;
    }

    /**
     * Generate RSA signature for license
     */
    private String generateSignature(LicenseDefinition definition) {
        try {
            String privateKey = properties.getRsa().getPrivateKey();
            if (privateKey == null || privateKey.isEmpty()) {
                throw new LicenseException("RSA private key not configured");
            }

            byte[] payloadBytes = LicenseCodeEncoder.getPayloadBytes(definition);
            return rsaSignatureService.sign(payloadBytes, privateKey);
        } catch (Exception e) {
            log.error("Failed to generate signature", e);
            throw new LicenseException("Failed to generate license signature", e);
        }
    }

    /**
     * Get configured public key
     */
    private String getPublicKey() {
        String publicKey = properties.getRsa().getPublicKey();
        if (publicKey == null || publicKey.isEmpty()) {
            throw new LicenseException("RSA public key not configured");
        }
        return publicKey;
    }

    /**
     * Build LicenseEntity from definition
     */
    private LicenseEntity buildEntity(LicenseDefinition definition, String licenseCode,
            String signature, String publicKey, LicenseCreateRequest request) {
        try {
            LicenseEntity entity = new LicenseEntity();
            entity.setLicenseId(definition.getLicenseId());
            entity.setLicenseCode(licenseCode);
            entity.setEdition(definition.getEdition().getCode());
            entity.setCustomerName(definition.getCustomer().getName());
            entity.setCustomerEmail(definition.getCustomer().getEmail());
            entity.setOrganization(definition.getCustomer().getOrganization());
            entity.setMachineId(definition.getBinding() != null 
                ? definition.getBinding().getMachineId() : null);
            entity.setIssuedAt(definition.getIssuedAt());
            entity.setExpiresAt(definition.getExpiresAt());
            entity.setFeatures(objectMapper.writeValueAsString(
                definition.getFeatures().stream().map(LicenseFeature::name).toList()));
            entity.setQuotas(objectMapper.writeValueAsString(definition.getQuotas()));
            entity.setSignature(signature);
            entity.setPublicKey(publicKey);
            entity.setStatus("ACTIVE");
            entity.setNotes(request.getNotes());
            return entity;
        } catch (Exception e) {
            log.error("Failed to build entity", e);
            throw new LicenseException("Failed to build license entity", e);
        }
    }

    /**
     * Get all licenses (paginated)
     */
    public Page<LicenseResponse> getLicenses(int page, int size, String status, String edition) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<LicenseEntity> entities;
        if (status != null && edition != null) {
            entities = licenseRepository.findByStatusAndEdition(status, edition, pageable);
        } else if (status != null) {
            entities = licenseRepository.findByStatus(status, pageable);
        } else if (edition != null) {
            entities = licenseRepository.findByEdition(edition, pageable);
        } else {
            entities = licenseRepository.findAll(pageable);
        }

        return entities.map(this::toResponse);
    }

    /**
     * Get license by ID
     */
    public LicenseResponse getLicense(String licenseId) {
        LicenseEntity entity = licenseRepository.findById(licenseId)
            .orElseThrow(() -> new LicenseException("License not found: " + licenseId));
        return toResponse(entity);
    }

    /**
     * Get license code by ID
     */
    public String getLicenseCode(String licenseId) {
        LicenseEntity entity = licenseRepository.findById(licenseId)
            .orElseThrow(() -> new LicenseException("License not found: " + licenseId));
        return entity.getLicenseCode();
    }

    /**
     * Validate license code
     */
    public LicenseValidationResult validateLicense(String licenseCode) {
        return LicenseCodeDecoder.validate(licenseCode, rsaSignatureService);
    }

    /**
     * Revoke license
     */
    @Transactional
    public LicenseResponse revokeLicense(String licenseId) {
        LicenseEntity entity = licenseRepository.findById(licenseId)
            .orElseThrow(() -> new LicenseException("License not found: " + licenseId));

        entity.markRevoked();
        entity = licenseRepository.save(entity);

        log.info("License revoked: {}", licenseId);
        return toResponse(entity);
    }

    /**
     * Renew license (extend expiration)
     */
    @Transactional
    public LicenseResponse renewLicense(String licenseId, Integer extensionDays) {
        LicenseEntity entity = licenseRepository.findById(licenseId)
            .orElseThrow(() -> new LicenseException("License not found: " + licenseId));

        if ("REVOKED".equals(entity.getStatus())) {
            throw new LicenseException("Cannot renew revoked license");
        }

        LocalDateTime expiresAt = entity.getExpiresAt();
        if (expiresAt == null) {
            throw new LicenseException("Cannot renew permanent license");
        }

        entity.setExpiresAt(expiresAt.plusDays(extensionDays));
        entity.setStatus("ACTIVE"); // Reset status if was expired
        entity = licenseRepository.save(entity);

        log.info("License renewed: {}, extended by {} days", licenseId, extensionDays);
        return toResponse(entity);
    }

    /**
     * Convert entity to response DTO
     */
    private LicenseResponse toResponse(LicenseEntity entity) {
        try {
            List<String> features = objectMapper.readValue(entity.getFeatures(), List.class);
            Map<String, Object> quotasMap = objectMapper.readValue(entity.getQuotas(), Map.class);

            LicenseResponse.LicenseQuotaResponse quotas = LicenseResponse.LicenseQuotaResponse.builder()
                .maxInstances((Integer) quotasMap.get("maxInstances"))
                .maxRoutes((Integer) quotasMap.get("maxRoutes"))
                .maxServices((Integer) quotasMap.get("maxServices"))
                .maxStrategies((Integer) quotasMap.get("maxStrategies"))
                .maxAuthPolicies((Integer) quotasMap.get("maxAuthPolicies"))
                .maxRateLimitQPS((Integer) quotasMap.get("maxRateLimitQPS"))
                .maxApiCopilotCalls((Integer) quotasMap.get("maxApiCopilotCalls"))
                .maxTraceRetention((Integer) quotasMap.get("maxTraceRetention"))
                .maxAuditLogRetention((Integer) quotasMap.get("maxAuditLogRetention"))
                .supportLevel((String) quotasMap.get("supportLevel"))
                .build();

            return LicenseResponse.builder()
                .licenseId(entity.getLicenseId())
                .licenseCode(entity.getLicenseCode())
                .edition(entity.getEdition())
                .customerName(entity.getCustomerName())
                .customerEmail(entity.getCustomerEmail())
                .organization(entity.getOrganization())
                .machineId(entity.getMachineId())
                .issuedAt(entity.getIssuedAt())
                .expiresAt(entity.getExpiresAt())
                .features(features)
                .quotas(quotas)
                .status(entity.getStatus())
                .daysRemaining(entity.getDaysRemaining())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
        } catch (Exception e) {
            log.error("Failed to convert entity to response", e);
            throw new LicenseException("Failed to process license data", e);
        }
    }

    /**
     * Get feature definitions
     */
    public Map<String, Map<String, String>> getFeatureDefinitions() {
        Map<String, Map<String, String>> features = new LinkedHashMap<>();
        for (LicenseFeature feature : LicenseFeature.values()) {
            Map<String, String> info = new LinkedHashMap<>();
            info.put("displayName", feature.getDisplayName());
            info.put("requiredEdition", feature.getRequiredEdition());
            info.put("description", feature.getDescription());
            features.put(feature.name(), info);
        }
        return features;
    }

    /**
     * Get quota comparison for editions
     */
    public Map<String, LicenseQuota> getQuotaComparison() {
        Map<String, LicenseQuota> comparison = new LinkedHashMap<>();
        comparison.put("BASIC", LicenseQuota.basicDefaults());
        comparison.put("ADVANCED", LicenseQuota.advancedDefaults());
        comparison.put("ENTERPRISE", LicenseQuota.enterpriseDefaults());
        return comparison;
    }

    /**
     * Get license statistics
     */
    public Map<String, Long> getStatistics() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("total", licenseRepository.count());
        stats.put("active", licenseRepository.countActive());
        stats.put("basic", licenseRepository.countByEdition("BASIC"));
        stats.put("advanced", licenseRepository.countByEdition("ADVANCED"));
        stats.put("enterprise", licenseRepository.countByEdition("ENTERPRISE"));
        stats.put("expired", licenseRepository.countByStatus("EXPIRED"));
        stats.put("revoked", licenseRepository.countByStatus("REVOKED"));
        return stats;
    }

    /**
     * Update expired licenses status
     */
    @Transactional
    public int updateExpiredStatus() {
        List<LicenseEntity> expired = licenseRepository.findExpired(LocalDateTime.now());
        for (LicenseEntity entity : expired) {
            entity.markExpired();
        }
        licenseRepository.saveAll(expired);
        log.info("Updated {} expired licenses", expired.size());
        return expired.size();
    }
}