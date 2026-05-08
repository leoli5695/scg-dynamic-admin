package com.leoli.gateway.trace.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Single Span record
 * 
 * Represents execution information of an operation unit
 * 
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceSpan {

    /**
     * Operation name
     * Examples: SeckillService.doSeckill, redis-execute, rocketmq-syncSend
     */
    private String operation;

    /**
     * 执行耗时（毫秒）
     */
    private long durationMs;

    /**
     * Success flag
     */
    private boolean success;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;

    /**
     * Additional metadata (optional)
     * Examples: Redis command type, MQ Topic name, SQL type, etc.
     */
    private Map<String, Object> metadata;

    /**
     * 简化构造函数
     */
    public ServiceSpan(String operation, long durationMs, boolean success) {
        this.operation = operation;
        this.durationMs = durationMs;
        this.success = success;
    }

    /**
     * Constructor with error message
     */
    public ServiceSpan(String operation, long durationMs, boolean success, String errorMessage) {
        this.operation = operation;
        this.durationMs = durationMs;
        this.success = success;
        this.errorMessage = errorMessage;
    }
}