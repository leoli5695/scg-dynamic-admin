package com.leoli.gateway.ai;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the built-in provider catalogue: ids, model-prefix routing and the
 * verified upstream URLs. A typo in any base URL / path fails here.
 */
class ProviderRegistryTest {

    @Test
    void byIdIsCaseInsensitiveAndAcceptsEnumName() {
        assertEquals(LlmProvider.DEEPSEEK, ProviderRegistry.byId("deepseek").orElseThrow());
        assertEquals(LlmProvider.QWEN, ProviderRegistry.byId("QWEN").orElseThrow());
        assertEquals(LlmProvider.MOONSHOT, ProviderRegistry.byId("moonshot").orElseThrow());
        assertEquals(LlmProvider.ANTHROPIC, ProviderRegistry.byId("Anthropic").orElseThrow());
    }

    @Test
    void byIdUnknownIsEmpty() {
        assertFalse(ProviderRegistry.byId("nope").isPresent());
        assertFalse(ProviderRegistry.byId(null).isPresent());
        assertFalse(ProviderRegistry.byId("  ").isPresent());
    }

    @Test
    void resolveByModelMatchesBuiltinPrefixes() {
        assertEquals(LlmProvider.OPENAI, ProviderRegistry.resolveByModel("gpt-4o").orElseThrow());
        assertEquals(LlmProvider.DEEPSEEK, ProviderRegistry.resolveByModel("deepseek-chat").orElseThrow());
        assertEquals(LlmProvider.QWEN, ProviderRegistry.resolveByModel("qwen-max").orElseThrow());
        assertEquals(LlmProvider.ZHIPU, ProviderRegistry.resolveByModel("glm-4-plus").orElseThrow());
        assertEquals(LlmProvider.MOONSHOT, ProviderRegistry.resolveByModel("kimi-k2").orElseThrow());
        assertEquals(LlmProvider.ANTHROPIC, ProviderRegistry.resolveByModel("claude-3-5-sonnet").orElseThrow());
    }

    @Test
    void resolveByModelUnknownIsEmpty() {
        assertFalse(ProviderRegistry.resolveByModel("some-unknown-model").isPresent());
        assertFalse(ProviderRegistry.resolveByModel(null).isPresent());
    }

    @Test
    void catalogueHasExactlySixBuiltinProviders() {
        assertEquals(6, ProviderRegistry.all().size());
    }

    @Test
    void chatUrlsMatchVerifiedEndpoints() {
        assertEquals("https://api.openai.com/v1/chat/completions",
                LlmProvider.OPENAI.resolveChatUrl(null));
        assertEquals("https://api.deepseek.com/chat/completions",
                LlmProvider.DEEPSEEK.resolveChatUrl(null));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                LlmProvider.QWEN.resolveChatUrl(null));
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                LlmProvider.ZHIPU.resolveChatUrl(null));
        assertEquals("https://api.moonshot.cn/v1/chat/completions",
                LlmProvider.MOONSHOT.resolveChatUrl(null));
        assertEquals("https://api.anthropic.com/v1/chat/completions",
                LlmProvider.ANTHROPIC.resolveChatUrl(null));
    }

    @Test
    void baseUrlOverrideWinsAndNormalizesSlashes() {
        assertEquals("https://relay.internal/deepseek/chat/completions",
                LlmProvider.DEEPSEEK.resolveChatUrl("https://relay.internal/deepseek/"));
        Optional<LlmProvider> p = ProviderRegistry.byId("deepseek");
        assertTrue(p.isPresent());
    }

    @Test
    void allBuiltinProvidersUseBearerAuth() {
        for (LlmProvider provider : ProviderRegistry.all()) {
            assertEquals("Authorization", provider.authHeaderName(),
                    provider.getId() + " should use Bearer auth in passthrough MVP");
            assertEquals(LlmProvider.Protocol.OPENAI_COMPAT, provider.getProtocol(),
                    provider.getId() + " should be OpenAI-compatible in passthrough MVP");
        }
    }

    @Test
    void matchesModelRejectsBlank() {
        assertFalse(LlmProvider.OPENAI.matchesModel(null));
        assertFalse(LlmProvider.OPENAI.matchesModel(""));
    }
}
