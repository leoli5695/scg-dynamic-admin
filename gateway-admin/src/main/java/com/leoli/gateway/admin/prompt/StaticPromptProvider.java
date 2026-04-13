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
        registerBasePrompts();
        registerDomainPrompts();
        registerTemplates();
        registerKnowledge();
        registerTaskPrompts();
        
        log.info("StaticPromptProvider initialized with {} prompts", staticPrompts.size());
    }

    private void registerBasePrompts() {
        // Base system prompts
        staticPrompts.put("base.system.zh", AiCopilotPrompts.getBasePromptZh());
        staticPrompts.put("base.system.en", AiCopilotPrompts.getBasePromptEn());
    }

    private void registerDomainPrompts() {
        // Domain prompts - Chinese
        Map<String, String> domainZh = AiCopilotPrompts.getDomainPromptsZh();
        if (domainZh != null) {
            for (Map.Entry<String, String> entry : domainZh.entrySet()) {
                staticPrompts.put("domain." + entry.getKey() + ".zh", entry.getValue());
            }
        }

        // Domain prompts - English
        Map<String, String> domainEn = AiCopilotPrompts.getDomainPromptsEn();
        if (domainEn != null) {
            for (Map.Entry<String, String> entry : domainEn.entrySet()) {
                staticPrompts.put("domain." + entry.getKey() + ".en", entry.getValue());
            }
        }
    }

    private void registerTemplates() {
        // Output templates
        staticPrompts.put("template.performance.zh", AiCopilotPrompts.getPerformanceOutputTemplateZh());
    }

    private void registerKnowledge() {
        // Knowledge base
        staticPrompts.put("knowledge.filter.zh", AiCopilotPrompts.getFilterOptimizationKnowledgeZh());
    }

    private void registerTaskPrompts() {
        // Task-specific prompts will be migrated later when we refactor AiCopilotService
        // For now, these are kept as inline prompts in AiCopilotService
        // TODO: Migrate analyzeError, generateRoute, suggestOptimizations prompts here
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