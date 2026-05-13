package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 告警响应DTO
 * ============================================================================
 * <p>
 * 提取自 AlertController 内嵌类
 * 用于统一管理告警相关的响应结构
 */
@Data
public class AlertResponse {
    private String code;
    private String message;
}