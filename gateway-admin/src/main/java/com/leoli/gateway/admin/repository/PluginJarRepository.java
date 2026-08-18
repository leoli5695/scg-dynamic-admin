package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.PluginJarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Plugin JAR repository interface.
 * Manages uploaded JAR file metadata.
 *
 * @author leoli
 */
@Repository
public interface PluginJarRepository extends JpaRepository<PluginJarEntity, Long> {

    /**
     * Find JAR by JAR ID.
     */
    Optional<PluginJarEntity> findByJarId(String jarId);

    /**
     * Find JAR by plugin ID.
     */
    Optional<PluginJarEntity> findByPluginId(String pluginId);

    /**
     * Find JAR by SHA256 hash (for duplicate detection).
     */
    Optional<PluginJarEntity> findBySha256Hash(String sha256Hash);

    /**
     * Check if hash already exists (duplicate file).
     */
    boolean existsBySha256Hash(String sha256Hash);

    /**
     * Check if plugin has JAR file.
     */
    boolean existsByPluginId(String pluginId);
}