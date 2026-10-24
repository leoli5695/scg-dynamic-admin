package com.leoli.gateway.filter.token;

import com.leoli.gateway.filter.token.TokenUsageCollectorFilter.SseFrameAccumulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framing and memory bounds of {@link SseFrameAccumulator}.
 * <p>
 * The accumulator sees EVERY chunk of an SSE response on the Netty EventLoop, so an unterminated
 * frame is a heap and CPU problem, not just a parsing one. These tests pin both the reassembly
 * behaviour the token-usage capture depends on and the cap that keeps a malformed stream bounded.
 */
class SseFrameAccumulatorTest {

    private static final int CAP = 65536;
    private static final int CHUNK = 16384;

    private static List<String> feedAll(SseFrameAccumulator frames, List<String> chunks) {
        List<String> events = new ArrayList<>();
        for (String chunk : chunks) {
            events.addAll(frames.accept(chunk));
        }
        events.addAll(frames.flush());
        return events;
    }

    @Test
    @DisplayName("one chunk carrying several events yields all of them in wire order")
    void severalEventsInOneChunk() {
        assertEquals(List.of("data: a", "data: b"),
                feedAll(new SseFrameAccumulator(CAP), List.of("data: a\n\ndata: b\n\n")));
    }

    @Test
    @DisplayName("an event split across chunk boundaries is reassembled")
    void eventSplitAcrossChunks() {
        assertEquals(List.of("data: a", "data: b"),
                feedAll(new SseFrameAccumulator(CAP), List.of("data: a\n\n", "data: b\n", "\n")));
    }

    @Test
    @DisplayName("the blank line itself split across chunks is still a boundary")
    void boundarySplitAcrossChunks() {
        assertEquals(List.of("data: a"),
                feedAll(new SseFrameAccumulator(CAP), List.of("data: a\n", "\n")));
    }

    @Test
    @DisplayName("CRLF framing normalises to the same boundary as LF")
    void crlfNormalises() {
        assertEquals(List.of("data: a", "data: b"),
                feedAll(new SseFrameAccumulator(CAP), List.of("data: a\r\n\r\ndata: b\r\n", "\r\n")));
    }

    @Test
    @DisplayName("an unterminated tail is emitted by flush at end of stream")
    void flushEmitsUnterminatedTail() {
        assertEquals(List.of("data: [DONE]"),
                feedAll(new SseFrameAccumulator(CAP), List.of("data: [DONE]")));
    }

    @Test
    @DisplayName("a stream that never frames is bounded by the cap, not by the response size")
    void unterminatedStreamStaysWithinCap() {
        SseFrameAccumulator frames = new SseFrameAccumulator(CAP);
        String filler = "x".repeat(CHUNK);

        List<String> emitted = new ArrayList<>();
        // 1 MiB with no blank line anywhere: 64x the cap.
        for (int i = 0; i < 64; i++) {
            emitted.addAll(frames.accept(filler));
        }

        assertTrue(emitted.isEmpty(), "no frame terminated, so nothing may be emitted");
        // After every accept the carry is either within the cap or was just cleared, so the
        // retained tail can never exceed it - this is what the unbounded version violated
        // (it retained all 1,048,576 chars).
        List<String> tail = frames.flush();
        assertEquals(1, tail.size());
        assertTrue(tail.get(0).length() <= CAP,
                "retained " + tail.get(0).length() + " chars, cap is " + CAP);
    }

    @Test
    @DisplayName("parsing resumes after an oversized frame, so the final usage event is not lost")
    void resumesAfterOversizedFrame() {
        SseFrameAccumulator frames = new SseFrameAccumulator(CAP);
        String filler = "x".repeat(CHUNK);
        for (int i = 0; i < 64; i++) {
            frames.accept(filler);
        }

        String usageEvent = "data: {\"usage\":{\"total_tokens\":42}}";
        List<String> emitted = new ArrayList<>(frames.accept("\n\n" + usageEvent + "\n\n"));

        assertEquals(usageEvent, emitted.get(emitted.size() - 1),
                "the usage event after an oversized frame must still be parsed");
        for (String event : emitted) {
            assertTrue(event.length() <= CAP,
                    "garbage from the dropped frame must stay within the cap, got " + event.length());
        }
    }
}
