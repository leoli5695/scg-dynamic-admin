package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.license.LicenseFeatureHelper;
import com.leoli.gateway.admin.repository.CopilotFeedbackRepository;
import com.leoli.gateway.admin.service.*;
import com.leoli.gateway.admin.service.copilot.*;
import com.leoli.gateway.admin.service.knowledge.KnowledgeIngestService;
import com.leoli.gateway.admin.service.knowledge.KnowledgeSearchService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCopilotControllerLifecycleTest {
    private final AiCopilotService service = mock(AiCopilotService.class);
    private final CopilotUsageGuard usage = mock(CopilotUsageGuard.class);
    private final AiCopilotController controller = new AiCopilotController(service,
            mock(AiAnalysisService.class), mock(RouteService.class), mock(AuditLogService.class), new ObjectMapper(),
            mock(LicenseFeatureHelper.class), mock(ToolExecutor.class), mock(PendingActionStore.class), usage,
            mock(CopilotFeedbackRepository.class), mock(KnowledgeIngestService.class), mock(KnowledgeSearchService.class));

    private void user(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "unused", List.of()));
    }

    private AiCopilotController.ChatRequest request() {
        var request = new AiCopilotController.ChatRequest(); request.setSessionId("shared-browser-id"); request.setMessage("hello");
        return request;
    }

    @BeforeEach void setup() { user("alice"); when(usage.checkRequestAllowed(anyString())).thenReturn(Optional.empty()); }
    @AfterEach void cleanup() { controller.shutdownStreamExecutor(); SecurityContextHolder.clearContext(); }

    @Test void chatHistoryAndClearAllUseTheAuthenticatedNamespace() {
        when(service.chat(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new AiCopilotService.ChatResponse(true, "hello", null));
        assertEquals("shared-browser-id", controller.chat(request()).getBody().get("sessionId"));
        String alice = CopilotSessionIds.scoped("alice", "shared-browser-id");
        verify(service).chat(eq(alice), eq("hello"), any(), any(), any(), any());
        controller.getHistory("shared-browser-id"); controller.clearHistory("shared-browser-id");
        verify(service).getHistory(alice); verify(service).clearHistory(alice);
        user("bob"); controller.getHistory("shared-browser-id");
        verify(service).getHistory(CopilotSessionIds.scoped("bob", "shared-browser-id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"completionCallback", "timeoutCallback", "errorCallback"})
    @SuppressWarnings("unchecked")
    void disconnectedOrTimedOutStreamsInterruptTheModelTask(String callbackName) throws Exception {
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        when(service.chat(anyString(), anyString(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { interrupted.countDown(); throw new CopilotStreamAbortedException(stopped); }
            throw new AssertionError("unexpected return");
        });
        var emitter = controller.chatStream(request(), new MockHttpServletResponse());
        assertTrue(started.await(3, TimeUnit.SECONDS));
        Object callback = ReflectionTestUtils.getField(emitter, callbackName);
        if (callback instanceof Runnable runnable) runnable.run();
        else ((Consumer<Throwable>) callback).accept(new IOException("connection closed"));
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        verify(service).chat(eq(CopilotSessionIds.scoped("alice", "shared-browser-id")), eq("hello"), any(), any(), any(), any(), any());
    }

    @Test void blankAndOversizedMessagesAreRejectedBeforeAiWork() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator(); var request = request();
            request.setMessage("   "); assertFalse(validator.validate(request).isEmpty());
            request.setMessage("x".repeat(32001)); assertFalse(validator.validate(request).isEmpty());
            request.setMessage("hello"); assertTrue(validator.validate(request).isEmpty());
        }
    }
}
