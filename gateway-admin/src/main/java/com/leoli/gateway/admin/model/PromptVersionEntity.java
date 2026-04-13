package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Prompt version history entity.
 * Stores historical versions of prompts for rollback capability.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "prompt_versions", indexes = {
    @Index(name = "idx_version_prompt", columnList = "prompt_id"),
    @Index(name = "idx_version_number", columnList = "prompt_id, version")
})
public class PromptVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Associated prompt ID
     */
    @Column(name = "prompt_id", nullable = false)
    private Long promptId;

    /**
     * Version number
     */
    @Column(name = "version", nullable = false)
    private Integer version;

    /**
     * Prompt content at this version
     */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * Change note/description
     */
    @Column(name = "change_note", length = 500)
    private String changeNote;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;
}