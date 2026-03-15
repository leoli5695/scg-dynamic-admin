package com.example.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Alert service for gateway monitoring.
 * 
 * Current implementation: Logs alerts to application log
 * Production integration points (extend as needed):
 *   - Email notifications (JavaMail)
 *   - SMS notifications (Aliyun SMS, Tencent SMS)
 *   - Instant messaging (DingTalk, WeChat Work, Slack webhooks)
 *   - Monitoring systems (Prometheus, Grafana, Datadog)
 *   - APM tools (SkyWalking, Pinpoint, New Relic)
 * 
 * @author leoli
 */
@Slf4j
@Component
public class AlertService {

    /**
     * Send alert message.
     * Currently logs to application log only.
     * Extend this method in production to integrate with actual notification channels.
     * 
     * @param level Alert level (INFO, WARN, ERROR)
     * @param message Alert message
     */
    public void send(String level, String message) {
        // Log the alert - this is always available
        switch (level.toUpperCase()) {
            case "ERROR":
                log.error("🚨 ALERT [ERROR]: {}", message);
                break;
            case "WARN":
                log.warn("⚠️  ALERT [WARN]: {}", message);
                break;
            default:
                log.info("ℹ️  ALERT [INFO]: {}", message);
        }
        
        // TODO: Production integrations - uncomment and implement as needed
        // Example 1: Email notification
        // if (level.equals("ERROR")) {
        //     emailService.sendAlert(message);
        // }
        //
        // Example 2: DingTalk webhook
        // dingTalkService.send(message);
        //
        // Example 3: Prometheus metrics
        // metricsCounter.increment();
        //
        // Example 4: SkyWalking tracing
        // TracingSpan span = tracer.newSpan("alert");
        // span.tag("level", level);
        // span.tag("message", message);
    }
    
    /**
     * Send error level alert.
     */
    public void sendError(String message) {
        send("ERROR", message);
    }
    
    /**
     * Send warning level alert.
     */
    public void sendWarn(String message) {
        send("WARN", message);
    }
}
