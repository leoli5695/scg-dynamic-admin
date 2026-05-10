package com.seckill.exception;

import lombok.Getter;

/**
 * ============================================================================
 * 秒杀异常基类
 * ============================================================================
 *
 * OPTIMIZATION (P2): Enhanced exception hierarchy with error codes
 *
 * Error code categories:
 * - -1xx: Redis failures
 * - -2xx: Database failures
 * - -3xx: MQ failures
 * - -4xx: Business errors (stock, activity, etc.)
 * - -99: Unknown error
 */
@Getter
public class SeckillException extends RuntimeException {

    private final int code;
    private final String message;
    private final String traceId;

    public SeckillException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.traceId = null;
    }

    public SeckillException(int code, String message, String traceId) {
        super(message);
        this.code = code;
        this.message = message;
        this.traceId = traceId;
    }

    public SeckillException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
        this.traceId = null;
    }

    public SeckillException(int code, String message, String traceId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
        this.traceId = traceId;
    }

    public SeckillException(String message) {
        super(message);
        this.code = -99;
        this.message = message;
        this.traceId = null;
    }

    /**
     * Get error category from code
     */
    public String getErrorCategory() {
        if (code >= -100 && code < -199) return "REDIS";
        if (code >= -200 && code < -299) return "DATABASE";
        if (code >= -300 && code < -399) return "MQ";
        if (code >= -400 && code < -499) return "BUSINESS";
        return "UNKNOWN";
    }
}