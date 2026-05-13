package com.leoli.gateway.admin.service.copilot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ============================================================================
 * 聊天消息结构
 * ============================================================================
 * <p>
 * 用于表示对话历史中的单条消息，包含角色和内容。
 * <p>
 * 角色类型:
 * - system: 系统提示词
 * - user: 用户消息
 * - assistant: AI回复
 * - tool: 工具执行结果
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 消息角色: system / user / assistant / tool
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 快速创建用户消息
     */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    /**
     * 快速创建助手消息
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /**
     * 快速创建系统消息
     */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }
}