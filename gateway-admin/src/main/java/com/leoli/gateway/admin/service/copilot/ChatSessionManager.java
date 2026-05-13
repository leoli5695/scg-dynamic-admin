package com.leoli.gateway.admin.service.copilot;

import com.leoli.gateway.admin.service.copilot.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * 会话状态管理器
 * ============================================================================
 * <p>
 * 管理 AI Copilot 的会话历史和意图记忆。
 * <p>
 * 功能:
 * - 维护每个 session 的对话历史（支持多轮对话）
 * - 记录每个 session 的最后意图（用于意图延续）
 * - 支持历史大小限制（防止内存膨胀）
 * - 支持会话清除
 * <p>
 * 存储结构:
 * - conversationHistory: Map<sessionId, List<ChatMessage>>
 * - sessionLastIntent: Map<sessionId, intent>
 * <p>
 * 线程安全: 使用 ConcurrentHashMap 保证并发安全
 *
 * @author leoli
 */
@Slf4j
@Component
public class ChatSessionManager {

    // ===================== 会话状态存储 =====================

    /**
     * 对话历史存储
     * Key: sessionId
     * Value: 该 session 的对话历史列表
     */
    private final Map<String, List<ChatMessage>> conversationHistory = new ConcurrentHashMap<>();

    /**
     * 意图记忆存储
     * Key: sessionId
     * Value: 该 session 最后检测到的意图
     */
    private final Map<String, String> sessionLastIntent = new ConcurrentHashMap<>();

    // ===================== 配置常量 =====================

    /**
     * 最大历史大小（防止内存膨胀）
     */
    public static final int MAX_HISTORY_SIZE = 20;

    /**
     * 默认历史大小
     */
    public static final int DEFAULT_HISTORY_SIZE = 10;

    // ===================== 核心方法 =====================

    /**
     * 获取或创建会话历史
     *
     * @param sessionId 会话ID
     * @return 该 session 的对话历史列表
     */
    public List<ChatMessage> getOrCreateHistory(String sessionId) {
        return conversationHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());
    }

    /**
     * 获取会话历史（不创建新历史）
     *
     * @param sessionId 会话ID
     * @return 该 session 的对话历史列表，不存在则返回 null
     */
    public List<ChatMessage> getHistory(String sessionId) {
        return conversationHistory.get(sessionId);
    }

    /**
     * 添加消息到历史
     *
     * @param sessionId 会话ID
     * @param role      角色 (user/assistant/system)
     * @param content   消息内容
     */
    public void addToHistory(String sessionId, String role, String content) {
        List<ChatMessage> history = getOrCreateHistory(sessionId);
        history.add(new ChatMessage(role, content));

        // 自动限制历史大小
        if (history.size() > MAX_HISTORY_SIZE) {
            trimHistory(sessionId, DEFAULT_HISTORY_SIZE);
        }

        log.debug("Added message to history: session={}, role={}, historySize={}",
                sessionId, role, history.size());
    }

    /**
     * 添加消息到历史（使用 ChatMessage 对象）
     *
     * @param sessionId  会话ID
     * @param chatMessage 聊天消息对象
     */
    public void addToHistory(String sessionId, ChatMessage chatMessage) {
        addToHistory(sessionId, chatMessage.getRole(), chatMessage.getContent());
    }

    /**
     * 限制历史大小（保留最新的 N 条消息）
     *
     * @param sessionId 会话ID
     * @param maxSize   最大保留数量
     */
    public void trimHistory(String sessionId, int maxSize) {
        List<ChatMessage> history = conversationHistory.get(sessionId);
        if (history == null || history.size() <= maxSize) {
            return;
        }

        // 保留最新的 maxSize 条消息
        int removeCount = history.size() - maxSize;
        for (int i = 0; i < removeCount; i++) {
            history.remove(0);
        }

        log.debug("Trimmed history: session={}, removed={}, remaining={}",
                sessionId, removeCount, history.size());
    }

    /**
     * 清除会话历史
     *
     * @param sessionId 会话ID
     */
    public void clearHistory(String sessionId) {
        conversationHistory.remove(sessionId);
        sessionLastIntent.remove(sessionId);
        log.info("Cleared session: {}", sessionId);
    }

    /**
     * 清除所有会话历史
     */
    public void clearAllHistory() {
        int sessionCount = conversationHistory.size();
        conversationHistory.clear();
        sessionLastIntent.clear();
        log.info("Cleared all sessions: count={}", sessionCount);
    }

    // ===================== 意图记忆管理 =====================

    /**
     * 获取上次意图
     *
     * @param sessionId 会话ID
     * @return 上次检测到的意图，不存在则返回 null
     */
    public String getLastIntent(String sessionId) {
        return sessionLastIntent.get(sessionId);
    }

    /**
     * 设置当前意图
     *
     * @param sessionId 会话ID
     * @param intent    意图类别
     */
    public void setLastIntent(String sessionId, String intent) {
        sessionLastIntent.put(sessionId, intent);
        log.debug("Set last intent: session={}, intent={}", sessionId, intent);
    }

    /**
     * 清除意图记忆
     *
     * @param sessionId 会话ID
     */
    public void clearIntent(String sessionId) {
        sessionLastIntent.remove(sessionId);
        log.debug("Cleared intent: session={}", sessionId);
    }

    // ===================== 统计方法 =====================

    /**
     * 获取活跃会话数量
     */
    public int getActiveSessionCount() {
        return conversationHistory.size();
    }

    /**
     * 获取指定会话的历史大小
     *
     * @param sessionId 会话ID
     * @return 历史消息数量
     */
    public int getHistorySize(String sessionId) {
        List<ChatMessage> history = conversationHistory.get(sessionId);
        return history != null ? history.size() : 0;
    }

    /**
     * 判断会话是否存在
     *
     * @param sessionId 会话ID
     * @return 是否存在
     */
    public boolean hasSession(String sessionId) {
        return conversationHistory.containsKey(sessionId);
    }

    // ===================== 辅助方法 =====================

    /**
     * 获取历史的最后一条消息
     *
     * @param sessionId 会话ID
     * @return 最后一条消息，不存在则返回 null
     */
    public ChatMessage getLastMessage(String sessionId) {
        List<ChatMessage> history = conversationHistory.get(sessionId);
        if (history == null || history.isEmpty()) {
            return null;
        }
        return history.get(history.size() - 1);
    }

    /**
     * 获取历史的最后一条用户消息
     *
     * @param sessionId 会话ID
     * @return 最后一条用户消息，不存在则返回 null
     */
    public ChatMessage getLastUserMessage(String sessionId) {
        List<ChatMessage> history = conversationHistory.get(sessionId);
        if (history == null) {
            return null;
        }

        // 从后向前查找用户消息
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if ("user".equals(msg.getRole())) {
                return msg;
            }
        }

        return null;
    }
}