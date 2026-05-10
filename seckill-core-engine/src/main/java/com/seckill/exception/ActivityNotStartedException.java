package com.seckill.exception;

/**
 * Activity not started - 活动未开始
 */
public class ActivityNotStartedException extends SeckillException {

    public static final int ACTIVITY_NOT_STARTED = -403;

    public ActivityNotStartedException(Long seckillId) {
        super(ACTIVITY_NOT_STARTED, "Activity not started: seckillId=" + seckillId);
    }
}