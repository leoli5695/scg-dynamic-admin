package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 库存预热状态响应DTO
 * ============================================================================
 * <p>
 * 提取自 WarmupController 内嵌类
 * 用于查询预热状态时返回结果
 */
@Data
public class WarmupStatusResponse {
    private Long seckillId;
    private boolean warmedUp;
}