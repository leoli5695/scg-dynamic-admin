package com.leoli.gateway.ai;

import lombok.Getter;

/**
 * Built-in LLM provider registry (the "hardcoded" catalogue the AI proxy routes to).
 * <p>
 * Each entry bakes in the provider's public base URL, chat-completions path, wire
 * protocol and auth scheme, so an operator only supplies an API key. Every field is
 * overridable at strategy-config level (see AiProxyConfig) to support relays,
 * egress proxies and self-hosted OpenAI-compatible backends.
 * <p>
 * Base URLs for DeepSeek / Qwen / Zhipu / Moonshot were verified against the
 * vendors' official docs; OpenAI / Anthropic are stable public values.
 *
 * @author leoli
 */
@Getter
public enum LlmProvider {

    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", "/chat/completions",
            Protocol.OPENAI_COMPAT, AuthScheme.BEARER,
            new String[]{"gpt-", "o1", "o3", "o4", "chatgpt-"}),

    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "/chat/completions",
            Protocol.OPENAI_COMPAT, AuthScheme.BEARER,
            new String[]{"deepseek-"}),

    QWEN("qwen", "通义千问/百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "/chat/completions",
            Protocol.OPENAI_COMPAT, AuthScheme.BEARER,
            new String[]{"qwen", "qwq"}),

    ZHIPU("zhipu", "智谱GLM", "https://open.bigmodel.cn/api/paas/v4", "/chat/completions",
            Protocol.OPENAI_COMPAT, AuthScheme.BEARER,
            new String[]{"glm-", "chatglm", "cogview", "cogvideo"}),

    MOONSHOT("moonshot", "月之暗面/Kimi", "https://api.moonshot.cn/v1", "/chat/completions",
            Protocol.OPENAI_COMPAT, AuthScheme.BEARER,
            new String[]{"kimi-", "moonshot-"}),

    // Anthropic's OpenAI-compatibility endpoint: passthrough works without a
    // request/response transform. Native /v1/messages (x-api-key + anthropic-version)
    // is a later protocol-transform target.
    ANTHROPIC("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", "/chat/completions",
            Protocol.OPENAI_COMPAT, AuthScheme.BEARER,
            new String[]{"claude-"});

    private final String id;
    private final String displayName;
    private final String defaultBaseUrl;
    private final String defaultPath;
    private final Protocol protocol;
    private final AuthScheme authScheme;
    private final String[] defaultModelPrefixes;

    LlmProvider(String id, String displayName, String defaultBaseUrl, String defaultPath,
                Protocol protocol, AuthScheme authScheme, String[] defaultModelPrefixes) {
        this.id = id;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultPath = defaultPath;
        this.protocol = protocol;
        this.authScheme = authScheme;
        this.defaultModelPrefixes = defaultModelPrefixes;
    }

    /**
     * Build the absolute chat-completions URL, honouring an optional base-url
     * override (relay / egress proxy / self-hosted backend).
     */
    public String resolveChatUrl(String baseUrlOverride) {
        String base = (baseUrlOverride != null && !baseUrlOverride.isBlank())
                ? baseUrlOverride.trim()
                : defaultBaseUrl;
        return joinUrl(base, defaultPath);
    }

    /** Header name used to carry the upstream credential. */
    public String authHeaderName() {
        return authScheme == AuthScheme.X_API_KEY ? "x-api-key" : "Authorization";
    }

    /** Whether the model name looks like it belongs to this provider (prefix match). */
    public boolean matchesModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String lower = model.trim().toLowerCase();
        for (String prefix : defaultModelPrefixes) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String joinUrl(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    /** Wire protocol spoken by the provider's chat endpoint. */
    public enum Protocol {
        /** OpenAI-compatible /v1/chat/completions shape - passthrough, no transform. */
        OPENAI_COMPAT,
        /** Anthropic native /v1/messages shape - requires request/response transform. */
        ANTHROPIC,
        /** Vendor-specific shape with configurable field mapping. */
        CUSTOM
    }

    /** How the upstream API key is transmitted. */
    public enum AuthScheme {
        /** {@code Authorization: Bearer <key>} */
        BEARER,
        /** {@code x-api-key: <key>} */
        X_API_KEY,
        /** Provider-specific auth (e.g. signed tokens). */
        CUSTOM
    }
}
