package com.leoli.gateway.ai;

import com.leoli.gateway.model.AiProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Credential pool selection (Higress ai-load-balancer counterpart): round-robin
 * rotation, least-inflight pick, release floor, and legacy single-ref fallback.
 */
class ProviderKeyPoolSelectorTest {

    private ProviderKeyPoolSelector selector;

    @BeforeEach
    void setUp() {
        selector = new ProviderKeyPoolSelector();
    }

    private AiProxyConfig configWithPool(String strategy, String... refs) {
        AiProxyConfig config = new AiProxyConfig();
        AiProxyConfig.KeyPool pool = new AiProxyConfig.KeyPool();
        pool.setStrategy(strategy);
        pool.setCredentialRefs(List.of(refs));
        config.setProviderKeyPools(Map.of("qwen", pool));
        return config;
    }

    @Test
    @DisplayName("Round-robin rotates over the pool and wraps around")
    void roundRobinRotates() {
        AiProxyConfig config = configWithPool("ROUND_ROBIN", "ref-a", "ref-b", "ref-c");
        assertEquals("ref-a", selector.selectRef("qwen", config));
        assertEquals("ref-b", selector.selectRef("qwen", config));
        assertEquals("ref-c", selector.selectRef("qwen", config));
        assertEquals("ref-a", selector.selectRef("qwen", config));
    }

    @Test
    @DisplayName("Least-inflight picks the ref with fewest ongoing requests")
    void leastInflightPicksMin() {
        AiProxyConfig config = configWithPool("LEAST_INFLIGHT", "ref-a", "ref-b");
        selector.acquire("qwen", "ref-a", config);
        selector.acquire("qwen", "ref-a", config);
        assertEquals("ref-b", selector.selectRef("qwen", config));
        assertEquals(2, selector.inflight("ref-a"));
        assertEquals(0, selector.inflight("ref-b"));
    }

    @Test
    @DisplayName("Release decrements and never goes negative")
    void releaseFloorsAtZero() {
        AiProxyConfig config = configWithPool("LEAST_INFLIGHT", "ref-a", "ref-b");
        selector.acquire("qwen", "ref-a", config);
        selector.release("qwen", "ref-a", config);
        selector.release("qwen", "ref-a", config);
        assertEquals(0, selector.inflight("ref-a"));
    }

    @Test
    @DisplayName("Acquire/release are no-ops for ROUND_ROBIN pools and legacy refs")
    void acquireOnlyForLeastInflight() {
        AiProxyConfig rr = configWithPool("ROUND_ROBIN", "ref-a", "ref-b");
        selector.acquire("qwen", "ref-a", rr);
        assertEquals(0, selector.inflight("ref-a"));

        AiProxyConfig legacy = new AiProxyConfig();
        legacy.setProviderApiKeyRefs(Map.of("qwen", "ref-legacy"));
        selector.acquire("qwen", "ref-legacy", legacy);
        assertEquals(0, selector.inflight("ref-legacy"));
    }

    @Test
    @DisplayName("Without a pool the legacy providerApiKeyRefs entry is used")
    void legacyFallback() {
        AiProxyConfig config = new AiProxyConfig();
        config.setProviderApiKeyRefs(Map.of("qwen", "ref-legacy"));
        assertEquals("ref-legacy", selector.selectRef("qwen", config));
        assertNull(selector.selectRef("deepseek", config));
    }

    @Test
    @DisplayName("Pool takes precedence over the legacy single ref")
    void poolWinsOverLegacy() {
        AiProxyConfig config = configWithPool("ROUND_ROBIN", "ref-pool");
        config.setProviderApiKeyRefs(Map.of("qwen", "ref-legacy"));
        assertEquals("ref-pool", selector.selectRef("qwen", config));
    }

    @Test
    @DisplayName("Blank refs are skipped and an empty pool falls back to legacy")
    void blankRefsSkipped() {
        AiProxyConfig config = configWithPool("ROUND_ROBIN", " ", "ref-b");
        config.setProviderApiKeyRefs(Map.of("qwen", "ref-legacy"));
        assertEquals("ref-b", selector.selectRef("qwen", config));

        AiProxyConfig emptyPool = configWithPool("ROUND_ROBIN");
        emptyPool.setProviderApiKeyRefs(Map.of("qwen", "ref-legacy"));
        assertEquals("ref-legacy", selector.selectRef("qwen", emptyPool));
    }
}
