package com.seckill.exception;

/**
 * Product not found - 商品不存在
 */
public class ProductNotFoundException extends SeckillException {

    public static final int PRODUCT_NOT_FOUND = -405;

    public ProductNotFoundException(Long productId) {
        super(PRODUCT_NOT_FOUND, "Product not found: productId=" + productId);
    }
}