package com.leoli.gateway.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * AI Security Guard configuration (strategy type AI_SECURITY).
 * <p>
 * Local dictionaries/regex/PII plus an optional provider-specific Java HTTP adapter.
 *
 * @author leoli
 */
@Data
public class AiSecurityConfig {

    private boolean enabled = false;

    /** Moderate the inbound request messages. */
    private boolean scanRequest = true;

    /** Moderate response text; SSE behavior is explicitly controlled by streamingPolicy. */
    private boolean scanResponse = true;

    /** Inbound action: DETECT (log/header only) | BLOCK (reject 403) | REPLACE (redact & forward). */
    private String inboundAction = "BLOCK";

    /** BLOCK rejects buffered content before commit; DETECT observes; REPLACE redacts. */
    private String outboundAction = "REPLACE";

    /** Sensitive words (case-insensitive substring match). */
    private List<String> bannedWords = new ArrayList<>();

    /** Separate business-maintained prohibited-word dictionary. No legal/semantic claim. */
    private List<String> prohibitedWords = new ArrayList<>();

    /** Sensitive regex patterns. */
    private List<String> bannedPatterns = new ArrayList<>();

    /** Mask PII (email / phone / id-card) in scanned text. */
    private boolean enablePiiMasking = true;

    /** PII handling is independent of sensitive/prohibited content handling. */
    private String piiAction = "REPLACE";

    /** Which PII types to mask: EMAIL, PHONE, ID_CARD. */
    private List<String> piiTypes = new ArrayList<>(Arrays.asList("EMAIL", "PHONE", "ID_CARD"));

    /** Message returned when a request/response is blocked or replaced. */
    private String blockMessage = "请求或响应包含不被允许的内容，已被网关拦截。";

    /** Legacy field retained for reading existing policies. Use externalAdapter for external services. */
    private String moderator = "RULE";

    /** Max request/response body buffered for moderation. */
    private long maxBodySize = 1048576;

    /** Empty URL means local checks only. HTTP adapter contract is documented in ai-content-security.md. */
    private String externalUrl = "";
    private Map<String, String> externalHeaders = new LinkedHashMap<>();
    private String externalAdapter = "HTTP_JSON";
    private Map<String, Object> externalOptions = new LinkedHashMap<>();
    private int externalTimeoutMs = 3000;
    /** BLOCK (503/502) or LOCAL (keep applying local checks). Never silently skip an outage. */
    private String externalFailureAction = "BLOCK";
    /** BUFFER audits the complete SSE before releasing it; BLOCK rejects SSE; SKIP explicitly bypasses it. */
    private String streamingPolicy = "BUFFER";
    private int responseTimeoutMs = 60000;

    /** Action enum. */
    public enum Action {
        DETECT, BLOCK, REPLACE
    }
}
