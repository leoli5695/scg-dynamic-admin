package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.alert.AlertLevel;
import com.leoli.gateway.admin.alert.AlertNotifier;
import com.leoli.gateway.admin.dto.InstanceHealthDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Alert service.
 *
 * @author leoli
 */
@Service
@Slf4j
public class AlertService {

    @Autowired
    private List<AlertNotifier> notifiers;

    /**
     * Send alert via all enabled notifiers.
     */
    public void sendAlert(String title, String content, AlertLevel level) {
        for (AlertNotifier notifier : notifiers) {
            if (notifier.isSupported()) {
                try {
                    notifier.sendAlert(title, content, level);
                } catch (Exception e) {
                    log.error("Notifier {} failed to send alert",
                            notifier.getClass().getSimpleName(), e);
                }
            }
        }
    }

    /**
     * Send instance unhealthy alert.
     */
    public void sendInstanceUnhealthyAlert(InstanceHealthDTO health) {
        String title = String.format("Instance Unhealthy: %s:%d", health.getIp(), health.getPort());
        String content = buildUnhealthyContent(health);

        sendAlert(title, content, AlertLevel.ERROR);
    }

    /**
     * Build unhealthy alert content.
     */
    private String buildUnhealthyContent(InstanceHealthDTO health) {
        StringBuilder sb = new StringBuilder();
        sb.append("Service ID: ").append(health.getServiceId()).append("\n");
        sb.append("IP Address: ").append(health.getIp()).append("\n");
        sb.append("Port: ").append(health.getPort()).append("\n");

        if (health.getUnhealthyReason() != null) {
            sb.append("Reason: ").append(health.getUnhealthyReason()).append("\n");
        }

        sb.append("Failure Count: ").append(health.getConsecutiveFailures()).append("\n");
        sb.append("Check Type: ").append(health.getCheckType()).append("\n");

        if (health.getLastRequestTime() != null) {
            sb.append("Last Request: ").append(new java.util.Date(health.getLastRequestTime())).append("\n");
        }

        return sb.toString();
    }

    /**
     * Send critical alert (multiple instances unhealthy).
     */
    public void sendCriticalAlert(String serviceName, int unhealthyCount) {
        String title = String.format("[CRITICAL] Service %s has multiple unhealthy instances", serviceName);
        String content = String.format(
                "Service %s has %d instances marked as unhealthy. Please check immediately!\n\n" +
                        "Possible causes:\n" +
                        "1. Service overall failure\n" +
                        "2. Network issues\n" +
                        "3. Gateway configuration error",
                serviceName, unhealthyCount
        );

        sendAlert(title, content, AlertLevel.CRITICAL);
    }
}