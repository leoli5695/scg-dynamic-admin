package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * AI route upstream - binds an AI route to one AI service (LLM provider).
 * <p>
 * Mirrors Higress {@code com.alibaba.higress.sdk.model.ai.AiUpstream}: the upstream
 * references a provider by name (here the AI {@link ServiceDefinition#getName() service name}),
 * carries a weight for multi-upstream load balancing and a per-upstream model mapping.
 *
 * @author leoli
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiUpstream implements Serializable {

    private static final long serialVersionUID = 1L;

    /** AI service name (the bound LLM provider service). Required. */
    private String provider;

    /** Weight for load balancing across multiple upstreams (1-100). */
    private Integer weight = 100;

    /** Requested model -> upstream model rewrites for this upstream. */
    private Map<String, String> modelMapping = new HashMap<>();
}
