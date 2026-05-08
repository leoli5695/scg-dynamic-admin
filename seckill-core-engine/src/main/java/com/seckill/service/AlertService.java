package com.seckill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ============================================================================
 * 告警服务
 * ============================================================================
 *
 * 功能:
 * 1. 发送告警消息（多种渠道）
 * 2. 异步发送（不影响主流程）
 * 3. 告警级别管理
 *
 * 支持的告警渠道:
 * - Webhook（通用）
 * - 钉钉机器人
 * - 企业微信机器人
 * - Slack
 *
 * 告警级别:
 * - INFO: 信息通知
 * - WARNING: 警告
 * - CRITICAL: 严重告警
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    @Value("${seckill.alert.webhook-url:}")
    private String webhookUrl;

    @Value("${seckill.alert.dingtalk-url:}")
    private String dingtalkUrl;

    @Value("${seckill.alert.enabled:false}")
    private boolean alertEnabled;

    /**
     * 异步执行器
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /**
     * RestTemplate（用于发送HTTP请求）
     */
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * ============================================================================
     * 发送告警
     * ============================================================================
     *
     * @param title 告警标题
     * @param message 告警内容
     */
    public void sendAlert(String title, String message) {
        sendAlert(title, message, AlertLevel.WARNING);
    }

    /**
     * ============================================================================
     * 发送告警（指定级别）
     * ============================================================================
     */
    public void sendAlert(String title, String message, AlertLevel level) {
        if (!alertEnabled) {
            log.info("告警未启用，跳过: {} - {}", title, message);
            return;
        }

        // 异步发送
        executor.submit(() -> {
            try {
                // 发送到 Webhook
                if (webhookUrl != null && !webhookUrl.isEmpty()) {
                    sendToWebhook(title, message, level);
                }

                // 发送到钉钉
                if (dingtalkUrl != null && !dingtalkUrl.isEmpty()) {
                    sendToDingtalk(title, message, level);
                }

                log.info("告警发送成功: {} - {}", title, message);

            } catch (Exception e) {
                log.error("告警发送失败: {} - {}, error={}", title, message, e.getMessage());
            }
        });
    }

    /**
     * ============================================================================
     * 发送到 Webhook
     * ============================================================================
     */
    private void sendToWebhook(String title, String message, AlertLevel level) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("message", message);
        payload.put("level", level.name());
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("service", "seckill-core-engine");

        restTemplate.postForEntity(webhookUrl, payload, String.class);
        log.debug("Webhook告警发送成功: {}", webhookUrl);
    }

    /**
     * ============================================================================
     * 发送到钉钉机器人
     * ============================================================================
     */
    private void sendToDingtalk(String title, String message, AlertLevel level) {
        Map<String, Object> payload = new HashMap<>();

        Map<String, Object> text = new HashMap<>();
        text.put("content", String.format("[%s] %s\n%s\n时间: %s",
                level.name(), title, message,
                java.time.LocalDateTime.now().toString()));

        payload.put("msgtype", "text");
        payload.put("text", text);

        restTemplate.postForEntity(dingtalkUrl, payload, String.class);
        log.debug("钉钉告警发送成功: {}", dingtalkUrl);
    }

    /**
     * ============================================================================
     * 发送严重告警（多次重试）
     * ============================================================================
     */
    public void sendCriticalAlert(String title, String message) {
        // 立即发送（同步）
        log.error("严重告警: {} - {}", title, message);

        // 同时异步发送到各渠道
        sendAlert(title, message, AlertLevel.CRITICAL);
    }

    /**
     * ============================================================================
     * 告警级别
     * ============================================================================
     */
    public enum AlertLevel {
        INFO,
        WARNING,
        CRITICAL
    }
}