package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Intent recognition configuration entity.
 * Stores keyword weights, combo rules, and negation words for intent detection.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "intent_configs", indexes = {
    @Index(name = "idx_intent_type", columnList = "config_type"),
    @Index(name = "idx_intent_keyword", columnList = "keyword"),
    @Index(name = "idx_intent_intent", columnList = "intent")
})
public class IntentConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Config type: KEYWORD_WEIGHT, COMBO_RULE, NEGATION_WORD
     */
    @Column(name = "config_type", length = 20, nullable = false)
    private String configType;

    /**
     * Keyword or combo phrase
     */
    @Column(name = "keyword", length = 100, nullable = false)
    private String keyword;

    /**
     * Target intent
     */
    @Column(name = "intent", length = 50, nullable = false)
    private String intent;

    /**
     * Weight/score value
     */
    @Column(name = "weight", nullable = false)
    private Integer weight = 1;

    /**
     * Whether this config is enabled
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * Language filter (all means applies to all languages)
     */
    @Column(name = "language", length = 10)
    private String language = "all";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}