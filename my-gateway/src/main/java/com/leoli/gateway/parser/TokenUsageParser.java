package com.leoli.gateway.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * - SSE Stream: Aggregate incremental usage from chunks
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

        if (usageNode.isMissingNode()) {
            return TokenUsage.empty();
        }

        int promptTokens = usageNode.path("prompt_tokens").asInt(0);
        int completionTokens = usageNode.path("completion_tokens").asInt(0);
        int totalTokens = usageNode.path("total_tokens").asInt(0);

        // If total not provided, calculate it
        if (totalTokens == 0) {
            totalTokens = promptTokens + completionTokens;
        }

        if (promptTokens == 0 && completionTokens == 0) {
            return TokenUsage.empty();
        }

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setTotalTokens(totalTokens);
        usage.setFormat("OPENAI");

        return usage;
    }

    /**
     * Parse Anthropic format.
     */
    private TokenUsage parseAnthropicFormat(JsonNode root) {
        JsonNode usageNode = root.path("usage");

        if (usageNode.isMissingNode()) {
            return TokenUsage.empty();
        }

        int inputTokens = usageNode.path("input_tokens").asInt(0);
        int outputTokens = usageNode.path("output_tokens").asInt(0);

        if (inputTokens == 0 && outputTokens == 0) {
            return TokenUsage.empty();
        }

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(inputTokens);   // Map input to prompt
        usage.setCompletionTokens(outputTokens); // Map output to completion
        usage.setTotalTokens(inputTokens + outputTokens);
        usage.setFormat("ANTHROPIC");

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

        if (usageNode.isMissingNode()) {
            // Try root-level fields
            int promptTokens = root.path(promptField).asInt(0);
            int completionTokens = root.path(completionField).asInt(0);

            if (promptTokens > 0 || completionTokens > 0) {
                TokenUsage usage = new TokenUsage();
                usage.setPromptTokens(promptTokens);
                usage.setCompletionTokens(completionTokens);
                usage.setTotalTokens(promptTokens + completionTokens);
                usage.setFormat("CUSTOM");
                return usage;
            }

            return TokenUsage.empty();
        }

        int promptTokens = usageNode.path(promptField).asInt(0);
        int completionTokens = usageNode.path(completionField).asInt(0);

        if (promptTokens == 0 && completionTokens == 0) {
            return TokenUsage.empty();
        }

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setTotalTokens(promptTokens + completionTokens);
        usage.setFormat("CUSTOM");

        return usage;
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

        // Remove "data: " prefix if present
        if (chunk.startsWith("data: ")) {
            chunk = chunk.substring(6);
        }

        // Skip [DONE] marker
        if (chunk.trim().equals("[DONE]")) {
            return TokenUsage.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(chunk);

            // OpenAI SSE format: usage may be null until final chunk
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
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

        int totalPrompt = 0;
        int totalCompletion = 0;
        int lastTotal = 0;

        for (String chunk : chunks) {
            TokenUsage chunkUsage = parseSseChunk(chunk);

            if (!chunkUsage.isEmpty()) {
                // Some services report cumulative totals, some report incremental
                // Use the maximum total seen (handles both cases)
                if (chunkUsage.getTotalTokens() > lastTotal) {
                    lastTotal = chunkUsage.getTotalTokens();
                    totalPrompt = chunkUsage.getPromptTokens();
                    totalCompletion = chunkUsage.getCompletionTokens();
                }
            }
        }

        if (totalPrompt == 0 && totalCompletion == 0) {
            return TokenUsage.empty();
        }

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(totalPrompt);
        usage.setCompletionTokens(totalCompletion);
        usage.setTotalTokens(totalPrompt + totalCompletion);
        usage.setFormat("SSE_AGGREGATED");

        return usage;
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
            return promptTokens == 0 && completionTokens == 0;
        }

        public boolean hasUsage() {
            return totalTokens > 0;
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