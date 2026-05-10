package com.seckill.exception;

/**
 * Activity ended - 活动已结束
 */
public class ActivityEndedException extends SeckillException {

    public static final int ACTIVITY_ENDED = -404;

    public ActivityEndedException(Long seckillId) {
        super(ACTIVITY_ENDED, "Activity ended: seckillId=" + seckillId);
    }
}