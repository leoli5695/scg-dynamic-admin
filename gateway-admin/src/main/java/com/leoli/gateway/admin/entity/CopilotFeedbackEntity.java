package com.leoli.gateway.admin.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI Copilot 反馈实体。
 * <p>
 * 记录用户对助手回答的 👍/👎 评价（连同问题与回答快照），用于度量回答质量、
 * 发现问题模式，并作为将来做 RAG / 微调的数据地基。没有度量就无法改进。
 * <p>
 * 列名刻意避开 Hibernate/SQL 保留字（用 user_comment / operator_name）。
 * dev 环境 ddl-auto=update 自动建表；prod(validate) 需执行
 * {@code resources/sql/copilot-feedback.sql}。
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "copilot_feedback", indexes = {
        @Index(name = "idx_copilot_fb_session", columnList = "session_id"),
        @Index(name = "idx_copilot_fb_rating", columnList = "rating"),
        @Index(name = "idx_copilot_fb_created", columnList = "created_at")
})
public class CopilotFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话ID（便于串联同一会话的多轮反馈）。 */
    @Column(name = "session_id", length = 128)
    private String sessionId;

    /** 触发该回答的用户问题（截断保存）。 */
    @Column(name = "question", length = 4000)
    private String question;

    /** AI 回答快照（截断保存）。 */
    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    /** 评价：UP / DOWN。 */
    @Column(name = "rating", length = 8, nullable = false)
    private String rating;

    /** 可选的文字备注。 */
    @Column(name = "user_comment", length = 1000)
    private String userComment;

    /** 提交反馈的操作者用户名。 */
    @Column(name = "operator_name", length = 64)
    private String operatorName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
