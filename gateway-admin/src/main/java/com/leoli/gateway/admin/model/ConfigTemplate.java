package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Configuration Template.
 * Pre-defined templates for routes, strategies, and other configurations.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "config_template")
public class ConfigTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "category", nullable = false, length = 50)
    private String category;  // route, strategy, filter, auth, etc.

    @Column(name = "subcategory", length = 50)
    private String subcategory;  // rate-limit, circuit-breaker, etc.

    @Column(name = "config_type", nullable = false, length = 50)
    private String configType;  // json, yaml, properties

    @Column(name = "config_content", columnDefinition = "TEXT")
    private String configContent;

    @Column(name = "preview_image", length = 500)
    private String previewImage;

    @Column(name = "tags", length = 200)
    private String tags;  // comma-separated

    @Column(name = "author", length = 100)
    private String author;

    @Column(name = "version", length = 20)
    private String version;

    @Column(name = "is_official")
    private Boolean isOfficial = false;

    @Column(name = "is_public")
    private Boolean isPublic = true;

    @Column(name = "download_count", nullable = false)
    private Integer downloadCount = 0;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Column(name = "rating")
    private Double rating;  // 0.0 - 5.0

    @Column(name = "compatible_version", length = 100)
    private String compatibleVersion;  // gateway version compatibility

    @Column(name = "usage_notes", columnDefinition = "TEXT")
    private String usageNotes;

    @Column(name = "variables", columnDefinition = "TEXT")
    private String variables;  // JSON array of variable definitions

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}