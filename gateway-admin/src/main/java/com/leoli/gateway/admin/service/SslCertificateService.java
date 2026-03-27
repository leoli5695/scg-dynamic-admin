package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.SslCertificate;
import com.leoli.gateway.admin.repository.SslCertificateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for managing SSL certificates.
 * Supports PEM and JKS/P12 certificate formats.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SslCertificateService {

    private final SslCertificateRepository sslCertificateRepository;
    private final ObjectMapper objectMapper;
    private final ConfigCenterService configCenterService;

    // Secret key for encrypting passwords (should be externalized in production)
    private static final String ENCRYPTION_KEY = "GatewaySSLKey123";

    /**
     * Get all certificates
     */
    public List<SslCertificate> getAllCertificates() {
        return sslCertificateRepository.findAll();
    }

    /**
     * Get certificate by ID
     */
    public Optional<SslCertificate> getCertificateById(Long id) {
        return sslCertificateRepository.findById(id);
    }

    /**
     * Get certificate by domain
     */
    public Optional<SslCertificate> getCertificateByDomain(String domain) {
        return sslCertificateRepository.findByDomain(domain);
    }

    /**
     * Get all enabled certificates
     */
    public List<SslCertificate> getEnabledCertificates() {
        return sslCertificateRepository.findByEnabled(true);
    }

    /**
     * Get certificates by enabled status
     */
    public List<SslCertificate> getCertificatesByEnabled(Boolean enabled) {
        return sslCertificateRepository.findByEnabled(enabled);
    }

    /**
     * Get certificates by status
     */
    public List<SslCertificate> getCertificatesByStatus(String status) {
        return sslCertificateRepository.findByStatus(status);
    }

    /**
     * Get expiring certificates
     */
    public List<SslCertificate> getExpiringCertificates(int days) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endDate = now.plusDays(days);
        return sslCertificateRepository.findExpiringSoon(now, endDate);
    }

    /**
     * Upload PEM certificate
     */
    @Transactional
    public SslCertificate uploadPemCertificate(String certName, String domain, String certContent, String keyContent) {
        // Parse certificate to extract info
        X509Certificate cert = parsePemCertificate(certContent);
        if (cert == null) {
            throw new RuntimeException("Failed to parse PEM certificate");
        }

        SslCertificate sslCert = new SslCertificate();
        sslCert.setCertName(certName);
        sslCert.setDomain(domain);
        sslCert.setCertType("PEM");
        sslCert.setCertContent(certContent);
        sslCert.setKeyContent(keyContent);

        fillCertificateInfo(sslCert, cert);
        sslCert = sslCertificateRepository.save(sslCert);

        // Publish to config center for hot reload
        publishCertificateUpdate(sslCert);

        log.info("Uploaded PEM certificate: {} for domain: {}", certName, domain);
        return sslCert;
    }

    /**
     * Upload JKS/P12 keystore
     */
    @Transactional
    public SslCertificate uploadKeystore(String certName, String domain, String certType,
                                          String keystoreContent, String password) {
        SslCertificate sslCert = new SslCertificate();
        sslCert.setCertName(certName);
        sslCert.setDomain(domain);
        sslCert.setCertType(certType.toUpperCase());
        sslCert.setKeystoreContent(keystoreContent);
        sslCert.setKeystorePassword(encryptPassword(password));

        // Parse keystore to extract certificate info
        try {
            byte[] keystoreBytes = Base64.getDecoder().decode(keystoreContent);
            KeyStore ks = KeyStore.getInstance(certType.toUpperCase());
            ks.load(new java.io.ByteArrayInputStream(keystoreBytes), password.toCharArray());

            Enumeration<String> aliases = ks.aliases();
            if (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate cert = ks.getCertificate(alias);
                if (cert instanceof X509Certificate) {
                    fillCertificateInfo(sslCert, (X509Certificate) cert);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse keystore", e);
            throw new RuntimeException("Failed to parse keystore: " + e.getMessage());
        }

        sslCert = sslCertificateRepository.save(sslCert);

        // Publish to config center for hot reload
        publishCertificateUpdate(sslCert);

        log.info("Uploaded {} keystore: {} for domain: {}", certType, certName, domain);
        return sslCert;
    }

    /**
     * Update certificate
     */
    @Transactional
    public SslCertificate updateCertificate(Long id, String certName, String certContent, String keyContent) {
        SslCertificate sslCert = sslCertificateRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Certificate not found: " + id));

        if (certName != null) {
            sslCert.setCertName(certName);
        }

        if ("PEM".equals(sslCert.getCertType()) && certContent != null) {
            sslCert.setCertContent(certContent);
            if (keyContent != null) {
                sslCert.setKeyContent(keyContent);
            }

            X509Certificate cert = parsePemCertificate(certContent);
            if (cert != null) {
                fillCertificateInfo(sslCert, cert);
            }
        }

        sslCert = sslCertificateRepository.save(sslCert);

        // Publish update to config center
        publishCertificateUpdate(sslCert);

        log.info("Updated certificate: {}", id);
        return sslCert;
    }

    /**
     * Enable/disable certificate
     */
    @Transactional
    public SslCertificate setCertificateEnabled(Long id, boolean enabled) {
        SslCertificate sslCert = sslCertificateRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Certificate not found: " + id));

        sslCert.setEnabled(enabled);
        sslCert = sslCertificateRepository.save(sslCert);

        // Publish update to config center
        publishCertificateUpdate(sslCert);

        log.info("Certificate {} {}", id, enabled ? "enabled" : "disabled");
        return sslCert;
    }

    /**
     * Delete certificate
     */
    @Transactional
    public void deleteCertificate(Long id) {
        SslCertificate sslCert = sslCertificateRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Certificate not found: " + id));

        sslCertificateRepository.deleteById(id);

        // Publish removal to config center
        publishCertificateRemoval(sslCert);

        log.info("Deleted certificate: {}", id);
    }

    /**
     * Update all certificate expiry statuses
     */
    @Transactional
    public void updateExpiryStatuses() {
        List<SslCertificate> certs = sslCertificateRepository.findAll();
        for (SslCertificate cert : certs) {
            cert.updateExpiryStatus();
        }
        sslCertificateRepository.saveAll(certs);
        log.info("Updated expiry statuses for {} certificates", certs.size());
    }

    /**
     * Get certificate statistics
     */
    public Map<String, Object> getCertificateStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", sslCertificateRepository.count());
        stats.put("valid", sslCertificateRepository.countByStatus("VALID"));
        stats.put("expiringSoon", sslCertificateRepository.countByStatus("EXPIRING_SOON"));
        stats.put("expired", sslCertificateRepository.countByStatus("EXPIRED"));
        stats.put("expiringList", getExpiringCertificates(30));
        return stats;
    }

    /**
     * Parse PEM certificate
     */
    private X509Certificate parsePemCertificate(String certContent) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            byte[] certBytes = certContent.getBytes(StandardCharsets.UTF_8);

            // Remove PEM headers if present
            String pemContent = certContent
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");

            byte[] decoded = Base64.getDecoder().decode(pemContent);
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(decoded));
            return cert;
        } catch (Exception e) {
            log.error("Failed to parse PEM certificate", e);
            return null;
        }
    }

    /**
     * Fill certificate info from X509Certificate
     */
    private void fillCertificateInfo(SslCertificate sslCert, X509Certificate cert) {
        try {
            sslCert.setIssuer(cert.getIssuerX500Principal().getName());
            sslCert.setSerialNumber(cert.getSerialNumber().toString(16));
            sslCert.setValidFrom(LocalDateTime.ofInstant(cert.getNotBefore().toInstant(), java.time.ZoneId.systemDefault()));
            sslCert.setValidTo(LocalDateTime.ofInstant(cert.getNotAfter().toInstant(), java.time.ZoneId.systemDefault()));
            sslCert.updateExpiryStatus();
        } catch (Exception e) {
            log.error("Failed to extract certificate info", e);
        }
    }

    /**
     * Encrypt password
     */
    private String encryptPassword(String password) {
        try {
            SecretKeySpec key = new SecretKeySpec(ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("Failed to encrypt password", e);
            return password;
        }
    }

    /**
     * Decrypt password
     */
    public String decryptPassword(String encryptedPassword) {
        try {
            SecretKeySpec key = new SecretKeySpec(ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedPassword));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt password", e);
            return encryptedPassword;
        }
    }

    /**
     * Publish certificate update to config center
     */
    private void publishCertificateUpdate(SslCertificate cert) {
        try {
            Map<String, Object> config = new HashMap<>();
            config.put("id", cert.getId());
            config.put("domain", cert.getDomain());
            config.put("certType", cert.getCertType());
            config.put("certContent", cert.getCertContent());
            config.put("keyContent", cert.getKeyContent());
            config.put("keystoreContent", cert.getKeystoreContent());
            config.put("keystorePassword", cert.getKeystorePassword());
            config.put("enabled", cert.getEnabled());

            String dataId = "ssl-certificate-" + cert.getDomain();
            configCenterService.publishConfig(dataId, config);

            log.info("Published certificate update to config center: {}", cert.getDomain());
        } catch (Exception e) {
            log.error("Failed to publish certificate update", e);
        }
    }

    /**
     * Publish certificate removal to config center
     */
    private void publishCertificateRemoval(SslCertificate cert) {
        try {
            String dataId = "ssl-certificate-" + cert.getDomain();
            configCenterService.removeConfig(dataId);
            log.info("Published certificate removal to config center: {}", cert.getDomain());
        } catch (Exception e) {
            log.error("Failed to publish certificate removal", e);
        }
    }
}