package com.leoli.gateway.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Token Usage Parser - Parses AI service response usage fields.
 *
 * Supported formats:
 * - OpenAI Standard: usage.prompt_tokens + usage.completion_tokens
 * - Anthropic: usage.input_tokens + usage.output_tokens
 * - Azure OpenAI: Same as OpenAI
 * - SSE Stream: Track cumulative provider usage across events
 *
 * OpenAI Standard Format:
 * {
 *   "id": "chatcmpl-xxx",
 *   "choices": [...],
 *   "usage": {
 *     "prompt_tokens": 100,
 *     "completion_tokens": 50,
 *     "total_tokens": 150
 *   }
 * }
 *
 * Anthropic Format:
 * {
 *   "content": [...],
 *   "usage": {
 *     "input_tokens": 100,
 *     "output_tokens": 50
 *   }
 * }
 *
 * SSE Stream Format:
 * data: {"choices":[{"delta":{"content":"Hello"}}],"usage":null}
 * data: {"choices":[{"delta":{"content":" world"}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}
 * data: [DONE]
 *
 * @author leoli
 */
@Component
@Slf4j
public class TokenUsageParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse token usage from JSON response.
     *
     * @param responseBody Response body as string
     * @param format       Response format: OPENAI, ANTHROPIC, CUSTOM
     * @param fieldMapping Custom field mapping for CUSTOM format
     * @return TokenUsage result
     */
    public TokenUsage parseJsonResponse(String responseBody, String format, Map<String, String> fieldMapping) {
        if (responseBody == null || responseBody.isEmpty()) {
            return TokenUsage.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            switch (format.toUpperCase()) {
                case "OPENAI":
                    return parseOpenAIFormat(root);
                case "ANTHROPIC":
                    return parseAnthropicFormat(root);
                case "CUSTOM":
                    return parseCustomFormat(root, fieldMapping);
                default:
                    // Try OpenAI first, then Anthropic
                    if (root.path("usage").has("cache_read_input_tokens")
                            || root.path("usage").has("cache_creation_input_tokens")
                            || "message".equals(root.path("type").asText())) {
                        return parseAnthropicFormat(root);
                    }
                    TokenUsage usage = parseOpenAIFormat(root);
                    if (usage.isEmpty()) {
                        usage = parseAnthropicFormat(root);
                    }
                    return usage;
            }
        } catch (Exception e) {
            log.warn("Failed to parse token usage from response: {}", e.getMessage());
            return TokenUsage.empty();
        }
    }

    /**
     * Parse OpenAI standard format.
     */
    private TokenUsage parseOpenAIFormat(JsonNode root) {
        JsonNode usageNode = root.path("usage");
        // Responses API uses input/output names; cached/reasoning details are subsets.
        boolean responses = usageNode.has("input_tokens") || usageNode.has("output_tokens");
        String input = responses ? "input_tokens" : "prompt_tokens";
        String output = responses ? "output_tokens" : "completion_tokens";

        if (!usageNode.isObject() || !((usageNode.path(input).isIntegralNumber()
                && usageNode.path(output).isIntegralNumber()) || usageNode.path("total_tokens").isIntegralNumber())) {
            return TokenUsage.empty();
        }

        int promptTokens = tokenCount(usageNode, input);
        int completionTokens = tokenCount(usageNode, output);
        int totalTokens = tokenCount(usageNode, "total_tokens");

        // If total not provided, calculate it
        if (totalTokens == 0) {
            totalTokens = Math.addExact(promptTokens, completionTokens);
        }

        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
            return TokenUsage.empty();
        }

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setTotalTokens(totalTokens);
        usage.setFormat("OPENAI");
        usage.setReported(true);

        return usage;
    }

    /**
     * Parse Anthropic format.
     */
    private TokenUsage parseAnthropicFormat(JsonNode root) {
        JsonNode usageNode = root.path("usage");

        if (!usageNode.isObject() || !(usageNode.path("input_tokens").isIntegralNumber()
                && usageNode.path("output_tokens").isIntegralNumber())) {
            return TokenUsage.empty();
        }

        // Anthropic reports uncached input separately from cache creation/read input.
        int inputTokens = Math.addExact(tokenCount(usageNode, "input_tokens"),
                Math.addExact(tokenCount(usageNode, "cache_creation_input_tokens"),
                        tokenCount(usageNode, "cache_read_input_tokens")));
        int outputTokens = tokenCount(usageNode, "output_tokens");

        if (inputTokens < 0 || outputTokens < 0) {
            return TokenUsage.empty();
        }

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(inputTokens);   // Map input to prompt
        usage.setCompletionTokens(outputTokens); // Map output to completion
        usage.setTotalTokens(Math.addExact(inputTokens, outputTokens));
        usage.setFormat("ANTHROPIC");
        usage.setReported(true);

        return usage;
    }

    /**
     * Parse custom format with configurable field mapping.
     */
    private TokenUsage parseCustomFormat(JsonNode root, Map<String, String> fieldMapping) {
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            return TokenUsage.empty();
        }

        String promptField = fieldMapping.getOrDefault("promptTokens", "prompt_tokens");
        String completionField = fieldMapping.getOrDefault("completionTokens", "completion_tokens");

        JsonNode usageNode = root.path("usage");

        if (usageNode.isMissingNode()) usageNode = root;
        if (!usageNode.path(promptField).isIntegralNumber() || !usageNode.path(completionField).isIntegralNumber()) {
            return TokenUsage.empty();
        }
        int promptTokens = tokenCount(usageNode, promptField);
        int completionTokens = tokenCount(usageNode, completionField);

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setTotalTokens(Math.addExact(promptTokens, completionTokens));
        usage.setFormat("CUSTOM");
        usage.setReported(true);

        return usage;
    }

    private int tokenCount(JsonNode usage, String field) {
        JsonNode value = usage.path(field);
        if (value.isMissingNode()) return 0;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException("Invalid token count: " + field);
        }
        return value.intValue();
    }

    // ============== SSE Stream Parsing ==============

    /**
     * Parse SSE chunk for incremental token usage.
     * Some AI services provide usage info in each chunk.
     *
     * @param chunk Single SSE data chunk (without "data: " prefix)
     * @return TokenUsage for this chunk (may be empty if no usage info)
     */
    public TokenUsage parseSseChunk(String chunk) {
        if (chunk == null || chunk.isEmpty() || chunk.equals("[DONE]")) {
            return TokenUsage.empty();
        }

        chunk = sseData(chunk);

        // Skip [DONE] marker
        if (chunk.trim().equals("[DONE]")) {
            return TokenUsage.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(chunk);

            if (root.path("response").isObject()) {
                return parseOpenAIFormat(root.path("response"));
            }
            if ("message_start".equals(root.path("type").asText())) {
                return parseAnthropicFormat(root.path("message"));
            }

            // OpenAI SSE format: usage may be null until final chunk
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                if (root.path("type").asText().startsWith("message_")) {
                    return parseAnthropicFormat(root);
                }
                return parseOpenAIFormat(root);
            }

            // Anthropic SSE: may have different structure
            // Check for message_stop or message_delta events
            String eventType = root.path("type").asText("");
            if ("message_stop".equals(eventType) || "message_delta".equals(eventType)) {
                JsonNode messageUsage = root.path("message").path("usage");
                if (!messageUsage.isMissingNode()) {
                    return parseAnthropicFormat(root.path("message"));
                }
            }

            return TokenUsage.empty();

        } catch (Exception e) {
            log.debug("Failed to parse SSE chunk: {}", e.getMessage());
            return TokenUsage.empty();
        }
    }

    /**
     * Aggregate token usage from multiple SSE chunks.
     *
     * @param chunks List of SSE chunks
     * @return Aggregated TokenUsage
     */
    public TokenUsage aggregateSseUsage(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return TokenUsage.empty();
        }

        SseUsageAccumulator accumulator = new SseUsageAccumulator();
        for (String chunk : chunks) {
            accumulator.accept(chunk);
        }
        return accumulator.usage();
    }

    private String sseData(String frame) {
        if (frame == null) return "";
        List<String> data = new ArrayList<>();
        for (String line : frame.split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                String value = line.substring(5);
                data.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        return data.isEmpty() ? frame.trim() : String.join("\n", data);
    }

    /** Constant-size provider usage state, independent of diagnostic frame retention. */
    public class SseUsageAccumulator {
        private TokenUsage latest = TokenUsage.empty();
        private final ObjectNode anthropic = objectMapper.createObjectNode();
        private boolean complete;

        public void accept(String frame) {
            String data = sseData(frame);
            if ("[DONE]".equals(data)) { complete = true; return; }
            try {
                JsonNode root = objectMapper.readTree(data);
                if (root == null) return;
                String type = root.path("type").asText();
                if ("message_stop".equals(type) || "response.completed".equals(type)) complete = true;
                TokenUsage parsed;
                if ("message_start".equals(type) || "message_delta".equals(type)) {
                    JsonNode update = "message_start".equals(type)
                            ? root.path("message").path("usage") : root.path("usage");
                    // message_delta output_tokens is cumulative, never additive.
                    for (String field : List.of("input_tokens", "output_tokens",
                            "cache_creation_input_tokens", "cache_read_input_tokens")) {
                        if (update.has(field)) anthropic.set(field, update.get(field));
                    }
                    parsed = parseAnthropicFormat(objectMapper.createObjectNode().set("usage", anthropic));
                } else {
                    parsed = parseSseChunk(data);
                }
                if (parsed.hasUsage() && (!latest.hasUsage() || parsed.getTotalTokens() >= latest.getTotalTokens())) {
                    latest = parsed;
                    latest.setFormat("SSE_AGGREGATED");
                }
            } catch (Exception e) {
                log.debug("Ignoring invalid SSE usage: {}", e.getMessage());
            }
        }

        public TokenUsage usage() { return latest; }
        public boolean isComplete() { return complete; }
    }

    /**
     * Detect response type from Content-Type header.
     *
     * @param contentType Content-Type header value
     * @return Response type: JSON, SSE, or UNKNOWN
     */
    public ResponseType detectResponseType(String contentType) {
        if (contentType == null) {
            return ResponseType.UNKNOWN;
        }

        if (contentType.contains("text/event-stream")) {
            return ResponseType.SSE;
        }

        if (contentType.contains("application/json")) {
            return ResponseType.JSON;
        }

        return ResponseType.UNKNOWN;
    }

    /**
     * Check if SSE stream is complete (has [DONE] marker).
     */
    public boolean isSseStreamComplete(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }

        for (String chunk : chunks) {
            if (chunk != null && chunk.contains("[DONE]")) {
                return true;
            }
        }

        return false;
    }

    // ============== Inner Classes ==============

    /**
     * Token usage information.
     */
    @Data
    public static class TokenUsage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private String format;
        private String model;
        private long timestamp;
        private boolean reported;

        public static TokenUsage empty() {
            TokenUsage usage = new TokenUsage();
            usage.setPromptTokens(0);
            usage.setCompletionTokens(0);
            usage.setTotalTokens(0);
            usage.setFormat("EMPTY");
            usage.setTimestamp(System.currentTimeMillis());
            return usage;
        }

        public boolean isEmpty() {
            return !reported && totalTokens == 0 && promptTokens == 0 && completionTokens == 0;
        }

        public boolean hasUsage() {
            return reported || totalTokens > 0;
        }

        /**
         * Merge two usage objects (for SSE aggregation).
         */
        public TokenUsage merge(TokenUsage other) {
            if (other == null || other.isEmpty()) {
                return this;
            }

            TokenUsage merged = new TokenUsage();
            merged.setPromptTokens(this.promptTokens + other.promptTokens);
            merged.setCompletionTokens(this.completionTokens + other.completionTokens);
            merged.setTotalTokens(this.totalTokens + other.totalTokens);
            merged.setFormat(this.format);
            merged.setTimestamp(System.currentTimeMillis());

            return merged;
        }
    }

    /**
     * Response type enumeration.
     */
    public enum ResponseType {
        JSON,
        SSE,
        UNKNOWN
    }
}
