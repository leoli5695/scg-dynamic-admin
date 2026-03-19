package com.leoli.gateway.admin.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Email alert notifier.
 *
 * @author leoli
 */
@Component
@Slf4j
public class EmailAlertNotifier implements AlertNotifier {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${gateway.health.alert.email.enabled:false}")
    private boolean enabled;

    @Value("${gateway.health.alert.email.to:}")
    private String toEmail;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Override
    public void sendAlert(String title, String content, AlertLevel level) {
        if (!enabled || mailSender == null || toEmail == null || toEmail.isEmpty()) {
            log.debug("Email alert disabled or not configured");
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();

            if (fromEmail != null && !fromEmail.isEmpty()) {
                message.setFrom(fromEmail);
            }

            message.setTo(toEmail);
            message.setSubject("[Gateway Alert] " + title);
            message.setText(buildEmailContent(title, content, level));
            message.setSentDate(new Date());

            mailSender.send(message);
            log.info("Sent email alert to: {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send email alert", e);
        }
    }

    private String buildEmailContent(String title, String content, AlertLevel level) {
        StringBuilder sb = new StringBuilder();
        sb.append("Alert Title: ").append(title).append("\n\n");
        sb.append("Alert Content:\n").append(content).append("\n\n");
        sb.append("Alert Level: ").append(level.getDescription()).append("\n\n");
        sb.append("Sent At: ").append(new Date()).append("\n");
        return sb.toString();
    }

    @Override
    public boolean isSupported() {
        return enabled && mailSender != null && toEmail != null && !toEmail.isEmpty();
    }
}