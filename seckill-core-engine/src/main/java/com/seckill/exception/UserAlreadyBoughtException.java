package com.seckill.exception;

/**
 * User already bought - 用户已购买
 */
public class UserAlreadyBoughtException extends SeckillException {

    public static final int USER_ALREADY_BOUGHT = -401;

    public UserAlreadyBoughtException(Long seckillId, Long userId) {
        super(USER_ALREADY_BOUGHT,
            "User already bought: seckillId=" + seckillId + ", userId=" + userId);
    }
}