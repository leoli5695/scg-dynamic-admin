package com.leoli.gateway.admin.service.copilot.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ============================================================================
 * 聊天消息结构
 * ============================================================================
 * <p>
 * 用于表示对话历史中的单条消息。角色:
 * - system: 系统提示词
 * - user: 用户消息
 * - assistant: AI回复
 * - tool: 工具执行结果
 * <p>
 * {@code toolDigest}：assistant 消息可携带"该轮工具调用的紧凑摘要"，用于多轮追问时
 * 让模型引用上一轮查到的原始数据（不重放完整工具结果，控制 token）。
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
public class ChatMessage {

    /** 消息角色: system / user / assistant / tool */
    private String role;

    /** 消息内容 */
    private String content;

    /** 该轮工具调用摘要（仅 assistant 可能有），可空。 */
    private String toolDigest;

    public ChatMessage(String role, String content) {
        this(role, content, null);
    }

    public ChatMessage(String role, String content, String toolDigest) {
        this.role = role;
        this.content = content;
        this.toolDigest = toolDigest;
    }

    /** 快速创建用户消息 */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    /** 快速创建助手消息 */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /** 快速创建带工具摘要的助手消息 */
    public static ChatMessage assistant(String content, String toolDigest) {
        return new ChatMessage("assistant", content, toolDigest);
    }

    /** 快速创建系统消息 */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }
}
