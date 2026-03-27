package com.leoli.gateway.admin.controller;

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
    public ResponseEntity<Map<String, Object>> getAllConfigs() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<EmailConfig> configs = emailConfigService.getAllConfigs();
            // Mask passwords
            configs.forEach(c -> c.setSmtpPassword("******"));
            
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", configs);
        } catch (Exception e) {
            log.error("Failed to get email configs", e);
            result.put("code", 500);
            result.put("message", "Failed to get configs: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Get current active SMTP configuration info (without password)
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getSmtpInfo() {
        try {
            Map<String, Object> info = emailSenderService.getSmtpInfo();
            info.put("code", 200);
            info.put("message", "success");
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            log.error("Failed to get SMTP info", e);
            return ResponseEntity.ok(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    /**
     * Get email config by ID
     */
    @GetMapping("/configs/{id}")
    public ResponseEntity<Map<String, Object>> getConfigById(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            Optional<EmailConfig> configOpt = emailConfigService.getConfigById(id);
            if (configOpt.isPresent()) {
                EmailConfig config = configOpt.get();
                config.setSmtpPassword("******"); // Mask password
                
                result.put("code", 200);
                result.put("message", "success");
                result.put("data", config);
            } else {
                result.put("code", 404);
                result.put("message", "Config not found");
            }
        } catch (Exception e) {
            log.error("Failed to get email config", e);
            result.put("code", 500);
            result.put("message", "Failed to get config: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Create new email configuration
     */
    @PostMapping("/configs")
    public ResponseEntity<Map<String, Object>> createConfig(@RequestBody EmailConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            // Validate required fields
            if (config.getSmtpHost() == null || config.getSmtpHost().isEmpty()) {
                result.put("code", 400);
                result.put("message", "SMTP host is required");
                return ResponseEntity.ok(result);
            }
            if (config.getSmtpUsername() == null || config.getSmtpUsername().isEmpty()) {
                result.put("code", 400);
                result.put("message", "SMTP username is required");
                return ResponseEntity.ok(result);
            }
            if (config.getSmtpPassword() == null || config.getSmtpPassword().isEmpty()) {
                result.put("code", 400);
                result.put("message", "SMTP password is required");
                return ResponseEntity.ok(result);
            }

            EmailConfig saved = emailConfigService.saveConfig(config);
            
            // Reload email sender with new config
            emailSenderService.reloadConfig();
            
            result.put("code", 200);
            result.put("message", "Configuration created successfully");
            result.put("data", Map.of("id", saved.getId()));
            
            log.info("Created email config: {}", saved.getId());
        } catch (Exception e) {
            log.error("Failed to create email config", e);
            result.put("code", 500);
            result.put("message", "Failed to create config: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Update email configuration
     */
    @PutMapping("/configs/{id}")
    public ResponseEntity<Map<String, Object>> updateConfig(
            @PathVariable Long id,
            @RequestBody EmailConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            Optional<EmailConfig> existingOpt = emailConfigService.getConfigById(id);
            if (existingOpt.isEmpty()) {
                result.put("code", 404);
                result.put("message", "Config not found");
                return ResponseEntity.ok(result);
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
            
            result.put("code", 200);
            result.put("message", "Configuration updated successfully");
            result.put("data", Map.of("id", saved.getId()));
            
            log.info("Updated email config: {}", saved.getId());
        } catch (Exception e) {
            log.error("Failed to update email config", e);
            result.put("code", 500);
            result.put("message", "Failed to update config: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Delete email configuration
     */
    @DeleteMapping("/configs/{id}")
    public ResponseEntity<Map<String, Object>> deleteConfig(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            Optional<EmailConfig> configOpt = emailConfigService.getConfigById(id);
            if (configOpt.isEmpty()) {
                result.put("code", 404);
                result.put("message", "Config not found");
                return ResponseEntity.ok(result);
            }

            emailConfigService.deleteConfig(id);
            
            // Reload email sender
            emailSenderService.reloadConfig();
            
            result.put("code", 200);
            result.put("message", "Configuration deleted successfully");
            
            log.info("Deleted email config: {}", id);
        } catch (Exception e) {
            log.error("Failed to delete email config", e);
            result.put("code", 500);
            result.put("message", "Failed to delete config: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Send test email to specific address
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> sendTestEmail(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        try {
            String to = request.get("to");
            if (to == null || to.isEmpty()) {
                result.put("code", 400);
                result.put("message", "Recipient email is required");
                return ResponseEntity.ok(result);
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
                result.put("code", 200);
                result.put("message", "Test email sent to " + to);
            } else {
                result.put("code", 500);
                result.put("message", "Failed to send email. Check SMTP configuration.");
            }
        } catch (Exception e) {
            log.error("Failed to send test email", e);
            result.put("code", 500);
            result.put("message", "Failed to send test email: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Check if email is configured
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean configured = emailSenderService.isConfigured();
        return ResponseEntity.ok(Map.of(
            "code", 200,
            "configured", configured,
            "message", configured ? "Email is configured" : "Email is not configured"
        ));
    }

    /**
     * Reload email configuration from database
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadConfig() {
        Map<String, Object> result = new HashMap<>();
        try {
            emailSenderService.reloadConfig();
            result.put("code", 200);
            result.put("message", "Configuration reloaded successfully");
        } catch (Exception e) {
            log.error("Failed to reload config", e);
            result.put("code", 500);
            result.put("message", "Failed to reload: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }
}