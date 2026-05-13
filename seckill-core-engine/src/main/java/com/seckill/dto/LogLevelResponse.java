package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 日志级别响应DTO
 * ============================================================================
 * <p>
 * 用于返回日志级别查询和修改操作的结果
 */
@Data
public class LogLevelResponse {
    /**
     * 包名/类名（解析后的完整名称）
     */
    private String packageName;

    /**
     * 当前日志级别
     */
    private String level;

    /**
     * 操作结果消息
     */
    private String message;
}