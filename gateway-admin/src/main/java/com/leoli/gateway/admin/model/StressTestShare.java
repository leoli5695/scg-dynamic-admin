package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Stress Test Share Record.
 * Stores share links for stress test reports with expiration.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "stress_test_share")
public class StressTestShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "share_id", nullable = false, length = 36, unique = true)
    private String shareId;

    @Column(name = "test_id", nullable = false)
    private Long testId;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "view_count")
    private Integer viewCount = 0;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (viewCount == null) viewCount = 0;
    }

    public boolean isExpired() {
        if (expiresAt == null) {
            return false;
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isPermanent() {
        return expiresAt == null;
    }
}