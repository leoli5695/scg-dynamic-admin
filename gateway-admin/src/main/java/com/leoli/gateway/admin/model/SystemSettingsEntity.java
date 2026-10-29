package com.leoli.gateway.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** Single-row, console-wide feature switches. Enabled by default for existing deployments. */
@Data
@Entity
@Table(name = "system_settings")
public class SystemSettingsEntity {

    public static final long DEFAULT_ID = 1L;

    @Id
    private Long id = DEFAULT_ID;

    @Column(name = "stress_test_enabled", nullable = false)
    private boolean stressTestEnabled = true;

    @Column(name = "middleware_enabled", nullable = false)
    private boolean middlewareEnabled = true;

    @Column(name = "plugins_enabled", nullable = false)
    private boolean pluginsEnabled = true;

    @Column(name = "kubernetes_enabled", nullable = false)
    private boolean kubernetesEnabled = true;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}