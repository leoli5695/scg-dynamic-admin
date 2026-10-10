package com.leoli.gateway.filter.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the SSE buffering heuristic in {@link TokenUsageCollectorFilter}.
 * <p>
 * OpenAI-compatible providers emit {@code "usage":null} and {@code "finish_reason":null}
 * on every intermediate chunk, so matching either bare field name buffers the whole
 * stream and per-stream memory becomes O(response size) instead of O(1).
 */
class TokenUsageCollectorFilterTest {

    /** Verbatim intermediate chunk from dashscope compatible-mode streaming. */
    private static final String CONTENT_CHUNK =
            "data: {\"model\":\"qwen-plus\",\"choices\":[{\"delta\":{\"content\":\"1\"},"
                    + "\"index\":0,\"finish_reason\":null,\"logprobs\":null}],\"usage\":null}";

    /** Verbatim terminal chunk emitted when stream_options.include_usage is on. */
    private static final String USAGE_CHUNK =
            "data: {\"model\":\"qwen-plus\",\"choices\":[],\"usage\":"
                    + "{\"prompt_tokens\":22,\"completion_tokens\":18,\"total_tokens\":40}}";

    private final TokenUsageCollectorFilter filter = new TokenUsageCollectorFilter();

    // ============== hasNonNullField ==============

    @Test
    @DisplayName("null-valued fields are not treated as present")
    void nullValuedFieldNotDetected() {
        assertFalse(TokenUsageCollectorFilter.hasNonNullField(CONTENT_CHUNK, "usage"));
        assertFalse(TokenUsageCollectorFilter.hasNonNullField(CONTENT_CHUNK, "finish_reason"));
        assertFalse(TokenUsageCollectorFilter.hasNonNullField("{\"usage\" : null}", "usage"));
    }

    @Test
    @DisplayName("Valued fields are detected, with or without whitespace")
    void valuedFieldDetected() {
        assertTrue(TokenUsageCollectorFilter.hasNonNullField(USAGE_CHUNK, "usage"));
        assertTrue(TokenUsageCollectorFilter.hasNonNullField("{\"usage\" : {\"total_tokens\":40}}", "usage"));
        assertTrue(TokenUsageCollectorFilter.hasNonNullField("{\"finish_reason\":\"stop\"}", "finish_reason"));
    }

    @Test
    @DisplayName("Absent fields and the same word inside content are not detected")
    void absentFieldNotDetected() {
        assertFalse(TokenUsageCollectorFilter.hasNonNullField("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}", "usage"));
        assertFalse(TokenUsageCollectorFilter.hasNonNullField(
                "{\"choices\":[{\"delta\":{\"content\":\"token usage limits\"}}],\"usage\":null}", "usage"));
        assertFalse(TokenUsageCollectorFilter.hasNonNullField("", "usage"));
    }

    // ============== shouldBufferChunk: the actual per-stream memory gate ==============

    @Test
    @DisplayName("Intermediate content chunks are NOT buffered")
    void contentChunksAreNotBuffered() {
        assertFalse(filter.shouldBufferChunk(CONTENT_CHUNK),
                "buffering every content chunk makes per-stream memory O(response size)");
    }

    @Test
    @DisplayName("Terminal usage / finish_reason / [DONE] chunks ARE buffered")
    void terminalChunksAreBuffered() {
        assertTrue(filter.shouldBufferChunk(USAGE_CHUNK));
        assertTrue(filter.shouldBufferChunk(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}],\"usage\":null}"));
        assertTrue(filter.shouldBufferChunk("data: [DONE]"));
        assertTrue(filter.shouldBufferChunk(""));
    }

    @Test
    @DisplayName("Anthropic native terminal events are buffered")
    void anthropicTerminalEventsBuffered() {
        assertTrue(filter.shouldBufferChunk("event: message_delta"));
        assertTrue(filter.shouldBufferChunk("event: message_stop"));
    }
}
