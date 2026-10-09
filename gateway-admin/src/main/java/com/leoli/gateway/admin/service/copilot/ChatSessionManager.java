package com.leoli.gateway.admin.service.copilot;

import com.leoli.gateway.admin.service.copilot.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
 * - 支持会话清除与空闲 TTL 驱逐（防止长期运行内存泄漏）
 * <p>
 * 线程安全: 每个 session 的状态封装在 {@link SessionState} 中，所有读写都在该
 * 对象的监视器锁内进行。ConcurrentHashMap 只保证 map 本身的并发安全，value 里的
 * ArrayList 若被并发 add/remove/iterate 会抛 ConcurrentModificationException，
 * 因此对列表的一切访问都必须持有 SessionState 锁。对外读取历史一律返回快照副本
 * （{@link #getHistorySnapshot}），调用方可安全遍历。
 *
 * @author leoli
 */
@Slf4j
@Component
public class ChatSessionManager {

    // ===================== 会话状态存储 =====================

    /**
     * 每个会话的可变状态。所有字段访问都必须在 synchronized(this) 内进行
     * （lastAccessedAt 用 volatile 以便驱逐扫描无锁读取）。
     */
    private static final class SessionState {
        final List<ChatMessage> history = new ArrayList<>();
        String lastIntent;
        volatile long lastAccessedAt = System.currentTimeMillis();

        void touch() {
            lastAccessedAt = System.currentTimeMillis();
        }
    }

    /**
     * 会话存储。Key: sessionId
     */
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    // ===================== 配置常量 =====================

    /**
     * 最大历史大小（防止内存膨胀）
     */
    public static final int MAX_HISTORY_SIZE = 20;

    /**
     * 默认历史大小
     */
    public static final int DEFAULT_HISTORY_SIZE = 10;

    /**
     * 会话空闲 TTL（毫秒）。超过此时长未访问的会话会被定时任务驱逐，
     * 防止 sessionId 无限增长导致的内存泄漏。默认 30 分钟。
     */
    public static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    /**
     * TTL 驱逐扫描间隔（毫秒）。默认 10 分钟。
     */
    public static final long EVICTION_INTERVAL_MS = 10 * 60 * 1000L;

    // ===================== 内部辅助 =====================

    /**
     * 获取（或创建）会话状态并刷新访问时间。返回的对象需由调用方加锁使用。
     */
    private SessionState stateFor(String sessionId) {
        SessionState state = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        state.touch();
        return state;
    }

    // ===================== 核心方法 ====================

    /**
     * 获取会话历史快照（副本），可安全遍历。会话不存在时返回空列表。
     * <p>
     * 用于构建发给模型的消息列表——绝不能把内部可变列表直接交给调用方遍历，
     * 否则并发写入会触发 ConcurrentModificationException。
     *
     * @param sessionId 会话ID
     * @return 历史消息的副本
     */
    public List<ChatMessage> getHistorySnapshot(String sessionId) {
        SessionState state = stateFor(sessionId);
        synchronized (state) {
            return new ArrayList<>(state.history);
        }
    }

    /**
     * 获取会话历史（不创建新历史）的快照副本。
     *
     * @param sessionId 会话ID
     * @return 历史消息副本，不存在则返回 null
     */
    public List<ChatMessage> getHistory(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return null;
        }
        state.touch();
        synchronized (state) {
            return new ArrayList<>(state.history);
        }
    }

    /**
     * 添加消息到历史
     *
     * @param sessionId 会话ID
     * @param role      角色 (user/assistant/system)
     * @param content   消息内容
     */
    public void addToHistory(String sessionId, String role, String content) {
        SessionState state = stateFor(sessionId);
        int size;
        synchronized (state) {
            state.history.add(new ChatMessage(role, content));
            // 自动限制历史大小（保留最新的 DEFAULT_HISTORY_SIZE 条）
            if (state.history.size() > MAX_HISTORY_SIZE) {
                int removeCount = state.history.size() - DEFAULT_HISTORY_SIZE;
                for (int i = 0; i < removeCount; i++) {
                    state.history.remove(0);
                }
            }
            size = state.history.size();
        }
        log.debug("Added message to history: session={}, role={}, historySize={}", sessionId, role, size);
    }

    /**
     * 添加消息到历史（使用 ChatMessage 对象）
     *
     * @param sessionId   会话ID
     * @param chatMessage 聊天消息对象
     */
    public void addToHistory(String sessionId, ChatMessage chatMessage) {
        addToHistory(sessionId, chatMessage.getRole(), chatMessage.getContent());
    }

    /**
     * 限制历史大小（保留最新的 maxSize 条消息）
     *
     * @param sessionId 会话ID
     * @param maxSize   最大保留数量
     */
    public void trimHistory(String sessionId, int maxSize) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.history.size() <= maxSize) {
                return;
            }
            int removeCount = state.history.size() - maxSize;
            for (int i = 0; i < removeCount; i++) {
                state.history.remove(0);
            }
            log.debug("Trimmed history: session={}, removed={}, remaining={}", sessionId, removeCount, state.history.size());
        }
    }

    /**
     * 清除会话历史
     *
     * @param sessionId 会话ID
     */
    public void clearHistory(String sessionId) {
        sessions.remove(sessionId);
        log.info("Cleared session: {}", sessionId);
    }

    /**
     * 清除所有会话历史
     */
    public void clearAllHistory() {
        int sessionCount = sessions.size();
        sessions.clear();
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
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return null;
        }
        state.touch();
        synchronized (state) {
            return state.lastIntent;
        }
    }

    /**
     * 设置当前意图
     *
     * @param sessionId 会话ID
     * @param intent    意图类别
     */
    public void setLastIntent(String sessionId, String intent) {
        SessionState state = stateFor(sessionId);
        synchronized (state) {
            state.lastIntent = intent;
        }
        log.debug("Set last intent: session={}, intent={}", sessionId, intent);
    }

    /**
     * 清除意图记忆
     *
     * @param sessionId 会话ID
     */
    public void clearIntent(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            synchronized (state) {
                state.lastIntent = null;
            }
        }
        log.debug("Cleared intent: session={}", sessionId);
    }

    // ===================== 生命周期 =====================

    /**
     * 定时驱逐空闲超时的会话，防止 sessionId 无限增长造成内存泄漏。
     */
    @Scheduled(fixedDelay = EVICTION_INTERVAL_MS)
    public void evictExpiredSessions() {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        sessions.values().removeIf(state -> now - state.lastAccessedAt > SESSION_TTL_MS);
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("Evicted {} expired copilot sessions, {} still active", removed, sessions.size());
        }
    }

    // ===================== 统计方法 =====================

    /**
     * 获取活跃会话数量
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * 获取指定会话的历史大小
     *
     * @param sessionId 会话ID
     * @return 历史消息数量
     */
    public int getHistorySize(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.history.size();
        }
    }

    /**
     * 判断会话是否存在
     *
     * @param sessionId 会话ID
     * @return 是否存在
     */
    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    // ===================== 辅助方法 =====================

    /**
     * 获取历史的最后一条消息
     *
     * @param sessionId 会话ID
     * @return 最后一条消息，不存在则返回 null
     */
    public ChatMessage getLastMessage(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            if (state.history.isEmpty()) {
                return null;
            }
            return state.history.get(state.history.size() - 1);
        }
    }

    /**
     * 获取历史的最后一条用户消息
     *
     * @param sessionId 会话ID
     * @return 最后一条用户消息，不存在则返回 null
     */
    public ChatMessage getLastUserMessage(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            for (int i = state.history.size() - 1; i >= 0; i--) {
                ChatMessage msg = state.history.get(i);
                if ("user".equals(msg.getRole())) {
                    return msg;
                }
            }
        }
        return null;
    }
}
