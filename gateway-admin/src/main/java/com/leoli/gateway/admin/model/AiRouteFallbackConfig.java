package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * AI route fallback configuration - ordered failover targets tried after the primary
 * upstream when the provider returns a retryable error.
 * <p>
 * Mirrors Higress {@code AiRouteFallbackConfig}. On the ns/v3 data plane this projects
 * into {@code AiProxyConfig.fallbacks} (an ordered chain walked by the retry filter),
 * so the effective strategy is sequential; {@link #fallbackStrategy} is kept for parity
 * with the Higress model and future RANDOM support.
 *
 * @author leoli
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiRouteFallbackConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Whether fallback is enabled. */
    private Boolean enabled = false;

    /** Fallback upstreams, tried in order after the primary. */
    private List<AiUpstream> upstreams = new ArrayList<>();

    /** Fallback strategy: SEQ (ordered) or RANDOM. Defaults to SEQ. */
    private String fallbackStrategy = Strategy.SEQ;

    /** Supported fallback strategies. */
    public static final class Strategy {
        private Strategy() {
        }

        public static final String SEQ = "SEQ";
        public static final String RANDOM = "RANDOM";
    }
}
