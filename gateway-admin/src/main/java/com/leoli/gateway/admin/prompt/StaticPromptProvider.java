package com.leoli.gateway.admin.prompt;

import com.leoli.gateway.admin.service.AiCopilotPrompts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static prompt provider - default fallback implementation.
 * Migrates existing static constants from AiCopilotPrompts.
 * 
 * This provider serves as the fallback when database has no prompt data.
 * It does NOT support write operations - prompts must be managed through database.
 *
 * PromptKey format: {category}.{name}.{language}
 * 
 * @author leoli
 */
@Slf4j
@Service
@Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)  // Lowest priority - fallback only
public class StaticPromptProvider implements PromptProvider {

    private final Map<String, String> staticPrompts = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        // Only register base system prompts as fallback
        // All other prompts are managed in database
        staticPrompts.put("base.system.zh", AiCopilotPrompts.getBasePromptZh());
        staticPrompts.put("base.system.en", AiCopilotPrompts.getBasePromptEn());
        
        log.info("StaticPromptProvider initialized with {} prompts (minimal fallback)", staticPrompts.size());
    }

    @Override
    public String getPrompt(String promptKey) {
        return staticPrompts.get(promptKey);
    }

    @Override
    public Map<String, String> getAllPrompts() {
        return new LinkedHashMap<>(staticPrompts);
    }

    @Override
    public boolean savePrompt(String promptKey, String content) {
        // Static provider does NOT support write operations
        log.warn("StaticPromptProvider does not support savePrompt operation");
        return false;
    }

    @Override
    public boolean deletePrompt(String promptKey) {
        // Static provider does NOT support delete operations
        log.warn("StaticPromptProvider does not support deletePrompt operation");
        return false;
    }

    @Override
    public boolean exists(String promptKey) {
        return staticPrompts.containsKey(promptKey);
    }

    @Override
    public String getProviderType() {
        return "STATIC";
    }

    @Override
    public boolean supportsWrite() {
        return false;  // Static implementation does NOT support write
    }

    /**
     * Get all prompt keys for listing.
     */
    public java.util.Set<String> getPromptKeys() {
        return staticPrompts.keySet();
    }
}