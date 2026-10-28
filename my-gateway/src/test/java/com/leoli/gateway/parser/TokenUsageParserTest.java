package com.leoli.gateway.parser;

import com.leoli.gateway.parser.TokenUsageParser.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token Usage Parser Unit Tests.
 *
 * Tests parsing of different AI response formats:
 * - OpenAI Standard
 * - Anthropic
 * - SSE Stream
 *
 * @author leoli
 */
class TokenUsageParserTest {
    @Test
    void customZeroIsReportedAndInvalidNumbersAreUnknown() {
        var parser = new TokenUsageParser();
        var mapping = Map.of("promptTokens", "in", "completionTokens", "out");
        assertTrue(parser.parseJsonResponse("{\"usage\":{\"in\":0,\"out\":0}}", "CUSTOM", mapping).hasUsage());
        assertTrue(parser.parseJsonResponse("{\"in\":0,\"out\":0}", "CUSTOM", mapping).hasUsage());
        for (String count : List.of("-1", "4294967297", "0.5", "\"12\"")) {
            assertFalse(parser.parseJsonResponse("{\"usage\":{\"in\":" + count + ",\"out\":1}}", "CUSTOM", mapping).hasUsage());
            assertFalse(parser.parseJsonResponse("{\"usage\":{\"prompt_tokens\":" + count + ",\"completion_tokens\":1}}", "OPENAI", null).hasUsage());
        }
        assertFalse(parser.parseJsonResponse("{\"usage\":{\"prompt_tokens\":2147483647,\"completion_tokens\":1}}", "OPENAI", null).hasUsage());
    }

    @Test
    void autoDetectionRetainsAnthropicCacheUsage() {
        var usage = new TokenUsageParser().parseJsonResponse("""
                {"usage":{"input_tokens":10,"output_tokens":20,"cache_read_input_tokens":100}}
                """, "AUTO", null);
        assertEquals(130, usage.getTotalTokens());
    }
    @Test
    void responsesUsageIncludesInputOutputWithoutDoubleCountingDetails() {
        TokenUsage usage = new TokenUsageParser().parseJsonResponse("""
                {"usage":{"input_tokens":100,"output_tokens":50,"total_tokens":150,
                "input_tokens_details":{"cached_tokens":60},"output_tokens_details":{"reasoning_tokens":30}}}
                """, "OPENAI", null);
        assertEquals(100, usage.getPromptTokens());
        assertEquals(50, usage.getCompletionTokens());
        assertEquals(150, usage.getTotalTokens());
    }

    @Test
    void anthropicIncludesCacheReadAndCreationInput() {
        TokenUsage usage = new TokenUsageParser().parseJsonResponse("""
                {"usage":{"input_tokens":10,"output_tokens":20,
                "cache_read_input_tokens":100,"cache_creation_input_tokens":30}}
                """, "ANTHROPIC", null);
        assertEquals(140, usage.getPromptTokens());
        assertEquals(160, usage.getTotalTokens());
    }

    @Test
    void nativeAnthropicStreamMergesStartAndCumulativeDeltas() {
        var usage = new TokenUsageParser().aggregateSseUsage(List.of(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":1,\"cache_read_input_tokens\":100}}}",
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":20}}",
                "data:{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":50}}",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}"));
        assertEquals(110, usage.getPromptTokens());
        assertEquals(50, usage.getCompletionTokens());
        assertEquals(160, usage.getTotalTokens());
    }

    @Test
    void responsesStreamParsesNestedResponseUsageAndTerminalEvent() {
        var accumulator = new TokenUsageParser().new SseUsageAccumulator();
        accumulator.accept("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}");
        assertTrue(accumulator.isComplete());
        assertEquals(30, accumulator.usage().getTotalTokens());
        assertEquals(10, accumulator.usage().getPromptTokens());
    }

    private TokenUsageParser parser;

    @BeforeEach
    void setUp() {
        parser = new TokenUsageParser();
    }

    // ============== OpenAI Format Tests ==============

    @Test
    @DisplayName("Parse OpenAI standard format - success")
    void parseOpenAIFormat_success() {
        String response = """
            {
              "id": "chatcmpl-abc123",
              "choices": [{"message": {"content": "Hello world"}}],
              "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 50,
                "total_tokens": 150
              }
            }
            """;

        TokenUsage usage = parser.parseJsonResponse(response, "OPENAI", null);

        assertTrue(usage.hasUsage());
        assertEquals(100, usage.getPromptTokens());
        assertEquals(50, usage.getCompletionTokens());
        assertEquals(150, usage.getTotalTokens());
        assertEquals("OPENAI", usage.getFormat());
    }

    @Test
    @DisplayName("Parse OpenAI format without total_tokens - calculate from prompt + completion")
    void parseOpenAIFormat_withoutTotalTokens() {
        String response = """
            {
              "id": "chatcmpl-abc123",
              "choices": [],
              "usage": {
                "prompt_tokens": 80,
                "completion_tokens": 20
              }
            }
            """;

        TokenUsage usage = parser.parseJsonResponse(response, "OPENAI", null);

        assertTrue(usage.hasUsage());
        assertEquals(80, usage.getPromptTokens());
        assertEquals(20, usage.getCompletionTokens());
        assertEquals(100, usage.getTotalTokens()); // Calculated
    }

    @Test
    @DisplayName("Parse OpenAI format with missing usage field - return empty")
    void parseOpenAIFormat_missingUsage() {
        String response = """
            {
              "id": "chatcmpl-abc123",
              "choices": [{"message": {"content": "Hello"}}]
            }
            """;

        TokenUsage usage = parser.parseJsonResponse(response, "OPENAI", null);

        assertFalse(usage.hasUsage());
        assertTrue(usage.isEmpty());
    }

    @Test
    @DisplayName("Explicit zero usage is confirmed, unlike missing usage")
    void parseOpenAIFormat_zeroTokens() {
        String response = """
            {
              "id": "chatcmpl-abc123",
              "usage": {
                "prompt_tokens": 0,
                "completion_tokens": 0,
                "total_tokens": 0
              }
            }
            """;

        TokenUsage usage = parser.parseJsonResponse(response, "OPENAI", null);

        assertTrue(usage.hasUsage());
        assertFalse(usage.isEmpty());
    }

    // ============== Anthropic Format Tests ==============

    @Test
    void malformedOrPartialUsageIsUnknownButSseZeroIsConfirmed() {
        for (String usage : List.of("null", "{}", "{\"prompt_tokens\":0}",
                "{\"prompt_tokens\":-1,\"completion_tokens\":2}")) {
            assertFalse(parser.parseJsonResponse("{\"usage\":" + usage + "}", "OPENAI", null).hasUsage());
        }
        TokenUsage zero = parser.aggregateSseUsage(List.of(
                "data: {\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}",
                "data: [DONE]"));
        assertTrue(zero.hasUsage());
        assertEquals(0, zero.getTotalTokens());
    }

    @Test
    @DisplayName("Parse Anthropic format - success")
    void parseAnthropicFormat_success() {
        String response = """
            {
              "content": [{"text": "Hello world"}],
              "usage": {
                "input_tokens": 120,
                "output_tokens": 30
              }
            }
            """;

        TokenUsage usage = parser.parseJsonResponse(response, "ANTHROPIC", null);

        assertTrue(usage.hasUsage());
        assertEquals(120, usage.getPromptTokens()); // input_tokens mapped to prompt
        assertEquals(30, usage.getCompletionTokens()); // output_tokens mapped to completion
        assertEquals(150, usage.getTotalTokens()); // input + output
        assertEquals("ANTHROPIC", usage.getFormat());
    }

    @Test
    @DisplayName("Parse Anthropic format with missing usage - return empty")
    void parseAnthropicFormat_missingUsage() {
        String response = """
            {
              "content": [{"text": "Hello"}]
            }
            """;

        TokenUsage usage = parser.parseJsonResponse(response, "ANTHROPIC", null);

        assertFalse(usage.hasUsage());
        assertTrue(usage.isEmpty());
    }

    // ============== Custom Format Tests ==============

    @Test
    @DisplayName("Parse custom format with field mapping - success")
    void parseCustomFormat_success() {
        String response = """
            {
              "result": "Hello world",
              "usage": {
                "input_count": 200,
                "output_count": 80
              }
            }
            """;

        Map<String, String> fieldMapping = Map.of(
                "promptTokens", "input_count",
                "completionTokens", "output_count"
        );

        TokenUsage usage = parser.parseJsonResponse(response, "CUSTOM", fieldMapping);

        assertTrue(usage.hasUsage());
        assertEquals(200, usage.getPromptTokens());
        assertEquals(80, usage.getCompletionTokens());
        assertEquals(280, usage.getTotalTokens());
        assertEquals("CUSTOM", usage.getFormat());
    }

    @Test
    @DisplayName("Parse custom format with root-level fields - success")
    void parseCustomFormat_rootLevelFields() {
        String response = """
            {
              "prompt_tokens": 50,
              "completion_tokens": 25
            }
            """;

        Map<String, String> fieldMapping = Map.of(
                "promptTokens", "prompt_tokens",
                "completionTokens", "completion_tokens"
        );

        TokenUsage usage = parser.parseJsonResponse(response, "CUSTOM", fieldMapping);

        assertTrue(usage.hasUsage());
        assertEquals(50, usage.getPromptTokens());
        assertEquals(25, usage.getCompletionTokens());
        assertEquals(75, usage.getTotalTokens());
    }

    // ============== SSE Stream Tests ==============

    @Test
    @DisplayName("Parse SSE chunk with usage info - success")
    void parseSseChunk_withUsage() {
        String chunk = """
            data: {"choices":[{"delta":{"content":"Hello"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """;

        TokenUsage usage = parser.parseSseChunk(chunk);

        assertTrue(usage.hasUsage());
        assertEquals(10, usage.getPromptTokens());
        assertEquals(5, usage.getCompletionTokens());
        assertEquals(15, usage.getTotalTokens());
    }

    @Test
    @DisplayName("Parse SSE chunk without usage - return empty")
    void parseSseChunk_noUsage() {
        String chunk = """
            data: {"choices":[{"delta":{"content":"Hello"}}],"usage":null}
            """;

        TokenUsage usage = parser.parseSseChunk(chunk);

        assertFalse(usage.hasUsage());
        assertTrue(usage.isEmpty());
    }

    @Test
    @DisplayName("Parse [DONE] marker - return empty")
    void parseSseChunk_doneMarker() {
        String chunk = "data: [DONE]";

        TokenUsage usage = parser.parseSseChunk(chunk);

        assertFalse(usage.hasUsage());
        assertTrue(usage.isEmpty());
    }

    @Test
    @DisplayName("Aggregate SSE stream usage - cumulative")
    void aggregateSseUsage_cumulative() {
        List<String> chunks = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}],\"usage\":null}",
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120}}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"!\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150}}",
                "data: [DONE]"
        );

        TokenUsage usage = parser.aggregateSseUsage(chunks);

        assertTrue(usage.hasUsage());
        assertEquals(150, usage.getTotalTokens()); // Maximum cumulative
        assertEquals("SSE_AGGREGATED", usage.getFormat());
    }

    @Test
    @DisplayName("Aggregate real stream_options.include_usage shape - usage arrives in a trailing choices:[] chunk")
    void aggregateSseUsage_includeUsageShape() {
        // Verbatim shape emitted by dashscope compatible-mode with include_usage on.
        List<String> chunks = List.of(
                "data: {\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\",\"role\":\"assistant\"},\"finish_reason\":null}],\"usage\":null}",
                "data: {\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"1\"},\"finish_reason\":null}],\"usage\":null}",
                "data: {\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"  \\n2\"},\"finish_reason\":null}],\"usage\":null}",
                "data: {\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}],\"usage\":null}",
                "data: {\"model\":\"qwen-plus\",\"choices\":[],\"usage\":{\"prompt_tokens\":22,\"completion_tokens\":18,\"total_tokens\":40}}",
                "data: [DONE]"
        );

        TokenUsage usage = parser.aggregateSseUsage(chunks);

        assertTrue(usage.hasUsage(), "the trailing usage-only chunk must be picked up");
        assertEquals(22, usage.getPromptTokens());
        assertEquals(18, usage.getCompletionTokens());
        assertEquals(40, usage.getTotalTokens());
    }

    @Test
    @DisplayName("Stream with usage:null on every chunk aggregates to empty - the reason include_usage is injected")
    void aggregateSseUsage_noUsageAnywhere() {
        List<String> chunks = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"1\"},\"finish_reason\":null}],\"usage\":null}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}],\"usage\":null}",
                "data: [DONE]"
        );

        TokenUsage usage = parser.aggregateSseUsage(chunks);

        assertFalse(usage.hasUsage(),
                "no usage means the collector settles 0 tokens and refunds the pre-deducted estimate");
    }

    @Test
    @DisplayName("Check SSE stream complete - has [DONE]")
    void isSseStreamComplete_hasDone() {
        List<String> chunks = List.of(
                "data: {\"usage\":null}",
                "data: [DONE]"
        );

        assertTrue(parser.isSseStreamComplete(chunks));
    }

    @Test
    @DisplayName("Check SSE stream incomplete - no [DONE]")
    void isSseStreamComplete_noDone() {
        List<String> chunks = List.of(
                "data: {\"usage\":null}",
                "data: {\"usage\":{\"total_tokens\":100}}"
        );

        assertFalse(parser.isSseStreamComplete(chunks));
    }

    // ============== Response Type Detection Tests ==============

    @Test
    @DisplayName("Detect SSE response type")
    void detectResponseType_sse() {
        String contentType = "text/event-stream";

        TokenUsageParser.ResponseType type = parser.detectResponseType(contentType);

        assertEquals(TokenUsageParser.ResponseType.SSE, type);
    }

    @Test
    @DisplayName("Detect JSON response type")
    void detectResponseType_json() {
        String contentType = "application/json";

        TokenUsageParser.ResponseType type = parser.detectResponseType(contentType);

        assertEquals(TokenUsageParser.ResponseType.JSON, type);
    }

    @Test
    @DisplayName("Detect unknown response type")
    void detectResponseType_unknown() {
        String contentType = "text/html";

        TokenUsageParser.ResponseType type = parser.detectResponseType(contentType);

        assertEquals(TokenUsageParser.ResponseType.UNKNOWN, type);
    }

    // ============== Edge Cases ==============

    @Test
    @DisplayName("Parse empty response body - return empty")
    void parseEmptyBody() {
        TokenUsage usage = parser.parseJsonResponse("", "OPENAI", null);

        assertFalse(usage.hasUsage());
        assertTrue(usage.isEmpty());
    }

    @Test
    @DisplayName("Parse null response body - return empty")
    void parseNullBody() {
        TokenUsage usage = parser.parseJsonResponse(null, "OPENAI", null);

        assertFalse(usage.hasUsage());
        assertTrue(usage.isEmpty());
    }

    @Test
    @DisplayName("Parse invalid JSON - return empty")
    void parseInvalidJson() {
        String response = "not a json";

        TokenUsage usage = parser.parseJsonResponse(response, "OPENAI", null);

        assertFalse(usage.hasUsage());
        assertTrue(usage.isEmpty());
    }

    @Test
    @DisplayName("Parse with default format - try OpenAI then Anthropic")
    void parseDefaultFormat() {
        String response = """
            {
              "usage": {
                "prompt_tokens": 50,
                "completion_tokens": 30
              }
            }
            """;

        TokenUsage usage = parser.parseJsonResponse(response, "DEFAULT", null);

        assertTrue(usage.hasUsage());
        assertEquals(80, usage.getTotalTokens());
    }

    // ============== TokenUsage Class Tests ==============

    @Test
    @DisplayName("TokenUsage empty() creates empty usage")
    void tokenUsage_empty() {
        TokenUsage usage = TokenUsage.empty();

        assertTrue(usage.isEmpty());
        assertFalse(usage.hasUsage());
        assertEquals(0, usage.getPromptTokens());
        assertEquals(0, usage.getCompletionTokens());
        assertEquals(0, usage.getTotalTokens());
        assertEquals("EMPTY", usage.getFormat());
    }

    @Test
    @DisplayName("TokenUsage merge() combines two usages")
    void tokenUsage_merge() {
        TokenUsage usage1 = new TokenUsage();
        usage1.setPromptTokens(50);
        usage1.setCompletionTokens(20);
        usage1.setTotalTokens(70);
        usage1.setFormat("OPENAI");

        TokenUsage usage2 = new TokenUsage();
        usage2.setPromptTokens(30);
        usage2.setCompletionTokens(10);
        usage2.setTotalTokens(40);
        usage2.setFormat("OPENAI");

        TokenUsage merged = usage1.merge(usage2);

        assertEquals(80, merged.getPromptTokens()); // 50 + 30
        assertEquals(30, merged.getCompletionTokens()); // 20 + 10
        assertEquals(110, merged.getTotalTokens()); // 70 + 40
    }

    @Test
    @DisplayName("TokenUsage merge() with null returns original")
    void tokenUsage_mergeNull() {
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(100);
        usage.setTotalTokens(100);

        TokenUsage merged = usage.merge(null);

        assertEquals(100, merged.getPromptTokens());
        assertEquals(100, merged.getTotalTokens());
    }
}
