package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Prompt entity for database persistence.
 * Stores AI prompts with version control and category management.
 *
 * PromptKey format: {category}.{name}.{language}
 * Examples: base.system.zh, domain.route.zh, task.analyzeError.zh
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "prompts", indexes = {
    @Index(name = "idx_prompt_key", columnList = "prompt_key", unique = true),
    @Index(name = "idx_prompt_category", columnList = "category"),
    @Index(name = "idx_prompt_name", columnList = "name"),
    @Index(name = "idx_prompt_enabled", columnList = "enabled"),
    @Index(name = "idx_prompt_version", columnList = "version")
})
public class PromptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Prompt key - unique identifier.
     * Format: {category}.{name}.{language}
     */
    @Column(name = "prompt_key", length = 100, nullable = false, unique = true)
    private String promptKey;

    /**
     * Category: BASE, DOMAIN, TASK, TEMPLATE, KNOWLEDGE
     */
    @Column(name = "category", length = 20, nullable = false)
    private String category;

    /**
     * Name within category (e.g., system, route, analyzeError)
     */
    @Column(name = "name", length = 50, nullable = false)
    private String name;

    /**
     * Language: zh, en
     */
    @Column(name = "language", length = 10, nullable = false)
    private String language = "zh";

    /**
     * Prompt content (TEXT type)
     */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * Version number (incremented on each update)
     */
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    /**
     * Whether the prompt is enabled
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * Description of the prompt
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * Tags for search and categorization (JSON array)
     */
    @Column(name = "tags", length = 200)
    private String tags;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}