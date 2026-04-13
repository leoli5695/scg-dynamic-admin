package com.leoli.gateway.admin.prompt;

import com.leoli.gateway.admin.model.PromptEntity;
import com.leoli.gateway.admin.model.PromptVersionEntity;
import com.leoli.gateway.admin.repository.PromptRepository;
import com.leoli.gateway.admin.repository.PromptVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database-based prompt provider.
 * Primary implementation that loads prompts from database with version control.
 *
 * @author leoli
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DatabasePromptProvider implements PromptProvider {

    private final PromptRepository promptRepository;
    private final PromptVersionRepository promptVersionRepository;

    @Override
    public String getPrompt(String promptKey) {
        PromptEntity entity = promptRepository.findByPromptKey(promptKey).orElse(null);
        if (entity != null && Boolean.TRUE.equals(entity.getEnabled())) {
            return entity.getContent();
        }
        return null;
    }

    @Override
    public Map<String, String> getAllPrompts() {
        List<PromptEntity> entities = promptRepository.findByEnabledTrue();
        Map<String, String> prompts = new LinkedHashMap<>();
        for (PromptEntity entity : entities) {
            prompts.put(entity.getPromptKey(), entity.getContent());
        }
        return prompts;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean savePrompt(String promptKey, String content) {
        PromptEntity entity = promptRepository.findByPromptKey(promptKey).orElse(null);

        if (entity == null) {
            // Create new prompt
            entity = new PromptEntity();
            entity.setPromptKey(promptKey);
            entity.setCategory(extractCategory(promptKey));
            entity.setName(extractName(promptKey));
            entity.setLanguage(extractLanguage(promptKey));
            entity.setContent(content);
            entity.setVersion(1);
            entity.setEnabled(true);
            promptRepository.save(entity);

            // Save initial version history
            saveVersionHistory(entity.getId(), 1, content, "Initial creation");
            log.info("Created new prompt: {} with version 1", promptKey);
        } else {
            // Update existing prompt
            int oldVersion = entity.getVersion();
            int newVersion = oldVersion + 1;

            // Save old version to history
            saveVersionHistory(entity.getId(), oldVersion, entity.getContent(), "Before update to v" + newVersion);

            // Update entity
            entity.setContent(content);
            entity.setVersion(newVersion);
            promptRepository.save(entity);

            log.info("Updated prompt: {} from v{} to v{}", promptKey, oldVersion, newVersion);
        }

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deletePrompt(String promptKey) {
        PromptEntity entity = promptRepository.findByPromptKey(promptKey).orElse(null);
        if (entity == null) {
            return false;
        }

        // Delete version history first
        List<PromptVersionEntity> versions = promptVersionRepository.findByPromptIdOrderByVersionDesc(entity.getId());
        promptVersionRepository.deleteAll(versions);

        // Delete prompt
        promptRepository.delete(entity);
        log.info("Deleted prompt: {} with {} version history entries", promptKey, versions.size());

        return true;
    }

    @Override
    public boolean exists(String promptKey) {
        return promptRepository.existsByPromptKey(promptKey);
    }

    @Override
    public String getProviderType() {
        return "DATABASE";
    }

    @Override
    public boolean supportsWrite() {
        return true;
    }

    /**
     * Get prompt entity with metadata.
     */
    public PromptEntity getPromptEntity(String promptKey) {
        return promptRepository.findByPromptKey(promptKey).orElse(null);
    }

    /**
     * Get version history for a prompt.
     */
    public List<PromptVersionEntity> getVersionHistory(Long promptId, int limit) {
        List<PromptVersionEntity> allVersions = promptVersionRepository.findByPromptIdOrderByVersionDesc(promptId);
        if (limit > 0 && allVersions.size() > limit) {
            return allVersions.subList(0, limit);
        }
        return allVersions;
    }

    /**
     * Rollback prompt to specific version.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean rollbackToVersion(String promptKey, int targetVersion) {
        PromptEntity entity = promptRepository.findByPromptKey(promptKey).orElse(null);
        if (entity == null) {
            log.warn("Cannot rollback: prompt {} not found", promptKey);
            return false;
        }

        PromptVersionEntity versionEntity = promptVersionRepository
            .findByPromptIdAndVersion(entity.getId(), targetVersion)
            .orElse(null);

        if (versionEntity == null) {
            log.warn("Cannot rollback: version {} not found for prompt {}", targetVersion, promptKey);
            return false;
        }

        int currentVersion = entity.getVersion();
        int newVersion = currentVersion + 1;

        // Save current version to history before rollback
        saveVersionHistory(entity.getId(), currentVersion, entity.getContent(),
            "Before rollback to v" + targetVersion);

        // Restore content from target version
        entity.setContent(versionEntity.getContent());
        entity.setVersion(newVersion);
        promptRepository.save(entity);

        log.info("Rolled back prompt {} to v{} (now v{})", promptKey, targetVersion, newVersion);
        return true;
    }

    /**
     * Save version history entry.
     */
    private void saveVersionHistory(Long promptId, int version, String content, String changeNote) {
        PromptVersionEntity versionEntity = new PromptVersionEntity();
        versionEntity.setPromptId(promptId);
        versionEntity.setVersion(version);
        versionEntity.setContent(content);
        versionEntity.setChangeNote(changeNote);
        promptVersionRepository.save(versionEntity);
    }

    /**
     * Extract category from prompt key.
     * Format: {category}.{name}.{language}
     */
    private String extractCategory(String promptKey) {
        String[] parts = promptKey.split("\\.");
        return parts.length > 0 ? parts[0] : "unknown";
    }

    /**
     * Extract name from prompt key.
     */
    private String extractName(String promptKey) {
        String[] parts = promptKey.split("\\.");
        return parts.length > 1 ? parts[1] : "unknown";
    }

    /**
     * Extract language from prompt key.
     */
    private String extractLanguage(String promptKey) {
        String[] parts = promptKey.split("\\.");
        return parts.length > 2 ? parts[2] : "zh";
    }
}