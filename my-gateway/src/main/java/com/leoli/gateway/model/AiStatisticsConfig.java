package com.leoli.gateway.model;

import lombok.Data;

/**
 * AI observability configuration (Higress ai-statistics counterpart).
 * <p>
 * Lightweight-by-default parity with Higress' {@code use_default_response_attributes}:
 * model + token counters + durations only, no request/response content buffering
 * beyond what the token collector already performs.
 *
 * @author leoli
 */
@Data
public class AiStatisticsConfig {

    private boolean enabled = false;

    /** Micrometer counters/timers (tokens, service duration, first-token duration). */
    private boolean enableMetrics = true;

    /** Single structured ai_log info line per request (Higress log parity). */
    private boolean enableLog = true;

    /** Tag the current OTel span via micrometer-tracing when present. */
    private boolean enableSpanTags = true;

    /**
     * Header carrying a session id for multi-turn correlation (Higress
     * session_id_header); recorded in the ai_log line when present.
     */
    private String sessionIdHeader;
}
