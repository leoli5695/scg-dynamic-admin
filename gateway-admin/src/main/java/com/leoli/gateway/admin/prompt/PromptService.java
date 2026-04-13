package com.leoli.gateway.admin.prompt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Unified prompt service interface.
 * Provides prompt management, version control, intent detection, and caching.
 *
 * @author leoli
 */
public interface PromptService {

    // ==================== Prompt Retrieval ====================

    /**
     * Get prompt content by key.
     * @param promptKey Prompt key (e.g., base.system.zh)
     * @return Prompt content, null if not found
     */
    String getPrompt(String promptKey);

    /**
     * Get prompt content with default value.
     * @param promptKey Prompt key
     * @param defaultValue Default value if not found
     * @return Prompt content or default value
     */
    String getPrompt(String promptKey, String defaultValue);

    /**
     * Get all prompts by category.
     * @param category Category (BASE, DOMAIN, TASK, etc.)
     * @param language Language (zh, en)
     * @return Map of promptKey -> content
     */
    Map<String, String> getPromptsByCategory(String category, String language);

    /**
     * Build complete system prompt (base + domain).
     * @param language Language (zh, en)
     * @param context Intent/context for domain selection
     * @return Complete system prompt
     */
    String buildSystemPrompt(String language, String context);

    // ==================== Prompt Management ====================

    /**
     * Create or update prompt.
     * @param promptKey Prompt key
     * @param content Prompt content
     * @param description Description
     * @return true if saved successfully
     */
    boolean savePrompt(String promptKey, String content, String description);

    /**
     * Batch save prompts.
     * @param prompts Map of promptKey -> content
     * @return Number of prompts saved
     */
    int savePromptsBatch(Map<String, String> prompts);

    /**
     * Delete prompt by key.
     * @param promptKey Prompt key
     * @return true if deleted successfully
     */
    boolean deletePrompt(String promptKey);

    /**
     * Enable or disable prompt.
     * @param promptKey Prompt key
     * @param enabled Enable status
     * @return true if operation successful
     */
    boolean togglePrompt(String promptKey, boolean enabled);

    // ==================== Version Management ====================

    /**
     * Get version history for a prompt.
     * @param promptKey Prompt key
     * @param limit Maximum number of versions to return
     * @return List of version history
     */
    List<PromptVersion> getVersionHistory(String promptKey, int limit);

    /**
     * Rollback prompt to specified version.
     * @param promptKey Prompt key
     * @param version Target version number
     * @return true if rollback successful
     */
    boolean rollbackToVersion(String promptKey, int version);

    // ==================== Intent Detection ====================

    /**
     * Detect user intent from message.
     * @param userMessage User message
     * @return Intent detection result
     */
    IntentResult detectIntent(String userMessage);

    /**
     * Check if intent is valid/supported.
     * @param intent Intent name
     * @return true if valid
     */
    boolean isValidIntent(String intent);

    /**
     * Get all supported intent types.
     * @return List of supported intents
     */
    List<String> getSupportedIntents();

    // ==================== Cache Management ====================

    /**
     * Refresh cache (reload all prompts).
     */
    void refreshCache();

    /**
     * Evict cache for specific prompt.
     * @param promptKey Prompt key
     */
    void evictCache(String promptKey);

    /**
     * Get cache statistics.
     * @return Cache stats (size, hit rate, etc.)
     */
    Map<String, Object> getCacheStats();

    // ==================== Data Models ====================

    /**
     * Prompt version record.
     */
    record PromptVersion(
        int version,
        String content,
        String changeNote,
        LocalDateTime createdAt,
        String createdBy
    ) {}

    /**
     * Intent detection result.
     */
    record IntentResult(
        String intent,
        int score,
        boolean isHighConfidence,
        boolean needsAiRefinement
    ) {}
}