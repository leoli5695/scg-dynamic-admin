package com.leoli.license.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * License Entity - Database Persistence Model
 * <p>
 * Follows the same design pattern as gateway-admin's RouteEntity:
 * - UUID primary key
 * - JSON metadata fields for complex data
 * - Creation/Update timestamps
 * - Database indexes for common queries
 */
@Data
@Entity
@Table(name = "licenses", indexes = {
    @Index(name = "idx_license_customer", columnList = "customer_email"),
    @Index(name = "idx_license_status", columnList = "status"),
    @Index(name = "idx_license_expires", columnList = "expires_at"),
    @Index(name = "idx_license_edition", columnList = "edition")
})
public class LicenseEntity {

    /**
     * License unique identifier (UUID)
     */
    @Id
    @Column(name = "license_id", length = 36, nullable = false)
    private String licenseId;

    /**
     * Complete License Code (Base64 encoded JSON)
     * Contains payload + signature + publicKey
     */
    @Column(name = "license_code", columnDefinition = "TEXT")
    private String licenseCode;

    /**
     * License edition: BASIC / ADVANCED / ENTERPRISE
     */
    @Column(name = "edition", length = 20, nullable = false)
    private String edition;

    /**
     * Customer name (company or individual)
     */
    @Column(name = "customer_name", length = 255, nullable = false)
    private String customerName;

    /**
     * Customer email (unique identifier for customer)
     */
    @Column(name = "customer_email", length = 255, nullable = false)
    private String customerEmail;

    /**
     * Organization/company name (optional)
     */
    @Column(name = "organization", length = 255)
    private String organization;

    /**
     * Machine binding ID (optional)
     * Can be MAC address, hostname, or custom fingerprint
     */
    @Column(name = "machine_id", length = 64)
    private String machineId;

    /**
     * License issue timestamp
     */
    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    /**
     * License expiration timestamp
     * null = permanent license
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * Authorized features (JSON array of LicenseFeature names)
     * Example: ["WEIGHTED_ROUTING", "DISTRIBUTED_RATE_LIMIT", "CIRCUIT_BREAKER"]
     */
    @Column(name = "features", columnDefinition = "TEXT")
    private String features;

    /**
     * Quota limits (JSON object of LicenseQuota)
     * Example: {"maxInstances":3,"maxRoutes":100,"maxRateLimitQPS":10000}
     */
    @Column(name = "quotas", columnDefinition = "TEXT")
    private String quotas;

    /**
     * RSA signature (Base64 encoded)
     * Signature of payload JSON using SHA256withRSA
     */
    @Column(name = "signature", columnDefinition = "TEXT")
    private String signature;

    /**
     * RSA public key (Base64 encoded X509)
     * Used for offline signature verification
     */
    @Column(name = "public_key", columnDefinition = "TEXT")
    private String publicKey;

    /**
     * License status: ACTIVE / EXPIRED / REVOKED
     */
    @Column(name = "status", length = 20, nullable = false)
    private String status = "ACTIVE";

    /**
     * Notes or comments (contract reference, etc.)
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Creation timestamp (auto-generated)
     */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * Last update timestamp (auto-generated)
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Check if license is expired
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false; // Permanent license
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if license is active and valid
     */
    public boolean isActive() {
        return "ACTIVE".equals(status) && !isExpired();
    }

    /**
     * Get remaining days until expiration
     * Returns null for permanent licenses
     */
    public Integer getDaysRemaining() {
        if (expiresAt == null) {
            return null; // Permanent
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), expiresAt);
        return days > 0 ? (int) days : 0;
    }

    /**
     * Mark license as expired
     */
    public void markExpired() {
        this.status = "EXPIRED";
    }

    /**
     * Mark license as revoked
     */
    public void markRevoked() {
        this.status = "REVOKED";
    }
}