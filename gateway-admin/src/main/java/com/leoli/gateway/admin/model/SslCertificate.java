package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * SSL Certificate entity.
 * Stores SSL/TLS certificate information for dynamic hot-reload.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "ssl_certificate")
public class SslCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;

    /**
     * Certificate name for display
     */
    @Column(name = "cert_name", nullable = false, length = 100)
    private String certName;

    /**
     * Domain/SNI this certificate is for
     */
    @Column(name = "domain", nullable = false, length = 255)
    private String domain;

    /**
     * Certificate type: PEM, JKS, P12
     */
    @Column(name = "cert_type", length = 20)
    private String certType = "PEM";

    /**
     * Certificate content (for PEM: the .crt/.pem file content)
     */
    @Column(name = "cert_content", columnDefinition = "MEDIUMTEXT")
    private String certContent;

    /**
     * Private key content (for PEM: the .key file content)
     */
    @Column(name = "key_content", columnDefinition = "MEDIUMTEXT")
    private String keyContent;

    /**
     * Keystore content (for JKS/P12: base64 encoded)
     */
    @Column(name = "keystore_content", columnDefinition = "MEDIUMTEXT")
    private String keystoreContent;

    /**
     * Keystore password (encrypted)
     */
    @Column(name = "keystore_password", length = 255)
    private String keystorePassword;

    /**
     * Certificate issuer
     */
    @Column(name = "issuer", length = 255)
    private String issuer;

    /**
     * Certificate serial number
     */
    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    /**
     * Certificate valid from date
     */
    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    /**
     * Certificate expiry date
     */
    @Column(name = "valid_to")
    private LocalDateTime validTo;

    /**
     * Days until expiry (auto-calculated)
     */
    @Column(name = "days_to_expiry")
    private Integer daysToExpiry;

    /**
     * Certificate status: VALID, EXPIRED, EXPIRING_SOON
     */
    @Column(name = "status", length = 20)
    private String status = "VALID";

    /**
     * Associated routes (JSON array of route IDs)
     */
    @Column(name = "associated_routes", columnDefinition = "TEXT")
    private String associatedRoutes;

    /**
     * Whether this certificate is enabled
     */
    @Column(name = "enabled")
    private Boolean enabled = true;

    /**
     * Whether auto-renewal is enabled
     */
    @Column(name = "auto_renew")
    private Boolean autoRenew = false;

    /**
     * Last renewal time
     */
    @Column(name = "last_renewed_at")
    private LocalDateTime lastRenewedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        updateExpiryStatus();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        updateExpiryStatus();
    }

    /**
     * Update expiry status based on valid_to date
     */
    public void updateExpiryStatus() {
        if (validTo != null) {
            LocalDateTime now = LocalDateTime.now();
            daysToExpiry = (int) java.time.temporal.ChronoUnit.DAYS.between(now, validTo);

            if (daysToExpiry < 0) {
                status = "EXPIRED";
            } else if (daysToExpiry <= 30) {
                status = "EXPIRING_SOON";
            } else {
                status = "VALID";
            }
        }
    }
}