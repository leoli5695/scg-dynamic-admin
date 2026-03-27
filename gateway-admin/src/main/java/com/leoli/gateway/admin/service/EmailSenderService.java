package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.EmailConfig;
import com.leoli.gateway.admin.model.EmailSendResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for sending emails.
 * Supports both application.yml config and database config.
 * Database config takes precedence if available.
 *
 * @author leoli
 */
@Slf4j
@Service
public class EmailSenderService {

    @Value("${mail.smtp.host:}")
    private String defaultSmtpHost;

    @Value("${mail.smtp.port:465}")
    private int defaultSmtpPort;

    @Value("${mail.smtp.username:}")
    private String defaultSmtpUsername;

    @Value("${mail.smtp.password:}")
    private String defaultSmtpPassword;

    @Value("${mail.smtp.from-email:}")
    private String defaultFromEmail;

    @Value("${mail.smtp.from-name:API Gateway}")
    private String defaultFromName;

    @Value("${mail.smtp.use-ssl:true}")
    private boolean defaultUseSsl;

    private final EmailConfigService emailConfigService;
    
    private final AtomicReference<JavaMailSender> mailSenderRef = new AtomicReference<>();
    private final AtomicReference<EmailConfig> currentConfigRef = new AtomicReference<>();

    public EmailSenderService(EmailConfigService emailConfigService) {
        this.emailConfigService = emailConfigService;
    }

    @PostConstruct
    public void init() {
        // Try to load from database first
        reloadConfig();
        log.info("EmailSenderService initialized");
    }

    /**
     * Reload email configuration from database or application.yml
     */
    public void reloadConfig() {
        EmailConfig config = emailConfigService.getActiveConfig().orElse(null);
        
        if (config != null && isValidConfig(config)) {
            currentConfigRef.set(config);
            mailSenderRef.set(createMailSenderFromConfig(config));
            log.info("Loaded email config from database: {}:{}", config.getSmtpHost(), config.getSmtpPort());
        } else if (isDefaultConfigured()) {
            // Fallback to application.yml config
            EmailConfig defaultConfig = createDefaultConfig();
            currentConfigRef.set(defaultConfig);
            mailSenderRef.set(createMailSenderFromConfig(defaultConfig));
            log.info("Using email config from application.yml: {}:{}", defaultSmtpHost, defaultSmtpPort);
            
            // Save default config to database if not exists
            if (emailConfigService.getAllConfigs().isEmpty()) {
                try {
                    emailConfigService.createDefaultConfig(
                        defaultSmtpHost, defaultSmtpPort, defaultSmtpUsername,
                        defaultSmtpPassword, defaultFromEmail, defaultFromName, defaultUseSsl
                    );
                    log.info("Saved default email config to database");
                } catch (Exception e) {
                    log.warn("Failed to save default email config to database: {}", e.getMessage());
                }
            }
        } else {
            log.warn("EmailSenderService initialized without SMTP configuration");
        }
    }

    /**
     * Check if SMTP is configured
     */
    public boolean isConfigured() {
        EmailConfig config = currentConfigRef.get();
        if (config != null && isValidConfig(config)) {
            return true;
        }
        return isDefaultConfigured();
    }

    /**
     * Get SMTP info (without password)
     */
    public Map<String, Object> getSmtpInfo() {
        Map<String, Object> info = new HashMap<>();
        EmailConfig config = currentConfigRef.get();
        
        if (config != null && isValidConfig(config)) {
            info.put("configured", true);
            info.put("id", config.getId());
            info.put("configName", config.getConfigName());
            info.put("host", config.getSmtpHost());
            info.put("port", config.getSmtpPort());
            info.put("username", config.getSmtpUsername());
            info.put("fromEmail", config.getFromEmail());
            info.put("fromName", config.getFromName());
            info.put("useSsl", config.getUseSsl());
            info.put("enabled", config.getEnabled());
            info.put("testStatus", config.getTestStatus());
            info.put("lastTestTime", config.getLastTestTime());
        } else if (isDefaultConfigured()) {
            info.put("configured", true);
            info.put("id", null);
            info.put("configName", "From application.yml");
            info.put("host", defaultSmtpHost);
            info.put("port", defaultSmtpPort);
            info.put("username", defaultSmtpUsername);
            info.put("fromEmail", defaultFromEmail);
            info.put("fromName", defaultFromName);
            info.put("useSsl", defaultUseSsl);
            info.put("enabled", true);
        } else {
            info.put("configured", false);
        }
        
        return info;
    }

    /**
     * Send email
     */
    public boolean sendEmail(String to, String subject, String content) {
        return sendEmail(to, subject, content, false);
    }

    /**
     * Send email with HTML option
     */
    public boolean sendEmail(String to, String subject, String content, boolean html) {
        JavaMailSender mailSender = mailSenderRef.get();
        EmailConfig config = currentConfigRef.get();
        
        if (mailSender == null || config == null) {
            log.error("SMTP is not configured");
            return false;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String fromEmail = config.getFromEmail() != null ? config.getFromEmail() : config.getSmtpUsername();
            String fromName = config.getFromName() != null ? config.getFromName() : "API Gateway";
            
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, html);

            mailSender.send(message);
            log.info("Email sent successfully to: {}", to);
            return true;

        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send email to multiple recipients
     */
    public boolean sendEmail(List<String> toList, String subject, String content, boolean html) {
        return sendEmailWithResult(toList, subject, content, html).isSuccess();
    }

    /**
     * Send email to multiple recipients with detailed result
     */
    public EmailSendResult sendEmailWithResult(List<String> toList, String subject, String content, boolean html) {
        JavaMailSender mailSender = mailSenderRef.get();
        EmailConfig config = currentConfigRef.get();
        
        if (mailSender == null || config == null) {
            String error = "SMTP is not configured";
            log.error(error);
            return EmailSendResult.failure(error);
        }

        if (toList == null || toList.isEmpty()) {
            String error = "No recipients provided";
            log.warn(error);
            return EmailSendResult.failure(error);
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String fromEmail = config.getFromEmail() != null ? config.getFromEmail() : config.getSmtpUsername();
            String fromName = config.getFromName() != null ? config.getFromName() : "API Gateway";
            
            helper.setFrom(fromEmail, fromName);
            helper.setTo(toList.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(content, html);

            mailSender.send(message);
            log.info("Email sent successfully to {} recipients", toList.size());
            return EmailSendResult.success();

        } catch (Exception e) {
            String error = e.getMessage();
            log.error("Failed to send email: {}", error, e);
            return EmailSendResult.failure(error);
        }
    }

    /**
     * Create JavaMailSender from EmailConfig
     */
    private JavaMailSender createMailSenderFromConfig(EmailConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getSmtpHost());
        sender.setPort(config.getSmtpPort());
        sender.setUsername(config.getSmtpUsername());
        sender.setPassword(config.getSmtpPassword());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");

        if (Boolean.TRUE.equals(config.getUseSsl())) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else if (Boolean.TRUE.equals(config.getUseStartTls())) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");

        return sender;
    }

    /**
     * Check if default config from application.yml is valid
     */
    private boolean isDefaultConfigured() {
        return defaultSmtpHost != null && !defaultSmtpHost.isEmpty()
            && defaultSmtpUsername != null && !defaultSmtpUsername.isEmpty()
            && defaultSmtpPassword != null && !defaultSmtpPassword.isEmpty();
    }

    /**
     * Check if EmailConfig is valid
     */
    private boolean isValidConfig(EmailConfig config) {
        return config.getSmtpHost() != null && !config.getSmtpHost().isEmpty()
            && config.getSmtpUsername() != null && !config.getSmtpUsername().isEmpty()
            && config.getSmtpPassword() != null && !config.getSmtpPassword().isEmpty();
    }

    /**
     * Create EmailConfig from application.yml values
     */
    private EmailConfig createDefaultConfig() {
        EmailConfig config = new EmailConfig();
        config.setSmtpHost(defaultSmtpHost);
        config.setSmtpPort(defaultSmtpPort);
        config.setSmtpUsername(defaultSmtpUsername);
        config.setSmtpPassword(defaultSmtpPassword);
        config.setFromEmail(defaultFromEmail);
        config.setFromName(defaultFromName);
        config.setUseSsl(defaultUseSsl);
        return config;
    }
}