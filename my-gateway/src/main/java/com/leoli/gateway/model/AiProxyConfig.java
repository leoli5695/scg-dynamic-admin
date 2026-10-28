package com.leoli.gateway.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * AI Proxy strategy configuration (strategy type {@code AI_PROXY}).
 * <p>
 * Turns a northbound OpenAI-compatible endpoint into a multi-provider LLM proxy:
 * the requested {@code model} selects the upstream provider, the gateway injects
 * that provider's credential and rewrites the upstream URL. Wire protocol stays
 * OpenAI-compatible for passthrough (no body transform) in the MVP.
 * <p>
 * Credentials are referenced by id ({@code providerApiKeyRefs}), never stored in
 * plaintext here - the actual key lives in the admin credential store and is
 * fetched by the gateway at runtime.
 *
 * @author leoli
 */
@Data
public class AiProxyConfig {

    private boolean enabled = false;

    /** Service-level targets. When present these take precedence over legacy provider maps. */
    private java.util.List<Upstream> upstreams = new java.util.ArrayList<>();

    @Data
    public static class Upstream {
        private String serviceId;
        private String provider;
        private String credentialRef;
        private String baseUrlOverride;
        private int weight = 100;
        private Map<String, String> modelMapping = new HashMap<>();
    }

    // ============== Provider selection ==============

    /**
     * How the upstream provider is chosen:
     * MODEL  - infer from the request body's {@code model} field (default)
     * FIXED  - always route to {@link #fixedProvider}
     * HEADER - read the provider id from {@link #providerHeader}
     */
    private String providerSelection = "MODEL";

    /** Provider id used when {@link #providerSelection} is FIXED. */
    private String fixedProvider;

    /** Header carrying the provider id when {@link #providerSelection} is HEADER. */
    private String providerHeader = "X-Provider";

    // ============== Model routing & mapping ==============

    /**
     * Explicit model -> provider id overrides, consulted before prefix inference.
     * Example: {"my-alias": "deepseek"}
     */
    private Map<String, String> modelRouting = new HashMap<>();

    /**
     * Requested model -> upstream model name rewrites.
     * Example: {"gpt-4o": "qwen-max"} lets one client model hit another provider.
     */
    private Map<String, String> modelMapping = new HashMap<>();

    /** Model to assume when the request omits one and selection is MODEL. */
    private String defaultModel;

    // ============== Credentials ==============

    /**
     * provider id -> credential reference id (points at the admin credential store).
     * The plaintext key is never part of this config.
     */
    private Map<String, String> providerApiKeyRefs = new HashMap<>();

    // ============== Endpoint overrides ==============

    /**
     * provider id -> base URL override, for relays / egress proxies / self-hosted
     * OpenAI-compatible backends. Falls back to the built-in default when absent.
     */
    private Map<String, String> providerBaseUrlOverride = new HashMap<>();

    // ============== Metering integration ==============

    /**
     * Response usage format fed to the token-usage collector: OPENAI, ANTHROPIC, CUSTOM.
     * Defaults to OPENAI since all built-in providers are reached via their
     * OpenAI-compatible endpoint.
     */
    private String responseFormat = "OPENAI";

    // ============== Request handling ==============

    /** Strip the client's inbound Authorization header before injecting the provider key. */
    private boolean stripClientAuth = true;

    /** Max request body buffered to read the {@code model} field. */
    private long maxBodySize = 1048576;

    // ============== Failover ==============

    /**
     * Ordered failover targets tried after the primary (model-resolved) provider.
     * <p>
     * Two triggers, two mechanisms:
     * <ul>
     *   <li><b>Unresolvable credential</b> (revoked/expired/deleted ref): walked past
     *       IMMEDIATELY inside AiProviderRoutingFilter, no RETRY strategy required - a
     *       config-level failure will not heal after a backoff, so the next target's
     *       credential is tried at once.</li>
     *   <li><b>Transient downstream error</b> (429/5xx returned by the provider): fails over
     *       via the route's RETRY strategy - bind one with maxAttempts = 1 + fallbacks.size()
     *       and retryOnStatusCodes covering 429/5xx; RetryGlobalFilter re-invokes the router,
     *       which advances to the next target. Streaming can only fail over before the first byte.</li>
     * </ul>
     */
    private java.util.List<FallbackTarget> fallbacks = new java.util.ArrayList<>();

    /**
     * Per-provider credential pools with load balancing across multiple keys
     * (Higress ai-load-balancer counterpart at credential level). When a pool
     * exists for a provider it takes precedence over {@link #providerApiKeyRefs}.
     */
    private java.util.Map<String, KeyPool> providerKeyPools = new java.util.HashMap<>();

    /** Credential pool for one provider. */
    @Data
    public static class KeyPool {
        /** Credential refs in selection order. */
        private java.util.List<String> credentialRefs = new java.util.ArrayList<>();
        /** ROUND_ROBIN (default) or LEAST_INFLIGHT (local ongoing-request count). */
        private String strategy = "ROUND_ROBIN";
    }

    /** Provider selection modes. */
    public enum Selection {
        MODEL, FIXED, HEADER
    }

    /** One failover target in the ordered chain. */
    @Data
    public static class FallbackTarget {
        private String serviceId;
        private Map<String, String> modelMapping = new HashMap<>();
        /** Provider id (deepseek/qwen/zhipu/moonshot/openai/anthropic/custom). */
        private String provider;
        /** Upstream model for this target; defaults to the requested model. */
        private String model;
        /** Credential ref; defaults to providerApiKeyRefs[provider] when omitted. */
        private String credentialRef;
        /** Optional base-url override (relay / self-hosted) for this target. */
        private String baseUrlOverride;
    }
}
