package com.leoli.gateway.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Lookup facade over the built-in {@link LlmProvider} catalogue.
 * <p>
 * Resolution order used by the AI routing filter:
 * <ol>
 *   <li>explicit provider id (strategy config or {@code X-Provider} header)</li>
 *   <li>model-name prefix match against the built-in catalogue</li>
 * </ol>
 * Custom / self-hosted providers are expressed as a base-url override on a known
 * protocol rather than a new enum constant, so they are handled at config level.
 *
 * @author leoli
 */
public final class ProviderRegistry {

    private ProviderRegistry() {
    }

    public static List<LlmProvider> all() {
        return Arrays.asList(LlmProvider.values());
    }

    /** Case-insensitive lookup by provider id (e.g. "deepseek", "QWEN"). */
    public static Optional<LlmProvider> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String needle = id.trim();
        for (LlmProvider provider : LlmProvider.values()) {
            if (provider.getId().equalsIgnoreCase(needle)
                    || provider.name().equalsIgnoreCase(needle)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /**
     * Infer the provider from a requested model name via prefix match.
     * Deterministic: follows enum declaration order on ties.
     */
    public static Optional<LlmProvider> resolveByModel(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        for (LlmProvider provider : LlmProvider.values()) {
            if (provider.matchesModel(model)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
