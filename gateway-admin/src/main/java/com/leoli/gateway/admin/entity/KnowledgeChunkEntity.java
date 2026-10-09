package com.leoli.gateway.admin.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库分块实体（RAG）。
 * <p>
 * 语料按分块存储，embedding 以 {@code float[]} 的原始字节存 BLOB。相似度计算<b>不</b>
 * 在数据库做——由 {@code VectorIndex} 启动时全量载入内存暴力算余弦（小语料场景最省基建）。
 * 因此本表没有向量索引，DB 只当持久化与"唯一真相源"。
 * <p>
 * dev(ddl-auto=update) 自动建表；prod(validate) 需执行 {@code resources/sql/knowledge-chunk.sql}。
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "knowledge_chunk", indexes = {
        @Index(name = "idx_kc_type_enabled", columnList = "source_type,enabled"),
        @Index(name = "idx_kc_hash", columnList = "content_hash")
})
public class KnowledgeChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 语料来源类型：runbook / incident / feedback / doc。 */
    @Column(name = "source_type", length = 32, nullable = false)
    private String sourceType;

    /** 来源标识：文档ID / 反馈ID / URL，便于溯源与增量刷新。 */
    @Column(name = "source_ref", length = 255)
    private String sourceRef;

    @Column(name = "title", length = 512)
    private String title;

    /** 分块正文，检索命中后返回给模型的就是它。 */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /** embedding 向量：float[dim] 的小端字节序。 */
    @Column(name = "embedding", columnDefinition = "MEDIUMBLOB")
    private byte[] embedding;

    /** 向量维度（同一部署应一致；变更模型需重建索引）。 */
    @Column(name = "dim")
    private Integer dim;

    /** 生成 embedding 所用模型，如 text-embedding-v3。 */
    @Column(name = "model", length = 64)
    private String model;

    /** 正文哈希，用于去重与增量刷新（内容不变则不重复嵌入）。 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
        if (this.enabled == null) this.enabled = true;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
