package com.leoli.gateway.admin.service.copilot;

import java.util.concurrent.ConcurrentHashMap;

/** One active turn per session in this process; cancellation is synchronized against history commits. */
public final class CopilotTurnGuard {
    private final ConcurrentHashMap<String, Turn> turns = new ConcurrentHashMap<>();

    public Turn tryStart(String sessionId) {
        Turn turn = new Turn(sessionId);
        return turns.putIfAbsent(sessionId, turn) == null ? turn : null;
    }

    public void cancelAndRun(String sessionId, Runnable clear) {
        turns.compute(sessionId, (key, active) -> {
            if (active == null) clear.run();
            else synchronized (active) {
                active.cancelled = true;
                active.worker.interrupt();
                clear.run();
            }
            return active;
        });
    }

    public final class Turn implements AutoCloseable {
        private final String sessionId;
        private final Thread worker = Thread.currentThread();
        private volatile boolean cancelled;
        private Turn(String sessionId) { this.sessionId = sessionId; }
        public void checkActive() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new CopilotStreamAbortedException("Conversation was cancelled");
            }
        }
        public synchronized void commit(Runnable operation) {
            checkActive();
            operation.run();
        }
        @Override public void close() { turns.remove(sessionId, this); }
    }
}
