package com.leoli.gateway.admin.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI Copilot 对话消息（持久化）。
 * <p>
 * 让会话历史落库：重启不丢、多副本共享、可按会话回看。{@code ChatSessionManager} 以
 * 内存缓存最近 N 条 + DB 为持久真相源。{@code toolDigest} 存该轮工具调用的紧凑摘要，
 * 供多轮追问时引用上一轮查到的数据（不重放完整工具结果，控制 token）。
 * <p>
 * dev(ddl-auto=update) 自动建表；prod(validate) 需执行 {@code resources/sql/copilot-chat-message.sql}。
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "copilot_chat_message", indexes = {
        @Index(name = "idx_ccm_session", columnList = "session_id,id")
})
public class CopilotChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 128, nullable = false)
    private String sessionId;

    /** user / assistant / system */
    @Column(name = "role", length = 16, nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 该轮工具调用的紧凑摘要（仅 assistant 消息可能有），用于多轮追问上下文。 */
    @Column(name = "tool_digest", columnDefinition = "TEXT")
    private String toolDigest;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
