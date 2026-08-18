package com.leoli.license.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leoli.license.model.LicenseEntity;
import com.leoli.license.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Audit Log Service
 * <p>
 * Records license operations for compliance and troubleshooting.
 * Follows the same pattern as gateway-admin's AuditLogService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final LicenseRepository licenseRepository;
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /**
     * Record audit log for license operation
     *
     * @param licenseId      License ID
     * @param operationType  CREATE / REVOKE / RENEW / VALIDATE / GENERATE_KEYS
     * @param operator       Operator (user or system)
     * @param oldValue       Old value (JSON, for update operations)
     * @param newValue       New value (JSON)
     * @param ipAddress      Client IP address
     */
    @Transactional
    public void recordAuditLog(String licenseId, String operationType, String operator,
            Object oldValue, Object newValue, String ipAddress) {
        log.info("License audit: license={}, op={}, operator={}, ip={}",
            licenseId, operationType, operator, ipAddress);

        // For now, we just log the operation
        // In a full implementation, this would persist to an audit_log table
        // with: licenseId, operationType, operator, oldValueJson, newValueJson, ipAddress, timestamp

        try {
            String oldValueJson = oldValue != null ? objectMapper.writeValueAsString(oldValue) : null;
            String newValueJson = newValue != null ? objectMapper.writeValueAsString(newValue) : null;

            log.debug("Audit detail: oldValue={}, newValue={}", oldValueJson, newValueJson);
        } catch (Exception e) {
            log.error("Failed to serialize audit log values", e);
        }
    }

    /**
     * Record audit for license creation
     */
    public void recordCreate(String licenseId, String operator, LicenseEntity entity, String ipAddress) {
        recordAuditLog(licenseId, "CREATE", operator, null, entity, ipAddress);
    }

    /**
     * Record audit for license revocation
     */
    public void recordRevoke(String licenseId, String operator, String reason, String ipAddress) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("reason", reason);
        detail.put("timestamp", LocalDateTime.now());
        recordAuditLog(licenseId, "REVOKE", operator, "ACTIVE", "REVOKED", ipAddress);
    }

    /**
     * Record audit for license renewal
     */
    public void recordRenew(String licenseId, String operator, int extensionDays, 
            LocalDateTime oldExpires, LocalDateTime newExpires, String ipAddress) {
        Map<String, Object> oldValue = new HashMap<>();
        oldValue.put("expiresAt", oldExpires);

        Map<String, Object> newValue = new HashMap<>();
        newValue.put("expiresAt", newExpires);
        newValue.put("extensionDays", extensionDays);

        recordAuditLog(licenseId, "RENEW", operator, oldValue, newValue, ipAddress);
    }

    /**
     * Record audit for license validation
     */
    public void recordValidate(String licenseId, String operator, boolean valid, 
            String errorMessage, String ipAddress) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("valid", valid);
        detail.put("errorMessage", errorMessage);
        recordAuditLog(licenseId, "VALIDATE", operator, null, detail, ipAddress);
    }

    /**
     * Record audit for RSA key generation
     */
    public void recordKeyGeneration(String operator, String ipAddress) {
        log.info("RSA key generation audit: operator={}, ip={}", operator, ipAddress);
        recordAuditLog("RSA_KEY", "GENERATE_KEYS", operator, null, null, ipAddress);
    }
}