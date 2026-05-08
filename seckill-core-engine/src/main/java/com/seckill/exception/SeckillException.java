package com.seckill.exception;

import lombok.Getter;

/**
 * ============================================================================
 * 秒杀异常
 * ============================================================================
 */
@Getter
public class SeckillException extends RuntimeException {

    private final int code;
    private final String message;

    public SeckillException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public SeckillException(String message) {
        super(message);
        this.code = -99;
        this.message = message;
    }
}