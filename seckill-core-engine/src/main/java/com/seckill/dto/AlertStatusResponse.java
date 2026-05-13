package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 告警状态响应DTO
 * ============================================================================
 * <p>
 * 提取自 AlertController 内嵌类
 * 用于返回告警服务的当前状态
 */
@Data
public class AlertStatusResponse {
    private String status;
    private boolean webhookEnabled;
}