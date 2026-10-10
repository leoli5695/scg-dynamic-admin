package com.leoli.gateway.model;

import lombok.Data;

/**
 * AI Semantic Cache configuration (strategy type AI_SEMANTIC_CACHE).
 * <p>
 * Mirrors Higress ai-cache: vectorize the prompt, and on a sufficiently similar
 * cached prompt return the stored response without calling the LLM (saving tokens,
 * cost and latency). Embeddings are produced by calling an OpenAI-compatible
 * {@code /embeddings} endpoint via the gateway's own credential store; the vector
 * store is in-memory (per instance) for this MVP. Only non-streaming responses are
 * cached.
 *
 * @author leoli
 */
@Data
public class AiSemanticCacheConfig {

    private boolean enabled = false;

    /** Provider id used for the embeddings call (must be OpenAI-compatible). */
    private String embeddingProvider = "qwen";

    /** Embedding model name. */
    private String embeddingModel = "text-embedding-v3";

    /** Credential ref (provider_credentials) for the embeddings call. */
    private String embeddingCredentialRef;

    /** Optional base-url override for the embeddings endpoint (relay / self-host). */
    private String embeddingBaseUrlOverride;

    /** Cosine similarity threshold for a hit (0..1). Higher = stricter, fewer wrong hits. */
    private double similarityThreshold = 0.95;

    /** Cache entry TTL in seconds. */
    private long ttlSeconds = 3600;

    /** Scope entries by model (avoid cross-model hits). */
    private boolean scopeByModel = true;

    /** Scope entries by tenant (avoid cross-tenant leakage). */
    private boolean scopeByTenant = true;

    /** Cache streaming responses too (not supported in this MVP; kept for forward-compat). */
    private boolean cacheStreaming = false;

    /** Max request body buffered to read the prompt. */
    private long maxBodySize = 1048576;
}
