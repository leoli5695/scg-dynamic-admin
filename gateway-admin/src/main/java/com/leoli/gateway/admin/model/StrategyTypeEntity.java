package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Strategy type entity for metadata storage.
 * Stores strategy type definitions with config schemas for dynamic form rendering.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "strategy_types", indexes = {
    @Index(name = "idx_strategy_types_category", columnList = "category"),
    @Index(name = "idx_strategy_types_enabled", columnList = "enabled"),
    @Index(name = "idx_strategy_types_sort", columnList = "sort_order")
})
public class StrategyTypeEntity {

    @Id
    @Column(name = "type_code", length = 50)
    private String typeCode;

    @Column(name = "type_name", length = 100, nullable = false)
    private String typeName;

    @Column(name = "type_name_en", length = 100, nullable = false)
    private String typeNameEn;

    @Column(name = "icon", length = 50, nullable = false)
    private String icon = "ThunderboltOutlined";

    @Column(name = "color", length = 20, nullable = false)
    private String color = "#3b82f6";

    @Column(name = "category", length = 50, nullable = false)
    private String category = "misc";

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "config_schema", columnDefinition = "JSON")
    private String configSchema;

    @Column(name = "filter_class", length = 200)
    private String filterClass;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 100;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}