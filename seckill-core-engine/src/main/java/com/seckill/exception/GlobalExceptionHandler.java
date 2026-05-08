package com.seckill.exception;

import com.seckill.dto.SeckillResponse;
import com.seckill.enums.SeckillResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ============================================================================
 * 全局异常处理器
 * ============================================================================
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理秒杀异常
     */
    @ExceptionHandler(SeckillException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleSeckillException(SeckillException e) {
        log.warn("秒杀异常: code={}, message={}", e.getCode(), e.getMessage());
        return SeckillResponse.systemError(e.getMessage());
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public SeckillResponse handleValidationException(org.springframework.web.bind.MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("参数错误");

        log.warn("参数校验失败: {}", message);
        return SeckillResponse.fail(SeckillResult.PARAM_ERROR);
    }

    /**
     * 处理其他异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public SeckillResponse handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return SeckillResponse.systemError("系统繁忙，请稍后再试");
    }
}