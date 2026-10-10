package com.leoli.gateway.model;

import lombok.Data;

/**
 * AI context-window limit configuration (Higress ai-context-limit counterpart).
 * <p>
 * Estimates the text input tokens of an OpenAI/Anthropic compatible request and
 * rejects it before it reaches the upstream model when the buffered estimate
 * exceeds the configured window, protecting both tenant quota and upstream
 * context limits.
 *
 * @author leoli
 */
@Data
public class AiContextLimitConfig {

    private boolean enabled = false;

    /**
     * Maximum context tokens. Requests whose buffered estimate exceeds this are
     * rejected. Values &lt;= 0 disable the check (fail-open).
     */
    private long maxContextTokens = 0;

    /**
     * Safety buffer multiplier applied to the estimate before comparing against
     * {@link #maxContextTokens} (Higress default 1.10).
     */
    private double bufferRatio = 1.10;

    /** HTTP status used for the rejection response (Higress default 400). */
    private int errorStatusCode = 400;

    /** Request bodies larger than this are not parsed; see filter for behaviour. */
    private long maxBodySize = 1048576;
}
