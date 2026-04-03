package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    public ResponseEntity<List<SslCertificate>> getAllCertificates(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            if (enabled != null && enabled) {
                return ResponseEntity.ok(sslCertificateService.getEnabledCertificates(instanceId));
            }
            return ResponseEntity.ok(sslCertificateService.getAllCertificates(instanceId));
        }
        if (enabled != null) {
            return ResponseEntity.ok(sslCertificateService.getCertificatesByEnabled(enabled));
        }
        return ResponseEntity.ok(sslCertificateService.getAllCertificates());
    }

    /**
     * Get certificate by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<SslCertificate> getCertificateById(@PathVariable Long id) {
        return sslCertificateService.getCertificateById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get certificate by domain
     * @param domain Domain name
     * @param instanceId Optional instance ID
     */
    @GetMapping("/domain/{domain}")
    public ResponseEntity<SslCertificate> getCertificateByDomain(
            @PathVariable String domain,
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            return sslCertificateService.getCertificateByDomain(instanceId, domain)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        }
        return sslCertificateService.getCertificateByDomain(domain)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get certificate statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCertificateStats() {
        return ResponseEntity.ok(sslCertificateService.getCertificateStats());
    }

    /**
     * Get expiring certificates
     * @param days Days threshold
     * @param instanceId Optional instance ID
     */
    @GetMapping("/expiring")
    public ResponseEntity<List<SslCertificate>> getExpiringCertificates(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(sslCertificateService.getExpiringCertificates(instanceId, days));
        }
        return ResponseEntity.ok(sslCertificateService.getExpiringCertificates(days));
    }

    /**
     * Upload PEM certificate (certificate content + private key)
     * @param instanceId Optional instance ID
     */
    @PostMapping("/pem")
    public ResponseEntity<?> uploadPemCertificate(
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String certName,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String certContent,
            @RequestParam(required = false) String keyContent) {
        try {
            // Support both JSON body and form params
            String name = certName != null ? certName : body.get("certName");
            String dom = domain != null ? domain : body.get("domain");
            String cert = certContent != null ? certContent : body.get("certContent");
            String key = keyContent != null ? keyContent : body.get("keyContent");
            
            SslCertificate sslCert = sslCertificateService.uploadPemCertificate(
                    instanceId, name, dom, cert, key);
            return ResponseEntity.ok(sslCert);
        } catch (Exception e) {
            log.error("Failed to upload PEM certificate", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Upload PEM certificate via files
     * @param instanceId Optional instance ID
     */
    @PostMapping("/pem/files")
    public ResponseEntity<?> uploadPemFiles(
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
            return ResponseEntity.ok(cert);
        } catch (Exception e) {
            log.error("Failed to upload PEM files", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Upload JKS/P12 keystore
     * @param instanceId Optional instance ID
     */
    @PostMapping("/keystore")
    public ResponseEntity<?> uploadKeystore(
            @RequestParam String certName,
            @RequestParam String domain,
            @RequestParam String certType,
            @RequestParam String keystoreContent,
            @RequestParam String password,
            @RequestParam(required = false) String instanceId) {
        try {
            SslCertificate cert = sslCertificateService.uploadKeystore(
                    instanceId, certName, domain, certType, keystoreContent, password);
            return ResponseEntity.ok(cert);
        } catch (Exception e) {
            log.error("Failed to upload keystore", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Upload keystore via file
     * @param instanceId Optional instance ID
     */
    @PostMapping("/keystore/file")
    public ResponseEntity<?> uploadKeystoreFile(
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
            return ResponseEntity.ok(cert);
        } catch (Exception e) {
            log.error("Failed to upload keystore file", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update certificate
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateCertificate(
            @PathVariable Long id,
            @RequestParam(required = false) String certName,
            @RequestParam(required = false) String certContent,
            @RequestParam(required = false) String keyContent) {
        try {
            SslCertificate cert = sslCertificateService.updateCertificate(
                    id, certName, certContent, keyContent);
            return ResponseEntity.ok(cert);
        } catch (Exception e) {
            log.error("Failed to update certificate", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Enable/disable certificate
     */
    @PutMapping("/{id}/enabled")
    public ResponseEntity<?> setCertificateEnabled(
            @PathVariable Long id,
            @RequestParam boolean enabled) {
        try {
            SslCertificate cert = sslCertificateService.setCertificateEnabled(id, enabled);
            return ResponseEntity.ok(cert);
        } catch (Exception e) {
            log.error("Failed to update certificate enabled status", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete certificate
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCertificate(@PathVariable Long id) {
        try {
            sslCertificateService.deleteCertificate(id);
            return ResponseEntity.ok(Map.of("message", "Certificate deleted"));
        } catch (Exception e) {
            log.error("Failed to delete certificate", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update all certificate expiry statuses
     */
    @PostMapping("/refresh-status")
    public ResponseEntity<?> refreshExpiryStatuses() {
        sslCertificateService.updateExpiryStatuses();
        return ResponseEntity.ok(Map.of("message", "Certificate statuses refreshed"));
    }
}