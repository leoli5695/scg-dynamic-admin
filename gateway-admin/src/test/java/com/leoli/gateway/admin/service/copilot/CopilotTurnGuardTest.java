package com.leoli.gateway.admin.service.copilot;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CopilotTurnGuardTest {
    @Test void sameSessionRejectsOverlappingTurnsAndReleasesAfterCompletion() {
        var guard = new CopilotTurnGuard();
        try (var first = guard.tryStart("a")) {
            assertNotNull(first); assertNull(guard.tryStart("a"));
            try (var separate = guard.tryStart("b")) { assertNotNull(separate); }
        }
        try (var next = guard.tryStart("a")) { assertNotNull(next); }
    }

    @Test void clearingCancelsTheWorkerAndPreventsLateHistoryCommit() throws Exception {
        var guard = new CopilotTurnGuard();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean wrote = new AtomicBoolean();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var active = pool.submit(() -> {
                try (var turn = guard.tryStart("a")) {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException ignored) { }
                    assertThrows(CopilotStreamAbortedException.class, () -> turn.commit(() -> wrote.set(true)));
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            guard.cancelAndRun("a", () -> wrote.set(false));
            release.countDown(); active.get(2, TimeUnit.SECONDS);
            assertFalse(wrote.get());
            try (var next = guard.tryStart("a")) { assertNotNull(next); }
        }
    }

    @Test void accountNamespaceCannotCollideForTheSameBrowserConversationId() {
        String first = CopilotSessionIds.scoped("alice", "same-session");
        assertEquals(first, CopilotSessionIds.scoped("alice", "same-session"));
        assertNotEquals(first, CopilotSessionIds.scoped("bob", "same-session"));
        assertNotEquals(first, CopilotSessionIds.scoped("alice", "other-session"));
        assertTrue(first.length() <= 128);
    }
}
