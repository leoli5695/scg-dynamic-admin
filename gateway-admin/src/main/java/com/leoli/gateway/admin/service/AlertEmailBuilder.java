package com.leoli.gateway.admin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Builds alert email HTML content and titles.
 */
@Slf4j
@Component
public class AlertEmailBuilder {

    public String buildAlertEmailBody(String alertType, String level, String metricName,
                                       double currentValue, double threshold, String analysis, String language) {
        String levelColor = "CRITICAL".equals(level) ? "#dc3545" : "#ffc107";
        String levelBgColor = "CRITICAL".equals(level) ? "#f8d7da" : "#fff3cd";
        String levelLabel = "CRITICAL".equals(level) ? ("zh".equals(language) ? "严重" : "CRITICAL") : ("zh".equals(language) ? "警告" : "WARNING");

        if ("zh".equals(language)) {
            return buildZhBody(levelBgColor, levelColor, levelLabel, alertType, metricName, currentValue, threshold, analysis);
        } else {
            return buildEnBody(levelBgColor, levelColor, levelLabel, alertType, metricName, currentValue, threshold, analysis);
        }
    }

    public String buildAlertTitle(String alertType, String level, String language) {
        String typeLabel = "zh".equals(language) ? getAlertTypeLabelZh(alertType) : getAlertTypeLabelEn(alertType);
        return "zh".equals(language)
                ? String.format("【%s】网关告警 - %s", level, typeLabel)
                : String.format("[%s] Gateway Alert - %s", level, typeLabel);
    }

    public String buildTestEmailBody(String language) {
        String title = "zh".equals(language) ? "网关告警通知测试" : "Gateway Alert Notification Test";
        String content = "zh".equals(language)
                ? "这是一封测试邮件，如果您收到此邮件，说明告警邮件配置正确。"
                : "This is a test email. If you received this, your alert email configuration is correct.";
        return String.format("""
                <!DOCTYPE html><html><head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; padding: 20px;">
                    <div style="max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #e8e8e8; border-radius: 8px;">
                        <h2 style="color: #1890ff;">%s</h2>
                        <p style="font-size: 14px; line-height: 1.6;">%s</p>
                        <p style="font-size: 12px; color: #666; margin-top: 20px;">Sent at: %s</p>
                    </div>
                </body></html>""", title, content, LocalDateTime.now());
    }

    private String buildZhBody(String bg, String color, String levelLabel, String alertType,
                                String metricName, double currentValue, double threshold, String analysis) {
        return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: 'Microsoft YaHei', Arial, sans-serif; line-height: 1.6; }
                        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                        .header { background: %s; padding: 15px; border-radius: 5px; margin-bottom: 20px; }
                        .header h1 { margin: 0; color: %s; }
                        .content { background: #f8f9fa; padding: 20px; border-radius: 5px; }
                        .metric { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #dee2e6; }
                        .metric-label { font-weight: bold; color: #495057; }
                        .metric-value { color: #212529; }
                        .analysis { margin-top: 20px; padding: 15px; background: white; border-left: 4px solid %s; }
                        .footer { margin-top: 20px; font-size: 12px; color: #6c757d; text-align: center; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header"><h1>网关告警通知 [%s]</h1></div>
                        <div class="content">
                            <div class="metric"><span class="metric-label">告警类型</span><span class="metric-value">%s</span></div>
                            <div class="metric"><span class="metric-label">告警指标</span><span class="metric-value">%s</span></div>
                            <div class="metric"><span class="metric-label">当前值</span><span class="metric-value" style="color: %s; font-weight: bold;">%.2f</span></div>
                            <div class="metric"><span class="metric-label">阈值</span><span class="metric-value">%.2f</span></div>
                            <div class="metric"><span class="metric-label">触发时间</span><span class="metric-value">%s</span></div>
                            <div class="analysis"><strong>分析与建议：</strong><br/>%s</div>
                        </div>
                        <div class="footer">此邮件由 Gateway Admin 系统自动发送，请勿回复。</div>
                    </div>
                </body></html>""",
                bg, color, color, levelLabel, getAlertTypeLabelZh(alertType),
                metricName, color, currentValue, threshold, LocalDateTime.now(), analysis);
    }

    private String buildEnBody(String bg, String color, String levelLabel, String alertType,
                                String metricName, double currentValue, double threshold, String analysis) {
        return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: Arial, sans-serif; line-height: 1.6; }
                        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                        .header { background: %s; padding: 15px; border-radius: 5px; margin-bottom: 20px; }
                        .header h1 { margin: 0; color: %s; }
                        .content { background: #f8f9fa; padding: 20px; border-radius: 5px; }
                        .metric { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #dee2e6; }
                        .metric-label { font-weight: bold; color: #495057; }
                        .metric-value { color: #212529; }
                        .analysis { margin-top: 20px; padding: 15px; background: white; border-left: 4px solid %s; }
                        .footer { margin-top: 20px; font-size: 12px; color: #6c757d; text-align: center; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header"><h1>Gateway Alert Notification [%s]</h1></div>
                        <div class="content">
                            <div class="metric"><span class="metric-label">Alert Type</span><span class="metric-value">%s</span></div>
                            <div class="metric"><span class="metric-label">Metric</span><span class="metric-value">%s</span></div>
                            <div class="metric"><span class="metric-label">Current Value</span><span class="metric-value" style="color: %s; font-weight: bold;">%.2f</span></div>
                            <div class="metric"><span class="metric-label">Threshold</span><span class="metric-value">%.2f</span></div>
                            <div class="metric"><span class="metric-label">Triggered At</span><span class="metric-value">%s</span></div>
                            <div class="analysis"><strong>Analysis & Recommendations:</strong><br/>%s</div>
                        </div>
                        <div class="footer">This email was automatically sent by Gateway Admin system. Please do not reply.</div>
                    </div>
                </body></html>""",
                bg, color, color, levelLabel, getAlertTypeLabelEn(alertType),
                metricName, color, currentValue, threshold, LocalDateTime.now(), analysis);
    }

    public String getAlertTypeLabelZh(String alertType) {
        return switch (alertType) {
            case "CPU_PROCESS" -> "进程CPU使用率";
            case "CPU_SYSTEM" -> "系统CPU使用率";
            case "MEMORY_HEAP" -> "堆内存使用率";
            case "HTTP_ERROR_RATE" -> "HTTP错误率";
            case "RESPONSE_TIME" -> "响应时间";
            case "THREAD_COUNT" -> "线程数";
            case "THREAD_USAGE" -> "线程使用率";
            case "INSTANCE_DOWN" -> "实例宕机";
            default -> alertType;
        };
    }

    public String getAlertTypeLabelEn(String alertType) {
        return switch (alertType) {
            case "CPU_PROCESS" -> "Process CPU Usage";
            case "CPU_SYSTEM" -> "System CPU Usage";
            case "MEMORY_HEAP" -> "Heap Memory Usage";
            case "HTTP_ERROR_RATE" -> "HTTP Error Rate";
            case "RESPONSE_TIME" -> "Response Time";
            case "THREAD_COUNT" -> "Thread Count";
            case "THREAD_USAGE" -> "Thread Usage";
            case "INSTANCE_DOWN" -> "Instance Down";
            default -> alertType;
        };
    }
}
