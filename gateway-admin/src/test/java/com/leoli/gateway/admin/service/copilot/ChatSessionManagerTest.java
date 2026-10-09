package com.leoli.gateway.admin.service.copilot;

import com.leoli.gateway.admin.service.copilot.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatSessionManager 单元测试。
 * 回归重点：历史读取返回快照（隔离内部可变列表）、超限自动裁剪、并发写入不丢消息/不抛
 * ConcurrentModificationException、空闲 TTL 驱逐不误删活跃会话。
 */
class ChatSessionManagerTest {

    private final ChatSessionManager manager = new ChatSessionManager();

    @Test
    @DisplayName("快照与内部列表隔离：修改快照不影响存储")
    void snapshotIsIsolated() {
        String s = "sess-1";
        manager.addToHistory(s, "user", "hello");
        List<ChatMessage> snapshot = manager.getHistorySnapshot(s);
        snapshot.add(new ChatMessage("user", "injected"));

        assertEquals(1, manager.getHistorySize(s), "快照被修改不应影响内部历史");
        assertEquals(1, manager.getHistorySnapshot(s).size());
    }

    @Test
    @DisplayName("超过 MAX_HISTORY_SIZE 自动裁剪，不会无限增长")
    void trimsBeyondMax() {
        String s = "sess-2";
        for (int i = 0; i < 25; i++) {
            manager.addToHistory(s, "user", "m" + i);
        }
        assertTrue(manager.getHistorySize(s) <= ChatSessionManager.MAX_HISTORY_SIZE,
                "历史应被裁剪到上限以内，实际=" + manager.getHistorySize(s));
    }

    @Test
    @DisplayName("并发写入 + 并发遍历快照：不抛异常且消息不丢")
    void concurrentAddAndIterateIsSafe() throws Exception {
        String s = "sess-concurrent";
        int threads = 8;
        int perThread = 2; // 合计 16 <= MAX_HISTORY_SIZE(20)，避免裁剪干扰计数
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        // +1：把遍历线程也纳入，等它自然跑完再断言，避免 shutdownNow 提前中断造成假失败
        CountDownLatch done = new CountDownLatch(threads + 1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        manager.addToHistory(s, "user", "t" + tid + "-" + i);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // 正常关闭信号，不算并发错误
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        // 并发遍历快照（旧实现直接遍历内部 ArrayList 会抛 ConcurrentModificationException）
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 2000; i++) {
                    for (ChatMessage ignored : manager.getHistorySnapshot(s)) {
                        // no-op 遍历
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                error.compareAndSet(null, e); // 真正要抓的是 ConcurrentModificationException 等
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "所有线程应在超时前完成");
        pool.shutdownNow();

        assertNull(error.get(), "并发访问不应抛异常: " + error.get());
        assertEquals(threads * perThread, manager.getHistorySize(s), "并发写入不应丢消息");
    }

    @Test
    @DisplayName("TTL 驱逐不误删刚活跃过的会话")
    void evictionKeepsFreshSessions() {
        manager.addToHistory("fresh", "user", "hi");
        manager.evictExpiredSessions();
        assertTrue(manager.hasSession("fresh"), "活跃会话不应被驱逐");
    }

    @Test
    @DisplayName("意图记忆读写与清除")
    void lastIntentRoundTrip() {
        manager.setLastIntent("s", "route");
        assertEquals("route", manager.getLastIntent("s"));
        manager.clearIntent("s");
        assertNull(manager.getLastIntent("s"));
    }
}
