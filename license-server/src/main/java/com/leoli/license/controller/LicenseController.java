package com.leoli.license.controller;

import com.leoli.license.config.LicenseServerProperties;
import com.leoli.license.dto.*;
import com.leoli.license.model.*;
import com.leoli.license.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * License Controller - REST API Endpoints
 * <p>
 * Provides License management and validation APIs.
 * <p>
 * Follows the same pattern as gateway-admin's RouteController:
 * - ResponseEntity with ApiResponse
 * - BaseController utility methods (if needed)
 * - Audit logging for operations
 */
@Slf4j
@RestController
@RequestMapping("/api/licenses")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;
    private final AuditLogService auditLogService;
    private final RsaSignatureService rsaSignatureService;
    private final LicenseServerProperties properties;

    /**
     * Create a new License
     */
    @PostMapping
    public ResponseEntity<ApiResponse<LicenseResponse>> createLicense(
            @Valid @RequestBody LicenseCreateRequest request,
            HttpServletRequest httpRequest) {
        try {
            var entity = licenseService.createLicense(request);
            var response = licenseService.getLicense(entity.getLicenseId());

            auditLogService.recordCreate(
                entity.getLicenseId(),
                getOperator(httpRequest),
                entity,
                getIpAddress(httpRequest)
            );

            return ResponseEntity.ok(ApiResponse.success(response, "License created successfully"));
        } catch (Exception e) {
            log.error("Failed to create license", e);
            return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest(e.getMessage()));
        }
    }

    /**
     * Get all licenses (paginated)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<LicenseResponse>>> getLicenses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String edition) {
        var pageResult = licenseService.getLicenses(page, size, status, edition);

        PageResponse<LicenseResponse> response = new PageResponse<>();
        response.setContent(pageResult.getContent());
        response.setTotalElements(pageResult.getTotalElements());
        response.setTotalPages(pageResult.getTotalPages());
        response.setPage(page);
        response.setSize(size);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get license by ID
     */
    @GetMapping("/{licenseId}")
    public ResponseEntity<ApiResponse<LicenseResponse>> getLicense(
            @PathVariable String licenseId) {
        try {
            var response = licenseService.getLicense(licenseId);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                .body(ApiResponse.notFound("License not found: " + licenseId));
        }
    }

    /**
     * Get license code (for client import)
     */
    @GetMapping("/{licenseId}/code")
    public ResponseEntity<ApiResponse<Map<String, String>>> getLicenseCode(
            @PathVariable String licenseId) {
        try {
            String code = licenseService.getLicenseCode(licenseId);
            Map<String, String> data = Map.of(
                "licenseId", licenseId,
                "licenseCode", code
            );
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                .body(ApiResponse.notFound("License not found: " + licenseId));
        }
    }

    /**
     * Validate license code (public endpoint for client)
     */
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<LicenseValidationResult>> validateLicense(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {
        String licenseCode = request.get("licenseCode");
        if (licenseCode == null || licenseCode.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest("licenseCode is required"));
        }

        var result = licenseService.validateLicense(licenseCode);

        auditLogService.recordValidate(
            result.getLicenseId() != null ? result.getLicenseId() : "UNKNOWN",
            getOperator(httpRequest),
            result.isValid(),
            result.getErrorMessage(),
            getIpAddress(httpRequest)
        );

        if (result.isValid()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.status(403)
                .body(ApiResponse.error(result.getErrorCode(), result.getErrorMessage()));
        }
    }

    /**
     * Revoke license
     */
    @PostMapping("/{licenseId}/revoke")
    public ResponseEntity<ApiResponse<LicenseResponse>> revokeLicense(
            @PathVariable String licenseId,
            @RequestBody(required = false) Map<String, String> request,
            HttpServletRequest httpRequest) {
        try {
            String reason = request != null ? request.get("reason") : "Manual revocation";
            var response = licenseService.revokeLicense(licenseId);

            auditLogService.recordRevoke(
                licenseId,
                getOperator(httpRequest),
                reason,
                getIpAddress(httpRequest)
            );

            return ResponseEntity.ok(ApiResponse.success(response, "License revoked"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest(e.getMessage()));
        }
    }

    /**
     * Renew license (extend expiration)
     */
    @PostMapping("/{licenseId}/renew")
    public ResponseEntity<ApiResponse<LicenseResponse>> renewLicense(
            @PathVariable String licenseId,
            @RequestBody Map<String, Integer> request,
            HttpServletRequest httpRequest) {
        Integer extensionDays = request.get("extensionDays");
        if (extensionDays == null || extensionDays <= 0) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest("extensionDays must be positive"));
        }

        try {
            var oldLicense = licenseService.getLicense(licenseId);
            var oldExpires = oldLicense.getExpiresAt();

            var response = licenseService.renewLicense(licenseId, extensionDays);

            auditLogService.recordRenew(
                licenseId,
                getOperator(httpRequest),
                extensionDays,
                oldExpires,
                response.getExpiresAt(),
                getIpAddress(httpRequest)
            );

            return ResponseEntity.ok(ApiResponse.success(response, 
                "License renewed, extended by " + extensionDays + " days"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest(e.getMessage()));
        }
    }

    /**
     * Get all feature definitions
     */
    @GetMapping("/features")
    public ResponseEntity<ApiResponse<Map<String, Map<String, String>>>> getFeatures() {
        var features = licenseService.getFeatureDefinitions();
        return ResponseEntity.ok(ApiResponse.success(features));
    }

    /**
     * Get quota comparison for all editions
     */
    @GetMapping("/quotas")
    public ResponseEntity<ApiResponse<Map<String, LicenseQuota>>> getQuotas() {
        var quotas = licenseService.getQuotaComparison();
        return ResponseEntity.ok(ApiResponse.success(quotas));
    }

    /**
     * Get license statistics
     */
    @GetMapping("/statistics")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getStatistics() {
        var stats = licenseService.getStatistics();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    /**
     * Generate RSA key pair (admin operation)
     */
    @PostMapping("/generate-keys")
    public ResponseEntity<ApiResponse<Map<String, String>>> generateKeys(
            HttpServletRequest httpRequest) {
        try {
            var keyPair = rsaSignatureService.generateKeyPair();

            auditLogService.recordKeyGeneration(
                getOperator(httpRequest),
                getIpAddress(httpRequest)
            );

            Map<String, String> data = Map.of(
                "privateKey", keyPair.getPrivateKey(),
                "publicKey", keyPair.getPublicKey(),
                "privateKeyPem", keyPair.getPrivateKeyPem(),
                "publicKeyPem", keyPair.getPublicKeyPem()
            );

            log.warn("RSA key pair generated - store private key securely!");
            return ResponseEntity.ok(ApiResponse.success(data, "RSA key pair generated. Store private key securely!"));
        } catch (Exception e) {
            log.error("Failed to generate RSA keys", e);
            return ResponseEntity.internalServerError()
                .body(ApiResponse.internalError("Failed to generate RSA keys"));
        }
    }

    // ============================================================================
    // Utility methods (similar to BaseController)
    // ============================================================================

    private String getOperator(HttpServletRequest request) {
        // TODO: Implement proper authentication and get current user
        return "admin";
    }

    /**
     * IP recorded against an audit entry.
     * <p>
     * FIX (spoofable audit IP): {@code X-Forwarded-For} and {@code X-Real-IP} are set by
     * the caller, so reading them unconditionally let anyone attribute a license
     * operation - issuing, revoking, reassigning - to an arbitrary address. The socket
     * peer is authoritative unless {@code license.server.audit-log.trust-proxy-headers}
     * is on, which is for a deployment behind a reverse proxy that overwrites them.
     */
    private String getIpAddress(HttpServletRequest request) {
        if (properties.getAuditLog().isTrustProxyHeaders()) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // First hop of the chain: the original client.
                int comma = ip.indexOf(',');
                return comma != -1 ? ip.substring(0, comma).trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * Page response wrapper
     */
    @lombok.Data
    public static class PageResponse<T> {
        private java.util.List<T> content;
        private long totalElements;
        private int totalPages;
        private int page;
        private int size;
    }
}