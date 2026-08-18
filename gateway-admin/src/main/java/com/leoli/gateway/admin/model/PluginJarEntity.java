package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Plugin JAR Entity for database persistence.
 * Stores information about uploaded JAR files containing custom plugins.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "plugin_jars", indexes = {
    @Index(name = "idx_jar_hash", columnList = "sha256_hash")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_jar_id", columnNames = "jar_id"),
    @UniqueConstraint(name = "uk_plugin_jar", columnNames = "plugin_id")
})
public class PluginJarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * JAR ID (UUID) - unique identifier.
     */
    @Column(name = "jar_id", length = 36, nullable = false, unique = true)
    private String jarId;

    /**
     * Plugin ID - references PluginEntity.pluginId.
     */
    @Column(name = "plugin_id", length = 36, nullable = false, unique = true)
    private String pluginId;

    /**
     * Original filename uploaded by user.
     */
    @Column(name = "original_filename", length = 255, nullable = false)
    private String originalFilename;

    /**
     * Storage path on the server.
     */
    @Column(name = "stored_path", length = 500, nullable = false)
    private String storedPath;

    /**
     * File size in bytes.
     */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /**
     * SHA256 hash for integrity verification.
     */
    @Column(name = "sha256_hash", length = 64, nullable = false)
    private String sha256Hash;

    @CreationTimestamp
    @Column(name = "upload_time", updatable = false)
    private LocalDateTime uploadTime;

    /**
     * Who uploaded this JAR file.
     */
    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;
}