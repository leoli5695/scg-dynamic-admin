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
 *
 * OPTIMIZATION (P2): 精细化异常处理，按异常类型返回不同错误码
 *
 * Exception hierarchy:
 * - RedisFailureException (-1xx): Redis 连接/命令失败
 * - DatabaseFailureException (-2xx): 数据库访问失败
 * - MQFailureException (-3xx): RocketMQ 消息失败
 * - StockInsufficientException (-400): 库存不足
 * - UserAlreadyBoughtException (-401): 用户已购买
 * - ActivityNotFoundException (-402): 活动不存在
 * - ActivityNotStartedException (-403): 活动未开始
 * - ActivityEndedException (-404): 活动已结束
 * - ProductNotFoundException (-405): 商品不存在
 * - StockNotWarmedException (-406): 库存未预热
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ============================================================================
    // 业务异常处理（返回业务错误码）
    // ============================================================================

    /**
     * 库存不足
     */
    @ExceptionHandler(StockInsufficientException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleStockInsufficient(StockInsufficientException e) {
        log.warn("库存不足: {}", e.getMessage());
        return SeckillResponse.fail(SeckillResult.STOCK_INSUFFICIENT);
    }

    /**
     * 用户已购买
     */
    @ExceptionHandler(UserAlreadyBoughtException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleUserAlreadyBought(UserAlreadyBoughtException e) {
        log.warn("用户已购买: {}", e.getMessage());
        return SeckillResponse.fail(SeckillResult.ALREADY_BOUGHT);
    }

    /**
     * 活动不存在
     */
    @ExceptionHandler(ActivityNotFoundException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleActivityNotFound(ActivityNotFoundException e) {
        log.warn("活动不存在: {}", e.getMessage());
        return SeckillResponse.fail(SeckillResult.ACTIVITY_NOT_FOUND);
    }

    /**
     * 活动未开始
     */
    @ExceptionHandler(ActivityNotStartedException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleActivityNotStarted(ActivityNotStartedException e) {
        log.warn("活动未开始: {}", e.getMessage());
        return SeckillResponse.fail(SeckillResult.ACTIVITY_NOT_STARTED);
    }

    /**
     * 活动已结束
     */
    @ExceptionHandler(ActivityEndedException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleActivityEnded(ActivityEndedException e) {
        log.warn("活动已结束: {}", e.getMessage());
        return SeckillResponse.fail(SeckillResult.ACTIVITY_ENDED);
    }

    /**
     * 商品不存在
     * FIX: 保持与其他 NotFound 类一致，使用 SeckillResult.PRODUCT_NOT_FOUND
     */
    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleProductNotFound(ProductNotFoundException e) {
        log.warn("商品不存在: {}", e.getMessage());
        return SeckillResponse.fail(SeckillResult.PRODUCT_NOT_FOUND);
    }

    /**
     * 库存未预热
     */
    @ExceptionHandler(StockNotWarmedException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleStockNotWarmed(StockNotWarmedException e) {
        log.warn("库存未预热: {}", e.getMessage());
        return SeckillResponse.fail(SeckillResult.STOCK_NOT_WARMED);
    }

    // ============================================================================
    // 系统异常处理（返回系统错误）
    // ============================================================================

    /**
     * Redis 故障
     */
    @ExceptionHandler(RedisFailureException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleRedisFailure(RedisFailureException e) {
        log.error("Redis故障: code={}, message={}", e.getCode(), e.getMessage(), e.getCause());
        return SeckillResponse.systemError("系统繁忙，请稍后再试");
    }

    /**
     * 数据库故障
     */
    @ExceptionHandler(DatabaseFailureException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleDatabaseFailure(DatabaseFailureException e) {
        log.error("数据库故障: code={}, message={}", e.getCode(), e.getMessage(), e.getCause());
        return SeckillResponse.systemError("系统繁忙，请稍后再试");
    }

    /**
     * MQ 故障
     */
    @ExceptionHandler(MQFailureException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleMQFailure(MQFailureException e) {
        log.error("MQ故障: code={}, message={}", e.getCode(), e.getMessage(), e.getCause());
        return SeckillResponse.systemError("系统繁忙，请稍后再试");
    }

    // ============================================================================
    // 通用异常处理
    // ============================================================================

    /**
     * 处理秒杀异常（基类，兜底）
     */
    @ExceptionHandler(SeckillException.class)
    @ResponseStatus(HttpStatus.OK)
    public SeckillResponse handleSeckillException(SeckillException e) {
        log.warn("秒杀异常: code={}, category={}, message={}", 
                e.getCode(), e.getErrorCategory(), e.getMessage());
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