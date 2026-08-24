package com.leoli.gateway.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Revoked JWT token record (server-side session blacklist).
 * <p>
 * SECURITY FIX (stateless JWT cannot be revoked): JWTs are valid for 24h with no
 * revocation mechanism, so a leaked token could not be stopped before expiry.
 * Logout now records the token hash here and the authentication filter rejects
 * any token present in this table. Only the SHA-256 hash is stored - the raw
 * token never reaches the database.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "gateway_revoked_token", indexes = {
        @Index(name = "idx_revoked_token_expires_at", columnList = "expires_at")
})
public class RevokedToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 hex digest of the revoked JWT. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Original JWT expiry - rows past this instant can be purged. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    @PrePersist
    protected void onCreate() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }
}
