package com.seckill.exception;

/**
 * Stock insufficient - 库存不足
 */
public class StockInsufficientException extends SeckillException {

    public static final int STOCK_INSUFFICIENT = -400;

    public StockInsufficientException(Long seckillId) {
        super(STOCK_INSUFFICIENT, "Stock insufficient: seckillId=" + seckillId);
    }

    public StockInsufficientException(Long seckillId, int requested, int available) {
        super(STOCK_INSUFFICIENT,
            "Stock insufficient: seckillId=" + seckillId +
            ", requested=" + requested + ", available=" + available);
    }
}