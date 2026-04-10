package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.EmailConfig;
import com.leoli.gateway.admin.repository.EmailConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Email configuration service.
 * Manages SMTP settings stored in database.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailConfigService {

    private final EmailConfigRepository emailConfigRepository;

    /**
     * Get all email configurations.
     */
    public List<EmailConfig> getAllConfigs() {
        return emailConfigRepository.findAll();
    }

    /**
     * Get email config by ID.
     */
    public Optional<EmailConfig> getConfigById(Long id) {
        return emailConfigRepository.findById(id);
    }

    /**
     * Get the active email configuration (first enabled config).
     */
    public Optional<EmailConfig> getActiveConfig() {
        List<EmailConfig> configs = emailConfigRepository.findByEnabledTrue();
        return configs.isEmpty() ? Optional.empty() : Optional.of(configs.get(0));
    }

    /**
     * Save email configuration.
     */
    @Transactional(rollbackFor = Exception.class)
    public EmailConfig saveConfig(EmailConfig config) {
        // If this config is enabled, disable others
        if (Boolean.TRUE.equals(config.getEnabled())) {
            emailConfigRepository.findByEnabled(true).forEach(c -> {
                if (!c.getId().equals(config.getId())) {
                    c.setEnabled(false);
                    emailConfigRepository.save(c);
                }
            });
        }

        return emailConfigRepository.save(config);
    }

    /**
     * Delete email configuration.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id) {
        emailConfigRepository.deleteById(id);
    }

    /**
     * Update test status.
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateTestStatus(Long id, boolean success, String error) {
        emailConfigRepository.findById(id).ifPresent(config -> {
            config.setTestStatus(success ? "SUCCESS" : "FAILED");
            config.setLastTestTime(java.time.LocalDateTime.now());
            config.setLastTestError(success ? null : error);
            emailConfigRepository.save(config);
        });
    }
}