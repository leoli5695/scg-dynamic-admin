package com.leoli.gateway.admin.service.copilot;

import com.leoli.gateway.admin.prompt.PromptService;
import com.leoli.gateway.admin.repository.AiConfigRepository;
import com.leoli.gateway.admin.service.AiAnalysisService;
import com.leoli.gateway.admin.service.AiCopilotService;
import com.leoli.gateway.admin.service.copilot.model.IntentResult;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCopilotConversationTest {
    private final ChatSessionManager sessions = mock(ChatSessionManager.class);
    private final IntentRouter intents = mock(IntentRouter.class);
    private final AiCopilotService service = new AiCopilotService(mock(AiConfigRepository.class), mock(AiAnalysisService.class),
            sessions, intents, mock(SemanticIntentClassifier.class), mock(ToolCallOrchestrator.class),
            mock(ContextDataProvider.class), mock(CopilotResponseBuilder.class), mock(PromptService.class));

    @Test void disconnectedLocalFallbackCannotPersistEvenIfBlockingCodeClearedTheInterruptFlag() {
        AtomicBoolean gone = new AtomicBoolean();
        when(intents.processIntent(anyString(), anyString(), any())).thenAnswer(invocation -> {
            gone.set(true); return IntentResult.withFallback("greeting", "late answer", "en");
        });
        CopilotProgressListener listener = new CopilotProgressListener() {
            public void onEvent(String type, Map<String, Object> data) { }
            public void checkCancelled() {
                if (gone.get()) throw new CopilotStreamAbortedException("client disconnected");
            }
        };
        assertThrows(CopilotStreamAbortedException.class, () -> service.chat("s", "hi", null, null, null, null, listener));
        verify(sessions, never()).addToHistory(anyString(), anyString(), anyString());
    }

    @Test void concurrentMessagesCannotReadTheSameHistoryAndCommitOutOfOrder() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(intents.processIntent(anyString(), anyString(), any())).thenAnswer(invocation -> {
            started.countDown(); release.await(); return IntentResult.withFallback("greeting", "hello", "en");
        });
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> service.chat("s", "hi", null, null));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals(false, service.chat("s", "second", null, null).toMap().get("success"));
            release.countDown(); assertEquals(true, first.get(3, TimeUnit.SECONDS).toMap().get("success"));
            verify(sessions, times(2)).addToHistory(anyString(), anyString(), anyString());
        }
    }

    @Test void clearingDuringIntentDetectionPreventsLateConversationResurrection() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        when(intents.processIntent(anyString(), anyString(), any())).thenAnswer(invocation -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { }
            return IntentResult.withFallback("greeting", "late answer", "en");
        });
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var active = pool.submit(() -> service.chat("s", "hi", null, null));
            assertTrue(started.await(3, TimeUnit.SECONDS)); service.clearHistory("s");
            var failure = assertThrows(ExecutionException.class, () -> active.get(3, TimeUnit.SECONDS));
            assertInstanceOf(CopilotStreamAbortedException.class, failure.getCause());
            verify(sessions).clearHistory("s");
            verify(sessions, never()).addToHistory(anyString(), anyString(), anyString());
        }
    }
}
