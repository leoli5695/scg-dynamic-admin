package com.seckill.controller;

import com.seckill.dto.AlertResponse;
import com.seckill.dto.AlertStatusResponse;
import com.seckill.service.AlertService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * ============================================================================
 * 告警管理 Controller
 * ============================================================================
 * <p>
 * 接口:
 * 1. POST /alert/send - 手动发送告警
 * 2. POST /alert/webhook - Webhook接收端（用于接收Prometheus Alertmanager告警）
 * 3. GET /alert/status - 查询告警服务状态
 * <p>
 * Webhook对接说明:
 * 1. Prometheus Alertmanager 配置 webhook receiver
 * 2. Alertmanager 发送告警到此接口
 * 3. 解析告警内容并转发到钉钉/企业微信
 */
@Slf4j
@RestController
@RequestMapping("/alert")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    /**
     * ============================================================================
     * 手动发送告警
     * ============================================================================
     */
    @PostMapping("/send")
    public AlertResponse sendAlert(@RequestBody AlertRequest request) {
        log.info("手动发送告警: title={}, message={}", request.getTitle(), request.getMessage());

        AlertService.AlertLevel level = parseLevel(request.getLevel());
        alertService.sendAlert(request.getTitle(), request.getMessage(), level);

        AlertResponse response = new AlertResponse();
        response.setCode("SUCCESS");
        response.setMessage("告警发送成功");
        return response;
    }

    /**
     * ============================================================================
     * Webhook接收端（Prometheus Alertmanager）
     * ============================================================================
     * <p>
     * Alertmanager Webhook 数据格式:
     * {
     * "status": "firing" | "resolved",
     * "alerts": [
     * {
     * "status": "firing",
     * "labels": {"alertname": "...", "severity": "..."},
     * "annotations": {"summary": "...", "description": "..."},
     * "startsAt": "...",
     * "endsAt": "..."
     * }
     * ]
     * }
     */
    @PostMapping("/webhook")
    public AlertResponse receiveWebhook(@RequestBody AlertmanagerWebhookPayload payload) {
        log.info("收到Alertmanager Webhook告警: status={}, count={}",
                payload.getStatus(), payload.getAlerts() != null ? payload.getAlerts().size() : 0);

        if (payload.getAlerts() == null || payload.getAlerts().isEmpty()) {
            AlertResponse response = new AlertResponse();
            response.setCode("SUCCESS");
            response.setMessage("无告警内容");
            return response;
        }

        // 处理每个告警
        for (AlertmanagerAlert alert : payload.getAlerts()) {
            String title = alert.getLabels() != null ?
                    alert.getLabels().get("alertname") : "未知告警";
            String message = alert.getAnnotations() != null ?
                    alert.getAnnotations().get("description") : "无描述";

            // 如果是恢复告警，标题添加[已恢复]
            if ("resolved".equals(payload.getStatus())) {
                title = "[已恢复] " + title;
            }

            AlertService.AlertLevel level = parseLevelFromLabel(
                    alert.getLabels() != null ? alert.getLabels().get("severity") : "warning");

            alertService.sendAlert(title, message != null ? message : "", level);
        }

        AlertResponse response = new AlertResponse();
        response.setCode("SUCCESS");
        response.setMessage("告警处理成功，共处理 " + payload.getAlerts().size() + " 条告警");
        return response;
    }

    /**
     * ============================================================================
     * 查询告警服务状态
     * ============================================================================
     */
    @GetMapping("/status")
    public AlertStatusResponse getStatus() {
        AlertStatusResponse response = new AlertStatusResponse();
        response.setStatus("RUNNING");
        response.setWebhookEnabled(true);
        return response;
    }

    /**
     * 解析告警级别
     */
    private AlertService.AlertLevel parseLevel(String level) {
        if (level == null) {
            return AlertService.AlertLevel.WARNING;
        }
        switch (level.toUpperCase()) {
            case "CRITICAL":
                return AlertService.AlertLevel.CRITICAL;
            case "INFO":
                return AlertService.AlertLevel.INFO;
            default:
                return AlertService.AlertLevel.WARNING;
        }
    }

    /**
     * 从 Alertmanager label 解析告警级别
     */
    private AlertService.AlertLevel parseLevelFromLabel(String severity) {
        if (severity == null) {
            return AlertService.AlertLevel.WARNING;
        }
        switch (severity.toLowerCase()) {
            case "critical":
                return AlertService.AlertLevel.CRITICAL;
            case "info":
                return AlertService.AlertLevel.INFO;
            default:
                return AlertService.AlertLevel.WARNING;
        }
    }

    /**
     * ============================================================================
     * 请求/响应 DTO（仅保留请求类，响应类已提取到dto包）
     * ============================================================================
     */
    @Data
    public static class AlertRequest {
        private String title;
        private String message;
        private String level;
    }

    /**
     * ============================================================================
     * Alertmanager Webhook Payload（Webhook专用请求结构）
     * ============================================================================
     */
    @Data
    public static class AlertmanagerWebhookPayload {
        private String status;
        private java.util.List<AlertmanagerAlert> alerts;
        private String groupKey;
        private String externalURL;
    }

    @Data
    public static class AlertmanagerAlert {
        private String status;
        private java.util.Map<String, String> labels;
        private java.util.Map<String, String> annotations;
        private String startsAt;
        private String endsAt;
        private String generatorURL;
    }
}