package com.leoli.gateway.admin.prompt;

import java.util.Map;

/**
 * Prompt provider interface.
 * Supports multiple implementations: database, config center, static default.
 *
 * @author leoli
 */
public interface PromptProvider {

    /**
     * Get prompt content by key.
     * @param promptKey Prompt key (e.g., base.system.zh)
     * @return Prompt content, null if not found
     */
    String getPrompt(String promptKey);

    /**
     * Get all prompts.
     * @return Map of promptKey -> content
     */
    Map<String, String> getAllPrompts();

    /**
     * Save prompt content.
     * @param promptKey Prompt key
     * @param content Prompt content
     * @return true if saved successfully
     */
    boolean savePrompt(String promptKey, String content);

    /**
     * Delete prompt by key.
     * @param promptKey Prompt key
     * @return true if deleted successfully
     */
    boolean deletePrompt(String promptKey);

    /**
     * Check if prompt exists.
     * @param promptKey Prompt key
     * @return true if exists
     */
    boolean exists(String promptKey);

    /**
     * Get provider type name.
     * @return Provider type (DATABASE, STATIC)
     */
    String getProviderType();

    /**
     * Check if this provider supports write operations.
     * @return true if supports write
     */
    boolean supportsWrite();
}