package com.leoli.gateway.admin.service.copilot;

import com.leoli.gateway.admin.entity.CopilotChatMessageEntity;
import com.leoli.gateway.admin.repository.CopilotChatMessageRepository;
import com.leoli.gateway.admin.service.copilot.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * 会话状态管理器（DB 持久化 + 内存缓存）
 * ============================================================================
 * <p>
 * 管理 AI Copilot 的会话历史与意图记忆：
 * - <b>持久化</b>：每条消息落库 {@code copilot_chat_message}，重启不丢、多副本共享、可回看；
 * - <b>内存缓存</b>：每会话缓存最近 N 条用于快速构建上下文，未命中时从 DB 回填（load-on-miss）；
 * - <b>TTL 驱逐</b>：只清内存缓存（DB 是真相源），空闲会话下次访问再回填。
 * <p>
 * 线程安全：每会话状态封装在 {@link SessionState}，对内部列表的一切读写都持有该对象锁；
 * 对外读取一律返回快照副本。DB 写失败不影响内存对话（降级为不持久，记录告警）。
 *
 * @author leoli
 */
@Slf4j
@Component
public class ChatSessionManager {

    private final CopilotChatMessageRepository messageRepository;

    public ChatSessionManager(CopilotChatMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /** 每会话可变状态；字段访问须在 synchronized(this) 内（lastAccessedAt 用 volatile 供驱逐扫描）。 */
    private static final class SessionState {
        final List<ChatMessage> history = new ArrayList<>();
        boolean loaded = false;   // 是否已从 DB 回填
        String lastIntent;
        volatile long lastAccessedAt = System.currentTimeMillis();

        void touch() {
            lastAccessedAt = System.currentTimeMillis();
        }
    }

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    /** 上下文滑动窗口：内存与 DB 都只保留每会话最近 20 条（最简单的"上下文压缩"）。 */
    public static final int MAX_HISTORY_SIZE = 20;
    public static final long SESSION_TTL_MS = 30 * 60 * 1000L;
    public static final long EVICTION_INTERVAL_MS = 10 * 60 * 1000L;

    private SessionState stateFor(String sessionId) {
        SessionState state = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        state.touch();
        return state;
    }

    /** 确保已从 DB 回填最近历史（load-on-miss）。必须在持有 state 锁时调用。 */
    private void ensureLoadedLocked(SessionState state, String sessionId) {
        if (state.loaded) {
            return;
        }
        try {
            List<CopilotChatMessageEntity> rows = messageRepository.findTop20BySessionIdOrderByIdDesc(sessionId);
            if (rows != null && !rows.isEmpty()) {
                // rows 为 id 倒序，反转为时间正序后载入
                for (int i = rows.size() - 1; i >= 0; i--) {
                    CopilotChatMessageEntity e = rows.get(i);
                    state.history.add(new ChatMessage(e.getRole(), e.getContent(), e.getToolDigest()));
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to load chat history from DB for session {}: {}", sessionId, ex.getMessage());
        }
        state.loaded = true;
    }

    // ===================== 读取 =====================

    /**
     * 获取会话历史快照（副本），可安全遍历。未命中缓存时从 DB 回填最近 N 条。
     */
    public List<ChatMessage> getHistorySnapshot(String sessionId) {
        SessionState state = stateFor(sessionId);
        synchronized (state) {
            ensureLoadedLocked(state, sessionId);
            return new ArrayList<>(state.history);
        }
    }

    /**
     * 获取完整历史（时间正序，直接读 DB）——供前端回看整段对话。
     */
    public List<ChatMessage> getFullHistory(String sessionId) {
        try {
            List<CopilotChatMessageEntity> rows = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
            List<ChatMessage> out = new ArrayList<>();
            if (rows != null) {
                for (CopilotChatMessageEntity e : rows) {
                    out.add(new ChatMessage(e.getRole(), e.getContent(), e.getToolDigest()));
                }
            }
            return out;
        } catch (Exception ex) {
            log.warn("Failed to load full history for session {}: {}", sessionId, ex.getMessage());
            return new ArrayList<>();
        }
    }

    // ===================== 写入 =====================

    public void addToHistory(String sessionId, String role, String content) {
        addToHistory(sessionId, role, content, null, null);
    }

    public void addToHistory(String sessionId, ChatMessage chatMessage) {
        addToHistory(sessionId, chatMessage.getRole(), chatMessage.getContent(), chatMessage.getToolDigest(), null);
    }

    /**
     * 追加一条消息：更新内存缓存（含裁剪）并持久化到 DB。
     *
     * @param toolDigest 该轮工具调用摘要（仅 assistant 可能有），用于多轮追问上下文
     * @param operator   操作者（可空）
     */
    public void addToHistory(String sessionId, String role, String content, String toolDigest, String operator) {
        SessionState state = stateFor(sessionId);
        int size;
        synchronized (state) {
            ensureLoadedLocked(state, sessionId);
            state.history.add(new ChatMessage(role, content, toolDigest));
            // 滑动窗口：超出上限只删最旧的，稳定保持最近 MAX_HISTORY_SIZE 条
            if (state.history.size() > MAX_HISTORY_SIZE) {
                int removeCount = state.history.size() - MAX_HISTORY_SIZE;
                for (int i = 0; i < removeCount; i++) {
                    state.history.remove(0);
                }
            }
            size = state.history.size();
        }
        // 持久化（DB 是真相源；失败降级为不持久，不阻断对话）
        try {
            CopilotChatMessageEntity e = new CopilotChatMessageEntity();
            e.setSessionId(sessionId);
            e.setRole(role);
            e.setContent(content);
            e.setToolDigest(toolDigest);
            e.setOperator(operator);
            messageRepository.save(e);
        } catch (Exception ex) {
            log.warn("Failed to persist chat message for session {}: {}", sessionId, ex.getMessage());
        }
        pruneOldMessages(sessionId);
        log.debug("Added message to history: session={}, role={}, size={}", sessionId, role, size);
    }

    // ===================== 裁剪 / 清除 =====================

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
        }
    }

    /**
     * 最简单"上下文压缩"：DB（真相源）也只保留每会话最近 {@link #MAX_HISTORY_SIZE} 条，
     * 物理删除更旧的行——30 条落库后只留最近 20 条。失败仅告警，不影响对话。
     */
    private void pruneOldMessages(String sessionId) {
        try {
            List<CopilotChatMessageEntity> rows = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
            if (rows != null && rows.size() > MAX_HISTORY_SIZE) {
                messageRepository.deleteAll(rows.subList(0, rows.size() - MAX_HISTORY_SIZE));
            }
        } catch (Exception ex) {
            log.warn("Failed to prune old chat messages for session {}: {}", sessionId, ex.getMessage());
        }
    }

    /** 清除会话：移除内存缓存并删除 DB 中该会话的全部消息。 */
    @Transactional
    public void clearHistory(String sessionId) {
        sessions.remove(sessionId);
        try {
            messageRepository.deleteBySessionId(sessionId);
        } catch (Exception ex) {
            log.warn("Failed to delete persisted history for session {}: {}", sessionId, ex.getMessage());
        }
        log.info("Cleared session: {}", sessionId);
    }

    public void clearAllHistory() {
        int sessionCount = sessions.size();
        sessions.clear();
        log.info("Cleared all in-memory sessions: count={}", sessionCount);
    }

    // ===================== 意图记忆（内存态，重启丢失可接受）=====================

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

    public void setLastIntent(String sessionId, String intent) {
        SessionState state = stateFor(sessionId);
        synchronized (state) {
            state.lastIntent = intent;
        }
    }

    public void clearIntent(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            synchronized (state) {
                state.lastIntent = null;
            }
        }
    }

    // ===================== 生命周期 =====================

    /** 定时驱逐空闲会话的<b>内存缓存</b>（DB 数据保留，下次访问回填）。 */
    @Scheduled(fixedDelay = EVICTION_INTERVAL_MS)
    public void evictExpiredSessions() {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        sessions.values().removeIf(state -> now - state.lastAccessedAt > SESSION_TTL_MS);
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("Evicted {} idle copilot sessions from memory (DB retained), {} still cached",
                    removed, sessions.size());
        }
    }

    // ===================== 统计 / 辅助 =====================

    public int getActiveSessionCount() {
        return sessions.size();
    }

    public int getHistorySize(String sessionId) {
        SessionState state = stateFor(sessionId);
        synchronized (state) {
            ensureLoadedLocked(state, sessionId);
            return state.history.size();
        }
    }

    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public ChatMessage getLastMessage(String sessionId) {
        SessionState state = stateFor(sessionId);
        synchronized (state) {
            ensureLoadedLocked(state, sessionId);
            return state.history.isEmpty() ? null : state.history.get(state.history.size() - 1);
        }
    }

    public ChatMessage getLastUserMessage(String sessionId) {
        SessionState state = stateFor(sessionId);
        synchronized (state) {
            ensureLoadedLocked(state, sessionId);
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
