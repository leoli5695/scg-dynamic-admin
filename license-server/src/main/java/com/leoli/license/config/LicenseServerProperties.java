package com.leoli.license.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * License Server Configuration Properties
 * <p>
 * Configuration prefix: license.server
 */
@Data
@ConfigurationProperties(prefix = "license.server")
public class LicenseServerProperties {

    /**
     * RSA key configuration
     */
    private RsaConfig rsa = new RsaConfig();

    /**
     * Audit log configuration
     */
    private AuditLogConfig auditLog = new AuditLogConfig();

    /**
     * Edition default configurations
     */
    private EditionDefaultsConfig editionDefaults = new EditionDefaultsConfig();

    /**
     * Production mode flag
     * When true, requires environment variables for sensitive config
     */
    private boolean productionMode = false;

    @Data
    public static class RsaConfig {
        /**
         * RSA private key (Base64 encoded PKCS8)
         * Production: must be set via LICENSE_RSA_PRIVATE_KEY env var
         */
        private String privateKey;

        /**
         * RSA public key (Base64 encoded X509)
         * Production: must be set via LICENSE_RSA_PUBLIC_KEY env var
         */
        private String publicKey;

        /**
         * Key size in bits (default: 2048)
         */
        private int keySize = 2048;
    }

    @Data
    public static class AuditLogConfig {
        /**
         * Audit log retention days
         */
        private int retentionDays = 365;

        /**
         * Enable audit logging
         */
        private boolean enabled = true;

        /**
         * Whether audit entries record the IP from {@code X-Forwarded-For} /
         * {@code X-Real-IP} instead of the socket peer.
         * <p>
         * Defaults to false: those headers are sent by the caller, so trusting them
         * lets anyone attribute a license operation to an arbitrary IP. Enable only
         * when this server sits behind a reverse proxy that overwrites them.
         */
        private boolean trustProxyHeaders = false;
    }

    @Data
    public static class EditionDefaultsConfig {
        private EditionFeatures basic = new EditionFeatures();
        private EditionFeatures advanced = new EditionFeatures();
        private EditionFeatures enterprise = new EditionFeatures();

        @Data
        public static class EditionFeatures {
            /**
             * Default features for this edition
             */
            private String[] features;

            /**
             * Default quotas for this edition (JSON)
             */
            private String quotas;
        }
    }
}