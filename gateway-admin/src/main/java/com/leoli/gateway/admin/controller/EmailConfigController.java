package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.EmailConfig;
import com.leoli.gateway.admin.service.EmailConfigService;
import com.leoli.gateway.admin.service.EmailSenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API controller for email configuration.
 * Provides CRUD operations for SMTP settings stored in database.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/email")
@RequiredArgsConstructor
public class EmailConfigController {

    private final EmailSenderService emailSenderService;
    private final EmailConfigService emailConfigService;

    /**
     * Get all email configurations
     */
    @GetMapping("/configs")
    public ResponseEntity<ApiResponse<List<EmailConfig>>> getAllConfigs() {
        try {
            List<EmailConfig> configs = emailConfigService.getAllConfigs();
            // Mask passwords
            configs.forEach(c -> c.setSmtpPassword("******"));
            
            return ResponseEntity.ok(ApiResponse.success(configs));
        } catch (Exception e) {
            log.error("Failed to get email configs", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get configs: " + e.getMessage()));
        }
    }

    /**
     * Get current active SMTP configuration info (without password)
     */
    @GetMapping("/info")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSmtpInfo() {
        try {
            Map<String, Object> info = emailSenderService.getSmtpInfo();
            return ResponseEntity.ok(ApiResponse.success(info));
        } catch (Exception e) {
            log.error("Failed to get SMTP info", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Get email config by ID
     */
    @GetMapping("/configs/{id}")
    public ResponseEntity<ApiResponse<EmailConfig>> getConfigById(@PathVariable Long id) {
        try {
            Optional<EmailConfig> configOpt = emailConfigService.getConfigById(id);
            if (configOpt.isPresent()) {
                EmailConfig config = configOpt.get();
                config.setSmtpPassword("******"); // Mask password
                return ResponseEntity.ok(ApiResponse.success(config));
            } else {
                return ResponseEntity.ok(ApiResponse.notFound("Config not found"));
            }
        } catch (Exception e) {
            log.error("Failed to get email config", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get config: " + e.getMessage()));
        }
    }

    /**
     * Create new email configuration
     */
    @PostMapping("/configs")
    public ResponseEntity<ApiResponse<Map<String, Long>>> createConfig(@RequestBody EmailConfig config) {
        try {
            // Validate required fields
            if (config.getSmtpHost() == null || config.getSmtpHost().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.badRequest("SMTP host is required"));
            }
            if (config.getSmtpUsername() == null || config.getSmtpUsername().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.badRequest("SMTP username is required"));
            }
            if (config.getSmtpPassword() == null || config.getSmtpPassword().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.badRequest("SMTP password is required"));
            }

            EmailConfig saved = emailConfigService.saveConfig(config);
            
            // Reload email sender with new config
            emailSenderService.reloadConfig();
            
            log.info("Created email config: {}", saved.getId());
            return ResponseEntity.ok(ApiResponse.success(Map.of("id", saved.getId()), "Configuration created successfully"));
        } catch (Exception e) {
            log.error("Failed to create email config", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to create config: " + e.getMessage()));
        }
    }

    /**
     * Update email configuration
     */
    @PutMapping("/configs/{id}")
    public ResponseEntity<ApiResponse<Map<String, Long>>> updateConfig(
            @PathVariable Long id,
            @RequestBody EmailConfig config) {
        try {
            Optional<EmailConfig> existingOpt = emailConfigService.getConfigById(id);
            if (existingOpt.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.notFound("Config not found"));
            }

            EmailConfig existing = existingOpt.get();
            
            // Update fields
            if (config.getConfigName() != null) existing.setConfigName(config.getConfigName());
            if (config.getSmtpHost() != null) existing.setSmtpHost(config.getSmtpHost());
            if (config.getSmtpPort() != null) existing.setSmtpPort(config.getSmtpPort());
            if (config.getSmtpUsername() != null) existing.setSmtpUsername(config.getSmtpUsername());
            // Only update password if not masked
            if (config.getSmtpPassword() != null && !config.getSmtpPassword().equals("******")) {
                existing.setSmtpPassword(config.getSmtpPassword());
            }
            if (config.getFromEmail() != null) existing.setFromEmail(config.getFromEmail());
            if (config.getFromName() != null) existing.setFromName(config.getFromName());
            if (config.getUseSsl() != null) existing.setUseSsl(config.getUseSsl());
            if (config.getUseStartTls() != null) existing.setUseStartTls(config.getUseStartTls());
            if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());

            EmailConfig saved = emailConfigService.saveConfig(existing);
            
            // Reload email sender with updated config
            emailSenderService.reloadConfig();
            
            log.info("Updated email config: {}", saved.getId());
            return ResponseEntity.ok(ApiResponse.success(Map.of("id", saved.getId()), "Configuration updated successfully"));
        } catch (Exception e) {
            log.error("Failed to update email config", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to update config: " + e.getMessage()));
        }
    }

    /**
     * Delete email configuration
     */
    @DeleteMapping("/configs/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteConfig(@PathVariable Long id) {
        try {
            Optional<EmailConfig> configOpt = emailConfigService.getConfigById(id);
            if (configOpt.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.notFound("Config not found"));
            }

            emailConfigService.deleteConfig(id);
            
            // Reload email sender
            emailSenderService.reloadConfig();
            
            log.info("Deleted email config: {}", id);
            return ResponseEntity.ok(ApiResponse.success("Configuration deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete email config", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to delete config: " + e.getMessage()));
        }
    }

    /**
     * Send test email to specific address
     */
    @PostMapping("/test")
    public ResponseEntity<ApiResponse<Void>> sendTestEmail(@RequestBody Map<String, String> request) {
        try {
            String to = request.get("to");
            if (to == null || to.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.badRequest("Recipient email is required"));
            }

            boolean success = emailSenderService.sendEmail(
                to,
                "Gateway Alert Test",
                "<h2>Test Email from API Gateway</h2>" +
                "<p>This is a test email from API Gateway Alert System.</p>" +
                "<p>Time: " + new java.util.Date() + "</p>",
                true
            );

            // Update test status
            emailConfigService.getActiveConfig().ifPresent(config -> {
                emailConfigService.updateTestStatus(config.getId(), success, success ? null : "Send failed");
            });

            if (success) {
                return ResponseEntity.ok(ApiResponse.success("Test email sent to " + to));
            } else {
                return ResponseEntity.ok(ApiResponse.error("Failed to send email. Check SMTP configuration."));
            }
        } catch (Exception e) {
            log.error("Failed to send test email", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to send test email: " + e.getMessage()));
        }
    }

    /**
     * Check if email is configured
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        boolean configured = emailSenderService.isConfigured();
        Map<String, Object> data = new HashMap<>();
        data.put("configured", configured);
        return ResponseEntity.ok(ApiResponse.success(data, configured ? "Email is configured" : "Email is not configured"));
    }

    /**
     * Reload email configuration from database
     */
    @PostMapping("/reload")
    public ResponseEntity<ApiResponse<Void>> reloadConfig() {
        try {
            emailSenderService.reloadConfig();
            return ResponseEntity.ok(ApiResponse.success("Configuration reloaded successfully"));
        } catch (Exception e) {
            log.error("Failed to reload config", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to reload: " + e.getMessage()));
        }
    }
}