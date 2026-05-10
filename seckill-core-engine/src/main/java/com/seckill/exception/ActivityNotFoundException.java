package com.seckill.exception;

/**
 * Activity not found - 活动不存在
 */
public class ActivityNotFoundException extends SeckillException {

    public static final int ACTIVITY_NOT_FOUND = -402;

    public ActivityNotFoundException(Long seckillId) {
        super(ACTIVITY_NOT_FOUND, "Activity not found: seckillId=" + seckillId);
    }
}