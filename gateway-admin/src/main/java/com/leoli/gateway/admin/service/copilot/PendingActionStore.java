package com.leoli.gateway.admin.service.copilot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * 待确认写操作的短时票据存储
 * ============================================================================
 * <p>
 * 当模型提议一个 requiresConfirmation 的写操作时，编排器把 {toolName, arguments}
 * 存到这里并拿到一个一次性 ticket，随 pendingAction 下发前端。用户点击确认后，
 * 前端只回传 ticket；服务端凭 ticket 取出<b>当初模型提议的原始参数</b>执行，
 * 而不信任前端回传的 arguments。这样：
 * <ul>
 *   <li>防止把"确认某个良性动作"的票据换成任意工具/任意参数来执行；</li>
 *   <li>票据一次性消费，防止重放；</li>
 *   <li>TTL 限制，过期需重新发起对话。</li>
 * </ul>
 * 仅内存存储：服务重启会丢失未确认的票据，用户重新提问即可，符合"写操作需人工
 * 即时确认"的语义。
 *
 * @author leoli
 */
@Slf4j
@Component
public class PendingActionStore {

    /** 票据有效期（毫秒）。默认 5 分钟。 */
    public static final long TTL_MS = 5 * 60 * 1000L;

    /** 过期票据清理间隔（毫秒）。 */
    static final long CLEANUP_INTERVAL_MS = 60 * 1000L;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /** 待确认动作的不可变快照。 */
    public static final class Entry {
        private final String toolName;
        private final Map<String, Object> arguments;
        private final long expiresAt;

        Entry(String toolName, Map<String, Object> arguments, long expiresAt) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.expiresAt = expiresAt;
        }

        public String getToolName() {
            return toolName;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }

        boolean isExpired(long now) {
            return now > expiresAt;
        }
    }

    /**
     * 登记一个待确认动作，返回一次性 ticket。
     *
     * @param toolName  工具名
     * @param arguments 模型提议的参数（会被复制保存）
     * @return ticket
     */
    public String register(String toolName, Map<String, Object> arguments) {
        String ticket = UUID.randomUUID().toString();
        Map<String, Object> argsCopy = arguments != null
                ? new LinkedHashMap<>(arguments) : new LinkedHashMap<>();
        store.put(ticket, new Entry(toolName, argsCopy, System.currentTimeMillis() + TTL_MS));
        log.debug("Registered pending action ticket for tool {}", toolName);
        return ticket;
    }

    /**
     * 一次性消费票据。票据不存在、已过期或已被消费时返回 null。
     *
     * @param ticket 前端回传的票据
     * @return 待确认动作快照，或 null
     */
    public Entry consume(String ticket) {
        if (ticket == null || ticket.isEmpty()) {
            return null;
        }
        Entry entry = store.remove(ticket);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            log.debug("Pending action ticket expired for tool {}", entry.getToolName());
            return null;
        }
        return entry;
    }

    /** 当前待确认动作数量（用于监控/测试）。 */
    public int size() {
        return store.size();
    }

    /** 定时清理过期但从未被消费的票据，防止内存滞留。 */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        int before = store.size();
        store.values().removeIf(entry -> entry.isExpired(now));
        int removed = before - store.size();
        if (removed > 0) {
            log.debug("Evicted {} expired pending-action tickets", removed);
        }
    }
}
