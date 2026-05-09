package com.leoli.gateway.ssl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic SSL Context Manager for hot-reloading certificates.
 * Subscribes to certificate updates from config center and updates SSL context at runtime.
 *
 * @author leoli
 */
@Slf4j
@Component
public class DynamicSslContextManager {

    private final ObjectMapper objectMapper;
    private final ConfigCenterService configCenterService;

    // Cache of domain -> SSL context
    private final Map<String, SslContext> sslContextCache = new ConcurrentHashMap<>();

    // Cache of certificate data
    private final Map<String, CertificateData> certificateDataCache = new ConcurrentHashMap<>();

    // Encryption key for decrypting passwords - loaded from environment variable
    // DO NOT hardcode encryption keys in production
    @Value("${GATEWAY_SSL_ENCRYPTION_KEY:}")
    private String encryptionKey;

    // Default keystore password for PEM certificates (can be overridden via config)
    @Value("${gateway.ssl.pem-keystore-password:changeit}")
    private String pemKeystorePassword;

    @Autowired
    public DynamicSslContextManager(ConfigCenterService configCenterService, ObjectMapper objectMapper) {
        this.configCenterService = configCenterService;
        this.objectMapper = objectMapper;
        log.info("DynamicSslContextManager initialized (listening handled by SslCertificateRefresher)");
    }

    /**
     * Handle certificate update from config center or loader
     */
    public void handleCertificateUpdate(Map<String, Object> certData) {
        try {
            String domain = (String) certData.get("domain");
            String certType = (String) certData.get("certType");
            Boolean enabled = (Boolean) certData.get("enabled");

            if (domain == null) {
                log.warn("Certificate update missing domain, ignoring");
                return;
            }

            if (enabled == null || !enabled) {
                log.info("Certificate for domain {} is disabled, removing from cache", domain);
                sslContextCache.remove(domain);
                certificateDataCache.remove(domain);
                return;
            }

            SslContext sslContext;
            if ("PEM".equalsIgnoreCase(certType)) {
                sslContext = createPemSslContext(certData);
            } else {
                sslContext = createKeystoreSslContext(certData);
            }

            if (sslContext != null) {
                sslContextCache.put(domain, sslContext);
                log.info("SSL context updated for domain: {}", domain);
            }
        } catch (Exception e) {
            log.error("Failed to update SSL context", e);
        }
    }

    /**
     * Create SSL context from PEM format
     */
    private SslContext createPemSslContext(Map<String, Object> certData) {
        try {
            String certContent = (String) certData.get("certContent");
            String keyContent = (String) certData.get("keyContent");

            if (certContent == null || keyContent == null) {
                log.error("PEM certificate missing cert or key content");
                return null;
            }

            // Parse certificate
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            String pemCert = certContent
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            byte[] certBytes = Base64.getDecoder().decode(pemCert);
            X509Certificate cert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBytes));

            // Parse private key (PKCS#8 format expected)
            String pemKey = keyContent
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(pemKey);

            // Create key spec and generate private key
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            // Create temporary keystore
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null, null);
            char[] password = pemKeystorePassword.toCharArray();
            keyStore.setKeyEntry("gateway", privateKey, password, new Certificate[]{cert});

            // Create key manager factory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);

            // Create trust manager factory
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            // Build SSL context
            return SslContextBuilder.forServer(kmf)
                    .trustManager(tmf)
                    .build();

        } catch (Exception e) {
            log.error("Failed to create PEM SSL context", e);
            return null;
        }
    }

    /**
     * Create SSL context from JKS/P12 keystore
     */
    private SslContext createKeystoreSslContext(Map<String, Object> certData) {
        try {
            String certType = (String) certData.get("certType");
            String keystoreContent = (String) certData.get("keystoreContent");
            String encryptedPassword = (String) certData.get("keystorePassword");

            if (keystoreContent == null) {
                log.error("Keystore content is null");
                return null;
            }

            // Decrypt password
            String password = decryptPassword(encryptedPassword);

            // Decode keystore
            byte[] keystoreBytes = Base64.getDecoder().decode(keystoreContent);

            // Load keystore
            KeyStore keyStore = KeyStore.getInstance(certType.toUpperCase());
            keyStore.load(new ByteArrayInputStream(keystoreBytes), password.toCharArray());

            // Create key manager factory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password.toCharArray());

            // Create trust manager factory
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            // Build SSL context
            return SslContextBuilder.forServer(kmf)
                    .trustManager(tmf)
                    .build();

        } catch (Exception e) {
            log.error("Failed to create keystore SSL context", e);
            return null;
        }
    }

    /**
     * Get SSL context for a domain
     */
    public SslContext getSslContext(String domain) {
        return sslContextCache.get(domain);
    }

    /**
     * Check if SSL is configured for a domain
     */
    public boolean hasSslContext(String domain) {
        return sslContextCache.containsKey(domain);
    }

    /**
     * Get all configured domains
     */
    public java.util.Set<String> getConfiguredDomains() {
        return sslContextCache.keySet();
    }

    /**
     * Decrypt password using environment-configured encryption key.
     * Returns encrypted password unchanged if decryption fails or key not configured.
     */
    private String decryptPassword(String encryptedPassword) {
        if (encryptedPassword == null || encryptedPassword.isEmpty()) {
            return "";
        }
        
        // Validate encryption key is configured
        if (encryptionKey == null || encryptionKey.isEmpty()) {
            log.error("GATEWAY_SSL_ENCRYPTION_KEY not configured, cannot decrypt password");
            throw new IllegalStateException("SSL encryption key not configured. Set GATEWAY_SSL_ENCRYPTION_KEY environment variable.");
        }
        
        // Validate key length (AES-256 requires 32 bytes)
        byte[] keyBytes = encryptionKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            log.error("GATEWAY_SSL_ENCRYPTION_KEY too short (minimum 32 bytes required for AES-256)");
            throw new IllegalStateException("SSL encryption key too short. Minimum 32 characters required.");
        }
        
        try {
            // Use first 32 bytes for AES-256
            byte[] validKey = new byte[32];
            System.arraycopy(keyBytes, 0, validKey, 0, 32);
            
            javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(validKey, "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedPassword));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt SSL keystore password", e);
            throw new IllegalStateException("SSL password decryption failed", e);
        }
    }

    /**
     * Certificate data holder
     */
    private static class CertificateData {
        private String domain;
        private String certType;
        private String issuer;
        private String validFrom;
        private String validTo;

        // Getters and setters
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getCertType() { return certType; }
        public void setCertType(String certType) { this.certType = certType; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getValidFrom() { return validFrom; }
        public void setValidFrom(String validFrom) { this.validFrom = validFrom; }
        public String getValidTo() { return validTo; }
        public void setValidTo(String validTo) { this.validTo = validTo; }
    }
}