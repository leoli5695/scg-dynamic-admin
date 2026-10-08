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

    /**
     * Protocols every dynamically built context is pinned to.
     * <p>
     * FIX (implicit protocol negotiation): the builders previously inherited the JVM
     * defaults. On JDK 21 those already exclude TLSv1/TLSv1.1 via
     * jdk.tls.disabledAlgorithms (measured: enabledProtocols=[TLSv1.3, TLSv1.2]), but
     * a customized java.security or an older JRE would silently widen negotiation on
     * an internet-facing gateway. Pinning makes the floor explicit and portable.
     */
    private static final String[] TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

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

            // FIX (hardcoded RSA): PKCS#1/SEC1 keys cannot be parsed as PKCS#8 and
            // were failing with a confusing InvalidKeySpecException. Detect them
            // and report an actionable error instead.
            if (keyContent.contains("BEGIN RSA PRIVATE KEY") || keyContent.contains("BEGIN EC PRIVATE KEY")) {
                log.error("PEM private key is in PKCS#1/SEC1 format; convert it to PKCS#8 " +
                        "(openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem)");
                return null;
            }

            // Parse private key (PKCS#8 format expected)
            String pemKey = keyContent
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(pemKey);

            // FIX (hardcoded RSA): the key algorithm was fixed to "RSA", so EC
            // certificates failed to load. Derive it from the certificate's public
            // key, falling back to common algorithms when the provider name does
            // not match a KeyFactory directly.
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            PrivateKey privateKey = null;
            String certAlgorithm = cert.getPublicKey().getAlgorithm();
            String[] candidates = (certAlgorithm != null && !certAlgorithm.isEmpty())
                    ? new String[]{certAlgorithm, "RSA", "EC", "DSA", "Ed25519"}
                    : new String[]{"RSA", "EC", "DSA", "Ed25519"};
            Exception lastFailure = null;
            for (String algorithm : candidates) {
                try {
                    java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance(algorithm);
                    privateKey = keyFactory.generatePrivate(keySpec);
                    break;
                } catch (Exception e) {
                    lastFailure = e;
                }
            }
            if (privateKey == null) {
                log.error("Failed to parse PEM private key (cert algorithm={}): {}",
                        certAlgorithm, lastFailure != null ? lastFailure.getMessage() : "unknown");
                return null;
            }

            // Create temporary keystore
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null, null);
            char[] password = pemKeystorePassword.toCharArray();
            keyStore.setKeyEntry("gateway", privateKey, password, new Certificate[]{cert});

            // Create key manager factory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);

            // Build SSL context.
            // FIX (trust anchor): no CA material is supplied with a PEM cert/key pair,
            // so the trust manager is left unset. Initializing it with a null keystore
            // used to load the JVM default cacerts, which would have accepted a client
            // certificate issued by any public CA once mTLS is enabled on this context.
            return SslContextBuilder.forServer(kmf)
                    .protocols(TLS_PROTOCOLS)
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

            // Create trust manager factory. The configured keystore is the only
            // trust anchor here - the operator supplied it, so client certificates
            // are validated against that CA set and nothing else.
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            // Build SSL context
            return SslContextBuilder.forServer(kmf)
                    .trustManager(tmf)
                    .protocols(TLS_PROTOCOLS)
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
            // FIX (P1): was Cipher.getInstance("AES") which defaults to AES/ECB.
            // ECB is insecure (reveals plaintext patterns) AND mismatched the admin
            // console, which encrypts keystore passwords with AES/GCM/NoPadding in the
            // format Base64(IV[12] + ciphertext + authTag[16]) - so gateway decryption
            // of admin-encrypted passwords failed outright. Align with
            // SslCertificateService: AES-256-GCM, same key schedule, auth-tag verified.
            byte[] combined = Base64.getDecoder().decode(encryptedPassword);
            if (combined.length < GCM_IV_LENGTH + 16) {
                throw new IllegalArgumentException("Encrypted data too short for GCM format");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertextWithTag = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertextWithTag, 0, ciphertextWithTag.length);

            javax.crypto.spec.SecretKeySpec key =
                    new javax.crypto.spec.SecretKeySpec(normalizeAesKey(keyBytes), "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec =
                    new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, spec);
            byte[] decrypted = cipher.doFinal(ciphertextWithTag);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt SSL keystore password", e);
            throw new IllegalStateException("SSL password decryption failed", e);
        }
    }

    /** GCM IV length in bytes (must match admin SslCertificateService). */
    private static final int GCM_IV_LENGTH = 12;
    /** GCM auth tag length in bits (must match admin SslCertificateService). */
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * Normalize the configured key to a valid AES length (16/24/32 bytes),
     * mirroring admin SslCertificateService.normalizeAesKey so both sides derive
     * the same key from the same shared secret.
     */
    private byte[] normalizeAesKey(byte[] keyBytes) {
        int keyLength = keyBytes.length;
        if (keyLength == 16 || keyLength == 24 || keyLength == 32) {
            return keyBytes;
        }
        if (keyLength > 32) {
            byte[] truncated = new byte[32];
            System.arraycopy(keyBytes, 0, truncated, 0, 32);
            return truncated;
        }
        if (keyLength > 16 && keyLength < 24) {
            byte[] padded = new byte[24];
            System.arraycopy(keyBytes, 0, padded, 0, keyLength);
            return padded;
        }
        if (keyLength > 24 && keyLength < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyLength);
            return padded;
        }
        return keyBytes;
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