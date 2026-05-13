package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.SslCertificate;
import com.leoli.gateway.admin.service.SslCertificateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API controller for SSL certificate management.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/ssl")
@RequiredArgsConstructor
public class SslCertificateController {

    private final SslCertificateService sslCertificateService;
    private final ObjectMapper objectMapper;

    /**
     * Get all certificates
     * @param enabled Optional filter by enabled status
     * @param instanceId Optional instance ID to filter certificates
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<SslCertificate>>> getAllCertificates(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String instanceId) {
        List<SslCertificate> certificates;
        if (instanceId != null && !instanceId.isEmpty()) {
            if (enabled != null && enabled) {
                certificates = sslCertificateService.getEnabledCertificates(instanceId);
            } else {
                certificates = sslCertificateService.getAllCertificates(instanceId);
            }
        } else if (enabled != null) {
            certificates = sslCertificateService.getCertificatesByEnabled(enabled);
        } else {
            certificates = sslCertificateService.getAllCertificates();
        }
        return ResponseEntity.ok(ApiResponse.success(certificates));
    }

    /**
     * Get certificate by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SslCertificate>> getCertificateById(@PathVariable Long id) {
        return sslCertificateService.getCertificateById(id)
                .map(cert -> ResponseEntity.ok(ApiResponse.success(cert)))
                .orElse(ResponseEntity.ok(ApiResponse.notFound("Certificate not found")));
    }

    /**
     * Get certificate by domain
     * @param domain Domain name
     * @param instanceId Optional instance ID
     */
    @GetMapping("/domain/{domain}")
    public ResponseEntity<ApiResponse<SslCertificate>> getCertificateByDomain(
            @PathVariable String domain,
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            return sslCertificateService.getCertificateByDomain(instanceId, domain)
                    .map(cert -> ResponseEntity.ok(ApiResponse.success(cert)))
                    .orElse(ResponseEntity.ok(ApiResponse.notFound("Certificate not found for domain: " + domain)));
        }
        return sslCertificateService.getCertificateByDomain(domain)
                .map(cert -> ResponseEntity.ok(ApiResponse.success(cert)))
                .orElse(ResponseEntity.ok(ApiResponse.notFound("Certificate not found for domain: " + domain)));
    }

    /**
     * Get certificate statistics
     * @param instanceId Optional instance ID to filter statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCertificateStats(
            @RequestParam(required = false) String instanceId) {
        Map<String, Object> stats;
        if (instanceId != null && !instanceId.isEmpty()) {
            stats = sslCertificateService.getCertificateStats(instanceId);
        } else {
            stats = sslCertificateService.getCertificateStats();
        }
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    /**
     * Get expiring certificates
     * @param days Days threshold
     * @param instanceId Optional instance ID
     */
    @GetMapping("/expiring")
    public ResponseEntity<ApiResponse<List<SslCertificate>>> getExpiringCertificates(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String instanceId) {
        List<SslCertificate> certificates;
        if (instanceId != null && !instanceId.isEmpty()) {
            certificates = sslCertificateService.getExpiringCertificates(instanceId, days);
        } else {
            certificates = sslCertificateService.getExpiringCertificates(days);
        }
        return ResponseEntity.ok(ApiResponse.success(certificates));
    }

    /**
     * Upload PEM certificate (certificate content + private key)
     * @param instanceId Optional instance ID
     */
    @PostMapping("/pem")
    public ResponseEntity<ApiResponse<SslCertificate>> uploadPemCertificate(
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String certName,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String certContent,
            @RequestParam(required = false) String keyContent) {
        try {
            String name = certName != null ? certName : body.get("certName");
            String dom = domain != null ? domain : body.get("domain");
            String cert = certContent != null ? certContent : body.get("certContent");
            String key = keyContent != null ? keyContent : body.get("keyContent");
            
            SslCertificate sslCert = sslCertificateService.uploadPemCertificate(
                    instanceId, name, dom, cert, key);
            return ResponseEntity.ok(ApiResponse.success(sslCert));
        } catch (Exception e) {
            log.error("Failed to upload PEM certificate", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Upload PEM certificate via files
     * @param instanceId Optional instance ID
     */
    @PostMapping("/pem/files")
    public ResponseEntity<ApiResponse<SslCertificate>> uploadPemFiles(
            @RequestParam String certName,
            @RequestParam String domain,
            @RequestParam("certFile") MultipartFile certFile,
            @RequestParam("keyFile") MultipartFile keyFile,
            @RequestParam(required = false) String instanceId) {
        try {
            String certContent = new String(certFile.getBytes(), StandardCharsets.UTF_8);
            String keyContent = new String(keyFile.getBytes(), StandardCharsets.UTF_8);

            SslCertificate cert = sslCertificateService.uploadPemCertificate(
                    instanceId, certName, domain, certContent, keyContent);
            return ResponseEntity.ok(ApiResponse.success(cert));
        } catch (Exception e) {
            log.error("Failed to upload PEM files", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Upload JKS/P12 keystore
     * @param instanceId Optional instance ID
     */
    @PostMapping("/keystore")
    public ResponseEntity<ApiResponse<SslCertificate>> uploadKeystore(
            @RequestParam String certName,
            @RequestParam String domain,
            @RequestParam String certType,
            @RequestParam String keystoreContent,
            @RequestParam String password,
            @RequestParam(required = false) String instanceId) {
        try {
            SslCertificate cert = sslCertificateService.uploadKeystore(
                    instanceId, certName, domain, certType, keystoreContent, password);
            return ResponseEntity.ok(ApiResponse.success(cert));
        } catch (Exception e) {
            log.error("Failed to upload keystore", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Upload keystore via file
     * @param instanceId Optional instance ID
     */
    @PostMapping("/keystore/file")
    public ResponseEntity<ApiResponse<SslCertificate>> uploadKeystoreFile(
            @RequestParam String certName,
            @RequestParam String domain,
            @RequestParam String certType,
            @RequestParam("keystoreFile") MultipartFile keystoreFile,
            @RequestParam String password,
            @RequestParam(required = false) String instanceId) {
        try {
            String keystoreContent = Base64.getEncoder().encodeToString(keystoreFile.getBytes());

            SslCertificate cert = sslCertificateService.uploadKeystore(
                    instanceId, certName, domain, certType, keystoreContent, password);
            return ResponseEntity.ok(ApiResponse.success(cert));
        } catch (Exception e) {
            log.error("Failed to upload keystore file", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Update certificate
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SslCertificate>> updateCertificate(
            @PathVariable Long id,
            @RequestParam(required = false) String certName,
            @RequestParam(required = false) String certContent,
            @RequestParam(required = false) String keyContent) {
        try {
            SslCertificate cert = sslCertificateService.updateCertificate(
                    id, certName, certContent, keyContent);
            return ResponseEntity.ok(ApiResponse.success(cert));
        } catch (Exception e) {
            log.error("Failed to update certificate", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Enable/disable certificate
     */
    @PutMapping("/{id}/enabled")
    public ResponseEntity<ApiResponse<SslCertificate>> setCertificateEnabled(
            @PathVariable Long id,
            @RequestParam boolean enabled) {
        try {
            SslCertificate cert = sslCertificateService.setCertificateEnabled(id, enabled);
            return ResponseEntity.ok(ApiResponse.success(cert));
        } catch (Exception e) {
            log.error("Failed to update certificate enabled status", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Delete certificate
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCertificate(@PathVariable Long id) {
        try {
            sslCertificateService.deleteCertificate(id);
            return ResponseEntity.ok(ApiResponse.success("Certificate deleted"));
        } catch (Exception e) {
            log.error("Failed to delete certificate", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Update all certificate expiry statuses
     */
    @PostMapping("/refresh-status")
    public ResponseEntity<ApiResponse<Void>> refreshExpiryStatuses() {
        sslCertificateService.updateExpiryStatuses();
        return ResponseEntity.ok(ApiResponse.success("Certificate statuses refreshed"));
    }
}