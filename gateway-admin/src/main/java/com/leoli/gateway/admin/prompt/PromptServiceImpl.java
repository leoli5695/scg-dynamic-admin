package com.leoli.gateway.admin.prompt;

import com.leoli.gateway.admin.model.IntentConfigEntity;
import com.leoli.gateway.admin.model.PromptEntity;
import com.leoli.gateway.admin.model.PromptVersionEntity;
import com.leoli.gateway.admin.repository.IntentConfigRepository;
import com.leoli.gateway.admin.repository.PromptRepository;
import com.leoli.gateway.admin.repository.PromptVersionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt service implementation.
 * Provides unified prompt management with caching and multi-provider support.
 *
 * Architecture:
 * - Cache Layer: ConcurrentHashMap<String, CachedPrompt>
 * - Provider Chain: DatabasePromptProvider -> StaticPromptProvider
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptServiceImpl implements PromptService {

    private final List<PromptProvider> providers;
    private final PromptRepository promptRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final IntentConfigRepository intentConfigRepository;

    // Local cache with version control
    private final ConcurrentHashMap<String, CachedPrompt> cache = new ConcurrentHashMap<>();

    // Cache configuration
    private static final long CACHE_EXPIRE_MS = 60000;  // 1 minute expiration
    private static final int MAX_CACHE_SIZE = 200;

    // Cached prompt structure
    private static class CachedPrompt {
        String content;
        int version;
        long loadTime;
        String providerType;
    }

    @PostConstruct
    public void init() {
        loadAllToCache();
        log.info("PromptServiceImpl initialized with {} cached prompts", cache.size());
    }

    // ==================== Prompt Retrieval ====================

    @Override
    public String getPrompt(String promptKey) {
        // 1. Check cache
        CachedPrompt cached = cache.get(promptKey);
        if (cached != null && !isExpired(cached)) {
            return cached.content;
        }

        // 2. Load from provider chain
        for (PromptProvider provider : providers) {
            String content = provider.getPrompt(promptKey);
            if (content != null && !content.isEmpty()) {
                // Update cache
                updateCache(promptKey, content, provider.getProviderType());
                return content;
            }
        }

        return null;
    }

    @Override
    public String getPrompt(String promptKey, String defaultValue) {
        String content = getPrompt(promptKey);
        return content != null ? content : defaultValue;
    }

    @Override
    public Map<String, String> getPromptsByCategory(String category, String language) {
        Map<String, String> result = new LinkedHashMap<>();

        // From cache
        for (Map.Entry<String, CachedPrompt> entry : cache.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(category + ".")) {
                if (language != null && !key.endsWith("." + language)) {
                    continue;
                }
                result.put(key, entry.getValue().content);
            }
        }

        // From database if not in cache
        List<PromptEntity> entities = promptRepository.findByCategoryAndLanguageAndEnabledTrue(category, language);
        for (PromptEntity entity : entities) {
            result.put(entity.getPromptKey(), entity.getContent());
        }

        return result;
    }

    @Override
    public String buildSystemPrompt(String language, String context) {
        String baseKey = "base.system." + language;
        String basePrompt = getPrompt(baseKey);

        if (basePrompt == null) {
            log.warn("Base prompt not found for language: {}", language);
            return "";
        }

        StringBuilder sb = new StringBuilder(basePrompt);

        // Add domain prompt if context matches
        if (context != null && !context.isEmpty() && isValidIntent(context)) {
            String domainKey = "domain." + context + "." + language;
            String domainPrompt = getPrompt(domainKey);
            if (domainPrompt != null) {
                sb.append("\n\n").append(domainPrompt);
                log.debug("Added domain prompt for context: {}", context);
            }
        }

        return sb.toString();
    }

    // ==================== Prompt Management ====================

    @Override
    public boolean savePrompt(String promptKey, String content, String description) {
        // Find a writable provider
        for (PromptProvider provider : providers) {
            if (provider.supportsWrite()) {
                boolean success = provider.savePrompt(promptKey, content);
                if (success) {
                    // Update cache
                    updateCache(promptKey, content, provider.getProviderType());
                    log.info("Saved prompt {} via {}", promptKey, provider.getProviderType());
                    return true;
                }
            }
        }

        log.warn("No writable provider available for prompt: {}", promptKey);
        return false;
    }

    @Override
    public int savePromptsBatch(Map<String, String> prompts) {
        int count = 0;
        for (Map.Entry<String, String> entry : prompts.entrySet()) {
            if (savePrompt(entry.getKey(), entry.getValue(), null)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean deletePrompt(String promptKey) {
        for (PromptProvider provider : providers) {
            if (provider.supportsWrite() && provider.exists(promptKey)) {
                boolean success = provider.deletePrompt(promptKey);
                if (success) {
                    cache.remove(promptKey);
                    log.info("Deleted prompt {}", promptKey);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean togglePrompt(String promptKey, boolean enabled) {
        PromptEntity entity = promptRepository.findByPromptKey(promptKey).orElse(null);
        if (entity == null) {
            return false;
        }

        entity.setEnabled(enabled);
        promptRepository.save(entity);

        // Update or remove from cache
        if (enabled) {
            updateCache(promptKey, entity.getContent(), "DATABASE");
        } else {
            cache.remove(promptKey);
        }

        log.info("Toggled prompt {} to enabled={}", promptKey, enabled);
        return true;
    }

    // ==================== Version Management ====================

    @Override
    public List<PromptVersion> getVersionHistory(String promptKey, int limit) {
        PromptEntity entity = promptRepository.findByPromptKey(promptKey).orElse(null);
        if (entity == null) {
            return Collections.emptyList();
        }

        List<PromptVersionEntity> versions = promptVersionRepository.findByPromptIdOrderByVersionDesc(entity.getId());
        if (limit > 0 && versions.size() > limit) {
            versions = versions.subList(0, limit);
        }

        return versions.stream()
            .map(v -> new PromptVersion(
                v.getVersion(),
                v.getContent(),
                v.getChangeNote(),
                v.getCreatedAt(),
                v.getCreatedBy()
            ))
            .toList();
    }

    @Override
    public boolean rollbackToVersion(String promptKey, int version) {
        PromptEntity entity = promptRepository.findByPromptKey(promptKey).orElse(null);
        if (entity == null) {
            log.warn("Cannot rollback: prompt {} not found", promptKey);
            return false;
        }

        PromptVersionEntity versionEntity = promptVersionRepository
            .findByPromptIdAndVersion(entity.getId(), version)
            .orElse(null);

        if (versionEntity == null) {
            log.warn("Cannot rollback: version {} not found for prompt {}", version, promptKey);
            return false;
        }

        int currentVersion = entity.getVersion();
        int newVersion = currentVersion + 1;

        // Save current version to history
        PromptVersionEntity historyEntity = new PromptVersionEntity();
        historyEntity.setPromptId(entity.getId());
        historyEntity.setVersion(currentVersion);
        historyEntity.setContent(entity.getContent());
        historyEntity.setChangeNote("Before rollback to v" + version);
        promptVersionRepository.save(historyEntity);

        // Restore content
        entity.setContent(versionEntity.getContent());
        entity.setVersion(newVersion);
        promptRepository.save(entity);

        // Update cache
        updateCache(promptKey, versionEntity.getContent(), "DATABASE");

        log.info("Rolled back prompt {} to v{} (now v{})", promptKey, version, newVersion);
        return true;
    }

    // ==================== Intent Detection ====================

    @Override
    public IntentResult detectIntent(String userMessage) {
        Map<String, Integer> intentScores = new HashMap<>();

        // Load intent configs from database
        List<IntentConfigEntity> configs = intentConfigRepository.findByEnabledTrue();

        String lowerMessage = userMessage.toLowerCase();

        for (IntentConfigEntity config : configs) {
            String lowerKeyword = config.getKeyword().toLowerCase();
            if (lowerMessage.contains(lowerKeyword)) {
                // Check negation (simple check)
                if (!isKeywordNegated(lowerMessage, lowerKeyword)) {
                    intentScores.merge(config.getIntent(), config.getWeight(), Integer::sum);
                }
            }
        }

        // Apply combo rules
        for (IntentConfigEntity config : configs) {
            if ("COMBO_RULE".equals(config.getConfigType())) {
                // Combo rules stored as keyword1|keyword2 format
                String[] keywords = config.getKeyword().split("\\|");
                boolean allMatch = Arrays.stream(keywords)
                    .allMatch(k -> lowerMessage.contains(k.toLowerCase()));

                if (allMatch) {
                    intentScores.merge(config.getIntent(), config.getWeight(), Integer::sum);
                }
            }
        }

        // Find best intent
        if (intentScores.isEmpty()) {
            return new IntentResult("general", 0, false, true);
        }

        String bestIntent = intentScores.entrySet()
            .stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("general");

        int bestScore = intentScores.getOrDefault(bestIntent, 0);

        boolean isHighConfidence = bestScore >= 10;
        boolean needsAiRefinement = bestScore < 5;

        return new IntentResult(bestIntent, bestScore, isHighConfidence, needsAiRefinement);
    }

    private boolean isKeywordNegated(String message, String keyword) {
        String[] negationWords = {"不想", "不要", "别", "不是", "不需要", "don't", "not", "no", "without"};
        int index = message.indexOf(keyword.toLowerCase());
        if (index > 0) {
            int start = Math.max(0, index - 10);
            String prefix = message.substring(start, index);
            for (String negation : negationWords) {
                if (prefix.contains(negation.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isValidIntent(String intent) {
        // Check against domain prompts (including strategyTest from database)
        return getPromptsByCategory("domain", null).keySet().stream()
            .anyMatch(k -> k.contains(intent))
            || "strategyTest".equals(intent)  // 策略测试指南（数据库动态加载）
            || "podAnalysis".equals(intent)   // Pod维度分析（新增）
            || "pod_stress_test".equals(intent)  // Pod压测分析（新增）
            || "general".equals(intent)
            || "config".equals(intent);
    }

    @Override
    public List<String> getSupportedIntents() {
        // Get unique intents from domain prompts
        Set<String> intents = new HashSet<>();
        for (String key : getPromptsByCategory("domain", null).keySet()) {
            // Key format: domain.{intent}.{language}
            String[] parts = key.split("\\.");
            if (parts.length >= 2) {
                intents.add(parts[1]);
            }
        }
        intents.add("strategyTest");  // 策略测试指南（数据库动态加载）
        intents.add("podAnalysis");   // Pod维度分析（新增）
        intents.add("pod_stress_test");  // Pod压测分析（新增）
        intents.add("general");
        intents.add("config");
        return new ArrayList<>(intents);
    }

    // ==================== Cache Management ====================

    @Override
    public void refreshCache() {
        cache.clear();
        loadAllToCache();
        log.info("Cache refreshed, now contains {} prompts", cache.size());
    }

    @Override
    public void evictCache(String promptKey) {
        cache.remove(promptKey);
        log.debug("Cache evicted for key: {}", promptKey);
    }

    @Override
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cacheSize", cache.size());
        stats.put("maxSize", MAX_CACHE_SIZE);
        stats.put("expireMs", CACHE_EXPIRE_MS);
        stats.put("providerCount", providers.size());
        stats.put("providerTypes", providers.stream().map(PromptProvider::getProviderType).toList());
        return stats;
    }

    // ==================== Private Helper Methods ====================

    private void loadAllToCache() {
        // Load from database
        List<PromptEntity> entities = promptRepository.findByEnabledTrue();
        for (PromptEntity entity : entities) {
            updateCache(entity.getPromptKey(), entity.getContent(), "DATABASE");
        }

        // Load from static provider (fallback)
        for (PromptProvider provider : providers) {
            if ("STATIC".equals(provider.getProviderType())) {
                Map<String, String> staticPrompts = provider.getAllPrompts();
                for (Map.Entry<String, String> entry : staticPrompts.entrySet()) {
                    if (!cache.containsKey(entry.getKey())) {
                        updateCache(entry.getKey(), entry.getValue(), "STATIC");
                    }
                }
            }
        }
    }

    private void updateCache(String promptKey, String content, String providerType) {
        CachedPrompt cached = new CachedPrompt();
        cached.content = content;
        cached.version = 1;  // Default version for cache
        cached.loadTime = System.currentTimeMillis();
        cached.providerType = providerType;

        cache.put(promptKey, cached);
    }

    private boolean isExpired(CachedPrompt cached) {
        return System.currentTimeMillis() - cached.loadTime > CACHE_EXPIRE_MS;
    }
}