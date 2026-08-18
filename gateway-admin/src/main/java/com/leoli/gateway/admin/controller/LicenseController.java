package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.license.LicenseFeatureHelper;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.license.client.exception.LicenseExpiredException;
import com.leoli.license.client.exception.LicenseInvalidException;
import com.leoli.license.client.model.LicenseInfo;
import com.leoli.license.client.validator.LicenseStore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * License Controller for gateway-admin.
 * Provides license status query and import APIs.
 * 
 * Note: License creation/list/delete/revocation is handled by license-server (vendor backend).
 * This controller only manages the license imported into gateway-admin (license-client).
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/license")
@RequiredArgsConstructor
public class LicenseController extends BaseController {

    private final LicenseStore licenseStore;
    private final LicenseFeatureHelper licenseFeatureHelper;
    private final AuditLogService auditLogService;

    /**
     * Get current license status.
     * Returns whether license is loaded and valid.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLicenseStatus() {
        Map<String, Object> status = new HashMap<>();
        
        LicenseInfo license = licenseStore.getLicense();
        
        if (license == null) {
            status.put("loaded", false);
            status.put("valid", false);
            status.put("edition", "UNLICENSED");
            status.put("message", "No license loaded");
            return ok(status);
        }
        
        status.put("loaded", true);
        status.put("valid", !license.isExpired());
        status.put("edition", license.getEdition());
        status.put("licenseId", license.getLicenseId());
        status.put("customerName", license.getCustomerName());
        status.put("organization", license.getOrganization());
        status.put("issuedAt", license.getIssuedAt());
        status.put("expiresAt", license.getExpiresAt());
        status.put("expired", license.isExpired());
        status.put("daysRemaining", license.getDaysRemaining());
        
        if (license.isExpired()) {
            status.put("message", "License expired");
        } else {
            status.put("message", "License valid");
        }
        
        // Add feature summary
        status.put("features", license.getFeatures());
        status.put("quotas", license.getQuotas());
        
        return ok(status);
    }

    /**
     * Get license list.
     * Note: gateway-admin only manages one imported license at a time.
     * For full license management (create/delete/revoke), use license-server.
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLicenseList() {
        LicenseInfo license = licenseStore.getLicense();

        if (license == null) {
            return ok(Collections.emptyList(), "No license loaded");
        }

        // Return current imported license as a single-item list
        Map<String, Object> item = new HashMap<>();
        item.put("licenseId", license.getLicenseId());
        item.put("edition", license.getEdition());
        item.put("customerName", license.getCustomerName());
        item.put("customerEmail", license.getCustomerEmail());
        item.put("customerCompany", license.getOrganization());
        item.put("issuedAt", license.getIssuedAt() != null ? license.getIssuedAt().toString() : null);
        item.put("expiresAt", license.getExpiresAt() != null ? license.getExpiresAt().toString() : null);
        item.put("expired", license.isExpired());
        item.put("status", license.isExpired() ? "EXPIRED" : "ACTIVE");
        item.put("features", license.getFeatures() != null ? license.getFeatures() : Collections.emptyList());
        item.put("createdAt", license.getLoadedAt() != null ? license.getLoadedAt().toString() : null);
        item.put("updatedAt", license.getLoadedAt() != null ? license.getLoadedAt().toString() : null);

        return ok(Collections.singletonList(item));
    }

    /**
     * Import/activate a license code.
     * This validates and loads the license into license-client.
     */
    @PostMapping("/import")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importLicense(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {
        
        String licenseCode = request.get("licenseCode");
        if (licenseCode == null || licenseCode.isEmpty()) {
            return badRequest("License code is required");
        }
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Validate and load the license
            licenseStore.updateLicense(licenseCode);
            LicenseInfo license = licenseStore.getLicense();
            
            // Audit log
            auditLogService.recordAuditLog(
                null,
                getOperator(),
                "IMPORT",
                "LICENSE",
                license.getLicenseId(),
                "License imported: " + license.getEdition() + " for " + license.getCustomerName(),
                getIpAddress(httpRequest)
            );
            
            result.put("success", true);
            result.put("licenseId", license.getLicenseId());
            result.put("edition", license.getEdition());
            result.put("customerName", license.getCustomerName());
            result.put("message", "License imported successfully");
            
            log.info("License imported: {} for {} by {}", 
                license.getEdition(), license.getCustomerName(), getOperator());
            
            return ok(result, "License imported successfully");
            
        } catch (LicenseInvalidException e) {
            log.warn("License import failed - invalid: {}", e.getReason());
            return badRequest("Invalid license: " + e.getReason());
            
        } catch (LicenseExpiredException e) {
            log.warn("License import failed - expired: {}", e.getMessage());
            return badRequest("License expired");
            
        } catch (Exception e) {
            log.error("License import failed", e);
            return fail("Failed to import license: " + e.getMessage());
        }
    }

    /**
     * Get detailed license information.
     */
    @GetMapping("/info")
    public ResponseEntity<ApiResponse<LicenseInfo>> getLicenseInfo() {
        LicenseInfo license = licenseStore.getLicense();
        
        if (license == null) {
            return ok(null, "No license loaded");
        }
        
        return ok(license);
    }

    /**
     * Check if a specific feature is enabled.
     */
    @GetMapping("/feature/{featureName}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkFeature(
            @PathVariable String featureName) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            LicenseInfo.Feature feature = LicenseInfo.Feature.valueOf(featureName.toUpperCase());
            boolean enabled = licenseFeatureHelper.hasFeature(feature);
            result.put("feature", featureName);
            result.put("enabled", enabled);
            result.put("edition", licenseFeatureHelper.getEditionName());
            return ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid feature name: {}", featureName);
            return badRequest("Invalid feature name: " + featureName);
        }
    }

    /**
     * Get all available features and their status.
     */
    @GetMapping("/features")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllFeatures() {
        Map<String, Object> result = new HashMap<>();
        
        LicenseInfo license = licenseStore.getLicense();
        result.put("edition", licenseFeatureHelper.getEditionName());
        result.put("loaded", license != null);
        
        if (license != null) {
            result.put("features", license.getFeatures());
            result.put("quotas", license.getQuotas());
        }
        
        // Check each feature status
        Map<String, Boolean> featureStatus = new HashMap<>();
        for (LicenseInfo.Feature feature : LicenseInfo.Feature.values()) {
            featureStatus.put(feature.name(), licenseFeatureHelper.hasFeature(feature));
        }
        result.put("featureStatus", featureStatus);
        
        return ok(result);
    }
}