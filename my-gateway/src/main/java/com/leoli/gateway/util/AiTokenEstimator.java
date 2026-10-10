package com.leoli.gateway.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.OptionalLong;

/**
 * Heuristic input-token estimation for OpenAI / Anthropic compatible request bodies.
 * <p>
 * Shared by the token-quota pre-deduction and the AI context-window limit so both
 * gate on the same number. Estimation mirrors the pre-check's original algorithm:
 * ~3 chars per token for mixed content, ~4 tokens of structure overhead per message.
 *
 * @author leoli
 */
public final class AiTokenEstimator {

    private AiTokenEstimator() {
    }

    /**
     * Estimate input tokens from any request body (pre-deduction flavour):
     * chat messages / Anthropic prompt when present, raw-length heuristic otherwise.
     *
     * @return the estimate, or empty when the body is absent so callers can choose
     *         their own fallback
     */
    public static OptionalLong estimate(String body, ObjectMapper objectMapper) {
        if (body == null || body.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode messages = root.path("messages");
            if (!messages.isMissingNode() && messages.isArray()) {
                return OptionalLong.of(estimateFromMessages(messages));
            }
            JsonNode prompt = root.path("prompt");
            if (!prompt.isMissingNode()) {
                return OptionalLong.of(estimateFromPrompt(prompt));
            }
            // Generic text estimation: ~4 chars per token for English,
            // ~2-3 chars per token for mixed content (code, math, Chinese).
            return OptionalLong.of(body.length() / 3);
        } catch (Exception e) {
            return OptionalLong.of(body.length() / 4);
        }
    }

    /**
     * Estimate input tokens of a chat-completions compatible request only
     * (context-limit flavour, Higress ai-context-limit parity): bodies without an
     * OpenAI {@code messages} array or Anthropic {@code prompt}, and non-JSON
     * bodies, yield empty so the caller can fail open.
     */
    public static OptionalLong estimateChat(String body, ObjectMapper objectMapper) {
        if (body == null || body.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode messages = root.path("messages");
            if (!messages.isMissingNode() && messages.isArray()) {
                return OptionalLong.of(estimateFromMessages(messages));
            }
            JsonNode prompt = root.path("prompt");
            if (!prompt.isMissingNode()) {
                return OptionalLong.of(estimateFromPrompt(prompt));
            }
            return OptionalLong.empty();
        } catch (Exception e) {
            return OptionalLong.empty();
        }
    }

    private static long estimateFromMessages(JsonNode messages) {
        long totalChars = 0;
        for (JsonNode message : messages) {
            JsonNode contentNode = message.path("content");
            if (contentNode.isTextual()) {
                totalChars += contentNode.asText().length();
            } else if (contentNode.isArray()) {
                // Multi-modal content: only the text parts carry tokens here.
                for (JsonNode part : contentNode) {
                    if (part.path("type").asText("").equals("text")) {
                        totalChars += part.path("text").asText().length();
                    }
                }
            }
            // Role + message structure overhead (~4 tokens per message).
            totalChars += 16;
        }
        return totalChars / 3;
    }

    private static long estimateFromPrompt(JsonNode prompt) {
        if (prompt.isTextual()) {
            return prompt.asText().length() / 3;
        }
        if (prompt.isArray()) {
            long totalChars = 0;
            for (JsonNode part : prompt) {
                totalChars += part.asText().length();
            }
            return totalChars / 3;
        }
        return 100;
    }
}
