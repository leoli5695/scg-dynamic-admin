package com.seckill.exception;

/**
 * Stock not warmed up - 库存未预热
 */
public class StockNotWarmedException extends SeckillException {

    public static final int STOCK_NOT_WARMED = -406;

    public StockNotWarmedException(Long seckillId) {
        super(STOCK_NOT_WARMED, "Stock not warmed: seckillId=" + seckillId);
    }
}