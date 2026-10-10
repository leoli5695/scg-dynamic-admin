package com.leoli.gateway.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI Security Guard configuration (strategy type AI_SECURITY).
 * <p>
 * Mirrors Higress ai-security-guard: moderate request (inbound) and response
 * (outbound) content with local rules - a sensitive-word dictionary, regex
 * patterns and PII masking. The moderator is pluggable ({@link #moderator}) so a
 * model-based moderator can be added later without changing the filter.
 *
 * @author leoli
 */
@Data
public class AiSecurityConfig {

    private boolean enabled = false;

    /** Moderate the inbound request messages. */
    private boolean scanRequest = true;

    /** Moderate the outbound response content (non-streaming; streaming is detect-only). */
    private boolean scanResponse = true;

    /** Inbound action: DETECT (log/header only) | BLOCK (reject 403) | REPLACE (redact & forward). */
    private String inboundAction = "BLOCK";

    /**
     * Outbound action: DETECT | REPLACE. BLOCK is downgraded to REPLACE outbound
     * because the HTTP status is already committed by the time the body is scanned.
     */
    private String outboundAction = "REPLACE";

    /** Sensitive words (case-insensitive substring match). */
    private List<String> bannedWords = new ArrayList<>();

    /** Sensitive regex patterns. */
    private List<String> bannedPatterns = new ArrayList<>();

    /** Mask PII (email / phone / id-card) in scanned text. */
    private boolean enablePiiMasking = true;

    /** Which PII types to mask: EMAIL, PHONE, ID_CARD. */
    private List<String> piiTypes = new ArrayList<>(Arrays.asList("EMAIL", "PHONE", "ID_CARD"));

    /** Message returned when a request/response is blocked or replaced. */
    private String blockMessage = "请求或响应包含不被允许的内容，已被网关拦截。";

    /** Moderator implementation: RULE (built-in) or MODEL (future pluggable hook). */
    private String moderator = "RULE";

    /** Max request/response body buffered for moderation. */
    private long maxBodySize = 1048576;

    /** Action enum. */
    public enum Action {
        DETECT, BLOCK, REPLACE
    }
}
