package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 库存预热响应DTO
 * ============================================================================
 * <p>
 * 提取自 WarmupController 内嵌类
 * 用于返回预热操作的执行结果
 */
@Data
public class WarmupResponse {
    private boolean success;
    private String message;
}